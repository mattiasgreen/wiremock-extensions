/**
 * Async Queues & Topics Explorer Controller (ADR 0025).
 * Provides cross-protocol destination browsing, non-destructive message peeking,
 * real-time corpus-based searching across headers/bodies, Polyglot Payload Lens inspection,
 * compensating edits, declarative JSON broker configuration with protocol templates,
 * and safe destination purging.
 * Zero external dependencies.
 */
import { state } from './state.js';
import { escapeHtml, formatBytes } from './dom.js';
import { evaluateQuery, extractSearchableText } from './filter.js';
import {
  openPayloadLens,
  detectFormat,
  detectCharset,
  discoverEmbeddedPayloads,
  renderFormattedWithNestedPreviews
} from './payload-lens.js';
import { highlightPayload, formatPayload } from './highlighter.js';
import { doFetch } from './api.js';

let pollTimer = null;

export const PROTOCOL_TEMPLATES = {
  'amqp-local': {
    id: 'local-artemis',
    protocol: 'amqp',
    name: 'ActiveMQ Artemis (Local)',
    endpoint: '127.0.0.1:61616',
    properties: {
      'amqp.protocol': '1.0',
      'client.id': 'wm-artemis-client',
      'amqp.idleTimeout': '30000'
    }
  },
  'amqp-k8s': {
    id: 'artemis-k8s',
    protocol: 'amqp',
    name: 'Artemis Cluster (K8s Secret)',
    endpoint: '${ARTEMIS_HOST}:5672',
    properties: {
      'amqp.protocol': '1.0',
      'username': '${AMQP_USER}',
      'password': '${AMQP_PASSWORD}',
      'client.id': 'wiremock-pod'
    }
  },
  'kafka-local': {
    id: 'local-kafka',
    protocol: 'kafka',
    name: 'Kafka Wire Sink (Local)',
    endpoint: 'localhost:9092',
    properties: {
      'client.id': 'wm-kafka-sink',
      'acks': 'all'
    }
  },
  'kafka-sasl': {
    id: 'corp-kafka-sasl',
    protocol: 'kafka',
    name: 'Corporate Kafka (SASL SCRAM)',
    endpoint: '${KAFKA_BOOTSTRAP_SERVERS}',
    properties: {
      'security.protocol': 'SASL_PLAINTEXT',
      'sasl.mechanism': 'SCRAM-SHA-512',
      'sasl.jaas.config': 'org.apache.kafka.common.security.scram.ScramLoginModule required username="${KAFKA_USERNAME}" password="${KAFKA_PASSWORD}";',
      'client.id': 'wiremock-async-engine'
    }
  },
  'casual-gateway': {
    id: 'casual-domain-gateway',
    protocol: 'casual',
    name: 'Casual XATMI Domain Gateway',
    endpoint: '127.0.0.1:7772',
    properties: {
      'domain.id': 'finance-xatmi',
      'casual.timeout.ms': '5000'
    }
  }
};

/**
 * Filters destinations list by protocol filter and channel search query.
 */
export function filterDestinations(destinations, { protocolFilter = 'all', searchQuery = '' } = {}) {
  if (!Array.isArray(destinations)) return [];
  const query = (searchQuery || '').trim().toLowerCase();
  const proto = (protocolFilter || 'all').toLowerCase();

  return destinations.filter(dest => {
    if (proto !== 'all' && dest.protocol.toLowerCase() !== proto) {
      return false;
    }
    if (query) {
      const corpus = `${dest.name} ${dest.protocol} ${dest.brokerId || ''}`.toLowerCase();
      if (!corpus.includes(query)) return false;
    }
    return true;
  });
}

/**
 * Evaluates real-time multi-token boolean query against a message corpus.
 * Searches across message key, correlation ID, reply-to, transport headers, and body payload text.
 */
