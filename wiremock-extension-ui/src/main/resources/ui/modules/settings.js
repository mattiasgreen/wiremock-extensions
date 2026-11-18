/**
 * Unified Settings & Target Host controller: Target Host switcher,
 * Persistent Headers, JSON Path Exclusions, and Clipboard Config Sync.
 * Preferences are stored exclusively in browser localStorage.
 */
import { elements, escapeHtml } from './dom.js';
import { showToast } from './dialog.js';
import { state, uiActions, setTargetUrlInLocation, getWireMockUri } from './state.js';
import { renderTargetCapabilities } from './capabilities.js';
import { enableDemoMode, disableDemoMode, isDemoModeActive } from './api.js';

export const LOCAL_SETTINGS_KEY = 'wiremock_ui_preferences';
export const LOCAL_HEADERS_KEY = 'wiremock_ui_local_headers';
export const LOCAL_HEADERS_ENABLED_KEY = 'wiremock_ui_local_headers_enabled';

export const DEFAULT_HEADERS = [
  { name: 'X-Correlation-Id', value: '{{uuid}}', enabled: true },
  { name: 'X-Tenant-Id', value: 'corp-default', enabled: true }
];

export const DEFAULT_EXCLUDED_JSON_PATHS = [
  '$.CORRELATION_ID',
  '$.REQUEST_TIMESTAMP',
  'CORRELATION_ID',
  'REQUEST_TIMESTAMP',
  'timestamp',
  'transactionId',
  'executionId',
  'xid',
  'spanId',
  'traceId'
];

/**
 * Resolves dynamic macros like {{uuid}}, {{now}}, and {{timestamp}}.
 *
 * @param {string} val
 * @returns {string}
 */
export function resolveMacro(val) {
  if (!val || typeof val !== 'string') return val;
  let res = val;
  if (res.includes('{{uuid}}')) {
    const uuid = (typeof crypto !== 'undefined' && crypto.randomUUID)
      ? crypto.randomUUID()
      : 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
          const r = Math.random() * 16 | 0;
          const v = c === 'x' ? r : (r & 0x3 | 0x8);
          return v.toString(16);
        });
    res = res.replaceAll('{{uuid}}', uuid);
  }
  if (res.includes('{{now}}')) {
    res = res.replaceAll('{{now}}', new Date().toISOString());
  }
  if (res.includes('{{timestamp}}')) {
    res = res.replaceAll('{{timestamp}}', String(Date.now()));
  }
  return res;
}

/**
 * Loads user preferences from browser localStorage, falling back to defaults.
 *
 * @returns {{ headers: Array<{ name: string, value: string, enabled: boolean }>, excludedJsonPaths: string[] }}
 */
export function loadPreferences() {
  try {
    const saved = localStorage.getItem(LOCAL_SETTINGS_KEY);
    if (saved) {
      const parsed = JSON.parse(saved);
      const headers = Array.isArray(parsed.headers) ? parsed.headers : DEFAULT_HEADERS;
      const excludedJsonPaths = Array.isArray(parsed.excludedJsonPaths) ? parsed.excludedJsonPaths : DEFAULT_EXCLUDED_JSON_PATHS;
      return { headers, excludedJsonPaths };
    }
  } catch (_) {}

  let headers = DEFAULT_HEADERS;
  try {
    const savedHeaders = localStorage.getItem(LOCAL_HEADERS_KEY);
    if (savedHeaders) {
      const parsed = JSON.parse(savedHeaders);
      if (Array.isArray(parsed)) {
        headers = parsed;
      }
    }
  } catch (_) {}

  return {
    headers,
    excludedJsonPaths: DEFAULT_EXCLUDED_JSON_PATHS
  };
}

/**
 * Persists preferences into browser localStorage.
 *
 * @param {{ headers?: Array<{ name: string, value: string, enabled?: boolean }>, excludedJsonPaths?: string[] }} prefs
 */
export function savePreferences(prefs) {
  if (!prefs || typeof prefs !== 'object') return;
  const current = loadPreferences();
  const headers = Array.isArray(prefs.headers) ? prefs.headers : current.headers;
  const excludedJsonPaths = Array.isArray(prefs.excludedJsonPaths) ? prefs.excludedJsonPaths : current.excludedJsonPaths;

  const toSave = { headers, excludedJsonPaths };
  try {
    localStorage.setItem(LOCAL_SETTINGS_KEY, JSON.stringify(toSave));
    localStorage.setItem(LOCAL_HEADERS_KEY, JSON.stringify(headers));
  } catch (_) {}
}

