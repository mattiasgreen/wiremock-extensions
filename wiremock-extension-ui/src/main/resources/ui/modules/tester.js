/**
 * Unified Multi-Protocol Tester controller (HTTP, Kafka, AMQP 1.0, Casual XATMI)
 * and execution history manager.
 */
import { state, uiActions, registerUiActions } from './state.js';
import { elements, escapeHtml, formatBytes } from './dom.js';
import { normalizeJsonPayload } from './base64.js';
import { detectFormat } from './highlighter.js';
import { mountPolyglotViewer, formatPayloadDescriptor, formatCasualBufferDescriptor, detectCharset } from './polyglot-viewer.js';
import { openDocumentWorkspace } from './document-workspace.js';
import { showToast, confirmDialog } from './dialog.js';
import { setRoute, toBase64, fromBase64, activateTab } from './router.js';
import { generateCurl, getStubUrl } from './stub-catalog.js';
import { dispatchRequest } from './dispatcher.js';
import { doFetch } from './api.js';
import { syncCasualCapabilityBanner, applyControlState } from './capabilities.js';
import {
  resolveMacro, loadLocalHeaders, saveLocalHeaders, isLocalHeadersEnabled, setLocalHeadersEnabled,
  getEffectiveTesterHeaders, LOCAL_HEADERS_KEY, LOCAL_HEADERS_ENABLED_KEY
} from './settings.js';

export {
  resolveMacro, loadLocalHeaders, saveLocalHeaders, isLocalHeadersEnabled, setLocalHeadersEnabled,
  getEffectiveTesterHeaders, LOCAL_HEADERS_KEY, LOCAL_HEADERS_ENABLED_KEY
};

const TESTER_HISTORY_KEY = 'wiremock_ui_tester_history';
const TESTER_COLLAPSED_KEY = 'wiremock_ui_tester_history_collapsed';
const getEl = (elemKey, id) => elements[elemKey] || (id ? document.getElementById(id) : null);
const valOf = (k, id) => (getEl(k, id)?.value || '').trim();
const setVal = (k, id, v) => { const el = getEl(k, id); if (el && v !== undefined && v !== null) el.value = String(v); };

const PROTOCOL_ADAPTERS = {
  http: {
    barId: 'tester-bar-http', protoBtnId: 'btn-tester-proto-http',
    bodyLabel: 'Request Body', headersLabel: 'Request Headers',
    headersPlaceholder: 'Content-Type: application/json\nAccept: application/json',
    sendLabel: '🚀 Send', sendingLabel: '⏳ Sending...', sendTitle: 'Send Request (Ctrl+Enter)',
    presets: [
      { id: 'btn-preset-json', label: '+ JSON', title: 'Add Content-Type: application/json', name: 'Content-Type', value: 'application/json' },
      { id: 'btn-preset-bearer', label: '+ Bearer', title: 'Add Authorization: Bearer token', name: 'Authorization', value: 'Bearer <token>' },
      { id: 'btn-preset-accept', label: '+ Accept', title: 'Add Accept: application/json', name: 'Accept', value: 'application/json' }
    ],
    defaults: { method: 'GET', url: '/api/v1/users', headers: '', body: '' },
    read: () => ({ method: getEl('testerMethod', 'tester-method')?.value || 'GET', destination: valOf('testerUrl', 'tester-url') }),
    write: (d) => { setVal('testerMethod', 'tester-method', d.method || 'GET'); setVal('testerUrl', 'tester-url', d.url || d.destination || '/api/v1/users'); },
    toShare: (p) => { const f = PROTOCOL_ADAPTERS.http.read(); if (f.method !== 'GET') p.set('m', f.method); if (f.destination && f.destination !== '/api/v1/users') p.set('p', f.destination); },
    fromShare: (p) => { if (p.get('m')) setVal('testerMethod', 'tester-method', p.get('m')); if (p.get('p')) setVal('testerUrl', 'tester-url', p.get('p')); }
  },
  kafka: {
    barId: 'tester-bar-kafka', protoBtnId: 'btn-tester-proto-kafka',
    bodyLabel: 'Event Payload', headersLabel: 'Record Headers (Key: Value)',
    headersPlaceholder: 'x-tenant-id: acme-corp\nx-correlation-id: corr-kafka-01',
    sendLabel: '🚀 Produce Message', sendingLabel: '⏳ Producing...', sendTitle: 'Produce Kafka Message (Ctrl+Enter)',
    presets: [
      { id: 'btn-preset-kafka-corr', label: '+ Correlation ID', title: 'Add x-correlation-id header', name: 'x-correlation-id', value: () => `corr-${Date.now()}` },
      { id: 'btn-preset-kafka-tenant', label: '+ Tenant', title: 'Add x-tenant-id header', name: 'x-tenant-id', value: 'acme-corp' }
    ],
    defaults: { topic: 'orders.events', key: '', partition: '', headers: '', body: '' },
    read: () => {
      const partRaw = valOf('testerKafkaPartition', 'tester-kafka-partition');
      return { method: 'PRODUCE', destination: valOf('testerKafkaTopic', 'tester-kafka-topic') || 'orders.events', key: valOf('testerKafkaKey', 'tester-kafka-key'), partition: partRaw ? parseInt(partRaw, 10) : undefined };
    },
    write: (d) => { setVal('testerKafkaTopic', 'tester-kafka-topic', d.topic || d.url || d.destination || 'orders.events'); setVal('testerKafkaKey', 'tester-kafka-key', d.key ?? ''); setVal('testerKafkaPartition', 'tester-kafka-partition', d.partition ?? ''); },
    toShare: (p) => { const f = PROTOCOL_ADAPTERS.kafka.read(); if (f.destination) p.set('topic', f.destination); if (f.key) p.set('key', f.key); if (f.partition !== undefined) p.set('partition', String(f.partition)); },
    fromShare: (p) => { const t = p.get('topic') || p.get('p'); if (t) setVal('testerKafkaTopic', 'tester-kafka-topic', t); if (p.get('key') !== null) setVal('testerKafkaKey', 'tester-kafka-key', p.get('key')); if (p.get('partition') !== null) setVal('testerKafkaPartition', 'tester-kafka-partition', p.get('partition')); }
  },
  amqp: {
    barId: 'tester-bar-amqp', protoBtnId: 'btn-tester-proto-amqp',
    bodyLabel: 'Message Payload', headersLabel: 'Application Properties',
    headersPlaceholder: 'tenant: acme-retail\nenv: staging',
    sendLabel: '🚀 Publish Message', sendingLabel: '⏳ Publishing...', sendTitle: 'Publish AMQP Message (Ctrl+Enter)',
    presets: [
      { id: 'btn-preset-amqp-tenant', label: '+ Tenant', title: 'Add tenant property', name: 'tenant', value: 'acme-retail' },
      { id: 'btn-preset-amqp-reply', label: '+ Reply Type', title: 'Add reply property', name: 'message-type', value: 'OrderValidationRequest' }
    ],
    defaults: { destination: 'orders.inbound', correlationId: '', replyTo: '', headers: '', body: '' },
    read: () => ({ method: 'PUBLISH', destination: valOf('testerAmqpDest', 'tester-amqp-dest') || 'orders.inbound', correlationId: valOf('testerAmqpCorrelation', 'tester-amqp-correlation'), replyTo: valOf('testerAmqpReplyTo', 'tester-amqp-reply-to') }),
    write: (d) => { setVal('testerAmqpDest', 'tester-amqp-dest', d.destination || d.url || 'orders.inbound'); setVal('testerAmqpCorrelation', 'tester-amqp-correlation', d.correlationId ?? ''); setVal('testerAmqpReplyTo', 'tester-amqp-reply-to', d.replyTo ?? ''); },
    toShare: (p) => { const f = PROTOCOL_ADAPTERS.amqp.read(); if (f.destination) p.set('dest', f.destination); if (f.correlationId) p.set('corr', f.correlationId); if (f.replyTo) p.set('reply', f.replyTo); },
    fromShare: (p) => { const d = p.get('dest') || p.get('p'); if (d) setVal('testerAmqpDest', 'tester-amqp-dest', d); if (p.get('corr') !== null) setVal('testerAmqpCorrelation', 'tester-amqp-correlation', p.get('corr')); if (p.get('reply') !== null) setVal('testerAmqpReplyTo', 'tester-amqp-reply-to', p.get('reply')); }
  },
  casual: {
    barId: 'tester-bar-casual', protoBtnId: 'btn-tester-proto-casual',
    bodyLabel: 'Buffer Payload', headersLabel: 'Call Flags & Headers',
    headersPlaceholder: 'Content-Type: application/json\nX-Casual-Service: echoService\nX-Casual-Buffer-Type: JSON',
    sendLabel: '🔌 Execute tpcall', sendingLabel: '⏳ Calling...', sendTitle: 'Execute Casual Service Call (Ctrl+Enter)',
    presets: [{ id: 'btn-tester-casual-preset-tx', label: '+ TX State', title: 'Add X-Casual-Transaction-State header', name: 'X-Casual-Transaction-State', value: 'TX_ACTIVE' }],
    defaults: { host: '127.0.0.1', port: 7772, service: 'echoService', bufferType: 'JSON', payloadEncoding: 'text', headers: '', body: '{\n  "message": "Hello Casual"\n}' },
    read: () => ({
      method: 'CALL', destination: valOf('testerCasualService', 'tester-casual-service') || 'echoService',
      host: valOf('testerCasualHost', 'tester-casual-host') || '127.0.0.1', port: parseInt(valOf('testerCasualPort', 'tester-casual-port'), 10) || 7772,
      bufferType: getEl('testerCasualBuffer', 'tester-casual-buffer')?.value || 'JSON', payloadEncoding: getEl('testerCasualEncoding', 'tester-casual-encoding')?.value || 'text'
    }),
    write: (d) => {
      if (d.host !== undefined) setVal('testerCasualHost', 'tester-casual-host', d.host);
      if (d.port !== undefined) setVal('testerCasualPort', 'tester-casual-port', d.port);
      setVal('testerCasualService', 'tester-casual-service', d.service || d.serviceName || d.url || 'echoService');
      const buf = d.bufferType || 'JSON';
      setVal('testerCasualBuffer', 'tester-casual-buffer', buf);
      setVal('testerCasualEncoding', 'tester-casual-encoding', d.payloadEncoding || (buf === 'X_OCTET' ? 'hex' : 'text'));
    },
    toShare: (p) => {
      const f = PROTOCOL_ADAPTERS.casual.read();
      if (f.destination) p.set('s', f.destination);
      if (f.bufferType !== 'JSON') p.set('b', f.bufferType);
      if (f.host && f.host !== '127.0.0.1') p.set('host', f.host);
      if (f.port && f.port !== 7772) p.set('port', String(f.port));
      if (f.payloadEncoding && f.payloadEncoding !== 'text') p.set('enc', f.payloadEncoding);
    },
    fromShare: (p) => {
      if (p.get('host')) setVal('testerCasualHost', 'tester-casual-host', p.get('host'));
      if (p.get('port')) setVal('testerCasualPort', 'tester-casual-port', p.get('port'));
      if (p.get('s')) setVal('testerCasualService', 'tester-casual-service', p.get('s'));
      const buf = p.get('buf') || p.get('b');
      if (buf && ['JSON', 'CSTRING', 'FIELDED', 'X_OCTET'].includes(buf.toUpperCase())) setVal('testerCasualBuffer', 'tester-casual-buffer', buf.toUpperCase());
      if (p.get('enc')) setVal('testerCasualEncoding', 'tester-casual-encoding', p.get('enc'));
    }
  }
};

