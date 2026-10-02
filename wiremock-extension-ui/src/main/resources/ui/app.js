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
    elements.tabs.forEach(t => {
      const tId = t.getAttribute('data-tab') || (t.dataset && t.dataset.tab);
      if (tId === tabId) {
        t.classList.add('active');
      } else {
        t.classList.remove('active');
      }
    });
    document.querySelectorAll('.tab-content').forEach(c => {
      if (c.id === tabId) {
        c.classList.add('active');
      } else {
        c.classList.remove('active');
      }
    });
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
    btnTesterResetForm: document.getElementById('btn-tester-reset-form'),
    testerResponseStatus: document.getElementById('tester-response-status'),
    testerResponseTime: document.getElementById('tester-response-time'),
    testerResponseSize: document.getElementById('tester-response-size'),
    testerResponseHeaders: document.getElementById('tester-response-headers'),
    testerResponseHeaderCount: document.getElementById('tester-response-header-count'),
    testerResponseBody: document.getElementById('tester-response-body'),
    btnViewHighlighted: document.getElementById('btn-view-highlighted'),
    btnViewRaw: document.getElementById('btn-view-raw'),
    btnTesterCopyResponse: document.getElementById('btn-tester-copy-response'),
    testerHistoryList: document.getElementById('tester-history-list'),
    testerHistoryCount: document.getElementById('tester-history-count'),
    btnTesterClearHistory: document.getElementById('btn-tester-clear-history'),
    btnPresetJson: document.getElementById('btn-preset-json'),
    btnPresetBearer: document.getElementById('btn-preset-bearer'),
    btnPresetAccept: document.getElementById('btn-preset-accept'),
    btnTesterTabResponse: document.getElementById('btn-tester-tab-response'),
    btnTesterTabRequest: document.getElementById('btn-tester-tab-request'),
    btnTesterTabBoth: document.getElementById('btn-tester-tab-both'),
    testerViewResponse: document.getElementById('tester-view-response'),
    testerViewRequest: document.getElementById('tester-view-request'),
    testerSentMethod: document.getElementById('tester-sent-method'),
    testerSentUrl: document.getElementById('tester-sent-url'),
    testerSentHeaders: document.getElementById('tester-sent-headers'),
    testerSentBody: document.getElementById('tester-sent-body'),
    tabs: document.querySelectorAll('.tab')
  };

  function doFetch(url, options = {}) {
    if (typeof fetch !== 'undefined') {
      return fetch(url, options);
    }
    return new Promise((resolve, reject) => {
      try {
        const xhr = new XMLHttpRequest();
        xhr.open(options.method || 'GET', url);
        if (options.headers) {
          Object.entries(options.headers).forEach(([k, v]) => xhr.setRequestHeader(k, v));
        }
        xhr.onload = () => {
          const headers = {
            forEach(cb) {
              const raw = xhr.getAllResponseHeaders() || '';
              raw.trim().split(/[\r\n]+/).forEach(line => {
                const parts = line.split(': ');
                if (parts.length >= 2) cb(parts.slice(1).join(': '), parts[0]);
              });
            }
          };
          resolve({
            status: xhr.status,
            statusText: xhr.statusText,
            headers: headers,
            text: () => Promise.resolve(xhr.responseText),
            json: () => Promise.resolve(JSON.parse(xhr.responseText)),
            ok: xhr.status >= 200 && xhr.status < 300
          });
        };
        xhr.onerror = () => reject(new Error('Network error from ' + url));
        xhr.send(options.body || null);
      } catch (err) {
        reject(err);
      }
    });
  }

  function apiGet(endpoint) {
    return doFetch(endpoint, { method: 'GET' }).then(res => {
      if (!res.ok) throw new Error(`HTTP ${res.status} from ${endpoint}`);
      return res.json();
    });
  }

  function loadData() {
    loadTesterHistoryFromStorage();
    return Promise.all([loadMappings(), loadJournal(), loadScenarios()])
      .then(() => {
        applyRouteFromUrl();
      })
      .catch(err => {
        console.error('Error refreshing data', err);
      });
  }

  function loadMappings() {
    return apiGet('/__admin/mappings')
      .then(data => {
        currentStubs = data.mappings || [];
        renderStubList();
        if (elements.statStubs) elements.statStubs.textContent = currentStubs.length;
        if (elements.stubsCountLabel) elements.stubsCountLabel.textContent = `${currentStubs.length} mappings`;
      })
      .catch(err => {
        if (elements.stubList) {
          elements.stubList.innerHTML = `<div class="empty-state text-error">Failed to load stubs: ${err.message}</div>`;
        }
      });
  }

  function loadJournal() {
    return apiGet('/__admin/requests?limit=100')
      .then(data => {
        currentRequests = data.requests || [];
        if (elements.statRequests) elements.statRequests.textContent = currentRequests.length;
        if (elements.journalCount) elements.journalCount.textContent = currentRequests.length;
        renderJournal();
      })
      .catch(err => {
        console.warn('Could not load journal', err);
      });
  }

  function loadScenarios() {
    return apiGet('/__admin/scenarios')
      .then(data => {
        const scenarios = data.scenarios || [];
        if (elements.statScenarios) elements.statScenarios.textContent = scenarios.length;
        renderScenarios(scenarios);
      })
      .catch(err => {
        console.warn('Could not load scenarios', err);
      });
  }

  function getStubUrl(req) {
    if (!req) return '/';
    return req.url || req.urlPath || req.urlPattern || req.urlPathPattern || '/';
  }

  function renderStubList() {
    const filter = (elements.searchBox.value || '').toLowerCase().trim();
    const filtered = currentStubs.filter(s => {
      if (!filter) return true;
      const method = (s.request && s.request.method ? s.request.method : 'ANY').toLowerCase();
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
      card.setAttribute('data-stub-id', stub.id);

      const method = (stub.request && stub.request.method) || 'ANY';
      const url = getStubUrl(stub.request);
      const name = stub.name || (stub.response && stub.response.status ? `Status ${stub.response.status}` : 'Unnamed');

      card.innerHTML = `
        <div class="stub-card-top">
          <span class="http-badge badge-${method}">${method}</span>
          <span class="stub-card-url" title="${url}">${url}</span>
        </div>
        <div class="stub-card-bottom">
          <span>${name}</span>
          <span>Status: ${(stub.response && stub.response.status) || 200}</span>
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
      const id = el.getAttribute('data-stub-id') || (el.dataset && el.dataset.stubId);
      if (id === stub.id) {
        el.classList.add('selected');
      } else {
        el.classList.remove('selected');
      }
    });

    elements.stubDetailEmpty.classList.add('hidden');
    elements.stubDetailView.classList.remove('hidden');

    const method = (stub.request && stub.request.method) || 'ANY';
    elements.detailMethod.textContent = method;
    elements.detailMethod.className = `http-badge badge-${method}`;
    elements.detailUrl.textContent = getStubUrl(stub.request);
    elements.detailName.textContent = stub.name ? `— ${stub.name}` : '';

    const status = (stub.response && stub.response.status) || 200;
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
      const method = (r.request && r.request.method ? r.request.method : '').toLowerCase();
      const url = (r.request && r.request.url ? r.request.url : '').toLowerCase();
      const status = String((r.response && r.response.status) || (r.responseDefinition && r.responseDefinition.status) || '');
      const stubName = (r.stubMapping && r.stubMapping.name ? r.stubMapping.name : '').toLowerCase();
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
      const time = req.request && req.request.loggedDate ? new Date(req.request.loggedDate).toLocaleTimeString() : '-';
      const method = (req.request && req.request.method) || '-';
      const url = (req.request && req.request.url) || '-';
      const status = (req.response && req.response.status) || (req.responseDefinition && req.responseDefinition.status) || 404;
      const matched = req.wasMatched;
      const duration = (req.timing && req.timing.totalTime !== undefined) ? `${req.timing.totalTime}ms` : '-';

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
        const reqHeadersHtml = highlightJson((req.request && req.request.headers) || {});
        const reqBodyRaw = (req.request && req.request.body) || '';
        const reqBodyHtml = reqBodyRaw ? highlightJson(reqBodyRaw) : '<span style="color: var(--text-muted);">(empty)</span>';

        const resHeaders = (req.response && req.response.headers) || (req.responseDefinition && req.responseDefinition.headers) || {};
        const resHeadersHtml = highlightJson(resHeaders);
        const resBodyRaw = (req.response && req.response.body) || (req.responseDefinition && req.responseDefinition.body) || '';
        const resBodyHtml = resBodyRaw ? highlightJson(resBodyRaw) : '<span style="color: var(--text-muted);">(empty)</span>';
        const stubName = (req.stubMapping && req.stubMapping.name)
          || (req.response && req.response.headers && req.response.headers['Matched-Stub-Name'])
          || (matched ? 'Matched' : 'None (404)');

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
        const reqId = row.getAttribute('data-request-id') || (row.dataset && row.dataset.requestId);
        toggleJournalDetail(reqId, true);
      });
    });

    // Attach copy button listeners
    elements.journalList.querySelectorAll('.btn-copy-req').forEach(btn => {
      btn.addEventListener('click', (e) => {
        e.stopPropagation();
        const targetId = btn.getAttribute('data-id') || (btn.dataset && btn.dataset.id);
        const req = currentRequests.find(r => r.id === targetId);
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
        const targetId = btn.getAttribute('data-id') || (btn.dataset && btn.dataset.id);
        const req = currentRequests.find(r => r.id === targetId);
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

  elements.btnResetJournal.addEventListener('click', () => {
    if (confirm('Are you sure you want to clear the request journal?')) {
      doFetch('/__admin/requests', { method: 'DELETE' })
        .then(() => loadJournal())
        .catch(err => console.warn('Could not reset journal', err));
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
      const method = (stub.request && stub.request.method) || 'GET';
      const path = getStubUrl(stub.request);
      const headers = {};
      if (stub.request && stub.request.headers) {
        for (const [k, v] of Object.entries(stub.request.headers)) {
          headers[k] = v.equalTo || v.matches || v.contains || Object.values(v)[0] || '';
        }
      }
      let body = '';
      if (stub.request && stub.request.bodyPatterns && stub.request.bodyPatterns.length > 0) {
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
  let activeInspectorTab = 'response';
  const TESTER_HISTORY_KEY = 'wiremock_ui_tester_history';
  let testerHistory = [];

  function formatBytes(bytes) {
    if (bytes === 0) return '0 B';
    if (!bytes || isNaN(bytes)) return '';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
  }

  function appendHeader(name, value) {
    if (!elements.testerHeaders) return;
    const current = elements.testerHeaders.value.trim();
    if (current.toLowerCase().includes(name.toLowerCase() + ':')) return;
    elements.testerHeaders.value = (current ? current + '\n' : '') + `${name}: ${value}`;
    updateTesterRoute(true);
  }

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

  function loadTesterHistoryFromStorage() {
    try {
      const saved = localStorage.getItem(TESTER_HISTORY_KEY);
      if (saved) {
        testerHistory = JSON.parse(saved);
      }
    } catch (_) {
      testerHistory = [];
    }
    renderTesterHistory();
  }

  function saveTesterHistoryItem(item) {
    testerHistory.unshift(item);
    if (testerHistory.length > 50) testerHistory.pop();
    try {
      localStorage.setItem(TESTER_HISTORY_KEY, JSON.stringify(testerHistory));
    } catch (_) {}
    renderTesterHistory();
  }

  function clearTesterHistory() {
    testerHistory = [];
    try {
      localStorage.removeItem(TESTER_HISTORY_KEY);
    } catch (_) {}
    renderTesterHistory();
  }

  function renderTesterHistory() {
    if (!elements.testerHistoryList) return;
    if (elements.testerHistoryCount) {
      elements.testerHistoryCount.textContent = testerHistory.length;
    }
    if (testerHistory.length === 0) {
      elements.testerHistoryList.innerHTML = '<div class="empty-state text-small">No requests sent yet.</div>';
      return;
    }

    elements.testerHistoryList.innerHTML = '';
    testerHistory.forEach((item, index) => {
      const card = document.createElement('div');
      card.className = 'history-item';
      card.setAttribute('data-history-idx', index);

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

  function restoreHistoryItem(item) {
    if (elements.testerMethod) elements.testerMethod.value = item.method;
    if (elements.testerUrl) elements.testerUrl.value = item.url;
    if (elements.testerHeaders) elements.testerHeaders.value = item.headersText || '';
    if (elements.testerBody) elements.testerBody.value = item.bodyText || '';

    displaySentRequest(item.method, item.url, item.headersText || '', item.bodyText || '');
    displayResponseResult(item.status, item.statusText || '', item.duration || 0, item.responseHeaders || [], item.responseBody || '');
    updateTesterRoute(true);
  }

  function setInspectorTab(tab) {
    activeInspectorTab = tab;
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

  function displaySentRequest(method, url, headersText, bodyText) {
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

  function displayResponseResult(status, statusText, elapsed, headerLines, responseBody) {
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

  function renderTesterResponse(bodyText) {
    lastRawResponseBody = bodyText;
    if (!elements.testerResponseBody) return;

    if (testerViewMode === 'raw') {
      elements.testerResponseBody.textContent = bodyText;
    } else {
      elements.testerResponseBody.innerHTML = highlightJson(bodyText);
    }
  }

  function executeTesterRequest() {
    if (!elements.testerUrl || !elements.testerMethod) return;
    const method = elements.testerMethod.value;
    let url = elements.testerUrl.value.trim();
    if (!url.startsWith('/') && !url.startsWith('http')) {
      url = '/' + url;
    }
    const headersText = elements.testerHeaders ? elements.testerHeaders.value : '';
    const headers = parseHeadersInput(headersText);
    const bodyText = elements.testerBody ? elements.testerBody.value.trim() : '';

    updateTesterRoute(false);
    displaySentRequest(method, url, headersText, bodyText);

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
    if (elements.testerResponseSize) {
      elements.testerResponseSize.textContent = '—';
    }

    const startTime = performance.now();
    const fetchOptions = {
      method: method,
      headers: headers
    };
    if (bodyText && ['POST', 'PUT', 'PATCH', 'DELETE'].indexOf(method) !== -1) {
      fetchOptions.body = bodyText;
    }

    let responseMeta = { status: 0, statusText: '', headerLines: [] };

    doFetch(url, fetchOptions)
      .then(res => {
        responseMeta.status = res.status;
        responseMeta.statusText = res.statusText || '';
        const headerLines = [];
        if (res.headers && res.headers.forEach) {
          res.headers.forEach((val, key) => {
            headerLines.push(`${key}: ${val}`);
          });
        }
        responseMeta.headerLines = headerLines;
        return res.text();
      })
      .then(body => {
        const elapsed = Math.round(performance.now() - startTime);
        displayResponseResult(responseMeta.status, responseMeta.statusText, elapsed, responseMeta.headerLines, body);

        saveTesterHistoryItem({
          timestamp: Date.now(),
          method: method,
          url: url,
          headersText: headersText,
          bodyText: bodyText,
          status: responseMeta.status,
          statusText: responseMeta.statusText,
          duration: elapsed,
          size: new Blob([body]).size,
          responseHeaders: responseMeta.headerLines,
          responseBody: body
        });

        loadJournal();
      })
      .catch(err => {
        const elapsed = Math.round(performance.now() - startTime);
        displayResponseResult('ERROR', err.message, elapsed, [], 'Fetch error: ' + err.message);

        saveTesterHistoryItem({
          timestamp: Date.now(),
          method: method,
          url: url,
          headersText: headersText,
          bodyText: bodyText,
          status: 0,
          statusText: err.message,
          duration: elapsed,
          size: 0,
          responseHeaders: [],
          responseBody: 'Fetch error: ' + err.message
        });
      })
      .then(() => {
        if (elements.btnTesterSend) {
          elements.btnTesterSend.disabled = false;
          elements.btnTesterSend.textContent = '🚀 Send';
        }
      });
  }

  // --- Tester Event Listeners ---
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
      if (elements.btnViewRaw) elements.btnViewRaw.classList.remove('active');
      renderTesterResponse(lastRawResponseBody);
    });
  }

  if (elements.btnViewRaw) {
    elements.btnViewRaw.addEventListener('click', () => {
      testerViewMode = 'raw';
      elements.btnViewRaw.classList.add('active');
      if (elements.btnViewHighlighted) elements.btnViewHighlighted.classList.remove('active');
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
    if (el) el.addEventListener('change', () => updateTesterRoute(true));
  });

  if (elements.btnTestStub) {
    elements.btnTestStub.addEventListener('click', () => {
      if (!selectedStubId) return;
      const stub = currentStubs.find(s => s.id === selectedStubId);
      if (!stub) return;

      const method = (stub.request && stub.request.method) || 'GET';
      const path = getStubUrl(stub.request);
      const headerLines = [];
      if (stub.request && stub.request.headers) {
        for (const [k, v] of Object.entries(stub.request.headers)) {
          headerLines.push(`${k}: ${v.equalTo || v.matches || v.contains || Object.values(v)[0] || ''}`);
        }
      }
      let body = '';
      if (stub.request && stub.request.bodyPatterns && stub.request.bodyPatterns.length > 0) {
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
      const target = tab.getAttribute('data-tab') || (tab.dataset && tab.dataset.tab);
      activateTab(target, true);
    });
  });

  window.addEventListener('hashchange', applyRouteFromUrl);

  // Initial Boot
  document.addEventListener('DOMContentLoaded', loadData);
})();
