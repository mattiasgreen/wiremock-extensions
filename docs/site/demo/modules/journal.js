/**
 * Request Journal viewer, diff inspector, and auto-refresh poller.
 * Contract: Bound to #journal-table, #journal-search, #journal-auto-refresh.
 */
import { state } from './state.js';
import { elements, escapeHtml } from './dom.js';
import { highlightJson } from './highlighter.js';
import {
  decodeBase64Payload,
  normalizeJsonPayload,
  stripIgnoredProperties,
  buildJsonPathBodyPatterns
} from './base64.js';
import { setRoute, activateTab } from './router.js';
import { matchesJournalEntry } from './filter.js';
import { generateCurl, generateJavaStubFor, selectStub } from './stubs.js';
import { updateTesterRoute } from './tester.js';
import { openStubEditorFromRequest, openStubEditor } from './lifecycle.js';

let journalAutoRefreshTimer = null;
let loadJournalFn = null;

export function registerJournalLoader(fn) {
  loadJournalFn = fn;
}

export function toggleJournalDetail(reqId, updateRoute = true) {
  if (state.expandedRequestId === reqId) {
    state.expandedRequestId = null;
  } else {
    state.expandedRequestId = reqId;
  }
  renderJournal(false);
  if (updateRoute) {
    setRoute('journal', { reqId: state.expandedRequestId });
  }
}

function getHeader(headers, name) {
  if (!headers) return null;
  const lower = name.toLowerCase();
  for (const [k, v] of Object.entries(headers)) {
    if (k.toLowerCase() === lower) return v;
  }
  return null;
}

/**
 * Classifies a WireMock ServeEvent into one of the 4 core verdicts:
 * - 'proxied'   (🌐 PROXIED): Matched a proxyBaseUrl stub and forwarded to upstream
 * - 'stateful'  (🧠 STATEFUL): Handled by wiremock-extension-stateful AST engine
 * - 'stub'      (📌 STUB / 📌 STUB · TPL): Served offline by a static or templated stub
 * - 'unmatched' (⚠️ UNMATCHED): No stub or proxy route matched (HTTP 404)
 */
export function getRequestVerdict(req) {
  const resHeaders = (req.response && req.response.headers) || (req.responseDefinition && req.responseDefinition.headers) || {};
  const isStateful = getHeader(resHeaders, 'x-wiremock-stateful') === 'true';
  if (isStateful) {
    const dynamicEntity = getHeader(resHeaders, 'x-wiremock-stateful-entity');
    return {
      key: 'stateful',
      type: 'dynamic',
      label: '🧠 STATEFUL',
      cssClass: 'pill-matched pill-stateful',
      stubDetail: `Stateful: ${dynamicEntity || 'Entity'}`,
      tooltip: 'Simulated by in-memory AST stateful engine (wiremock-extension-stateful)'
    };
  }
  if (!req.wasMatched) {
    return {
      key: 'unmatched',
      type: 'unmatched',
      label: '⚠️ UNMATCHED',
      cssClass: 'pill-unmatched',
      stubDetail: 'Unmatched (404)',
      tooltip: 'No stub or service proxy route matched this request'
    };
  }
  const proxyUrl = (req.responseDefinition && req.responseDefinition.proxyBaseUrl)
    || (req.stubMapping && req.stubMapping.response && req.stubMapping.response.proxyBaseUrl)
    || (req.stubMapping && req.stubMapping.metadata && (req.stubMapping.metadata.proxyBaseUrl || (req.stubMapping.metadata.mode === 'proxy' && req.stubMapping.metadata.targetBaseUrl)));
  const isProxyMode = Boolean(proxyUrl || (req.stubMapping && req.stubMapping.metadata && req.stubMapping.metadata.mode === 'proxy'));
  const rawName = (req.stubMapping && req.stubMapping.name)
    || (req.response && req.response.headers && req.response.headers['Matched-Stub-Name']);
  if (isProxyMode) {
    const detail = proxyUrl
      ? (rawName ? `${rawName} (Proxied to ${proxyUrl})` : `Proxied to ${proxyUrl}`)
      : (rawName ? `${rawName} (Proxied)` : 'Proxied');
    return {
      key: 'proxied',
      type: 'proxied',
      label: '🌐 PROXIED',
      cssClass: 'pill-matched pill-proxied',
      proxyUrl: proxyUrl || 'upstream',
      stubDetail: detail,
      tooltip: `Forwarded to live upstream (${proxyUrl || 'proxy route'}) — ready to Snapshot into an offline stub`
    };
  }
  const transformers = (req.stubMapping && req.stubMapping.response && req.stubMapping.response.transformers)
    || (req.responseDefinition && req.responseDefinition.transformers) || [];
  const resBody = (req.stubMapping && req.stubMapping.response && req.stubMapping.response.body) || '';
  const isTemplated = transformers.includes('response-template') || (typeof resBody === 'string' && resBody.includes('{{'));
  return {
    key: 'stub',
    type: 'stubbed',
    label: isTemplated ? '📌 STUB · TPL' : '📌 STUB',
    cssClass: 'pill-matched pill-stub pill-stubbed',
    isTemplated,
    stubDetail: rawName ? `Stub: ${rawName}` : 'Stubbed',
    tooltip: isTemplated
      ? 'Served offline by a parameterized Handlebars response-template stub'
      : 'Served offline by a static WireMock stub mapping'
  };
}

