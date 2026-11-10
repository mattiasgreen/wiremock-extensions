/**
 * HTTP Tester UI controller and execution history manager.
 * Manages headers key-value editor, JSON formatting, and response inspect panes.
 */
import { state } from './state.js';
import { elements, escapeHtml, formatBytes } from './dom.js';
import { highlightJson, highlightPayload, formatXml, detectFormat } from './highlighter.js';
import {
  discoverEmbeddedPayloads,
  renderFormattedWithNestedPreviews,
  attachEmbeddedPayloadsBar,
  attachDualLayerEditor,
  openPayloadLens,
  detectCharset,
  formatPayloadDescriptor,
  pushViewportLayer,
  createPayloadComponent
} from './payload-lens.js';
import { openStubEditor } from './lifecycle.js';
import { setRoute, toBase64 } from './router.js';
import { generateCurl, sendStubToTester } from './stubs.js';
import { executeTesterRequest } from './api.js';
import {
  resolveMacro,
  loadLocalHeaders,
  saveLocalHeaders,
  isLocalHeadersEnabled,
  setLocalHeadersEnabled,
  getEffectiveTesterHeaders,
  LOCAL_HEADERS_KEY,
  LOCAL_HEADERS_ENABLED_KEY
} from './settings.js';

export {
  resolveMacro,
  loadLocalHeaders,
  saveLocalHeaders,
  isLocalHeadersEnabled,
  setLocalHeadersEnabled,
  getEffectiveTesterHeaders,
  LOCAL_HEADERS_KEY,
  LOCAL_HEADERS_ENABLED_KEY
};

export const QUICK_TEMPLATES = {
  'http-get-users': {
    protocol: 'http',
    method: 'GET',
    url: '/api/v1/users',
    headers: 'Accept: application/json',
    body: ''
  },
  'http-post-order': {
    protocol: 'http',
    method: 'POST',
    url: '/api/v1/orders',
    headers: 'Content-Type: application/json\nAccept: application/json',
    body: JSON.stringify({ customerId: 'CUST-1001', items: [{ sku: 'ITEM-88', qty: 2 }], total: 59.90 }, null, 2)
  },
  'kafka-order-event': {
    protocol: 'kafka',
    topic: 'orders.events',
    key: 'ORD-1001',
    partition: '',
    headers: 'x-tenant-id: acme-corp\nx-correlation-id: corr-kafka-01',
    body: JSON.stringify({ eventId: 'EVT-551', eventType: 'ORDER_CREATED', orderId: 'ORD-1001', timestamp: new Date().toISOString() }, null, 2)
  },
  'kafka-payments-stream': {
    protocol: 'kafka',
    topic: 'payments.stream',
    key: 'PAY-404',
    partition: '0',
    headers: 'x-tenant-id: finance-eu\nx-priority: high',
    body: JSON.stringify({ paymentId: 'PAY-404', amount: 249.00, currency: 'EUR', status: 'INITIATED' }, null, 2)
  },
  'amqp-orders-inbound': {
    protocol: 'amqp',
    destination: 'orders.inbound',
    correlationId: 'corr-amqp-01',
    replyTo: '',
    headers: 'tenant: acme-retail\nenv: staging',
    body: JSON.stringify({ orderId: 'ORD-9002', customer: 'alice@example.com', amount: 142.50 }, null, 2)
  },
  'amqp-req-reply': {
    protocol: 'amqp',
    destination: 'orders.requests',
    correlationId: 'corr-req-rep-77',
    replyTo: 'orders.replies',
    headers: 'tenant: enterprise-core\nmessage-type: OrderValidationRequest',
    body: JSON.stringify({ orderId: 'ORD-9003', validateCredit: true, requestedLimit: 5000 }, null, 2)
  },
  'casual-echo': {
    protocol: 'casual',
    service: 'echoService',
    bufferType: 'JSON',
    headers: 'Content-Type: application/json\nX-Casual-Service: echoService\nX-Casual-Buffer-Type: JSON',
    body: JSON.stringify({ message: 'Hello Casual from Unified Tester', ping: true }, null, 2)
  },
  'casual-charge': {
    protocol: 'casual',
    service: 'billing/charge',
    bufferType: 'JSON',
    headers: 'Content-Type: application/json\nX-Casual-Service: billing/charge\nX-Casual-Buffer-Type: JSON',
    body: JSON.stringify({ accountId: 'ACC-8821', chargeAmount: 95.00, currency: 'USD' }, null, 2)
  },
  'casual-enqueue': {
    protocol: 'casual',
    service: 'order.queue',
    bufferType: 'JSON',
    headers: 'Content-Type: application/json\nX-Casual-Operation: enqueue\nX-Casual-Queue: order.queue\nX-Casual-Buffer-Type: JSON',
    body: JSON.stringify({ queueItemId: 'Q-991', payloadData: 'Process expedited batch' }, null, 2)
  }
};

