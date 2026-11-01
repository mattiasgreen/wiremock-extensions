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
import { handleStubCheckboxClick, updateBulkToolbar, handleSelectAllToggle } from './bulk-actions.js';
import { normalizeStatefulModelsToStubs } from './dynamic-stubs.js';
import { matchesStub } from './filter.js';
import { normalizeJsonPayload } from './base64.js';
import { exportStubs } from './import-export.js';
import { updateFilterCounts } from './projects.js';
import { loadData } from './api.js';
import { renderJournal } from './journal.js';

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
  const origin = (state.targetConfig && state.targetConfig.fullUrl) ||
    state.apiBaseUrl ||
    (typeof window !== 'undefined' ? window.location.origin : '') ||
    'http://localhost:8080';
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

export function generateCreateStubCurl(stub) {
  if (!stub) return '';
  const origin = (state.targetConfig && state.targetConfig.fullUrl) ||
    state.apiBaseUrl ||
    (typeof window !== 'undefined' ? window.location.origin : '') ||
    'http://localhost:8080';

  const isDynamic = Boolean(stub.isDynamic);
  const endpoint = isDynamic ? `${origin}/__admin/stateful/rules` : `${origin}/__admin/mappings`;
  const payload = isDynamic ? (stub.rule || stub) : stub;
  const jsonStr = JSON.stringify(payload, null, 2);

  return [
    `curl -X POST "${endpoint}" \\`,
    `  -H "Content-Type: application/json" \\`,
    `  --data-binary @- << 'EOF'`,
    jsonStr,
    `EOF`
  ].join('\n');
}

export function generateJavaStubFor(stub) {
  if (!stub) return '';
  const req = stub.request || {};
  const res = stub.response || {};
  const method = (req.method || 'ANY').toLowerCase();
  const wmMethod = ['get', 'post', 'put', 'delete', 'patch', 'head', 'options'].includes(method) ? method : 'any';

  let urlMatcher = `urlEqualTo("${(req.url || '/').replace(/"/g, '\\"')}")`;
  if (req.urlPathTemplate) {
    urlMatcher = `urlPathTemplate("${req.urlPathTemplate.replace(/"/g, '\\"')}")`;
  } else if (req.urlPattern || req.urlPathPattern || req.urlPathMatching) {
    const pat = req.urlPattern || req.urlPathPattern || req.urlPathMatching;
    urlMatcher = `urlMatching("${pat.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}")`;
  } else if (req.urlPath) {
    urlMatcher = `urlPathEqualTo("${req.urlPath.replace(/"/g, '\\"')}")`;
  }

  const lines = [`stubFor(${wmMethod}(${urlMatcher})`];
  if (stub.name) {
    lines.push(`    .withName("${String(stub.name).replace(/"/g, '\\"')}")`);
  }
  if (stub.priority && stub.priority !== 5) {
    lines.push(`    .atPriority(${stub.priority})`);
  }
  if (req.headers) {
    for (const [k, v] of Object.entries(req.headers)) {
      const val = v.equalTo || v.contains || v.matches || '';
      const op = v.contains ? 'containing' : (v.matches ? 'matching' : 'equalTo');
      if (val) {
        lines.push(`    .withHeader("${k.replace(/"/g, '\\"')}", ${op}("${String(val).replace(/"/g, '\\"')}"))`);
      }
    }
  }
  if (req.bodyPatterns && req.bodyPatterns.length > 0) {
    req.bodyPatterns.forEach(bp => {
      if (bp.equalToJson) {
        const jsonStr = typeof bp.equalToJson === 'string' ? bp.equalToJson : JSON.stringify(bp.equalToJson);
        lines.push(`    .withRequestBody(equalToJson("${jsonStr.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}", true, true))`);
      } else if (bp.contains) {
        lines.push(`    .withRequestBody(containing("${String(bp.contains).replace(/"/g, '\\"')}"))`);
      } else if (bp.matchesJsonPath) {
        if (typeof bp.matchesJsonPath === 'object' && bp.matchesJsonPath !== null) {
          const expr = bp.matchesJsonPath.expression || '';
          const sub = bp.matchesJsonPath.equalTo !== undefined ? bp.matchesJsonPath.equalTo : bp.matchesJsonPath.contains;
          if (sub !== undefined) {
            lines.push(`    .withRequestBody(matchingJsonPath("${String(expr).replace(/"/g, '\\"')}", equalTo("${String(sub).replace(/"/g, '\\"')}")))`);
          } else {
            lines.push(`    .withRequestBody(matchingJsonPath("${String(expr).replace(/"/g, '\\"')}"))`);
          }
        } else if (bp.equalTo) {
          lines.push(`    .withRequestBody(matchingJsonPath("${String(bp.matchesJsonPath).replace(/"/g, '\\"')}", equalTo("${String(bp.equalTo).replace(/"/g, '\\"')}")))`);
        } else {
          lines.push(`    .withRequestBody(matchingJsonPath("${String(bp.matchesJsonPath).replace(/"/g, '\\"')}"))`);
        }
      } else if (bp.equalTo) {
        lines.push(`    .withRequestBody(equalTo("${String(bp.equalTo).replace(/"/g, '\\"')}"))`);
      }
    });
  }

  if (res.proxyBaseUrl) {
    lines.push(`    .willReturn(aResponse().proxiedFrom("${String(res.proxyBaseUrl).replace(/"/g, '\\"')}")));`);
    return lines.join('\n');
  }

  const status = res.status || 200;
  lines.push(`    .willReturn(aResponse()`);
  lines.push(`        .withStatus(${status})`);
  if (res.headers) {
    for (const [k, v] of Object.entries(res.headers)) {
      lines.push(`        .withHeader("${k.replace(/"/g, '\\"')}", "${String(v).replace(/"/g, '\\"')}")`);
    }
  }
  const rawBody = res.jsonBody ? JSON.stringify(res.jsonBody) : (res.body || '');
  if (rawBody) {
    lines.push(`        .withBody("${String(rawBody).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\r?\n/g, '\\n')}")`);
  }
  if (Array.isArray(res.transformers) && res.transformers.includes('response-template')) {
    lines.push(`        .withTransformers("response-template")`);
  }
  lines.push(`    ));`);
  return lines.join('\n');
}