export function filterMessages(messages, query) {
  if (!Array.isArray(messages)) return [];
  if (!query || !query.trim()) return messages;

  return messages.filter(msg => {
    const tokens = [];
    if (msg.id) tokens.push(msg.id);
    if (msg.key) tokens.push(msg.key);
    if (msg.correlationId) tokens.push(msg.correlationId);
    if (msg.replyTo) tokens.push(msg.replyTo);
    if (msg.protocol) tokens.push(msg.protocol);
    if (msg.destination) tokens.push(msg.destination);

    if (msg.headers && typeof msg.headers === 'object') {
      for (const [k, v] of Object.entries(msg.headers)) {
        tokens.push(k);
        tokens.push(String(v));
      }
    }

    if (msg.payload) {
      tokens.push(extractSearchableText(msg.payload));
    }

    const corpus = tokens.join(' ').toLowerCase();
    return evaluateQuery(query, term => corpus.includes(term));
  });
}

/**
 * Builds table row HTML for a single message in the queue table.
 */
export function buildMessageRowHtml(msg, isExpanded = false) {
  const safeId = escapeHtml(msg.id);
  const proto = (msg.protocol || 'amqp').toUpperCase();
  const keyPart = msg.key ? `${msg.key}${msg.partition !== null && msg.partition !== undefined ? ' (' + msg.partition + ')' : ''}` : '-';
  const corr = msg.correlationId || '-';
  const size = formatBytes(msg.size || (msg.payload ? msg.payload.length : 0));
  const timeStr = msg.timestamp ? new Date(msg.timestamp).toLocaleTimeString() : '-';
  const preview = (msg.payload || '').substring(0, 80).replace(/\s+/g, ' ');

  return `
    <tr class="queues-message-row ${isExpanded ? 'expanded' : ''}" data-msg-id="${safeId}" data-testid="queues-message-row" style="cursor: pointer;">
      <td class="queues-cell-chevron">${isExpanded ? '▼' : '▶'}</td>
      <td class="queues-cell-time">${timeStr}</td>
      <td class="queues-cell-proto"><span class="protocol-badge protocol-${msg.protocol.toLowerCase()} badge-${proto}">${proto}</span></td>
      <td class="queues-cell-key" title="${escapeHtml(keyPart)}">${escapeHtml(keyPart)}</td>
      <td class="queues-cell-corr" title="${escapeHtml(corr)}">${escapeHtml(corr)}</td>
      <td class="queues-cell-size">${size}</td>
      <td class="queues-cell-preview" title="${escapeHtml(msg.payload || '')}"><code>${escapeHtml(preview)}</code></td>
      <td class="queues-cell-actions">
        <button type="button" class="btn btn-tiny btn-secondary btn-msg-lens" data-msg-id="${safeId}" data-testid="btn-msg-lens" title="Inspect in Polyglot Payload Lens (Viewer Mode)">🔍 Lens</button>
        <button type="button" class="btn btn-tiny btn-danger btn-msg-delete" data-msg-id="${safeId}" data-testid="btn-msg-delete" title="Delete message">🗑️</button>
      </td>
    </tr>
  `;
}

/**
 * Builds expanded inline drawer HTML for message metadata and action triggers.
 * Renders nested polyglot payloads directly inline matching screenshot specifications.
 */
