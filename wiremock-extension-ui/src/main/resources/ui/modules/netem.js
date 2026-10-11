/**
 * WireMock Console UI - TCP NetEm Module (ES Module).
 * Pure Vanilla JS, zero NPM dependencies.
 */

import { state } from './state.js';
import { doFetch } from './api.js';

let journalRefreshTimer = null;
let currentProxyName = 'wiremock-netem';

export function initNetEmTab() {
  const refreshBtn = document.getElementById('btn-netem-refresh');
  if (refreshBtn) {
    refreshBtn.addEventListener('click', () => {
      loadNetEmStatus();
      loadNetEmJournal();
    });
  }

  const resetAllBtn = document.getElementById('btn-netem-reset-all');
  if (resetAllBtn) {
    resetAllBtn.addEventListener('click', async () => {
      if (confirm('Reset all NetEm toxics? Traffic will return to normal.')) {
        await resetAllToxics();
      }
    });
  }

  const addToxicBtn = document.getElementById('btn-netem-add-toxic');
  const addToxicModal = document.getElementById('netem-add-toxic-modal');
  const closeToxicModalBtn = document.getElementById('btn-netem-close-toxic-modal');
  const cancelToxicModalBtn = document.getElementById('btn-netem-cancel-toxic');

  if (addToxicBtn && addToxicModal) {
    addToxicBtn.addEventListener('click', () => {
      addToxicModal.classList.remove('hidden');
    });
  }

  if (closeToxicModalBtn && addToxicModal) {
    closeToxicModalBtn.addEventListener('click', () => {
      addToxicModal.classList.add('hidden');
    });
  }

  if (cancelToxicModalBtn && addToxicModal) {
    cancelToxicModalBtn.addEventListener('click', () => {
      addToxicModal.classList.add('hidden');
    });
  }

  // Type change handler to toggle attribute fields
  const typeSelect = document.getElementById('netem-toxic-type');
  if (typeSelect) {
    typeSelect.addEventListener('change', () => {
      updateAttributeInputs(typeSelect.value);
    });
    updateAttributeInputs(typeSelect.value);
  }

  // Submit Toxic
  const submitToxicBtn = document.getElementById('btn-netem-submit-toxic');
  if (submitToxicBtn) {
    submitToxicBtn.addEventListener('click', submitNewToxic);
  }

  // Preset Buttons
  document.querySelectorAll('.netem-preset-btn').forEach(btn => {
    btn.addEventListener('click', async () => {
      const preset = btn.getAttribute('data-preset');
      if (preset) {
        await applyPreset(preset);
      }
    });
  });

  // Clear Journal
  const clearJournalBtn = document.getElementById('btn-netem-clear-journal');
  if (clearJournalBtn) {
    clearJournalBtn.addEventListener('click', async () => {
      await clearJournal();
    });
  }

  // Journal Filters
  const filterCorr = document.getElementById('netem-journal-filter-corr');
  if (filterCorr) {
    filterCorr.addEventListener('input', () => loadNetEmJournal());
  }

  const filterOutcome = document.getElementById('netem-journal-filter-outcome');
  if (filterOutcome) {
    filterOutcome.addEventListener('change', () => loadNetEmJournal());
  }

  // Auto-refresh interval
  const autoRefreshCb = document.getElementById('netem-journal-auto-refresh');
  if (autoRefreshCb) {
    autoRefreshCb.addEventListener('change', () => {
      setupJournalAutoRefresh(autoRefreshCb.checked);
    });
  }
}

export function activateNetEm() {
  loadNetEmStatus();
  loadNetEmJournal();
  setupJournalAutoRefresh(true);
}

export function deactivateNetEm() {
  setupJournalAutoRefresh(false);
}

