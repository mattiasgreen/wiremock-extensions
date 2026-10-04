/**
 * HTTP Tester UI controller and execution history manager.
 * Manages headers key-value editor, JSON formatting, and response inspect panes.
 */
import { state } from './state.js';
import { elements, escapeHtml, formatBytes } from './dom.js';
import { highlightJson } from './highlighter.js';
import { setRoute, toBase64 } from './router.js';

const TESTER_HISTORY_KEY = 'wiremock_ui_tester_history';

export function appendHeader(name, value) {
  if (!elements.testerHeaders) return;
  const current = elements.testerHeaders.value.trim();
  if (current.toLowerCase().includes(name.toLowerCase() + ':')) return;
  elements.testerHeaders.value = (current ? current + '\n' : '') + `${name}: ${value}`;
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
  if (elements.testerHistoryCount) {
    elements.testerHistoryCount.textContent = state.testerHistory.length;
  }
  if (state.testerHistory.length === 0) {
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
        <span class="history-item-meta">${timeStr}</span>
      </div>
      <div class="history-item-url" title="${escapeHtml(item.url)}">${escapeHtml(item.url)}</div>
      <div class="history-item-meta">${item.duration || 0} ms &bull; ${formatBytes(item.size || 0)}</div>
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