export function loadLocalHeaders() {
  return loadPreferences().headers;
}

export function saveLocalHeaders(headers) {
  savePreferences({ headers });
}

export function loadExcludedJsonPaths() {
  return loadPreferences().excludedJsonPaths;
}

export function saveExcludedJsonPaths(paths) {
  savePreferences({ excludedJsonPaths: paths });
}

export function isLocalHeadersEnabled() {
  try {
    const saved = localStorage.getItem(LOCAL_HEADERS_ENABLED_KEY);
    if (saved === null) return true;
    return saved === 'true';
  } catch (_) {
    return true;
  }
}

export function setLocalHeadersEnabled(enabled) {
  try {
    localStorage.setItem(LOCAL_HEADERS_ENABLED_KEY, enabled ? 'true' : 'false');
  } catch (_) {}
}

/**
 * Checks if a path or key matches any rule in the excludedJsonPaths list.
 *
 * @param {string} path
 * @param {string} key
 * @param {string[]} excludedPaths
 * @returns {boolean}
 */
export function isJsonPathExcluded(path, key, excludedPaths = []) {
  if (!excludedPaths || excludedPaths.length === 0) return false;
  const cleanKey = (key || '').trim().toLowerCase();
  const cleanPath = (path || '').trim().toLowerCase();

  for (const rawRule of excludedPaths) {
    if (!rawRule || typeof rawRule !== 'string') continue;
    const rule = rawRule.trim().toLowerCase();
    if (!rule) continue;

    if (rule.startsWith('$')) {
      if (rule.endsWith('*')) {
        const prefix = rule.slice(0, -1);
        if (cleanPath.startsWith(prefix)) return true;
      } else if (cleanPath === rule || cleanPath.startsWith(rule + '.') || cleanPath.startsWith(rule + '[')) {
        return true;
      }
    } else {
      if (cleanKey === rule || cleanKey.replace(/[-_]/g, '') === rule.replace(/[-_]/g, '')) {
        return true;
      }
      if (cleanPath.endsWith('.' + rule) || cleanPath.endsWith('[\'' + rule + '\']') || cleanPath.endsWith('["' + rule + '"]')) {
        return true;
      }
    }
  }

  return false;
}

/**
 * Merges browser-persistent headers with request headers entered in the tester textarea.
 * Textarea headers take precedence over persistent local headers.
 *
 * @param {string} textareaVal
 * @param {boolean} executeMacros
 */
export function getEffectiveTesterHeaders(textareaVal = '', executeMacros = true) {
  const localHeaders = loadLocalHeaders();
  const isEnabled = isLocalHeadersEnabled();
  const effective = {};
  const customHeaders = {};
  const overriddenKeys = new Set();
  const appliedLocalHeaders = [];
  const localHeaderKeySet = new Set(localHeaders.map(h => (h && h.name) ? h.name.trim().toLowerCase() : ''));

  const rawLines = (textareaVal || '').split('\n');
  for (const line of rawLines) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith('#')) continue;
    const colonIdx = trimmed.indexOf(':');
    if (colonIdx > 0) {
      const key = trimmed.substring(0, colonIdx).trim();
      const val = trimmed.substring(colonIdx + 1).trim();
      if (key) {
        customHeaders[key] = val;
        const lowerKey = key.toLowerCase();
        if (localHeaderKeySet.has(lowerKey)) {
          overriddenKeys.add(lowerKey);
        }
      }
    }
  }

  if (isEnabled) {
    for (const h of localHeaders) {
      if (!h.enabled || !h.name || !h.name.trim()) continue;
      const key = h.name.trim();
      const lowerKey = key.toLowerCase();
      if (!overriddenKeys.has(lowerKey)) {
        const finalVal = executeMacros ? resolveMacro(h.value || '') : (h.value || '');
        effective[key] = finalVal;
        appliedLocalHeaders.push({ name: key, value: finalVal });
      }
    }
  }

  for (const [k, v] of Object.entries(customHeaders)) {
    effective[k] = v;
  }

  return {
    headers: effective,
    customHeaders,
    overriddenKeys,
    appliedLocalHeaders,
    localHeaders,
    isEnabled
  };
}

