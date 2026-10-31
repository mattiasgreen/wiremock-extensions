/**
 * Casual XATMI Tester UI controller and execution history manager.
 * Manages service call (tpcall) execution, JSON formatting, ATMI error state rendering,
 * and persistent call history.
 */
import { state } from './state.js';
import { elements, escapeHtml } from './dom.js';
import { highlightJson, highlightPayload, formatPayload } from './highlighter.js';
import { discoverEmbeddedPayloads, renderFormattedWithNestedPreviews, attachEmbeddedPayloadsBar } from './payload-lens.js';
import { setRoute, toBase64, fromBase64 } from './router.js';
import { doFetch } from './api.js';

const CASUAL_HISTORY_KEY = 'wiremock_ui_casual_history';
const CASUAL_COLLAPSED_KEY = 'wiremock_ui_casual_history_collapsed';

let lastRawCasualResponse = '';
let lastHexCasualResponse = '';
let lastCasualResponseData = null;
let lastCasualResponseHeaders = null;
let lastCasualRequestHeaders = null;
let lastCasualSentBody = '';
let lastCasualSentService = '';
let casualViewMode = 'formatted'; // 'formatted', 'raw', or 'hex'
let casualActiveInspectorTab = 'response'; // 'response', 'request', or 'both'

function formatHeadersBlock(headersObj) {
  if (!headersObj) return '(no headers)';
  const entries = Object.entries(headersObj);
  if (entries.length === 0) return '(no headers)';
  return entries.map(([k, v]) => `${k}: ${v}`).join('\n');
}

