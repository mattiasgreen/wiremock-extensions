/**
 * OpenAPI Import Modal Controller (ES Module).
 * Pure Vanilla JS, zero NPM dependencies.
 */

import { elements, escapeHtml } from './dom.js';
import { doFetch, loadData, inspectOpenApi } from './api.js';

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
    clearAnalyzeFeedback();
  }

  function clearFeedback() {
    if (elements.openapiImportFeedback) {
      elements.openapiImportFeedback.className = 'modal-feedback hidden';
      elements.openapiImportFeedback.textContent = '';
    }
  }

  function clearAnalyzeFeedback() {
    if (elements.analyzeFeedback) {
      elements.analyzeFeedback.className = 'modal-feedback hidden';
      elements.analyzeFeedback.textContent = '';
    }
  }

  function showAnalyzeFeedback(msg, isError) {
    if (elements.analyzeFeedback) {
      elements.analyzeFeedback.className = `modal-feedback ${isError ? 'error' : 'success'}`;
      elements.analyzeFeedback.textContent = msg;
    }
  }

  function showFeedback(msg, isError) {
    if (elements.openapiImportFeedback) {
      elements.openapiImportFeedback.className = `modal-feedback ${isError ? 'error' : 'success'}`;
      elements.openapiImportFeedback.textContent = msg;
    }
  }

  // Generation mode toggle
  function updateModeUi() {
    const isProxy = elements.optOpenApiModeProxy && elements.optOpenApiModeProxy.checked;
    if (elements.openapiProxyConfig) {
      if (isProxy) {
        elements.openapiProxyConfig.classList.remove('hidden');
      } else {
        elements.openapiProxyConfig.classList.add('hidden');
      }
    }
  }

  if (elements.optOpenApiModeSynthetic) {
    elements.optOpenApiModeSynthetic.addEventListener('change', updateModeUi);
  }
  if (elements.optOpenApiModeProxy) {
    elements.optOpenApiModeProxy.addEventListener('change', updateModeUi);
  }

  // Auto-inspect OpenAPI spec on input
  async function triggerSpecInspection() {
    const spec = (elements.openapiSpecContent && elements.openapiSpecContent.value.trim()) || '';
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

  if (elements.openapiSpecContent) {
    let inspectTimer = null;
    elements.openapiSpecContent.addEventListener('input', () => {
      clearTimeout(inspectTimer);
      inspectTimer = setTimeout(triggerSpecInspection, 600);
    });
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
        triggerSpecInspection();
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

      const payload = {
        spec,
        options
      };

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
          showFeedback(`Successfully created ${data.totalStubsCreated} stubs from OpenAPI spec!`, false);
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

  if (elements.btnCancelAnalyze) {
    elements.btnCancelAnalyze.addEventListener('click', closeModal);
  }

  // Analyze & Generalize Stubs Logic
  if (elements.btnRunStubAnalysis) {
    elements.btnRunStubAnalysis.addEventListener('click', async () => {
      const spec = (elements.analyzeSpecContent && elements.analyzeSpecContent.value.trim()) || '';
      if (!spec) {
        showAnalyzeFeedback('Please paste an OpenAPI specification to analyze recorded stubs.', true);
        return;
      }
      const project = (elements.analyzeTargetProject && elements.analyzeTargetProject.value) || null;

      elements.btnRunStubAnalysis.disabled = true;
      elements.btnRunStubAnalysis.textContent = 'Analyzing...';
      clearAnalyzeFeedback();
      if (elements.analyzeReportContainer) elements.analyzeReportContainer.classList.add('hidden');
      if (elements.btnExecuteGeneralize) elements.btnExecuteGeneralize.classList.add('hidden');

      try {
        const response = await doFetch('/__admin/openapi/analyze-recorded-stubs', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ spec, project, generalizeAndRegister: false })
        });
        const data = await response.json();
        if (!response.ok) {
          showAnalyzeFeedback(data.error || 'Failed to analyze stubs.', true);
          return;
        }

        renderAnalysisReport(data);
      } catch (err) {
        showAnalyzeFeedback(`Network error: ${err.message}`, true);
      } finally {
        elements.btnRunStubAnalysis.disabled = false;
        elements.btnRunStubAnalysis.textContent = '🔍 Run Stub Analysis';
      }
    });
  }

  if (elements.btnExecuteGeneralize) {
    elements.btnExecuteGeneralize.addEventListener('click', async () => {
      const spec = (elements.analyzeSpecContent && elements.analyzeSpecContent.value.trim()) || '';
      const project = (elements.analyzeTargetProject && elements.analyzeTargetProject.value) || null;

      elements.btnExecuteGeneralize.disabled = true;
      elements.btnExecuteGeneralize.textContent = 'Generalizing...';

      try {
        const response = await doFetch('/__admin/openapi/analyze-recorded-stubs', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ spec, project, generalizeAndRegister: true })
        });
        const data = await response.json();
        if (!response.ok) {
          showAnalyzeFeedback(data.error || 'Failed to generalize stubs.', true);
          return;
        }

        const count = (data.registeredStubIds && data.registeredStubIds.length) || 0;
        showAnalyzeFeedback(`Successfully generalized and registered ${count} dynamic stub(s)!`, false);
        await loadData();
      } catch (err) {
        showAnalyzeFeedback(`Network error: ${err.message}`, true);
      } finally {
        elements.btnExecuteGeneralize.disabled = false;
        elements.btnExecuteGeneralize.textContent = '✨ Generalize & Register Stubs';
      }
    });
  }

  function renderAnalysisReport(data) {
    if (!elements.analyzeReportContainer || !elements.analyzeDetails || !elements.analyzeSummaryBanner) return;
    const report = data.report || {};
    const ops = report.operations || [];
    const mutations = report.detectedMutations || [];
    const candidateCount = data.candidateStubsAnalyzed || 0;

    elements.analyzeSummaryBanner.innerHTML = `
      <div><strong>${escapeHtml(report.specTitle || 'OpenAPI Specification')}</strong> (v${escapeHtml(report.specVersion || '1.0')})</div>
      <div class="analyze-summary-stats">
        <span>Stubs Evaluated: <strong>${candidateCount}</strong></span>
        <span>Operations: <strong>${ops.length}</strong></span>
        <span>Mutations: <strong>${mutations.length}</strong></span>
      </div>
    `;

    let html = '';

    if (ops.length === 0 && candidateCount === 0) {
      html = '<div class="analysis-card"><p style="color: var(--text-muted);">No candidate recorded stubs found to analyze. Record traffic first via Live Proxy mode.</p></div>';
    } else if (ops.length === 0) {
      html = '<div class="analysis-card"><p style="color: var(--text-muted);">No matching OpenAPI operations found for the candidate stubs.</p></div>';
    }

    ops.forEach(op => {
      const echoes = op.parameterEchoes || [];
      const temporals = op.temporalFields || [];
      const traits = op.distinguishingTraitFields || {};

      let cardContent = `
        <div class="analysis-card">
          <div class="analysis-card-header">
            <span class="analysis-card-title">
              <span class="http-badge badge-${(op.httpMethod || 'GET').toUpperCase()}">${escapeHtml(op.httpMethod || 'GET')}</span>
              <span>${escapeHtml(op.openApiPathTemplate || '')}</span>
            </span>
            <span class="muted-text" style="font-size: 0.75rem;">${escapeHtml(op.operationId || '')} &bull; ${op.matchedStubIds ? op.matchedStubIds.length : 0} stub(s)</span>
          </div>
      `;

      if (echoes.length > 0) {
        cardContent += `
          <div style="margin-top: 6px;">
            <span class="analysis-tag tag-echo">Parameter Echoes (${echoes.length})</span>
            <ul class="analysis-item-list">
              ${echoes.map(e => `<li class="analysis-item"><span class="analysis-item-key">${escapeHtml(e.parameterName)}</span> &rarr; <span>${escapeHtml(e.responseJsonPointer)}</span> <span style="color: var(--accent-color);">({{jsonPath request.body '${escapeHtml(e.parameterName)}'}})</span></li>`).join('')}
            </ul>
          </div>
        `;
      }

      if (temporals.length > 0) {
        cardContent += `
          <div style="margin-top: 6px;">
            <span class="analysis-tag tag-warning">⚠️ Temporal Locks (${temporals.length})</span>
            <ul class="analysis-item-list">
              ${temporals.map(t => `<li class="analysis-item"><span class="analysis-item-key">${escapeHtml(t.jsonPointer)}</span> <span>(${escapeHtml(t.schemaFormat || 'date')}) &bull; brittle date lock in raw recording</span></li>`).join('')}
            </ul>
          </div>
        `;
      }

      const traitKeys = Object.keys(traits);
      if (traitKeys.length > 0) {
        cardContent += `
          <div style="margin-top: 6px;">
            <span class="analysis-tag tag-trait">Archetype Discriminators (${traitKeys.length})</span>
            <ul class="analysis-item-list">
              ${traitKeys.map(k => `<li class="analysis-item"><span class="analysis-item-key">${escapeHtml(k)}</span>: <span>${escapeHtml(JSON.stringify(traits[k]))}</span></li>`).join('')}
            </ul>
          </div>
        `;
      }

      cardContent += '</div>';
      html += cardContent;
    });

    if (mutations.length > 0) {
      html += `
        <div class="analysis-card">
          <div class="analysis-card-header">
            <span class="analysis-card-title">
              <span class="analysis-tag tag-mutation">Stateful Mutations (${mutations.length})</span>
            </span>
          </div>
          <ul class="analysis-item-list">
            ${mutations.map(m => `<li class="analysis-item"><span class="analysis-item-key">${escapeHtml(m.resourcePathTemplate)}</span> &bull; <span>${escapeHtml(m.mutatingOperationId)}</span> modifies [${(m.mutatedFields || []).map(f => escapeHtml(f)).join(', ')}]</li>`).join('')}
          </ul>
        </div>
      `;
    }

    elements.analyzeDetails.innerHTML = html;
    elements.analyzeReportContainer.classList.remove('hidden');

    if (ops.length > 0 && elements.btnExecuteGeneralize) {
      elements.btnExecuteGeneralize.classList.remove('hidden');
    }
  }
}