export const QUICK_TEMPLATES = {
  'http-get-users': { protocol: 'http', method: 'GET', url: '/api/v1/users', headers: 'Accept: application/json', body: '' },
  'http-post-order': { protocol: 'http', method: 'POST', url: '/api/v1/orders', headers: 'Content-Type: application/json\nAccept: application/json', body: JSON.stringify({ customerId: 'CUST-1001', items: [{ sku: 'ITEM-88', qty: 2 }], total: 59.90 }, null, 2) },
  'kafka-order-event': { protocol: 'kafka', topic: 'orders.events', key: 'ORD-1001', partition: '', headers: 'x-tenant-id: acme-corp\nx-correlation-id: corr-kafka-01', body: JSON.stringify({ eventId: 'EVT-551', eventType: 'ORDER_CREATED', orderId: 'ORD-1001', timestamp: new Date().toISOString() }, null, 2) },
  'kafka-payments-stream': { protocol: 'kafka', topic: 'payments.stream', key: 'PAY-404', partition: '0', headers: 'x-tenant-id: finance-eu\nx-priority: high', body: JSON.stringify({ paymentId: 'PAY-404', amount: 249.00, currency: 'EUR', status: 'INITIATED' }, null, 2) },
  'amqp-orders-inbound': { protocol: 'amqp', destination: 'orders.inbound', correlationId: 'corr-amqp-01', replyTo: '', headers: 'tenant: acme-retail\nenv: staging', body: JSON.stringify({ orderId: 'ORD-9002', customer: 'alice@example.com', amount: 142.50 }, null, 2) },
  'amqp-req-reply': { protocol: 'amqp', destination: 'orders.requests', correlationId: 'corr-req-rep-77', replyTo: 'orders.replies', headers: 'tenant: enterprise-core\nmessage-type: OrderValidationRequest', body: JSON.stringify({ orderId: 'ORD-9003', validateCredit: true, requestedLimit: 5000 }, null, 2) },
  'casual-echo': { protocol: 'casual', service: 'echoService', bufferType: 'JSON', headers: 'Content-Type: application/json\nX-Casual-Service: echoService\nX-Casual-Buffer-Type: JSON', body: JSON.stringify({ message: 'Hello Casual from Unified Tester', ping: true }, null, 2) },
  'casual-charge': { protocol: 'casual', service: 'billing/charge', bufferType: 'JSON', headers: 'Content-Type: application/json\nX-Casual-Service: billing/charge\nX-Casual-Buffer-Type: JSON', body: JSON.stringify({ accountId: 'ACC-8821', chargeAmount: 95.00, currency: 'USD' }, null, 2) },
  'casual-enqueue': { protocol: 'casual', service: 'order.queue', bufferType: 'JSON', headers: 'Content-Type: application/json\nX-Casual-Operation: enqueue\nX-Casual-Queue: order.queue\nX-Casual-Buffer-Type: JSON', body: JSON.stringify({ queueItemId: 'Q-991', payloadData: 'Process expedited batch' }, null, 2) }
};

const headerDrafts = { http: '', kafka: '', amqp: '', casual: '' };
let currentRequestBody = '';
let currentRequestViewMode = 'rich';
let requestViewer = null;
let responseViewer = null;
let sentBodyViewer = null;
let lastHexResponseBody = '';
let lastCasualResponseData = null;
let boundTabEl = null;

const getActiveHeadersText = () => getEl('testerHeaders', 'tester-headers')?.value ?? (headerDrafts[state.testerProtocol || 'http'] || '');

function setActiveHeadersText(proto, text) {
  const val = text == null ? '' : String(text);
  headerDrafts[proto] = val;
  if ((state.testerProtocol || 'http') === proto) {
    const el = getEl('testerHeaders', 'tester-headers');
    if (el) el.value = val;
  }
}

function mountOrUpdateViewer(slot, containerEl, options) {
  if (!containerEl) return null;
  if (slot && slot.el === containerEl) {
    slot.ctrl.update(options);
    return slot;
  }
  if (slot) slot.ctrl.destroy();
  return { el: containerEl, ctrl: mountPolyglotViewer(containerEl, options) };
}

function destroyViewer(slot) {
  if (slot) slot.ctrl.destroy();
  return null;
}