import { stubCatalog } from './stub-catalog.js';

export function getAllCatalogStubs() {
  stubCatalog.setRawCollections({
    active: state.currentStubs,
    disabled: state.disabledStubs,
    statefulModels: state.statefulModels
  });
  return stubCatalog.getAll();
}

export function findCatalogStubById(id) {
  stubCatalog.setRawCollections({
    active: state.currentStubs,
    disabled: state.disabledStubs,
    statefulModels: state.statefulModels
  });
  return stubCatalog.getById(id);
}

export function getVisibleCatalogStubs(searchQuery) {
  stubCatalog.setRawCollections({
    active: state.currentStubs,
    disabled: state.disabledStubs,
    statefulModels: state.statefulModels
  });
  const filter = (searchQuery !== undefined ? searchQuery : (elements.searchBox && elements.searchBox.value || '')).trim();
  return stubCatalog.query({
    search: filter,
    status: state.statusFilter,
    project: state.selectedProject
  });
}

export function getCatalogCounts(projectFilter = state.selectedProject) {
  stubCatalog.setRawCollections({
    active: state.currentStubs,
    disabled: state.disabledStubs,
    statefulModels: state.statefulModels
  });
  return stubCatalog.getCounts(projectFilter);
}

export function renderStubList() {
  const allStubs = getAllCatalogStubs();
  const filtered = getVisibleCatalogStubs();

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
    const isCasual = url.startsWith('/__casual/services/') || Boolean(stub.metadata && stub.metadata.protocol === 'casual');
    const displayMethod = isCasual ? 'CASUAL' : method;
    const rawStatus = (stub.response && stub.response.status) || 200;
    const resHeaders = (stub.response && stub.response.headers) || {};
    const casualErrorHeader = resHeaders['X-Casual-Error-State'] || resHeaders['x-casual-error-state'];
    const casualState = isCasual
      ? (casualErrorHeader || (rawStatus >= 200 && rawStatus < 300 ? 'OK' : (rawStatus === 404 ? 'TPENOENT' : 'TPESVCFAIL')))
      : null;
    const name = stub.name || (isCasual ? `Casual ${casualState}` : (stub.response && stub.response.status ? `Status ${stub.response.status}` : 'Unnamed'));
    const project = isDynamic ? (stub.metadata && stub.metadata.project) : getStubProject(stub);
    const isProxy = (stub.response && !!stub.response.proxyBaseUrl) || (stub.metadata && stub.metadata.mode === 'proxy');
    const isTemplated = Boolean(stub.response && Array.isArray(stub.response.transformers) && stub.response.transformers.includes('response-template'));
    const statusCode = isProxy ? 'PROXY' : (isCasual ? casualState : rawStatus);
    const statusCls = isProxy ? 'status-proxy' : (rawStatus >= 500 ? 'status-500' : (rawStatus >= 400 ? 'status-400' : 'status-200'));

    card.innerHTML = `
      <div class="stub-card-select">
        <input type="checkbox" class="stub-card-chk" data-testid="stub-card-chk" data-stub-id="${stub.id}" ${isChecked ? 'checked' : ''} title="Select stub">
      </div>
      <div class="stub-card-content">
        <div class="stub-card-top">
          <span class="http-badge badge-${displayMethod}">${displayMethod}</span>
          <span class="stub-card-url" title="${escapeHtml(url)}">${escapeHtml(url)}</span>
          <span class="stub-status-pill ${statusCls}">${escapeHtml(String(statusCode))}</span>
          ${isTemplated ? '<span class="status-badge badge-tpl" title="Uses WireMock response-template Handlebars transformer">TPL</span>' : ''}
          ${isDynamic ? '<span class="status-badge badge-dynamic" title="In-memory Stateful AST simulation rule">🧠 STATEFUL</span>' : ''}
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
  const stubUrl = getStubUrl(stub.request);
  const isCasual = stubUrl.startsWith('/__casual/services/') || Boolean(stub.metadata && stub.metadata.protocol === 'casual');
  const displayMethod = isCasual ? 'CASUAL' : method;
  elements.detailMethod.textContent = displayMethod;
  elements.detailMethod.className = `http-badge badge-${displayMethod}`;
  elements.detailUrl.textContent = stubUrl;
  elements.detailName.textContent = stub.name ? `— ${stub.name}` : '';

  const isProxy = (stub.response && !!stub.response.proxyBaseUrl) || (stub.metadata && stub.metadata.mode === 'proxy');
  const rawStatus = (stub.response && stub.response.status) || 200;
  const resHeaders = (stub.response && stub.response.headers) || {};
  const casualErrorHeader = resHeaders['X-Casual-Error-State'] || resHeaders['x-casual-error-state'];
  const casualState = isCasual
    ? (casualErrorHeader || (rawStatus >= 200 && rawStatus < 300 ? 'OK' : (rawStatus === 404 ? 'TPENOENT' : 'TPESVCFAIL')))
    : null;
  const status = isProxy ? 'PROXY' : (isCasual ? casualState : rawStatus);
  elements.detailStatus.textContent = status;
  elements.detailStatus.className = `card-value ${isProxy ? 'status-proxy' : (rawStatus >= 500 ? 'status-500' : (rawStatus >= 400 ? 'status-400' : 'status-200'))}`;

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
      detailState.textContent = 'STATEFUL SIMULATION';
      detailState.className = 'card-value text-dynamic';
    } else if (!state.capabilities.lifecycle) {
      detailState.textContent = 'ACTIVE';
      detailState.className = 'card-value text-success';
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
    } else if (!state.capabilities.lifecycle) {
      btnToggle.classList.remove('hidden');
      btnToggle.setAttribute('disabled', 'true');
      btnToggle.classList.add('cap-disabled');
      btnToggle.textContent = '⏸️ Disable Stub';
      btnToggle.setAttribute('title', 'Pausing/disabling stubs requires wiremock-extension-ui lifecycle endpoint');
    } else {
      btnToggle.classList.remove('hidden');
      btnToggle.removeAttribute('disabled');
      btnToggle.classList.remove('cap-disabled');
      btnToggle.textContent = isDisabled ? '🟢 Enable Stub' : '⏸️ Disable Stub';
      btnToggle.className = `btn btn-small ${isDisabled ? 'btn-success' : 'btn-warning'}`;
      btnToggle.removeAttribute('title');
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
      const normalized = normalizeJsonPayload(bp.equalToJson);
      body = normalized !== null ? JSON.stringify(normalized, null, 2) : (typeof bp.equalToJson === 'string' ? bp.equalToJson : JSON.stringify(bp.equalToJson, null, 2));
    } else if (bp.equalTo) {
      body = bp.equalTo;
    } else if (bp.matchesJsonPath) {
      const sampleObj = {};
      stub.request.bodyPatterns.forEach(p => {
        if (p.matchesJsonPath) {
          const expr = typeof p.matchesJsonPath === 'object' && p.matchesJsonPath !== null
            ? (p.matchesJsonPath.expression || '')
            : String(p.matchesJsonPath);
          const val = typeof p.matchesJsonPath === 'object' && p.matchesJsonPath !== null && p.matchesJsonPath.equalTo !== undefined
            ? p.matchesJsonPath.equalTo
            : (p.equalTo !== undefined ? p.equalTo : 'sample_value');
          const match = expr.match(/\$\.([a-zA-Z0-9_.-]+)/);
          if (match && match[1]) {
            const parts = match[1].split('.');
            let curr = sampleObj;
            for (let i = 0; i < parts.length - 1; i++) {
              if (!curr[parts[i]]) curr[parts[i]] = {};
              curr = curr[parts[i]];
            }
            curr[parts[parts.length - 1]] = val;
          }
        }
      });
      body = Object.keys(sampleObj).length > 0 ? JSON.stringify(sampleObj, null, 2) : '{\n  \n}';
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

export function initStubsTab() {
  if (elements.searchBox) {
    elements.searchBox.addEventListener('input', () => {
      renderStubList();
      updateBulkToolbar();
      setRoute('stubs', { q: elements.searchBox.value.trim() }, true);
    });
  }
  if (elements.projectFilterSelect) {
    elements.projectFilterSelect.addEventListener('change', (e) => {
      state.selectedProject = e.target.value || null;
      renderStubList();
      updateFilterCounts();
      updateBulkToolbar();
    });
  }
  ['all', 'active', 'disabled', 'dynamic'].forEach(filter => {
    const btn = document.getElementById(`btn-filter-${filter}`);
    if (btn) {
      btn.addEventListener('click', () => {
        document.querySelectorAll('.filter-pill').forEach(p => p.classList.remove('active'));
        btn.classList.add('active');
        state.statusFilter = filter;
        renderStubList();
        updateBulkToolbar();
      });
    }
  });

  if (elements.chkSelectAll) {
    elements.chkSelectAll.addEventListener('change', (e) => {
      handleSelectAllToggle(e.target.checked);
    });
  }
  if (elements.btnExportStubs) {
    elements.btnExportStubs.addEventListener('click', () => {
      exportStubs(state.selectedProject ? 'project' : 'all');
    });
  }
  if (elements.btnRefresh) {
    elements.btnRefresh.addEventListener('click', loadData);
  }
  if (elements.btnRefreshStubs) {
    elements.btnRefreshStubs.addEventListener('click', () => {
      elements.btnRefreshStubs.disabled = true;
      loadData().finally(() => {
        elements.btnRefreshStubs.disabled = false;
      });
    });
  }

  // Stubs Detail Actions
  if (elements.btnTestStub) {
    elements.btnTestStub.addEventListener('click', () => {
      if (!state.selectedStubId) return;
      const stub = findCatalogStubById(state.selectedStubId);
      if (stub) sendStubToTester(stub);
    });
  }
  if (elements.btnViewStubInJournal) {
    elements.btnViewStubInJournal.addEventListener('click', () => {
      if (!state.selectedStubId) return;
      const stub = findCatalogStubById(state.selectedStubId);
      if (!stub) return;
      const path = getStubUrl(stub.request);
      state.journalCurrentPage = 1;
      activateTab('tab-journal', false);
      if (elements.journalSearch) {
        elements.journalSearch.value = path;
      }
      if (elements.journalContextBanner) {
        elements.journalContextBanner.classList.remove('hidden');
        if (elements.journalFilterChipText) {
          elements.journalFilterChipText.textContent = `Filtered by Stub: ${path}`;
        }
      }
      renderJournal();
      setRoute('journal', { q: path }, false);
    });
  }
  if (elements.btnCopyJson) {
    elements.btnCopyJson.addEventListener('click', () => {
      if (elements.stubJsonViewer && elements.stubJsonViewer.textContent) {
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(elements.stubJsonViewer.textContent).catch(() => {});
        }
        elements.btnCopyJson.textContent = '✅ Copied!';
        setTimeout(() => elements.btnCopyJson.textContent = '📋 Copy JSON', 1500);
      }
    });
  }
  if (elements.btnCopyCurl) {
    elements.btnCopyCurl.addEventListener('click', () => {
      if (!state.selectedStubId) return;
      const stub = findCatalogStubById(state.selectedStubId);
      if (!stub) return;
      const cmd = generateCreateStubCurl(stub);
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(cmd).catch(() => {});
      }
      elements.btnCopyCurl.textContent = '✅ Copied!';
      setTimeout(() => elements.btnCopyCurl.textContent = '📋 Copy cURL', 1500);
    });
  }
  if (elements.btnCopyJava) {
    elements.btnCopyJava.addEventListener('click', () => {
      if (!state.selectedStubId) return;
      const stub = findCatalogStubById(state.selectedStubId);
      if (!stub) return;
      const snippet = generateJavaStubFor(stub);
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(snippet).catch(() => {});
      }
      elements.btnCopyJava.textContent = '✅ Copied!';
      setTimeout(() => elements.btnCopyJava.textContent = '☕ Copy Java', 1500);
    });
  }
}
