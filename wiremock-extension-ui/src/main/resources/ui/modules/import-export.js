/**
 * Import & Export Hub: WireMock JSON bundle export/import and unified import modal.
 * Endpoint: POST /__admin/mappings/import
 */
import { state } from './state.js';
import { elements } from './dom.js';
import { importMappings, loadData } from './api.js';
import { getStubProject } from './projects.js';

export function exportStubs(scope = 'all') {
  let list = [];
  let filename = 'wiremock-stubs.json';

  const allStubs = [...state.currentStubs, ...state.disabledStubs];

  if (scope === 'selected') {
    list = allStubs.filter(s => state.selectedStubIds.has(s.id));
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
    alert('No stubs to export.');
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
  const contentEl = document.getElementById('bundle-content');
  const policyEl = document.getElementById('bundle-conflict-policy');
  const projectEl = document.getElementById('bundle-target-project');
  const feedbackEl = document.getElementById('bundle-import-feedback');
  const submitBtn = document.getElementById('btn-submit-bundle-import');

  const rawJson = contentEl ? contentEl.value.trim() : '';
  if (!rawJson) {
    if (feedbackEl) {
      feedbackEl.textContent = 'Please paste or select a WireMock JSON bundle.';
      feedbackEl.className = 'import-feedback error';
    }
    return;
  }

  let parsed;
  try {
    parsed = JSON.parse(rawJson);
  } catch (err) {
    if (feedbackEl) {
      feedbackEl.textContent = 'Invalid JSON: ' + err.message;
      feedbackEl.className = 'import-feedback error';
    }
    return;
  }

  let mappings = [];
  if (Array.isArray(parsed)) {
    mappings = parsed;
  } else if (parsed.mappings && Array.isArray(parsed.mappings)) {
    mappings = parsed.mappings;
  } else if (parsed.request) {
    // Single stub mapping
    mappings = [parsed];
  } else {
    if (feedbackEl) {
      feedbackEl.textContent = 'JSON does not contain WireMock stub mappings.';
      feedbackEl.className = 'import-feedback error';
    }
    return;
  }

  const targetProject = projectEl ? projectEl.value.trim() : '';
  const duplicatePolicy = policyEl ? policyEl.value : 'OVERWRITE';

  // Apply target project and handle APPEND (generate new UUIDs)
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
    .then(() => loadData())
    .then(() => {
      if (feedbackEl) {
        feedbackEl.textContent = `Successfully imported ${mappings.length} mappings!`;
        feedbackEl.className = 'import-feedback success';
      }
      closeImportModal();
    })
    .catch(err => {
      if (feedbackEl) {
        feedbackEl.textContent = 'Import failed: ' + err.message;
        feedbackEl.className = 'import-feedback error';
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
  const modal = document.getElementById('openapi-modal');
  if (modal) {
    modal.classList.remove('hidden');
    switchImportTab(initialTab);
  }
}

export function closeImportModal() {
  const modal = document.getElementById('openapi-modal');
  if (modal) modal.classList.add('hidden');
  const bundleContent = document.getElementById('bundle-content');
  if (bundleContent) bundleContent.value = '';
  const bundleFeedback = document.getElementById('bundle-import-feedback');
  if (bundleFeedback) {
    bundleFeedback.textContent = '';
    bundleFeedback.className = 'import-feedback';
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