export const getRequestResolution = getRequestVerdict;

let visibleCount = 50;
let lastFilteredRequests = [];
let scrollListenerAttached = false;
let eventsDelegated = false;

function buildRowHtml(req) {
  const isExpanded = req.id === state.expandedRequestId;
  const time = req.request && req.request.loggedDate ? new Date(req.request.loggedDate).toLocaleTimeString() : '-';
  const method = (req.request && req.request.method) || '-';
  const url = (req.request && req.request.url) || '-';
  const status = (req.response && req.response.status) || (req.responseDefinition && req.responseDefinition.status) || 404;
  const duration = (req.timing && req.timing.totalTime !== undefined) ? `${req.timing.totalTime}ms` : '-';

  const reqHeaders = (req.request && req.request.headers) || {};
  const correlationId = getHeader(reqHeaders, 'x-correlation-id');
  const resHeaders = (req.response && req.response.headers) || (req.responseDefinition && req.responseDefinition.headers) || {};
  const casualService = getHeader(reqHeaders, 'x-casual-service')
    || (url.startsWith('/__casual/services/') ? url.substring('/__casual/services/'.length) : null);
  const isCasual = Boolean(casualService);
  const displayMethod = isCasual ? 'CASUAL' : method;
  const casualErrorState = isCasual
    ? (getHeader(resHeaders, 'x-casual-error-state')
        || (status >= 200 && status < 300 ? 'OK' : (status === 404 ? 'TPENOENT' : 'TPESVCFAIL')))
    : null;
  const displayStatus = isCasual ? casualErrorState : status;
  const verdict = getRequestVerdict(req);
  const isStateful = verdict.key === 'stateful';
  const dynamicEntity = getHeader(resHeaders, 'x-wiremock-stateful-entity');
  const dynamicRoute = getHeader(resHeaders, 'x-wiremock-stateful-route');
  const dynamicInvariants = getHeader(resHeaders, 'x-wiremock-stateful-invariants');

  let html = `
    <tr class="journal-row ${isExpanded ? 'expanded' : ''}" data-request-id="${req.id}" data-verdict="${verdict.key}" data-protocol="${isCasual ? 'casual' : 'http'}" data-testid="journal-row">
      <td><span class="journal-chevron">▶</span></td>
      <td>${time}</td>
      <td><span class="http-badge badge-${displayMethod}" title="${isCasual ? 'Casual XATMI Service Call (tpcall)' : escapeHtml(method)}">${displayMethod}</span></td>
      <td title="${escapeHtml(url)}">
        ${escapeHtml(url)}
        ${correlationId ? `<span class="journal-corr-chip" data-corr="${escapeHtml(correlationId)}" title="Filter Journal by X-Correlation-Id: ${escapeHtml(correlationId)}">🔗 ${escapeHtml(correlationId)}</span>` : ''}
      </td>
      <td>${escapeHtml(String(displayStatus))}</td>
      <td class="${verdict.cssClass}" title="${escapeHtml(verdict.tooltip)}">${verdict.label}</td>
      <td>${duration}</td>
    </tr>
  `;

  if (isExpanded) {
    const reqHeadersHtml = highlightJson(reqHeaders);
    const reqBodyRaw = (req.request && (req.request.body || req.request.bodyAsBase64)) || '';
    const reqB64 = decodeBase64Payload(reqBodyRaw);
    const reqBodyHtml = reqBodyRaw ? highlightJson(reqBodyRaw) : '<span style="color: var(--text-muted);">(empty)</span>';
    const reqBodyDecodedHtml = reqB64.hasBase64 ? highlightJson(reqB64.decodedFormatted) : '';

    const resHeadersHtml = highlightJson(resHeaders);
    const resBodyRaw = (req.response && (req.response.body || req.response.base64Body))
      || (req.responseDefinition && (req.responseDefinition.body || req.responseDefinition.base64Body)) || '';
    const resB64 = decodeBase64Payload(resBodyRaw);
    const resBodyHtml = resBodyRaw ? highlightJson(resBodyRaw) : '<span style="color: var(--text-muted);">(empty)</span>';
    const resBodyDecodedHtml = resB64.hasBase64 ? highlightJson(resB64.decodedFormatted) : '';
    const stubId = req.stubMapping && req.stubMapping.id;
    const stubName = verdict.stubDetail;

    html += `
      <tr class="journal-detail-row" data-request-id="${req.id}" data-testid="journal-detail-row">
        <td colspan="7">
          <div class="journal-detail-content">
            ${verdict.key === 'unmatched' ? `
            <div class="journal-unmatched-diagnostic" data-testid="journal-unmatched-diagnostic">
              <span>⚠️ <strong>Unmatched Request:</strong> WireMock had no matching stub or service proxy route for <code>${escapeHtml(displayMethod)} ${escapeHtml(url)}</code>. Create a stub directly from this captured request or replay it in the HTTP Tester.</span>
              <button class="btn btn-small btn-primary btn-create-stub-from-req" data-id="${req.id}" data-testid="btn-create-stub-from-req">➕ Create Stub from Request</button>
            </div>
            ` : ''}
            <div class="journal-detail-grid">
              ${isStateful ? `
              <div class="journal-detail-card journal-stateful-card" data-testid="journal-stateful-card">
                <div class="journal-card-header">
                  <span>🧠 Stateful Simulation Trace</span>
                  <span class="status-badge badge-dynamic">STATEFUL</span>
                </div>
                <div class="journal-section-title">Entity &amp; Route</div>
                <div style="font-family: monospace; font-size: 0.85rem; margin-bottom: 8px;">
                  <strong>${escapeHtml(dynamicEntity || 'Entity')}</strong>: ${escapeHtml(dynamicRoute || '')}
                </div>
                <div class="journal-section-title">Invariant Evaluation</div>
                <div style="font-size: 0.85rem;" class="${(dynamicInvariants || '').toUpperCase().startsWith('VIOLATED') ? 'text-danger' : 'text-success'}">
                  🛡️ ${escapeHtml(dynamicInvariants || 'PASSED')}
                </div>
              </div>
              ` : ''}
              <div class="journal-detail-card">
                <div class="journal-card-header">
                  <span>${isCasual ? `Casual Request: tpcall("${escapeHtml(casualService)}")` : `Request: ${escapeHtml(method)} ${escapeHtml(url)}`}</span>
                  <div style="display: flex; gap: 6px; flex-wrap: wrap;">
                    ${correlationId ? `<button class="btn btn-small btn-journal-filter-corr" data-corr="${escapeHtml(correlationId)}" title="Filter Journal by X-Correlation-Id: ${escapeHtml(correlationId)}">🔗 Corr: ${escapeHtml(correlationId)}</button>` : ''}
                    <button class="btn btn-small btn-primary btn-journal-to-tester" data-id="${req.id}" data-testid="btn-journal-to-tester" title="Pre-fill HTTP Tester with this exact request">🚀 Replay in Tester</button>
                    <button class="btn btn-small btn-journal-copy-curl" data-id="${req.id}" data-testid="btn-journal-copy-curl" title="Copy request as cURL command">📋 Copy cURL</button>
                    <button class="btn btn-small btn-copy-req" data-id="${req.id}">📋 Copy Request</button>
                  </div>
                </div>
                <div class="journal-section-title">Headers</div>
                <pre class="code-block-mini">${reqHeadersHtml}</pre>
                <div class="journal-section-header">
                  <div class="journal-section-title">Body</div>
                  ${reqB64.hasBase64 ? `
                    <div class="journal-b64-controls">
                      <span class="badge-b64-detected" data-testid="b64-badge" title="Base64-encoded JSON detected in request body">🔓 Base64 JSON</span>
                      <div class="btn-group-toggle">
                        <button type="button" class="btn btn-tiny btn-b64-toggle" data-view="raw" data-target="req-body-${req.id}" data-testid="btn-b64-raw" title="Show raw transmitted payload">Raw</button>
                        <button type="button" class="btn btn-tiny btn-b64-toggle active" data-view="decoded" data-target="req-body-${req.id}" data-testid="btn-b64-decoded" title="Show Base64 decoded JSON">Decoded</button>
                      </div>
                      <button type="button" class="btn btn-tiny btn-copy-body" data-target="req-body-${req.id}" data-testid="btn-copy-body" title="Copy visible body content">📋 Copy Body</button>
                    </div>
                  ` : ''}
                </div>
                ${reqB64.hasBase64 ? `
                  <div id="req-body-${req.id}" class="journal-body-container" data-active-view="decoded">
                    <pre class="code-block-mini journal-body-raw hidden" data-testid="journal-body-raw">${reqBodyHtml}</pre>
                    <pre class="code-block-mini journal-body-decoded" data-testid="journal-body-decoded">${reqBodyDecodedHtml}</pre>
                  </div>
                ` : `
                  <pre class="code-block-mini">${reqBodyHtml}</pre>
                `}
              </div>
              <div class="journal-detail-card">
                <div class="journal-card-header">
                  <span>${isCasual ? `Casual Response: ${escapeHtml(String(displayStatus))} (${escapeHtml(stubName)})` : `Response: Status ${status} (${escapeHtml(stubName)})`}</span>
                  <div style="display: flex; gap: 6px; flex-wrap: wrap;">
                    ${verdict.key !== 'unmatched' && !isStateful ? `
                      <button class="btn btn-small btn-snapshot-req ${!state.capabilities.lifecycle ? 'cap-disabled' : ''}" data-id="${req.id}" ${!state.capabilities.lifecycle ? 'disabled title="Snapshotting into offline stubs requires wiremock-extension-ui lifecycle endpoint"' : `title="${verdict.key === 'proxied' ? 'Snapshot this live proxied response into a disabled static stub for offline simulation' : 'Save a disabled snapshot copy of this response'}"`}>📸 Snapshot Stub</button>
                    ` : ''}
                    ${verdict.key !== 'unmatched' ? `
                      <button class="btn btn-small btn-journal-copy-java" data-id="${req.id}" data-testid="btn-journal-copy-java" title="Copy WireMock Java stubFor() snippet for this request/response pair">☕ Copy Java</button>
                    ` : ''}
                    ${stubId ? `
                      <button class="btn btn-small btn-journal-inspect-stub" data-stub-id="${escapeHtml(stubId)}" title="Jump to the matched stub in the Stubs tab">🔗 Inspect Stub</button>
                    ` : ''}
                    <button class="btn btn-small btn-copy-resp" data-id="${req.id}">📋 Copy Response</button>
                  </div>
                </div>
                <div class="journal-section-title">Headers</div>
                <pre class="code-block-mini">${resHeadersHtml}</pre>
                <div class="journal-section-header">
                  <div class="journal-section-title">Body</div>
                  ${resB64.hasBase64 ? `
                    <div class="journal-b64-controls">
                      <span class="badge-b64-detected" data-testid="b64-badge" title="Base64-encoded JSON detected in response body">🔓 Base64 JSON</span>
                      <div class="btn-group-toggle">
                        <button type="button" class="btn btn-tiny btn-b64-toggle" data-view="raw" data-target="res-body-${req.id}" data-testid="btn-b64-raw" title="Show raw transmitted payload">Raw</button>
                        <button type="button" class="btn btn-tiny btn-b64-toggle active" data-view="decoded" data-target="res-body-${req.id}" data-testid="btn-b64-decoded" title="Show Base64 decoded JSON">Decoded</button>
                      </div>
                      <button type="button" class="btn btn-tiny btn-copy-body" data-target="res-body-${req.id}" data-testid="btn-copy-body" title="Copy visible body content">📋 Copy Body</button>
                    </div>
                  ` : ''}
                </div>
                ${resB64.hasBase64 ? `
                  <div id="res-body-${req.id}" class="journal-body-container" data-active-view="decoded">
                    <pre class="code-block-mini journal-body-raw hidden" data-testid="journal-body-raw">${resBodyHtml}</pre>
                    <pre class="code-block-mini journal-body-decoded" data-testid="journal-body-decoded">${resBodyDecodedHtml}</pre>
                  </div>
                ` : `
                  <pre class="code-block-mini">${resBodyHtml}</pre>
                `}
              </div>
            </div>
          </div>
        </td>
      </tr>
    `;
  }
  return html;
}

