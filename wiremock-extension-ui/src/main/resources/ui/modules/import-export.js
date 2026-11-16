/**
 * Import & Export Hub: WireMock JSON bundle export/import and unified import modal.
 * Endpoint: POST /__admin/mappings/import
 */
import { state, uiActions } from './state.js';
import { elements } from './dom.js';
import { showToast } from './dialog.js';
import { importMappings } from './api.js';
import { getStubProject } from './stub-catalog.js';
import { validatePayload } from './highlighter.js';
import { mountPolyglotViewer } from './polyglot-viewer.js';
import { openDocumentWorkspace } from './document-workspace.js';

let currentBundleText = '';
let bundleViewer = null;
let bundleUiBound = false;

function updateBundleLintPill() {
  const pill = elements.bundleContentLintPill || document.getElementById('bundle-content-lint-pill');
  if (!pill) return;
  const trimmed = currentBundleText.trim();
  if (!trimmed) {
    pill.textContent = '';
    pill.classList.add('hidden');
    return;
  }
  const res = validatePayload(trimmed, 'json');
  if (!res.valid) {
    pill.textContent = `⚠️ ${res.errorMessage || 'Invalid JSON'}`;
    pill.classList.remove('hidden');
  } else {
    pill.textContent = '';
    pill.classList.add('hidden');
  }
}

function openBundleWorkspace(startInEditor = true) {
  openDocumentWorkspace({
    title: 'WireMock JSON Bundle',
    text: currentBundleText,
    format: 'json',
    editable: true,
    startInEditor,
    saveLabel: '✓ Apply Bundle',
    onCommit: (nextText) => {
      setBundleContent(nextText);
      return true;
    }
  });
}

function ensureBundleViewer() {
  const host = elements.bundleContentViewer || document.getElementById('bundle-content-viewer');
  if (!host) return null;
  if (!bundleViewer) {
    bundleViewer = mountPolyglotViewer(host, {
      text: currentBundleText,
      mode: 'rich',
      editable: false,
      onOpenWorkspace: () => openBundleWorkspace(false)
    });
  }
  return bundleViewer;
}

export function setBundleContent(text) {
  currentBundleText = typeof text === 'string' ? text : String(text ?? '');
  const viewer = ensureBundleViewer();
  if (viewer) {
    viewer.update({ text: currentBundleText });
  }
  updateBundleLintPill();
}

export function getBundleContent() {
  return currentBundleText;
}

export function initBundleImport() {
  ensureBundleViewer();
  if (bundleUiBound) return;
  bundleUiBound = true;

  const editBtn = elements.btnBundleEditWorkspace || document.getElementById('btn-bundle-edit-workspace');
  if (editBtn) {
    editBtn.addEventListener('click', () => openBundleWorkspace(true));
  }

  const cancelBtn = elements.btnCancelBundleImport || document.getElementById('btn-cancel-bundle-import');
  if (cancelBtn) {
    cancelBtn.addEventListener('click', closeImportModal);
  }

  const browseBtn = elements.btnBrowseBundleFile || document.getElementById('btn-browse-bundle-file');
  const fileInput = elements.bundleFileInput || document.getElementById('bundle-file-input');
  if (browseBtn && fileInput) {
    browseBtn.addEventListener('click', () => fileInput.click());
    fileInput.addEventListener('change', (e) => {
      const file = e.target.files && e.target.files[0];
      if (file) {
        const reader = new FileReader();
        reader.onload = (event) => {
          setBundleContent(event.target.result || '');
        };
        reader.readAsText(file);
      }
    });
  }

  const dropzone = elements.bundleDropzone || document.getElementById('bundle-dropzone');
  if (dropzone) {
    dropzone.addEventListener('dragover', (e) => {
      e.preventDefault();
      dropzone.classList.add('dragover');
    });
    dropzone.addEventListener('dragleave', () => {
      dropzone.classList.remove('dragover');
    });
    dropzone.addEventListener('drop', (e) => {
      e.preventDefault();
      dropzone.classList.remove('dragover');
      if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
        const file = e.dataTransfer.files[0];
        const reader = new FileReader();
        reader.onload = (event) => {
          setBundleContent(event.target.result || '');
        };
        reader.readAsText(file);
      }
    });
  }
}