export function initCasualTester() {
  initCasualHistoryCollapse();
  loadCasualHistoryFromStorage();
  fetchCasualConfig();
  syncDefaultCasualHeaders();

  if (elements.casualServiceName) {
    elements.casualServiceName.addEventListener('input', () => syncDefaultCasualHeaders());
  }
  if (elements.casualBufferType) {
    elements.casualBufferType.addEventListener('change', () => syncDefaultCasualHeaders());
  }

  if (elements.casualBufferType && elements.casualPayloadEncoding) {
    elements.casualBufferType.addEventListener('change', () => {
      const val = elements.casualBufferType.value;
      if (val === 'X_OCTET' || val === 'FIELDED') {
        if (elements.casualPayloadEncoding.value === 'text') {
          elements.casualPayloadEncoding.value = 'hex';
        }
      } else if (val === 'JSON') {
        if (elements.casualPayloadEncoding.value === 'hex') {
          elements.casualPayloadEncoding.value = 'text';
        }
      }
    });
  }

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

  if (elements.btnCasualSend) {
    elements.btnCasualSend.addEventListener('click', executeCasualRequest);
  }
  const tabCasualEl = document.getElementById('tab-casual');
  if (tabCasualEl) {
    tabCasualEl.addEventListener('keydown', (e) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
        e.preventDefault();
        executeCasualRequest();
      }
    });
  }
  if (elements.btnCasualFormatJson) {
    elements.btnCasualFormatJson.addEventListener('click', formatCasualJsonPayload);
  }
  if (elements.btnCasualReset) {
    elements.btnCasualReset.addEventListener('click', resetCasualForm);
  }
  if (elements.btnToggleCasualHistory) {
    elements.btnToggleCasualHistory.addEventListener('click', toggleCasualHistory);
  }
  if (elements.btnExpandCasualHistory) {
    elements.btnExpandCasualHistory.addEventListener('click', () => setCasualHistoryCollapsed(false));
  }
  if (elements.btnCasualClearHistory) {
    elements.btnCasualClearHistory.addEventListener('click', clearCasualHistory);
  }
  if (elements.btnCasualViewHighlighted) {
    elements.btnCasualViewHighlighted.addEventListener('click', () => setCasualViewMode('formatted'));
  }
  if (elements.btnCasualViewRaw) {
    elements.btnCasualViewRaw.addEventListener('click', () => setCasualViewMode('raw'));
  }
  if (elements.btnCasualViewHex) {
    elements.btnCasualViewHex.addEventListener('click', () => setCasualViewMode('hex'));
  }
  if (elements.btnCasualDownloadBinary) {
    elements.btnCasualDownloadBinary.addEventListener('click', downloadCasualBinaryResponse);
  }
  if (elements.btnCasualUploadFile && elements.casualFileInput) {
    elements.btnCasualUploadFile.addEventListener('click', () => elements.casualFileInput.click());
    elements.casualFileInput.addEventListener('change', handleCasualFileUpload);
  }
  if (elements.btnCasualTabResponse) {
    elements.btnCasualTabResponse.addEventListener('click', () => setCasualInspectorTab('response'));
  }
  if (elements.btnCasualTabRequest) {
    elements.btnCasualTabRequest.addEventListener('click', () => setCasualInspectorTab('request'));
  }
  if (elements.btnCasualTabBoth) {
    elements.btnCasualTabBoth.addEventListener('click', () => setCasualInspectorTab('both'));
  }
  if (elements.btnCasualPresetCorr) {
    elements.btnCasualPresetCorr.addEventListener('click', () => appendCasualHeader('X-Correlation-Id', crypto.randomUUID()));
  }
  if (elements.btnCasualPresetTx) {
    elements.btnCasualPresetTx.addEventListener('click', () => appendCasualHeader('X-Casual-Transaction-State', 'TX_ACTIVE'));
  }
  if (elements.btnCasualCopyResponse) {
    elements.btnCasualCopyResponse.addEventListener('click', () => {
      if (elements.casualResponseBody && elements.casualResponseBody.textContent) {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(elements.casualResponseBody.textContent).catch(() => {});
        }
        elements.btnCasualCopyResponse.textContent = '✅ Copied!';
        setTimeout(() => elements.btnCasualCopyResponse.textContent = '📋 Copy', 1500);
      }
    });
  }
  [elements.casualServiceName, elements.casualBufferType, elements.casualTargetHost, elements.casualTargetPort, elements.casualRequestPayload].forEach(el => {
    if (el) el.addEventListener('change', () => updateCasualRoute(true));
  });

  if (elements.casualRequestPayload && elements.casualPayloadEmbeddedBar) {
    attachEmbeddedPayloadsBar(elements.casualRequestPayload, elements.casualPayloadEmbeddedBar, () => updateCasualRoute(true));
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
  if (elements.casualRequestHeaders && item.requestHeaders) {
    const lines = Object.entries(item.requestHeaders).map(([k, v]) => `${k}: ${v}`).join('\n');
    elements.casualRequestHeaders.value = lines;
  } else {
    syncDefaultCasualHeaders();
  }

  lastCasualResponseData = {
    service: item.serviceName,
    payload: item.responsePayload,
    isBinary: item.isBinary,
    sizeBytes: item.sizeBytes,
    payloadHex: item.payloadHex,
    headers: item.headers,
    requestHeaders: item.requestHeaders
  };

  displayCasualResponseResult(
    item.errorState || 'OK',
    item.errorCode !== undefined ? item.errorCode : 0,
    item.userCode || 0,
    item.durationMs || 0,
    item.execution || '',
    item.responsePayload || '',
    item.isBinary || false,
    item.sizeBytes,
    item.payloadHex || '',
    item.headers || null,
    item.requestHeaders || null,
    item.requestPayload || '',
    item.serviceName || ''
  );
  updateCasualRoute(true);
}

export function formatCasualJsonPayload() {
  if (!elements.casualRequestPayload) return;
  const raw = elements.casualRequestPayload.value.trim();
  if (!raw) return;
  elements.casualRequestPayload.value = formatPayload(raw);
  updateCasualRoute(true);
}