const leafSectionPath = (chain) => (Array.isArray(chain) && chain.length > 0 ? chain[chain.length - 1]?.path || null : null);

function formatTopPillText(rawText, headers, casualBufferType = null) {
  const raw = rawText || '';
  const fmt = detectFormat(raw);
  const sizeKb = (new Blob([raw]).size / 1024).toFixed(1);
  const charset = detectCharset(raw, headers);
  return casualBufferType
    ? formatCasualBufferDescriptor(casualBufferType, fmt, sizeKb, charset)
    : formatPayloadDescriptor({ format: fmt, charset, sizeKb });
}

function syncModeToggle(toggleEl, activeMode) {
  if (!toggleEl) return;
  const norm = activeMode === 'raw' ? 'raw' : 'rich';
  toggleEl.querySelectorAll('[data-mode], .btn-mode-rich, .btn-mode-raw').forEach((btn) => {
    const mode = btn.getAttribute('data-mode') || (btn.classList.contains('btn-mode-raw') ? 'raw' : 'rich');
    btn.classList.toggle('active', mode === norm);
  });
}

function copyWithFeedback(text, btn, toastMsg, feedbackText = '✅ Copied!') {
  if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText) navigator.clipboard.writeText(text).catch(() => {});
  if (btn) {
    if (!btn.dataset.origLabel) btn.dataset.origLabel = btn.textContent;
    const orig = btn.dataset.origLabel;
    clearTimeout(btn._copyTimer);
    btn.textContent = feedbackText;
    btn._copyTimer = setTimeout(() => { btn.textContent = orig; }, 1500);
  }
  if (toastMsg) showToast(toastMsg, { type: 'success' });
}

function fetchCasualConfig() {
  return doFetch('/__admin/casual/config', { method: 'GET' })
    .then(res => (res.status >= 200 && res.status < 300 ? res.json() : null))
    .then(cfg => {
      if (!cfg) return;
      if (cfg.port) setVal('testerCasualPort', 'tester-casual-port', cfg.port);
      const statusEl = getEl('testerCasualGatewayStatus', 'tester-casual-gateway-status');
      if (statusEl) statusEl.textContent = `Casual Gateway: Port ${cfg.port} (${cfg.running ? 'Running' : 'Ready'})`;
    })
    .catch(() => {});
}

function syncDefaultCasualHeaders() {
  const current = (headerDrafts.casual || '').trim();
  const service = valOf('testerCasualService', 'tester-casual-service') || 'echoService';
  const buf = getEl('testerCasualBuffer', 'tester-casual-buffer')?.value || 'JSON';
  const ctype = buf === 'X_OCTET' ? 'application/octet-stream' : (buf.includes('STRING') ? 'text/plain; charset=UTF-8' : 'application/json');
  if (!current || /^Content-Type:[^\n]*\nX-Casual-Service:[^\n]*\nX-Casual-Buffer-Type:[^\n]*$/.test(current)) {
    setActiveHeadersText('casual', `Content-Type: ${ctype}\nX-Casual-Service: ${service}\nX-Casual-Buffer-Type: ${buf}`);
  }
}

function syncProtocolSendCapability(proto) {
  const sendBtn = getEl('btnTesterSend', 'btn-tester-send');
  const adapter = PROTOCOL_ADAPTERS[proto] || PROTOCOL_ADAPTERS.http;
  if (sendBtn) {
    sendBtn.textContent = adapter.sendLabel;
    if (proto === 'casual') {
      const ok = !(state.capabilities && state.capabilities.casual === false);
      applyControlState(sendBtn, ok, 'Casual Tester requires wiremock-extension-casual (/__admin/casual/config)');
      if (ok) sendBtn.title = adapter.sendTitle;
    } else {
      sendBtn.removeAttribute('disabled');
      sendBtn.classList.remove('cap-disabled');
      sendBtn.title = adapter.sendTitle;
    }
  }
  syncCasualCapabilityBanner();
}

function renderTesterRequestViewer() {
  const viewerEl = getEl('testerRequestViewer', 'tester-request-viewer');
  const bufType = (state.testerProtocol || 'http') === 'casual' ? (getEl('testerCasualBuffer', 'tester-casual-buffer')?.value || 'JSON') : undefined;
  requestViewer = mountOrUpdateViewer(requestViewer, viewerEl, {
    text: currentRequestBody,
    mode: currentRequestViewMode,
    editable: false,
    headers: getActiveHeadersText(),
    onOpenWorkspace: (chain, snap) => openDocumentWorkspace({
      title: 'Tester Request Payload',
      text: snap?.text ?? currentRequestBody,
      headers: snap?.headers ?? getActiveHeadersText(),
      bufferType: bufType,
      initialSectionPath: leafSectionPath(chain),
      editable: true,
      saveLabel: '✓ Apply to Request',
      onCommit: (next) => setTesterBody(next)
    })
  });
}

export function getTesterBody() { return currentRequestBody; }

export function setTesterBody(text, { render = true } = {}) {
  currentRequestBody = text == null ? '' : String(text);
  updateTesterRequestPill();
  if (render) renderTesterRequestViewer();
}

export function updateTesterRequestPill() {
  const topPill = getEl('testerRequestTopPill', 'tester-request-top-pill');
  if (!topPill) return;
  const bufType = (state.testerProtocol || 'http') === 'casual' ? (getEl('testerCasualBuffer', 'tester-casual-buffer')?.value || 'JSON') : null;
  topPill.textContent = formatTopPillText(currentRequestBody, getActiveHeadersText(), bufType);
  const topPillGroup = getEl('testerRequestTopPillGroup', 'tester-request-top-pill-group') || topPill.parentElement;
  if (topPillGroup?.style.display === 'none') topPillGroup.style.display = '';
}

export function populateCasualTester({ service, payload, bufferType = 'JSON', headers = null, switchTab = true } = {}) {
  switchTesterProtocol('casual', false);
  let normBuf = String(bufferType).toUpperCase();
  if (normBuf.includes('FIELD')) normBuf = 'FIELDED';
  else if (normBuf.includes('STRING')) normBuf = 'CSTRING';
  else if (normBuf.includes('OCTET')) normBuf = 'X_OCTET';
  PROTOCOL_ADAPTERS.casual.write({ service, bufferType: normBuf });

  if (headers) setActiveHeadersText('casual', Object.entries(headers).map(([k, v]) => `${k}: ${v}`).join('\n'));
  else { setActiveHeadersText('casual', ''); syncDefaultCasualHeaders(); }
  setTesterBody(payload || '{\n  \n}');
  fetchCasualConfig();
  if (switchTab) { activateTab('tab-tester', false); updateTesterRoute(false); }
}

function handleCasualFileUpload(event) {
  const file = event?.target?.files?.[0];
  if (!file) return;
  const encoding = getEl('testerCasualEncoding', 'tester-casual-encoding')?.value || 'text';
  const reader = new FileReader();
  reader.onload = (e) => {
    if (encoding === 'text') return setTesterBody(e.target.result);
    const bytes = new Uint8Array(e.target.result);
    if (encoding === 'hex') return setTesterBody(Array.from(bytes, b => b.toString(16).padStart(2, '0').toUpperCase()).join(' '));
    let binary = '';
    for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
    setTesterBody(btoa(binary));
  };
  if (encoding === 'text') reader.readAsText(file); else reader.readAsArrayBuffer(file);
  event.target.value = '';
}

function downloadCasualBinaryResponse() {
  if (!lastCasualResponseData?.payload) return;
  const svc = lastCasualResponseData.service || 'casual-response';
  let blob; let ext = 'bin';
  try {
    const bin = atob(lastCasualResponseData.payload);
    blob = new Blob([Uint8Array.from(bin, c => c.charCodeAt(0))], { type: 'application/octet-stream' });
  } catch {
    blob = new Blob([lastCasualResponseData.payload], { type: 'text/plain' });
    ext = 'txt';
  }
  const url = URL.createObjectURL(blob);
  const a = Object.assign(document.createElement('a'), { href: url, download: `${svc}.${ext}` });
  document.body.appendChild(a); a.click(); document.body.removeChild(a); URL.revokeObjectURL(url);
}

