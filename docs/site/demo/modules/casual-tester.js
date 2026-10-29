/**
 * Casual XATMI Tester UI controller and execution history manager.
 * Manages service call (tpcall) execution, JSON formatting, ATMI error state rendering,
 * and persistent call history.
 */
import { state } from './state.js';
import { elements, escapeHtml } from './dom.js';
import { highlightJson } from './highlighter.js';
import { setRoute, toBase64, fromBase64 } from './router.js';
import { doFetch } from './api.js';

const CASUAL_HISTORY_KEY = 'wiremock_ui_casual_history';
const CASUAL_COLLAPSED_KEY = 'wiremock_ui_casual_history_collapsed';

let lastRawCasualResponse = '';
let casualViewMode = 'formatted'; // 'formatted' or 'raw'

export function initCasualTester() {
  initCasualHistoryCollapse();
  loadCasualHistoryFromStorage();
  fetchCasualConfig();

  // Restore from URL query params if present
  const hash = window.location.hash || '';
  if (hash.startsWith('#casual')) {
    const qIdx = hash.indexOf('?');
    if (qIdx !== -1) {
      const params = new URLSearchParams(hash.substring(qIdx + 1));
      const s = params.get('s');
      const b = params.get('b');
      const p = params.get('p');
      if (s && elements.casualServiceName) elements.casualServiceName.value = s;
      if (b && elements.casualBufferType) elements.casualBufferType.value = b;
      if (p && elements.casualRequestPayload) elements.casualRequestPayload.value = fromBase64(p);
    }
  }
}

export function fetchCasualConfig() {
  doFetch('/__admin/casual/config', { method: 'GET' })
    .then(res => {
      if (res.status >= 200 && res.status < 300) {
        return res.json();
      }
      return null;
    })
    .then(config => {
      if (!config) return;
      if (elements.casualTargetPort && config.port) {
        elements.casualTargetPort.value = config.port;
      }
      if (elements.casualGatewayStatus) {
        const runningStr = config.running ? 'Running' : 'Ready';
        elements.casualGatewayStatus.textContent = `Casual Gateway: Port ${config.port} (${runningStr})`;
      }
    })
    .catch(() => {
      // Graceful fallback if endpoint is not available
    });
}

export function updateCasualRoute(replace = true) {
  if (!elements.casualServiceName) return;
  const s = elements.casualServiceName.value.trim();
  const b = elements.casualBufferType ? elements.casualBufferType.value : 'JSON';
  const p = elements.casualRequestPayload ? elements.casualRequestPayload.value.trim() : '';

  setRoute('casual', {
    s: s && s !== 'echoService' ? s : null,
    b: b !== 'JSON' ? b : null,
    p: p ? toBase64(p) : null
  }, replace);
}

export function initCasualHistoryCollapse() {
  try {
    const saved = localStorage.getItem(CASUAL_COLLAPSED_KEY);
    if (saved === 'true') {
      setCasualHistoryCollapsed(true);
    }
  } catch (_) {}
}

export function setCasualHistoryCollapsed(collapsed) {
  state.casualHistoryCollapsed = !!collapsed;
  if (elements.casualHistoryPanel) {
    if (state.casualHistoryCollapsed) {
      elements.casualHistoryPanel.classList.add('collapsed');
    } else {
      elements.casualHistoryPanel.classList.remove('collapsed');
    }
  }
  try {
    localStorage.setItem(CASUAL_COLLAPSED_KEY, state.casualHistoryCollapsed ? 'true' : 'false');
  } catch (_) {}
}

export function toggleCasualHistory() {
  setCasualHistoryCollapsed(!state.casualHistoryCollapsed);
}

export function loadCasualHistoryFromStorage() {
  try {
    const saved = localStorage.getItem(CASUAL_HISTORY_KEY);
    state.casualHistory = saved ? JSON.parse(saved) : [];
  } catch (_) {
    state.casualHistory = [];
  }
  renderCasualHistory();
}

export function saveCasualHistoryItem(item) {
  if (!state.casualHistory) state.casualHistory = [];
  state.casualHistory.unshift(item);
  if (state.casualHistory.length > 50) state.casualHistory.pop();
  try {
    localStorage.setItem(CASUAL_HISTORY_KEY, JSON.stringify(state.casualHistory));
  } catch (_) {}
  renderCasualHistory();
}

