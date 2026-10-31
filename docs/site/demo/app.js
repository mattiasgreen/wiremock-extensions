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
  sendStubToTester,
  getAllCatalogStubs,
  findCatalogStubById,
  initStubsTab
} from './modules/stubs.js';
import {
  getRequestVerdict,
  renderJournal,
  setupJournalAutoRefresh,
  registerJournalLoader,
  getFilteredProxiedRequests,
  initJournalTab
} from './modules/journal.js';
import {
  renderScenarios,
  registerScenarioFetchers,
  initScenariosTab
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
  toggleTesterHistory,
  renderLocalHeaderChips,
  initTesterTab
} from './modules/tester.js';
import {
  openLocalHeadersModal,
  closeLocalHeadersModal,
  switchSettingsTab,
  addLocalHeaderRow,
  saveSettingsFromModal,
  copySettingsConfigToClipboard,
  applyPastedSettingsFromModal,
  resetDefaultSettingsInModal,
  setLocalHeadersEnabled,
  loadExcludedJsonPaths,
  updateTargetHostDisplay,
  initTargetConnectionModal
} from './modules/settings.js';
import {
  initCasualTester,
  executeCasualRequest,
  formatCasualJsonPayload,
  resetCasualForm,
  setCasualViewMode,
  setCasualInspectorTab,
  appendCasualHeader,
  clearCasualHistory,
  toggleCasualHistory,
  setCasualHistoryCollapsed,
  updateCasualRoute,
  handleCasualFileUpload,
  downloadCasualBinaryResponse
} from './modules/casual-tester.js';
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
  updateBodyMatchLabel,
  setEditorMode,
  formatRawEditorJson,
  formatRequestBodyJson,
  formatResponseBodyJson,
  initStubLifecycleHandlers
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
  snapshotFilteredRecordings,
  demoBackend
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
import {
  initPayloadLensModal,
  openPayloadLens,
  closePayloadLens,
  discoverEmbeddedPayloads,
  validatePayload,
  applyNestedPayloadEdit,
  cascadeLensStackToRoot,
  renderPayloadViewer
} from './modules/payload-lens.js';
import {
  formatPayload,
  formatXml,
  minifyXml,
  highlightXml,
  highlightPayload,
  detectFormat
} from './modules/highlighter.js';

export function getAllStubs() {
  return getAllCatalogStubs();
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
  getWireMockUri,
  updateTargetHostDisplay,
  initPayloadLensModal,
  openPayloadLens,
  closePayloadLens,
  discoverEmbeddedPayloads,
  validatePayload,
  applyNestedPayloadEdit,
  cascadeLensStackToRoot,
  renderPayloadViewer,
  formatPayload,
  formatXml,
  minifyXml,
  highlightXml,
  highlightPayload,
  detectFormat,
  demoBackend
};

// Normalize base pathname with trailing slash for relative neighbor URLs (e.g. ../swagger-ui/)
if (window.location.pathname && !window.location.pathname.endsWith('/')) {
  if (window.history && window.history.replaceState) {
    window.history.replaceState(null, '', window.location.pathname + '/' + window.location.search + window.location.hash);
  }
}

// Wire inter-module callbacks and feature modules
registerJournalLoader(loadJournal);
registerScenarioFetchers({ doFetch, loadScenarios });
setupOpenApiModal();
setupProjectLifecycleBar(loadData);
setupDynamicStubModal();

// Feature module initializers
initStubsTab();
initStubLifecycleHandlers();
initJournalTab();
initTesterTab();
initCasualTester();
initScenariosTab();
initTargetConnectionModal({ loadData, loadVersionInfo });
initNetEmTab();
initPayloadLensModal();

registerRouteHandlers({
  applyStubs: (params) => {
    const q = params.get('q') || '';
    if (elements.searchBox && elements.searchBox.value !== q) {
      elements.searchBox.value = q;
    }
    renderStubList();
    const stubId = params.get('stubId');
    if (stubId) {
      const match = findCatalogStubById(stubId);
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
  applyCasual: (params) => {
    const s = params.get('s');
    if (s && elements.casualServiceName) elements.casualServiceName.value = s;
    const b = params.get('b');
    if (b && elements.casualBufferType) elements.casualBufferType.value = b;
    const p = params.get('p');
    if (p && elements.casualRequestPayload) elements.casualRequestPayload.value = fromBase64(p);
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
  renderLocalHeaderChips();
  loadData();
  loadVersionInfo();
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