function setupJournalAutoRefresh(enabled) {
  if (journalRefreshTimer) {
    clearInterval(journalRefreshTimer);
    journalRefreshTimer = null;
  }
  if (enabled) {
    journalRefreshTimer = setInterval(() => {
      const activeTab = document.querySelector('.main-container')?.getAttribute('data-active-tab');
      if (activeTab === 'tab-netem') {
        loadNetEmStatus(true);
        loadNetEmJournal();
      }
    }, 2000);
  }
}

export async function loadNetEmStatus(silent = false) {
  try {
    const res = await doFetch('/__admin/tcp-netem/proxies');
    if (!res.ok) return;
    const proxies = await res.json();
    if (!proxies || proxies.length === 0) {
      renderNoProxies();
      return;
    }

    const proxy = proxies[0];
    currentProxyName = proxy.name || 'wiremock-netem';

    // Ingress banner
    const ingressInfo = document.getElementById('netem-ingress-info');
    if (ingressInfo) {
      ingressInfo.textContent = `Proxy [${proxy.name}]: Port ${proxy.listenPort} ➔ Target ${proxy.targetHost}:${proxy.targetPort}`;
    }

    // Stats
    const stats = proxy.stats || {};
    setStatText('netem-stat-normal-transfers', stats.normalTransfers || 0);
    setStatText('netem-stat-impaired-transfers', stats.impairedTransfers || 0);
    setStatText('netem-stat-active-conn', stats.activeConnections || 0);

    const toxics = proxy.toxics || [];
    setStatText('netem-stat-active-toxics', toxics.length);
    const navBadge = document.getElementById('stat-netem-toxics');
    if (navBadge) navBadge.textContent = toxics.length;

    renderToxics(toxics);
  } catch (e) {
    if (!silent) console.error('Failed to load NetEm status', e);
  }
}

function setStatText(id, val) {
  const el = document.getElementById(id);
  if (el) el.textContent = val;
}

function renderNoProxies() {
  const container = document.getElementById('netem-toxics-list');
  if (container) {
    container.innerHTML = '<div class="empty-state">No NetEm proxies configured. Default port 8081 is unassigned.</div>';
  }
}

function renderToxics(toxics) {
  const container = document.getElementById('netem-toxics-list');
  if (!container) return;

  if (!toxics || toxics.length === 0) {
    container.innerHTML = '<div class="empty-state">✅ No network impairments active. All traffic on port 8081 flows at line rate.</div>';
    return;
  }

  container.innerHTML = toxics.map(t => {
    const streamBadge = t.stream === 'upstream'
      ? '<span class="netem-badge badge-upstream">⬆ Upstream</span>'
      : '<span class="netem-badge badge-downstream">⬇ Downstream</span>';

    const corrBadge = t.correlationId
      ? `<span class="netem-badge badge-corr" title="Scoped to Correlation ID">Scoped: ${escapeHtml(t.correlationId)}</span>`
      : '<span class="netem-badge badge-all" title="Applies to all connections">Global</span>';

    const attrs = t.attributes || {};
    const attrDetails = Object.entries(attrs).map(([k, v]) => `<strong>${k}:</strong> ${v}`).join(' &bull; ');

    return `
      <div class="netem-toxic-card" data-toxic-name="${escapeHtml(t.name)}">
        <div class="toxic-card-header">
          <div class="toxic-title-group">
            <span class="toxic-type-badge">${escapeHtml(t.type.toUpperCase())}</span>
            <strong class="toxic-name">${escapeHtml(t.name)}</strong>
          </div>
          <button class="btn btn-tiny btn-danger btn-delete-toxic" data-name="${escapeHtml(t.name)}" title="Delete Toxic">✕</button>
        </div>
        <div class="toxic-card-body">
          <div class="toxic-tags">${streamBadge} ${corrBadge} <span class="netem-badge">Rate: ${Math.round(t.toxicity * 100)}%</span></div>
          <div class="toxic-attributes">${attrDetails || '<em>Default settings</em>'}</div>
        </div>
      </div>
    `;
  }).join('');

  // Wire delete buttons
  container.querySelectorAll('.btn-delete-toxic').forEach(btn => {
    btn.addEventListener('click', async () => {
      const name = btn.getAttribute('data-name');
      if (name) {
        await deleteToxic(name);
      }
    });
  });
}