export function clearCasualHistory() {
  state.casualHistory = [];
  try {
    localStorage.removeItem(CASUAL_HISTORY_KEY);
  } catch (_) {}
  renderCasualHistory();
}

export function renderCasualHistory() {
  if (!elements.casualHistoryList) return;
  const count = state.casualHistory ? state.casualHistory.length : 0;
  if (elements.casualHistoryCount) {
    elements.casualHistoryCount.textContent = count;
  }
  if (elements.casualHistoryCollapsedCount) {
    elements.casualHistoryCollapsedCount.textContent = count;
  }
  if (count === 0) {
    elements.casualHistoryList.innerHTML = '<div class="empty-state text-small">No Casual calls sent yet.</div>';
    return;
  }

  elements.casualHistoryList.innerHTML = '';
  state.casualHistory.forEach((item, index) => {
    const card = document.createElement('div');
    card.className = 'history-item';
    card.setAttribute('data-casual-idx', index);
    card.setAttribute('data-testid', 'casual-history-item');

    const isOk = item.errorState === 'OK';
    const statusCls = isOk ? 'status-200' : 'status-500';
    const timeStr = item.timestamp ? new Date(item.timestamp).toLocaleTimeString() : '';

    card.innerHTML = `
      <div class="history-item-top">
        <span class="http-badge badge-get">TPCALL</span>
        <span class="card-value ${statusCls}" style="font-size: 0.78rem; margin: 0;">${escapeHtml(item.errorState || 'ERR')}</span>
        <span class="history-time-meta">${timeStr}</span>
      </div>
      <div class="history-item-url" title="${escapeHtml(item.serviceName)}">${escapeHtml(item.serviceName)}</div>
      <div class="history-item-meta">
        <span>${item.durationMs || 0} ms</span>
        <span>${escapeHtml(item.bufferType || 'JSON')}</span>
      </div>
    `;

    card.addEventListener('click', () => {
      document.querySelectorAll('#casual-history-list .history-item').forEach(el => el.classList.remove('active'));
      card.classList.add('active');
      restoreCasualHistoryItem(item);
    });

    elements.casualHistoryList.appendChild(card);
  });
}

export function restoreCasualHistoryItem(item) {
  if (elements.casualServiceName) elements.casualServiceName.value = item.serviceName || '';
  if (elements.casualBufferType) elements.casualBufferType.value = item.bufferType || 'JSON';
  if (elements.casualTargetHost && item.host) elements.casualTargetHost.value = item.host;
  if (elements.casualTargetPort && item.port) elements.casualTargetPort.value = item.port;
  if (elements.casualRequestPayload) elements.casualRequestPayload.value = item.requestPayload || '';

  displayCasualResponseResult(
    item.errorState || 'OK',
    item.errorCode !== undefined ? item.errorCode : 0,
    item.userCode || 0,
    item.durationMs || 0,
    item.execution || '',
    item.responsePayload || ''
  );
  updateCasualRoute(true);
}

export function formatCasualJsonPayload() {
  if (!elements.casualRequestPayload) return;
  const raw = elements.casualRequestPayload.value.trim();
  if (!raw) return;
  try {
    const parsed = JSON.parse(raw);
    elements.casualRequestPayload.value = JSON.stringify(parsed, null, 2);
    updateCasualRoute(true);
  } catch (err) {
    // Leave intact if unparseable
  }
}

export function resetCasualForm() {
  if (elements.casualServiceName) elements.casualServiceName.value = 'echoService';
  if (elements.casualBufferType) elements.casualBufferType.value = 'JSON';
  if (elements.casualRequestPayload) elements.casualRequestPayload.value = '{\n  "message": "Hello Casual"\n}';
  if (elements.casualTargetHost) elements.casualTargetHost.value = '127.0.0.1';
  fetchCasualConfig();
  updateCasualRoute(true);
}

export function setCasualViewMode(mode) {
  casualViewMode = mode;
  if (elements.btnCasualViewHighlighted) {
    elements.btnCasualViewHighlighted.classList.toggle('active', mode === 'formatted');
  }
  if (elements.btnCasualViewRaw) {
    elements.btnCasualViewRaw.classList.toggle('active', mode === 'raw');
  }
  renderCasualResponseBody(lastRawCasualResponse);
}

