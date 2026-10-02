// Pure Vanilla JS for WireMock Stub Viewer
(function () {
  'use strict';

  let currentStubs = [];
  let selectedStubId = null;
  let currentRequests = [];
  let isSyncingRoute = false;

  const TAB_ROUTES = {
    'stubs': 'tab-stub-detail',
    'journal': 'tab-journal',
    'tester': 'tab-tester',
    'scenarios': 'tab-scenarios'
  };

  const ROUTE_FOR_TAB = {
    'tab-stub-detail': 'stubs',
    'tab-journal': 'journal',
    'tab-tester': 'tester',
    'tab-scenarios': 'scenarios'
  };

  function toBase64(str) {
    if (!str) return '';
    try {
      return btoa(unescape(encodeURIComponent(str)))
        .replace(/\+/g, '-')
        .replace(/\//g, '_')
        .replace(/=+$/, '');
    } catch (e) {
      return encodeURIComponent(str);
    }
  }

  function fromBase64(b64) {
    if (!b64) return '';
    try {
      let base64 = b64.replace(/-/g, '+').replace(/_/g, '/');
      while (base64.length % 4) base64 += '=';
      return decodeURIComponent(escape(atob(base64)));
    } catch (e) {
      try {
        return decodeURIComponent(b64);
      } catch (_) {
        return b64;
      }
    }
  }

  function parseHash() {
    const raw = (window.location.hash || '').replace(/^#\/?/, '');
    if (!raw) return { route: 'stubs', params: new URLSearchParams() };
    const idx = raw.indexOf('?');
    if (idx === -1) {
      return { route: raw || 'stubs', params: new URLSearchParams() };
    }
    return {
      route: raw.substring(0, idx) || 'stubs',
      params: new URLSearchParams(raw.substring(idx + 1))
    };
  }

  function setRoute(route, newParams = {}, replace = false) {
    if (isSyncingRoute) return;
    const current = parseHash();
    const targetRoute = route !== undefined ? route : current.route;
    const params = new URLSearchParams(current.params);
    for (const [k, v] of Object.entries(newParams)) {
      if (v === null || v === undefined || v === '') {
        params.delete(k);
      } else {
        params.set(k, v);
      }
    }
    const q = params.toString();
    const newHash = `#${targetRoute}${q ? '?' + q : ''}`;
    if (window.location.hash !== newHash) {
      if (replace && window.history && window.history.replaceState) {
        window.history.replaceState(null, '', newHash);
      } else {
        window.location.hash = newHash;
      }
    }
  }

  function activateTab(tabId, updateRoute = true) {
    elements.tabs.forEach(t => t.classList.toggle('active', t.dataset.tab === tabId));
    document.querySelectorAll('.tab-content').forEach(c => c.classList.toggle('active', c.id === tabId));
    if (updateRoute) {
      const route = ROUTE_FOR_TAB[tabId] || 'stubs';
      setRoute(route);
    }
  }

  function applyRouteFromUrl() {
    isSyncingRoute = true;
    try {
      const { route, params } = parseHash();
      const tabId = TAB_ROUTES[route] || 'tab-stub-detail';
      activateTab(tabId, false);

      if (route === 'stubs' || !route) {
        const q = params.get('q') || '';
        if (elements.searchBox.value !== q) {
          elements.searchBox.value = q;
        }
        renderStubList();
        const stubId = params.get('stubId');
        if (stubId) {
          const match = currentStubs.find(s => s.id === stubId);
          if (match) {
            selectStub(match, false);
          }
        }
      }

      if (route === 'journal') {
        const q = params.get('q') || '';
        if (elements.journalSearch && elements.journalSearch.value !== q) {
          elements.journalSearch.value = q;
        }
        const unmatched = params.get('unmatched') === 'true';
        if (elements.filterUnmatchedOnly) {
          elements.filterUnmatchedOnly.checked = unmatched;
        }
        expandedRequestId = params.get('reqId') || null;
        renderJournal();
      }

      if (route === 'tester') {
        const m = params.get('m');
        if (m && elements.testerMethod) elements.testerMethod.value = m;
        const p = params.get('p');
        if (p && elements.testerUrl) elements.testerUrl.value = p;
        const h = params.get('h');
        if (h && elements.testerHeaders) elements.testerHeaders.value = fromBase64(h);
        const b = params.get('b');
        if (b && elements.testerBody) elements.testerBody.value = fromBase64(b);
      }
    } finally {
      isSyncingRoute = false;
    }
  }

  const elements = {
    stubList: document.getElementById('stub-list'),
    searchBox: document.getElementById('search-box'),
    statStubs: document.getElementById('stat-stubs'),
    statRequests: document.getElementById('stat-requests'),
    statScenarios: document.getElementById('stat-scenarios'),
    stubsCountLabel: document.getElementById('stubs-count-label'),
    journalCount: document.getElementById('journal-count'),
    stubDetailEmpty: document.getElementById('stub-detail-empty'),
    stubDetailView: document.getElementById('stub-detail-view'),
    detailMethod: document.getElementById('detail-method'),
    detailUrl: document.getElementById('detail-url'),
    detailName: document.getElementById('detail-name'),
    detailStatus: document.getElementById('detail-status'),
    detailPriority: document.getElementById('detail-priority'),
    detailScenario: document.getElementById('detail-scenario'),
    stubJsonViewer: document.getElementById('stub-json-viewer'),
    journalList: document.getElementById('journal-list'),
    journalSearch: document.getElementById('journal-search'),
    journalAutoRefresh: document.getElementById('journal-auto-refresh'),
    journalFilteredCount: document.getElementById('journal-filtered-count'),
    scenariosContainer: document.getElementById('scenarios-container'),
    btnRefresh: document.getElementById('btn-refresh'),
    btnResetJournal: document.getElementById('btn-reset-journal'),
    btnCopyJson: document.getElementById('btn-copy-json'),
    btnCopyCurl: document.getElementById('btn-copy-curl'),
    btnTestStub: document.getElementById('btn-test-stub'),
    filterUnmatchedOnly: document.getElementById('filter-unmatched-only'),
    testerMethod: document.getElementById('tester-method'),
    testerUrl: document.getElementById('tester-url'),
    btnTesterSend: document.getElementById('btn-tester-send'),
    testerHeaders: document.getElementById('tester-headers'),
    testerBody: document.getElementById('tester-body'),
    btnTesterFormatJson: document.getElementById('btn-tester-format-json'),
    btnTesterCopyCurl: document.getElementById('btn-tester-copy-curl'),
    btnTesterShareLink: document.getElementById('btn-tester-share-link'),
    testerResponseStatus: document.getElementById('tester-response-status'),
    testerResponseTime: document.getElementById('tester-response-time'),
    testerResponseHeaders: document.getElementById('tester-response-headers'),
    testerResponseHeaderCount: document.getElementById('tester-response-header-count'),
    testerResponseBody: document.getElementById('tester-response-body'),
    btnViewHighlighted: document.getElementById('btn-view-highlighted'),
    btnViewRaw: document.getElementById('btn-view-raw'),
    btnTesterCopyResponse: document.getElementById('btn-tester-copy-response'),
    tabs: document.querySelectorAll('.tab')
  };

  async function apiGet(endpoint) {
    const res = await fetch(endpoint);
    if (!res.ok) throw new Error(`HTTP ${res.status} from ${endpoint}`);
    return res.json();
  }

  async function loadData() {
    try {
      await Promise.all([loadMappings(), loadJournal(), loadScenarios()]);
      applyRouteFromUrl();
    } catch (err) {
      console.error('Error refreshing data', err);
    }
  }

  async function loadMappings() {
    try {
      const data = await apiGet('/__admin/mappings');
      currentStubs = data.mappings || [];
      renderStubList();
      elements.statStubs.textContent = currentStubs.length;
      elements.stubsCountLabel.textContent = `${currentStubs.length} mappings`;
    } catch (err) {
      elements.stubList.innerHTML = `<div class="empty-state text-error">Failed to load stubs: ${err.message}</div>`;
    }
  }

  async function loadJournal() {
    try {
      const data = await apiGet('/__admin/requests?limit=100');
      currentRequests = data.requests || [];
      elements.statRequests.textContent = currentRequests.length;
      elements.journalCount.textContent = currentRequests.length;
      renderJournal();
    } catch (err) {
      console.warn('Could not load journal', err);
    }
  }

  async function loadScenarios() {
    try {
      const data = await apiGet('/__admin/scenarios');
      const scenarios = data.scenarios || [];
      elements.statScenarios.textContent = scenarios.length;
      renderScenarios(scenarios);
    } catch (err) {
      console.warn('Could not load scenarios', err);
    }
  }

  function getStubUrl(req) {
    if (!req) return '/';
    return req.url || req.urlPath || req.urlPattern || req.urlPathPattern || '/';
  }

  function renderStubList() {
    const filter = (elements.searchBox.value || '').toLowerCase().trim();
    const filtered = currentStubs.filter(s => {
      if (!filter) return true;
      const method = (s.request?.method || 'ANY').toLowerCase();
      const url = getStubUrl(s.request).toLowerCase();
      const name = (s.name || '').toLowerCase();
      return method.includes(filter) || url.includes(filter) || name.includes(filter);
    });

    if (filtered.length === 0) {
      elements.stubList.innerHTML = '<div class="empty-state">No matching stubs found.</div>';
      return;
    }

    elements.stubList.innerHTML = '';
    filtered.forEach(stub => {
      const card = document.createElement('div');
      card.className = `stub-card ${stub.id === selectedStubId ? 'selected' : ''}`;
      card.dataset.stubId = stub.id;

      const method = stub.request?.method || 'ANY';
      const url = getStubUrl(stub.request);
      const name = stub.name || (stub.response?.status ? `Status ${stub.response.status}` : 'Unnamed');

      card.innerHTML = `
        <div class="stub-card-top">
          <span class="http-badge badge-${method}">${method}</span>
          <span class="stub-card-url" title="${url}">${url}</span>
        </div>
        <div class="stub-card-bottom">
          <span>${name}</span>
          <span>Status: ${stub.response?.status || 200}</span>
        </div>
      `;

      card.addEventListener('click', () => selectStub(stub, true));
      elements.stubList.appendChild(card);
    });

    // Keep active selected stub updated
    if (selectedStubId) {
      const selected = currentStubs.find(s => s.id === selectedStubId);
      if (selected) selectStub(selected, false);
    }
  }

  function selectStub(stub, updateRoute = true) {
    selectedStubId = stub.id;
    document.querySelectorAll('.stub-card').forEach(el => {
      el.classList.toggle('selected', el.dataset.stubId === stub.id);
    });

    elements.stubDetailEmpty.classList.add('hidden');
    elements.stubDetailView.classList.remove('hidden');

    const method = stub.request?.method || 'ANY';
    elements.detailMethod.textContent = method;
    elements.detailMethod.className = `http-badge badge-${method}`;
    elements.detailUrl.textContent = getStubUrl(stub.request);
    elements.detailName.textContent = stub.name ? `— ${stub.name}` : '';

    const status = stub.response?.status || 200;
    elements.detailStatus.textContent = status;
    elements.detailStatus.className = `card-value status-${status >= 500 ? '500' : (status >= 400 ? '400' : '200')}`;

    elements.detailPriority.textContent = stub.priority || 5;
    elements.detailScenario.textContent = stub.scenarioName
      ? `${stub.scenarioName} [${stub.requiredScenarioState || 'Start'} -> ${stub.newScenarioState || 'Same'}]`
      : 'None';

    elements.stubJsonViewer.innerHTML = highlightJson(stub);

    if (updateRoute) {
      setRoute('stubs', { stubId: stub.id });
    }
  }

  function highlightJson(input) {
    if (input === null || input === undefined) return '';
    let jsonStr = typeof input === 'string' ? input : JSON.stringify(input, null, 2);
    try {
      if (typeof input === 'string' && (input.trim().startsWith('{') || input.trim().startsWith('['))) {
        jsonStr = JSON.stringify(JSON.parse(input), null, 2);
      }
    } catch (_) {}

    const safe = escapeHtml(jsonStr);
    return safe.replace(/("(\\u[a-zA-Z0-9]{4}|\\[^u]|[^\\"])*"(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d*)?(?:[eE][+\-]?\d+)?)/g, function (match) {
      let cls = 'json-number';
      if (/^"/.test(match)) {
        if (/:$/.test(match)) {
          cls = 'json-key';
        } else {
          cls = 'json-string';
        }
      } else if (/true|false/.test(match)) {
        cls = 'json-boolean';
      } else if (/null/.test(match)) {
        cls = 'json-null';
      }
      return `<span class="${cls}">${match}</span>`;
    });
  }

  function generateCurl(method, path, headers = {}, body = '') {
    const origin = window.location.origin || 'http://localhost:8080';
    const cleanPath = path || '/';
    const fullUrl = cleanPath.startsWith('http') ? cleanPath : `${origin}${cleanPath.startsWith('/') ? '' : '/'}${cleanPath}`;
    const parts = [`curl -X ${method || 'GET'} "${fullUrl}"`];
    for (const [k, v] of Object.entries(headers)) {
      if (v) parts.push(`-H "${k}: ${v}"`);
    }
    if (body && ['POST', 'PUT', 'PATCH', 'DELETE'].includes((method || 'GET').toUpperCase())) {
      const escapedBody = String(body).replace(/"/g, '\\"');
      parts.push(`-d "${escapedBody}"`);
    }
    return parts.join(' \\\n  ');
  }

  let expandedRequestId = null;
  let journalAutoRefreshTimer = null;

  function escapeHtml(str) {
    if (str === null || str === undefined) return '';
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }

  function toggleJournalDetail(reqId, updateRoute = true) {
    if (expandedRequestId === reqId) {
      expandedRequestId = null;
    } else {
      expandedRequestId = reqId;
    }
    renderJournal();
    if (updateRoute) {
      setRoute('journal', { reqId: expandedRequestId });
    }
  }

  function renderJournal() {
    const filter = (elements.journalSearch ? elements.journalSearch.value : '').toLowerCase().trim();
    const unmatchedOnly = elements.filterUnmatchedOnly.checked;

    const filtered = currentRequests.filter(r => {
      if (unmatchedOnly && r.wasMatched) return false;
      if (!filter) return true;
      const method = (r.request?.method || '').toLowerCase();
      const url = (r.request?.url || '').toLowerCase();
      const status = String(r.response?.status || r.responseDefinition?.status || '');
      const stubName = (r.stubMapping?.name || '').toLowerCase();
      return method.includes(filter) || url.includes(filter) || status.includes(filter) || stubName.includes(filter);
    });

    if (elements.journalFilteredCount) {
      elements.journalFilteredCount.textContent = `Showing ${filtered.length} of ${currentRequests.length}`;
    }

    if (filtered.length === 0) {
      elements.journalList.innerHTML = '<tr><td colspan="7" class="text-center">No matching requests found.</td></tr>';
      return;
    }

    let html = '';
    filtered.forEach(req => {
      const isExpanded = req.id === expandedRequestId;
      const time = req.request?.loggedDate ? new Date(req.request.loggedDate).toLocaleTimeString() : '-';
      const method = req.request?.method || '-';
      const url = req.request?.url || '-';
      const status = req.response?.status || req.responseDefinition?.status || 404;
      const matched = req.wasMatched;
      const duration = req.timing?.totalTime !== undefined ? `${req.timing.totalTime}ms` : '-';

      html += `
        <tr class="journal-row ${isExpanded ? 'expanded' : ''}" data-request-id="${req.id}">
          <td><span class="journal-chevron">▶</span></td>
          <td>${time}</td>
          <td><span class="http-badge badge-${method}">${method}</span></td>
          <td title="${escapeHtml(url)}">${escapeHtml(url)}</td>
          <td>${status}</td>
          <td class="${matched ? 'pill-matched' : 'pill-unmatched'}">${matched ? 'MATCHED' : 'UNMATCHED'}</td>
          <td>${duration}</td>
        </tr>
      `;

      if (isExpanded) {
        const reqHeadersHtml = highlightJson(req.request?.headers || {});
        const reqBodyRaw = req.request?.body || '';
        const reqBodyHtml = reqBodyRaw ? highlightJson(reqBodyRaw) : '<span style="color: var(--text-muted);">(empty)</span>';

        const resHeadersHtml = highlightJson(req.response?.headers || req.responseDefinition?.headers || {});
        const resBodyRaw = req.response?.body || req.responseDefinition?.body || '';
        const resBodyHtml = resBodyRaw ? highlightJson(resBodyRaw) : '<span style="color: var(--text-muted);">(empty)</span>';
        const stubName = req.stubMapping?.name || req.response?.headers?.['Matched-Stub-Name'] || (matched ? 'Matched' : 'None (404)');

        html += `
          <tr class="journal-detail-row" data-request-id="${req.id}">
            <td colspan="7">
              <div class="journal-detail-content">
                <div class="journal-detail-grid">
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
                      <button class="btn btn-small btn-copy-resp" data-id="${req.id}">📋 Copy Response</button>
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

    // Attach row click listeners
    elements.journalList.querySelectorAll('.journal-row').forEach(row => {
      row.addEventListener('click', (e) => {
        if (e.target.closest('button')) return;
        const reqId = row.dataset.requestId;
        toggleJournalDetail(reqId, true);
      });
    });

    // Attach copy button listeners
    elements.journalList.querySelectorAll('.btn-copy-req').forEach(btn => {
      btn.addEventListener('click', (e) => {
        e.stopPropagation();
        const req = currentRequests.find(r => r.id === btn.dataset.id);
        if (req) {
          navigator.clipboard.writeText(JSON.stringify(req.request, null, 2));
          btn.textContent = '✅ Copied!';
          setTimeout(() => btn.textContent = '📋 Copy Request', 1500);
        }
      });
    });

    elements.journalList.querySelectorAll('.btn-copy-resp').forEach(btn => {
      btn.addEventListener('click', (e) => {
        e.stopPropagation();
        const req = currentRequests.find(r => r.id === btn.dataset.id);
        if (req) {
          navigator.clipboard.writeText(JSON.stringify(req.response || req.responseDefinition, null, 2));
          btn.textContent = '✅ Copied!';
          setTimeout(() => btn.textContent = '📋 Copy Response', 1500);
        }
      });
    });
  }

  function setupJournalAutoRefresh() {
    if (elements.journalAutoRefresh && elements.journalAutoRefresh.checked) {
      if (!journalAutoRefreshTimer) {
        journalAutoRefreshTimer = setInterval(() => {
          loadJournal();
        }, 3000);
      }
    } else {
      if (journalAutoRefreshTimer) {
        clearInterval(journalAutoRefreshTimer);
        journalAutoRefreshTimer = null;
      }
    }
  }

  function renderScenarios(scenarios) {
    if (scenarios.length === 0) {
      elements.scenariosContainer.innerHTML = '<div class="empty-state">No active stateful scenarios defined.</div>';
      return;
    }

    elements.scenariosContainer.innerHTML = scenarios.map(sc => `
      <div class="summary-card" style="margin-bottom: 12px;">
        <div class="card-label">Scenario</div>
        <div class="card-value" style="font-size: 1rem;">${sc.name}</div>
        <div style="font-size: 0.85rem; color: var(--accent-color); margin-top: 6px;">
          Current State: <strong>${sc.state}</strong>
        </div>
      </div>
    `).join('');
  }

  // Event Listeners
  elements.searchBox.addEventListener('input', () => {
    renderStubList();
    setRoute('stubs', { q: elements.searchBox.value.trim() }, true);
  });
  elements.btnRefresh.addEventListener('click', loadData);
  elements.filterUnmatchedOnly.addEventListener('change', () => {
    renderJournal();
    setRoute('journal', { unmatched: elements.filterUnmatchedOnly.checked ? 'true' : null }, true);
  });

  if (elements.journalSearch) {
    elements.journalSearch.addEventListener('input', () => {
      renderJournal();
      setRoute('journal', { q: elements.journalSearch.value.trim() }, true);
    });
  }

  if (elements.journalAutoRefresh) {
    elements.journalAutoRefresh.addEventListener('change', setupJournalAutoRefresh);
  }

  elements.btnResetJournal.addEventListener('click', async () => {
    if (confirm('Are you sure you want to clear the request journal?')) {
      await fetch('/__admin/requests', { method: 'DELETE' });
      await loadJournal();
    }
  });

  elements.btnCopyJson.addEventListener('click', () => {
    if (elements.stubJsonViewer.textContent) {
      navigator.clipboard.writeText(elements.stubJsonViewer.textContent);
      elements.btnCopyJson.textContent = '✅ Copied!';
      setTimeout(() => elements.btnCopyJson.textContent = '📋 Copy JSON', 1500);
    }
  });

  if (elements.btnCopyCurl) {
    elements.btnCopyCurl.addEventListener('click', () => {
      if (!selectedStubId) return;
      const stub = currentStubs.find(s => s.id === selectedStubId);
      if (!stub) return;
      const method = stub.request?.method || 'GET';
      const path = getStubUrl(stub.request);
      const headers = {};
      if (stub.request?.headers) {
        for (const [k, v] of Object.entries(stub.request.headers)) {
          headers[k] = v.equalTo || v.matches || v.contains || Object.values(v)[0] || '';
        }
      }
      let body = '';
      if (stub.request?.bodyPatterns && stub.request.bodyPatterns.length > 0) {
        const bp = stub.request.bodyPatterns[0];
        body = bp.equalToJson ? JSON.stringify(bp.equalToJson) : (bp.equalTo || '');
      }
      const cmd = generateCurl(method === 'ANY' ? 'GET' : method, path, headers, body);
      navigator.clipboard.writeText(cmd);
      elements.btnCopyCurl.textContent = '✅ Copied!';
      setTimeout(() => elements.btnCopyCurl.textContent = '📋 Copy cURL', 1500);
    });
  }

  // --- HTTP Request Tester Logic ---
  let testerViewMode = 'highlighted';
  let lastRawResponseBody = '';

  function parseHeadersInput(text) {
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

  function updateTesterRoute(replace = true) {
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

  function renderTesterResponse(bodyText) {
    lastRawResponseBody = bodyText;
    if (!elements.testerResponseBody) return;

    if (testerViewMode === 'raw') {
      elements.testerResponseBody.textContent = bodyText;
    } else {
      elements.testerResponseBody.innerHTML = highlightJson(bodyText);
    }
  }

  async function executeTesterRequest() {
    if (!elements.testerUrl || !elements.testerMethod) return;
    const method = elements.testerMethod.value;
    let url = elements.testerUrl.value.trim();
    if (!url.startsWith('/') && !url.startsWith('http')) {
      url = '/' + url;
    }
    const headers = parseHeadersInput(elements.testerHeaders ? elements.testerHeaders.value : '');
    const bodyText = elements.testerBody ? elements.testerBody.value.trim() : '';

    updateTesterRoute(false);

    if (elements.btnTesterSend) {
      elements.btnTesterSend.disabled = true;
      elements.btnTesterSend.textContent = '⏳ Sending...';
    }
    if (elements.testerResponseStatus) {
      elements.testerResponseStatus.textContent = '...';
      elements.testerResponseStatus.className = 'card-value';
    }
    if (elements.testerResponseTime) {
      elements.testerResponseTime.textContent = 'measuring...';
    }

    const startTime = performance.now();
    try {
      const fetchOptions = {
        method,
        headers
      };
      if (bodyText && ['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) {
        fetchOptions.body = bodyText;
      }

      const res = await fetch(url, fetchOptions);
      const elapsed = Math.round(performance.now() - startTime);

      if (elements.testerResponseStatus) {
        elements.testerResponseStatus.textContent = `${res.status} ${res.statusText || ''}`.trim();
        elements.testerResponseStatus.className = `card-value status-${res.status >= 500 ? '500' : (res.status >= 400 ? '400' : '200')}`;
      }
      if (elements.testerResponseTime) {
        elements.testerResponseTime.textContent = `${elapsed} ms`;
      }

      const headerLines = [];
      res.headers.forEach((val, key) => {
        headerLines.push(`${key}: ${val}`);
      });
      if (elements.testerResponseHeaderCount) {
        elements.testerResponseHeaderCount.textContent = headerLines.length;
      }
      if (elements.testerResponseHeaders) {
        elements.testerResponseHeaders.textContent = headerLines.join('\n') || '(no headers)';
      }

      const body = await res.text();
      renderTesterResponse(body);

      // Silently refresh journal so the user sees the new entry if they switch tabs
      loadJournal();
    } catch (err) {
      const elapsed = Math.round(performance.now() - startTime);
      if (elements.testerResponseStatus) {
        elements.testerResponseStatus.textContent = 'ERROR';
        elements.testerResponseStatus.className = 'card-value status-500';
      }
      if (elements.testerResponseTime) {
        elements.testerResponseTime.textContent = `${elapsed} ms`;
      }
      if (elements.testerResponseBody) {
        elements.testerResponseBody.textContent = `Fetch error: ${err.message}`;
      }
    } finally {
      if (elements.btnTesterSend) {
        elements.btnTesterSend.disabled = false;
        elements.btnTesterSend.textContent = '🚀 Send';
      }
    }
  }

  // --- Tester Event Listeners ---
  if (elements.btnTesterSend) {
    elements.btnTesterSend.addEventListener('click', executeTesterRequest);
  }

  document.getElementById('tab-tester')?.addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') {
      e.preventDefault();
      executeTesterRequest();
    }
  });

  if (elements.btnTesterFormatJson) {
    elements.btnTesterFormatJson.addEventListener('click', () => {
      if (!elements.testerBody || !elements.testerBody.value.trim()) return;
      try {
        const parsed = JSON.parse(elements.testerBody.value);
        elements.testerBody.value = JSON.stringify(parsed, null, 2);
      } catch (err) {
        alert('Invalid JSON: ' + err.message);
      }
    });
  }

  if (elements.btnTesterCopyCurl) {
    elements.btnTesterCopyCurl.addEventListener('click', () => {
      const method = elements.testerMethod?.value || 'GET';
      const path = elements.testerUrl?.value || '/';
      const headers = parseHeadersInput(elements.testerHeaders?.value || '');
      const body = elements.testerBody?.value || '';
      const cmd = generateCurl(method, path, headers, body);
      navigator.clipboard.writeText(cmd);
      elements.btnTesterCopyCurl.textContent = '✅ Copied!';
      setTimeout(() => elements.btnTesterCopyCurl.textContent = '📋 Copy cURL', 1500);
    });
  }

  if (elements.btnTesterShareLink) {
    elements.btnTesterShareLink.addEventListener('click', () => {
      updateTesterRoute(false);
      navigator.clipboard.writeText(window.location.href);
      elements.btnTesterShareLink.textContent = '✅ Link Copied!';
      setTimeout(() => elements.btnTesterShareLink.textContent = '🔗 Copy Share Link', 1500);
    });
  }

  if (elements.btnViewHighlighted) {
    elements.btnViewHighlighted.addEventListener('click', () => {
      testerViewMode = 'highlighted';
      elements.btnViewHighlighted.classList.add('active');
      elements.btnViewRaw?.classList.remove('active');
      renderTesterResponse(lastRawResponseBody);
    });
  }

  if (elements.btnViewRaw) {
    elements.btnViewRaw.addEventListener('click', () => {
      testerViewMode = 'raw';
      elements.btnViewRaw.classList.add('active');
      elements.btnViewHighlighted?.classList.remove('active');
      renderTesterResponse(lastRawResponseBody);
    });
  }

  if (elements.btnTesterCopyResponse) {
    elements.btnTesterCopyResponse.addEventListener('click', () => {
      if (lastRawResponseBody) {
        navigator.clipboard.writeText(lastRawResponseBody);
        elements.btnTesterCopyResponse.textContent = '✅ Copied!';
        setTimeout(() => elements.btnTesterCopyResponse.textContent = '📋 Copy', 1500);
      }
    });
  }

  [elements.testerMethod, elements.testerUrl, elements.testerHeaders, elements.testerBody].forEach(el => {
    el?.addEventListener('change', () => updateTesterRoute(true));
  });

  if (elements.btnTestStub) {
    elements.btnTestStub.addEventListener('click', () => {
      if (!selectedStubId) return;
      const stub = currentStubs.find(s => s.id === selectedStubId);
      if (!stub) return;

      const method = stub.request?.method || 'GET';
      const path = getStubUrl(stub.request);
      const headerLines = [];
      if (stub.request?.headers) {
        for (const [k, v] of Object.entries(stub.request.headers)) {
          headerLines.push(`${k}: ${v.equalTo || v.matches || v.contains || Object.values(v)[0] || ''}`);
        }
      }
      let body = '';
      if (stub.request?.bodyPatterns && stub.request.bodyPatterns.length > 0) {
        const bp = stub.request.bodyPatterns[0];
        body = bp.equalToJson ? JSON.stringify(bp.equalToJson, null, 2) : (bp.equalTo || '');
      }

      if (elements.testerMethod) elements.testerMethod.value = method === 'ANY' ? 'GET' : method;
      if (elements.testerUrl) elements.testerUrl.value = path;
      if (elements.testerHeaders) elements.testerHeaders.value = headerLines.join('\n');
      if (elements.testerBody) elements.testerBody.value = body;

      activateTab('tab-tester', false);
      setRoute('tester', {
        m: method === 'ANY' ? 'GET' : method,
        p: path,
        h: headerLines.length ? toBase64(headerLines.join('\n')) : null,
        b: body ? toBase64(body) : null
      }, false);
    });
  }

  elements.tabs.forEach(tab => {
    tab.addEventListener('click', () => {
      activateTab(tab.dataset.tab, true);
    });
  });

  window.addEventListener('hashchange', applyRouteFromUrl);

  // Initial Boot
  document.addEventListener('DOMContentLoaded', loadData);
})();
