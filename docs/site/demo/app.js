/**
 * WireMock Console UI - Application Entry Point (ES Module).
 * Pure Vanilla JS, zero NPM dependencies.
 *
 * Re-exports core API & utilities for test automation compatibility:
 * loadMappings, executeTesterRequest, highlightJson, generateCurl, parseHash.
 */

import { state, setTargetUrlInLocation, getWireMockUri } from './modules/state.js';
import { elements } from './modules/dom.js';
import { highlightJson } from './modules/highlighter.js';
import {
  parseHash,
  setRoute,
  activateTab,
  applyRouteFromUrl,
  registerRouteHandlers,
  toBase64,
  fromBase64
} from './modules/router.js';
import {
  getStubUrl,
  generateCurl,
  generateCreateStubCurl,
  generateJavaStubFor,
  renderStubList,
  selectStub,
  sendStubToTester
} from './modules/stubs.js';
import {
  getRequestVerdict,
  renderJournal,
  setupJournalAutoRefresh,
  registerJournalLoader,
  getFilteredProxiedRequests
} from './modules/journal.js';
import {
  renderScenarios,
  registerScenarioFetchers
} from './modules/scenarios.js';
import {
  appendHeader,
  parseHeadersInput,
  updateTesterRoute,
  setInspectorTab,
  clearTesterHistory,
  renderTesterResponse,
  initTesterHistoryCollapse,
  setTesterHistoryCollapsed,
  toggleTesterHistory
} from './modules/tester.js';
import { setupOpenApiModal } from './modules/openapi.js';
import {
  renderProjectSelector,
  updateFilterCounts,
  setupProjectLifecycleBar
} from './modules/projects.js';
import {
  handleToggleStub,
  handleDeleteStub,
  openStubEditor,
  closeStubEditor,
  saveStubEditor,
  applyStubTemplate,
  updateBodyMatchLabel
} from './modules/lifecycle.js';
import {
  exportStubs,
  handleBundleImport,
  openImportModal,
  closeImportModal,
  switchImportTab
} from './modules/import-export.js';
import {
  handleSelectAllToggle,
  executeBulkAction,
  updateBulkToolbar
} from './modules/bulk-actions.js';
import {
  doFetch,
  apiGet,
  loadMappings,
  loadDisabledStubs,
  loadJournal,
  loadScenarios,
  loadData,
  executeTesterRequest,
  snapshotFilteredRecordings
} from './modules/api.js';
import {
  setupDynamicStubModal,
  normalizeStatefulModelsToStubs
} from './modules/dynamic-stubs.js';
import {
  initNetEmTab,
  activateNetEm,
  deactivateNetEm
} from './modules/netem.js';
import {
  renderTargetCapabilities,
  applyCapabilityVisibility
} from './modules/capabilities.js';

export function getAllStubs() {
  return [...state.currentStubs, ...state.disabledStubs, ...normalizeStatefulModelsToStubs(state.statefulModels || [])];
}

// Exported public API for test suite and external scripts
export {
  loadMappings,
  loadDisabledStubs,
  executeTesterRequest,
  highlightJson,
  generateCurl,
  generateJavaStubFor,
  getRequestVerdict,
  parseHash,
  setRoute,
  activateTab,
  exportStubs,
  renderTargetCapabilities,
  applyCapabilityVisibility,
  getWireMockUri
};

// Normalize base pathname with trailing slash for relative neighbor URLs (e.g. ../swagger-ui/)
if (window.location.pathname && !window.location.pathname.endsWith('/')) {
  if (window.history && window.history.replaceState) {
    window.history.replaceState(null, '', window.location.pathname + '/' + window.location.search + window.location.hash);
  }
}

// Wire inter-module callbacks
registerJournalLoader(loadJournal);
registerScenarioFetchers({ doFetch, loadScenarios });
setupOpenApiModal();
setupProjectLifecycleBar(loadData);
setupDynamicStubModal();
initTesterHistoryCollapse();

