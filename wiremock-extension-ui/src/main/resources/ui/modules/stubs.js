/**
 * Stubs master-detail viewer, filtering, project grouping, and cURL generator.
 * Handles search filtering, detail card rendering, and transfer to HTTP tester.
 */
import { state, uiActions, registerUiActions } from './state.js';
import { elements, escapeHtml } from './dom.js';
import { detectFormat } from './highlighter.js';
import { detectCharset, createPayloadComponent } from './payload-lens.js';
import { setRoute, activateTab } from './router.js';
import {
  stubCatalog,
  getStubUrl,
  generateCurl,
  generateCreateStubCurl,
  generateJavaStubFor,
  getStubProject,
  isStubParked
} from './stub-catalog.js';
import { handleToggleStub, handleDeleteStub, openStubEditor } from './lifecycle.js';
import { handleStubCheckboxClick, updateBulkToolbar, handleSelectAllToggle } from './bulk-actions.js';
import { exportStubs } from './import-export.js';
import { updateFilterCounts } from './projects.js';
import { sendStubToTester } from './tester.js';

export { getStubUrl, generateCurl, generateCreateStubCurl, generateJavaStubFor, sendStubToTester };

let currentStubViewMode = 'rich';

export function getAllCatalogStubs() {
  return stubCatalog.getAll();
}

export function findCatalogStubById(id) {
  return stubCatalog.getById(id);
}

export function getVisibleCatalogStubs(searchQuery) {
  const filter = (searchQuery !== undefined ? searchQuery : (elements.searchBox && elements.searchBox.value || '')).trim();
  return stubCatalog.query({
    search: filter,
    status: state.statusFilter,
    project: state.selectedProject
  });
}

export function getCatalogCounts(projectFilter = state.selectedProject) {
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
    const isDisabled = isStubParked(stub);
    const isSelected = stub.id === state.selectedStubId;
    const isChecked = state.selectedStubIds.has(stub.id);

    const card = document.createElement('div');
    card.className = `stub-card ${isSelected ? 'selected' : ''} ${isDisabled ? 'stub-disabled' : ''}`;
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
    const project = getStubProject(stub);
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

  const isDisabled = isStubParked(stub);
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

  elements.detailPriority.textContent = stub.priority || 5;
  elements.detailScenario.textContent = stub.scenarioName
    ? `${stub.scenarioName} [${stub.requiredScenarioState || 'Start'} -> ${stub.newScenarioState || 'Same'}]`
    : 'None';

  // Project card
  const detailProject = document.getElementById('detail-project');
  if (detailProject) {
    detailProject.textContent = getStubProject(stub);
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
    btnToggle.classList.remove('hidden');
    btnToggle.removeAttribute('disabled');
    btnToggle.classList.remove('cap-disabled');
    btnToggle.textContent = isDisabled ? '🟢 Enable Stub' : '⏸️ Disable Stub';
    btnToggle.className = `btn btn-small ${isDisabled ? 'btn-success' : 'btn-warning'}`;
    btnToggle.title = isDisabled
      ? 'Restore stub priority to active tier (<= 10)'
      : 'Park stub at priority 100 (below active stubs and proxies)';
  }

  // Edit / Clone buttons
  const btnEdit = document.getElementById('btn-edit-stub');
  const btnClone = document.getElementById('btn-clone-stub');
  if (btnEdit) btnEdit.style.display = '';
  if (btnClone) btnClone.style.display = '';

  const rawStubJson = typeof stub === 'string' ? stub : JSON.stringify(stub, null, 2);

  const resBody = (stub.response && stub.response.body) || '';
  const bodyFormat = resBody ? detectFormat(resBody).toUpperCase() : 'JSON';
  const stubCharset = detectCharset(resBody, resHeaders);
  let normBuf = null;
  if (isCasual) {
    const bufferType = (stub.metadata && stub.metadata.bufferType)
      || (resHeaders['X-Casual-Buffer-Type'] || resHeaders['x-casual-buffer-type'])
      || (stub.request && stub.request.headers && (stub.request.headers['x-casual-buffer-type'] || stub.request.headers['X-Casual-Buffer-Type']))
      || 'JSON';
    normBuf = typeof bufferType === 'object' && bufferType.equalTo ? bufferType.equalTo : String(bufferType);
  }

  const modeToggle = document.getElementById('stub-viewer-mode-toggle');
  const topPillGroup = document.getElementById('stub-top-pill-group');
  const topPill = document.getElementById('stub-top-pill');
  const btnTopEdit = document.getElementById('btn-top-stub-edit');
  const btnStubCopy = document.getElementById('btn-stub-copy');
  const btnStubMaximize = document.getElementById('btn-stub-maximize');

  if (btnTopEdit) {
    btnTopEdit.style.display = 'inline-flex';
    btnTopEdit.disabled = false;
    btnTopEdit.title = 'Edit stub mapping in editor modal';
  }

  createPayloadComponent(null, {
    viewerEl: elements.stubJsonViewer,
    toggleEl: modeToggle,
    pillEl: topPill,
    pillGroupEl: topPillGroup,
    maximizeBtn: btnStubMaximize,
    copyBtn: btnStubCopy,
    editBtn: btnTopEdit,
    onEdit: () => openStubEditor(stub, false),
    readOnly: true,
    editable: false,
    readOnlyReason: "Read-only stub preview — click '✏️ Edit' in the stub header above to modify this stub",
    text: rawStubJson,
    format: bodyFormat,
    charset: stubCharset,
    bufferType: normBuf,
    defaultMode: currentStubViewMode,
    title: `Stub: ${stub.name || stub.id}`,
    context: 'stub'
  });

  if (updateRoute) {
    setRoute('stubs', { stubId: stub.id });
  }
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
  ['all', 'active', 'disabled'].forEach(filter => {
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
    elements.btnRefresh.addEventListener('click', () => {
      if (uiActions.loadData) uiActions.loadData();
    });
  }
  if (elements.btnRefreshStubs) {
    elements.btnRefreshStubs.addEventListener('click', () => {
      elements.btnRefreshStubs.disabled = true;
      (uiActions.loadData ? uiActions.loadData() : Promise.resolve()).finally(() => {
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
      if (uiActions.renderJournal) uiActions.renderJournal();
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

  const topPill = document.getElementById('stub-top-pill');
  if (topPill) {
    topPill.addEventListener('click', () => {
      if (elements.stubJsonViewer) {
        const isHidden = elements.stubJsonViewer.classList.toggle('hidden');
        topPill.title = isHidden ? 'Click to expand' : 'Click to collapse';
      }
    });
  }
}

registerUiActions({ selectStub, renderStubList });
