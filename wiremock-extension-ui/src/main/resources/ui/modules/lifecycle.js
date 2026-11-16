/**
 * WireMock Console — Stub Lifecycle & Document Workspace Integration
 * Handles stub creation, editing, cloning, toggling, deletion, bulk action wiring,
 * and import-modal/welcome-guide entry points.
 */

import { state, uiActions } from './state.js';
import { elements } from './dom.js';
import { confirmDialog, showToast } from './dialog.js';
import { openDocumentWorkspace, closeDocumentWorkspace } from './document-workspace.js';
import { normalizeJsonPayload } from './base64.js';
import {
  toggleStubState,
  deleteStubMapping,
  saveStubMapping,
  createStubMapping
} from './api.js';
import { stubCatalog } from './stub-catalog.js';
import { executeBulkAction } from './bulk-actions.js';
import {
  switchImportTab,
  handleBundleImport,
  openImportModal,
  initBundleImport
} from './import-export.js';

export const STUB_TEMPLATES = {
  'uri-get': {
    name: 'Get Item List (URI Match)',
    priority: 5,
    request: {
      method: 'GET',
      url: '/api/v1/items'
    },
    response: {
      status: 200,
      headers: {
        'Content-Type': 'application/json'
      },
      jsonBody: {
        items: [
          { id: 1, name: 'Item Alpha' },
          { id: 2, name: 'Item Beta' }
        ]
      }
    }
  },
  'body-contains': {
    name: 'Submit Message (Body Contains)',
    priority: 5,
    request: {
      method: 'POST',
      url: '/api/v1/messages',
      bodyPatterns: [
        { contains: 'string X' }
      ]
    },
    response: {
      status: 200,
      headers: {
        'Content-Type': 'application/json'
      },
      jsonBody: {
        status: 'received',
        matched: 'body contains string X'
      }
    }
  },
  'body-json': {
    name: 'Create Order (JSON Match)',
    priority: 5,
    request: {
      method: 'POST',
      url: '/api/v1/orders',
      bodyPatterns: [
        {
          equalToJson: JSON.stringify({
            item: 'widget',
            quantity: 1
          }, null, 2),
          ignoreArrayOrder: true,
          ignoreExtraElements: true
        }
      ]
    },
    response: {
      status: 201,
      headers: {
        'Content-Type': 'application/json'
      },
      jsonBody: {
        orderId: 'ORD-1001',
        status: 'created'
      }
    }
  },
  'url-pattern': {
    name: 'Get Item by ID (Regex Pattern)',
    priority: 5,
    request: {
      method: 'GET',
      urlPattern: '/api/v1/items/[0-9]+'
    },
    response: {
      status: 200,
      headers: {
        'Content-Type': 'application/json'
      },
      jsonBody: {
        id: 1,
        name: 'Dynamic Item'
      }
    }
  },
  'error-response': {
    name: 'Resource Not Found (404)',
    priority: 10,
    request: {
      method: 'GET',
      url: '/api/v1/missing'
    },
    response: {
      status: 404,
      headers: {
        'Content-Type': 'application/json'
      },
      jsonBody: {
        error: 'Not Found',
        message: 'The requested resource does not exist'
      }
    }
  },
  'proxy-route': {
    name: 'Live Service Proxy (ERP System)',
    priority: 10,
    request: {
      method: 'ANY',
      urlPattern: '/api/v1/erp/.*'
    },
    response: {
      proxyBaseUrl: 'http://erp-system:8089'
    },
    metadata: {
      project: 'ERP System'
    }
  },
  'offline-guard': {
    name: 'Offline Catch-All Guard (501)',
    priority: 50,
    request: {
      method: 'ANY',
      urlPattern: '.*'
    },
    response: {
      status: 501,
      headers: {
        'Content-Type': 'application/json'
      },
      jsonBody: {
        error: 'Not Implemented: offline simulation guard'
      }
    },
    metadata: {
      role: 'offline-guard'
    }
  }
};

function cleanStubForWireMock(stub) {
  if (!stub || typeof stub !== 'object') return stub;
  const clean = { ...stub };
  delete clean._disabled;
  return clean;
}

export function buildStubFromTemplate(key, project = '') {
  const base = STUB_TEMPLATES[key] || STUB_TEMPLATES['uri-get'];
  const cloned = JSON.parse(JSON.stringify(base));
  const cleanProject = typeof project === 'string' ? project.trim() : '';
  if (cleanProject) {
    cloned.metadata = {
      ...(cloned.metadata || {}),
      project: cleanProject
    };
  }
  return cloned;
}

function buildWorkspaceTemplates(project = '') {
  return Object.entries(STUB_TEMPLATES).map(([key, tpl]) => ({
    key,
    label: tpl.name || key,
    buildText: () => JSON.stringify(buildStubFromTemplate(key, project), null, 2)
  }));
}

function getActiveProjectFilter() {
  return (state.selectedProject && state.selectedProject !== '*' && state.selectedProject !== '_all')
    ? state.selectedProject
    : '';
}