registerRouteHandlers({
  applyStubs: (params) => {
    const q = params.get('q') || '';
    if (elements.searchBox && elements.searchBox.value !== q) {
      elements.searchBox.value = q;
    }
    renderStubList();
    const stubId = params.get('stubId');
    if (stubId) {
      const match = getAllStubs().find(s => s.id === stubId);
      if (match) selectStub(match, false);
    }
  },
  applyJournal: (params) => {
    const q = params.get('q') || '';
    if (elements.journalSearch && elements.journalSearch.value !== q) {
      elements.journalSearch.value = q;
    }
    const unmatched = params.get('unmatched') === 'true';
    const prevUnmatched = elements.filterUnmatchedOnly ? elements.filterUnmatchedOnly.checked : false;
    if (elements.filterUnmatchedOnly) {
      elements.filterUnmatchedOnly.checked = unmatched;
    }
    state.expandedRequestId = params.get('reqId') || null;
    if (unmatched !== prevUnmatched) {
      renderJournal();
      loadJournal();
    } else {
      renderJournal();
    }
  },
  applyTester: (params) => {
    const m = params.get('m');
    if (m && elements.testerMethod) elements.testerMethod.value = m;
    const p = params.get('p');
    if (p && elements.testerUrl) elements.testerUrl.value = p;
    const h = params.get('h');
    if (h && elements.testerHeaders) elements.testerHeaders.value = fromBase64(h);
    const b = params.get('b');
    if (b && elements.testerBody) elements.testerBody.value = fromBase64(b);
  },
  applyNetEm: () => {
    activateNetEm();
  }
});

// Event Listeners: Navigation
elements.tabs.forEach(tab => {
  tab.addEventListener('click', () => {
    const target = tab.getAttribute('data-tab') || (tab.dataset && tab.dataset.tab);
    activateTab(target, true);
    if (target === 'tab-netem') {
      activateNetEm();
    } else {
      deactivateNetEm();
    }
  });
});

window.addEventListener('hashchange', applyRouteFromUrl);

// Event Listeners: Stubs Tab
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
if (elements.btnCreateStub) {
  elements.btnCreateStub.addEventListener('click', () => {
    openStubEditor(null, false);
  });
}

