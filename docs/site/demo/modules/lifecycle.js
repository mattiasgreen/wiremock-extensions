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

export const STUB_TEMPLATES = {
  'uri-get': {
    name: 'Get Item List (URI Match)',
    priority: 5,
    method: 'GET',
    url: '/api/v1/items',
    bodyMatchType: 'none',
    bodyMatchPattern: '',
    status: 200,
    headers: 'Content-Type: application/json',
    body: JSON.stringify({
      items: [
        { id: 1, name: 'Item Alpha' },
        { id: 2, name: 'Item Beta' }
      ]
    }, null, 2)
  },
  'body-contains': {
    name: 'Submit Message (Body Contains)',
    priority: 5,
    method: 'POST',
    url: '/api/v1/messages',
    bodyMatchType: 'contains',
    bodyMatchPattern: 'string X',
    status: 200,
    headers: 'Content-Type: application/json',
    body: JSON.stringify({
      status: 'received',
      matched: 'body contains string X'
    }, null, 2)
  },
  'body-json': {
    name: 'Create Order (JSON Match)',
    priority: 5,
    method: 'POST',
    url: '/api/v1/orders',
    bodyMatchType: 'equalToJson',
    bodyMatchPattern: JSON.stringify({
      item: 'widget',
      quantity: 1
    }, null, 2),
    status: 201,
    headers: 'Content-Type: application/json',
    body: JSON.stringify({
      id: 101,
      status: 'created',
      item: 'widget'
    }, null, 2)
  },
  'url-pattern': {
    name: 'Get Item by ID (Regex Pattern)',
    priority: 5,
    method: 'GET',
    url: '/api/v1/items/[0-9]+',
    bodyMatchType: 'none',
    bodyMatchPattern: '',
    status: 200,
    headers: 'Content-Type: application/json',
    body: JSON.stringify({
      id: 1,
      name: 'Dynamic Item'
    }, null, 2)
  },
  'error-response': {
    name: 'Resource Not Found (404)',
    priority: 10,
    method: 'GET',
    url: '/api/v1/missing',
    bodyMatchType: 'none',
    bodyMatchPattern: '',
    status: 404,
    headers: 'Content-Type: application/json',
    body: JSON.stringify({
      error: 'Not Found',
      message: 'The requested resource does not exist'
    }, null, 2)
  }
};

export function updateBodyMatchLabel(type) {
  const label = document.getElementById('editor-body-match-label');
  const input = document.getElementById('editor-body-match-pattern');
  if (!label || !input) return;

  switch (type) {
    case 'contains':
      label.textContent = 'Expected Substring ("contains"):';
      input.placeholder = 'e.g. string X';
      break;
    case 'equalToJson':
      label.textContent = 'Expected JSON Body ("equalToJson"):';
      input.placeholder = '{\n  "key": "value"\n}';
      break;
    case 'equalTo':
      label.textContent = 'Exact Expected Body ("equalTo"):';
      input.placeholder = 'exact string content';
      break;
    case 'matchesJsonPath':
      label.textContent = 'JSONPath Expression ("matchesJsonPath"):';
      input.placeholder = '$.item';
      break;
    default:
      label.textContent = 'Expected Request Body Pattern:';
      input.placeholder = 'Expected pattern...';
      break;
  }
}

export function applyStubTemplate(templateKey) {
  const tpl = STUB_TEMPLATES[templateKey];
  if (!tpl) return;

  const nameInput = document.getElementById('editor-name');
  const methodSelect = document.getElementById('editor-method');
  const urlInput = document.getElementById('editor-url');
  const statusInput = document.getElementById('editor-status');
  const priorityInput = document.getElementById('editor-priority');
  const headersText = document.getElementById('editor-headers');
  const bodyText = document.getElementById('editor-body');
  const bodyMatchTypeSelect = document.getElementById('editor-body-match-type');
  const bodyMatchPatternInput = document.getElementById('editor-body-match-pattern');
  const bodyMatchContainer = document.getElementById('editor-body-match-container');

  if (nameInput) nameInput.value = tpl.name;
  if (methodSelect) methodSelect.value = tpl.method;
  if (urlInput) urlInput.value = tpl.url;
  if (statusInput) statusInput.value = tpl.status;
  if (priorityInput) priorityInput.value = tpl.priority;
  if (headersText) headersText.value = tpl.headers;
  if (bodyText) bodyText.value = tpl.body;

  if (bodyMatchTypeSelect) {
    bodyMatchTypeSelect.value = tpl.bodyMatchType;
  }
  if (bodyMatchPatternInput) {
    bodyMatchPatternInput.value = tpl.bodyMatchPattern;
  }
  if (bodyMatchContainer) {
    if (tpl.bodyMatchType === 'none') {
      bodyMatchContainer.classList.add('hidden');
    } else {
      bodyMatchContainer.classList.remove('hidden');
    }
  }
  updateBodyMatchLabel(tpl.bodyMatchType);
}

