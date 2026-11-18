/**
 * WireMock Console UI - Application Entry Point (ES Module).
 * Pure Vanilla JS, zero NPM dependencies.
 *
 * Composition root wiring route handlers, feature initializers, and test exports.
 */

import { state, getWireMockUri } from './modules/state.js';
import { elements } from './modules/dom.js';
import { initDialogSystem } from './modules/dialog.js';
import {
  highlightJson,
  formatPayload,
  formatXml,
  minifyXml,
  highlightXml,
  highlightPayload,
  detectFormat,
  validatePayload
} from './modules/highlighter.js';
import {
  discoverEmbeddedPayloads,
  applyNestedPathEdit,
  spliceEmbeddedPayloadEdit,
  buildPolyglotSegments
} from './modules/polyglot-ast.js';
import {
  mountCodeEditor,
  getEditorEngine,
  setEditorEngine,
  isHighlightApiSupported
} from './modules/code-editor.js';
import {
  mountPolyglotViewer,
  renderPolyglotTreeHtml
} from './modules/polyglot-viewer.js';
import {
  initDocumentWorkspace,
  openDocumentWorkspace,
  closeDocumentWorkspace,
  isWorkspaceOpen,
  isWorkspaceDirty,
  getWorkspaceRootText
} from './modules/document-workspace.js';
import {
  parseHash,
  setRoute,
  activateTab,
  applyRouteFromUrl,
  registerRouteHandlers
} from './modules/router.js';
import {
  generateCurl,
  generateJavaStubFor,
  renderStubList,
  selectStub,
  getAllCatalogStubs,
  findCatalogStubById,
  initStubsTab
} from './modules/stubs.js';
import {
  getRequestVerdict,
  renderJournal,
  registerJournalLoader,
  initJournalTab
} from './modules/journal.js';
import {
  registerScenarioFetchers,
  initScenariosTab
} from './modules/scenarios.js';
import {
  applyTesterRouteParams,
  getTesterBody,
  setTesterBody,
  buildTesterShareLink,
  renderLocalHeaderChips,
  executeTesterRequest,
  initTesterTab
} from './modules/tester.js';
import {
  openSettingsModal,
  closeSettingsModal,
  switchSettingsTab,
  updateTargetHostDisplay,
  initTargetConnectionModal
} from './modules/settings.js';
import { setupOpenApiModal } from './modules/openapi.js';
import { initStubLifecycleHandlers } from './modules/lifecycle.js';
import { exportStubs, initBundleImport } from './modules/import-export.js';
import { getVisibleSelectedStubIds } from './modules/bulk-actions.js';
import {
  doFetch,
  registerDemoFallbackHandler,
  enableDemoMode,
  disableDemoMode,
  isDemoModeActive
} from './modules/api.js';
import {
  loadMappings,
  loadJournal,
  loadScenarios,
  loadData
} from './modules/app-loader.js';
import {
  initNetEmTab,
  activateNetEm,
  deactivateNetEm
} from './modules/netem.js';
import {
  initQueues,
  refreshQueues,
  filterDestinations,
  filterMessages
} from './modules/queues.js';
import {
  renderTargetCapabilities,
  applyCapabilityVisibility
} from './modules/capabilities.js';

export function getAllStubs() {
  return getAllCatalogStubs();
}

export {
  loadMappings,
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
  openDocumentWorkspace,
  closeDocumentWorkspace,
  isWorkspaceOpen,
  isWorkspaceDirty,
  getWorkspaceRootText,
  openSettingsModal,
  closeSettingsModal,
  switchSettingsTab,
  getTesterBody,
  setTesterBody,
  buildTesterShareLink,
  getVisibleSelectedStubIds,
  enableDemoMode,
  disableDemoMode,
  isDemoModeActive,
  discoverEmbeddedPayloads,
  validatePayload,
  applyNestedPathEdit,
  spliceEmbeddedPayloadEdit,
  buildPolyglotSegments,
  mountCodeEditor,
  getEditorEngine,
  setEditorEngine,
  isHighlightApiSupported,
  mountPolyglotViewer,
  renderPolyglotTreeHtml,
  formatPayload,
  formatXml,
  minifyXml,
  highlightXml,
  highlightPayload,
  detectFormat,
  initQueues,
  refreshQueues,
  filterDestinations,
  filterMessages
};

// Normalize base pathname with trailing slash for relative neighbor URLs (e.g. ../swagger-ui/)
if (window.location.pathname && !window.location.pathname.endsWith('/')) {
  if (window.history && window.history.replaceState) {
    window.history.replaceState(null, '', window.location.pathname + '/' + window.location.search + window.location.hash);
  }
}

// Wire inter-module callbacks and feature modules
registerDemoFallbackHandler(updateTargetHostDisplay);
registerJournalLoader(loadJournal);
registerScenarioFetchers({ doFetch, loadScenarios });
initDialogSystem();
initDocumentWorkspace();
setupOpenApiModal();
initBundleImport();

// Feature module initializers
initStubsTab();
initStubLifecycleHandlers();
initJournalTab();
initTesterTab();
initScenariosTab();
initTargetConnectionModal({ loadData, loadVersionInfo, onHeadersChanged: renderLocalHeaderChips });
initNetEmTab();
initQueues();

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
  applyTester: (params, forcedProto = null) => {
    applyTesterRouteParams(params, forcedProto);
  },
  applyNetEm: () => {
    activateNetEm();
  },
  applyQueues: () => {
    refreshQueues();
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
    if (target === 'tab-queues') {
      refreshQueues();
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
  refreshQueues();
  const { route } = parseHash();
  if (route === 'netem') {
    activateNetEm();
  } else if (route === 'queues') {
    refreshQueues();
  }
}

// Initial Boot
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', boot);
} else {
  boot();
}
