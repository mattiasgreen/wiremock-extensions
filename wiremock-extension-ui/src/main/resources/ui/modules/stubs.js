/**
 * Stubs master-detail viewer, filtering, project grouping, and cURL generator.
 * Handles search filtering, detail card rendering, and transfer to HTTP tester.
 */
import { state } from './state.js';
import { elements, escapeHtml } from './dom.js';
import { highlightJson } from './highlighter.js';
import { setRoute, toBase64, activateTab } from './router.js';
import { getStubProject } from './projects.js';
import { handleToggleStub, handleDeleteStub, openStubEditor } from './lifecycle.js';
import { handleStubCheckboxClick, updateBulkToolbar } from './bulk-actions.js';

export function getStubUrl(req) {
  if (!req) return '/';
  return (
    req.url ||
    req.urlPath ||
    req.urlPathTemplate ||
    req.urlPattern ||
    req.urlPathPattern ||
    req.urlPathMatching ||
    '/'
  );
}

export function generateCurl(method, path, headers = {}, body = '') {
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

export function renderStubList() {
  const filter = (elements.searchBox && elements.searchBox.value || '').toLowerCase().trim();
  const allStubs = [...state.currentStubs, ...state.disabledStubs];

  const filtered = allStubs.filter(s => {
    const isDisabled = state.disabledStubs.some(d => d.id === s.id);
    if (state.statusFilter === 'active' && isDisabled) return false;
    if (state.statusFilter === 'disabled' && !isDisabled) return false;

    if (state.selectedProject) {
      const proj = getStubProject(s);
      if (proj !== state.selectedProject) return false;
    }

    if (!filter) return true;
    const method = (s.request && s.request.method ? s.request.method : 'ANY').toLowerCase();
    const url = getStubUrl(s.request).toLowerCase();
    const name = (s.name || '').toLowerCase();
    const tags = (s.metadata && Array.isArray(s.metadata.tags)) ? s.metadata.tags.join(' ').toLowerCase() : '';
    const proj = getStubProject(s).toLowerCase();
    return method.includes(filter) || url.includes(filter) || name.includes(filter) || tags.includes(filter) || proj.includes(filter);
  });

  if (filtered.length === 0) {
    elements.stubList.innerHTML = '<div class="empty-state">No matching stubs found.</div>';
    return;
  }

  elements.stubList.innerHTML = '';
  filtered.forEach(stub => {
    const isDisabled = state.disabledStubs.some(d => d.id === stub.id);
    const isSelected = stub.id === state.selectedStubId;
    const isChecked = state.selectedStubIds.has(stub.id);

    const card = document.createElement('div');
    card.className = `stub-card ${isSelected ? 'selected' : ''} ${isDisabled ? 'stub-disabled' : ''}`;
    card.setAttribute('data-stub-id', stub.id);

    const method = (stub.request && stub.request.method) || 'ANY';
    const url = getStubUrl(stub.request);
    const name = stub.name || (stub.response && stub.response.status ? `Status ${stub.response.status}` : 'Unnamed');
    const project = getStubProject(stub);

    card.innerHTML = `
      <div class="stub-card-select">
        <input type="checkbox" class="stub-card-chk" data-stub-id="${stub.id}" ${isChecked ? 'checked' : ''} title="Select stub">
      </div>
      <div class="stub-card-content">
        <div class="stub-card-top">
          <span class="http-badge badge-${method}">${method}</span>
          <span class="stub-card-url" title="${escapeHtml(url)}">${escapeHtml(url)}</span>
          ${isDisabled ? '<span class="status-badge badge-disabled">DISABLED</span>' : ''}
        </div>
        <div class="stub-card-bottom">
          <span class="stub-card-name" title="${escapeHtml(name)}">${escapeHtml(name)}</span>
          <span class="project-pill" title="Project: ${escapeHtml(project)}">📁 ${escapeHtml(project)}</span>
          <span class="stub-status-pill">Status: ${(stub.response && stub.response.status) || 200}</span>
        </div>
      </div>
      <div class="stub-card-actions">
        <button class="btn-card-toggle ${isDisabled ? 'off' : 'on'}" data-stub-id="${stub.id}" title="${isDisabled ? 'Enable stub' : 'Disable stub'}">
          ${isDisabled ? '⏸️ Off' : '🟢 On'}
        </button>
        <button class="btn-card-delete" data-stub-id="${stub.id}" title="Delete stub">🗑️</button>
      </div>
    `;

    // Click on card selects it (unless clicking checkbox or action buttons)
    card.addEventListener('click', (e) => {
      if (e.target.closest('.stub-card-select') || e.target.closest('.stub-card-actions')) {
        return;
      }
      selectStub(stub, true);
    });

    // Checkbox toggle
    const chk = card.querySelector('.stub-card-chk');
    if (chk) {
      chk.addEventListener('change', (e) => {
        e.stopPropagation();
        handleStubCheckboxClick(stub.id, chk.checked);
      });
    }

    // Quick toggle button
    const btnToggle = card.querySelector('.btn-card-toggle');
    if (btnToggle) {
      btnToggle.addEventListener('click', (e) => {
        e.stopPropagation();
        handleToggleStub(stub.id);
      });
    }

    // Quick delete button
    const btnDelete = card.querySelector('.btn-card-delete');
    if (btnDelete) {
      btnDelete.addEventListener('click', (e) => {
        e.stopPropagation();
        handleDeleteStub(stub.id);
      });
    }

    elements.stubList.appendChild(card);
  });

  if (state.selectedStubId) {
    const selected = allStubs.find(s => s.id === state.selectedStubId);
    if (selected) selectStub(selected, false);
  }
}

export function selectStub(stub, updateRoute = true) {
  state.selectedStubId = stub.id;
  const isDisabled = state.disabledStubs.some(d => d.id === stub.id);

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

  // Project card
  const detailProject = document.getElementById('detail-project');
  if (detailProject) {
    const proj = getStubProject(stub);
    detailProject.textContent = proj;
  }

  // Lifecycle state card
  const detailState = document.getElementById('detail-lifecycle-state');
  if (detailState) {
    detailState.textContent = isDisabled ? 'DISABLED' : 'ACTIVE';
    detailState.className = `card-value ${isDisabled ? 'text-warning' : 'text-success'}`;
  }

  // Toggle button label
  const btnToggle = document.getElementById('btn-toggle-stub');
  if (btnToggle) {
    btnToggle.textContent = isDisabled ? '🟢 Enable Stub' : '⏸️ Disable Stub';
    btnToggle.className = `btn btn-small ${isDisabled ? 'btn-success' : 'btn-warning'}`;
  }

  elements.stubJsonViewer.innerHTML = highlightJson(stub);

  if (updateRoute) {
    setRoute('stubs', { stubId: stub.id });
  }
}

export function sendStubToTester(stub) {
  if (!stub) return;
  const method = (stub.request && stub.request.method) || 'GET';
  const example = stub.metadata && stub.metadata.exampleRequest;

  let path = example && example.path ? example.path : getStubUrl(stub.request);
  const headerLines = [];

  if (example && example.headers && Object.keys(example.headers).length > 0) {
    for (const [k, v] of Object.entries(example.headers)) {
      headerLines.push(`${k}: ${v}`);
    }
  } else if (stub.request && stub.request.headers) {
    for (const [k, v] of Object.entries(stub.request.headers)) {
      headerLines.push(`${k}: ${v.equalTo || v.matches || v.contains || Object.values(v)[0] || ''}`);
    }
  }

  let body = '';
  if (example && example.body) {
    body = example.body;
  } else if (stub.request && stub.request.bodyPatterns && stub.request.bodyPatterns.length > 0) {
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
}