export function showSettlementBanner(text, cssClass = 'status-settled') {
  if (!elements.testerSettlementBanner) return;
  elements.testerSettlementBanner.className = `tester-settlement-banner ${cssClass}`;
  const iconEl = document.getElementById('tester-settlement-icon');
  const textEl = document.getElementById('tester-settlement-text') || elements.testerSettlementBanner;
  if (textEl) {
    if (iconEl) {
      iconEl.textContent = cssClass.includes('error') ? '❌' : (text.startsWith('📨') ? '📨' : '✅');
      textEl.textContent = text.replace(/^[✅❌📨]\s*/, '');
    } else {
      textEl.textContent = text;
    }
  }
  elements.testerSettlementBanner.classList.remove('hidden');
}

export function switchTesterProtocol(protocol, updateRoute = true) {
  state.testerProtocol = protocol;

  const protoButtons = [
    { el: elements.btnTesterProtoHttp || document.getElementById('btn-tester-proto-http'), p: 'http' },
    { el: elements.btnTesterProtoKafka || document.getElementById('btn-tester-proto-kafka'), p: 'kafka' },
    { el: elements.btnTesterProtoAmqp || document.getElementById('btn-tester-proto-amqp'), p: 'amqp' },
    { el: elements.btnTesterProtoCasual || document.getElementById('btn-tester-proto-casual'), p: 'casual' }
  ];
  protoButtons.forEach(b => {
    if (b.el) {
      if (b.p === protocol) b.el.classList.add('active');
      else b.el.classList.remove('active');
    }
  });

  if (elements.testerBarHttp) elements.testerBarHttp.classList.toggle('hidden', protocol !== 'http');
  if (elements.testerBarKafka) elements.testerBarKafka.classList.toggle('hidden', protocol !== 'kafka');
  if (elements.testerBarAmqp) elements.testerBarAmqp.classList.toggle('hidden', protocol !== 'amqp');
  if (elements.testerBarCasual) elements.testerBarCasual.classList.toggle('hidden', protocol !== 'casual');

  if (elements.testerSectionHeadersHttp) elements.testerSectionHeadersHttp.classList.toggle('hidden', protocol !== 'http');
  if (elements.testerSectionHeadersKafka) elements.testerSectionHeadersKafka.classList.toggle('hidden', protocol !== 'kafka');
  if (elements.testerSectionHeadersAmqp) elements.testerSectionHeadersAmqp.classList.toggle('hidden', protocol !== 'amqp');
  if (elements.testerSectionHeadersCasual) elements.testerSectionHeadersCasual.classList.toggle('hidden', protocol !== 'casual');

  const bodyLabel = document.getElementById('tester-body-label');
  if (bodyLabel) {
    if (protocol === 'kafka') bodyLabel.textContent = 'Event Payload';
    else if (protocol === 'amqp') bodyLabel.textContent = 'Message Payload';
    else if (protocol === 'casual') bodyLabel.textContent = 'Buffer Payload';
    else bodyLabel.textContent = 'Request Body';
  }

  if (elements.testerSettlementBanner) {
    elements.testerSettlementBanner.classList.add('hidden');
  }

  if (updateRoute) {
    updateTesterRoute(true);
  }
}

export function applyQuickTemplate(templateId) {
  const tpl = QUICK_TEMPLATES[templateId];
  if (!tpl) return;

  switchTesterProtocol(tpl.protocol, false);

  if (tpl.protocol === 'http') {
    if (elements.testerMethod) elements.testerMethod.value = tpl.method;
    if (elements.testerUrl) elements.testerUrl.value = tpl.url;
    if (elements.testerHeaders) elements.testerHeaders.value = tpl.headers;
    if (elements.testerBody) {
      elements.testerBody.value = tpl.body;
      if (elements.testerBody._dualLayer) elements.testerBody._dualLayer.refresh();
    }
  } else if (tpl.protocol === 'kafka') {
    if (elements.testerKafkaTopic) elements.testerKafkaTopic.value = tpl.topic;
    if (elements.testerKafkaKey) elements.testerKafkaKey.value = tpl.key;
    if (elements.testerKafkaPartition) elements.testerKafkaPartition.value = tpl.partition;
    if (elements.testerKafkaHeaders) elements.testerKafkaHeaders.value = tpl.headers;
    if (elements.testerBody) {
      elements.testerBody.value = tpl.body;
      if (elements.testerBody._dualLayer) elements.testerBody._dualLayer.refresh();
    }
  } else if (tpl.protocol === 'amqp') {
    if (elements.testerAmqpDest) elements.testerAmqpDest.value = tpl.destination;
    if (elements.testerAmqpCorrelation) elements.testerAmqpCorrelation.value = tpl.correlationId;
    if (elements.testerAmqpReplyTo) elements.testerAmqpReplyTo.value = tpl.replyTo;
    if (elements.testerAmqpHeaders) elements.testerAmqpHeaders.value = tpl.headers;
    if (elements.testerBody) {
      elements.testerBody.value = tpl.body;
      if (elements.testerBody._dualLayer) elements.testerBody._dualLayer.refresh();
    }
  } else if (tpl.protocol === 'casual') {
    if (elements.testerCasualService) elements.testerCasualService.value = tpl.service;
    if (elements.testerCasualBuffer) elements.testerCasualBuffer.value = tpl.bufferType;
    if (elements.testerCasualHeaders) elements.testerCasualHeaders.value = tpl.headers;
    if (elements.casualServiceName) elements.casualServiceName.value = tpl.service;
    if (elements.casualBufferType) elements.casualBufferType.value = tpl.bufferType;
    if (elements.casualRequestHeaders) elements.casualRequestHeaders.value = tpl.headers;
    if (elements.casualRequestPayload) elements.casualRequestPayload.value = tpl.body;
    if (elements.testerBody) {
      elements.testerBody.value = tpl.body;
      if (elements.testerBody._dualLayer) elements.testerBody._dualLayer.refresh();
    }
  }

  updateTesterRoute(true);
}