// Stubs Detail Actions
if (elements.btnTestStub) {
  elements.btnTestStub.addEventListener('click', () => {
    if (!state.selectedStubId) return;
    const stub = getAllStubs().find(s => s.id === state.selectedStubId);
    if (stub) sendStubToTester(stub);
  });
}
if (elements.btnEditStub) {
  elements.btnEditStub.addEventListener('click', () => {
    if (!state.selectedStubId) return;
    const stub = [...state.currentStubs, ...state.disabledStubs].find(s => s.id === state.selectedStubId);
    if (stub) openStubEditor(stub, false);
  });
}
if (elements.btnCloneStub) {
  elements.btnCloneStub.addEventListener('click', () => {
    if (!state.selectedStubId) return;
    const stub = [...state.currentStubs, ...state.disabledStubs].find(s => s.id === state.selectedStubId);
    if (stub) openStubEditor(stub, true);
  });
}
if (elements.btnToggleStub) {
  elements.btnToggleStub.addEventListener('click', () => {
    if (!state.selectedStubId) return;
    handleToggleStub(state.selectedStubId);
  });
}
if (elements.btnDeleteStub) {
  elements.btnDeleteStub.addEventListener('click', () => {
    if (!state.selectedStubId) return;
    handleDeleteStub(state.selectedStubId);
  });
}
if (elements.btnViewStubInJournal) {
  elements.btnViewStubInJournal.addEventListener('click', () => {
    if (!state.selectedStubId) return;
    const stub = getAllStubs().find(s => s.id === state.selectedStubId);
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
if (elements.btnJournalClearStubFilter) {
  elements.btnJournalClearStubFilter.addEventListener('click', () => {
    state.journalCurrentPage = 1;
    if (elements.journalSearch) {
      elements.journalSearch.value = '';
    }
    if (elements.journalContextBanner) {
      elements.journalContextBanner.classList.add('hidden');
    }
    renderJournal();
    setRoute('journal', {}, false);
  });
}
if (elements.btnTesterResetStub) {
  elements.btnTesterResetStub.addEventListener('click', () => {
    if (state.contextStub) {
      sendStubToTester(state.contextStub);
    }
  });
}
if (elements.btnTesterClearContext) {
  elements.btnTesterClearContext.addEventListener('click', () => {
    state.contextStub = null;
    state.selectedStubId = null;
    document.querySelectorAll('.stub-card').forEach(el => el.classList.remove('selected'));
    if (elements.testerContextBanner) {
      elements.testerContextBanner.classList.add('hidden');
    }
    if (elements.testerMethod) elements.testerMethod.value = 'GET';
    if (elements.testerUrl) elements.testerUrl.value = '/api/v1/users';
    if (elements.testerHeaders) elements.testerHeaders.value = '';
    if (elements.testerBody) elements.testerBody.value = '';
    updateTesterRoute(true);
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
    const stub = getAllStubs().find(s => s.id === state.selectedStubId);
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
    const stub = getAllStubs().find(s => s.id === state.selectedStubId);
    if (!stub) return;
    const snippet = generateJavaStubFor(stub);
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(snippet).catch(() => {});
    }
    elements.btnCopyJava.textContent = '✅ Copied!';
    setTimeout(() => elements.btnCopyJava.textContent = '☕ Copy Java', 1500);
  });
}

// Bulk Actions Toolbar
if (elements.btnBulkEnable) {
  elements.btnBulkEnable.addEventListener('click', () => executeBulkAction('enable'));
}
if (elements.btnBulkDisable) {
  elements.btnBulkDisable.addEventListener('click', () => executeBulkAction('disable'));
}
if (elements.btnBulkExport) {
  elements.btnBulkExport.addEventListener('click', () => executeBulkAction('export'));
}
if (elements.btnBulkDelete) {
  elements.btnBulkDelete.addEventListener('click', () => executeBulkAction('delete'));
}

// Stub Editor Modal
if (elements.btnCloseEditorModal) {
  elements.btnCloseEditorModal.addEventListener('click', closeStubEditor);
}
if (elements.btnCancelEditor) {
  elements.btnCancelEditor.addEventListener('click', closeStubEditor);
}
if (elements.btnSaveEditor) {
  elements.btnSaveEditor.addEventListener('click', saveStubEditor);
}
if (elements.btnEditorFormatJson) {
  elements.btnEditorFormatJson.addEventListener('click', () => {
    const bodyEl = document.getElementById('editor-body');
    if (!bodyEl || !bodyEl.value.trim()) return;
    try {
      const parsed = JSON.parse(bodyEl.value);
      bodyEl.value = JSON.stringify(parsed, null, 2);
    } catch (err) {
      alert('Invalid JSON: ' + err.message);
    }
  });
}
if (elements.btnEditorOpenOpenApi) {
  elements.btnEditorOpenOpenApi.addEventListener('click', () => {
    closeStubEditor();
    if (elements.btnOpenOpenApiModal) {
      elements.btnOpenOpenApiModal.click();
    }
  });
}
if (elements.editorTemplateSelect) {
  elements.editorTemplateSelect.addEventListener('change', () => {
    const val = elements.editorTemplateSelect.value;
    if (val && val !== 'custom') {
      applyStubTemplate(val);
    }
  });
}
if (elements.btnApplyTemplate) {
  elements.btnApplyTemplate.addEventListener('click', () => {
    if (elements.editorTemplateSelect && elements.editorTemplateSelect.value !== 'custom') {
      applyStubTemplate(elements.editorTemplateSelect.value);
    }
  });
}
if (elements.editorBodyMatchType) {
  elements.editorBodyMatchType.addEventListener('change', () => {
    const val = elements.editorBodyMatchType.value;
    if (elements.editorBodyMatchContainer) {
      if (val === 'none') {
        elements.editorBodyMatchContainer.classList.add('hidden');
      } else {
        elements.editorBodyMatchContainer.classList.remove('hidden');
      }
    }
    updateBodyMatchLabel(val);
  });
}
if (elements.btnWelcomeOpenApi) {
  elements.btnWelcomeOpenApi.addEventListener('click', () => {
    if (elements.btnOpenOpenApiModal) {
      elements.btnOpenOpenApiModal.click();
    }
  });
}
if (elements.btnWelcomeCreate) {
  elements.btnWelcomeCreate.addEventListener('click', () => {
    openStubEditor(null, false);
  });
}

// Unified Import Modal: Tabs and Bundle Import
if (elements.importTabOpenApi) {
  elements.importTabOpenApi.addEventListener('click', () => switchImportTab('openapi'));
}
if (elements.importTabBundle) {
  elements.importTabBundle.addEventListener('click', () => switchImportTab('bundle'));
}
if (elements.btnSubmitBundleImport) {
  elements.btnSubmitBundleImport.addEventListener('click', handleBundleImport);
}
if (elements.btnBrowseBundleFile && elements.bundleFileInput) {
  elements.btnBrowseBundleFile.addEventListener('click', () => elements.bundleFileInput.click());
  elements.bundleFileInput.addEventListener('change', (e) => {
    const file = e.target.files && e.target.files[0];
    if (file) {
      const reader = new FileReader();
      reader.onload = (event) => {
        if (elements.bundleContent) elements.bundleContent.value = event.target.result;
      };
      reader.readAsText(file);
    }
  });
}
if (elements.bundleDropzone) {
  elements.bundleDropzone.addEventListener('dragover', (e) => {
    e.preventDefault();
    elements.bundleDropzone.classList.add('dragover');
  });
  elements.bundleDropzone.addEventListener('dragleave', () => {
    elements.bundleDropzone.classList.remove('dragover');
  });
  elements.bundleDropzone.addEventListener('drop', (e) => {
    e.preventDefault();
    elements.bundleDropzone.classList.remove('dragover');
    if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
      const file = e.dataTransfer.files[0];
      const reader = new FileReader();
      reader.onload = (event) => {
        if (elements.bundleContent) elements.bundleContent.value = event.target.result;
      };
      reader.readAsText(file);
    }
  });
}

// Event Listeners: Journal Tab
['all', 'proxied', 'stub', 'stateful', 'unmatched'].forEach(verdict => {
  const btn = document.getElementById(`btn-journal-verdict-${verdict}`);
  if (btn) {
    btn.addEventListener('click', () => {
      document.querySelectorAll('.journal-verdict-pill').forEach(p => p.classList.remove('active'));
      btn.classList.add('active');
      state.journalVerdictFilter = verdict;
      const wasUnmatched = elements.filterUnmatchedOnly && elements.filterUnmatchedOnly.checked;
      const isNowUnmatched = (verdict === 'unmatched');
      if (elements.filterUnmatchedOnly) {
        elements.filterUnmatchedOnly.checked = isNowUnmatched;
      }
      renderJournal();
      loadJournal();
      setRoute('journal', { unmatched: isNowUnmatched ? 'true' : null }, true);
    });
  }
});
if (elements.filterUnmatchedOnly) {
  elements.filterUnmatchedOnly.addEventListener('change', () => {
    const isChecked = elements.filterUnmatchedOnly.checked;
    state.journalVerdictFilter = isChecked ? 'unmatched' : 'all';
    document.querySelectorAll('.journal-verdict-pill').forEach(p => p.classList.remove('active'));
    const activeBtn = document.getElementById(isChecked ? 'btn-journal-verdict-unmatched' : 'btn-journal-verdict-all');
    if (activeBtn) activeBtn.classList.add('active');
    renderJournal();
    loadJournal();
    setRoute('journal', { unmatched: isChecked ? 'true' : null }, true);
  });
}
if (elements.journalSearch) {
  elements.journalSearch.addEventListener('input', () => {
    renderJournal();
    setRoute('journal', { q: elements.journalSearch.value.trim() }, true);
  });
  elements.journalSearch.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      loadJournal();
    }
  });
}
if (elements.journalTimeRange) {
  elements.journalTimeRange.addEventListener('change', () => {
    const range = elements.journalTimeRange.value;
    state.journalTimeRange = range;
    const now = Date.now();
    if (range === 'custom') {
      if (elements.journalCustomTime) elements.journalCustomTime.classList.remove('hidden');
      return;
    }
    if (elements.journalCustomTime) elements.journalCustomTime.classList.add('hidden');
    if (range === '5m') {
      state.journalSince = new Date(now - 5 * 60 * 1000).toISOString();
      state.journalUntil = null;
    } else if (range === '15m') {
      state.journalSince = new Date(now - 15 * 60 * 1000).toISOString();
      state.journalUntil = null;
    } else if (range === '1h') {
      state.journalSince = new Date(now - 60 * 60 * 1000).toISOString();
      state.journalUntil = null;
    } else if (range === '24h') {
      state.journalSince = new Date(now - 24 * 60 * 60 * 1000).toISOString();
      state.journalUntil = null;
    } else {
      state.journalSince = null;
      state.journalUntil = null;
    }
    renderJournal();
    loadJournal();
  });
}
if (elements.journalTimeSince) {
  elements.journalTimeSince.addEventListener('change', () => {
    state.journalSince = elements.journalTimeSince.value ? new Date(elements.journalTimeSince.value).toISOString() : null;
    renderJournal();
    loadJournal();
  });
  elements.journalTimeSince.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      state.journalSince = elements.journalTimeSince.value ? new Date(elements.journalTimeSince.value).toISOString() : null;
      renderJournal();
      loadJournal();
    }
  });
}
if (elements.journalTimeUntil) {
  elements.journalTimeUntil.addEventListener('change', () => {
    state.journalUntil = elements.journalTimeUntil.value ? new Date(elements.journalTimeUntil.value).toISOString() : null;
    renderJournal();
    loadJournal();
  });
  elements.journalTimeUntil.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      state.journalUntil = elements.journalTimeUntil.value ? new Date(elements.journalTimeUntil.value).toISOString() : null;
      renderJournal();
      loadJournal();
    }
  });
}
if (elements.btnJournalSnapshotFiltered) {
  elements.btnJournalSnapshotFiltered.addEventListener('click', async () => {
    const proxiedReqs = getFilteredProxiedRequests();
    if (!proxiedReqs || proxiedReqs.length === 0) return;
    const count = proxiedReqs.length;
    const targetProject = (state.selectedProject && state.selectedProject !== '_all' && state.selectedProject !== '*')
      ? state.selectedProject
      : 'Recorded Traffic';

    elements.btnJournalSnapshotFiltered.disabled = true;
    const origText = elements.btnJournalSnapshotFiltered.innerHTML;
    elements.btnJournalSnapshotFiltered.textContent = '⏳ Snapshotting...';

    try {
      const requestIds = proxiedReqs.map(r => r.id);
      if (state.capabilities.lifecycle) {
        await snapshotFilteredRecordings(requestIds, targetProject, false);
      } else {
        // Vanilla WireMock fallback: create active mappings
        for (const req of proxiedReqs) {
          const status = (req.response && req.response.status) || (req.responseDefinition && req.responseDefinition.status) || 200;
          const resBody = (req.response && req.response.body) || (req.responseDefinition && req.responseDefinition.body) || '';
          await doFetch('/__admin/mappings', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              name: `[RECORDED ${req.request.method} ${status}] ${req.request.url}`,
              priority: 5,
              request: { method: req.request.method, url: req.request.url },
              response: { status, body: resBody },
              metadata: { source: 'recorded-proxy', project: targetProject, mode: 'static' }
            })
          });
        }
      }
      await loadData();
      elements.btnJournalSnapshotFiltered.textContent = `✅ Snapshotted ${count}!`;
      setTimeout(() => {
        elements.btnJournalSnapshotFiltered.innerHTML = origText;
        renderJournal();
      }, 2000);
    } catch (err) {
      alert('Failed to snapshot recordings: ' + err.message);
      elements.btnJournalSnapshotFiltered.innerHTML = origText;
      renderJournal();
    }
  });
}
if (elements.journalLimitInput) {
  const handleLimitChange = () => {
    const val = parseInt(elements.journalLimitInput.value, 10);
    state.journalLimit = (val && val > 0) ? val : 100;
    elements.journalLimitInput.value = state.journalLimit;
    loadJournal();
  };
  elements.journalLimitInput.addEventListener('change', handleLimitChange);
  elements.journalLimitInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      handleLimitChange();
    }
  });
}
if (elements.journalAutoRefresh) {
  elements.journalAutoRefresh.addEventListener('change', setupJournalAutoRefresh);
}
if (elements.btnRefreshJournal) {
  elements.btnRefreshJournal.addEventListener('click', () => {
    elements.btnRefreshJournal.disabled = true;
    loadJournal().finally(() => {
      elements.btnRefreshJournal.disabled = false;
    });
  });
}
if (elements.btnResetJournal) {
  elements.btnResetJournal.addEventListener('click', () => {
    if (confirm('Are you sure you want to clear the request journal?')) {
      doFetch('/__admin/requests', { method: 'DELETE' })
        .then(() => {
          state.journalCurrentPage = 1;
          loadJournal();
        })
        .catch(err => console.warn('Could not reset journal', err));
    }
  });
}