function updateCountDisplay() {
  if (!elements.journalFilteredCount) return;
  const total = lastFilteredRequests.length;
  const shown = Math.min(visibleCount, total);
  if (total === state.currentRequests.length) {
    elements.journalFilteredCount.textContent = total > shown
      ? `Showing ${shown} of ${total}`
      : `Showing ${total} of ${total}`;
  } else {
    elements.journalFilteredCount.textContent = total > shown
      ? `Showing ${shown} of ${total} matching (${state.currentRequests.length} total)`
      : `Showing ${total} of ${total} matching (${state.currentRequests.length} total)`;
  }
}

function appendMoreRows() {
  if (visibleCount >= lastFilteredRequests.length) return;
  const nextBatch = lastFilteredRequests.slice(visibleCount, visibleCount + 50);
  visibleCount += nextBatch.length;

  const tempTbody = document.createElement('tbody');
  tempTbody.innerHTML = nextBatch.map(req => buildRowHtml(req)).join('');
  while (tempTbody.firstChild) {
    elements.journalList.appendChild(tempTbody.firstChild);
  }
  updateCountDisplay();
}

function setupLazyScroll() {
  if (scrollListenerAttached) return;
  const container = (elements.journalTable && elements.journalTable.closest('.table-container'))
    || (elements.journalList && elements.journalList.closest('.table-container'))
    || document.querySelector('#tab-journal .table-container');
  if (!container) return;
  scrollListenerAttached = true;
  container.addEventListener('scroll', () => {
    if (container.scrollTop + container.clientHeight >= container.scrollHeight - 80) {
      appendMoreRows();
    }
  });
}

