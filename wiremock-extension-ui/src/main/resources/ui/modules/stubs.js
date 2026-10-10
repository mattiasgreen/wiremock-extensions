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
import { normalizeStatefulModelsToStubs } from './dynamic-stubs.js';

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
  const origin = state.apiBaseUrl || (typeof window !== 'undefined' ? window.location.origin : '') || 'http://localhost:8080';
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
  const dynamicStubs = normalizeStatefulModelsToStubs(state.statefulModels || []);
  const allStubs = [...state.currentStubs, ...state.disabledStubs, ...dynamicStubs];

  const filtered = allStubs.filter(s => {
    const isDynamic = !!s.isDynamic;
    const isDisabled = state.disabledStubs.some(d => d.id === s.id);

    if (state.statusFilter === 'active' && (isDisabled || isDynamic)) return false;
    if (state.statusFilter === 'disabled' && (!isDisabled || isDynamic)) return false;
    if (state.statusFilter === 'dynamic' && !isDynamic) return false;

    if (state.selectedProject) {
      const proj = isDynamic ? (s.metadata && s.metadata.project) : getStubProject(s);
      if (proj !== state.selectedProject) return false;
    }

    if (!filter) return true;
    const method = (s.request && s.request.method ? s.request.method : 'ANY').toLowerCase();
    const url = getStubUrl(s.request).toLowerCase();
    const name = (s.name || '').toLowerCase();
    const tags = (s.metadata && Array.isArray(s.metadata.tags)) ? s.metadata.tags.join(' ').toLowerCase() : '';
    const proj = (isDynamic ? (s.metadata && s.metadata.project || '') : getStubProject(s)).toLowerCase();
    const entity = (s.entity || '').toLowerCase();
    return method.includes(filter) || url.includes(filter) || name.includes(filter) || tags.includes(filter) || proj.includes(filter) || entity.includes(filter);
  });

  if (allStubs.length === 0) {
    elements.stubList.innerHTML = `
      <div class="empty-state empty-stubs-container" data-testid="empty-stubs-container">
        <div class="empty-stubs-icon">📋</div>
        <div class="empty-stubs-title">No Stubs Configured</div>
        <div class="empty-stubs-desc">Bootstrap your stubs from an OpenAPI spec or create one from scratch.</div>
        <div class="empty-stubs-actions">
          <button id="btn-empty-import-openapi" class="btn btn-tiny btn-primary" title="Import stubs from OpenAPI spec">📥 Import OpenAPI</button>
          <button id="btn-empty-create-stub" class="btn btn-tiny btn-secondary" title="Create a new stub from scratch">➕ New Stub</button>
        </div>
      </div>
    `;
    const btnEmptyOpenApi = document.getElementById('btn-empty-import-openapi');
    if (btnEmptyOpenApi) {
      btnEmptyOpenApi.addEventListener('click', () => {
        if (elements.btnOpenOpenApiModal) elements.btnOpenOpenApiModal.click();
      });
    }
    const btnEmptyCreate = document.getElementById('btn-empty-create-stub');
    if (btnEmptyCreate) {
      btnEmptyCreate.addEventListener('click', () => {
        openStubEditor(null, false);
      });
    }
    if (elements.stubDetailEmpty) elements.stubDetailEmpty.classList.remove('hidden');
    if (elements.stubDetailEmptyDefault) elements.stubDetailEmptyDefault.classList.add('hidden');
    if (elements.stubDetailEmptyWelcome) elements.stubDetailEmptyWelcome.classList.remove('hidden');
    if (elements.stubDetailView) elements.stubDetailView.classList.add('hidden');
    state.selectedStubId = null;
    return;
  }

  if (filtered.length === 0) {
    elements.stubList.innerHTML = '<div class="empty-state">No matching stubs found.</div>';
    if (elements.stubDetailEmpty) elements.stubDetailEmpty.classList.remove('hidden');
    if (elements.stubDetailEmptyDefault) elements.stubDetailEmptyDefault.classList.remove('hidden');
    if (elements.stubDetailEmptyWelcome) elements.stubDetailEmptyWelcome.classList.add('hidden');
    if (elements.stubDetailView) elements.stubDetailView.classList.add('hidden');
    state.selectedStubId = null;
    return;
  }

  if (elements.stubDetailEmptyDefault) elements.stubDetailEmptyDefault.classList.remove('hidden');
  if (elements.stubDetailEmptyWelcome) elements.stubDetailEmptyWelcome.classList.add('hidden');

  elements.stubList.innerHTML = '';
  filtered.forEach(stub => {
    const isDynamic = !!stub.isDynamic;
    const isDisabled = state.disabledStubs.some(d => d.id === stub.id);
    const isSelected = stub.id === state.selectedStubId;
    const isChecked = state.selectedStubIds.has(stub.id);

    const card = document.createElement('div');
    card.className = `stub-card ${isSelected ? 'selected' : ''} ${isDisabled ? 'stub-disabled' : ''} ${isDynamic ? 'stub-dynamic' : ''}`;
    card.setAttribute('data-stub-id', stub.id);
    card.setAttribute('data-testid', 'stub-card');

    const method = (stub.request && stub.request.method) || 'ANY';
    const url = getStubUrl(stub.request);
    const name = stub.name || (stub.response && stub.response.status ? `Status ${stub.response.status}` : 'Unnamed');
    const project = isDynamic ? (stub.metadata && stub.metadata.project) : getStubProject(stub);
    const isProxy = (stub.response && !!stub.response.proxyBaseUrl) || (stub.metadata && stub.metadata.mode === 'proxy');
    const statusCode = isProxy ? 'PROXY' : ((stub.response && stub.response.status) || 200);
    const statusCls = isProxy ? 'status-proxy' : (statusCode >= 500 ? 'status-500' : (statusCode >= 400 ? 'status-400' : 'status-200'));

    card.innerHTML = `
      <div class="stub-card-select">
        <input type="checkbox" class="stub-card-chk" data-testid="stub-card-chk" data-stub-id="${stub.id}" ${isChecked ? 'checked' : ''} title="Select stub">
      </div>
      <div class="stub-card-content">
        <div class="stub-card-top">
          <span class="http-badge badge-${method}">${method}</span>
          <span class="stub-card-url" title="${escapeHtml(url)}">${escapeHtml(url)}</span>
          <span class="stub-status-pill ${statusCls}">${statusCode}</span>
          ${isDynamic ? '<span class="status-badge badge-dynamic">⚡ DYNAMIC</span>' : ''}
          ${isDisabled ? '<span class="status-badge badge-disabled">DISABLED</span>' : ''}
        </div>
        <div class="stub-card-bottom">
          <span class="stub-card-name" title="${escapeHtml(name)}">${escapeHtml(name)}</span>
          <span class="project-pill" title="Coverage: ${escapeHtml(stub.urlRange || project)}">📦 ${escapeHtml(stub.urlRange || project)}</span>
        </div>
      </div>
    `;

    // Click on card selects it (unless clicking checkbox)
    card.addEventListener('click', (e) => {
      if (e.target.closest('.stub-card-select')) {
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

    elements.stubList.appendChild(card);
  });

  elements.stubList.setAttribute('data-state', 'ready');

  if (state.selectedStubId) {
    const selected = allStubs.find(s => s.id === state.selectedStubId);
    if (selected) {
      selectStub(selected, false);
    } else {
      state.selectedStubId = null;
      if (elements.stubDetailEmpty) elements.stubDetailEmpty.classList.remove('hidden');
      if (elements.stubDetailView) elements.stubDetailView.classList.add('hidden');
    }
  }
}

export function selectStub(stub, updateRoute = true) {
  state.selectedStubId = stub.id;

  document.querySelectorAll('.stub-card').forEach(el => {
    const id = el.getAttribute('data-stub-id') || (el.dataset && el.dataset.stubId);
    if (id === stub.id) {
      el.classList.add('selected');
    } else {
      el.classList.remove('selected');
    }
  });

  if (state.activeTab === 'tab-tester') {
    sendStubToTester(stub, false);
    return;
  }

  const isDynamic = !!stub.isDynamic;
  const isDisabled = state.disabledStubs.some(d => d.id === stub.id);
  elements.stubDetailEmpty.classList.add('hidden');
  elements.stubDetailView.classList.remove('hidden');

  const method = (stub.request && stub.request.method) || 'ANY';
  elements.detailMethod.textContent = method;
  elements.detailMethod.className = `http-badge badge-${method}`;
  elements.detailUrl.textContent = getStubUrl(stub.request);
  elements.detailName.textContent = stub.name ? `— ${stub.name}` : '';

  const isProxy = (stub.response && !!stub.response.proxyBaseUrl) || (stub.metadata && stub.metadata.mode === 'proxy');
  const status = isProxy ? 'PROXY' : ((stub.response && stub.response.status) || 200);
  elements.detailStatus.textContent = status;
  elements.detailStatus.className = `card-value ${isProxy ? 'status-proxy' : (status >= 500 ? 'status-500' : (status >= 400 ? 'status-400' : 'status-200'))}`;

  elements.detailPriority.textContent = stub.priority || (isDynamic ? 1 : 5);
  elements.detailScenario.textContent = isDynamic
    ? (stub.invariants && stub.invariants.length > 0 ? `${stub.invariants.length} Invariant Guard(s)` : 'Stateful Relational FSM')
    : (stub.scenarioName
      ? `${stub.scenarioName} [${stub.requiredScenarioState || 'Start'} -> ${stub.newScenarioState || 'Same'}]`
      : 'None');

  // Project card
  const detailProject = document.getElementById('detail-project');
  if (detailProject) {
    const proj = isDynamic ? (stub.metadata && stub.metadata.project) : getStubProject(stub);
    detailProject.textContent = proj;
  }

  // Lifecycle state card
  const detailState = document.getElementById('detail-lifecycle-state');
  if (detailState) {
    if (isDynamic) {
      detailState.textContent = 'DYNAMIC SIMULATION';
      detailState.className = 'card-value text-dynamic';
    } else {
      detailState.textContent = isDisabled ? 'DISABLED' : 'ACTIVE';
      detailState.className = `card-value ${isDisabled ? 'text-warning' : 'text-success'}`;
    }
  }

  // Toggle button label
  const btnToggle = document.getElementById('btn-toggle-stub');
  if (btnToggle) {
    if (isDynamic) {
      btnToggle.classList.add('hidden');
    } else {
      btnToggle.classList.remove('hidden');
      btnToggle.textContent = isDisabled ? '🟢 Enable Stub' : '⏸️ Disable Stub';
      btnToggle.className = `btn btn-small ${isDisabled ? 'btn-success' : 'btn-warning'}`;
    }
  }

  // Edit / Clone buttons
  const btnEdit = document.getElementById('btn-edit-stub');
  const btnClone = document.getElementById('btn-clone-stub');
  if (btnEdit) btnEdit.style.display = isDynamic ? 'none' : '';
  if (btnClone) btnClone.style.display = isDynamic ? 'none' : '';

  // Dynamic / Stateful Specification section
  const statefulSection = elements.detailStatefulSection || document.getElementById('detail-stateful-section');
  if (statefulSection) {
    if (isDynamic) {
      statefulSection.classList.remove('hidden');
      const entityLabel = document.getElementById('stateful-entity-label');
      const scopeLabel = document.getElementById('stateful-route-scope');
      const invCount = document.getElementById('stateful-invariants-count');
      const invList = document.getElementById('stateful-invariants-list');
      const mutCount = document.getElementById('stateful-mutations-count');
      const mutList = document.getElementById('stateful-mutations-list');

      if (entityLabel) entityLabel.textContent = stub.entity || 'entity';
      if (scopeLabel) scopeLabel.textContent = stub.urlRange || stub.request.urlPathTemplate;
      if (invCount) invCount.textContent = stub.invariants ? stub.invariants.length : 0;
      if (mutCount) mutCount.textContent = stub.mutations ? stub.mutations.length : 0;

      if (invList) {
        if (!stub.invariants || stub.invariants.length === 0) {
          invList.innerHTML = '<span class="muted-text">No invariant guards (always accepted)</span>';
        } else {
          invList.innerHTML = stub.invariants.map(inv => {
            const guardTitle = inv.name || inv.failMessage || 'Invariant Guard';
            const rejectCode = inv.failStatus || inv.rejectStatusCode || 400;
            return `
            <div class="stateful-rule-item">
              <div><strong>Guard:</strong> ${escapeHtml(guardTitle)}</div>
              <div class="muted-text" style="font-size: 0.75rem;">Condition: <code>${escapeHtml(JSON.stringify(inv.condition))}</code> &rarr; Reject HTTP ${rejectCode}</div>
            </div>
          `;
          }).join('');
        }
      }

      if (mutList) {
        if (!stub.mutations || stub.mutations.length === 0) {
          mutList.innerHTML = '<span class="muted-text">Read-only (no state mutations)</span>';
        } else {
          mutList.innerHTML = stub.mutations.map(m => `
            <div class="stateful-rule-item">
              <div><strong>Action:</strong> <code>${escapeHtml(m.type)}</code></div>
              <div class="muted-text" style="font-size: 0.75rem;">${escapeHtml(JSON.stringify(m))}</div>
            </div>
          `).join('');
        }
      }
    } else {
      statefulSection.classList.add('hidden');
    }
  }

  elements.stubJsonViewer.innerHTML = highlightJson(isDynamic ? (stub.rule || stub) : stub);

  if (updateRoute) {
    setRoute('stubs', { stubId: stub.id });
  }
}

export function sendStubToTester(stub, switchTab = true) {
  if (!stub) return;
  state.selectedStubId = stub.id;

  document.querySelectorAll('.stub-card').forEach(el => {
    const id = el.getAttribute('data-stub-id') || (el.dataset && el.dataset.stubId);
    if (id === stub.id) {
      el.classList.add('selected');
    } else {
      el.classList.remove('selected');
    }
  });

  const method = (stub.request && stub.request.method) || 'GET';
  const example = stub.metadata && stub.metadata.exampleRequest;

  let path = example && example.path ? example.path : getStubUrl(stub.request);
  if (stub.isDynamic) {
    const idPrefix = (stub.model && stub.model.idPrefix) || (stub.entity ? stub.entity + '-' : 'item-');
    path = path.replace(/\{([^}]+)\}/g, () => `${idPrefix}100`);
  }

  const headerLines = [];

  if (stub.isDynamic) {
    headerLines.push('X-Correlation-Id: test-scenario-1');
    headerLines.push('Content-Type: application/json');
  }

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
  if (stub.isDynamic && ['POST', 'PUT', 'PATCH'].includes(method)) {
    if (path.includes('/tasks')) {
      body = JSON.stringify({ title: 'Verification Task', status: 'PENDING' }, null, 2);
    } else if (path.includes('/items')) {
      body = JSON.stringify({ sku: 'WIDGET-01', quantity: 2 }, null, 2);
    } else if (path.includes('/close') || path.includes('/ship')) {
      body = '{\n  \n}';
    } else {
      body = JSON.stringify({ title: 'Sample ' + (stub.entity || 'Item'), status: 'OPEN' }, null, 2);
    }
  } else if (example && example.body) {
    body = typeof example.body === 'object' ? JSON.stringify(example.body, null, 2) : String(example.body);
  } else if (stub.request && stub.request.bodyPatterns && stub.request.bodyPatterns.length > 0) {
    const bp = stub.request.bodyPatterns[0];
    if (bp.equalToJson) {
      body = typeof bp.equalToJson === 'string' ? bp.equalToJson : JSON.stringify(bp.equalToJson, null, 2);
    } else if (bp.equalTo) {
      body = bp.equalTo;
    } else if (bp.matchesJsonPath) {
      const match = bp.matchesJsonPath.match(/\$\.([a-zA-Z0-9_-]+)/);
      if (match && match[1]) {
        body = JSON.stringify({ [match[1]]: "sample_value" }, null, 2);
      } else {
        body = '{\n  \n}';
      }
    } else if (bp.contains) {
      body = bp.contains;
    }
  } else if (['POST', 'PUT', 'PATCH'].includes(method)) {
    body = '{\n  \n}';
  }

  if (elements.testerMethod) elements.testerMethod.value = method === 'ANY' ? 'GET' : method;
  if (elements.testerUrl) elements.testerUrl.value = path;
  if (elements.testerHeaders) elements.testerHeaders.value = headerLines.join('\n');
  if (elements.testerBody) elements.testerBody.value = body;

  state.contextStub = stub;
  if (elements.testerContextBanner) {
    elements.testerContextBanner.classList.remove('hidden');
    if (elements.testerContextTitle) {
      const stubDesc = stub.name ? `"${stub.name}" (${method} ${path})` : `${method} ${path}`;
      elements.testerContextTitle.textContent = `Pre-filled from stub: ${stubDesc}`;
    }
  }

  if (switchTab) {
    activateTab('tab-tester', false);
  }
  setRoute('tester', {
    m: method === 'ANY' ? 'GET' : method,
    p: path,
    h: headerLines.length ? toBase64(headerLines.join('\n')) : null,
    b: body ? toBase64(body) : null
  }, false);
}