const TESTER_HISTORY_KEY = 'wiremock_ui_tester_history';
const TESTER_COLLAPSED_KEY = 'wiremock_ui_tester_history_collapsed';

export function appendHeader(name, value) {
  if (!elements.testerHeaders) return;
  const current = elements.testerHeaders.value.trim();
  if (current.toLowerCase().includes(name.toLowerCase() + ':')) return;
  elements.testerHeaders.value = (current ? current + '\n' : '') + `${name}: ${value}`;
  renderLocalHeaderChips();
  updateTesterRoute(true);
}

export function appendKafkaHeader(name, value) {
  if (!elements.testerKafkaHeaders) return;
  const current = elements.testerKafkaHeaders.value.trim();
  if (current.toLowerCase().includes(name.toLowerCase() + ':')) return;
  elements.testerKafkaHeaders.value = (current ? current + '\n' : '') + `${name}: ${value}`;
}

export function appendAmqpHeader(name, value) {
  if (!elements.testerAmqpHeaders) return;
  const current = elements.testerAmqpHeaders.value.trim();
  if (current.toLowerCase().includes(name.toLowerCase() + ':')) return;
  elements.testerAmqpHeaders.value = (current ? current + '\n' : '') + `${name}: ${value}`;
}

export function parseHeadersInput(text) {
  const headers = {};
  if (!text) return headers;
  const lines = text.split('\n');
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const colonIdx = trimmed.indexOf(':');
    if (colonIdx > 0) {
      const key = trimmed.substring(0, colonIdx).trim();
      const val = trimmed.substring(colonIdx + 1).trim();
      if (key) headers[key] = val;
    }
  }
  return headers;
}

export function updateTesterRoute(replace = true) {
  if (!elements.testerMethod || !elements.testerUrl) return;
  const m = elements.testerMethod.value;
  const p = elements.testerUrl.value.trim();
  const h = elements.testerHeaders ? elements.testerHeaders.value.trim() : '';
  const b = elements.testerBody ? elements.testerBody.value.trim() : '';
  const proto = state.testerProtocol || 'http';
  setRoute('tester', {
    proto: proto !== 'http' ? proto : null,
    m: m !== 'GET' ? m : null,
    p: p && p !== '/api/v1/users' ? p : null,
    h: h ? toBase64(h) : null,
    b: b ? toBase64(b) : null
  }, replace);
}

export function initTesterHistoryCollapse() {
  try {
    const saved = localStorage.getItem(TESTER_COLLAPSED_KEY);
    if (saved === 'true') {
      setTesterHistoryCollapsed(true);
    }
  } catch (_) {}
}

export function setTesterHistoryCollapsed(collapsed) {
  state.testerHistoryCollapsed = !!collapsed;
  if (elements.testerHistoryPanel) {
    if (state.testerHistoryCollapsed) {
      elements.testerHistoryPanel.classList.add('collapsed');
    } else {
      elements.testerHistoryPanel.classList.remove('collapsed');
    }
  }
  try {
    localStorage.setItem(TESTER_COLLAPSED_KEY, state.testerHistoryCollapsed ? 'true' : 'false');
  } catch (_) {}
}

export function toggleTesterHistory() {
  setTesterHistoryCollapsed(!state.testerHistoryCollapsed);
}

export function loadTesterHistoryFromStorage() {
  try {
    const saved = localStorage.getItem(TESTER_HISTORY_KEY);
    if (saved) {
      state.testerHistory = JSON.parse(saved);
    }
  } catch (_) {
    state.testerHistory = [];
  }
  renderTesterHistory();
}

export function saveTesterHistoryItem(item) {
  state.testerHistory.unshift(item);
  if (state.testerHistory.length > 50) state.testerHistory.pop();
  try {
    localStorage.setItem(TESTER_HISTORY_KEY, JSON.stringify(state.testerHistory));
  } catch (_) {}
  renderTesterHistory();
}

export function clearTesterHistory() {
  state.testerHistory = [];
  try {
    localStorage.removeItem(TESTER_HISTORY_KEY);
  } catch (_) {}
  renderTesterHistory();
}

