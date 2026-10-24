/**
 * HTTP Tester UI controller and execution history manager.
 * Manages headers key-value editor, JSON formatting, and response inspect panes.
 */
import { state } from './state.js';
import { elements, escapeHtml, formatBytes } from './dom.js';
import { highlightJson } from './highlighter.js';
import { setRoute, toBase64 } from './router.js';

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
  setRoute('tester', {
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
  const count = state.testerHistory.length;
  if (elements.testerHistoryCount) {
    elements.testerHistoryCount.textContent = count;
  }
  if (elements.testerHistoryCollapsedCount) {
    elements.testerHistoryCollapsedCount.textContent = count;
  }
  if (count === 0) {
    elements.testerHistoryList.innerHTML = '<div class="empty-state text-small">No requests sent yet.</div>';
    return;
  }

  elements.testerHistoryList.innerHTML = '';
  state.testerHistory.forEach((item, index) => {
    const card = document.createElement('div');
    card.className = 'history-item';
    card.setAttribute('data-history-idx', index);
    card.setAttribute('data-testid', 'history-item');

    const statusCls = item.status >= 500 ? 'status-500' : (item.status >= 400 ? 'status-400' : 'status-200');
    const timeStr = item.timestamp ? new Date(item.timestamp).toLocaleTimeString() : '';

    card.innerHTML = `
      <div class="history-item-top">
        <span class="http-badge badge-${(item.method || 'GET').toLowerCase()}">${escapeHtml(item.method || 'GET')}</span>
        <span class="card-value ${statusCls}" style="font-size: 0.78rem; margin: 0;">${escapeHtml(item.status ? String(item.status) : 'ERR')}</span>
        <span class="history-time-meta">${timeStr}</span>
      </div>
      <div class="history-item-url" title="${escapeHtml(item.url)}">${escapeHtml(item.url)}</div>
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
  if (elements.testerMethod) elements.testerMethod.value = item.method;
  if (elements.testerUrl) elements.testerUrl.value = item.url;
  if (elements.testerHeaders) elements.testerHeaders.value = item.headersText || '';
  if (elements.testerBody) elements.testerBody.value = item.bodyText || '';

  renderLocalHeaderChips();
  displaySentRequest(item.method, item.url, item.headersText || '', item.bodyText || '');
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
    elements.testerSentBody.textContent = bodyText.trim() || '(empty body)';
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
  renderTesterResponse(responseBody);
}

export function renderTesterResponse(bodyText) {
  state.lastRawResponseBody = bodyText;
  if (!elements.testerResponseBody) return;

  if (state.testerViewMode === 'raw') {
    elements.testerResponseBody.textContent = bodyText;
  } else {
    elements.testerResponseBody.innerHTML = highlightJson(bodyText);
  }
}

// -----------------------------------------------------------------------------
// Browser-Persistent Corporate Headers (Local Storage)
// -----------------------------------------------------------------------------

export const LOCAL_HEADERS_KEY = 'wiremock_ui_local_headers';
export const LOCAL_HEADERS_ENABLED_KEY = 'wiremock_ui_local_headers_enabled';

export function loadLocalHeaders() {
  try {
    const raw = localStorage.getItem(LOCAL_HEADERS_KEY);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) return parsed;
    }
  } catch (_) {}
  return [];
}

export function saveLocalHeaders(headers) {
  try {
    localStorage.setItem(LOCAL_HEADERS_KEY, JSON.stringify(headers));
  } catch (_) {}
}

export function isLocalHeadersEnabled() {
  try {
    const val = localStorage.getItem(LOCAL_HEADERS_ENABLED_KEY);
    return val !== 'false';
  } catch (_) {
    return true;
  }
}

export function setLocalHeadersEnabled(enabled) {
  try {
    localStorage.setItem(LOCAL_HEADERS_ENABLED_KEY, enabled ? 'true' : 'false');
  } catch (_) {}
  renderLocalHeaderChips();
}

export function resolveMacro(value) {
  if (!value || typeof value !== 'string') return value;
  let res = value;
  if (res.includes('{{uuid}}')) {
    const uuid = (typeof crypto !== 'undefined' && crypto.randomUUID)
      ? crypto.randomUUID()
      : 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
          const r = Math.random() * 16 | 0;
          const v = c === 'x' ? r : (r & 0x3 | 0x8);
          return v.toString(16);
        });
    res = res.replaceAll('{{uuid}}', uuid);
  }
  if (res.includes('{{now}}')) {
    res = res.replaceAll('{{now}}', new Date().toISOString());
  }
  if (res.includes('{{timestamp}}')) {
    res = res.replaceAll('{{timestamp}}', String(Date.now()));
  }
  return res;
}

export function getEffectiveTesterHeaders(textareaText, resolveMacros = true) {
  const customHeaders = parseHeadersInput(textareaText);
  const customKeysLower = new Set(Object.keys(customHeaders).map(k => k.toLowerCase()));

  const localHeaders = loadLocalHeaders();
  const enabled = isLocalHeadersEnabled();

  const effective = {};
  const overriddenKeys = new Set();
  const appliedLocalHeaders = [];

  if (enabled) {
    for (const h of localHeaders) {
      if (!h.enabled || !h.name || !h.name.trim()) continue;
      const name = h.name.trim();
      if (customKeysLower.has(name.toLowerCase())) {
        overriddenKeys.add(name.toLowerCase());
      } else {
        const finalVal = resolveMacros ? resolveMacro(h.value || '') : (h.value || '');
        effective[name] = finalVal;
        appliedLocalHeaders.push({ name, value: finalVal, rawValue: h.value || '' });
      }
    }
  }

  for (const [k, v] of Object.entries(customHeaders)) {
    effective[k] = v;
  }

  return {
    headers: effective,
    customHeaders,
    overriddenKeys,
    appliedLocalHeaders,
    localHeaders,
    isEnabled: enabled
  };
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

export function openLocalHeadersModal() {
  if (!elements.modalLocalHeaders || !elements.localHeadersTbody) return;
  const list = loadLocalHeaders();
  elements.localHeadersTbody.innerHTML = '';
  if (list.length === 0) {
    addLocalHeaderRow('X-Correlation-Id', '{{uuid}}', true);
    addLocalHeaderRow('X-Tenant-Id', 'corp-default', true);
  } else {
    list.forEach(h => addLocalHeaderRow(h.name, h.value, h.enabled !== false));
  }
  elements.modalLocalHeaders.classList.remove('hidden');
}

export function closeLocalHeadersModal() {
  if (elements.modalLocalHeaders) {
    elements.modalLocalHeaders.classList.add('hidden');
  }
}

export function addLocalHeaderRow(name = '', value = '', enabled = true) {
  if (!elements.localHeadersTbody) return;
  const tr = document.createElement('tr');
  tr.className = 'local-header-row';
  tr.innerHTML = `
    <td style="text-align: center;">
      <input type="checkbox" class="header-row-enabled" ${enabled ? 'checked' : ''} title="Enable this header">
    </td>
    <td>
      <input type="text" class="form-control header-row-name" placeholder="Header-Name" value="${escapeHtml(name)}" autocomplete="off" spellcheck="false">
    </td>
    <td>
      <input type="text" class="form-control header-row-value" placeholder="Value or {{uuid}}" value="${escapeHtml(value)}" autocomplete="off" spellcheck="false">
    </td>
    <td style="text-align: center;">
      <button type="button" class="btn-del-header-row" title="Delete header">🗑️</button>
    </td>
  `;
  const delBtn = tr.querySelector('.btn-del-header-row');
  if (delBtn) {
    delBtn.addEventListener('click', () => tr.remove());
  }
  elements.localHeadersTbody.appendChild(tr);
}

export function saveLocalHeadersFromModal() {
  if (!elements.localHeadersTbody) return;
  const rows = elements.localHeadersTbody.querySelectorAll('.local-header-row');
  const headers = [];
  rows.forEach(r => {
    const enabledInput = r.querySelector('.header-row-enabled');
    const nameInput = r.querySelector('.header-row-name');
    const valInput = r.querySelector('.header-row-value');
    const name = nameInput ? nameInput.value.trim() : '';
    const val = valInput ? valInput.value : '';
    const isEnabled = enabledInput ? enabledInput.checked : true;
    if (name) {
      headers.push({ name, value: val, enabled: isEnabled });
    }
  });
  saveLocalHeaders(headers);
  closeLocalHeadersModal();
  renderLocalHeaderChips();
}