export function setTesterViewMode(mode) {
  state.testerViewMode = mode;
  getEl('btnViewHighlighted', 'btn-view-highlighted')?.classList.toggle('active', mode === 'highlighted');
  getEl('btnViewRaw', 'btn-view-raw')?.classList.toggle('active', mode === 'raw');
  getEl('btnViewHex', 'btn-view-hex')?.classList.toggle('active', mode === 'hex');
  const respToggle = getEl('testerResponseModeToggle', 'tester-response-mode-toggle') || document.querySelector('#btn-tester-response-mode-toggle');
  syncModeToggle(respToggle, mode === 'raw' ? 'raw' : 'rich');
  renderTesterResponse(state.lastRawResponseBody);
}

export function showSettlementBanner(text, cssClass = 'status-settled') {
  const banner = getEl('testerSettlementBanner', 'tester-settlement-banner');
  if (!banner) return;
  banner.className = `tester-settlement-banner ${cssClass}`;
  const iconEl = document.getElementById('tester-settlement-icon');
  const textEl = document.getElementById('tester-settlement-text') || banner;
  if (iconEl) {
    iconEl.textContent = cssClass.includes('error') ? '❌' : (text.startsWith('📨') ? '📨' : '✅');
    textEl.textContent = text.replace(/^[✅❌📨]\s*/, '');
  } else {
    textEl.textContent = text;
  }
  banner.classList.remove('hidden');
}

export function switchTesterProtocol(protocol, updateRoute = true) {
  const nextProto = PROTOCOL_ADAPTERS[protocol] ? protocol : 'http';
  const prevProto = state.testerProtocol || 'http';
  const headersEl = getEl('testerHeaders', 'tester-headers');

  if (headersEl && prevProto !== nextProto) headerDrafts[prevProto] = headersEl.value;
  state.testerProtocol = nextProto;
  if (headersEl && prevProto !== nextProto) headersEl.value = headerDrafts[nextProto] || '';

  const adapter = PROTOCOL_ADAPTERS[nextProto];
  for (const [p, cfg] of Object.entries(PROTOCOL_ADAPTERS)) {
    document.getElementById(cfg.protoBtnId)?.classList.toggle('active', p === nextProto);
    document.getElementById(cfg.barId)?.classList.toggle('hidden', p !== nextProto);
  }

  const presetsEl = document.getElementById('tester-header-presets');
  if (presetsEl) {
    presetsEl.innerHTML = adapter.presets.map((p, idx) =>
      `<span class="preset-badge" id="${escapeHtml(p.id)}" data-preset-idx="${idx}" title="${escapeHtml(p.title)}">${escapeHtml(p.label)}</span>`
    ).join('');
  }
  getEl('testerLocalHeadersContainer', 'tester-local-headers-container')?.classList.toggle('hidden', nextProto !== 'http');

  if (headersEl) headersEl.placeholder = adapter.headersPlaceholder;
  const headersLabel = getEl('testerHeadersLabel', 'tester-headers-label')
    || headersEl?.closest('.tester-section')?.querySelector('.tester-section-header > span');
  if (headersLabel) headersLabel.textContent = adapter.headersLabel;

  getEl('testerCasualPayloadControls', 'tester-casual-payload-controls')?.classList.toggle('hidden', nextProto !== 'casual');
  getEl('testerCasualGatewayStatus', 'tester-casual-gateway-status')?.classList.toggle('hidden', nextProto !== 'casual');
  getEl('btnViewHex', 'btn-view-hex')?.classList.toggle('hidden', nextProto !== 'casual');
  getEl('btnTesterCopyCurl', 'btn-tester-copy-curl')?.classList.toggle('hidden', nextProto === 'casual');

  if (nextProto === 'http') renderLocalHeaderChips();
  if (nextProto === 'casual') {
    fetchCasualConfig();
    syncDefaultCasualHeaders();
  } else {
    getEl('btnTesterDownloadBinary', 'btn-tester-download-binary')?.classList.add('hidden');
    getEl('testerResponseCode', 'tester-response-code')?.classList.add('hidden');
    getEl('testerResponseExecution', 'tester-response-execution')?.classList.add('hidden');
    if (state.testerViewMode === 'hex') setTesterViewMode('highlighted');
  }

  const bodyLabel = document.getElementById('tester-body-label');
  if (bodyLabel) bodyLabel.textContent = adapter.bodyLabel;
  getEl('testerSettlementBanner', 'tester-settlement-banner')?.classList.add('hidden');
  syncProtocolSendCapability(nextProto);
  updateTesterRequestPill();
  renderTesterRequestViewer();
  if (updateRoute) updateTesterRoute(true);
}

export function applyQuickTemplate(templateId) {
  const tpl = QUICK_TEMPLATES[templateId];
  if (!tpl) return;
  switchTesterProtocol(tpl.protocol, false);
  PROTOCOL_ADAPTERS[tpl.protocol].write(tpl);
  setActiveHeadersText(tpl.protocol, tpl.headers || '');
  if (tpl.protocol === 'http') renderLocalHeaderChips();
  setTesterBody(tpl.body || '');
  updateTesterRoute(true);
}

export function appendHeader(name, value) {
  const headersEl = getEl('testerHeaders', 'tester-headers');
  if (!headersEl) return;
  const current = headersEl.value.trim();
  if (current.toLowerCase().includes(`${name.toLowerCase()}:`)) return;
  setActiveHeadersText(state.testerProtocol || 'http', (current ? `${current}\n` : '') + `${name}: ${value}`);
  if ((state.testerProtocol || 'http') === 'http') renderLocalHeaderChips();
  updateTesterRequestPill();
}

export function parseHeadersInput(text) {
  const headers = {};
  if (!text) return headers;
  for (const line of text.split('\n')) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const colonIdx = trimmed.indexOf(':');
    if (colonIdx > 0) {
      const key = trimmed.substring(0, colonIdx).trim();
      if (key) headers[key] = trimmed.substring(colonIdx + 1).trim();
    }
  }
  return headers;
}

export function updateTesterRoute(replace = true) {
  const proto = state.testerProtocol || 'http';
  setRoute('tester', {
    proto: proto !== 'http' ? proto : null,
    m: null, p: null, h: null, b: null, s: null, buf: null, topic: null, key: null, partition: null, dest: null, corr: null, reply: null, host: null, port: null, enc: null
  }, replace);
}

export function buildTesterShareLink() {
  const proto = (state.testerProtocol || 'http').toLowerCase();
  const params = new URLSearchParams();
  if (proto !== 'http') params.set('proto', proto);
  (PROTOCOL_ADAPTERS[proto] || PROTOCOL_ADAPTERS.http).toShare(params);
  const headersText = getActiveHeadersText().trim();
  const bodyText = currentRequestBody.trim();
  if (headersText) params.set('h', toBase64(headersText));
  if (bodyText) params.set(proto === 'casual' ? 'p' : 'b', toBase64(bodyText));
  const base = (typeof window !== 'undefined' && window.location) ? window.location.href.split('#')[0] : '';
  const q = params.toString();
  return `${base}#tester${q ? `?${q}` : ''}`;
}

export function applyTesterRouteParams(params, forcedProto = null) {
  if (!params) return;
  const rawProto = forcedProto || params.get('proto');
  const proto = rawProto && PROTOCOL_ADAPTERS[rawProto.toLowerCase()] ? rawProto.toLowerCase() : (state.testerProtocol || 'http');
  if (rawProto) switchTesterProtocol(proto, false);

  PROTOCOL_ADAPTERS[proto].fromShare(params);
  const h = params.get('h');
  if (h) {
    setActiveHeadersText(proto, fromBase64(h));
    if (proto === 'http') renderLocalHeaderChips();
  } else if (proto === 'casual' && (params.get('s') || params.get('buf') || params.get('b'))) {
    syncDefaultCasualHeaders();
  }

  const b = params.get('b');
  const p = params.get('p');
  if (proto === 'casual') {
    const knownBuffers = new Set(['JSON', 'CSTRING', 'FIELDED', 'X_OCTET']);
    if (p) setTesterBody(fromBase64(p));
    else if (b && !knownBuffers.has(b.toUpperCase())) setTesterBody(fromBase64(b));
    else updateTesterRequestPill();
  } else if (b) {
    setTesterBody(fromBase64(b));
  } else {
    updateTesterRequestPill();
  }
}