export function renderTesterHistory() {
  if (!elements.testerHistoryList) return;
  const totalCount = state.testerHistory.length;
  if (elements.testerHistoryCount) {
    elements.testerHistoryCount.textContent = totalCount;
  }
  if (elements.testerHistoryCollapsedCount) {
    elements.testerHistoryCollapsedCount.textContent = totalCount;
  }

  const filter = (state.testerHistoryProtocolFilter || 'all').toLowerCase();
  const items = filter === 'all'
    ? state.testerHistory
    : state.testerHistory.filter(item => (item.protocol || 'http').toLowerCase() === filter);

  if (items.length === 0) {
    elements.testerHistoryList.innerHTML = `<div class="empty-state text-small">${totalCount === 0 ? 'No requests sent yet.' : 'No ' + filter.toUpperCase() + ' requests recorded.'}</div>`;
    return;
  }

  elements.testerHistoryList.innerHTML = '';
  items.forEach((item, index) => {
    const card = document.createElement('div');
    card.className = 'history-item';
    card.setAttribute('data-history-idx', index);
    card.setAttribute('data-testid', 'history-item');

    const proto = (item.protocol || 'http').toLowerCase();
    const methodOrOp = item.method || (proto === 'casual' ? 'CALL' : (proto === 'kafka' ? 'PRODUCE' : (proto === 'amqp' ? 'PUBLISH' : 'GET')));
    const isErr = item.status === 'ERR' || item.status === 'ERROR' || (typeof item.status === 'number' && item.status >= 400);
    const statusCls = isErr ? (item.status >= 500 || item.status === 'ERR' || item.status === 'ERROR' ? 'status-500' : 'status-400') : 'status-200';
    const timeStr = item.timestamp ? new Date(item.timestamp).toLocaleTimeString() : '';
    const displayUrl = item.url || item.destination || item.serviceName || '/';

    card.innerHTML = `
      <div class="history-item-top">
        <span class="protocol-badge proto-${proto}">${proto.toUpperCase()}</span>
        <span class="http-badge badge-${methodOrOp.toLowerCase()}">${escapeHtml(methodOrOp)}</span>
        <span class="card-value ${statusCls}" style="font-size: 0.78rem; margin: 0;">${escapeHtml(item.status ? String(item.status) : 'OK')}</span>
        <span class="history-time-meta">${timeStr}</span>
      </div>
      <div class="history-item-url" title="${escapeHtml(displayUrl)}">${escapeHtml(displayUrl)}</div>
      <div class="history-item-meta">
        <span>${item.duration || 0} ms</span>
        <span>${formatBytes(item.size || 0)}</span>
      </div>
    `;

    card.addEventListener('click', () => {
      document.querySelectorAll('.history-item').forEach(el => el.classList.remove('active'));
      card.classList.add('active');
      restoreHistoryItem(item);
    });

    elements.testerHistoryList.appendChild(card);
  });
}

export function restoreHistoryItem(item) {
  const proto = (item.protocol || 'http').toLowerCase();
  switchTesterProtocol(proto, false);

  if (proto === 'http') {
    if (elements.testerMethod) elements.testerMethod.value = item.method || 'GET';
    if (elements.testerUrl) elements.testerUrl.value = item.url || '/api/v1/users';
    if (elements.testerHeaders) elements.testerHeaders.value = item.headersText || '';
    if (elements.testerBody) {
      elements.testerBody.value = item.bodyText || '';
      if (elements.testerBody._dualLayer) elements.testerBody._dualLayer.refresh();
    }
  } else if (proto === 'kafka') {
    if (elements.testerKafkaTopic) elements.testerKafkaTopic.value = item.url || item.destination || 'orders.events';
    if (elements.testerKafkaKey) elements.testerKafkaKey.value = item.key || '';
    if (elements.testerKafkaPartition) elements.testerKafkaPartition.value = item.partition !== undefined ? item.partition : '';
    if (elements.testerKafkaHeaders) elements.testerKafkaHeaders.value = item.headersText || '';
    if (elements.testerBody) {
      elements.testerBody.value = item.bodyText || '';
      if (elements.testerBody._dualLayer) elements.testerBody._dualLayer.refresh();
    }
  } else if (proto === 'amqp') {
    if (elements.testerAmqpDest) elements.testerAmqpDest.value = item.url || item.destination || 'orders.inbound';
    if (elements.testerAmqpCorrelation) elements.testerAmqpCorrelation.value = item.correlationId || '';
    if (elements.testerAmqpReplyTo) elements.testerAmqpReplyTo.value = item.replyTo || '';
    if (elements.testerAmqpHeaders) elements.testerAmqpHeaders.value = item.headersText || '';
    if (elements.testerBody) {
      elements.testerBody.value = item.bodyText || '';
      if (elements.testerBody._dualLayer) elements.testerBody._dualLayer.refresh();
    }
  } else if (proto === 'casual') {
    if (elements.testerCasualService) elements.testerCasualService.value = item.serviceName || item.url || 'echoService';
    if (elements.testerCasualBuffer) elements.testerCasualBuffer.value = item.bufferType || 'JSON';
    if (elements.testerCasualHeaders) elements.testerCasualHeaders.value = item.headersText || '';
    if (elements.casualServiceName) elements.casualServiceName.value = item.serviceName || item.url || 'echoService';
    if (elements.casualBufferType) elements.casualBufferType.value = item.bufferType || 'JSON';
    if (elements.casualRequestHeaders) elements.casualRequestHeaders.value = item.headersText || '';
    if (elements.casualRequestPayload) elements.casualRequestPayload.value = item.bodyText || '';
    if (elements.testerBody) {
      elements.testerBody.value = item.bodyText || '';
      if (elements.testerBody._dualLayer) elements.testerBody._dualLayer.refresh();
    }
  }

  const methodOrOp = item.method || (proto === 'casual' ? 'CALL' : (proto === 'kafka' ? 'PRODUCE' : (proto === 'amqp' ? 'PUBLISH' : 'GET')));
  displaySentRequest(methodOrOp, item.url || item.destination || item.serviceName || '', item.headersText || '', item.bodyText || '');
  displayResponseResult(item.status, item.statusText || '', item.duration || 0, item.responseHeaders || [], item.responseBody || '');
  updateTesterRoute(true);
}