export function syncDefaultCasualHeaders() {
  if (!elements.casualRequestHeaders) return;
  const current = elements.casualRequestHeaders.value.trim();
  const service = elements.casualServiceName ? elements.casualServiceName.value.trim() : 'echoService';
  const bufferType = elements.casualBufferType ? elements.casualBufferType.value : 'JSON';
  const ctype = bufferType === 'X_OCTET'
    ? 'application/octet-stream'
    : (bufferType.includes('STRING') ? 'text/plain; charset=UTF-8' : 'application/json');

  if (!current || current.startsWith('Content-Type:') || current.startsWith('X-Casual-Service:')) {
    elements.casualRequestHeaders.value = `Content-Type: ${ctype}\nX-Casual-Service: ${service}\nX-Casual-Buffer-Type: ${bufferType}`;
  }
}

export function appendCasualHeader(name, value) {
  if (!elements.casualRequestHeaders) return;
  const current = elements.casualRequestHeaders.value.trim();
  const line = `${name}: ${value}`;
  if (!current) {
    elements.casualRequestHeaders.value = line;
  } else {
    elements.casualRequestHeaders.value = `${current}\n${line}`;
  }
}

export function resetCasualForm() {
  if (elements.casualServiceName) elements.casualServiceName.value = 'echoService';
  if (elements.casualBufferType) elements.casualBufferType.value = 'JSON';
  if (elements.casualRequestPayload) elements.casualRequestPayload.value = '{\n  "message": "Hello Casual"\n}';
  if (elements.casualTargetHost) elements.casualTargetHost.value = '127.0.0.1';
  if (elements.casualRequestHeaders) {
    elements.casualRequestHeaders.value = '';
    syncDefaultCasualHeaders();
  }
  fetchCasualConfig();
  updateCasualRoute(true);
}

export function setCasualInspectorTab(tab) {
  casualActiveInspectorTab = tab;
  [elements.btnCasualTabResponse, elements.btnCasualTabRequest, elements.btnCasualTabBoth].forEach(b => {
    if (b) b.classList.remove('active');
  });

  const container = elements.casualInspectorContent || document.getElementById('casual-inspector-content');
  if (!container) return;

  if (tab === 'response') {
    if (elements.btnCasualTabResponse) elements.btnCasualTabResponse.classList.add('active');
    if (elements.casualViewResponse) {
      elements.casualViewResponse.classList.remove('hidden');
      elements.casualViewResponse.classList.add('active');
    }
    if (elements.casualViewRequest) {
      elements.casualViewRequest.classList.add('hidden');
      elements.casualViewRequest.classList.remove('active');
    }
    container.classList.remove('both-mode');
  } else if (tab === 'request') {
    if (elements.btnCasualTabRequest) elements.btnCasualTabRequest.classList.add('active');
    if (elements.casualViewRequest) {
      elements.casualViewRequest.classList.remove('hidden');
      elements.casualViewRequest.classList.add('active');
    }
    if (elements.casualViewResponse) {
      elements.casualViewResponse.classList.add('hidden');
      elements.casualViewResponse.classList.remove('active');
    }
    container.classList.remove('both-mode');
  } else if (tab === 'both') {
    if (elements.btnCasualTabBoth) elements.btnCasualTabBoth.classList.add('active');
    if (elements.casualViewResponse) {
      elements.casualViewResponse.classList.remove('hidden');
      elements.casualViewResponse.classList.add('active');
    }
    if (elements.casualViewRequest) {
      elements.casualViewRequest.classList.remove('hidden');
      elements.casualViewRequest.classList.add('active');
    }
    container.classList.add('both-mode');
  }
}

export function setCasualViewMode(mode) {
  casualViewMode = mode;
  if (elements.btnCasualViewHighlighted) {
    elements.btnCasualViewHighlighted.classList.toggle('active', mode === 'formatted');
  }
  if (elements.btnCasualViewRaw) {
    elements.btnCasualViewRaw.classList.toggle('active', mode === 'raw');
  }
  if (elements.btnCasualViewHex) {
    elements.btnCasualViewHex.classList.toggle('active', mode === 'hex');
  }
  renderCasualResponseBody(lastRawCasualResponse, lastHexCasualResponse);
}

