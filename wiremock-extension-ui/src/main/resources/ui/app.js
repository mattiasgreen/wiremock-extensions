// Pure Vanilla JS for WireMock Stub Viewer
(function () {
  'use strict';

  let currentStubs = [];
  let selectedStubId = null;
  let currentRequests = [];

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
    scenariosContainer: document.getElementById('scenarios-container'),
    btnRefresh: document.getElementById('btn-refresh'),
    btnResetJournal: document.getElementById('btn-reset-journal'),
    btnCopyJson: document.getElementById('btn-copy-json'),
    filterUnmatchedOnly: document.getElementById('filter-unmatched-only'),
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

      card.addEventListener('click', () => selectStub(stub));
      elements.stubList.appendChild(card);
    });

    // Keep active selected stub updated
    if (selectedStubId) {
      const selected = currentStubs.find(s => s.id === selectedStubId);
      if (selected) selectStub(selected);
    }
  }

  function selectStub(stub) {
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

    elements.stubJsonViewer.textContent = JSON.stringify(stub, null, 2);
  }

  function renderJournal() {
    const unmatchedOnly = elements.filterUnmatchedOnly.checked;
    const filtered = currentRequests.filter(r => !unmatchedOnly || !r.wasMatched);

    if (filtered.length === 0) {
      elements.journalList.innerHTML = '<tr><td colspan="6" class="text-center">No requests found.</td></tr>';
      return;
    }

    elements.journalList.innerHTML = filtered.map(req => {
      const time = req.request?.loggedDate ? new Date(req.request.loggedDate).toLocaleTimeString() : '-';
      const method = req.request?.method || '-';
      const url = req.request?.url || '-';
      const status = req.response?.status || 404;
      const matched = req.wasMatched;
      const duration = req.timing?.totalTime ? `${req.timing.totalTime}ms` : '-';

      return `
        <tr>
          <td>${time}</td>
          <td><span class="http-badge badge-${method}">${method}</span></td>
          <td title="${url}">${url}</td>
          <td>${status}</td>
          <td class="${matched ? 'pill-matched' : 'pill-unmatched'}">${matched ? 'MATCHED' : 'UNMATCHED'}</td>
          <td>${duration}</td>
        </tr>
      `;
    }).join('');
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
  elements.searchBox.addEventListener('input', renderStubList);
  elements.btnRefresh.addEventListener('click', loadData);
  elements.filterUnmatchedOnly.addEventListener('change', renderJournal);

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

  elements.tabs.forEach(tab => {
    tab.addEventListener('click', () => {
      elements.tabs.forEach(t => t.classList.remove('active'));
      document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));

      tab.classList.add('active');
      const targetId = tab.dataset.tab;
      document.getElementById(targetId)?.classList.add('active');
    });
  });

  // Initial Boot
  document.addEventListener('DOMContentLoaded', loadData);
})();