/**
 * Exports preferences as a clean, pretty-printed JSON string.
 *
 * @returns {string}
 */
export function exportPreferencesJson() {
  const prefs = loadPreferences();
  return JSON.stringify(prefs, null, 2);
}

/**
 * Validates and imports preferences from a pasted JSON string into localStorage.
 *
 * @param {string} jsonText
 * @returns {{ success: boolean, message: string }}
 */
export function importPreferencesJson(jsonText) {
  if (!jsonText || typeof jsonText !== 'string' || !jsonText.trim()) {
    return { success: false, message: 'Please paste configuration JSON to import.' };
  }
  try {
    const parsed = JSON.parse(jsonText.trim());
    if (typeof parsed !== 'object' || parsed === null) {
      return { success: false, message: 'Invalid JSON configuration: root must be an object.' };
    }
    const headers = Array.isArray(parsed.headers) ? parsed.headers : undefined;
    const excludedJsonPaths = Array.isArray(parsed.excludedJsonPaths) ? parsed.excludedJsonPaths : undefined;

    if (!headers && !excludedJsonPaths) {
      return { success: false, message: 'Invalid format: JSON must contain "headers" or "excludedJsonPaths".' };
    }

    savePreferences({ headers, excludedJsonPaths });
    return {
      success: true,
      message: `Successfully imported ${headers ? headers.length : 0} header(s) and ${excludedJsonPaths ? excludedJsonPaths.length : 0} exclusion path(s).`
    };
  } catch (err) {
    return { success: false, message: `JSON parsing error: ${err.message}` };
  }
}

export function switchSettingsTab(tab = 'target') {
  const activeTab = ['target', 'headers', 'exclusions', 'sync'].includes(tab) ? tab : 'target';
  const tabs = [
    { btn: elements.settingsTabTarget, panel: elements.settingsPanelTarget, name: 'target' },
    { btn: elements.settingsTabHeaders, panel: elements.settingsPanelHeaders, name: 'headers' },
    { btn: elements.settingsTabExclusions, panel: elements.settingsPanelExclusions, name: 'exclusions' },
    { btn: elements.settingsTabSync, panel: elements.settingsPanelSync, name: 'sync' }
  ];

  tabs.forEach(t => {
    if (!t.btn || !t.panel) return;
    const isMatch = t.name === activeTab;
    t.btn.classList.toggle('active', isMatch);
    t.panel.classList.toggle('hidden', !isMatch);
  });

  const isTarget = activeTab === 'target';
  if (elements.btnResetTargetHost) elements.btnResetTargetHost.classList.toggle('hidden', !isTarget);
  if (elements.btnDemoTargetHost) elements.btnDemoTargetHost.classList.toggle('hidden', !isTarget);
  if (elements.btnSaveTargetHost) elements.btnSaveTargetHost.classList.toggle('hidden', !isTarget);
  if (elements.btnResetDefaultSettings) elements.btnResetDefaultSettings.classList.toggle('hidden', isTarget);
  if (elements.btnSaveLocalHeaders) elements.btnSaveLocalHeaders.classList.toggle('hidden', isTarget);
}

export function openSettingsModal(initialTab = 'target') {
  const modal = elements.modalSettings || document.getElementById('modal-settings');
  if (!modal) return;

  // Populate Target Host controls
  if (elements.inputTargetHost) {
    elements.inputTargetHost.value = (state.targetConfig && state.targetConfig.fullUrl) || state.apiBaseUrl || '';
  }
  if (state.connectionStrategy === 'direct') {
    if (elements.radioStrategyDirect) elements.radioStrategyDirect.checked = true;
  } else {
    if (elements.radioStrategyProxy) elements.radioStrategyProxy.checked = true;
  }
  if (elements.targetHostStatus) {
    elements.targetHostStatus.classList.add('hidden');
  }
  renderTargetCapabilities();

  // Populate Local Preferences controls
  const prefs = loadPreferences();
  if (elements.localHeadersTbody) {
    elements.localHeadersTbody.innerHTML = '';
    const headers = prefs.headers;
    if (headers.length === 0) {
      addLocalHeaderRow('X-Correlation-Id', '{{uuid}}', true);
      addLocalHeaderRow('X-Tenant-Id', 'corp-default', true);
    } else {
      headers.forEach(h => addLocalHeaderRow(h.name, h.value, h.enabled !== false));
    }
  }
  if (elements.settingsExcludedPaths) {
    elements.settingsExcludedPaths.value = (prefs.excludedJsonPaths || []).join('\n');
  }
  if (elements.settingsImportJson) elements.settingsImportJson.value = '';
  if (elements.settingsCopyFeedback) elements.settingsCopyFeedback.classList.add('hidden');
  if (elements.settingsImportFeedback) {
    elements.settingsImportFeedback.className = 'modal-feedback hidden';
    elements.settingsImportFeedback.textContent = '';
  }

  switchSettingsTab(initialTab);
  modal.classList.remove('hidden');

  if (initialTab === 'target' && elements.inputTargetHost) {
    elements.inputTargetHost.focus();
  }
}