// Event Listeners: Tester Tab
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
if (elements.btnToggleTesterHistory) {
  elements.btnToggleTesterHistory.addEventListener('click', (e) => {
    e.stopPropagation();
    toggleTesterHistory();
  });
}
if (elements.btnExpandTesterHistory) {
  elements.btnExpandTesterHistory.addEventListener('click', (e) => {
    e.stopPropagation();
    setTesterHistoryCollapsed(false);
  });
}
if (elements.testerHistoryCollapsedBar) {
  elements.testerHistoryCollapsedBar.addEventListener('click', () => {
    setTesterHistoryCollapsed(false);
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
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(cmd).catch(() => {});
    }
    elements.btnTesterCopyCurl.textContent = '✅ Copied!';
    setTimeout(() => elements.btnTesterCopyCurl.textContent = '📋 Copy cURL', 1500);
  });
}
if (elements.btnTesterShareLink) {
  elements.btnTesterShareLink.addEventListener('click', () => {
    updateTesterRoute(false);
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(window.location.href).catch(() => {});
    }
    elements.btnTesterShareLink.textContent = '✅ Link Copied!';
    setTimeout(() => elements.btnTesterShareLink.textContent = '🔗 Copy Share Link', 1500);
  });
}
if (elements.btnViewHighlighted) {
  elements.btnViewHighlighted.addEventListener('click', () => {
    state.testerViewMode = 'highlighted';
    elements.btnViewHighlighted.classList.add('active');
    if (elements.btnViewRaw) elements.btnViewRaw.classList.remove('active');
    renderTesterResponse(state.lastRawResponseBody);
  });
}
if (elements.btnViewRaw) {
  elements.btnViewRaw.addEventListener('click', () => {
    state.testerViewMode = 'raw';
    elements.btnViewRaw.classList.add('active');
    if (elements.btnViewHighlighted) elements.btnViewHighlighted.classList.remove('active');
    renderTesterResponse(state.lastRawResponseBody);
  });
}
if (elements.btnTesterCopyResponse) {
  elements.btnTesterCopyResponse.addEventListener('click', () => {
    if (state.lastRawResponseBody) {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(state.lastRawResponseBody).catch(() => {});
      }
      elements.btnTesterCopyResponse.textContent = '✅ Copied!';
      setTimeout(() => elements.btnTesterCopyResponse.textContent = '📋 Copy', 1500);
    }
  });
}
[elements.testerMethod, elements.testerUrl, elements.testerHeaders, elements.testerBody].forEach(el => {
  if (el) el.addEventListener('change', () => updateTesterRoute(true));
});