export function renderCasualResponseBody(bodyText, hexText) {
  if (bodyText !== undefined) lastRawCasualResponse = bodyText || '';
  if (hexText !== undefined) lastHexCasualResponse = hexText || '';
  if (!elements.casualResponseBody) return;

  if (casualViewMode === 'hex') {
    elements.casualResponseBody.textContent = lastHexCasualResponse || lastRawCasualResponse;
  } else if (casualViewMode === 'raw') {
    elements.casualResponseBody.textContent = lastRawCasualResponse;
  } else {
    if (lastCasualResponseData && lastCasualResponseData.isBinary) {
      elements.casualResponseBody.textContent = lastHexCasualResponse || lastRawCasualResponse;
    } else {
      const discovered = discoverEmbeddedPayloads(lastRawCasualResponse);
      if (discovered.length > 0) {
        elements.casualResponseBody.innerHTML = renderFormattedWithNestedPreviews(lastRawCasualResponse, discovered, 'casual_res');
      } else {
        elements.casualResponseBody.innerHTML = highlightPayload(lastRawCasualResponse);
      }
    }
  }
}

export function displayCasualResponseResult(
  errorState,
  errorCode,
  userCode,
  durationMs,
  execution,
  payload,
  isBinary = false,
  sizeBytes,
  payloadHex = '',
  headers = null,
  requestHeaders = null,
  sentPayload = '',
  sentService = ''
) {
  if (elements.casualResponseStatus) {
    const isOk = errorState === 'OK';
    elements.casualResponseStatus.textContent = `${errorState} (${errorCode})`;
    elements.casualResponseStatus.className = `card-value ${isOk ? 'status-200' : 'status-500'}`;
  }
  if (elements.casualResponseTime) {
    elements.casualResponseTime.textContent = `${durationMs} ms`;
  }
  if (elements.casualResponseCode) {
    const sizeStr = (isBinary && sizeBytes !== undefined) ? ` | ${sizeBytes} B` : '';
    elements.casualResponseCode.textContent = `UserCode: ${userCode}${sizeStr}`;
  }
  if (elements.casualResponseExecution) {
    elements.casualResponseExecution.textContent = execution ? `UUID: ${execution}` : '';
  }

  if (elements.btnCasualDownloadBinary) {
    elements.btnCasualDownloadBinary.style.display = isBinary ? 'inline-flex' : 'none';
  }

  // Response headers
  lastCasualResponseHeaders = headers;
  if (elements.casualResponseHeaders) {
    elements.casualResponseHeaders.textContent = formatHeadersBlock(headers);
  }
  if (elements.casualResponseHeaderCount) {
    elements.casualResponseHeaderCount.textContent = headers ? Object.keys(headers).length : 0;
  }

  // Sent Request view
  lastCasualRequestHeaders = requestHeaders;
  lastCasualSentBody = sentPayload;
  lastCasualSentService = sentService;
  if (elements.casualSentHeaders) {
    elements.casualSentHeaders.textContent = formatHeadersBlock(requestHeaders);
  }
  if (elements.casualSentBody) {
    elements.casualSentBody.textContent = sentPayload || '(empty body)';
  }
  if (elements.casualSentServiceSummary) {
    elements.casualSentServiceSummary.textContent = sentService || 'tpcall';
  }

  lastRawCasualResponse = payload || '';
  lastHexCasualResponse = payloadHex || '';
  if (isBinary && casualViewMode === 'formatted') {
    setCasualViewMode('hex');
  } else {
    renderCasualResponseBody(payload, payloadHex);
  }
}