export function openStubEditor(stub, clone = false) {
  const modal = document.getElementById('stub-editor-modal');
  if (!modal) return;

  activeEditingStub = stub;
  isCloneMode = clone;

  const titleEl = document.getElementById('editor-modal-title');
  if (titleEl) {
    titleEl.textContent = clone ? 'Duplicate Stub Mapping' : (stub ? 'Edit Stub Mapping' : 'Create New Stub');
  }

  const openApiBanner = document.getElementById('editor-openapi-banner');
  const templateGroup = document.getElementById('editor-template-group');
  const templateSelect = document.getElementById('editor-template-select');
  const bodyMatchTypeSelect = document.getElementById('editor-body-match-type');
  const bodyMatchPatternInput = document.getElementById('editor-body-match-pattern');
  const bodyMatchContainer = document.getElementById('editor-body-match-container');

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
  const activeProj = (state.activeProjectFilter && state.activeProjectFilter !== '*' && state.activeProjectFilter !== '_all')
    ? state.activeProjectFilter
    : '';

  if (!stub) {
    // New stub creation: show OpenAPI guidance & template presets
    if (openApiBanner) openApiBanner.classList.remove('hidden');
    if (templateGroup) templateGroup.classList.remove('hidden');
    if (templateSelect) templateSelect.value = 'uri-get';

    // Populate with uri-get template
    applyStubTemplate('uri-get');
    if (projectInput) projectInput.value = activeProj;
  } else {
    // Editing or duplicating existing stub: hide OpenAPI banner and template presets
    if (openApiBanner) openApiBanner.classList.add('hidden');
    if (templateGroup) templateGroup.classList.add('hidden');
    if (templateSelect) templateSelect.value = 'custom';

    if (nameInput) nameInput.value = clone ? `${stub.name || 'Stub'} (Copy)` : (stub.name || '');
    if (methodSelect) methodSelect.value = (req.method || 'GET').toUpperCase();
    if (urlInput) urlInput.value = stub ? (req.url || req.urlPath || req.urlPathTemplate || req.urlPattern || '/') : '/';
    if (statusInput) statusInput.value = (res.status != null) ? res.status : 200;
    if (priorityInput) priorityInput.value = (stub && stub.priority) ? stub.priority : 5;
    if (projectInput) projectInput.value = meta.project || activeProj;
    if (tagsInput) tagsInput.value = Array.isArray(meta.tags) ? meta.tags.join(', ') : (meta.tags || '');

    // Extract request body patterns
    let matchType = 'none';
    let matchPattern = '';
    if (req.bodyPatterns && req.bodyPatterns.length > 0) {
      const bp = req.bodyPatterns[0];
      if (bp.contains) {
        matchType = 'contains';
        matchPattern = bp.contains;
      } else if (bp.equalToJson) {
        matchType = 'equalToJson';
        matchPattern = typeof bp.equalToJson === 'string' ? bp.equalToJson : JSON.stringify(bp.equalToJson, null, 2);
      } else if (bp.equalTo) {
        matchType = 'equalTo';
        matchPattern = bp.equalTo;
      } else if (bp.matchesJsonPath) {
        matchType = 'matchesJsonPath';
        matchPattern = bp.matchesJsonPath;
      }
    }

    if (bodyMatchTypeSelect) bodyMatchTypeSelect.value = matchType;
    if (bodyMatchPatternInput) bodyMatchPatternInput.value = matchPattern;
    if (bodyMatchContainer) {
      if (matchType === 'none') {
        bodyMatchContainer.classList.add('hidden');
      } else {
        bodyMatchContainer.classList.remove('hidden');
      }
    }
    updateBodyMatchLabel(matchType);

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
  }

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
  const bodyMatchTypeSelect = document.getElementById('editor-body-match-type');
  const bodyMatchPatternInput = document.getElementById('editor-body-match-pattern');

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

  // Preserve existing request headers/queryParameters/cookies if editing
  if (activeEditingStub && !isCloneMode && activeEditingStub.request) {
    if (activeEditingStub.request.headers) {
      requestPattern.headers = activeEditingStub.request.headers;
    }
    if (activeEditingStub.request.queryParameters) {
      requestPattern.queryParameters = activeEditingStub.request.queryParameters;
    }
    if (activeEditingStub.request.cookies) {
      requestPattern.cookies = activeEditingStub.request.cookies;
    }
  }

  // Request body matching
  const bodyMatchType = bodyMatchTypeSelect ? bodyMatchTypeSelect.value : 'none';
  const bodyMatchPattern = bodyMatchPatternInput ? bodyMatchPatternInput.value : '';

  if (bodyMatchType !== 'none' && bodyMatchPattern.trim()) {
    if (bodyMatchType === 'contains') {
      requestPattern.bodyPatterns = [{ contains: bodyMatchPattern.trim() }];
    } else if (bodyMatchType === 'equalTo') {
      requestPattern.bodyPatterns = [{ equalTo: bodyMatchPattern }];
    } else if (bodyMatchType === 'equalToJson') {
      requestPattern.bodyPatterns = [{
        equalToJson: bodyMatchPattern.trim(),
        ignoreArrayOrder: true,
        ignoreExtraElements: true
      }];
    } else if (bodyMatchType === 'matchesJsonPath') {
      requestPattern.bodyPatterns = [{ matchesJsonPath: bodyMatchPattern.trim() }];
    }
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

  // Preserve scenario attributes if present
  if (activeEditingStub && !isCloneMode) {
    if (activeEditingStub.scenarioName) stubPayload.scenarioName = activeEditingStub.scenarioName;
    if (activeEditingStub.requiredScenarioState) stubPayload.requiredScenarioState = activeEditingStub.requiredScenarioState;
    if (activeEditingStub.newScenarioState) stubPayload.newScenarioState = activeEditingStub.newScenarioState;
  }

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