// Event Listeners: Scenarios Tab
if (elements.btnRefreshScenarios) {
  elements.btnRefreshScenarios.addEventListener('click', () => {
    elements.btnRefreshScenarios.disabled = true;
    loadScenarios().finally(() => {
      elements.btnRefreshScenarios.disabled = false;
    });
  });
}
if (elements.btnResetScenarios) {
  elements.btnResetScenarios.addEventListener('click', () => {
    if (confirm('Reset all scenarios to their Started state?')) {
      doFetch('/__admin/scenarios/reset', { method: 'POST' })
        .then(() => loadScenarios())
        .catch(err => console.warn('Could not reset scenarios', err));
    }
  });
}

// Target Host Display & Switcher
export function updateTargetHostDisplay() {
  const wiremockUri = getWireMockUri();
  document.title = `WMC - ${wiremockUri}`;

  const brandEl = document.querySelector('.brand');
  if (brandEl) {
    brandEl.title = `WMC - ${wiremockUri}`;
  }

  if (elements.targetHostLabel) {
    if (state.targetConfig) {
      const modeStr = state.connectionStrategy === 'direct' ? 'direct' : 'proxied';
      elements.targetHostLabel.textContent = `Target: ${state.targetConfig.fullUrl} (${modeStr})`;
    } else {
      elements.targetHostLabel.textContent = 'Target: (same-origin)';
    }
  }
  if (elements.targetHostDot) {
    elements.targetHostDot.className = state.targetConfig
      ? 'target-host-dot remote'
      : 'target-host-dot';
  }
  const upstreamUrl = state.targetConfig ? state.targetConfig.fullUrl : '';
  if (elements.linkSwaggerUi) {
    elements.linkSwaggerUi.href = upstreamUrl
      ? `${upstreamUrl}/__admin/swagger-ui/`
      : '../swagger-ui/';
  }
  if (elements.linkMetrics) {
    elements.linkMetrics.href = upstreamUrl
      ? `${upstreamUrl}/__admin/metrics/prometheus`
      : '../metrics/prometheus';
  }
}

