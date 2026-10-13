/**
 * Request Journal viewer, diff inspector, and auto-refresh poller.
 * Contract: Bound to #journal-table, #journal-search, #journal-auto-refresh.
 */
import { state } from './state.js';
import { elements, escapeHtml } from './dom.js';
import { highlightJson } from './highlighter.js';
import { setRoute } from './router.js';

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

export function renderJournal() {
  const filter = (elements.journalSearch ? elements.journalSearch.value : '').toLowerCase().trim();
  const unmatchedOnly = elements.filterUnmatchedOnly.checked;

  const filtered = state.currentRequests.filter(r => {
    if (unmatchedOnly && r.wasMatched) return false;
    if (!filter) return true;
    const method = (r.request && r.request.method ? r.request.method : '').toLowerCase();
    const url = (r.request && r.request.url ? r.request.url : '').toLowerCase();
    const status = String((r.response && r.response.status) || (r.responseDefinition && r.responseDefinition.status) || '');
    const stubName = (r.stubMapping && r.stubMapping.name ? r.stubMapping.name : '').toLowerCase();
    const rHeaders = (r.response && r.response.headers) || (r.responseDefinition && r.responseDefinition.headers) || {};
    const statefulEntity = (getHeader(rHeaders, 'x-wiremock-stateful-entity') || '').toLowerCase();
    return method.includes(filter) || url.includes(filter) || status.includes(filter) || stubName.includes(filter) || statefulEntity.includes(filter);
  });

  if (elements.journalFilteredCount) {
    elements.journalFilteredCount.textContent = `Showing ${filtered.length} of ${state.currentRequests.length}`;
  }

  if (filtered.length === 0) {
    elements.journalList.innerHTML = '<tr><td colspan="7" class="text-center">No matching requests found.</td></tr>';
    return;
  }

  let html = '';
  filtered.forEach(req => {
    const isExpanded = req.id === state.expandedRequestId;
    const time = req.request && req.request.loggedDate ? new Date(req.request.loggedDate).toLocaleTimeString() : '-';
    const method = (req.request && req.request.method) || '-';
    const url = (req.request && req.request.url) || '-';
    const status = (req.response && req.response.status) || (req.responseDefinition && req.responseDefinition.status) || 404;
    const matched = req.wasMatched;
    const duration = (req.timing && req.timing.totalTime !== undefined) ? `${req.timing.totalTime}ms` : '-';

    const resHeaders = (req.response && req.response.headers) || (req.responseDefinition && req.responseDefinition.headers) || {};
    const isDynamic = getHeader(resHeaders, 'x-wiremock-stateful') === 'true';
    const dynamicEntity = getHeader(resHeaders, 'x-wiremock-stateful-entity');
    const dynamicRoute = getHeader(resHeaders, 'x-wiremock-stateful-route');
    const dynamicInvariants = getHeader(resHeaders, 'x-wiremock-stateful-invariants');

    html += `
      <tr class="journal-row ${isExpanded ? 'expanded' : ''}" data-request-id="${req.id}" data-testid="journal-row">
        <td><span class="journal-chevron">▶</span></td>
        <td>${time}</td>
        <td><span class="http-badge badge-${method}">${method}</span></td>
        <td title="${escapeHtml(url)}">${escapeHtml(url)}</td>
        <td>${status}</td>
        <td class="${isDynamic ? 'pill-matched pill-stateful' : (matched ? 'pill-matched' : 'pill-unmatched')}">${isDynamic ? '⚡ DYNAMIC' : (matched ? 'MATCHED' : 'UNMATCHED')}</td>
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
      const stubName = (req.stubMapping && req.stubMapping.name)
        || (req.response && req.response.headers && req.response.headers['Matched-Stub-Name'])
        || (isDynamic ? `Dynamic: ${dynamicEntity}` : (matched ? 'Matched' : 'None (404)'));

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
  });

  elements.journalList.innerHTML = html;

  elements.journalList.querySelectorAll('.journal-row').forEach(row => {
    row.addEventListener('click', (e) => {
      if (e.target.closest('button')) return;
      const reqId = row.getAttribute('data-request-id') || (row.dataset && row.dataset.requestId);
      toggleJournalDetail(reqId, true);
    });
  });

  elements.journalList.querySelectorAll('.btn-freeze-req').forEach(btn => {
    btn.addEventListener('click', async (e) => {
      e.stopPropagation();
      const targetId = btn.getAttribute('data-id') || (btn.dataset && btn.dataset.id);
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
        btn.disabled = true;
        btn.textContent = 'Saving...';
        const { createStubMapping, toggleStubState, loadData } = await import('./api.js');
        const created = await createStubMapping(stubData);
        if (created && created.id) {
          await toggleStubState(created.id); // Park into disabled store per default
        }
        await loadData();
        btn.textContent = '✅ Frozen!';
        setTimeout(() => btn.textContent = '📸 Freeze Stub', 1500);
      } catch (err) {
        alert('Failed to freeze stub: ' + err.message);
        btn.textContent = '📸 Freeze Stub';
      } finally {
        btn.disabled = false;
      }
    });
  });

  elements.journalList.querySelectorAll('.btn-copy-req').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const targetId = btn.getAttribute('data-id') || (btn.dataset && btn.dataset.id);
      const req = state.currentRequests.find(r => r.id === targetId);
      if (req) {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(JSON.stringify(req.request, null, 2)).catch(() => {});
        }
        btn.textContent = '✅ Copied!';
        setTimeout(() => btn.textContent = '📋 Copy Request', 1500);
      }
    });
  });

  elements.journalList.querySelectorAll('.btn-copy-resp').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const targetId = btn.getAttribute('data-id') || (btn.dataset && btn.dataset.id);
      const req = state.currentRequests.find(r => r.id === targetId);
      if (req) {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(JSON.stringify(req.response || req.responseDefinition, null, 2)).catch(() => {});
        }
        btn.textContent = '✅ Copied!';
        setTimeout(() => btn.textContent = '📋 Copy Response', 1500);
      }
    });
  });
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