export function handleCasualFileUpload(event) {
  const file = event?.target?.files?.[0];
  if (!file) return;

  const encoding = elements.casualPayloadEncoding ? elements.casualPayloadEncoding.value : 'text';

  if (encoding === 'hex') {
    const reader = new FileReader();
    reader.onload = (e) => {
      const bytes = new Uint8Array(e.target.result);
      let hex = '';
      for (let i = 0; i < bytes.length; i++) {
        hex += bytes[i].toString(16).padStart(2, '0').toUpperCase() + ' ';
      }
      if (elements.casualRequestPayload) {
        elements.casualRequestPayload.value = hex.trim();
        updateCasualRoute(true);
      }
    };
    reader.readAsArrayBuffer(file);
  } else if (encoding === 'base64') {
    const reader = new FileReader();
    reader.onload = (e) => {
      const bytes = new Uint8Array(e.target.result);
      let binary = '';
      for (let i = 0; i < bytes.length; i++) {
        binary += String.fromCharCode(bytes[i]);
      }
      if (elements.casualRequestPayload) {
        elements.casualRequestPayload.value = btoa(binary);
        updateCasualRoute(true);
      }
    };
    reader.readAsArrayBuffer(file);
  } else {
    const reader = new FileReader();
    reader.onload = (e) => {
      if (elements.casualRequestPayload) {
        elements.casualRequestPayload.value = e.target.result;
        updateCasualRoute(true);
      }
    };
    reader.readAsText(file);
  }
}

export function downloadCasualBinaryResponse() {
  if (!lastCasualResponseData || !lastCasualResponseData.payload) return;
  try {
    const binaryString = atob(lastCasualResponseData.payload);
    const bytes = new Uint8Array(binaryString.length);
    for (let i = 0; i < binaryString.length; i++) {
      bytes[i] = binaryString.charCodeAt(i);
    }
    const blob = new Blob([bytes], { type: 'application/octet-stream' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${lastCasualResponseData.service || 'casual-response'}.bin`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  } catch (_) {
    const blob = new Blob([lastCasualResponseData.payload], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${lastCasualResponseData.service || 'casual-response'}.txt`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }
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
  const encoding = elements.casualPayloadEncoding ? elements.casualPayloadEncoding.value : 'text';

  const headerLines = elements.casualRequestHeaders ? elements.casualRequestHeaders.value.trim().split('\n') : [];
  const customHeaders = {};
  headerLines.forEach(line => {
    const colon = line.indexOf(':');
    if (colon > 0) {
      const k = line.substring(0, colon).trim();
      const v = line.substring(colon + 1).trim();
      if (k) customHeaders[k] = v;
    }
  });

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
    payloadEncoding: encoding,
    headers: customHeaders,
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
      lastCasualResponseData = data;
      displayCasualResponseResult(
        data.errorState || 'OK',
        data.errorCode !== undefined ? data.errorCode : 0,
        data.userCode || 0,
        data.durationMs || elapsed,
        data.execution || '',
        data.payload || '',
        data.isBinary || false,
        data.sizeBytes,
        data.payloadHex || '',
        data.headers || null,
        data.requestHeaders || null,
        payload,
        service
      );

      saveCasualHistoryItem({
        timestamp: Date.now(),
        serviceName: service,
        host: host,
        port: port,
        bufferType: bufferType,
        requestPayload: payload,
        requestHeaders: data.requestHeaders || customHeaders,
        headers: data.headers || null,
        errorState: data.errorState || 'OK',
        errorCode: data.errorCode !== undefined ? data.errorCode : 0,
        userCode: data.userCode || 0,
        durationMs: data.durationMs || elapsed,
        execution: data.execution || '',
        responsePayload: data.payload || '',
        isBinary: data.isBinary || false,
        sizeBytes: data.sizeBytes,
        payloadHex: data.payloadHex || ''
      });
      updateCasualRoute(true);
    })
    .catch(err => {
      const elapsed = Math.round(performance.now() - startTime);
      lastCasualResponseData = null;
      displayCasualResponseResult('ERROR', -1, -1, elapsed, '', 'Error connecting to Admin API: ' + err.message, false, undefined, '', null, customHeaders, payload, service);

      saveCasualHistoryItem({
        timestamp: Date.now(),
        serviceName: service,
        host: host,
        port: port,
        bufferType: bufferType,
        requestPayload: payload,
        requestHeaders: customHeaders,
        headers: null,
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
