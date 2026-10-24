/**
 * WireMock Console UI - TCP NetEm Module (ES Module).
 * Pure Vanilla JS, zero NPM dependencies.
 */

import { state } from './state.js';
import { doFetch } from './api.js';

let journalRefreshTimer = null;
let currentProxyName = 'wiremock-netem';
let currentPatternType = 'ASCII';

export function asciiToHex(str) {
  if (!str) return '';
  const bytes = new TextEncoder().encode(str);
  return Array.from(bytes)
    .map(b => b.toString(16).padStart(2, '0'))
    .join(' ');
}

export function hexToAscii(hex) {
  if (!hex) return '';
  const clean = hex.replace(/[^0-9a-fA-F]/g, '');
  if (clean.length === 0 || clean.length % 2 !== 0) return '';
  const bytes = new Uint8Array(clean.length / 2);
  for (let i = 0; i < clean.length; i += 2) {
    bytes[i / 2] = parseInt(clean.substring(i, i + 2), 16);
  }
  return new TextDecoder('utf-8', { fatal: false }).decode(bytes);
}

export function isValidHex(hex) {
  if (!hex) return true;
  const clean = hex.replace(/[^0-9a-fA-F]/g, '');
  return clean.length % 2 === 0;
}

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

  // ASCII / HEX Toggle
  const btnAscii = document.getElementById('btn-pattern-ascii');
  const btnHex = document.getElementById('btn-pattern-hex');
  const patternInput = document.getElementById('netem-toxic-pattern');
  const patternLbl = document.getElementById('lbl-toxic-pattern');
  const patternHint = document.getElementById('netem-pattern-hint');

  if (btnAscii && btnHex && patternInput) {
    btnAscii.addEventListener('click', () => {
      if (currentPatternType === 'ASCII') return;
      currentPatternType = 'ASCII';
      btnAscii.classList.add('active');
      btnHex.classList.remove('active');
      if (patternLbl) patternLbl.textContent = 'Stream Pattern Matcher (ASCII):';
      if (patternHint) patternHint.textContent = 'Matches unencoded UTF-8/ASCII substring in the stream.';
      patternInput.placeholder = 'e.g. chaos-test-session or order-service';
      if (patternInput.value.trim()) {
        patternInput.value = hexToAscii(patternInput.value);
      }
    });

    btnHex.addEventListener('click', () => {
      if (currentPatternType === 'HEX') return;
      currentPatternType = 'HEX';
      btnHex.classList.add('active');
      btnAscii.classList.remove('active');
      if (patternLbl) patternLbl.textContent = 'Stream Pattern Matcher (Hex / Raw Bytes):';
      if (patternHint) patternHint.textContent = 'Matches exact raw byte sequence on the wire.';
      patternInput.placeholder = 'e.g. CA 50 00 01 (Casual) or DE AD BE EF';
      if (patternInput.value.trim()) {
        patternInput.value = asciiToHex(patternInput.value);
      }
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
    currentProxyName = proxy.name;

    // Ingress badge
    const badge = document.getElementById('netem-ingress-info');
    if (badge) {
      badge.textContent = `Ingress: 127.0.0.1:${proxy.listenPort} ➔ Target: ${proxy.targetHost}:${proxy.targetPort} (${proxy.name})`;
    }

    // Stats
    const stats = proxy.stats || {};
    setStatValue('netem-stat-normal-transfers', stats.normalTransfers || 0);
    setStatValue('netem-stat-impaired-transfers', stats.impairedTransfers || 0);
    setStatValue('netem-stat-active-conn', stats.activeConnections || 0);
    setStatValue('netem-stat-active-toxics', (proxy.toxics || []).length);

    // Toxics
    renderToxicsList(proxy.toxics || []);
  } catch (e) {
    if (!silent) console.error('Failed to load NetEm status', e);
  }
}

function setStatValue(id, val) {
  const el = document.getElementById(id);
  if (el) el.textContent = String(val);
}

function renderNoProxies() {
  const container = document.getElementById('netem-toxics-list');
  if (container) {
    container.innerHTML = '<div class="empty-state">No TCP NetEm proxy active. Run Standalone with NetEm extension enabled.</div>';
  }
}

function renderToxicsList(toxics) {
  const container = document.getElementById('netem-toxics-list');
  if (!container) return;

  if (toxics.length === 0) {
    container.innerHTML = '<div class="empty-state">✅ No network impairments active. All traffic on port 8081 flows at line rate.</div>';
    return;
  }

  container.innerHTML = toxics.map(t => {
    const streamBadge = t.stream === 'upstream'
      ? '<span class="netem-badge badge-upstream">⬆ Upstream</span>'
      : '<span class="netem-badge badge-downstream">⬇ Downstream</span>';

    const pattern = t.pattern || t.correlationId;
    let scopeBadge = '<span class="netem-badge badge-all" title="Applies to all connections">Global</span>';
    if (pattern) {
      const typeLabel = t.patternType === 'HEX' ? 'Hex' : 'Scoped';
      scopeBadge = `<span class="netem-badge badge-corr" title="Stream pattern matcher">${typeLabel}: ${escapeHtml(pattern)}</span>`;
    }
    if (t.clientIp) {
      scopeBadge += ` <span class="netem-badge badge-ip" title="Client IP scope">IP: ${escapeHtml(t.clientIp)}</span>`;
    }

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
          <div class="toxic-tags">${streamBadge} ${scopeBadge} <span class="netem-badge">Rate: ${Math.round(t.toxicity * 100)}%</span></div>
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
  const filterCorr = document.getElementById('netem-journal-filter-corr')?.value?.trim();
  const filterOutcome = document.getElementById('netem-journal-filter-outcome')?.value;

  let url = '/__admin/tcp-netem/journal?limit=100';
  if (filterCorr) url += `&scope=${encodeURIComponent(filterCorr)}`;
  if (filterOutcome === 'impaired') url += '&impaired=true';
  else if (filterOutcome === 'normal') url += '&impaired=false';

  try {
    const res = await doFetch(url);
    if (!res.ok) return;
    const entries = await res.json();
    renderJournal(entries || []);
  } catch (e) {
    console.error('Failed to load NetEm journal', e);
  }
}

function renderJournal(entries) {
  const tbody = document.getElementById('netem-journal-tbody');
  if (!tbody) return;

  if (entries.length === 0) {
    tbody.innerHTML = '<tr class="empty-state-row"><td colspan="7" class="empty-state-cell">No matching network events recorded in NetEm journal.</td></tr>';
    return;
  }

  tbody.innerHTML = entries.map(e => {
    const timeStr = new Date(e.timestampEpochMs).toLocaleTimeString();
    const targetScope = e.scope || e.correlationId;
    const corrBadge = targetScope
      ? `<span class="badge-corr-table">${escapeHtml(targetScope)}</span>`
      : '<span class="muted-text">-</span>';

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
          <label>Average Slice Size (bytes):</label>
          <input type="number" id="attr-avg-size" class="form-control" value="64" min="1">
        </div>
        <div class="form-group" style="flex: 1;">
          <label>Inter-slice Delay (ms):</label>
          <input type="number" id="attr-slicer-delay" class="form-control" value="10" min="0">
        </div>
      </div>
    `;
  } else if (type === 'limit_data') {
    container.innerHTML = `
      <div class="form-group">
        <label>Byte Limit:</label>
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

  const patternVal = document.getElementById('netem-toxic-pattern')?.value?.trim()
    || document.getElementById('netem-toxic-correlation-id')?.value?.trim()
    || null;
  const clientIp = document.getElementById('netem-toxic-client-ip')?.value?.trim() || null;
  const patternType = patternVal ? currentPatternType : 'ASCII';

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
    pattern: patternVal,
    patternType,
    clientIp,
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