export function initTesterHistoryCollapse() {
  try { if (localStorage.getItem(TESTER_COLLAPSED_KEY) === 'true') setTesterHistoryCollapsed(true); } catch { /* ignore */ }
}

export function setTesterHistoryCollapsed(collapsed) {
  state.testerHistoryCollapsed = Boolean(collapsed);
  elements.testerHistoryPanel?.classList.toggle('collapsed', state.testerHistoryCollapsed);
  try { localStorage.setItem(TESTER_COLLAPSED_KEY, state.testerHistoryCollapsed ? 'true' : 'false'); } catch { /* ignore */ }
}

export function toggleTesterHistory() { setTesterHistoryCollapsed(!state.testerHistoryCollapsed); }

export function loadTesterHistoryFromStorage() {
  try { const saved = localStorage.getItem(TESTER_HISTORY_KEY); if (saved) state.testerHistory = JSON.parse(saved); } catch { state.testerHistory = []; }
  renderTesterHistory();
}

export function saveTesterHistoryItem(item) {
  state.testerHistory.unshift(item);
  if (state.testerHistory.length > 50) state.testerHistory.pop();
  try { localStorage.setItem(TESTER_HISTORY_KEY, JSON.stringify(state.testerHistory)); } catch { /* ignore */ }
  renderTesterHistory();
}

export function clearTesterHistory() {
  state.testerHistory = [];
  try { localStorage.removeItem(TESTER_HISTORY_KEY); } catch { /* ignore */ }
  renderTesterHistory();
}

export function renderTesterHistory() {
  if (!elements.testerHistoryList) return;
  const totalCount = state.testerHistory.length;
  if (elements.testerHistoryCount) elements.testerHistoryCount.textContent = totalCount;
  if (elements.testerHistoryCollapsedCount) elements.testerHistoryCollapsedCount.textContent = totalCount;

  const filter = (state.testerHistoryProtocolFilter || 'all').toLowerCase();
  const items = filter === 'all' ? state.testerHistory : state.testerHistory.filter(i => (i.protocol || 'http').toLowerCase() === filter);
  if (items.length === 0) {
    elements.testerHistoryList.innerHTML = `<div class="empty-state text-small">${totalCount === 0 ? 'No requests sent yet.' : `No ${filter.toUpperCase()} requests recorded.`}</div>`;
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
      <div class="history-item-meta"><span>${item.duration || 0} ms</span><span>${formatBytes(item.size || 0)}</span></div>
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
  const adapter = PROTOCOL_ADAPTERS[proto] || PROTOCOL_ADAPTERS.http;
  adapter.write(proto === 'casual' ? { host: '127.0.0.1', port: 7772, ...item } : item);

  lastCasualResponseData = proto === 'casual' ? {
    service: item.serviceName || item.url || 'echoService', bufferType: item.bufferType || 'JSON', payload: item.responseBody || '',
    isBinary: Boolean(item.isBinary), sizeBytes: item.size, payloadHex: item.payloadHex || '', errorState: item.errorState || 'OK',
    errorCode: item.errorCode ?? 0, userCode: item.userCode ?? 0, execution: item.execution || ''
  } : null;

  setActiveHeadersText(proto, item.headersText || '');
  if (proto === 'http') renderLocalHeaderChips();
  setTesterBody(item.bodyText || '');

  const methodOrOp = item.method || (proto === 'casual' ? 'CALL' : (proto === 'kafka' ? 'PRODUCE' : (proto === 'amqp' ? 'PUBLISH' : 'GET')));
  displaySentRequest(methodOrOp, item.url || item.destination || item.serviceName || '', item.headersText || '', item.bodyText || '');
  displayResponseResult(item.status, item.statusText || '', item.duration || 0, item.responseHeaders || [], item.responseBody || '', lastCasualResponseData);
  updateTesterRoute(true);
}

export function setInspectorTab(tab) {
  state.activeInspectorTab = tab;
  [elements.btnTesterTabResponse, elements.btnTesterTabRequest, elements.btnTesterTabBoth].forEach(b => b?.classList.remove('active'));
  const container = document.getElementById('tester-inspector-content');
  if (!container) return;

  const showResp = tab === 'response' || tab === 'both';
  const showReq = tab === 'request' || tab === 'both';
  if (tab === 'response') elements.btnTesterTabResponse?.classList.add('active');
  if (tab === 'request') elements.btnTesterTabRequest?.classList.add('active');
  if (tab === 'both') elements.btnTesterTabBoth?.classList.add('active');

  elements.testerViewResponse?.classList.toggle('hidden', !showResp);
  elements.testerViewResponse?.classList.toggle('active', showResp);
  elements.testerViewRequest?.classList.toggle('hidden', !showReq);
  elements.testerViewRequest?.classList.toggle('active', showReq);
  container.classList.toggle('both-mode', tab === 'both');
}

export function displaySentRequest(method, url, headersText, bodyText) {
  if (elements.testerSentMethod) {
    elements.testerSentMethod.textContent = method;
    elements.testerSentMethod.className = `http-badge badge-${method.toLowerCase()}`;
  }
  if (elements.testerSentUrl) elements.testerSentUrl.textContent = url;
  if (elements.testerSentHeaders) elements.testerSentHeaders.textContent = (headersText || '').trim() || '(no headers sent)';
  const sentBodyEl = elements.testerSentBody;
  if (!sentBodyEl) return;
  if (!(bodyText || '').trim()) {
    sentBodyViewer = destroyViewer(sentBodyViewer);
    sentBodyEl.textContent = '(empty body)';
    return;
  }
  sentBodyViewer = mountOrUpdateViewer(sentBodyViewer, sentBodyEl, {
    text: bodyText, mode: 'rich', editable: false, headers: headersText,
    onOpenWorkspace: (chain, snap) => openDocumentWorkspace({
      title: 'Sent Request Payload',
      text: snap?.text ?? bodyText,
      headers: snap?.headers ?? headersText,
      initialSectionPath: leafSectionPath(chain),
      editable: false
    })
  });
}

export function displayResponseResult(status, statusText, elapsed, headerLines, responseBody, extra = null) {
  const isCasual = Boolean(extra && extra.errorState !== undefined);
  lastHexResponseBody = (extra && extra.payloadHex) || '';
  lastCasualResponseData = isCasual ? { ...extra, payload: responseBody } : null;

  if (elements.testerResponseStatus) {
    if (isCasual) {
      const errorState = extra.errorState || 'OK';
      elements.testerResponseStatus.textContent = `${errorState} (${extra.errorCode ?? 0})`;
      elements.testerResponseStatus.className = `card-value ${errorState === 'OK' ? 'status-200' : 'status-500'}`;
    } else if (status === 'ERROR') {
      elements.testerResponseStatus.textContent = 'ERROR';
      elements.testerResponseStatus.className = 'card-value status-500';
    } else {
      elements.testerResponseStatus.textContent = `${status} ${statusText || ''}`.trim();
      elements.testerResponseStatus.className = `card-value status-${status >= 500 ? '500' : (status >= 400 ? '400' : '200')}`;
    }
  }
  if (elements.testerResponseTime) elements.testerResponseTime.textContent = `${elapsed} ms`;
  if (elements.testerResponseSize) {
    elements.testerResponseSize.textContent = formatBytes(extra?.sizeBytes !== undefined ? extra.sizeBytes : new Blob([responseBody || '']).size);
  }
  if (elements.testerResponseCode) {
    if (isCasual && extra.userCode !== undefined) {
      elements.testerResponseCode.textContent = `UserCode: ${extra.userCode}${(extra.isBinary && extra.sizeBytes !== undefined) ? ` | ${extra.sizeBytes} B` : ''}`;
      elements.testerResponseCode.classList.remove('hidden');
    } else {
      elements.testerResponseCode.textContent = '';
      elements.testerResponseCode.classList.add('hidden');
    }
  }
  if (elements.testerResponseExecution) {
    elements.testerResponseExecution.textContent = (isCasual && extra.execution) ? `UUID: ${extra.execution}` : '';
    elements.testerResponseExecution.classList.toggle('hidden', !(isCasual && extra.execution));
  }
  getEl('btnTesterDownloadBinary', 'btn-tester-download-binary')?.classList.toggle('hidden', !(isCasual && extra.isBinary));

  if (elements.testerResponseHeaderCount) elements.testerResponseHeaderCount.textContent = headerLines.length;
  if (elements.testerResponseHeaders) elements.testerResponseHeaders.textContent = headerLines.join('\n') || '(no headers)';
  state.lastResponseHeaders = headerLines;

  if (isCasual && extra.isBinary && state.testerViewMode === 'highlighted') {
    state.lastRawResponseBody = responseBody;
    setTesterViewMode('hex');
  } else {
    renderTesterResponse(responseBody);
  }
}

export function renderTesterResponse(bodyText) {
  state.lastRawResponseBody = bodyText == null ? '' : String(bodyText);
  const respEl = getEl('testerResponseBody', 'tester-response-body');
  if (!respEl) return;

  const topPillGroup = getEl('testerTopPillGroup', 'tester-top-pill-group') || document.querySelector('#tester-view-response .preview-pill-group');
  const topPill = getEl('testerTopPill', 'tester-top-pill') || document.querySelector('#tester-view-response .preview-pill-badge');

  if (state.testerViewMode === 'hex' || (lastCasualResponseData?.isBinary && state.testerViewMode !== 'raw')) {
    responseViewer = destroyViewer(responseViewer);
    respEl.textContent = lastHexResponseBody || state.lastRawResponseBody || '';
    if (topPillGroup) topPillGroup.style.display = 'none';
    return;
  }
  if (lastCasualResponseData?.isBinary && state.testerViewMode === 'raw') {
    responseViewer = destroyViewer(responseViewer);
    respEl.textContent = state.lastRawResponseBody || '';
    if (topPillGroup) topPillGroup.style.display = 'none';
    return;
  }

  if (topPill) topPill.textContent = formatTopPillText(state.lastRawResponseBody, state.lastResponseHeaders, lastCasualResponseData?.bufferType || null);
  if (topPillGroup) topPillGroup.style.display = state.lastRawResponseBody.trim() ? '' : 'none';

  responseViewer = mountOrUpdateViewer(responseViewer, respEl, {
    text: state.lastRawResponseBody,
    mode: state.testerViewMode === 'raw' ? 'raw' : 'rich',
    editable: false,
    headers: state.lastResponseHeaders,
    onOpenWorkspace: (chain, snap) => openDocumentWorkspace({
      title: 'Tester Response Payload',
      text: snap?.text ?? state.lastRawResponseBody,
      headers: snap?.headers ?? state.lastResponseHeaders,
      bufferType: lastCasualResponseData?.bufferType || undefined,
      initialSectionPath: leafSectionPath(chain),
      editable: false
    })
  });
}

export function renderLocalHeaderChips() {
  if (!elements.testerLocalHeadersChips) return;
  const localHeaders = loadLocalHeaders();
  const isEnabled = isLocalHeadersEnabled();
  const { overriddenKeys } = getEffectiveTesterHeaders(getActiveHeadersText(), false);
  const activeHeaders = localHeaders.filter(h => h.enabled && h.name?.trim());

  if (elements.testerLocalHeadersCount) elements.testerLocalHeadersCount.textContent = activeHeaders.length;
  if (elements.testerLocalHeadersToggle) elements.testerLocalHeadersToggle.checked = isEnabled;
  elements.testerLocalHeadersContainer?.classList.toggle('disabled', !isEnabled);

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
    const name = h.name.trim();
    const isOverridden = overriddenKeys.has(name.toLowerCase());
    const chip = document.createElement('div');
    chip.className = isOverridden ? 'local-header-chip overridden' : 'local-header-chip';
    chip.setAttribute('data-header-name', name);
    chip.setAttribute('title', isOverridden ? 'Overridden by Request Headers textarea' : 'From browser local storage');
    chip.innerHTML = `<span class="chip-name">${escapeHtml(name)}:</span> <span class="chip-val">${escapeHtml(h.value || '')}</span>`
      + (isOverridden ? '<span class="chip-override-badge">(overridden)</span>' : '');
    elements.testerLocalHeadersChips.appendChild(chip);
  }
}