export function exportStubs(scope = 'all', explicitIds = null) {
  let list = [];
  let filename = 'wiremock-stubs.json';

  const allStubs = state.currentStubs || [];

  if (scope === 'selected') {
    const idSet = Array.isArray(explicitIds) ? new Set(explicitIds) : state.selectedStubIds;
    list = allStubs.filter(s => idSet.has(s.id));
    filename = `wiremock-stubs-selected-${list.length}.json`;
  } else if (scope === 'project' && state.selectedProject) {
    list = allStubs.filter(s => getStubProject(s) === state.selectedProject);
    const safeProj = state.selectedProject.toLowerCase().replace(/[^a-z0-9_-]/g, '-');
    filename = `wiremock-stubs-${safeProj}.json`;
  } else {
    list = allStubs;
    filename = 'wiremock-stubs-all.json';
  }

  if (list.length === 0) {
    showToast('No stubs to export.', { type: 'warning' });
    return;
  }

  const bundle = {
    mappings: list,
    meta: {
      exportedAt: new Date().toISOString(),
      count: list.length,
      scope: scope
    }
  };

  const jsonStr = JSON.stringify(bundle, null, 2);
  const blob = new Blob([jsonStr], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

export function handleBundleImport() {
  const policyEl = document.getElementById('bundle-conflict-policy');
  const projectEl = document.getElementById('bundle-target-project');
  const feedbackEl = document.getElementById('bundle-import-feedback');
  const submitBtn = document.getElementById('btn-submit-bundle-import');

  const rawJson = currentBundleText.trim();
  if (!rawJson) {
    if (feedbackEl) {
      feedbackEl.textContent = 'Please paste or select a WireMock JSON bundle.';
      feedbackEl.className = 'modal-feedback error';
    }
    return;
  }

  let parsed;
  try {
    parsed = JSON.parse(rawJson);
  } catch (err) {
    if (feedbackEl) {
      feedbackEl.textContent = 'Invalid JSON: ' + err.message;
      feedbackEl.className = 'modal-feedback error';
    }
    return;
  }

  let mappings = [];
  if (Array.isArray(parsed)) {
    mappings = parsed;
  } else if (parsed.mappings && Array.isArray(parsed.mappings)) {
    mappings = parsed.mappings;
  } else if (parsed.request) {
    mappings = [parsed];
  } else {
    if (feedbackEl) {
      feedbackEl.textContent = 'JSON does not contain WireMock stub mappings.';
      feedbackEl.className = 'modal-feedback error';
    }
    return;
  }

  const targetProject = projectEl ? projectEl.value.trim() : '';
  const duplicatePolicy = policyEl ? policyEl.value : 'OVERWRITE';

  mappings = mappings.map(stub => {
    const copy = { ...stub };
    if (targetProject) {
      copy.metadata = { ...(copy.metadata || {}), project: targetProject };
    }
    if (duplicatePolicy === 'APPEND') {
      copy.id = crypto.randomUUID ? crypto.randomUUID() : undefined;
    }
    return copy;
  });

  const payload = {
    mappings: mappings,
    importOptions: {
      duplicatePolicy: duplicatePolicy === 'APPEND' ? 'OVERWRITE' : duplicatePolicy,
      deleteAllNotInImport: false
    }
  };

  if (submitBtn) {
    submitBtn.disabled = true;
    submitBtn.textContent = '⏳ Importing...';
  }

  importMappings(payload)
    .then(() => (uiActions.loadData ? uiActions.loadData() : Promise.resolve()))
    .then(() => {
      if (feedbackEl) {
        feedbackEl.textContent = `Successfully imported ${mappings.length} mappings!`;
        feedbackEl.className = 'modal-feedback success';
      }
      showToast(`Imported ${mappings.length} stub mapping(s)`, { type: 'success' });
      closeImportModal();
    })
    .catch(err => {
      if (feedbackEl) {
        feedbackEl.textContent = 'Import failed: ' + err.message;
        feedbackEl.className = 'modal-feedback error';
      }
    })
    .finally(() => {
      if (submitBtn) {
        submitBtn.disabled = false;
        submitBtn.textContent = '📥 Import Stubs';
      }
    });
}

export function openImportModal(initialTab = 'openapi') {
  initBundleImport();
  const modal = document.getElementById('openapi-modal');
  if (modal) {
    modal.classList.remove('hidden');
    switchImportTab(initialTab);
  }
}

export function closeImportModal() {
  const modal = document.getElementById('openapi-modal');
  if (modal) modal.classList.add('hidden');
  setBundleContent('');
  const bundleFeedback = document.getElementById('bundle-import-feedback');
  if (bundleFeedback) {
    bundleFeedback.textContent = '';
    bundleFeedback.className = 'modal-feedback hidden';
  }
}

export function switchImportTab(tabName) {
  const tabOpenApi = document.getElementById('import-tab-openapi');
  const tabBundle = document.getElementById('import-tab-bundle');
  const viewOpenApi = document.getElementById('import-view-openapi');
  const viewBundle = document.getElementById('import-view-bundle');

  if (tabName === 'bundle') {
    if (tabBundle) tabBundle.classList.add('active');
    if (tabOpenApi) tabOpenApi.classList.remove('active');
    if (viewBundle) viewBundle.classList.remove('hidden');
    if (viewOpenApi) viewOpenApi.classList.add('hidden');
  } else {
    if (tabOpenApi) tabOpenApi.classList.add('active');
    if (tabBundle) tabBundle.classList.remove('active');
    if (viewOpenApi) viewOpenApi.classList.remove('hidden');
    if (viewBundle) viewBundle.classList.add('hidden');
  }
}