export function buildMessageDrawerHtml(msg) {
  const safeId = escapeHtml(msg.id);
  const headers = msg.headers || {};
  const headerRows = Object.entries(headers).map(([k, v]) => `
    <tr><td style="font-weight:600; color:var(--text-muted); width: 140px; padding: 2px 6px 2px 0;">${escapeHtml(k)}:</td><td style="font-family:monospace; padding: 2px 0;">${escapeHtml(v)}</td></tr>
  `).join('');

  const rawPayload = msg.payload || '';
  const discovered = rawPayload ? discoverEmbeddedPayloads(rawPayload) : [];
  let payloadHtml = '';

  if (rawPayload) {
    if (discovered.length > 0) {
      payloadHtml = renderFormattedWithNestedPreviews(
        rawPayload,
        discovered,
        `queue_msg_${msg.id}`,
        async (newPayload) => {
          const currentDest = (state.queuesDestinations || []).find(d => d.id === state.queuesSelectedDestinationId);
          if (currentDest) {
            const updatedMsg = { ...msg, payload: newPayload };
            await publishMessage(currentDest.protocol, currentDest.name, updatedMsg);
            await refreshQueues();
          }
        },
        null,
        rawPayload,
        {
          editable: true,
          readOnlyReason: 'Use "Open in Polyglot Lens" or "Edit & Re-publish" to modify',
          context: 'queue-message'
        }
      );
    } else {
      payloadHtml = highlightPayload(formatPayload(rawPayload));
    }
  } else {
    payloadHtml = '<span style="color: var(--text-muted);">(empty)</span>';
  }

  return `
    <tr class="queues-drawer-row" data-drawer-for="${safeId}" data-testid="queues-drawer-row">
      <td colspan="8">
        <div class="queues-drawer-inner" style="padding: 14px 18px; background: rgba(255,255,255,0.02); border-bottom: 1px solid var(--border-color);">
          <div style="display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 12px;">
            <div>
              <span style="font-weight: 600; font-size: 0.9rem;">Message Details:</span>
              <code style="margin-left: 8px; font-size: 0.8rem; color: var(--text-muted);">${safeId}</code>
              ${msg.replyTo ? `<span style="margin-left: 12px; font-size: 0.8rem;">Reply-To: <code>${escapeHtml(msg.replyTo)}</code></span>` : ''}
            </div>
            <div style="display: flex; gap: 8px;">
              <button type="button" class="btn btn-small btn-primary btn-drawer-lens" data-msg-id="${safeId}" data-testid="btn-drawer-lens">🔍 Open in Polyglot Lens</button>
              <button type="button" class="btn btn-small btn-secondary btn-drawer-edit" data-msg-id="${safeId}" data-testid="btn-drawer-edit">✏️ Edit & Re-publish</button>
              <button type="button" class="btn btn-small btn-danger btn-drawer-delete" data-msg-id="${safeId}" data-testid="btn-drawer-delete">🗑️ Delete</button>
            </div>
          </div>

          ${headerRows ? `
            <div style="margin-bottom: 12px;">
              <div style="font-size: 0.78rem; font-weight: 600; text-transform: uppercase; color: var(--text-muted); margin-bottom: 4px;">Headers</div>
              <table style="font-size: 0.82rem; margin: 0; background: transparent; width: 100%;"><tbody>${headerRows}</tbody></table>
            </div>
          ` : ''}

          <div>
            <div style="font-size: 0.78rem; font-weight: 600; text-transform: uppercase; color: var(--text-muted); margin-bottom: 6px;">Payload Preview</div>
            <pre class="code-block code-block-payload" style="margin: 0; padding: 12px 16px; background: rgba(0,0,0,0.3); border-radius: 4px; max-height: 280px; overflow-y: auto; font-size: 0.82rem; font-family: monospace;"><code>${payloadHtml}</code></pre>
          </div>
        </div>
      </td>
    </tr>
  `;
}

/**
 * Fetches all destinations from the admin API.
 */
export async function fetchDestinations() {
  try {
    const res = await doFetch('/__admin/async/destinations');
    if (!res.ok) return [];
    const data = await res.json();
    return data.destinations || [];
  } catch (err) {
    return [];
  }
}

/**
 * Fetches peeked messages for the specified destination.
 */
export async function fetchMessages(protocol, destination) {
  try {
    const res = await doFetch(`/__admin/async/destinations/${encodeURIComponent(protocol)}/${encodeURIComponent(destination)}/messages`);
    if (!res.ok) return [];
    const data = await res.json();
    return data.messages || [];
  } catch (err) {
    return [];
  }
}

/**
 * Deletes single message by ID.
 */
export async function deleteMessage(protocol, destination, messageId) {
  try {
    const res = await doFetch(`/__admin/async/destinations/${encodeURIComponent(protocol)}/${encodeURIComponent(destination)}/messages/${encodeURIComponent(messageId)}`, {
      method: 'DELETE'
    });
    return res.status === 204 || res.ok;
  } catch (err) {
    return false;
  }
}

/**
 * Purges all messages from destination.
 */
export async function purgeDestination(protocol, destination) {
  try {
    const res = await doFetch(`/__admin/async/destinations/${encodeURIComponent(protocol)}/${encodeURIComponent(destination)}`, {
      method: 'DELETE'
    });
    return res.status === 204 || res.ok;
  } catch (err) {
    return false;
  }
}

