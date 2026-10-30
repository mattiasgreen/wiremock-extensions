/**
 * HTTP Tester UI controller and execution history manager.
 * Manages headers key-value editor, JSON formatting, and response inspect panes.
 */
import { state } from './state.js';
import { elements, escapeHtml, formatBytes } from './dom.js';
import { highlightJson } from './highlighter.js';
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

  if (elements.btnTesterSend) {
    elements.btnTesterSend.addEventListener('click', executeTesterRequest);
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
  if (elements.btnPresetJson) {
    elements.btnPresetJson.addEventListener('click', () => appendHeader('Content-Type', 'application/json'));
  }
  if (elements.btnPresetBearer) {
    elements.btnPresetBearer.addEventListener('click', () => appendHeader('Authorization', 'Bearer <token>'));
  }
  if (elements.btnPresetAccept) {
    elements.btnPresetAccept.addEventListener('click', () => appendHeader('Accept', 'application/json'));
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
      try {
        const parsed = JSON.parse(elements.testerBody.value);
        elements.testerBody.value = JSON.stringify(parsed, null, 2);
        appendHeader('Content-Type', 'application/json');
      } catch (err) {
        alert('Invalid JSON: ' + err.message);
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
      if (elements.testerBody) elements.testerBody.value = '';
      updateTesterRoute(true);
    });
  }
}