export function renderCasualResponseBody(bodyText) {
  lastRawCasualResponse = bodyText || '';
  if (!elements.casualResponseBody) return;

  if (casualViewMode === 'raw') {
    elements.casualResponseBody.textContent = lastRawCasualResponse;
  } else {
    elements.casualResponseBody.innerHTML = highlightJson(lastRawCasualResponse);
  }
}

export function displayCasualResponseResult(errorState, errorCode, userCode, durationMs, execution, payload) {
  if (elements.casualResponseStatus) {
    const isOk = errorState === 'OK';
    elements.casualResponseStatus.textContent = `${errorState} (${errorCode})`;
    elements.casualResponseStatus.className = `card-value ${isOk ? 'status-200' : 'status-500'}`;
  }
  if (elements.casualResponseTime) {
    elements.casualResponseTime.textContent = `${durationMs} ms`;
  }
  if (elements.casualResponseCode) {
    elements.casualResponseCode.textContent = `UserCode: ${userCode}`;
  }
  if (elements.casualResponseExecution) {
    elements.casualResponseExecution.textContent = execution ? `UUID: ${execution}` : '';
  }
  renderCasualResponseBody(payload);
}

export function executeCasualRequest() {
  if (!elements.casualServiceName) return;
  const service = elements.casualServiceName.value.trim();
  if (!service) {
    if (elements.casualResponseBody) {
      elements.casualResponseBody.textContent = 'Error: Service name is required.';
    }
    return;
  }

  const host = elements.casualTargetHost ? elements.casualTargetHost.value.trim() || '127.0.0.1' : '127.0.0.1';
  const port = elements.casualTargetPort ? parseInt(elements.casualTargetPort.value.trim(), 10) || 7772 : 7772;
  const bufferType = elements.casualBufferType ? elements.casualBufferType.value : 'JSON';
  const payload = elements.casualRequestPayload ? elements.casualRequestPayload.value.trim() : '{}';

  if (elements.btnCasualSend) {
    elements.btnCasualSend.setAttribute('disabled', 'true');
    elements.btnCasualSend.textContent = '⏳ Executing...';
  }

  const callBody = JSON.stringify({
    service: service,
    host: host,
    port: port,
    bufferType: bufferType,
    payload: payload,
    timeoutSeconds: 4
  });

  const startTime = performance.now();

  doFetch('/__admin/casual/call', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: callBody
  })
    .then(res => res.json())
    .then(data => {
      const elapsed = Math.round(performance.now() - startTime);
      displayCasualResponseResult(
        data.errorState || 'OK',
        data.errorCode !== undefined ? data.errorCode : 0,
        data.userCode || 0,
        data.durationMs || elapsed,
        data.execution || '',
        data.payload || ''
      );

      saveCasualHistoryItem({
        timestamp: Date.now(),
        serviceName: service,
        host: host,
        port: port,
        bufferType: bufferType,
        requestPayload: payload,
        errorState: data.errorState || 'OK',
        errorCode: data.errorCode !== undefined ? data.errorCode : 0,
        userCode: data.userCode || 0,
        durationMs: data.durationMs || elapsed,
        execution: data.execution || '',
        responsePayload: data.payload || ''
      });
      updateCasualRoute(true);
    })
    .catch(err => {
      const elapsed = Math.round(performance.now() - startTime);
      displayCasualResponseResult('ERROR', -1, -1, elapsed, '', 'Error connecting to Admin API: ' + err.message);

      saveCasualHistoryItem({
        timestamp: Date.now(),
        serviceName: service,
        host: host,
        port: port,
        bufferType: bufferType,
        requestPayload: payload,
        errorState: 'ERROR',
        errorCode: -1,
        userCode: -1,
        durationMs: elapsed,
        execution: '',
        responsePayload: err.message
      });
    })
    .finally(() => {
      if (elements.btnCasualSend) {
        elements.btnCasualSend.removeAttribute('disabled');
        elements.btnCasualSend.textContent = '🔌 Execute tpcall';
      }
    });
}