export async function deleteToxic(name) {
  try {
    const res = await doFetch(`/__admin/tcp-netem/proxies/${encodeURIComponent(currentProxyName)}/toxics/${encodeURIComponent(name)}`, {
      method: 'DELETE'
    });
    if (res.ok) {
      await loadNetEmStatus();
    }
  } catch (e) {
    console.error('Failed to delete toxic', e);
  }
}

export async function resetAllToxics() {
  try {
    const res = await doFetch('/__admin/tcp-netem/reset', { method: 'POST' });
    if (res.ok) {
      await loadNetEmStatus();
    }
  } catch (e) {
    console.error('Failed to reset toxics', e);
  }
}

export async function applyPreset(presetName) {
  try {
    const res = await doFetch(`/__admin/tcp-netem/proxies/${encodeURIComponent(currentProxyName)}/presets/${encodeURIComponent(presetName)}`, {
      method: 'POST'
    });
    if (res.ok) {
      await loadNetEmStatus();
    }
  } catch (e) {
    console.error('Failed to apply preset', e);
  }
}

export async function loadNetEmJournal() {
  try {
    const filterCorr = document.getElementById('netem-journal-filter-corr')?.value?.trim();
    const filterOutcome = document.getElementById('netem-journal-filter-outcome')?.value;

    let url = '/__admin/tcp-netem/journal?limit=100';
    if (filterCorr) url += `&correlationId=${encodeURIComponent(filterCorr)}`;
    if (filterOutcome === 'impaired') url += `&impaired=true`;
    else if (filterOutcome === 'normal') url += `&impaired=false`;

    const res = await doFetch(url);
    if (!res.ok) return;
    const entries = await res.json();
    renderJournalTable(entries);
  } catch (e) {
    console.error('Failed to load NetEm journal', e);
  }
}

function renderJournalTable(entries) {
  const tbody = document.getElementById('netem-journal-tbody');
  if (!tbody) return;

  if (!entries || entries.length === 0) {
    tbody.innerHTML = '<tr class="empty-state-row"><td colspan="7" class="empty-state-cell">No matching network events recorded in NetEm journal.</td></tr>';
    return;
  }

  tbody.innerHTML = entries.map(e => {
    const timeStr = new Date(e.timestampEpochMs).toLocaleTimeString();
    const corrBadge = e.correlationId
      ? `<span class="badge-corr-table">${escapeHtml(e.correlationId)}</span>`
      : '<span class="muted-text">none</span>';

    const outcomeBadge = e.impaired
      ? `<span class="netem-outcome outcome-impaired" title="${escapeHtml(e.appliedToxics)}">⚠️ Impaired</span>`
      : '<span class="netem-outcome outcome-normal">✅ Normal</span>';

    return `
      <tr>
        <td class="cell-time">${timeStr}</td>
        <td class="cell-corr">${corrBadge}</td>
        <td class="cell-addr">${escapeHtml(e.clientAddress || 'unknown')}</td>
        <td class="cell-num">${e.durationMs}ms</td>
        <td class="cell-num">${e.bytesUpstream}B / ${e.bytesDownstream}B</td>
        <td class="cell-toxics">${escapeHtml(e.appliedToxics || e.terminationReason || '-')}</td>
        <td class="cell-outcome">${outcomeBadge}</td>
      </tr>
    `;
  }).join('');
}

export async function clearJournal() {
  try {
    const res = await doFetch('/__admin/tcp-netem/journal', { method: 'DELETE' });
    if (res.ok) {
      await loadNetEmJournal();
    }
  } catch (e) {
    console.error('Failed to clear NetEm journal', e);
  }
}

