/**
 * Request Journal viewer, diff inspector, and auto-refresh poller.
 * Contract: Bound to #journal-table, #journal-search, #journal-auto-refresh.
 */
import { state } from './state.js';
import { elements, escapeHtml } from './dom.js';
import { highlightJson } from './highlighter.js';
import { setRoute } from './router.js';
import { matchesJournalEntry } from './filter.js';

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
  renderJournal();
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

export function getRequestResolution(req) {
  if (!req.wasMatched) {
    return {
      type: 'unmatched',
      label: 'UNMATCHED',
      cssClass: 'pill-unmatched',
      stubDetail: 'None (404)'
    };
  }

  const resHeaders = (req.response && req.response.headers) || (req.responseDefinition && req.responseDefinition.headers) || {};
  const isDynamic = getHeader(resHeaders, 'x-wiremock-stateful') === 'true';
  if (isDynamic) {
    const dynamicEntity = getHeader(resHeaders, 'x-wiremock-stateful-entity');
    return {
      type: 'dynamic',
      label: '⚡ DYNAMIC',
      cssClass: 'pill-matched pill-stateful',
      stubDetail: `Dynamic: ${dynamicEntity || 'Stateful'}`
    };
  }

  const proxyBaseUrl = (req.responseDefinition && req.responseDefinition.proxyBaseUrl) ||
    (req.stubMapping && req.stubMapping.response && req.stubMapping.response.proxyBaseUrl) ||
    (req.stubMapping && req.stubMapping.metadata && (req.stubMapping.metadata.proxyBaseUrl || (req.stubMapping.metadata.mode === 'proxy' && req.stubMapping.metadata.targetBaseUrl)));
  const isProxy = Boolean(
    proxyBaseUrl ||
    (req.stubMapping && req.stubMapping.metadata && req.stubMapping.metadata.mode === 'proxy')
  );

  if (isProxy) {
    const rawName = (req.stubMapping && req.stubMapping.name) || (req.response && req.response.headers && req.response.headers['Matched-Stub-Name']);
    const detail = proxyBaseUrl
      ? (rawName ? `${rawName} (Proxied to ${proxyBaseUrl})` : `Proxied to ${proxyBaseUrl}`)
      : (rawName ? `${rawName} (Proxied)` : 'Proxied');
    return {
      type: 'proxied',
      label: '🌐 PROXIED',
      cssClass: 'pill-matched pill-proxied',
      stubDetail: detail
    };
  }

  const rawName = (req.stubMapping && req.stubMapping.name)
    || (req.response && req.response.headers && req.response.headers['Matched-Stub-Name']);

  return {
    type: 'stubbed',
    label: '📋 STUBBED',
    cssClass: 'pill-matched pill-stubbed',
    stubDetail: rawName ? `Stub: ${rawName}` : 'Stubbed'
  };
}

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
  const resolution = getRequestResolution(req);

  const resHeaders = (req.response && req.response.headers) || (req.responseDefinition && req.responseDefinition.headers) || {};
  const isDynamic = resolution.type === 'dynamic';
  const dynamicEntity = getHeader(resHeaders, 'x-wiremock-stateful-entity');
  const dynamicRoute = getHeader(resHeaders, 'x-wiremock-stateful-route');
  const dynamicInvariants = getHeader(resHeaders, 'x-wiremock-stateful-invariants');

  let html = `
    <tr class="journal-row ${isExpanded ? 'expanded' : ''}" data-request-id="${req.id}" data-testid="journal-row">
      <td><span class="journal-chevron">▶</span></td>
      <td>${time}</td>
      <td><span class="http-badge badge-${method}">${method}</span></td>
      <td title="${escapeHtml(url)}">${escapeHtml(url)}</td>
      <td>${status}</td>
      <td class="${resolution.cssClass}">${resolution.label}</td>
      <td>${duration}</td>
    </tr>
  `;

  if (isExpanded) {
    const reqHeadersHtml = highlightJson((req.request && req.request.headers) || {});
    const reqBodyRaw = (req.request && req.request.body) || '';
    const reqBodyHtml = reqBodyRaw ? highlightJson(reqBodyRaw) : '<span style="color: var(--text-muted);">(empty)</span>';

    const resHeadersHtml = highlightJson(resHeaders);
    const resBodyRaw = (req.response && req.response.body) || (req.responseDefinition && req.responseDefinition.body) || '';
    const resBodyHtml = resBodyRaw ? highlightJson(resBodyRaw) : '<span style="color: var(--text-muted);">(empty)</span>';
    const stubName = resolution.stubDetail;

    html += `
      <tr class="journal-detail-row" data-request-id="${req.id}" data-testid="journal-detail-row">
        <td colspan="7">
          <div class="journal-detail-content">
            <div class="journal-detail-grid">
              ${isDynamic ? `
              <div class="journal-detail-card journal-stateful-card" data-testid="journal-stateful-card">
                <div class="journal-card-header">
                  <span>⚡ Stateful Simulation Trace</span>
                  <span class="status-badge badge-dynamic">DYNAMIC</span>
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
                  <span>Request: ${escapeHtml(method)} ${escapeHtml(url)}</span>
                  <button class="btn btn-small btn-copy-req" data-id="${req.id}">📋 Copy Request</button>
                </div>
                <div class="journal-section-title">Headers</div>
                <pre class="code-block-mini">${reqHeadersHtml}</pre>
                <div class="journal-section-title">Body</div>
                <pre class="code-block-mini">${reqBodyHtml}</pre>
              </div>
              <div class="journal-detail-card">
                <div class="journal-card-header">
                  <span>Response: Status ${status} (${escapeHtml(stubName)})</span>
                  <div style="display: flex; gap: 6px;">
                    <button class="btn btn-small btn-freeze-req" data-id="${req.id}" title="Freeze this live response as a disabled static stub for review">📸 Freeze Stub</button>
                    <button class="btn btn-small btn-copy-resp" data-id="${req.id}">📋 Copy Response</button>
                  </div>
                </div>
                <div class="journal-section-title">Headers</div>
                <pre class="code-block-mini">${resHeadersHtml}</pre>
                <div class="journal-section-title">Body</div>
                <pre class="code-block-mini">${resBodyHtml}</pre>
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
      ? `Showing ${shown} of ${total} matching (${state.currentRequests.length} loaded)`
      : `Showing ${total} of ${total} matching (${state.currentRequests.length} loaded)`;
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

function setupEventDelegation() {
  if (eventsDelegated || !elements.journalList) return;
  eventsDelegated = true;

  elements.journalList.addEventListener('click', async (e) => {
    const copyReqBtn = e.target.closest('.btn-copy-req');
    if (copyReqBtn) {
      e.stopPropagation();
      const targetId = copyReqBtn.getAttribute('data-id') || (copyReqBtn.dataset && copyReqBtn.dataset.id);
      const req = state.currentRequests.find(r => r.id === targetId);
      if (req && navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(JSON.stringify(req.request, null, 2)).catch(() => {});
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
      if (req && navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(JSON.stringify(req.response || req.responseDefinition, null, 2)).catch(() => {});
        copyRespBtn.textContent = '✅ Copied!';
        setTimeout(() => copyRespBtn.textContent = '📋 Copy Response', 1500);
      }
      return;
    }

    const freezeBtn = e.target.closest('.btn-freeze-req');
    if (freezeBtn) {
      e.stopPropagation();
      const targetId = freezeBtn.getAttribute('data-id') || (freezeBtn.dataset && freezeBtn.dataset.id);
      const req = state.currentRequests.find(r => r.id === targetId);
      if (!req) return;

      const project = (req.stubMapping && req.stubMapping.metadata && req.stubMapping.metadata.project) || 'Recorded Traffic';
      const status = (req.response && req.response.status) || (req.responseDefinition && req.responseDefinition.status) || 200;
      const resBody = (req.response && req.response.body) || (req.responseDefinition && req.responseDefinition.body) || '';
      const headers = (req.response && req.response.headers) || (req.responseDefinition && req.responseDefinition.headers) || {};

      const stubData = {
        name: `[RECORDED ${req.request.method} ${status}] ${req.request.url}`,
        priority: 5,
        request: {
          method: req.request.method,
          url: req.request.url
        },
        response: {
          status: status,
          headers: headers,
          body: typeof resBody === 'string' ? resBody : JSON.stringify(resBody)
        },
        metadata: {
          source: 'recorded-proxy',
          project: project,
          mode: 'static',
          recordedAt: new Date().toISOString()
        }
      };

      try {
        freezeBtn.disabled = true;
        freezeBtn.textContent = 'Saving...';
        const { createStubMapping, toggleStubState, loadData } = await import('./api.js');
        const created = await createStubMapping(stubData);
        if (created && created.id) {
          await toggleStubState(created.id);
        }
        await loadData();
        freezeBtn.textContent = '✅ Frozen!';
        setTimeout(() => freezeBtn.textContent = '📸 Freeze Stub', 1500);
      } catch (err) {
        alert('Failed to freeze stub: ' + err.message);
        freezeBtn.textContent = '📸 Freeze Stub';
      } finally {
        freezeBtn.disabled = false;
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

  lastFilteredRequests = state.currentRequests.filter(r => {
    if (unmatchedOnly && r.wasMatched) return false;
    if (!filter) return true;
    const rHeaders = (r.response && r.response.headers) || (r.responseDefinition && r.responseDefinition.headers) || {};
    const statefulEntity = getHeader(rHeaders, 'x-wiremock-stateful-entity') || '';
    const res = getRequestResolution(r);
    return matchesJournalEntry(r, filter, statefulEntity, `${res.type} ${res.label}`);
  });

  if (resetLimit) {
    visibleCount = 50;
  }

  const total = lastFilteredRequests.length;
  const itemsToRender = lastFilteredRequests.slice(0, visibleCount);

  updateCountDisplay();

  if (total === 0) {
    if (unmatchedOnly) {
      elements.journalList.innerHTML = '<tr><td colspan="7" class="text-center">No unmatched requests recorded.</td></tr>';
    } else if (filter) {
      elements.journalList.innerHTML = '<tr><td colspan="7" class="text-center">No matching requests found.</td></tr>';
    } else {
      elements.journalList.innerHTML = '<tr><td colspan="7" class="text-center">No requests recorded yet.</td></tr>';
    }
    return;
  }

  elements.journalList.innerHTML = itemsToRender.map(req => buildRowHtml(req)).join('');
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
