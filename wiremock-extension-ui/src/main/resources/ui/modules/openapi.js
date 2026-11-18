/**
 * OpenAPI Import Modal Controller (ES Module).
 * Pure Vanilla JS, zero NPM dependencies.
 */
import { elements } from './dom.js';
import { showToast } from './dialog.js';
import { doFetch, inspectOpenApi } from './api.js';
import { loadData } from './app-loader.js';
import { detectFormat, validatePayload } from './highlighter.js';
import { mountPolyglotViewer } from './polyglot-viewer.js';
import { openDocumentWorkspace } from './document-workspace.js';

let currentSpecText = '';
let specViewer = null;

export function getOpenApiSpecText() {
  return currentSpecText;
}

export function setupOpenApiModal() {
  if (!elements.btnOpenOpenApiModal || !elements.openapiModal) return;

  function updateSpecLintPill() {
    const pill = elements.openapiSpecLintPill || document.getElementById('openapi-spec-lint-pill');
    if (!pill) return;
    const trimmed = currentSpecText.trim();
    if (!trimmed) {
      pill.textContent = '';
      pill.classList.add('hidden');
      return;
    }
    const fmt = detectFormat(trimmed);
    if (fmt === 'json' || trimmed.startsWith('{') || trimmed.startsWith('[')) {
      const res = validatePayload(trimmed, 'json');
      if (!res.valid) {
        pill.textContent = `⚠️ ${res.errorMessage || 'Invalid JSON'}`;
        pill.classList.remove('hidden');
        return;
      }
    }
    pill.textContent = '';
    pill.classList.add('hidden');
  }

  function openSpecWorkspace(startInEditor = true) {
    const trimmed = currentSpecText.trim();
    const fmt = (detectFormat(trimmed) === 'json' || trimmed.startsWith('{') || trimmed.startsWith('['))
      ? 'json'
      : 'yaml';
    openDocumentWorkspace({
      title: 'OpenAPI Specification',
      text: currentSpecText,
      format: fmt,
      editable: true,
      startInEditor,
      saveLabel: '✓ Apply Spec',
      onCommit: (nextText) => {
        setSpecText(nextText);
        return true;
      }
    });
  }

  function ensureSpecViewer() {
    const host = elements.openapiSpecViewer || document.getElementById('openapi-spec-viewer');
    if (!host) return null;
    if (!specViewer) {
      specViewer = mountPolyglotViewer(host, {
        text: currentSpecText,
        mode: 'rich',
        editable: false,
        onOpenWorkspace: () => openSpecWorkspace(false)
      });
    }
    return specViewer;
  }

  function setSpecText(text) {
    currentSpecText = typeof text === 'string' ? text : String(text ?? '');
    const viewer = ensureSpecViewer();
    if (viewer) {
      viewer.update({ text: currentSpecText });
    }
    updateSpecLintPill();
    triggerSpecInspection();
  }

  ensureSpecViewer();

  const editSpecBtn = elements.btnOpenApiEditWorkspace || document.getElementById('btn-openapi-edit-workspace');
  if (editSpecBtn) {
    editSpecBtn.addEventListener('click', () => openSpecWorkspace(true));
  }

  function openModal() {
    ensureSpecViewer();
    elements.openapiModal.classList.remove('hidden');
    clearFeedback();
  }

  function closeModal() {
    elements.openapiModal.classList.add('hidden');
    clearFeedback();
  }

  function clearFeedback() {
    if (elements.openapiImportFeedback) {
      elements.openapiImportFeedback.className = 'modal-feedback hidden';
      elements.openapiImportFeedback.textContent = '';
    }
  }

  function showFeedback(msg, isError) {
    if (elements.openapiImportFeedback) {
      elements.openapiImportFeedback.className = `modal-feedback ${isError ? 'error' : 'success'}`;
      elements.openapiImportFeedback.textContent = msg;
    }
  }

  function updateModeUi() {
    const isProxy = elements.optOpenApiModeProxy && elements.optOpenApiModeProxy.checked;
    if (elements.openapiProxyConfig) {
      elements.openapiProxyConfig.classList.toggle('hidden', !isProxy);
    }
  }

  if (elements.optOpenApiModeSynthetic) {
    elements.optOpenApiModeSynthetic.addEventListener('change', updateModeUi);
  }
  if (elements.optOpenApiModeProxy) {
    elements.optOpenApiModeProxy.addEventListener('change', updateModeUi);
  }

  async function triggerSpecInspection() {
    const spec = currentSpecText.trim();
    if (!spec || spec.length < 20) return;

    try {
      const info = await inspectOpenApi(spec);
      if (info && info.title && elements.openapiProjectName && !elements.openapiProjectName.value) {
        elements.openapiProjectName.value = info.title;
      }
      if (info && Array.isArray(info.servers) && elements.openapiServerSelect) {
        elements.openapiServerSelect.innerHTML = '<option value="">Spec Servers (' + info.servers.length + ')...</option>';
        info.servers.forEach(s => {
          const opt = document.createElement('option');
          opt.value = s.url;
          opt.textContent = s.description ? `${s.url} (${s.description})` : s.url;
          elements.openapiServerSelect.appendChild(opt);
        });

        if (info.servers.length > 0 && elements.openapiProxyUrl && !elements.openapiProxyUrl.value) {
          elements.openapiProxyUrl.value = info.servers[0].url;
        }
      }
    } catch (_) {
      // Ignored during typing
    }
  }

  if (elements.openapiServerSelect) {
    elements.openapiServerSelect.addEventListener('change', () => {
      if (elements.openapiServerSelect.value && elements.openapiProxyUrl) {
        elements.openapiProxyUrl.value = elements.openapiServerSelect.value;
      }
    });
  }

  elements.btnOpenOpenApiModal.addEventListener('click', openModal);

  if (elements.btnCloseOpenApiModal) {
    elements.btnCloseOpenApiModal.addEventListener('click', closeModal);
  }

  if (elements.btnCancelOpenApi) {
    elements.btnCancelOpenApi.addEventListener('click', closeModal);
  }

  elements.openapiModal.addEventListener('click', (e) => {
    if (e.target === elements.openapiModal) {
      closeModal();
    }
  });

  if (elements.btnBrowseOpenApiFile && elements.openapiFileInput) {
    elements.btnBrowseOpenApiFile.addEventListener('click', () => {
      elements.openapiFileInput.click();
    });

    elements.openapiFileInput.addEventListener('change', (e) => {
      const file = e.target.files && e.target.files[0];
      if (file) {
        readFileIntoSpec(file);
      }
    });
  }

  if (elements.openapiDropzone) {
    ['dragenter', 'dragover'].forEach(eventName => {
      elements.openapiDropzone.addEventListener(eventName, (e) => {
        e.preventDefault();
        e.stopPropagation();
        elements.openapiDropzone.classList.add('dragover');
      });
    });

    ['dragleave', 'drop'].forEach(eventName => {
      elements.openapiDropzone.addEventListener(eventName, (e) => {
        e.preventDefault();
        e.stopPropagation();
        elements.openapiDropzone.classList.remove('dragover');
      });
    });

    elements.openapiDropzone.addEventListener('drop', (e) => {
      const dt = e.dataTransfer;
      const file = dt && dt.files && dt.files[0];
      if (file) {
        readFileIntoSpec(file);
      }
    });
  }

  function readFileIntoSpec(file) {
    const reader = new FileReader();
    reader.onload = (event) => {
      setSpecText(event.target.result || '');
    };
    reader.readAsText(file);
  }

  if (elements.btnSubmitOpenApiImport) {
    elements.btnSubmitOpenApiImport.addEventListener('click', async () => {
      const spec = currentSpecText.trim();
      if (!spec) {
        showFeedback('Please paste an OpenAPI specification or drop a file.', true);
        return;
      }

      const isProxy = elements.optOpenApiModeProxy && elements.optOpenApiModeProxy.checked;
      const proxyBaseUrl = (elements.openapiProxyUrl && elements.openapiProxyUrl.value.trim()) || '';
      const targetProject = (elements.openapiProjectName && elements.openapiProjectName.value.trim()) || null;

      if (isProxy && !proxyBaseUrl) {
        showFeedback('Please enter or select a Target Upstream Base URL (proxyBaseUrl).', true);
        return;
      }

      const options = {
        generationMode: isProxy ? 'PROXY' : 'SYNTHETIC',
        proxyBaseUrl: isProxy ? proxyBaseUrl : null,
        targetProject: targetProject,
        includeOnlySuccessResponses: elements.optOpenApiSuccessOnly ? elements.optOpenApiSuccessOnly.checked : false,
        matchRequiredHeaders: elements.optOpenApiMatchHeaders ? elements.optOpenApiMatchHeaders.checked : true,
        matchRequiredQueryParams: elements.optOpenApiMatchQueries ? elements.optOpenApiMatchQueries.checked : true
      };

      if (isProxy && elements.openapiProxyHeaders && elements.openapiProxyHeaders.value.trim()) {
        const headers = {};
        elements.openapiProxyHeaders.value.split('\n').forEach(line => {
          const colon = line.indexOf(':');
          if (colon > 0) {
            headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim();
          }
        });
        if (Object.keys(headers).length > 0) {
          options.additionalProxyHeaders = headers;
        }
      }

      const payload = { spec, options };

      elements.btnSubmitOpenApiImport.disabled = true;
      elements.btnSubmitOpenApiImport.textContent = isProxy ? 'Configuring Proxy...' : 'Generating...';

      try {
        const response = await doFetch('/__admin/openapi/import', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload)
        });

        const data = await response.json();
        if (response.ok) {
          const msg = `Successfully created ${data.totalStubsCreated} stubs from OpenAPI spec!`;
          showFeedback(msg, false);
          showToast(msg, { type: 'success' });
          await loadData();
          setTimeout(() => {
            closeModal();
          }, 1400);
        } else {
          showFeedback(data.error || 'Failed to import OpenAPI spec.', true);
        }
      } catch (err) {
        showFeedback(`Network error: ${err.message}`, true);
      } finally {
        elements.btnSubmitOpenApiImport.disabled = false;
        elements.btnSubmitOpenApiImport.textContent = '🚀 Generate Stubs';
      }
    });
  }
}