export function setInspectorTab(tab) {
  state.activeInspectorTab = tab;
  [elements.btnTesterTabResponse, elements.btnTesterTabRequest, elements.btnTesterTabBoth].forEach(b => {
    if (b) b.classList.remove('active');
  });

  const container = document.getElementById('tester-inspector-content');
  if (!container) return;

  if (tab === 'response') {
    if (elements.btnTesterTabResponse) elements.btnTesterTabResponse.classList.add('active');
    if (elements.testerViewResponse) {
      elements.testerViewResponse.classList.remove('hidden');
      elements.testerViewResponse.classList.add('active');
    }
    if (elements.testerViewRequest) {
      elements.testerViewRequest.classList.add('hidden');
      elements.testerViewRequest.classList.remove('active');
    }
    container.classList.remove('both-mode');
  } else if (tab === 'request') {
    if (elements.btnTesterTabRequest) elements.btnTesterTabRequest.classList.add('active');
    if (elements.testerViewRequest) {
      elements.testerViewRequest.classList.remove('hidden');
      elements.testerViewRequest.classList.add('active');
    }
    if (elements.testerViewResponse) {
      elements.testerViewResponse.classList.add('hidden');
      elements.testerViewResponse.classList.remove('active');
    }
    container.classList.remove('both-mode');
  } else if (tab === 'both') {
    if (elements.btnTesterTabBoth) elements.btnTesterTabBoth.classList.add('active');
    if (elements.testerViewResponse) {
      elements.testerViewResponse.classList.remove('hidden');
      elements.testerViewResponse.classList.add('active');
    }
    if (elements.testerViewRequest) {
      elements.testerViewRequest.classList.remove('hidden');
      elements.testerViewRequest.classList.add('active');
    }
    container.classList.add('both-mode');
  }
}

export function displaySentRequest(method, url, headersText, bodyText) {
  if (elements.testerSentMethod) {
    elements.testerSentMethod.textContent = method;
    elements.testerSentMethod.className = `http-badge badge-${method.toLowerCase()}`;
  }
  if (elements.testerSentUrl) {
    elements.testerSentUrl.textContent = url;
  }
  if (elements.testerSentHeaders) {
    elements.testerSentHeaders.textContent = headersText.trim() || '(no headers sent)';
  }
  if (elements.testerSentBody) {
    const rawTrimmed = (bodyText || '').trim();
    if (!rawTrimmed) {
      elements.testerSentBody.textContent = '(empty body)';
    } else {
      const discovered = discoverEmbeddedPayloads(rawTrimmed);
      if (discovered.length > 0) {
        elements.testerSentBody.innerHTML = renderFormattedWithNestedPreviews(rawTrimmed, discovered, 'tester_sent_req', null, {
          editable: false,
          readOnlyReason: 'Sent request snapshot is read-only — edit the Request Body input on the left',
          context: 'tester-sent'
        });
      } else {
        elements.testerSentBody.innerHTML = highlightPayload(rawTrimmed);
      }
    }
  }
}

export function displayResponseResult(status, statusText, elapsed, headerLines, responseBody) {
  if (elements.testerResponseStatus) {
    if (status === 'ERROR') {
      elements.testerResponseStatus.textContent = 'ERROR';
      elements.testerResponseStatus.className = 'card-value status-500';
    } else {
      elements.testerResponseStatus.textContent = `${status} ${statusText || ''}`.trim();
      elements.testerResponseStatus.className = `card-value status-${status >= 500 ? '500' : (status >= 400 ? '400' : '200')}`;
    }
  }
  if (elements.testerResponseTime) {
    elements.testerResponseTime.textContent = `${elapsed} ms`;
  }
  if (elements.testerResponseSize) {
    const bytes = new Blob([responseBody || '']).size;
    elements.testerResponseSize.textContent = formatBytes(bytes);
  }
  if (elements.testerResponseHeaderCount) {
    elements.testerResponseHeaderCount.textContent = headerLines.length;
  }
  if (elements.testerResponseHeaders) {
    elements.testerResponseHeaders.textContent = headerLines.join('\n') || '(no headers)';
  }
  state.lastResponseHeaders = headerLines;
  renderTesterResponse(responseBody);
}