function setupTargetHostControls() {
  if (elements.btnTargetHost && elements.targetHostModal) {
    elements.btnTargetHost.addEventListener('click', () => {
      elements.targetHostModal.classList.remove('hidden');
      if (elements.inputTargetHost) {
        elements.inputTargetHost.value = (state.targetConfig && state.targetConfig.fullUrl) || state.apiBaseUrl || '';
        elements.inputTargetHost.focus();
      }
      if (state.connectionStrategy === 'direct') {
        if (elements.radioStrategyDirect) elements.radioStrategyDirect.checked = true;
      } else {
        if (elements.radioStrategyProxy) elements.radioStrategyProxy.checked = true;
      }
      if (elements.targetHostStatus) {
        elements.targetHostStatus.classList.add('hidden');
      }
      renderTargetCapabilities();
    });
  }

  const closeTargetModal = () => {
    if (elements.targetHostModal) {
      elements.targetHostModal.classList.add('hidden');
    }
  };

  if (elements.btnCloseTargetHost) {
    elements.btnCloseTargetHost.addEventListener('click', closeTargetModal);
  }
  if (elements.btnCancelTargetHost) {
    elements.btnCancelTargetHost.addEventListener('click', closeTargetModal);
  }
  if (elements.targetHostModal) {
    elements.targetHostModal.addEventListener('click', (e) => {
      if (e.target === elements.targetHostModal) {
        closeTargetModal();
      }
    });
  }

  const applyTargetHost = (newTarget, strategy) => {
    state.capabilitiesDetected = false;
    setTargetUrlInLocation(newTarget, strategy);
    updateTargetHostDisplay();
    renderTargetCapabilities();
    closeTargetModal();
    loadData();
    loadVersionInfo();
  };

  if (elements.btnSaveTargetHost) {
    elements.btnSaveTargetHost.addEventListener('click', () => {
      let val = (elements.inputTargetHost ? elements.inputTargetHost.value : '').trim();
      if (val && !val.startsWith('http://') && !val.startsWith('https://')) {
        val = `http://${val}`;
      }
      const isDirect = elements.radioStrategyDirect && elements.radioStrategyDirect.checked;
      const strategy = isDirect ? 'direct' : 'proxy';
      applyTargetHost(val, strategy);
    });
  }

  if (elements.btnResetTargetHost) {
    elements.btnResetTargetHost.addEventListener('click', () => {
      applyTargetHost('', 'proxy');
    });
  }

  if (elements.inputTargetHost) {
    elements.inputTargetHost.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        if (elements.btnSaveTargetHost) elements.btnSaveTargetHost.click();
      } else if (e.key === 'Escape') {
        closeTargetModal();
      }
    });
  }
}

// Version Badge Loader
function loadVersionInfo() {
  const badge = document.getElementById('app-version-badge');
  if (!badge) return;
  doFetch('/__admin/ui/version')
    .then(res => res.json())
    .then(data => {
      if (data && data.version) {
        badge.textContent = `v${data.version} (${data.branch}@${data.commit})`;
        badge.title = `WireMock Extensions v${data.version} | Branch: ${data.branch} | Commit: ${data.commit}`;
      }
    })
    .catch(() => {});
}

function boot() {
  updateTargetHostDisplay();
  setupTargetHostControls();
  loadData();
  loadVersionInfo();
  initNetEmTab();
  const { route } = parseHash();
  if (route === 'netem') {
    activateNetEm();
  }
}

// Initial Boot
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', boot);
} else {
  boot();
}

