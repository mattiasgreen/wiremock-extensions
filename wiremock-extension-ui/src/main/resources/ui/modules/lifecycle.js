/**
 * Stub lifecycle operations: enable, disable, toggle, clone, edit, delete.
 * Interacts with /__admin/stubs/* and /__admin/mappings/*.
 */
import { state } from './state.js';
import { elements, escapeHtml } from './dom.js';
import {
  toggleStubState,
  deleteStubMapping,
  saveStubMapping,
  createStubMapping,
  loadData
} from './api.js';
import { selectStub, renderStubList } from './stubs.js';
import { renderProjectSelector } from './projects.js';

let activeEditingStub = null;
let isCloneMode = false;

export function handleToggleStub(stubId) {
  if (!stubId) return;
  return toggleStubState(stubId)
    .then(() => loadData())
    .then(() => {
      // Re-select if it was currently selected
      if (state.selectedStubId === stubId) {
        const found = [...state.currentStubs, ...state.disabledStubs].find(s => s.id === stubId);
        if (found) selectStub(found, false);
      }
    })
    .catch(err => {
      alert('Failed to toggle stub: ' + err.message);
    });
}

export function handleDeleteStub(stubId) {
  if (!stubId) return;
  const found = [...state.currentStubs, ...state.disabledStubs].find(s => s.id === stubId);
  const name = found ? (found.name || stubId) : stubId;
  if (!confirm(`Are you sure you want to permanently delete stub "${name}"?`)) {
    return;
  }

  return deleteStubMapping(stubId)
    .then(() => {
      state.selectedStubIds.delete(stubId);
      if (state.selectedStubId === stubId) {
        state.selectedStubId = null;
        if (elements.stubDetailView) elements.stubDetailView.classList.add('hidden');
        if (elements.stubDetailEmpty) elements.stubDetailEmpty.classList.remove('hidden');
      }
      return loadData();
    })
    .catch(err => {
      alert('Failed to delete stub: ' + err.message);
    });
}

export function openStubEditor(stub, clone = false) {
  const modal = document.getElementById('stub-editor-modal');
  if (!modal) return;

  activeEditingStub = stub;
  isCloneMode = clone;

  const titleEl = document.getElementById('editor-modal-title');
  if (titleEl) {
    titleEl.textContent = clone ? 'Duplicate Stub Mapping' : 'Edit Stub Mapping';
  }

  const nameInput = document.getElementById('editor-name');
  const methodSelect = document.getElementById('editor-method');
  const urlInput = document.getElementById('editor-url');
  const statusInput = document.getElementById('editor-status');
  const priorityInput = document.getElementById('editor-priority');
  const projectInput = document.getElementById('editor-project');
  const tagsInput = document.getElementById('editor-tags');
  const headersText = document.getElementById('editor-headers');
  const bodyText = document.getElementById('editor-body');

  const req = stub ? stub.request : {};
  const res = stub ? stub.response : {};
  const meta = stub ? (stub.metadata || {}) : {};

  if (nameInput) nameInput.value = clone ? `${stub.name || 'Stub'} (Copy)` : (stub.name || '');
  if (methodSelect) methodSelect.value = (req.method || 'GET').toUpperCase();
  if (urlInput) urlInput.value = req.url || req.urlPath || req.urlPathTemplate || req.urlPattern || '/';
  if (statusInput) statusInput.value = (res.status != null) ? res.status : 200;
  if (priorityInput) priorityInput.value = stub.priority || 5;
  if (projectInput) projectInput.value = meta.project || '';
  if (tagsInput) tagsInput.value = Array.isArray(meta.tags) ? meta.tags.join(', ') : (meta.tags || '');

  // Extract response headers
  const headerLines = [];
  if (res.headers) {
    for (const [k, v] of Object.entries(res.headers)) {
      headerLines.push(`${k}: ${v}`);
    }
  }
  if (headersText) headersText.value = headerLines.join('\n');

  // Response body
  let bodyContent = '';
  if (res.body != null) {
    bodyContent = res.body;
  } else if (res.jsonBody != null) {
    bodyContent = JSON.stringify(res.jsonBody, null, 2);
  }
  if (bodyText) bodyText.value = bodyContent;

  modal.classList.remove('hidden');
}

export function closeStubEditor() {
  const modal = document.getElementById('stub-editor-modal');
  if (modal) modal.classList.add('hidden');
  activeEditingStub = null;
  isCloneMode = false;
}

export function saveStubEditor() {
  const nameInput = document.getElementById('editor-name');
  const methodSelect = document.getElementById('editor-method');
  const urlInput = document.getElementById('editor-url');
  const statusInput = document.getElementById('editor-status');
  const priorityInput = document.getElementById('editor-priority');
  const projectInput = document.getElementById('editor-project');
  const tagsInput = document.getElementById('editor-tags');
  const headersText = document.getElementById('editor-headers');
  const bodyText = document.getElementById('editor-body');

  const method = methodSelect ? methodSelect.value : 'GET';
  const url = (urlInput ? urlInput.value.trim() : '') || '/';
  const status = statusInput ? parseInt(statusInput.value, 10) : 200;
  const priority = priorityInput ? parseInt(priorityInput.value, 10) : 5;
  const name = nameInput ? nameInput.value.trim() : '';
  const project = projectInput ? projectInput.value.trim() : '';
  const tags = tagsInput ? tagsInput.value.split(',').map(s => s.trim()).filter(Boolean) : [];

  // Parse headers
  const responseHeaders = {};
  if (headersText && headersText.value.trim()) {
    headersText.value.trim().split('\n').forEach(line => {
      const idx = line.indexOf(':');
      if (idx > 0) {
        responseHeaders[line.substring(0, idx).trim()] = line.substring(idx + 1).trim();
      }
    });
  }

  // Build Request Pattern
  const requestPattern = { method };
  if (url.includes('{') && url.includes('}')) {
    requestPattern.urlPathTemplate = url;
  } else if (url.includes('*') || url.includes('.*')) {
    requestPattern.urlPattern = url;
  } else {
    requestPattern.url = url;
  }

  // Build Response Definition
  const responseDef = { status };
  if (Object.keys(responseHeaders).length > 0) {
    responseDef.headers = responseHeaders;
  }
  const rawBody = bodyText ? bodyText.value : '';
  if (rawBody) {
    try {
      responseDef.jsonBody = JSON.parse(rawBody);
    } catch {
      responseDef.body = rawBody;
    }
  }

  // Preserve existing metadata, update project & tags
  const originalMeta = activeEditingStub && activeEditingStub.metadata ? { ...activeEditingStub.metadata } : {};
  if (project) originalMeta.project = project;
  if (tags.length > 0) originalMeta.tags = tags;

  const stubPayload = {
    name: name || undefined,
    priority,
    request: requestPattern,
    response: responseDef,
    metadata: originalMeta
  };

  const savePromise = (isCloneMode || !activeEditingStub || !activeEditingStub.id)
    ? createStubMapping(stubPayload)
    : saveStubMapping(activeEditingStub.id, { ...stubPayload, id: activeEditingStub.id });

  return savePromise
    .then(saved => {
      closeStubEditor();
      return loadData().then(() => {
        if (saved && saved.id) {
          const found = [...state.currentStubs, ...state.disabledStubs].find(s => s.id === saved.id);
          if (found) selectStub(found, true);
        }
      });
    })
    .catch(err => {
      alert('Failed to save stub mapping: ' + err.message);
    });
}