export async function executeTesterRequest() {
  const proto = (state.testerProtocol || 'http').toLowerCase();
  const adapter = PROTOCOL_ADAPTERS[proto] || PROTOCOL_ADAPTERS.http;
  const headersText = getActiveHeadersText();
  headerDrafts[proto] = headersText;

  if (proto === 'http' && (!getEl('testerUrl', 'tester-url') || !getEl('testerMethod', 'tester-method'))) return;
  const fields = adapter.read();
  let bodyText = currentRequestBody.trim();
  if (proto !== 'http' && !bodyText) bodyText = '{}';

  const { headers } = proto === 'http' ? getEffectiveTesterHeaders(headersText, true) : { headers: parseHeadersInput(headersText) };
  const sentHeadersText = Object.entries(headers).map(([k, v]) => `${k}: ${v}`).join('\n');
  displaySentRequest(fields.method, fields.destination, sentHeadersText, bodyText);

  const sendBtn = getEl('btnTesterSend', 'btn-tester-send');
  if (sendBtn) { sendBtn.disabled = true; sendBtn.textContent = adapter.sendingLabel; }
  if (elements.testerResponseStatus) { elements.testerResponseStatus.textContent = '...'; elements.testerResponseStatus.className = 'card-value'; }
  if (elements.testerResponseTime) elements.testerResponseTime.textContent = 'measuring...';
  if (elements.testerResponseSize) elements.testerResponseSize.textContent = '—';

  try {
    const dispatchOptions = { protocol: proto, ...fields, headers, body: bodyText };
    if (proto === 'casual') dispatchOptions.timeoutSeconds = 4;

    const result = await dispatchRequest(dispatchOptions);
    const settlement = result.settlement || {};

    if (proto === 'casual') {
      if (settlement.requestHeaders) {
        displaySentRequest(fields.method, fields.destination, Object.entries(settlement.requestHeaders).map(([k, v]) => `${k}: ${v}`).join('\n'), bodyText);
      }
      displayResponseResult(
        result.status === 'ERR' ? 'ERROR' : result.status,
        result.statusText, result.elapsedMs,
        result.status === 'ERR' ? [] : result.responseHeaders,
        result.responseBody,
        result.status === 'ERR'
          ? { service: fields.destination, errorState: 'ERROR', errorCode: -1, userCode: -1 }
          : { service: fields.destination, ...settlement }
      );
    } else {
      displayResponseResult(result.status, result.statusText, result.elapsedMs, result.responseHeaders, result.responseBody);
      if (result.banner) showSettlementBanner(result.banner.text, result.banner.type);
    }

    const historyItem = {
      timestamp: Date.now(), protocol: proto, method: fields.method, url: fields.destination, destination: fields.destination,
      headersText, bodyText, status: result.status, statusText: result.statusText, duration: result.elapsedMs,
      size: new Blob([result.responseBody]).size, responseHeaders: result.responseHeaders, responseBody: result.responseBody
    };
    if (proto === 'kafka') {
      historyItem.key = fields.key;
      if (settlement.partition !== undefined) historyItem.partition = settlement.partition;
    } else if (proto === 'amqp') {
      historyItem.correlationId = fields.correlationId;
      if (fields.replyTo) historyItem.replyTo = fields.replyTo;
    } else if (proto === 'casual') {
      Object.assign(historyItem, {
        serviceName: fields.destination, bufferType: fields.bufferType, host: fields.host, port: fields.port, payloadEncoding: fields.payloadEncoding,
        errorState: settlement.errorState || (result.status === 'ERR' ? 'ERROR' : 'OK'),
        errorCode: settlement.errorCode ?? (result.status === 'ERR' ? -1 : 0),
        userCode: settlement.userCode ?? 0, execution: settlement.execution || '',
        isBinary: Boolean(settlement.isBinary), payloadHex: settlement.payloadHex || ''
      });
      if (settlement.sizeBytes !== undefined) historyItem.size = settlement.sizeBytes;
      if (settlement.errorState) { historyItem.status = settlement.errorState === 'OK' ? 'OK' : settlement.errorState; historyItem.statusText = settlement.errorState; }
    }

    saveTesterHistoryItem(historyItem);
    if (uiActions.loadJournal) uiActions.loadJournal();
  } catch (err) {
    displayResponseResult('ERROR', err.message, 0, [], `Dispatch error: ${err.message}`);
    showSettlementBanner(`❌ Error: ${err.message}`, 'status-error');
    showToast(err.message, { type: 'error' });
  } finally {
    syncProtocolSendCapability(state.testerProtocol || 'http');
  }
}

