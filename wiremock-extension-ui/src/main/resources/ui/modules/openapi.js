/**
 * OpenAPI Import Modal Controller (ES Module).
 * Pure Vanilla JS, zero NPM dependencies.
 */

import { elements } from './dom.js';
import { doFetch, loadMappings } from './api.js';

export function setupOpenApiModal() {
  if (!elements.btnOpenOpenApiModal || !elements.openapiModal) return;

  function openModal() {
    elements.openapiModal.classList.remove('hidden');
    clearFeedback();
    if (elements.openapiSpecContent) {
      elements.openapiSpecContent.focus();
    }
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

  elements.btnOpenOpenApiModal.addEventListener('click', openModal);

  if (elements.btnCloseOpenApiModal) {
    elements.btnCloseOpenApiModal.addEventListener('click', closeModal);
  }

  if (elements.btnCancelOpenApi) {
    elements.btnCancelOpenApi.addEventListener('click', closeModal);
  }

  // Close on clicking backdrop outside modal dialog
  elements.openapiModal.addEventListener('click', (e) => {
    if (e.target === elements.openapiModal) {
      closeModal();
    }
  });

  // Browse file button
  if (elements.btnBrowseOpenApiFile && elements.openapiFileInput) {
    elements.btnBrowseOpenApiFile.addEventListener('click', () => {
      elements.openapiFileInput.click();
    });

    elements.openapiFileInput.addEventListener('change', (e) => {
      const file = e.target.files && e.target.files[0];
      if (file) {
        readFileIntoTextarea(file);
      }
    });
  }

  // Drag and drop support
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
        readFileIntoTextarea(file);
      }
    });
  }

  function readFileIntoTextarea(file) {
    const reader = new FileReader();
    reader.onload = (event) => {
      if (elements.openapiSpecContent) {
        elements.openapiSpecContent.value = event.target.result;
      }
    };
    reader.readAsText(file);
  }

  // Submit button
  if (elements.btnSubmitOpenApiImport) {
    elements.btnSubmitOpenApiImport.addEventListener('click', async () => {
      const spec = (elements.openapiSpecContent && elements.openapiSpecContent.value.trim()) || '';
      if (!spec) {
        showFeedback('Please paste an OpenAPI specification or drop a file.', true);
        return;
      }

      const options = {
        includeOnlySuccessResponses: elements.optOpenApiSuccessOnly ? elements.optOpenApiSuccessOnly.checked : false,
        matchRequiredHeaders: elements.optOpenApiMatchHeaders ? elements.optOpenApiMatchHeaders.checked : true,
        matchRequiredQueryParams: elements.optOpenApiMatchQueries ? elements.optOpenApiMatchQueries.checked : true
      };

      const payload = {
        spec,
        options
      };

      elements.btnSubmitOpenApiImport.disabled = true;
      elements.btnSubmitOpenApiImport.textContent = 'Generating...';

      try {
        const response = await doFetch('/__admin/openapi/import', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload)
        });

        const data = await response.json();
        if (response.ok) {
          showFeedback(`Successfully created ${data.totalStubsCreated} stubs from OpenAPI spec!`, false);
          await loadMappings();
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