/**
 * Publishes or executes compensating edit on destination.
 */
export async function publishMessage(protocol, destination, messageObj) {
  try {
    const res = await doFetch(`/__admin/async/destinations/${encodeURIComponent(protocol)}/${encodeURIComponent(destination)}/messages`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(messageObj)
    });
    return res.status === 201 || res.ok;
  } catch (err) {
    return false;
  }
}

/**
 * Fetches registered broker connections from the admin API.
 */
export async function fetchConnections() {
  try {
    const res = await doFetch('/__admin/async/connections');
    if (!res.ok) return [];
    const data = await res.json();
    return data.connections || [];
  } catch (err) {
    return [];
  }
}

/**
 * Registers one or more broker connections via JSON descriptor or envelope.
 */
export async function registerConnection(connPayload) {
  try {
    const res = await doFetch('/__admin/async/connections', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(connPayload)
    });
    return res.status === 201 || res.ok;
  } catch (err) {
    return false;
  }
}

/**
 * Deletes a registered broker connection by ID.
 */
export async function deleteConnection(id) {
  try {
    const res = await doFetch(`/__admin/async/connections/${encodeURIComponent(id)}`, {
      method: 'DELETE'
    });
    return res.status === 204 || res.ok;
  } catch (err) {
    return false;
  }
}

/**
 * Opens the Broker Connections modal.
 */
export async function openBrokersModal() {
  const modal = document.getElementById('modal-queues-brokers');
  if (!modal) return;
  const feedbackEl = document.getElementById('queues-broker-feedback');
  if (feedbackEl) {
    feedbackEl.textContent = '';
    feedbackEl.classList.add('hidden');
  }
  const conns = await fetchConnections();
  state.queuesBrokerConnections = conns;
  renderBrokersModal(conns);
  modal.classList.remove('hidden');
}

/**
 * Closes the Broker Connections modal.
 */
export function closeBrokersModal() {
  const modal = document.getElementById('modal-queues-brokers');
  if (modal) modal.classList.add('hidden');
}

/**
 * Renders the Broker Connections modal table and live JSON configuration.
 */
export function renderBrokersModal(connections) {
  const tbody = document.getElementById('queues-brokers-tbody');
  const countEl = document.getElementById('queues-brokers-count');
  const jsonEl = document.getElementById('queues-brokers-export-json');

  if (countEl) {
    countEl.textContent = `${connections.length} registered`;
  }

  if (jsonEl) {
    jsonEl.textContent = JSON.stringify({ connections }, null, 2);
  }

  if (!tbody) return;

  if (connections.length === 0) {
    tbody.innerHTML = `<tr><td colspan="7" style="padding: 24px; text-align: center; color: var(--text-muted);">No broker connections registered. Use template editor below to add one.</td></tr>`;
    return;
  }

  tbody.innerHTML = connections.map(c => {
    const safeId = escapeHtml(c.id);
    const proto = (c.protocol || 'kafka').toUpperCase();
    const propsList = c.properties && Object.keys(c.properties).length > 0
      ? Object.entries(c.properties).map(([k, v]) => `<code>${escapeHtml(k)}=${escapeHtml(v)}</code>`).join(', ')
      : '<span style="color: var(--text-muted);">(none)</span>';

    return `
      <tr data-conn-id="${safeId}">
        <td style="padding: 6px 10px; font-weight: 600;"><code>${safeId}</code></td>
        <td style="padding: 6px 10px;"><span class="protocol-badge protocol-${c.protocol.toLowerCase()} badge-${proto}">${proto}</span></td>
        <td style="padding: 6px 10px;">${escapeHtml(c.name || safeId)}</td>
        <td style="padding: 6px 10px; font-family: monospace; font-size: 0.8rem;">${escapeHtml(c.endpoint || '')}</td>
        <td style="padding: 6px 10px;"><span class="badge badge-success" style="font-size: 0.72rem; padding: 2px 6px;">${escapeHtml(c.status || 'CONFIGURED')}</span></td>
        <td style="padding: 6px 10px; font-size: 0.78rem; max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;" title="${escapeHtml(JSON.stringify(c.properties || {}))}">${propsList}</td>
        <td style="padding: 6px 10px; text-align: center;">
          <button type="button" class="btn btn-tiny btn-danger btn-delete-broker-conn" data-conn-id="${safeId}" title="Unregister broker connection">🗑️</button>
        </td>
      </tr>
    `;
  }).join('');
}