function updateAttributeInputs(type) {
  const container = document.getElementById('netem-attribute-fields');
  if (!container) return;

  if (type === 'latency') {
    container.innerHTML = `
      <div class="form-row" style="display: flex; gap: 12px;">
        <div class="form-group" style="flex: 1;">
          <label>Latency (ms):</label>
          <input type="number" id="attr-latency" class="form-control" value="250" min="0">
        </div>
        <div class="form-group" style="flex: 1;">
          <label>Jitter (ms):</label>
          <input type="number" id="attr-jitter" class="form-control" value="50" min="0">
        </div>
      </div>
    `;
  } else if (type === 'bandwidth') {
    container.innerHTML = `
      <div class="form-group">
        <label>Rate (KB/s):</label>
        <input type="number" id="attr-rate" class="form-control" value="100" min="1">
      </div>
    `;
  } else if (type === 'slow_close') {
    container.innerHTML = `
      <div class="form-group">
        <label>Delay (ms):</label>
        <input type="number" id="attr-delay" class="form-control" value="1000" min="0">
      </div>
    `;
  } else if (type === 'slicer') {
    container.innerHTML = `
      <div class="form-row" style="display: flex; gap: 12px;">
        <div class="form-group" style="flex: 1;">
          <label>Average Size (bytes):</label>
          <input type="number" id="attr-avg-size" class="form-control" value="64" min="1">
        </div>
        <div class="form-group" style="flex: 1;">
          <label>Delay (ms):</label>
          <input type="number" id="attr-slicer-delay" class="form-control" value="10" min="0">
        </div>
      </div>
    `;
  } else if (type === 'limit_data') {
    container.innerHTML = `
      <div class="form-group">
        <label>Max Bytes:</label>
        <input type="number" id="attr-limit-bytes" class="form-control" value="1024" min="1">
      </div>
    `;
  } else {
    container.innerHTML = '<p class="muted-text">This impairment operates on connection level without custom attributes.</p>';
  }
}

async function submitNewToxic() {
  const name = document.getElementById('netem-toxic-name')?.value?.trim();
  if (!name) {
    alert('Please enter a unique toxic name.');
    return;
  }
  const type = document.getElementById('netem-toxic-type')?.value;
  const stream = document.getElementById('netem-toxic-stream')?.value;
  const toxicity = parseFloat(document.getElementById('netem-toxic-toxicity')?.value || '1.0');
  const correlationId = document.getElementById('netem-toxic-correlation-id')?.value?.trim() || null;

  const attributes = {};
  if (type === 'latency') {
    attributes.latency = parseInt(document.getElementById('attr-latency')?.value || '0', 10);
    attributes.jitter = parseInt(document.getElementById('attr-jitter')?.value || '0', 10);
  } else if (type === 'bandwidth') {
    attributes.rate = parseInt(document.getElementById('attr-rate')?.value || '100', 10);
  } else if (type === 'slow_close') {
    attributes.delay = parseInt(document.getElementById('attr-delay')?.value || '1000', 10);
  } else if (type === 'slicer') {
    attributes.average_size = parseInt(document.getElementById('attr-avg-size')?.value || '64', 10);
    attributes.delay = parseInt(document.getElementById('attr-slicer-delay')?.value || '10', 10);
  } else if (type === 'limit_data') {
    attributes.bytes = parseInt(document.getElementById('attr-limit-bytes')?.value || '1024', 10);
  }

  const payload = {
    name,
    type,
    stream,
    toxicity,
    correlationId,
    attributes
  };

  try {
    const res = await doFetch(`/__admin/tcp-netem/proxies/${encodeURIComponent(currentProxyName)}/toxics`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    });
    if (res.ok) {
      document.getElementById('netem-add-toxic-modal')?.classList.add('hidden');
      await loadNetEmStatus();
    } else {
      const err = await res.json().catch(() => ({}));
      alert(`Failed to add toxic: ${err.error || res.statusText}`);
    }
  } catch (e) {
    alert(`Error: ${e.message}`);
  }
}

function escapeHtml(str) {
  if (!str) return '';
  return String(str)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}