export function sendStubToTester(stub, switchTab = true) {
  if (!stub) return;
  state.selectedStubId = stub.id;
  document.querySelectorAll('.stub-card').forEach(el => {
    const id = el.getAttribute('data-stub-id') || el.dataset?.stubId;
    el.classList.toggle('selected', id === stub.id);
  });

  const method = stub.request?.method || 'GET';
  const example = stub.metadata?.exampleRequest;
  const path = example?.path || getStubUrl(stub.request);
  const isCasual = path.startsWith('/__casual/services/') || stub.metadata?.protocol === 'casual';

  let body = '';
  if (example?.body) {
    body = typeof example.body === 'object' ? JSON.stringify(example.body, null, 2) : String(example.body);
  } else if (stub.request?.bodyPatterns?.length > 0) {
    const bp = stub.request.bodyPatterns[0];
    if (bp.equalToJson) {
      const norm = normalizeJsonPayload(bp.equalToJson);
      body = norm !== null ? JSON.stringify(norm, null, 2) : (typeof bp.equalToJson === 'string' ? bp.equalToJson : JSON.stringify(bp.equalToJson, null, 2));
    } else if (bp.equalTo) {
      body = bp.equalTo;
    } else if (!isCasual && bp.matchesJsonPath) {
      const sample = {};
      stub.request.bodyPatterns.forEach(p => {
        if (!p.matchesJsonPath) return;
        const expr = typeof p.matchesJsonPath === 'object' && p.matchesJsonPath !== null ? (p.matchesJsonPath.expression || '') : String(p.matchesJsonPath);
        const val = typeof p.matchesJsonPath === 'object' && p.matchesJsonPath !== null && p.matchesJsonPath.equalTo !== undefined ? p.matchesJsonPath.equalTo : (p.equalTo ?? 'sample_value');
        const m = expr.match(/\$\.([a-zA-Z0-9_.-]+)/);
        if (m?.[1]) {
          const parts = m[1].split('.');
          let cur = sample;
          for (let i = 0; i < parts.length - 1; i++) { if (!cur[parts[i]]) cur[parts[i]] = {}; cur = cur[parts[i]]; }
          cur[parts[parts.length - 1]] = val;
        }
      });
      body = Object.keys(sample).length > 0 ? JSON.stringify(sample, null, 2) : '{\n  \n}';
    } else if (!isCasual && bp.contains) {
      body = bp.contains;
    }
  } else if (!isCasual && ['POST', 'PUT', 'PATCH'].includes(method)) {
    body = '{\n  \n}';
  }

  if (isCasual) {
    const service = stub.metadata?.service || (path.startsWith('/__casual/services/') ? path.substring('/__casual/services/'.length) : 'echoService');
    populateCasualTester({ service, payload: body, bufferType: stub.metadata?.bufferType || 'JSON', headers: example?.headers || null, switchTab });
    return;
  }

  switchTesterProtocol('http', false);
  const headerLines = [];
  if (example?.headers && Object.keys(example.headers).length > 0) {
    for (const [k, v] of Object.entries(example.headers)) headerLines.push(`${k}: ${v}`);
  } else if (stub.request?.headers) {
    for (const [k, v] of Object.entries(stub.request.headers)) headerLines.push(`${k}: ${v.equalTo || v.matches || v.contains || Object.values(v)[0] || ''}`);
  }

  PROTOCOL_ADAPTERS.http.write({ method: method === 'ANY' ? 'GET' : method, url: path });
  setActiveHeadersText('http', headerLines.join('\n'));
  renderLocalHeaderChips();
  setTesterBody(body);

  state.contextStub = stub;
  if (elements.testerContextBanner) {
    elements.testerContextBanner.classList.remove('hidden');
    if (elements.testerContextTitle) elements.testerContextTitle.textContent = `Pre-filled from stub: ${stub.name ? `"${stub.name}" (${method} ${path})` : `${method} ${path}`}`;
  }
  if (switchTab) activateTab('tab-tester', false);
  updateTesterRoute(false);
}