export function renderTesterResponse(bodyText) {
  state.lastRawResponseBody = bodyText;
  if (!elements.testerResponseBody) return;

  const modeToggle = document.getElementById('tester-response-mode-toggle');
  const topPillGroup = document.getElementById('tester-top-pill-group');
  const topPill = document.getElementById('tester-top-pill');
  const btnMax = document.getElementById('btn-tester-response-maximize');
  const charset = detectCharset(bodyText, state.lastResponseHeaders);
  const fmt = detectFormat(bodyText);

  createPayloadComponent(null, {
    viewerEl: elements.testerResponseBody,
    toggleEl: modeToggle,
    pillEl: topPill,
    pillGroupEl: topPillGroup,
    maximizeBtn: btnMax,
    editBtn: document.getElementById('btn-tester-response-edit'),
    readOnly: true,
    editable: false,
    readOnlyReason: 'Received response payload is read-only',
    text: bodyText,
    format: fmt,
    charset,
    defaultMode: state.testerViewMode === 'raw' ? 'raw' : 'rich',
    title: 'HTTP Tester Response',
    context: 'tester-response'
  });
}

export function renderLocalHeaderChips() {
  if (!elements.testerLocalHeadersChips) return;

  const localHeaders = loadLocalHeaders();
  const isEnabled = isLocalHeadersEnabled();
  const textareaVal = elements.testerHeaders ? elements.testerHeaders.value : '';
  const { overriddenKeys } = getEffectiveTesterHeaders(textareaVal, false);

  const activeHeaders = localHeaders.filter(h => h.enabled && h.name && h.name.trim());
  if (elements.testerLocalHeadersCount) {
    elements.testerLocalHeadersCount.textContent = activeHeaders.length;
  }

  if (elements.testerLocalHeadersToggle) {
    elements.testerLocalHeadersToggle.checked = isEnabled;
  }

  if (elements.testerLocalHeadersContainer) {
    if (!isEnabled) {
      elements.testerLocalHeadersContainer.classList.add('disabled');
    } else {
      elements.testerLocalHeadersContainer.classList.remove('disabled');
    }
  }

  if (!isEnabled) {
    elements.testerLocalHeadersChips.innerHTML = '<span class="muted-text text-small">Persistent headers paused (toggle switch to re-enable).</span>';
    return;
  }

  if (activeHeaders.length === 0) {
    elements.testerLocalHeadersChips.innerHTML = '<span class="muted-text text-small">No persistent headers configured (click ⚙️ Manage to add).</span>';
    return;
  }

  elements.testerLocalHeadersChips.innerHTML = '';
  for (const h of activeHeaders) {
    const isOverridden = overriddenKeys.has(h.name.trim().toLowerCase());
    const chip = document.createElement('div');
    chip.className = isOverridden ? 'local-header-chip overridden' : 'local-header-chip';
    chip.setAttribute('data-header-name', h.name.trim());
    if (isOverridden) {
      chip.setAttribute('title', 'Overridden by Request Headers textarea');
      chip.innerHTML = `<span class="chip-name">${escapeHtml(h.name.trim())}:</span> <span class="chip-val">${escapeHtml(h.value || '')}</span><span class="chip-override-badge">(overridden)</span>`;
    } else {
      chip.setAttribute('title', 'From browser local storage');
      chip.innerHTML = `<span class="chip-name">${escapeHtml(h.name.trim())}:</span> <span class="chip-val">${escapeHtml(h.value || '')}</span>`;
    }
    elements.testerLocalHeadersChips.appendChild(chip);
  }
}