export function buildSnapshotStubMapping(req, options = {}) {
  const project = (req.stubMapping && req.stubMapping.metadata && req.stubMapping.metadata.project) || 'Recorded Traffic';
  const status = (req.response && req.response.status) || (req.responseDefinition && req.responseDefinition.status) || 200;
  const rawResBody = (req.response && (req.response.body || req.response.base64Body))
    || (req.responseDefinition && (req.responseDefinition.body || req.responseDefinition.base64Body)) || '';
  const headers = (req.response && req.response.headers) || (req.responseDefinition && req.responseDefinition.headers) || {};

  const method = req.request ? req.request.method : 'POST';
  const url = req.request ? req.request.url : '/';

  const requestMatch = {
    method: method,
    url: url
  };

  if (req.request && req.request.body) {
    const raw = req.request.body;
    const normalizedJson = normalizeJsonPayload(raw);
    if (normalizedJson !== null) {
      const cleanJson = stripIgnoredProperties(normalizedJson);
      const jsonPathPatterns = buildJsonPathBodyPatterns(cleanJson);
      if (options.useEqualToJson || jsonPathPatterns.length === 0) {
        requestMatch.bodyPatterns = [{
          equalToJson: cleanJson,
          ignoreArrayOrder: true,
          ignoreExtraElements: true
        }];
      } else {
        requestMatch.bodyPatterns = jsonPathPatterns;
      }
    } else {
      requestMatch.bodyPatterns = [{ equalTo: raw }];
    }
  } else if (req.request && req.request.bodyAsBase64) {
    const normalizedJson = normalizeJsonPayload(req.request.bodyAsBase64);
    if (normalizedJson !== null) {
      const cleanJson = stripIgnoredProperties(normalizedJson);
      const jsonPathPatterns = buildJsonPathBodyPatterns(cleanJson);
      if (options.useEqualToJson || jsonPathPatterns.length === 0) {
        requestMatch.bodyPatterns = [{
          equalToJson: cleanJson,
          ignoreArrayOrder: true,
          ignoreExtraElements: true
        }];
      } else {
        requestMatch.bodyPatterns = jsonPathPatterns;
      }
    } else {
      requestMatch.bodyPatterns = [{ binaryEqualTo: req.request.bodyAsBase64 }];
    }
  }

  let finalResBody = '';
  const normalizedResJson = normalizeJsonPayload(rawResBody);
  if (normalizedResJson !== null) {
    finalResBody = JSON.stringify(normalizedResJson, null, 2);
  } else if (typeof rawResBody === 'string') {
    finalResBody = rawResBody;
  } else if (rawResBody) {
    finalResBody = JSON.stringify(rawResBody, null, 2);
  }

  return {
    name: `[SNAPSHOT ${method} ${status}] ${url}`,
    priority: 5,
    request: requestMatch,
    response: {
      status: status,
      headers: headers,
      body: finalResBody
    },
    metadata: {
      source: 'recorded-proxy',
      project: project,
      mode: 'static',
      recordedAt: new Date().toISOString()
    }
  };
}