export function initTesterTab() {
  const tabTesterEl = document.getElementById('tab-tester');
  if (boundTabEl === tabTesterEl && tabTesterEl) return;
  boundTabEl = tabTesterEl;

  initTesterHistoryCollapse();
  for (const [proto, cfg] of Object.entries(PROTOCOL_ADAPTERS)) {
    document.getElementById(cfg.protoBtnId)?.addEventListener('click', () => switchTesterProtocol(proto));
  }

  document.getElementById('tester-quick-templates')?.addEventListener('change', (e) => {
    if (e.target.value) { applyQuickTemplate(e.target.value); e.target.value = ''; }
  });

  const filterContainer = document.getElementById('tester-history-filters');
  filterContainer?.querySelectorAll('.filter-pill').forEach(pill => {
    pill.addEventListener('click', () => {
      filterContainer.querySelectorAll('.filter-pill').forEach(p => p.classList.remove('active'));
      pill.classList.add('active');
      state.testerHistoryProtocolFilter = pill.getAttribute('data-filter') || 'all';
      renderTesterHistory();
    });
  });

  getEl('btnTesterSend', 'btn-tester-send')?.addEventListener('click', executeTesterRequest);
  tabTesterEl?.addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); executeTesterRequest(); }
  });

  document.getElementById('tester-header-presets')?.addEventListener('click', (e) => {
    const chip = e.target.closest('.preset-badge');
    if (!chip) return;
    const preset = PROTOCOL_ADAPTERS[state.testerProtocol || 'http']?.presets[parseInt(chip.getAttribute('data-preset-idx') || '-1', 10)];
    if (preset) appendHeader(preset.name, typeof preset.value === 'function' ? preset.value() : preset.value);
  });

  const headersEl = getEl('testerHeaders', 'tester-headers');
  headersEl?.addEventListener('input', () => {
    const proto = state.testerProtocol || 'http';
    headerDrafts[proto] = headersEl.value;
    if (proto === 'http') renderLocalHeaderChips();
    updateTesterRequestPill();
  });
  headersEl?.addEventListener('change', () => renderTesterRequestViewer());

  getEl('testerCasualService', 'tester-casual-service')?.addEventListener('input', syncDefaultCasualHeaders);
  getEl('testerCasualBuffer', 'tester-casual-buffer')?.addEventListener('change', () => {
    syncDefaultCasualHeaders();
    const buf = getEl('testerCasualBuffer', 'tester-casual-buffer');
    const enc = getEl('testerCasualEncoding', 'tester-casual-encoding');
    if (buf && enc) {
      if ((buf.value === 'X_OCTET' || buf.value === 'FIELDED') && enc.value === 'text') enc.value = 'hex';
      else if (buf.value === 'JSON' && enc.value === 'hex') enc.value = 'text';
    }
    updateTesterRequestPill();
  });

  const fileInput = getEl('testerCasualFileInput', 'tester-casual-file-input');
  const uploadBtn = getEl('btnTesterUploadFile', 'btn-tester-upload-file');
  if (uploadBtn && fileInput) {
    uploadBtn.addEventListener('click', () => fileInput.click());
    fileInput.addEventListener('change', handleCasualFileUpload);
  }
  getEl('btnTesterDownloadBinary', 'btn-tester-download-binary')?.addEventListener('click', downloadCasualBinaryResponse);

  getEl('btnTesterEditWorkspace', 'btn-tester-edit-workspace')?.addEventListener('click', () => openDocumentWorkspace({
    title: 'Tester Request Payload',
    text: currentRequestBody,
    headers: getActiveHeadersText(),
    bufferType: (state.testerProtocol || 'http') === 'casual' ? (getEl('testerCasualBuffer', 'tester-casual-buffer')?.value || 'JSON') : undefined,
    editable: true,
    saveLabel: '✓ Apply to Request',
    onCommit: (next) => setTesterBody(next)
  }));

  const copyReqBtn = getEl('btnTesterCopyRequest', 'btn-tester-copy-request');
  copyReqBtn?.addEventListener('click', () => copyWithFeedback(currentRequestBody || '', copyReqBtn, currentRequestBody ? 'Request payload copied' : 'Request payload is empty'));

  const bodyModeToggle = getEl('testerBodyModeToggle', 'tester-body-mode-toggle');
  bodyModeToggle?.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-mode], .btn-mode-rich, .btn-mode-raw');
    if (!btn) return;
    currentRequestViewMode = (btn.getAttribute('data-mode') || (btn.classList.contains('btn-mode-raw') ? 'raw' : 'rich')) === 'raw' ? 'raw' : 'rich';
    syncModeToggle(bodyModeToggle, currentRequestViewMode);
    if (requestViewer) requestViewer.ctrl.setMode(currentRequestViewMode);
    else renderTesterRequestViewer();
  });

  const respModeToggle = getEl('testerResponseModeToggle', 'tester-response-mode-toggle') || document.querySelector('#btn-tester-response-mode-toggle');
  respModeToggle?.addEventListener('click', (e) => {
    const btn = e.target.closest('[data-mode], .btn-mode-rich, .btn-mode-raw');
    if (!btn) return;
    const mode = (btn.getAttribute('data-mode') || (btn.classList.contains('btn-mode-raw') ? 'raw' : 'rich')) === 'raw' ? 'raw' : 'highlighted';
    setTesterViewMode(mode);
  });

  getEl('btnTesterResponseMaximize', 'btn-tester-response-maximize')?.addEventListener('click', () => openDocumentWorkspace({
    title: 'Tester Response Payload',
    text: state.lastRawResponseBody || '',
    headers: state.lastResponseHeaders,
    bufferType: lastCasualResponseData?.bufferType || undefined,
    editable: false
  }));

  getEl('btnTesterResetForm', 'btn-tester-reset-form')?.addEventListener('click', () => {
    const proto = state.testerProtocol || 'http';
    const adapter = PROTOCOL_ADAPTERS[proto] || PROTOCOL_ADAPTERS.http;
    adapter.write(adapter.defaults);
    setActiveHeadersText(proto, adapter.defaults.headers || '');
    if (proto === 'casual') { syncDefaultCasualHeaders(); fetchCasualConfig(); }
    if (proto === 'http') renderLocalHeaderChips();
    setTesterBody(adapter.defaults.body || '');
    updateTesterRoute(true);
  });

  getEl('btnTesterClearHistory', 'btn-tester-clear-history')?.addEventListener('click', async () => {
    const choice = await confirmDialog({ title: 'Clear Request History', message: 'Clear all request history?', confirmLabel: 'Clear History', danger: true });
    if (choice === 'confirm') { clearTesterHistory(); showToast('Request history cleared', { type: 'info' }); }
  });

  elements.btnToggleTesterHistory?.addEventListener('click', (e) => { e.stopPropagation(); toggleTesterHistory(); });
  elements.btnExpandTesterHistory?.addEventListener('click', (e) => { e.stopPropagation(); setTesterHistoryCollapsed(false); });
  elements.testerHistoryCollapsedBar?.addEventListener('click', () => setTesterHistoryCollapsed(false));

  elements.btnTesterTabResponse?.addEventListener('click', () => setInspectorTab('response'));
  elements.btnTesterTabRequest?.addEventListener('click', () => setInspectorTab('request'));
  elements.btnTesterTabBoth?.addEventListener('click', () => setInspectorTab('both'));

  const copyCurlBtn = getEl('btnTesterCopyCurl', 'btn-tester-copy-curl');
  copyCurlBtn?.addEventListener('click', () => {
    const f = PROTOCOL_ADAPTERS.http.read();
    copyWithFeedback(generateCurl(f.method, f.destination || '/', parseHeadersInput(getActiveHeadersText()), currentRequestBody), copyCurlBtn, 'cURL command copied');
  });

  const shareBtn = getEl('btnTesterShareLink', 'btn-tester-share-link');
  shareBtn?.addEventListener('click', () => copyWithFeedback(buildTesterShareLink(), shareBtn, 'Share link copied', '✅ Link Copied!'));

  getEl('btnViewHighlighted', 'btn-view-highlighted')?.addEventListener('click', () => setTesterViewMode('highlighted'));
  getEl('btnViewRaw', 'btn-view-raw')?.addEventListener('click', () => setTesterViewMode('raw'));
  getEl('btnViewHex', 'btn-view-hex')?.addEventListener('click', () => setTesterViewMode('hex'));

  const copyRespBtn = getEl('btnTesterCopyResponse', 'btn-tester-copy-response');
  copyRespBtn?.addEventListener('click', () => {
    const copyText = state.lastRawResponseBody || getEl('testerResponseBody', 'tester-response-body')?.textContent || '';
    if (copyText) copyWithFeedback(copyText, copyRespBtn, 'Response copied to clipboard');
  });

  elements.btnTesterResetStub?.addEventListener('click', () => { if (state.contextStub) sendStubToTester(state.contextStub); });
  elements.btnTesterClearContext?.addEventListener('click', () => {
    state.contextStub = null;
    state.selectedStubId = null;
    document.querySelectorAll('.stub-card').forEach(el => el.classList.remove('selected'));
    elements.testerContextBanner?.classList.add('hidden');
    switchTesterProtocol('http', false);
    PROTOCOL_ADAPTERS.http.write(PROTOCOL_ADAPTERS.http.defaults);
    setActiveHeadersText('http', '');
    renderLocalHeaderChips();
    setTesterBody('');
    updateTesterRoute(true);
  });

  getEl('testerRequestTopPill', 'tester-request-top-pill')?.addEventListener('click', (e) => {
    const viewerEl = getEl('testerRequestViewer', 'tester-request-viewer');
    if (viewerEl) e.currentTarget.title = viewerEl.classList.toggle('hidden') ? 'Click to expand' : 'Click to collapse';
  });
  (getEl('testerTopPill', 'tester-top-pill') || document.querySelector('#tester-view-response .preview-pill-badge'))?.addEventListener('click', (e) => {
    const respEl = getEl('testerResponseBody', 'tester-response-body');
    if (respEl) e.currentTarget.title = respEl.classList.toggle('hidden') ? 'Click to expand' : 'Click to collapse';
  });

  switchTesterProtocol(state.testerProtocol || 'http', false);
}

registerUiActions({
  setTesterRequestPayload: (body) => setTesterBody(body),
  renderLocalHeaderChips
});