export function closeSettingsModal() {
  const modal = elements.modalSettings || document.getElementById('modal-settings');
  if (modal) {
    modal.classList.add('hidden');
  }
}

export function openLocalHeadersModal(initialTab = 'headers') {
  openSettingsModal(initialTab);
}

export function closeLocalHeadersModal() {
  closeSettingsModal();
}

export function addLocalHeaderRow(name = '', value = '', enabled = true) {
  if (!elements.localHeadersTbody) return;
  const tr = document.createElement('tr');
  tr.className = 'local-header-row';
  tr.innerHTML = `
    <td style="text-align: center;">
      <input type="checkbox" class="header-row-enabled" ${enabled ? 'checked' : ''} title="Enable this header">
    </td>
    <td>
      <input type="text" class="form-control header-row-name" placeholder="Header-Name" value="${escapeHtml(name)}" autocomplete="off" spellcheck="false">
    </td>
    <td>
      <input type="text" class="form-control header-row-value" placeholder="Value or {{uuid}}" value="${escapeHtml(value)}" autocomplete="off" spellcheck="false">
    </td>
    <td style="text-align: center;">
      <button type="button" class="btn-del-header-row" title="Delete header">🗑️</button>
    </td>
  `;
  const delBtn = tr.querySelector('.btn-del-header-row');
  if (delBtn) {
    delBtn.addEventListener('click', () => tr.remove());
  }
  elements.localHeadersTbody.appendChild(tr);
}

export function saveSettingsFromModal(onSavedCallback = null) {
  const headers = [];
  if (elements.localHeadersTbody) {
    const rows = elements.localHeadersTbody.querySelectorAll('.local-header-row');
    rows.forEach(r => {
      const enabledInput = r.querySelector('.header-row-enabled');
      const nameInput = r.querySelector('.header-row-name');
      const valInput = r.querySelector('.header-row-value');
      const name = nameInput ? nameInput.value.trim() : '';
      const val = valInput ? valInput.value : '';
      const isEnabled = enabledInput ? enabledInput.checked : true;
      if (name) {
        headers.push({ name, value: val, enabled: isEnabled });
      }
    });
  }

  const excludedJsonPaths = [];
  if (elements.settingsExcludedPaths) {
    const lines = elements.settingsExcludedPaths.value.split('\n');
    lines.forEach(l => {
      const trimmed = l.trim();
      if (trimmed && !trimmed.startsWith('#')) {
        excludedJsonPaths.push(trimmed);
      }
    });
  }

  savePreferences({ headers, excludedJsonPaths });
  closeSettingsModal();
  if (typeof onSavedCallback === 'function') {
    onSavedCallback();
  }
  showToast('Preferences saved to browser', { type: 'success' });
}

export async function copySettingsConfigToClipboard() {
  const jsonStr = exportPreferencesJson();
  try {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      await navigator.clipboard.writeText(jsonStr);
    } else {
      const ta = document.createElement('textarea');
      ta.value = jsonStr;
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
    }
    if (elements.settingsCopyFeedback) {
      elements.settingsCopyFeedback.classList.remove('hidden');
      setTimeout(() => {
        if (elements.settingsCopyFeedback) elements.settingsCopyFeedback.classList.add('hidden');
      }, 3000);
    }
  } catch (err) {
    showToast('Failed to copy to clipboard: ' + err.message, { type: 'error' });
  }
}