export function initTesterTab() {
  initTesterHistoryCollapse();

  // Multi-Protocol Switcher
  const protoButtons = [
    { el: elements.btnTesterProtoHttp || document.getElementById('btn-tester-proto-http'), p: 'http' },
    { el: elements.btnTesterProtoKafka || document.getElementById('btn-tester-proto-kafka'), p: 'kafka' },
    { el: elements.btnTesterProtoAmqp || document.getElementById('btn-tester-proto-amqp'), p: 'amqp' },
    { el: elements.btnTesterProtoCasual || document.getElementById('btn-tester-proto-casual'), p: 'casual' }
  ];
  protoButtons.forEach(({ el, p }) => {
    if (el) {
      el.addEventListener('click', () => switchTesterProtocol(p));
    }
  });

  // Quick Templates Dropdown
  const quickTplEl = elements.testerQuickTemplates || document.getElementById('tester-quick-templates');
  if (quickTplEl) {
    quickTplEl.addEventListener('change', (e) => {
      if (e.target.value) {
        applyQuickTemplate(e.target.value);
        e.target.value = '';
      }
    });
  }

  // Multi-Protocol History Filter Pills
  const filterContainer = elements.testerHistoryFilters || document.getElementById('tester-history-filters');
  if (filterContainer) {
    filterContainer.querySelectorAll('.filter-pill').forEach(pill => {
      pill.addEventListener('click', () => {
        filterContainer.querySelectorAll('.filter-pill').forEach(p => p.classList.remove('active'));
        pill.classList.add('active');
        state.testerHistoryProtocolFilter = pill.getAttribute('data-filter') || 'all';
        renderTesterHistory();
      });
    });
  }

  if (elements.btnTesterSend) {
    elements.btnTesterSend.addEventListener('click', executeTesterRequest);
  }
  if (elements.btnTesterSendKafka) {
    elements.btnTesterSendKafka.addEventListener('click', executeTesterRequest);
  }
  if (elements.btnTesterSendAmqp) {
    elements.btnTesterSendAmqp.addEventListener('click', executeTesterRequest);
  }

  const tabTesterEl = document.getElementById('tab-tester');
  if (tabTesterEl) {
    tabTesterEl.addEventListener('keydown', (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
        e.preventDefault();
        executeTesterRequest();
      }
    });
  }

  // HTTP Header Presets
  if (elements.btnPresetJson) {
    elements.btnPresetJson.addEventListener('click', () => appendHeader('Content-Type', 'application/json'));
  }
  if (elements.btnPresetBearer) {
    elements.btnPresetBearer.addEventListener('click', () => appendHeader('Authorization', 'Bearer <token>'));
  }
  if (elements.btnPresetAccept) {
    elements.btnPresetAccept.addEventListener('click', () => appendHeader('Accept', 'application/json'));
  }

  // Kafka Header Presets
  const btnPresetKafkaCorr = document.getElementById('btn-preset-kafka-corr');
  if (btnPresetKafkaCorr) {
    btnPresetKafkaCorr.addEventListener('click', () => appendKafkaHeader('x-correlation-id', 'corr-' + Date.now()));
  }
  const btnPresetKafkaTenant = document.getElementById('btn-preset-kafka-tenant');
  if (btnPresetKafkaTenant) {
    btnPresetKafkaTenant.addEventListener('click', () => appendKafkaHeader('x-tenant-id', 'acme-corp'));
  }

  // AMQP Property Presets
  const btnPresetAmqpTenant = document.getElementById('btn-preset-amqp-tenant');
  if (btnPresetAmqpTenant) {
    btnPresetAmqpTenant.addEventListener('click', () => appendAmqpHeader('tenant', 'acme-retail'));
  }
  const btnPresetAmqpReply = document.getElementById('btn-preset-amqp-reply');
  if (btnPresetAmqpReply) {
    btnPresetAmqpReply.addEventListener('click', () => appendAmqpHeader('message-type', 'OrderValidationRequest'));
  }

  // Casual Presets
  const btnCasualPresetTx = document.getElementById('btn-casual-preset-tx');
  if (elements.btnTesterSendCasual) {
    elements.btnTesterSendCasual.addEventListener('click', executeTesterRequest);
  }
  const btnTesterCasualPresetTx = document.getElementById('btn-tester-casual-preset-tx');
  if (btnTesterCasualPresetTx && elements.testerCasualHeaders) {
    btnTesterCasualPresetTx.addEventListener('click', () => {
      const cur = elements.testerCasualHeaders.value.trim();
      if (!cur.includes('X-Casual-Transaction-State')) {
        elements.testerCasualHeaders.value = (cur ? cur + '\n' : '') + 'X-Casual-Transaction-State: ACTIVE';
      }
    });
  }
  if (elements.btnTesterResetForm) {
    elements.btnTesterResetForm.addEventListener('click', () => {
      if (elements.testerMethod) elements.testerMethod.value = 'GET';
      if (elements.testerUrl) elements.testerUrl.value = '/api/v1/users';
      if (elements.testerHeaders) elements.testerHeaders.value = '';
      if (elements.testerBody) elements.testerBody.value = '';
      setRoute('tester', { m: null, p: null, h: null, b: null });
    });
  }
  if (elements.btnTesterClearHistory) {
    elements.btnTesterClearHistory.addEventListener('click', () => {
      if (confirm('Clear all request history?')) {
        clearTesterHistory();
      }
    });
  }
  if (elements.btnToggleTesterHistory) {
    elements.btnToggleTesterHistory.addEventListener('click', (e) => {
      e.stopPropagation();
      toggleTesterHistory();
    });
  }
  if (elements.btnExpandTesterHistory) {
    elements.btnExpandTesterHistory.addEventListener('click', (e) => {
      e.stopPropagation();
      setTesterHistoryCollapsed(false);
    });
  }
  if (elements.testerHistoryCollapsedBar) {
    elements.testerHistoryCollapsedBar.addEventListener('click', () => {
      setTesterHistoryCollapsed(false);
    });
  }
  if (elements.btnTesterTabResponse) {
    elements.btnTesterTabResponse.addEventListener('click', () => setInspectorTab('response'));
  }
  if (elements.btnTesterTabRequest) {
    elements.btnTesterTabRequest.addEventListener('click', () => setInspectorTab('request'));
  }
  if (elements.btnTesterTabBoth) {
    elements.btnTesterTabBoth.addEventListener('click', () => setInspectorTab('both'));
  }
  if (elements.btnTesterFormatJson) {
    elements.btnTesterFormatJson.addEventListener('click', () => {
      if (!elements.testerBody || !elements.testerBody.value.trim()) return;
      const val = elements.testerBody.value.trim();
      const detected = detectFormat(val);
      if (detected === 'xml') {
        elements.testerBody.value = formatXml(val);
        if (elements.testerBody._dualLayer) elements.testerBody._dualLayer.refresh();
        appendHeader('Content-Type', 'application/xml');
      } else {
        try {
          const parsed = JSON.parse(val);
          elements.testerBody.value = JSON.stringify(parsed, null, 2);
          if (elements.testerBody._dualLayer) elements.testerBody._dualLayer.refresh();
          appendHeader('Content-Type', 'application/json');
        } catch (err) {
          alert('Invalid payload: ' + err.message);
        }
      }
    });
  }
  if (elements.btnTesterCopyCurl) {
    elements.btnTesterCopyCurl.addEventListener('click', () => {
      const method = (elements.testerMethod && elements.testerMethod.value) || 'GET';
      const path = (elements.testerUrl && elements.testerUrl.value) || '/';
      const headers = parseHeadersInput((elements.testerHeaders && elements.testerHeaders.value) || '');
      const body = (elements.testerBody && elements.testerBody.value) || '';
      const cmd = generateCurl(method, path, headers, body);
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(cmd).catch(() => {});
      }
      elements.btnTesterCopyCurl.textContent = '✅ Copied!';
      setTimeout(() => elements.btnTesterCopyCurl.textContent = '📋 Copy cURL', 1500);
    });
  }
  if (elements.btnTesterShareLink) {
    elements.btnTesterShareLink.addEventListener('click', () => {
      updateTesterRoute(false);
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(window.location.href).catch(() => {});
      }
      elements.btnTesterShareLink.textContent = '✅ Link Copied!';
      setTimeout(() => elements.btnTesterShareLink.textContent = '🔗 Copy Share Link', 1500);
    });
  }
  if (elements.btnViewHighlighted) {
    elements.btnViewHighlighted.addEventListener('click', () => {
      state.testerViewMode = 'highlighted';
      elements.btnViewHighlighted.classList.add('active');
      if (elements.btnViewRaw) elements.btnViewRaw.classList.remove('active');
      renderTesterResponse(state.lastRawResponseBody);
    });
  }
  if (elements.btnViewRaw) {
    elements.btnViewRaw.addEventListener('click', () => {
      state.testerViewMode = 'raw';
      elements.btnViewRaw.classList.add('active');
      if (elements.btnViewHighlighted) elements.btnViewHighlighted.classList.remove('active');
      renderTesterResponse(state.lastRawResponseBody);
    });
  }
  if (elements.btnTesterCopyResponse) {
    elements.btnTesterCopyResponse.addEventListener('click', () => {
      if (state.lastRawResponseBody) {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(state.lastRawResponseBody).catch(() => {});
        }
        elements.btnTesterCopyResponse.textContent = '✅ Copied!';
        setTimeout(() => elements.btnTesterCopyResponse.textContent = '📋 Copy', 1500);
      }
    });
  }
  [elements.testerMethod, elements.testerUrl, elements.testerHeaders, elements.testerBody].forEach(el => {
    if (el) el.addEventListener('change', () => updateTesterRoute(true));
  });
  if (elements.btnTesterResetStub) {
    elements.btnTesterResetStub.addEventListener('click', () => {
      if (state.contextStub) {
        sendStubToTester(state.contextStub);
      }
    });
  }
  if (elements.btnTesterClearContext) {
    elements.btnTesterClearContext.addEventListener('click', () => {
      state.contextStub = null;
      state.selectedStubId = null;
      document.querySelectorAll('.stub-card').forEach(el => el.classList.remove('selected'));
      if (elements.testerContextBanner) {
        elements.testerContextBanner.classList.add('hidden');
      }
      if (elements.testerMethod) elements.testerMethod.value = 'GET';
      if (elements.testerUrl) elements.testerUrl.value = '/api/v1/users';
      if (elements.testerHeaders) elements.testerHeaders.value = '';
      if (elements.testerBody) {
        elements.testerBody.value = '';
        elements.testerBody.dispatchEvent(new Event('input'));
      }
      updateTesterRoute(true);
    });
  }

  if (elements.testerBody) {
    createPayloadComponent(null, {
      backingTextarea: elements.testerBody,
      toggleEl: elements.testerBodyModeToggle || document.getElementById('tester-body-mode-toggle'),
      pillEl: document.getElementById('tester-request-top-pill'),
      pillGroupEl: document.getElementById('tester-request-top-pill-group'),
      formatBtn: document.getElementById('btn-tester-format-json'),
      maximizeBtn: document.getElementById('btn-tester-request-maximize'),
      title: 'HTTP Tester Request Body',
      context: 'tester-request',
      defaultAction: 'edit',
      onChange: () => updateTesterRoute(true)
    });
    if (elements.testerBodyEmbeddedBar) {
      attachEmbeddedPayloadsBar(elements.testerBody, elements.testerBodyEmbeddedBar, () => updateTesterRoute(true));
    }
  }

  const topPill = document.getElementById('tester-top-pill');
  if (topPill) {
    topPill.addEventListener('click', () => {
      if (elements.testerResponseBody) {
        const isHidden = elements.testerResponseBody.classList.toggle('hidden');
        topPill.title = isHidden ? 'Click to expand' : 'Click to collapse';
      }
    });
  }
}
