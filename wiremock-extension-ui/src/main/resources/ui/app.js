/**
 * WireMock Console UI - Application Entry Point (ES Module).
 * Pure Vanilla JS, zero NPM dependencies.
 *
 * Re-exports core API & utilities for test automation compatibility:
 * loadMappings, executeTesterRequest, highlightJson, generateCurl, parseHash.
 */

import { state } from './modules/state.js';
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
  renderStubList,
  selectStub,
  sendStubToTester
} from './modules/stubs.js';
import {
  renderJournal,
  setupJournalAutoRefresh,
  registerJournalLoader
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
  updateFilterCounts
} from './modules/projects.js';
import {
  handleToggleStub,
  handleDeleteStub,
  openStubEditor,
  closeStubEditor,
  saveStubEditor
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
  executeTesterRequest
} from './modules/api.js';

// Exported public API for test suite and external scripts
export {
  loadMappings,
  loadDisabledStubs,
  executeTesterRequest,
  highlightJson,
  generateCurl,
  parseHash,
  setRoute,
  activateTab,
  exportStubs
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
      const match = [...state.currentStubs, ...state.disabledStubs].find(s => s.id === stubId);
      if (match) selectStub(match, false);
    }
  },
  applyJournal: (params) => {
    const q = params.get('q') || '';
    if (elements.journalSearch && elements.journalSearch.value !== q) {
      elements.journalSearch.value = q;
    }
    const unmatched = params.get('unmatched') === 'true';
    if (elements.filterUnmatchedOnly) {
      elements.filterUnmatchedOnly.checked = unmatched;
    }
    state.expandedRequestId = params.get('reqId') || null;
    renderJournal();
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
  }
});

// Event Listeners: Navigation
elements.tabs.forEach(tab => {
  tab.addEventListener('click', () => {
    const target = tab.getAttribute('data-tab') || (tab.dataset && tab.dataset.tab);
    activateTab(target, true);
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
    const stub = [...state.currentStubs, ...state.disabledStubs].find(s => s.id === state.selectedStubId);
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
    const stub = [...state.currentStubs, ...state.disabledStubs].find(s => s.id === state.selectedStubId);
    if (!stub) return;
    const path = getStubUrl(stub.request);
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
      navigator.clipboard.writeText(elements.stubJsonViewer.textContent);
      elements.btnCopyJson.textContent = '✅ Copied!';
      setTimeout(() => elements.btnCopyJson.textContent = '📋 Copy JSON', 1500);
    }
  });
}
if (elements.btnCopyCurl) {
  elements.btnCopyCurl.addEventListener('click', () => {
    if (!state.selectedStubId) return;
    const stub = [...state.currentStubs, ...state.disabledStubs].find(s => s.id === state.selectedStubId);
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
if (elements.filterUnmatchedOnly) {
  elements.filterUnmatchedOnly.addEventListener('change', () => {
    renderJournal();
    setRoute('journal', { unmatched: elements.filterUnmatchedOnly.checked ? 'true' : null }, true);
  });
}
if (elements.journalSearch) {
  elements.journalSearch.addEventListener('input', () => {
    renderJournal();
    setRoute('journal', { q: elements.journalSearch.value.trim() }, true);
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
        .then(() => loadJournal())
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
      navigator.clipboard.writeText(state.lastRawResponseBody);
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

// Initial Boot
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', loadData);
} else {
  loadData();
}