export function applyPastedSettingsFromModal(onAppliedCallback = null) {
  if (!elements.settingsImportJson) return;
  const text = elements.settingsImportJson.value.trim();
  const res = importPreferencesJson(text);
  if (elements.settingsImportFeedback) {
    elements.settingsImportFeedback.classList.remove('hidden');
    if (res.success) {
      elements.settingsImportFeedback.className = 'modal-feedback feedback-success';
      elements.settingsImportFeedback.textContent = '✅ ' + res.message;
      const prefs = loadPreferences();
      if (elements.localHeadersTbody) {
        elements.localHeadersTbody.innerHTML = '';
        prefs.headers.forEach(h => addLocalHeaderRow(h.name, h.value, h.enabled !== false));
      }
      if (elements.settingsExcludedPaths) {
        elements.settingsExcludedPaths.value = prefs.excludedJsonPaths.join('\n');
      }
      if (typeof onAppliedCallback === 'function') {
        onAppliedCallback();
      }
    } else {
      elements.settingsImportFeedback.className = 'modal-feedback feedback-error';
      elements.settingsImportFeedback.textContent = '❌ ' + res.message;
    }
  }
}

export function resetDefaultSettingsInModal(onResetCallback = null) {
  if (elements.localHeadersTbody) {
    elements.localHeadersTbody.innerHTML = '';
    DEFAULT_HEADERS.forEach(h => addLocalHeaderRow(h.name, h.value, h.enabled !== false));
  }
  if (elements.settingsExcludedPaths) {
    elements.settingsExcludedPaths.value = DEFAULT_EXCLUDED_JSON_PATHS.join('\n');
  }
  savePreferences({ headers: DEFAULT_HEADERS, excludedJsonPaths: DEFAULT_EXCLUDED_JSON_PATHS });
  if (typeof onResetCallback === 'function') {
    onResetCallback();
  }
  showToast('Preferences reset to defaults', { type: 'info' });
}

export function updateTargetHostDisplay() {
  const wiremockUri = getWireMockUri();
  document.title = `WMC - ${wiremockUri}`;

  const brandEl = document.querySelector('.brand');
  if (brandEl) {
    brandEl.title = `WMC - ${wiremockUri}`;
  }

  const demoActive = isDemoModeActive();

  if (elements.demoModeBadge) {
    elements.demoModeBadge.classList.toggle('hidden', !demoActive);
  }

  if (elements.targetHostLabel) {
    if (demoActive) {
      elements.targetHostLabel.textContent = 'Target: ✨ Demo Mode (In-Browser)';
    } else if (state.targetConfig) {
      const modeStr = state.connectionStrategy === 'direct' ? 'direct' : 'proxied';
      elements.targetHostLabel.textContent = `Target: ${state.targetConfig.fullUrl} (${modeStr})`;
    } else {
      elements.targetHostLabel.textContent = 'Target: (same-origin)';
    }
  }
  if (elements.targetHostDot) {
    if (demoActive) {
      elements.targetHostDot.className = 'target-host-dot demo';
    } else {
      elements.targetHostDot.className = state.targetConfig
        ? 'target-host-dot remote'
        : 'target-host-dot';
    }
  }
  const upstreamUrl = state.targetConfig ? state.targetConfig.fullUrl : '';
  if (elements.linkSwaggerUi) {
    elements.linkSwaggerUi.href = upstreamUrl
      ? `${upstreamUrl}/__admin/swagger-ui/`
      : '../swagger-ui/';
  }
  if (elements.linkMetrics) {
    elements.linkMetrics.href = upstreamUrl
      ? `${upstreamUrl}/__admin/metrics/prometheus`
      : '../metrics/prometheus';
  }
}