/**
 * Renders the destination list in the sidebar.
 */
export function renderDestinations() {
  const container = document.getElementById('queues-destination-list');
  if (!container) return;

  const filtered = filterDestinations(state.queuesDestinations, {
    protocolFilter: state.queuesProtocolFilter,
    searchQuery: state.queuesChannelSearchQuery
  });

  if (filtered.length === 0) {
    container.innerHTML = `<div class="queues-empty-destinations" style="padding: 16px; text-align: center; color: var(--text-muted); font-size: 0.85rem;">No matching queues or topics found.</div>`;
    return;
  }

  container.innerHTML = filtered.map(d => {
    const isSelected = d.id === state.queuesSelectedDestinationId;
    const proto = d.protocol.toUpperCase();
    return `
      <div class="queues-dest-item ${isSelected ? 'active' : ''}" data-dest-id="${escapeHtml(d.id)}" data-dest-name="${escapeHtml(d.name)}" data-dest-proto="${escapeHtml(d.protocol)}" data-testid="queues-dest-item">
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span style="font-weight: 500; font-size: 0.88rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">${escapeHtml(d.name)}</span>
          <span class="nav-badge" style="margin-left: 6px;">${d.messageCount}</span>
        </div>
        <div style="display: flex; justify-content: space-between; align-items: center; margin-top: 4px; font-size: 0.75rem;">
          <span class="protocol-badge protocol-${d.protocol.toLowerCase()} badge-${proto}" style="font-size: 0.7rem; padding: 1px 5px;">${proto}</span>
          <span style="color: var(--text-muted);">${escapeHtml(d.brokerId || 'local')}</span>
        </div>
      </div>
    `;
  }).join('');
}

/**
 * Renders the peeked messages table.
 */
export function renderMessages() {
  const tbody = document.getElementById('queues-messages-tbody');
  if (!tbody) return;

  const currentDest = (state.queuesDestinations || []).find(d => d.id === state.queuesSelectedDestinationId);
  const headerTitle = document.getElementById('queues-selected-dest-title');
  const countBadge = document.getElementById('queues-selected-dest-count');
  const brokerBadge = document.getElementById('queues-selected-dest-broker');

  if (currentDest) {
    if (headerTitle) headerTitle.textContent = currentDest.name;
    if (countBadge) countBadge.textContent = `${state.queuesMessages.length} messages`;
    if (brokerBadge) {
      if (currentDest.brokerId) {
        brokerBadge.textContent = `Broker: ${currentDest.brokerId} (${currentDest.protocol.toUpperCase()})`;
        brokerBadge.classList.remove('hidden');
      } else {
        brokerBadge.classList.add('hidden');
      }
    }
  } else {
    if (headerTitle) headerTitle.textContent = 'Select a Queue or Topic';
    if (countBadge) countBadge.textContent = '';
    if (brokerBadge) brokerBadge.classList.add('hidden');
  }

  if (!currentDest) {
    tbody.innerHTML = `<tr><td colspan="8" style="padding: 32px; text-align: center; color: var(--text-muted);">Select a destination from the sidebar to inspect messages.</td></tr>`;
    return;
  }

  const filtered = filterMessages(state.queuesMessages, state.queuesSearchQuery);
  if (filtered.length === 0) {
    tbody.innerHTML = `<tr><td colspan="8" style="padding: 32px; text-align: center; color: var(--text-muted);">No messages found in this destination.</td></tr>`;
    return;
  }

  let html = '';
  for (const msg of filtered) {
    const isExpanded = msg.id === state.queuesSelectedMessageId;
    html += buildMessageRowHtml(msg, isExpanded);
    if (isExpanded) {
      html += buildMessageDrawerHtml(msg);
    }
  }
  tbody.innerHTML = html;
}