export function openStubEditor(stub, clone = false, customTitle = null) {
  const activeProj = getActiveProjectFilter();
  let initialStub;

  if (stub && clone) {
    initialStub = cleanStubForWireMock(stub);
    initialStub.name = `${stub.name || 'Stub'} (Copy)`;
    delete initialStub.id;
    delete initialStub.uuid;
  } else if (stub) {
    initialStub = cleanStubForWireMock(stub);
  } else {
    initialStub = buildStubFromTemplate('uri-get', activeProj);
  }

  const title = customTitle || (clone
    ? 'Duplicate Stub Mapping'
    : (stub ? 'Edit Stub Mapping' : 'Create New Stub'));

  openDocumentWorkspace({
    title,
    text: JSON.stringify(initialStub, null, 2),
    format: 'json',
    editable: true,
    saveLabel: '💾 Save Mapping',
    templates: !stub ? buildWorkspaceTemplates(activeProj) : null,
    defaultTemplateKey: 'uri-get',
    onOpenOpenApi: !stub ? () => {
      closeDocumentWorkspace(true);
      openImportModal('openapi');
    } : null,
    onCommit: async (nextText) => {
      if (!nextText || !nextText.trim()) {
        showToast('Stub mapping JSON cannot be empty.', { type: 'error' });
        return false;
      }

      let parsed;
      try {
        parsed = JSON.parse(nextText);
      } catch (err) {
        showToast('Cannot save: Invalid JSON: ' + err.message, { type: 'error' });
        return false;
      }

      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
        showToast('Cannot save: Stub mapping root must be a JSON object.', { type: 'error' });
        return false;
      }

      const stubPayload = cleanStubForWireMock(parsed);
      const existingId = (!clone && stub) ? (stub.id || stub.uuid) : null;
      const payloadId = (stubPayload && !clone) ? (stubPayload.id || stubPayload.uuid) : null;
      const targetId = existingId || payloadId;
      const existsInCatalog = Boolean(targetId)
        && (state.currentStubs || []).some(s => (s.id || s.uuid) === targetId);

      if (clone && stubPayload) {
        delete stubPayload.id;
        delete stubPayload.uuid;
      }

      try {
        const saved = (!clone && existsInCatalog)
          ? await saveStubMapping(targetId, { ...stubPayload, id: targetId })
          : await createStubMapping(stubPayload);

        if (uiActions.loadData) {
          await uiActions.loadData();
        }
        const savedId = (saved && (saved.id || saved.uuid)) || targetId;
        if (savedId) {
          const found = stubCatalog.getById(savedId);
          if (found && uiActions.selectStub) {
            uiActions.selectStub(found, true);
          }
        }
        showToast('Stub saved', { type: 'success' });
        return true;
      } catch (err) {
        showToast('Failed to save stub mapping: ' + err.message, { type: 'error' });
        return false;
      }
    }
  });
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
  const activeProj = getActiveProjectFilter();
  const syntheticStub = {
    name: `${method} ${url}`,
    priority: 5,
    request: {
      method,
      url,
      ...(bodyPatterns.length > 0 ? { bodyPatterns } : {})
    },
    response: {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
      jsonBody: { status: 'ok' }
    },
    metadata: activeProj ? { project: activeProj } : {}
  };
  openStubEditor(syntheticStub, false, 'Create Stub from Journal Request');
}

export async function handleToggleStub(stubId) {
  if (!stubId) return;
  try {
    await toggleStubState(stubId);
    if (uiActions.loadData) {
      await uiActions.loadData();
    }
    if (state.selectedStubId === stubId) {
      const found = stubCatalog.getById(stubId);
      if (found && uiActions.selectStub) {
        uiActions.selectStub(found, false);
      }
    }
    showToast('Stub state updated', { type: 'success' });
  } catch (err) {
    showToast('Failed to toggle stub: ' + err.message, { type: 'error' });
  }
}

export async function handleDeleteStub(stubId) {
  if (!stubId) return;
  const found = stubCatalog.getById(stubId);
  const name = found ? (found.name || stubId) : stubId;
  const outcome = await confirmDialog({
    title: 'Delete Stub Mapping',
    message: `Are you sure you want to permanently delete stub "${name}"?`,
    confirmLabel: 'Delete Stub',
    cancelLabel: 'Cancel',
    danger: true
  });
  if (outcome !== 'confirm') return;

  try {
    await deleteStubMapping(stubId);
    state.selectedStubIds.delete(stubId);
    if (state.selectedStubId === stubId) {
      state.selectedStubId = null;
      if (elements.stubDetailView) elements.stubDetailView.classList.add('hidden');
      if (elements.stubDetailEmpty) elements.stubDetailEmpty.classList.remove('hidden');
    }
    if (uiActions.loadData) {
      await uiActions.loadData();
    }
    showToast(`Deleted stub "${name}"`, { type: 'success' });
  } catch (err) {
    showToast('Failed to delete stub: ' + err.message, { type: 'error' });
  }
}

export function initStubLifecycleHandlers() {
  if (elements.btnCreateStub) {
    elements.btnCreateStub.addEventListener('click', () => openStubEditor(null, false));
  }
  if (elements.btnEditStub) {
    elements.btnEditStub.addEventListener('click', () => {
      if (!state.selectedStubId) return;
      const stub = stubCatalog.getById(state.selectedStubId);
      if (stub) openStubEditor(stub, false);
    });
  }
  if (elements.btnCloneStub) {
    elements.btnCloneStub.addEventListener('click', () => {
      if (!state.selectedStubId) return;
      const stub = stubCatalog.getById(state.selectedStubId);
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

  if (elements.btnWelcomeOpenApi) {
    elements.btnWelcomeOpenApi.addEventListener('click', () => {
      openImportModal('openapi');
    });
  }
  if (elements.btnWelcomeCreate) {
    elements.btnWelcomeCreate.addEventListener('click', () => {
      openStubEditor(null, false);
    });
  }

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

  if (elements.importTabOpenApi) {
    elements.importTabOpenApi.addEventListener('click', () => switchImportTab('openapi'));
  }
  if (elements.importTabBundle) {
    elements.importTabBundle.addEventListener('click', () => switchImportTab('bundle'));
  }
  if (elements.btnSubmitBundleImport) {
    elements.btnSubmitBundleImport.addEventListener('click', handleBundleImport);
  }
  initBundleImport();
}