function setupEventDelegation() {
  if (eventsDelegated || !elements.journalList) return;
  eventsDelegated = true;

  elements.journalList.addEventListener('click', async (e) => {
    const corrBtn = e.target.closest('.btn-journal-filter-corr');
    if (corrBtn) {
      e.stopPropagation();
      const corr = corrBtn.getAttribute('data-corr') || '';
      if (elements.journalSearch) {
        elements.journalSearch.value = corr;
      }
      if (elements.journalContextBanner) {
        elements.journalContextBanner.classList.remove('hidden');
        if (elements.journalFilterChipText) {
          elements.journalFilterChipText.textContent = `Correlation ID: ${corr}`;
        }
      }
      renderJournal();
      setRoute('journal', { q: corr }, true);
      if (loadJournalFn) loadJournalFn();
      return;
    }

    const toTesterBtn = e.target.closest('.btn-journal-to-tester');
    if (toTesterBtn) {
      e.stopPropagation();
      const targetId = toTesterBtn.getAttribute('data-id') || (toTesterBtn.dataset && toTesterBtn.dataset.id);
      const req = state.currentRequests.find(r => r.id === targetId);
      if (req && req.request) {
        if (elements.testerMethod) elements.testerMethod.value = req.request.method || 'GET';
        if (elements.testerUrl) elements.testerUrl.value = req.request.url || '/';
        const headerLines = [];
        if (req.request.headers) {
          const skip = new Set(['host', 'content-length', 'connection', 'user-agent', 'accept-encoding']);
          for (const [k, v] of Object.entries(req.request.headers)) {
            if (!skip.has(k.toLowerCase())) {
              headerLines.push(`${k}: ${v}`);
            }
          }
        }
        if (elements.testerHeaders) elements.testerHeaders.value = headerLines.join('\n');
        if (elements.testerBody) elements.testerBody.value = req.request.body || '';
        activateTab('tab-tester', false);
        updateTesterRoute(false);
      }
      return;
    }

    const copyCurlBtn = e.target.closest('.btn-journal-copy-curl');
    if (copyCurlBtn) {
      e.stopPropagation();
      const targetId = copyCurlBtn.getAttribute('data-id') || (copyCurlBtn.dataset && copyCurlBtn.dataset.id);
      const req = state.currentRequests.find(r => r.id === targetId);
      if (req && req.request) {
        const cmd = generateCurl(req.request.method || 'GET', req.request.url || '/', req.request.headers || {}, req.request.body || '');
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(cmd).catch(() => {});
        }
        copyCurlBtn.textContent = '✅ Copied!';
        setTimeout(() => copyCurlBtn.textContent = '📋 Copy cURL', 1500);
      }
      return;
    }

    const copyJavaBtn = e.target.closest('.btn-journal-copy-java');
    if (copyJavaBtn) {
      e.stopPropagation();
      const targetId = copyJavaBtn.getAttribute('data-id') || (copyJavaBtn.dataset && copyJavaBtn.dataset.id);
      const req = state.currentRequests.find(r => r.id === targetId);
      if (req && req.request) {
        const status = (req.response && req.response.status) || (req.responseDefinition && req.responseDefinition.status) || 200;
        const resBody = (req.response && req.response.body) || (req.responseDefinition && req.responseDefinition.body) || '';
        const resHeaders = (req.response && req.response.headers) || (req.responseDefinition && req.responseDefinition.headers) || {};
        const syntheticStub = {
          request: {
            method: req.request.method || 'GET',
            url: req.request.url || '/',
            bodyPatterns: req.request.body ? [{ equalToJson: req.request.body }] : undefined
          },
          response: {
            status,
            headers: { 'Content-Type': getHeader(resHeaders, 'content-type') || 'application/json' },
            body: typeof resBody === 'string' ? resBody : JSON.stringify(resBody)
          }
        };
        const snippet = generateJavaStubFor(syntheticStub);
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(snippet).catch(() => {});
        }
        copyJavaBtn.textContent = '✅ Copied!';
        setTimeout(() => copyJavaBtn.textContent = '☕ Copy Java', 1500);
      }
      return;
    }

    const inspectStubBtn = e.target.closest('.btn-journal-inspect-stub');
    if (inspectStubBtn) {
      e.stopPropagation();
      const stubId = inspectStubBtn.getAttribute('data-stub-id');
      if (stubId) {
        const found = [...state.currentStubs, ...state.disabledStubs].find(s => s.id === stubId);
        activateTab('tab-stubs', false);
        if (found) {
          selectStub(found, true);
        }
      }
      return;
    }

    const createStubBtn = e.target.closest('.btn-create-stub-from-req');
    if (createStubBtn) {
      e.stopPropagation();
      const targetId = createStubBtn.getAttribute('data-id') || (createStubBtn.dataset && createStubBtn.dataset.id);
      const req = state.currentRequests.find(r => r.id === targetId);
      if (req && req.request) {
        openStubEditorFromRequest(req.request);
      }
      return;
    }

    const copyReqBtn = e.target.closest('.btn-copy-req');
    if (copyReqBtn) {
      e.stopPropagation();
      const targetId = copyReqBtn.getAttribute('data-id') || (copyReqBtn.dataset && copyReqBtn.dataset.id);
      const req = state.currentRequests.find(r => r.id === targetId);
      if (req) {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(JSON.stringify(req.request, null, 2)).catch(() => {});
        }
        copyReqBtn.textContent = '✅ Copied!';
        setTimeout(() => copyReqBtn.textContent = '📋 Copy Request', 1500);
      }
      return;
    }

    const copyRespBtn = e.target.closest('.btn-copy-resp');
    if (copyRespBtn) {
      e.stopPropagation();
      const targetId = copyRespBtn.getAttribute('data-id') || (copyRespBtn.dataset && copyRespBtn.dataset.id);
      const req = state.currentRequests.find(r => r.id === targetId);
      if (req) {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(JSON.stringify(req.response || req.responseDefinition, null, 2)).catch(() => {});
        }
        copyRespBtn.textContent = '✅ Copied!';
        setTimeout(() => copyRespBtn.textContent = '📋 Copy Response', 1500);
      }
      return;
    }

    const b64ToggleBtn = e.target.closest('.btn-b64-toggle');
    if (b64ToggleBtn) {
      e.stopPropagation();
      const view = b64ToggleBtn.getAttribute('data-view');
      const targetId = b64ToggleBtn.getAttribute('data-target');
      const container = document.getElementById(targetId);
      if (!container) return;

      const parentControls = b64ToggleBtn.closest('.journal-b64-controls');
      if (parentControls) {
        parentControls.querySelectorAll('.btn-b64-toggle').forEach(btn => {
          btn.classList.toggle('active', btn === b64ToggleBtn);
        });
      }

      container.setAttribute('data-active-view', view);
      const rawEl = container.querySelector('.journal-body-raw');
      const decodedEl = container.querySelector('.journal-body-decoded');

      if (view === 'raw') {
        if (rawEl) rawEl.classList.remove('hidden');
        if (decodedEl) decodedEl.classList.add('hidden');
      } else {
        if (rawEl) rawEl.classList.add('hidden');
        if (decodedEl) decodedEl.classList.remove('hidden');
      }
      return;
    }

    const copyBodyBtn = e.target.closest('.btn-copy-body');
    if (copyBodyBtn) {
      e.stopPropagation();
      const targetId = copyBodyBtn.getAttribute('data-target');
      const container = document.getElementById(targetId);
      if (container) {
        const activeView = container.getAttribute('data-active-view') || 'decoded';
        const activeEl = container.querySelector(activeView === 'raw' ? '.journal-body-raw' : '.journal-body-decoded');
        const textToCopy = activeEl ? (activeEl.textContent || '') : '';
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(textToCopy).catch(() => {});
        }
        copyBodyBtn.textContent = '✅ Copied!';
        setTimeout(() => {
          copyBodyBtn.textContent = '📋 Copy Body';
        }, 1500);
      }
      return;
    }

    const snapshotBtn = e.target.closest('.btn-snapshot-req');
    if (snapshotBtn) {
      e.stopPropagation();
      const targetId = snapshotBtn.getAttribute('data-id') || (snapshotBtn.dataset && snapshotBtn.dataset.id);
      const req = state.currentRequests.find(r => r.id === targetId);
      if (!req) return;

      const stubData = buildSnapshotStubMapping(req);
      openStubEditor(stubData, false);
      const titleEl = document.getElementById('editor-modal-title');
      if (titleEl) {
        titleEl.textContent = 'Snapshot Stub from Journal';
      }
      return;
    }

    const row = e.target.closest('.journal-row');
    if (row && !e.target.closest('button')) {
      const reqId = row.getAttribute('data-request-id') || (row.dataset && row.dataset.requestId);
      toggleJournalDetail(reqId, true);
    }
  });
}