/**
 * Refreshes current destinations and messages.
 */
export async function refreshQueues() {
  const destinations = await fetchDestinations();
  state.queuesDestinations = destinations;

  if (destinations.length > 0 && !state.queuesSelectedDestinationId) {
    state.queuesSelectedDestinationId = destinations[0].id;
  }

  const currentDest = destinations.find(d => d.id === state.queuesSelectedDestinationId);
  if (currentDest) {
    state.queuesMessages = await fetchMessages(currentDest.protocol, currentDest.name);
  } else {
    state.queuesMessages = [];
  }

  renderDestinations();
  renderMessages();
}

/**
 * Initializes the Queues Explorer event listeners and DOM wires.
 */
export function initQueues() {
  // Destination item selection
  const destContainer = document.getElementById('queues-destination-list');
  if (destContainer) {
    destContainer.addEventListener('click', async e => {
      const item = e.target.closest('.queues-dest-item');
      if (!item) return;
      const destId = item.dataset.destId;
      if (destId && destId !== state.queuesSelectedDestinationId) {
        state.queuesSelectedDestinationId = destId;
        state.queuesSelectedMessageId = null;
        const currentDest = (state.queuesDestinations || []).find(d => d.id === destId);
        if (currentDest) {
          state.queuesMessages = await fetchMessages(currentDest.protocol, currentDest.name);
        } else {
          state.queuesMessages = [];
        }
        renderDestinations();
        renderMessages();
      }
    });
  }

  // Sidebar Channel Filter
  const channelSearch = document.getElementById('queues-channel-search');
  if (channelSearch) {
    channelSearch.addEventListener('input', () => {
      state.queuesChannelSearchQuery = channelSearch.value;
      renderDestinations();
    });
  }

  // Protocol Filter Pills
  const filterPills = document.querySelectorAll('.queues-filter-pill');
  filterPills.forEach(pill => {
    pill.addEventListener('click', () => {
      filterPills.forEach(p => p.classList.remove('active'));
      pill.classList.add('active');
      state.queuesProtocolFilter = pill.dataset.filter || 'all';
      renderDestinations();
    });
  });

  // Message Search Input
  const messageSearch = document.getElementById('queues-message-search');
  if (messageSearch) {
    messageSearch.addEventListener('input', () => {
      state.queuesSearchQuery = messageSearch.value;
      renderMessages();
    });
  }

  // Refresh Button
  const btnRefresh = document.getElementById('btn-queues-refresh');
  if (btnRefresh) {
    btnRefresh.addEventListener('click', () => refreshQueues());
  }

  // Auto Poll Toggle Button
  const btnAutoPoll = document.getElementById('btn-queues-auto-poll');
  if (btnAutoPoll) {
    btnAutoPoll.addEventListener('click', () => {
      state.queuesAutoPoll = !state.queuesAutoPoll;
      btnAutoPoll.classList.toggle('active', state.queuesAutoPoll);
      if (state.queuesAutoPoll) {
        btnAutoPoll.textContent = '⏱️ Auto (ON)';
        pollTimer = setInterval(refreshQueues, 3000);
      } else {
        btnAutoPoll.textContent = '⏱️ Auto (3s)';
        if (pollTimer) clearInterval(pollTimer);
        pollTimer = null;
      }
    });
  }

  // Purge Modal Wires
  const btnPurge = document.getElementById('btn-queues-purge');
  const modalPurge = document.getElementById('modal-queues-purge');
  const btnCancelPurge = document.getElementById('btn-cancel-queues-purge');
  const btnConfirmPurge = document.getElementById('btn-confirm-queues-purge');

  if (btnPurge && modalPurge) {
    btnPurge.addEventListener('click', () => {
      const currentDest = (state.queuesDestinations || []).find(d => d.id === state.queuesSelectedDestinationId);
      if (!currentDest) return;
      const nameEl = document.getElementById('queues-purge-target-name');
      if (nameEl) nameEl.textContent = `${currentDest.name} (${currentDest.protocol.toUpperCase()})`;
      modalPurge.classList.remove('hidden');
    });
  }

  if (btnCancelPurge && modalPurge) {
    btnCancelPurge.addEventListener('click', () => {
      modalPurge.classList.add('hidden');
    });
  }

  if (btnConfirmPurge && modalPurge) {
    btnConfirmPurge.addEventListener('click', async () => {
      const currentDest = (state.queuesDestinations || []).find(d => d.id === state.queuesSelectedDestinationId);
      if (currentDest) {
        await purgeDestination(currentDest.protocol, currentDest.name);
        modalPurge.classList.add('hidden');
        await refreshQueues();
      }
    });
  }

  // Broker Modal Wires
  const btnSidebarBrokers = document.getElementById('btn-queues-brokers');
  const btnHeaderBrokers = document.getElementById('btn-queues-manage-brokers');
  const btnCloseBrokers = document.getElementById('btn-close-queues-brokers');
  const btnCloseBrokersFooter = document.getElementById('btn-close-queues-brokers-footer');
  const templateSelect = document.getElementById('queues-broker-template-select');
  const jsonEditor = document.getElementById('queues-broker-json-editor');
  const btnPrettify = document.getElementById('btn-queues-prettify-json');
  const btnSubmitJson = document.getElementById('btn-queues-submit-json');
  const feedbackEl = document.getElementById('queues-broker-feedback');
  const btnCopyJson = document.getElementById('btn-queues-copy-json');
  const btnDownloadJson = document.getElementById('btn-queues-download-json');

  if (btnSidebarBrokers) {
    btnSidebarBrokers.addEventListener('click', () => openBrokersModal());
  }
  if (btnHeaderBrokers) {
    btnHeaderBrokers.addEventListener('click', () => openBrokersModal());
  }
  if (btnCloseBrokers) {
    btnCloseBrokers.addEventListener('click', () => closeBrokersModal());
  }
  if (btnCloseBrokersFooter) {
    btnCloseBrokersFooter.addEventListener('click', () => closeBrokersModal());
  }

  // Template select change -> load example JSON
  if (templateSelect && jsonEditor) {
    templateSelect.addEventListener('change', () => {
      const key = templateSelect.value;
      if (key && PROTOCOL_TEMPLATES[key]) {
        jsonEditor.value = JSON.stringify(PROTOCOL_TEMPLATES[key], null, 2);
        if (feedbackEl) feedbackEl.classList.add('hidden');
      }
    });
  }

  // Prettify JSON button
  if (btnPrettify && jsonEditor) {
    btnPrettify.addEventListener('click', () => {
      try {
        const val = jsonEditor.value.trim();
        if (!val) return;
        const parsed = JSON.parse(val);
        jsonEditor.value = JSON.stringify(parsed, null, 2);
        if (feedbackEl) {
          feedbackEl.textContent = '✓ JSON is valid and formatted';
          feedbackEl.className = 'text-small text-success';
          feedbackEl.classList.remove('hidden');
        }
      } catch (err) {
        if (feedbackEl) {
          feedbackEl.textContent = `❌ Invalid JSON: ${err.message}`;
          feedbackEl.className = 'text-small text-danger';
          feedbackEl.classList.remove('hidden');
        }
      }
    });
  }

  // Submit / Register JSON button
  if (btnSubmitJson && jsonEditor) {
    btnSubmitJson.addEventListener('click', async () => {
      try {
        const val = jsonEditor.value.trim();
        if (!val) {
          if (feedbackEl) {
            feedbackEl.textContent = '❌ Please enter or load JSON connection configuration';
            feedbackEl.className = 'text-small text-danger';
            feedbackEl.classList.remove('hidden');
          }
          return;
        }
        const parsed = JSON.parse(val);
        const ok = await registerConnection(parsed);
        if (ok) {
          const conns = await fetchConnections();
          state.queuesBrokerConnections = conns;
          renderBrokersModal(conns);
          if (feedbackEl) {
            feedbackEl.textContent = '✓ Connection(s) successfully registered!';
            feedbackEl.className = 'text-small text-success';
            feedbackEl.classList.remove('hidden');
          }
          await refreshQueues();
        } else {
          if (feedbackEl) {
            feedbackEl.textContent = '❌ Registration failed (check required field: id)';
            feedbackEl.className = 'text-small text-danger';
            feedbackEl.classList.remove('hidden');
          }
        }
      } catch (err) {
        if (feedbackEl) {
          feedbackEl.textContent = `❌ JSON parse error: ${err.message}`;
          feedbackEl.className = 'text-small text-danger';
          feedbackEl.classList.remove('hidden');
        }
      }
    });
  }

  // Copy JSON export
  if (btnCopyJson) {
    btnCopyJson.addEventListener('click', () => {
      const jsonEl = document.getElementById('queues-brokers-export-json');
      if (jsonEl && navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(jsonEl.textContent).then(() => {
          btnCopyJson.textContent = '✓ Copied';
          setTimeout(() => { btnCopyJson.textContent = '📋 Copy JSON'; }, 1500);
        }).catch(() => {});
      }
    });
  }

  // Download JSON export
  if (btnDownloadJson) {
    btnDownloadJson.addEventListener('click', () => {
      const jsonEl = document.getElementById('queues-brokers-export-json');
      if (!jsonEl) return;
      const blob = new Blob([jsonEl.textContent], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'wiremock-broker-connections.json';
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    });
  }

  // Delete broker from modal table
  const tbodyBrokers = document.getElementById('queues-brokers-tbody');
  if (tbodyBrokers) {
    tbodyBrokers.addEventListener('click', async e => {
      const delBtn = e.target.closest('.btn-delete-broker-conn');
      if (delBtn) {
        const connId = delBtn.dataset.connId;
        if (connId) {
          await deleteConnection(connId);
          const conns = await fetchConnections();
          state.queuesBrokerConnections = conns;
          renderBrokersModal(conns);
          await refreshQueues();
        }
      }
    });
  }

  // Messages Table Click Delegation (Expand / Lens / Delete / Edit)
  const tbody = document.getElementById('queues-messages-tbody');
  if (tbody) {
    tbody.addEventListener('click', async e => {
      const msgId = e.target.dataset.msgId || (e.target.closest('[data-msg-id]') && e.target.closest('[data-msg-id]').dataset.msgId);
      const currentDest = (state.queuesDestinations || []).find(d => d.id === state.queuesSelectedDestinationId);
      if (!currentDest) return;

      // Lens Action Button (Viewer Mode as requested)
      if (e.target.closest('.btn-msg-lens') || e.target.closest('.btn-drawer-lens')) {
        const msg = state.queuesMessages.find(m => m.id === msgId);
        if (msg) {
          openPayloadLens(
            msg.payload,
            null,
            async (newPayload) => {
              // Compensating Edit / In-Place Update (ADR 0025)
              const updatedMsg = { ...msg, payload: newPayload };
              await publishMessage(currentDest.protocol, currentDest.name, updatedMsg);
              await refreshQueues();
            },
            null,
            'viewer',
            { editable: true, context: 'queue-message' }
          );
        }
        return;
      }

      // Edit & Re-Publish Action Button (Editor Mode)
      if (e.target.closest('.btn-drawer-edit')) {
        const msg = state.queuesMessages.find(m => m.id === msgId);
        if (msg) {
          openPayloadLens(
            msg.payload,
            null,
            async (newPayload) => {
              const updatedMsg = { ...msg, id: 'copy-' + Date.now(), payload: newPayload };
              await publishMessage(currentDest.protocol, currentDest.name, updatedMsg);
              await refreshQueues();
            },
            null,
            'editor',
            { editable: true, context: 'compensating-edit' }
          );
        }
        return;
      }

      // Delete Button
      if (e.target.closest('.btn-msg-delete') || e.target.closest('.btn-drawer-delete')) {
        await deleteMessage(currentDest.protocol, currentDest.name, msgId);
        await refreshQueues();
        return;
      }

      // Toggle Row Expansion
      const row = e.target.closest('.queues-message-row');
      if (row) {
        state.queuesSelectedMessageId = (state.queuesSelectedMessageId === msgId) ? null : msgId;
        renderMessages();
      }
    });
  }
}
