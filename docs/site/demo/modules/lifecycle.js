/**
 * Stub lifecycle operations: enable, disable, toggle, clone, edit, delete.
 * Interacts with /__admin/stubs/* and /__admin/mappings/*.
 */
import { state } from './state.js';
import { elements, escapeHtml } from './dom.js';
import { normalizeJsonPayload } from './base64.js';
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
let activeEditorMode = 'form';

export function getEditorMode() {
  return activeEditorMode;
}

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
    }, null, 2),
    proxyBaseUrl: ''
  },
  'proxy-route': {
    name: 'Live Service Proxy (ERP System)',
    priority: 10,
    method: 'ANY',
    url: '/api/v1/erp/.*',
    bodyMatchType: 'none',
    bodyMatchPattern: '',
    status: 200,
    headers: '',
    body: '',
    proxyBaseUrl: 'http://erp-system:8089',
    project: 'ERP System'
  }
};

export function updateBodyMatchLabel(type) {
  const label = document.getElementById('editor-body-match-label');
  const input = document.getElementById('editor-body-match-pattern');
  const formatBtn = document.getElementById('btn-editor-format-request-json');
  const optionsDiv = document.getElementById('editor-json-match-options');

  if (type === 'equalToJson') {
    if (formatBtn) formatBtn.classList.remove('hidden');
    if (optionsDiv) optionsDiv.classList.remove('hidden');
  } else {
    if (formatBtn) formatBtn.classList.add('hidden');
    if (optionsDiv) optionsDiv.classList.add('hidden');
  }

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

export function formatRequestBodyJson() {
  const input = document.getElementById('editor-body-match-pattern');
  if (!input || !input.value.trim()) return;
  const normalized = normalizeJsonPayload(input.value);
  if (normalized !== null) {
    input.value = JSON.stringify(normalized, null, 2);
  } else {
    try {
      const parsed = JSON.parse(input.value);
      input.value = JSON.stringify(parsed, null, 2);
    } catch (err) {
      alert('Invalid JSON in request body matcher: ' + err.message);
    }
  }
}

export function formatResponseBodyJson() {
  const input = document.getElementById('editor-body');
  if (!input || !input.value.trim()) return;
  const normalized = normalizeJsonPayload(input.value);
  if (normalized !== null) {
    input.value = JSON.stringify(normalized, null, 2);
  } else {
    try {
      const parsed = JSON.parse(input.value);
      input.value = JSON.stringify(parsed, null, 2);
    } catch (err) {
      alert('Invalid JSON in response body: ' + err.message);
    }
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
  const projectInput = document.getElementById('editor-project');
  const proxyUrlInput = document.getElementById('editor-proxy-url');
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
  if (projectInput && tpl.project) projectInput.value = tpl.project;
  if (proxyUrlInput) proxyUrlInput.value = tpl.proxyBaseUrl || '';
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

export function openStubEditorFromRequest(reqObj) {
  if (!reqObj) return;
  const method = (reqObj.method || 'GET').toUpperCase();
  const url = reqObj.url || '/';
  const rawBody = reqObj.body || '';
  const bodyPatterns = [];
  if (rawBody.trim()) {
    const normalized = normalizeJsonPayload(rawBody);
    if (normalized !== null) {
      bodyPatterns.push({
        equalToJson: normalized,
        ignoreArrayOrder: true,
        ignoreExtraElements: true
      });
    } else {
      bodyPatterns.push({ equalTo: rawBody });
    }
  }
  const syntheticStub = {
    name: `${method} ${url}`,
    priority: 5,
    request: {
      method,
      url,
      bodyPatterns: bodyPatterns.length > 0 ? bodyPatterns : undefined
    },
    response: {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
      jsonBody: { status: 'ok' }
    },
    metadata: {}
  };
  openStubEditor(syntheticStub, false);
  const titleEl = document.getElementById('editor-modal-title');
  if (titleEl) {
    titleEl.textContent = 'Create Stub from Unmatched Request';
  }
}

export function buildStubFromForm() {
  const nameInput = document.getElementById('editor-name');
  const methodSelect = document.getElementById('editor-method');
  const urlInput = document.getElementById('editor-url');
  const statusInput = document.getElementById('editor-status');
  const priorityInput = document.getElementById('editor-priority');
  const projectInput = document.getElementById('editor-project');
  const tagsInput = document.getElementById('editor-tags');
  const proxyUrlInput = document.getElementById('editor-proxy-url');
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
  const proxyUrl = proxyUrlInput ? proxyUrlInput.value.trim() : '';

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
      let equalToJsonVal = bodyMatchPattern.trim();
      const normalized = normalizeJsonPayload(equalToJsonVal);
      if (normalized !== null) {
        equalToJsonVal = normalized;
      }
      const ignoreExtraEl = document.getElementById('editor-json-ignore-extra');
      const ignoreOrderEl = document.getElementById('editor-json-ignore-order');
      requestPattern.bodyPatterns = [{
        equalToJson: equalToJsonVal,
        ignoreArrayOrder: ignoreOrderEl ? ignoreOrderEl.checked : true,
        ignoreExtraElements: ignoreExtraEl ? ignoreExtraEl.checked : false
      }];
    } else if (bodyMatchType === 'matchesJsonPath') {
      requestPattern.bodyPatterns = [{ matchesJsonPath: bodyMatchPattern.trim() }];
    }
  }

  // Build Response Definition
  const responseDef = { status };
  if (proxyUrl) {
    responseDef.proxyBaseUrl = proxyUrl;
  }
  if (Object.keys(responseHeaders).length > 0) {
    responseDef.headers = responseHeaders;
  }
  const rawBody = bodyText ? bodyText.value : '';
  if (rawBody && !proxyUrl) {
    const normalizedRes = normalizeJsonPayload(rawBody);
    if (normalizedRes !== null) {
      responseDef.jsonBody = normalizedRes;
    } else {
      try {
        responseDef.jsonBody = JSON.parse(rawBody);
      } catch {
        responseDef.body = rawBody;
      }
    }
  }

  // Preserve existing metadata, update project & tags
  const originalMeta = activeEditingStub && activeEditingStub.metadata ? { ...activeEditingStub.metadata } : {};
  if (project) originalMeta.project = project;
  if (tags.length > 0) originalMeta.tags = tags;
  if (proxyUrl) {
    originalMeta.mode = 'proxy';
    originalMeta.proxyBaseUrl = proxyUrl;
  }

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

  return stubPayload;
}

export function populateFormFromStub(stub) {
  if (!stub) return;
  const nameInput = document.getElementById('editor-name');
  const methodSelect = document.getElementById('editor-method');
  const urlInput = document.getElementById('editor-url');
  const statusInput = document.getElementById('editor-status');
  const priorityInput = document.getElementById('editor-priority');
  const projectInput = document.getElementById('editor-project');
  const tagsInput = document.getElementById('editor-tags');
  const proxyUrlInput = document.getElementById('editor-proxy-url');
  const headersText = document.getElementById('editor-headers');
  const bodyText = document.getElementById('editor-body');
  const bodyMatchTypeSelect = document.getElementById('editor-body-match-type');
  const bodyMatchPatternInput = document.getElementById('editor-body-match-pattern');
  const bodyMatchContainer = document.getElementById('editor-body-match-container');

  const req = stub.request || {};
  const res = stub.response || {};
  const meta = stub.metadata || {};

  if (nameInput) nameInput.value = stub.name || '';
  if (methodSelect) methodSelect.value = (req.method || 'GET').toUpperCase();
  if (urlInput) urlInput.value = req.url || req.urlPath || req.urlPathTemplate || req.urlPattern || '/';
  if (statusInput) statusInput.value = (res.status != null) ? res.status : 200;
  if (priorityInput) priorityInput.value = stub.priority || 5;
  if (projectInput) projectInput.value = meta.project || '';
  if (tagsInput) tagsInput.value = Array.isArray(meta.tags) ? meta.tags.join(', ') : (meta.tags || '');
  if (proxyUrlInput) proxyUrlInput.value = res.proxyBaseUrl || meta.proxyBaseUrl || '';

  let matchType = 'none';
  let matchPattern = '';
  let ignoreExtra = true;
  let ignoreOrder = true;
  if (req.bodyPatterns && req.bodyPatterns.length > 0) {
    const bp = req.bodyPatterns[0];
    if (bp.contains) {
      matchType = 'contains';
      matchPattern = bp.contains;
    } else if (bp.equalToJson) {
      matchType = 'equalToJson';
      const normalized = normalizeJsonPayload(bp.equalToJson);
      if (normalized !== null) {
        matchPattern = JSON.stringify(normalized, null, 2);
      } else if (typeof bp.equalToJson === 'string') {
        matchPattern = bp.equalToJson;
      } else {
        matchPattern = JSON.stringify(bp.equalToJson, null, 2);
      }
      if (bp.ignoreExtraElements !== undefined) {
        ignoreExtra = bp.ignoreExtraElements;
      }
      if (bp.ignoreArrayOrder !== undefined) {
        ignoreOrder = bp.ignoreArrayOrder;
      }
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
  const ignoreExtraEl = document.getElementById('editor-json-ignore-extra');
  const ignoreOrderEl = document.getElementById('editor-json-ignore-order');
  if (ignoreExtraEl) ignoreExtraEl.checked = ignoreExtra;
  if (ignoreOrderEl) ignoreOrderEl.checked = ignoreOrder;

  if (bodyMatchContainer) {
    if (matchType === 'none') {
      bodyMatchContainer.classList.add('hidden');
    } else {
      bodyMatchContainer.classList.remove('hidden');
    }
  }
  updateBodyMatchLabel(matchType);

  const headerLines = [];
  if (res.headers) {
    for (const [k, v] of Object.entries(res.headers)) {
      headerLines.push(`${k}: ${v}`);
    }
  }
  if (headersText) headersText.value = headerLines.join('\n');

  let bodyContent = '';
  if (res.jsonBody != null) {
    const normalized = normalizeJsonPayload(res.jsonBody);
    bodyContent = JSON.stringify(normalized || res.jsonBody, null, 2);
  } else if (res.body != null) {
    const normalized = normalizeJsonPayload(res.body);
    if (normalized !== null) {
      bodyContent = JSON.stringify(normalized, null, 2);
    } else {
      bodyContent = res.body;
    }
  }
  if (bodyText) bodyText.value = bodyContent;
}

export function setEditorMode(mode) {
  const formPanel = document.getElementById('editor-form-panel');
  const jsonPanel = document.getElementById('editor-json-panel');
  const btnForm = document.getElementById('btn-editor-mode-form');
  const btnJson = document.getElementById('btn-editor-mode-json');
  const rawTextarea = document.getElementById('editor-raw-json');
  const errorEl = document.getElementById('editor-raw-json-error');

  if (mode === 'json') {
    const stubObj = buildStubFromForm();
    if (rawTextarea) {
      rawTextarea.value = JSON.stringify(stubObj, null, 2);
    }
    if (errorEl) {
      errorEl.classList.add('hidden');
      errorEl.textContent = '';
    }
    if (formPanel) formPanel.classList.add('hidden');
    if (jsonPanel) jsonPanel.classList.remove('hidden');
    if (btnForm) btnForm.classList.remove('active');
    if (btnJson) btnJson.classList.add('active');
    activeEditorMode = 'json';
    return true;
  } else {
    if (rawTextarea && rawTextarea.value.trim()) {
      try {
        const parsed = JSON.parse(rawTextarea.value);
        if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
          throw new Error('Stub mapping root must be a JSON object');
        }
        populateFormFromStub(parsed);
      } catch (err) {
        if (errorEl) {
          errorEl.textContent = 'Invalid JSON: ' + err.message;
          errorEl.classList.remove('hidden');
        }
        return false;
      }
    }
    if (errorEl) {
      errorEl.classList.add('hidden');
      errorEl.textContent = '';
    }
    if (formPanel) formPanel.classList.remove('hidden');
    if (jsonPanel) jsonPanel.classList.add('hidden');
    if (btnForm) btnForm.classList.add('active');
    if (btnJson) btnJson.classList.remove('active');
    activeEditorMode = 'form';
    return true;
  }
}

export function formatRawEditorJson() {
  const rawTextarea = document.getElementById('editor-raw-json');
  const errorEl = document.getElementById('editor-raw-json-error');
  if (!rawTextarea || !rawTextarea.value.trim()) return;
  try {
    const parsed = JSON.parse(rawTextarea.value);
    rawTextarea.value = JSON.stringify(parsed, null, 2);
    if (errorEl) {
      errorEl.classList.add('hidden');
      errorEl.textContent = '';
    }
  } catch (err) {
    if (errorEl) {
      errorEl.textContent = 'Invalid JSON: ' + err.message;
      errorEl.classList.remove('hidden');
    }
    alert('Invalid JSON: ' + err.message);
  }
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
  const projectInput = document.getElementById('editor-project');

  const activeProj = (state.activeProjectFilter && state.activeProjectFilter !== '*' && state.activeProjectFilter !== '_all')
    ? state.activeProjectFilter
    : '';

  if (!stub) {
    if (openApiBanner) openApiBanner.classList.remove('hidden');
    if (templateGroup) templateGroup.classList.remove('hidden');
    if (templateSelect) templateSelect.value = 'uri-get';

    applyStubTemplate('uri-get');
    if (projectInput) projectInput.value = activeProj;
  } else {
    if (openApiBanner) openApiBanner.classList.add('hidden');
    if (templateGroup) templateGroup.classList.add('hidden');
    if (templateSelect) templateSelect.value = 'custom';

    populateFormFromStub(stub);
    if (clone) {
      const nameInput = document.getElementById('editor-name');
      if (nameInput) nameInput.value = `${stub.name || 'Stub'} (Copy)`;
    }
  }

  // Initialize raw JSON textarea with current stub
  const rawTextarea = document.getElementById('editor-raw-json');
  if (rawTextarea) {
    const initialStub = clone
      ? { ...stub, id: undefined, name: `${stub.name || 'Stub'} (Copy)` }
      : (stub || buildStubFromForm());
    rawTextarea.value = JSON.stringify(initialStub, null, 2);
  }

  // Reset to form mode on open without re-parsing rawTextarea
  const formPanel = document.getElementById('editor-form-panel');
  const jsonPanel = document.getElementById('editor-json-panel');
  const btnForm = document.getElementById('btn-editor-mode-form');
  const btnJson = document.getElementById('btn-editor-mode-json');
  const errorEl = document.getElementById('editor-raw-json-error');
  if (errorEl) {
    errorEl.classList.add('hidden');
    errorEl.textContent = '';
  }
  if (formPanel) formPanel.classList.remove('hidden');
  if (jsonPanel) jsonPanel.classList.add('hidden');
  if (btnForm) btnForm.classList.add('active');
  if (btnJson) btnJson.classList.remove('active');
  activeEditorMode = 'form';

  modal.classList.remove('hidden');
}

export function closeStubEditor() {
  const modal = document.getElementById('stub-editor-modal');
  if (modal) modal.classList.add('hidden');
  const rawTextarea = document.getElementById('editor-raw-json');
  if (rawTextarea) rawTextarea.value = '';
  activeEditingStub = null;
  isCloneMode = false;
  activeEditorMode = 'form';
}

export function saveStubEditor() {
  let stubPayload;

  if (activeEditorMode === 'json') {
    const rawTextarea = document.getElementById('editor-raw-json');
    const errorEl = document.getElementById('editor-raw-json-error');
    if (!rawTextarea || !rawTextarea.value.trim()) {
      alert('Raw JSON cannot be empty');
      return;
    }
    try {
      stubPayload = JSON.parse(rawTextarea.value);
      if (typeof stubPayload !== 'object' || stubPayload === null || Array.isArray(stubPayload)) {
        throw new Error('Stub mapping root must be a JSON object');
      }
    } catch (err) {
      if (errorEl) {
        errorEl.textContent = 'Invalid JSON: ' + err.message;
        errorEl.classList.remove('hidden');
      }
      alert('Cannot save: Invalid JSON: ' + err.message);
      return;
    }
  } else {
    stubPayload = buildStubFromForm();
  }

  const targetId = (!isCloneMode && activeEditingStub && activeEditingStub.id)
    ? activeEditingStub.id
    : (stubPayload.id && !isCloneMode ? stubPayload.id : null);

  const isSnapshot = !targetId && stubPayload.metadata && stubPayload.metadata.source === 'recorded-proxy';
  const savePromise = (isCloneMode || !targetId)
    ? createStubMapping(stubPayload).then(async saved => {
        if (isSnapshot && saved && saved.id && state.capabilities.lifecycle) {
          try {
            await toggleStubState(saved.id);
          } catch (_) {}
        }
        return saved;
      })
    : saveStubMapping(targetId, { ...stubPayload, id: targetId });

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