export function renderJournal(resetLimit = true) {
  setupEventDelegation();
  setupLazyScroll();

  const filter = (elements.journalSearch ? elements.journalSearch.value : '').trim();
  const unmatchedOnly = elements.filterUnmatchedOnly ? elements.filterUnmatchedOnly.checked : false;
  const verdictFilter = state.journalVerdictFilter || 'all';
  const sinceMs = state.journalSince ? new Date(state.journalSince).getTime() : null;
  const untilMs = state.journalUntil ? new Date(state.journalUntil).getTime() : null;

  lastFilteredRequests = state.currentRequests.filter(r => {
    const verdict = getRequestVerdict(r);
    if (unmatchedOnly && verdict.key !== 'unmatched') return false;
    if (verdictFilter !== 'all' && verdict.key !== verdictFilter) return false;
    if (sinceMs !== null || untilMs !== null) {
      const loggedDate = r.request && r.request.loggedDate;
      if (loggedDate) {
        const t = typeof loggedDate === 'number' ? loggedDate : new Date(loggedDate).getTime();
        if (!isNaN(t)) {
          if (sinceMs !== null && t < sinceMs) return false;
          if (untilMs !== null && t > untilMs) return false;
        }
      }
    }
    if (!filter) return true;
    const rHeaders = (r.response && r.response.headers) || (r.responseDefinition && r.responseDefinition.headers) || {};
    const statefulEntity = getHeader(rHeaders, 'x-wiremock-stateful-entity') || '';
    return matchesJournalEntry(r, filter, statefulEntity, `${verdict.key} ${verdict.type} ${verdict.label}`);
  });

  const proxiedFiltered = lastFilteredRequests.filter(r => getRequestVerdict(r).key === 'proxied');
  if (elements.journalProxiedCount) {
    elements.journalProxiedCount.textContent = proxiedFiltered.length;
  }
  if (elements.btnJournalSnapshotFiltered) {
    elements.btnJournalSnapshotFiltered.disabled = proxiedFiltered.length === 0;
    elements.btnJournalSnapshotFiltered.title = proxiedFiltered.length > 0
      ? `Snapshot ${proxiedFiltered.length} proxied request(s) into disabled stubs`
      : 'No proxied requests match the active filter';
  }

  if (resetLimit) {
    visibleCount = 50;
  }

  const total = lastFilteredRequests.length;
  const itemsToRender = lastFilteredRequests.slice(0, visibleCount);

  updateCountDisplay();

  if (total === 0) {
    if (unmatchedOnly || verdictFilter === 'unmatched') {
      elements.journalList.innerHTML = '<tr><td colspan="7" class="text-center">No unmatched requests recorded.</td></tr>';
    } else if (filter || verdictFilter !== 'all' || sinceMs !== null || untilMs !== null) {
      elements.journalList.innerHTML = '<tr><td colspan="7" class="text-center">No matching requests found.</td></tr>';
    } else {
      elements.journalList.innerHTML = '<tr><td colspan="7" class="text-center">No requests recorded yet.</td></tr>';
    }
    return;
  }

  elements.journalList.innerHTML = itemsToRender.map(req => buildRowHtml(req)).join('');
}

export function getFilteredProxiedRequests() {
  return (lastFilteredRequests || []).filter(r => getRequestVerdict(r).key === 'proxied');
}

export function setupJournalAutoRefresh() {
  if (elements.journalAutoRefresh && elements.journalAutoRefresh.checked) {
    if (!journalAutoRefreshTimer) {
      journalAutoRefreshTimer = setInterval(() => {
        if (loadJournalFn) loadJournalFn();
      }, 3000);
    }
  } else {
    if (journalAutoRefreshTimer) {
      clearInterval(journalAutoRefreshTimer);
      journalAutoRefreshTimer = null;
    }
  }
}