export function initTargetConnectionModal({ loadData, loadVersionInfo, onHeadersChanged } = {}) {
  const notifyHeadersChanged = () => {
    if (typeof onHeadersChanged === 'function') {
      onHeadersChanged();
    } else if (uiActions.renderLocalHeaderChips) {
      uiActions.renderLocalHeaderChips();
    }
  };
  if (elements.btnTargetHost) {
    elements.btnTargetHost.addEventListener('click', () => openSettingsModal('target'));
  }
  if (elements.btnOpenSettings) {
    elements.btnOpenSettings.addEventListener('click', () => openSettingsModal('headers'));
  }
  if (elements.btnManageLocalHeaders) {
    elements.btnManageLocalHeaders.addEventListener('click', () => openSettingsModal('headers'));
  }
  if (elements.btnCloseSettingsModal) {
    elements.btnCloseSettingsModal.addEventListener('click', closeSettingsModal);
  }
  if (elements.btnCancelSettingsModal) {
    elements.btnCancelSettingsModal.addEventListener('click', closeSettingsModal);
  }
  if (elements.modalSettings) {
    elements.modalSettings.addEventListener('click', (e) => {
      if (e.target === elements.modalSettings) {
        closeSettingsModal();
      }
    });
  }
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && elements.modalSettings && !elements.modalSettings.classList.contains('hidden')) {
      const confirmModal = document.getElementById('modal-confirm');
      if (confirmModal && !confirmModal.classList.contains('hidden')) return;
      closeSettingsModal();
    }
  });

  if (elements.settingsTabTarget) {
    elements.settingsTabTarget.addEventListener('click', () => switchSettingsTab('target'));
  }
  if (elements.settingsTabHeaders) {
    elements.settingsTabHeaders.addEventListener('click', () => switchSettingsTab('headers'));
  }
  if (elements.settingsTabExclusions) {
    elements.settingsTabExclusions.addEventListener('click', () => switchSettingsTab('exclusions'));
  }
  if (elements.settingsTabSync) {
    elements.settingsTabSync.addEventListener('click', () => switchSettingsTab('sync'));
  }

  if (elements.btnAddLocalHeaderRow) {
    elements.btnAddLocalHeaderRow.addEventListener('click', () => addLocalHeaderRow('', '', true));
  }
  if (elements.btnSaveLocalHeaders) {
    elements.btnSaveLocalHeaders.addEventListener('click', () => saveSettingsFromModal(notifyHeadersChanged));
  }
  if (elements.btnSettingsCopyConfig) {
    elements.btnSettingsCopyConfig.addEventListener('click', copySettingsConfigToClipboard);
  }
  if (elements.btnSettingsApplyImport) {
    elements.btnSettingsApplyImport.addEventListener('click', () => applyPastedSettingsFromModal(notifyHeadersChanged));
  }
  if (elements.btnResetDefaultSettings) {
    elements.btnResetDefaultSettings.addEventListener('click', () => resetDefaultSettingsInModal(notifyHeadersChanged));
  }
  if (elements.testerLocalHeadersToggle) {
    elements.testerLocalHeadersToggle.addEventListener('change', (e) => {
      setLocalHeadersEnabled(e.target.checked);
      notifyHeadersChanged();
    });
  }
  if (elements.testerHeaders) {
    elements.testerHeaders.addEventListener('input', () => {
      notifyHeadersChanged();
    });
  }

  const applyTargetHost = (newTarget, strategy) => {
    state.capabilitiesDetected = false;
    if (newTarget) {
      disableDemoMode();
    }
    setTargetUrlInLocation(newTarget, strategy);
    updateTargetHostDisplay();
    renderTargetCapabilities();
    closeSettingsModal();
    if (typeof loadData === 'function') loadData();
    if (typeof loadVersionInfo === 'function') loadVersionInfo();
  };

  if (elements.btnSaveTargetHost) {
    elements.btnSaveTargetHost.addEventListener('click', () => {
      let val = (elements.inputTargetHost ? elements.inputTargetHost.value : '').trim();
      if (val && !val.startsWith('http://') && !val.startsWith('https://')) {
        val = `http://${val}`;
      }
      const isDirect = elements.radioStrategyDirect && elements.radioStrategyDirect.checked;
      const strategy = isDirect ? 'direct' : 'proxy';
      applyTargetHost(val, strategy);
    });
  }

  if (elements.btnResetTargetHost) {
    elements.btnResetTargetHost.addEventListener('click', () => {
      disableDemoMode();
      applyTargetHost('', 'proxy');
    });
  }

  if (elements.btnDemoTargetHost) {
    elements.btnDemoTargetHost.addEventListener('click', async () => {
      await enableDemoMode();
      applyTargetHost('', 'proxy');
    });
  }

  if (elements.inputTargetHost) {
    elements.inputTargetHost.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        e.preventDefault();
        if (elements.btnSaveTargetHost) elements.btnSaveTargetHost.click();
      } else if (e.key === 'Escape') {
        closeSettingsModal();
      }
    });
  }
}
