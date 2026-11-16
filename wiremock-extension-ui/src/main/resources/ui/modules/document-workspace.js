/**
 * Unified Document Workspace Modal controller.
 * Combines a non-destructive Polyglot Tree Viewer (DOCUMENT_VIEWER) with a
 * full-featured Code Editor (SECTION_EDITOR) supporting root edits and
 * span-exact nested section splicing.
 */
import { escapeHtml } from './dom.js';
import { confirmDialog, showToast } from './dialog.js';
import { detectCharset } from './charset-codec.js';
import {
  detectFormat,
  formatPayload,
  validatePayload
} from './highlighter.js';
import {
  applyNestedPathEdit,
  formatPayloadDescriptor,
  formatCasualBufferDescriptor
} from './polyglot-ast.js';
import {
  mountCodeEditor,
  getEditorEngine,
  setEditorEngine
} from './code-editor.js';
import {
  mountPolyglotViewer,
  collectSegmentsAndNodes
} from './polyglot-viewer.js';

let initialized = false;
let isOpen = false;
let workspaceMode = 'DOCUMENT_VIEWER'; // 'DOCUMENT_VIEWER' | 'SECTION_EDITOR'

let currentTitle = 'Document Workspace';
let rootText = '';
let initialRootText = '';
let rootFormat = 'text';
let rootHeaders = null;
let rootBufferType = null;
let isEditable = false;
let currentSaveLabel = '💾 Save';
let onCommitCallback = null;
let onOpenOpenApiCallback = null;

let normalizedTemplates = [];
let currentTemplateKey = '';
let viewerDisplayMode = 'rich';
let dirty = false;
let saving = false;

let viewerController = null;
let editorController = null;
let activeSectionChain = null;
let activeSectionInitialText = '';
let activeSectionFormat = 'text';

function el(id) {
  return document.getElementById(id);
}

function utf8ByteLength(str) {
  if (!str) return 0;
  if (typeof TextEncoder !== 'undefined') {
    return new TextEncoder().encode(str).length;
  }
  if (typeof Blob !== 'undefined') {
    return new Blob([str]).size;
  }
  return str.length;
}

function resolveDocumentFormat(text, hint) {
  const detected = detectFormat(text);
  if (hint === 'yaml' && detected === 'text') {
    return 'yaml';
  }
  if (detected !== 'text') {
    return detected;
  }
  return hint || 'text';
}

function normalizeTemplateList(templates) {
  if (!templates) return [];
  if (Array.isArray(templates)) {
    return templates
      .filter(t => t && t.key)
      .map(t => ({
        key: String(t.key),
        label: t.label || String(t.key),
        buildText: typeof t.buildText === 'function'
          ? t.buildText
          : () => (typeof t.text === 'string' ? t.text : JSON.stringify(t.payload || t, null, 2))
      }));
  }
  if (typeof templates === 'object') {
    return Object.entries(templates).map(([key, tpl]) => ({
      key,
      label: (tpl && (tpl.label || tpl.name)) || key,
      buildText: tpl && typeof tpl.buildText === 'function'
        ? tpl.buildText
        : () => (typeof tpl === 'string' ? tpl : JSON.stringify(tpl, null, 2))
    }));
  }
  return [];
}

function syncDirtyBadge() {
  const badge = el('workspace-dirty-badge');
  if (!badge) return;
  const hasUnsaved = dirty || (
    workspaceMode === 'SECTION_EDITOR'
    && editorController
    && editorController.getValue() !== activeSectionInitialText
  );
  badge.classList.toggle('hidden', !hasUnsaved);
}

function updateDescriptorPill() {
  const pill = el('workspace-desc-pill');
  if (!pill) return;

  const textForDesc = workspaceMode === 'SECTION_EDITOR' && editorController
    ? editorController.getValue()
    : rootText;
  const fmt = resolveDocumentFormat(textForDesc, workspaceMode === 'SECTION_EDITOR' ? activeSectionFormat : rootFormat);
  const charset = detectCharset(textForDesc, rootHeaders);
  const sizeKb = (utf8ByteLength(textForDesc) / 1024).toFixed(1);

  if (rootBufferType && workspaceMode === 'DOCUMENT_VIEWER') {
    pill.textContent = formatCasualBufferDescriptor(rootBufferType, fmt, sizeKb, charset);
  } else {
    pill.textContent = formatPayloadDescriptor({ format: fmt, charset, sizeKb });
  }
}

function syncEngineToggleButtons() {
  const current = editorController ? editorController.getEngine() : getEditorEngine();
  const betaBtn = el('btn-editor-engine-beta');
  const legacyBtn = el('btn-editor-engine-legacy');
  if (betaBtn) betaBtn.classList.toggle('active', current === 'highlight-api');
  if (legacyBtn) legacyBtn.classList.toggle('active', current === 'legacy-duallayer');
}

function syncViewModeButtons() {
  const toggle = el('workspace-view-mode-toggle');
  if (!toggle) return;
  const richBtn = toggle.querySelector('.btn-mode-rich');
  const rawBtn = toggle.querySelector('.btn-mode-raw');
  if (richBtn) richBtn.classList.toggle('active', viewerDisplayMode === 'rich');
  if (rawBtn) rawBtn.classList.toggle('active', viewerDisplayMode === 'raw');
}

function updateValidationUi(value) {
  const lintPill = el('workspace-lint-pill');
  const banner = el('workspace-validation-banner');
  const forceBtn = el('btn-workspace-force-apply');
  const applyBtn = el('btn-workspace-apply-section');

  const fmt = activeSectionFormat;
  const shouldCheck = fmt === 'json' || fmt === 'xml' || fmt === 'kv';
  const result = shouldCheck ? validatePayload(value, fmt) : { valid: true };

  if (!result.valid) {
    const msg = result.errorMessage || `Invalid ${fmt.toUpperCase()} syntax`;
    if (lintPill) {
      lintPill.textContent = `⚠️ Invalid ${fmt.toUpperCase()}`;
      lintPill.title = msg;
      lintPill.classList.remove('hidden');
    }
    if (banner) {
      banner.textContent = msg;
      banner.classList.remove('hidden');
    }
    const isStrictSyntax = fmt === 'json' || fmt === 'xml';
    if (forceBtn) forceBtn.classList.toggle('hidden', !isStrictSyntax);
    if (applyBtn) applyBtn.disabled = isStrictSyntax;
  } else {
    if (lintPill) {
      lintPill.textContent = '';
      lintPill.title = '';
      lintPill.classList.add('hidden');
    }
    if (banner) {
      banner.textContent = '';
      banner.classList.add('hidden');
    }
    if (forceBtn) forceBtn.classList.add('hidden');
    if (applyBtn) applyBtn.disabled = false;
  }

  return result;
}

function renderBreadcrumbs() {
  const bcEl = el('workspace-breadcrumbs');
  if (!bcEl) return;

  const parts = ['<span class="breadcrumb-item" data-bc-root="true">Root Document</span>'];
  if (Array.isArray(activeSectionChain) && activeSectionChain.length > 0) {
    for (const node of activeSectionChain) {
      const label = node.key || node.path || 'Section';
      parts.push('<span class="breadcrumb-sep">▸</span>');
      parts.push(`<span class="breadcrumb-item">${escapeHtml(label)}</span>`);
    }
  }
  // Mark last item active
  const lastIdx = parts.length - 1;
  if (lastIdx >= 0) {
    parts[lastIdx] = parts[lastIdx].replace('class="breadcrumb-item"', 'class="breadcrumb-item active"');
  }
  bcEl.innerHTML = parts.join('');
}

function destroyEditor() {
  if (editorController) {
    editorController.destroy();
    editorController = null;
  }
  const host = el('workspace-code-editor-host');
  if (host) host.innerHTML = '';
}

function destroyViewer() {
  if (viewerController) {
    viewerController.destroy();
    viewerController = null;
  }
  const pane = el('workspace-viewer-pane');
  if (pane) pane.innerHTML = '';
}

function highlightInitialSection(sectionPath) {
  if (!sectionPath) return;
  const pane = el('workspace-viewer-pane');
  if (!pane) return;
  const blocks = pane.querySelectorAll('.nested-payload-block');
  for (const block of blocks) {
    if (block.getAttribute('data-node-path') === sectionPath) {
      block.scrollIntoView({ block: 'center', behavior: 'smooth' });
      block.classList.add('workspace-node-highlight');
      setTimeout(() => block.classList.remove('workspace-node-highlight'), 1800);
      break;
    }
  }
}

function enterDocumentViewer(initialSectionPath = null) {
  destroyEditor();
  workspaceMode = 'DOCUMENT_VIEWER';
  activeSectionChain = null;
  activeSectionInitialText = '';

  const viewerPane = el('workspace-viewer-pane');
  const editorPane = el('workspace-editor-pane');
  const subheader = el('workspace-editor-subheader');
  const viewToggle = el('workspace-view-mode-toggle');
  const editRootBtn = el('btn-workspace-edit-root');
  const tplGroup = el('workspace-template-group');

  const cancelBtn = el('btn-workspace-cancel');
  const saveBtn = el('btn-workspace-save');
  const cancelSectionBtn = el('btn-workspace-cancel-section');
  const forceApplyBtn = el('btn-workspace-force-apply');
  const applySectionBtn = el('btn-workspace-apply-section');

  if (viewerPane) viewerPane.classList.remove('hidden');
  if (editorPane) editorPane.classList.add('hidden');
  if (subheader) subheader.classList.add('hidden');
  if (viewToggle) viewToggle.classList.remove('hidden');
  if (editRootBtn) editRootBtn.classList.toggle('hidden', !isEditable);
  if (tplGroup) tplGroup.classList.toggle('hidden', !(isEditable && normalizedTemplates.length > 0));

  if (cancelBtn) cancelBtn.classList.remove('hidden');
  if (saveBtn) {
    saveBtn.textContent = currentSaveLabel;
    saveBtn.classList.toggle('hidden', !isEditable);
  }
  if (cancelSectionBtn) cancelSectionBtn.classList.add('hidden');
  if (forceApplyBtn) forceApplyBtn.classList.add('hidden');
  if (applySectionBtn) applySectionBtn.classList.add('hidden');

  destroyViewer();
  if (viewerPane) {
    viewerController = mountPolyglotViewer(viewerPane, {
      text: rootText,
      mode: viewerDisplayMode,
      editable: isEditable,
      showOpenWorkspace: false,
      headers: rootHeaders,
      onEditSection: (chain) => {
        if (isEditable) {
          enterSectionEditor(chain);
        }
      }
    });
  }

  syncViewModeButtons();
  syncDirtyBadge();
  updateDescriptorPill();

  if (initialSectionPath) {
    highlightInitialSection(initialSectionPath);
  }
}

function enterSectionEditor(nodeChain = null) {
  if (!isEditable) return;
  destroyViewer();
  workspaceMode = 'SECTION_EDITOR';
  activeSectionChain = Array.isArray(nodeChain) && nodeChain.length > 0 ? nodeChain : null;

  let initialText;
  let sectionFmt;
  if (!activeSectionChain) {
    initialText = rootText;
    sectionFmt = resolveDocumentFormat(rootText, rootFormat);
  } else {
    const leaf = activeSectionChain[activeSectionChain.length - 1];
    sectionFmt = leaf.format || detectFormat(leaf.unescapedValue || '');
    if (sectionFmt === 'kv' || sectionFmt === 'text') {
      initialText = leaf.unescapedValue ?? leaf.formattedValue ?? '';
    } else {
      initialText = leaf.formattedValue || formatPayload(leaf.unescapedValue ?? '');
    }
  }

  activeSectionInitialText = initialText;
  activeSectionFormat = sectionFmt;

  const viewerPane = el('workspace-viewer-pane');
  const editorPane = el('workspace-editor-pane');
  const subheader = el('workspace-editor-subheader');
  const viewToggle = el('workspace-view-mode-toggle');
  const editRootBtn = el('btn-workspace-edit-root');
  const tplGroup = el('workspace-template-group');
  const wireStyleSelect = el('workspace-wire-style-select');

  const cancelBtn = el('btn-workspace-cancel');
  const saveBtn = el('btn-workspace-save');
  const cancelSectionBtn = el('btn-workspace-cancel-section');
  const applySectionBtn = el('btn-workspace-apply-section');

  if (viewerPane) viewerPane.classList.add('hidden');
  if (editorPane) editorPane.classList.remove('hidden');
  if (subheader) subheader.classList.remove('hidden');
  if (viewToggle) viewToggle.classList.add('hidden');
  if (editRootBtn) editRootBtn.classList.add('hidden');
  if (tplGroup) tplGroup.classList.add('hidden');

  if (wireStyleSelect) {
    wireStyleSelect.value = 'auto';
    wireStyleSelect.classList.toggle('hidden', !activeSectionChain);
  }

  if (cancelBtn) cancelBtn.classList.add('hidden');
  if (saveBtn) saveBtn.classList.add('hidden');
  if (cancelSectionBtn) cancelSectionBtn.classList.remove('hidden');
  if (applySectionBtn) applySectionBtn.classList.remove('hidden');

  renderBreadcrumbs();
  syncEngineToggleButtons();

  const host = el('workspace-code-editor-host');
  if (host) {
    editorController = mountCodeEditor(host, {
      value: initialText,
      format: sectionFmt,
      readOnly: false,
      engine: getEditorEngine(),
      onChange: (nextVal) => {
        updateValidationUi(nextVal);
        syncDirtyBadge();
        updateDescriptorPill();
      },
      onSaveShortcut: () => {
        handleApplySection(false);
      }
    });
    editorController?.focus();
  }

  updateValidationUi(initialText);
  syncDirtyBadge();
  updateDescriptorPill();
}

function handleApplySection(forceApply = false) {
  if (!editorController) return false;
  const editedText = editorController.getValue();

  if (!forceApply && (activeSectionFormat === 'json' || activeSectionFormat === 'xml')) {
    const valRes = updateValidationUi(editedText);
    if (!valRes.valid) {
      showToast(valRes.errorMessage || 'Invalid syntax', { type: 'error' });
      return false;
    }
  }

  if (!activeSectionChain) {
    let nextRoot = editedText;
    if (!forceApply && (activeSectionFormat === 'json' || activeSectionFormat === 'xml') && editedText.trim()) {
      nextRoot = formatPayload(editedText);
    }
    rootText = nextRoot;
  } else {
    const wireSelect = el('workspace-wire-style-select');
    const wireStyleOverride = wireSelect ? wireSelect.value : 'auto';
    rootText = applyNestedPathEdit(rootText, activeSectionChain, editedText, { wireStyleOverride });
  }

  dirty = rootText !== initialRootText;
  enterDocumentViewer();
  return true;
}

async function requestCancelSection() {
  if (workspaceMode !== 'SECTION_EDITOR') return true;
  const currentVal = editorController ? editorController.getValue() : activeSectionInitialText;
  if (currentVal !== activeSectionInitialText) {
    const outcome = await confirmDialog({
      title: 'Discard Section Changes',
      message: 'Discard unsaved edits in this section and return to the document viewer?',
      confirmLabel: 'Discard Changes',
      cancelLabel: 'Keep Editing',
      danger: true
    });
    if (outcome !== 'confirm') {
      return false;
    }
  }
  enterDocumentViewer();
  return true;
}

async function handleCommitSave() {
  if (!isEditable || saving) return false;
  if (workspaceMode === 'SECTION_EDITOR') {
    const applied = handleApplySection(false);
    if (!applied) return false;
  }

  if (!onCommitCallback) {
    dirty = false;
    closeDocumentWorkspace(true);
    return true;
  }

  const saveBtn = el('btn-workspace-save');
  saving = true;
  if (saveBtn) {
    saveBtn.disabled = true;
    saveBtn.textContent = '⏳ Saving...';
  }
  try {
    const result = await onCommitCallback(rootText);
    if (result === false) {
      return false;
    }
    dirty = false;
    initialRootText = rootText;
    closeDocumentWorkspace(true);
    return true;
  } catch (err) {
    showToast((err && err.message) || 'Failed to save changes', { type: 'error' });
    return false;
  } finally {
    saving = false;
    if (saveBtn) {
      saveBtn.disabled = false;
      saveBtn.textContent = currentSaveLabel;
    }
  }
}

async function requestCloseWorkspace() {
  if (!isOpen) return true;

  if (workspaceMode === 'SECTION_EDITOR') {
    return requestCancelSection();
  }

  if (dirty) {
    const outcome = await confirmDialog({
      title: 'Unsaved Changes',
      message: 'Save changes before closing?',
      confirmLabel: currentSaveLabel,
      secondaryLabel: 'Discard Changes',
      cancelLabel: 'Cancel'
    });
    if (outcome === 'confirm') {
      return handleCommitSave();
    }
    if (outcome === 'secondary') {
      closeDocumentWorkspace(true);
      return true;
    }
    return false;
  }

  closeDocumentWorkspace(true);
  return true;
}

function findNodeChainByPath(text, targetPath, headers) {
  if (!targetPath) return null;
  const collected = collectSegmentsAndNodes(text, 5, headers);
  for (const chain of collected.nodeMap.values()) {
    const leaf = chain[chain.length - 1];
    if (leaf && leaf.path === targetPath) {
      return chain;
    }
  }
  return null;
}

/**
 * Initializes event listeners for `#modal-document-workspace`.
 * Idempotent: safe to call multiple times.
 */
export function initDocumentWorkspace() {
  if (initialized) return;
  const modal = el('modal-document-workspace');
  if (!modal) return;
  initialized = true;

  const closeBtn = el('btn-close-workspace');
  const cancelBtn = el('btn-workspace-cancel');
  const saveBtn = el('btn-workspace-save');
  const editRootBtn = el('btn-workspace-edit-root');
  const copyDocBtn = el('btn-workspace-copy-doc');
  const openApiBtn = el('btn-workspace-open-openapi');
  const templateSelect = el('workspace-template-select');
  const viewModeToggle = el('workspace-view-mode-toggle');
  const cancelSectionBtn = el('btn-workspace-cancel-section');
  const forceApplyBtn = el('btn-workspace-force-apply');
  const applySectionBtn = el('btn-workspace-apply-section');
  const formatBtn = el('btn-workspace-format');
  const minifyBtn = el('btn-workspace-minify');
  const betaEngineBtn = el('btn-editor-engine-beta');
  const legacyEngineBtn = el('btn-editor-engine-legacy');

  if (closeBtn) closeBtn.addEventListener('click', () => requestCloseWorkspace());
  if (cancelBtn) cancelBtn.addEventListener('click', () => requestCloseWorkspace());
  if (saveBtn) saveBtn.addEventListener('click', () => handleCommitSave());
  if (editRootBtn) editRootBtn.addEventListener('click', () => enterSectionEditor(null));
  if (cancelSectionBtn) cancelSectionBtn.addEventListener('click', () => requestCancelSection());
  if (applySectionBtn) applySectionBtn.addEventListener('click', () => handleApplySection(false));
  if (forceApplyBtn) forceApplyBtn.addEventListener('click', () => handleApplySection(true));

  if (openApiBtn) {
    openApiBtn.addEventListener('click', () => {
      if (onOpenOpenApiCallback) onOpenOpenApiCallback();
    });
  }

  if (copyDocBtn) {
    copyDocBtn.addEventListener('click', () => {
      const textToCopy = workspaceMode === 'SECTION_EDITOR' && editorController
        ? editorController.getValue()
        : rootText;
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(textToCopy).catch(() => {});
      }
      copyDocBtn.textContent = '✅ Copied!';
      setTimeout(() => {
        copyDocBtn.textContent = '📋 Copy';
      }, 1500);
    });
  }

  if (viewModeToggle) {
    viewModeToggle.addEventListener('click', (e) => {
      const btn = e.target.closest('[data-mode]');
      if (!btn) return;
      const nextMode = btn.getAttribute('data-mode') === 'raw' ? 'raw' : 'rich';
      viewerDisplayMode = nextMode;
      syncViewModeButtons();
      if (viewerController) {
        viewerController.setMode(nextMode);
      }
    });
  }

  if (templateSelect) {
    templateSelect.addEventListener('change', async () => {
      const nextKey = templateSelect.value;
      const tpl = normalizedTemplates.find(t => t.key === nextKey);
      if (!tpl) return;

      if (dirty) {
        const outcome = await confirmDialog({
          title: 'Apply Template',
          message: 'Replace your current edits with the selected template?',
          confirmLabel: 'Apply Template',
          cancelLabel: 'Cancel',
          danger: true
        });
        if (outcome !== 'confirm') {
          templateSelect.value = currentTemplateKey;
          return;
        }
      }

      const nextText = tpl.buildText();
      currentTemplateKey = nextKey;
      rootText = nextText;
      initialRootText = nextText;
      dirty = false;
      enterDocumentViewer();
    });
  }

  if (formatBtn) {
    formatBtn.addEventListener('click', () => {
      if (!editorController) return;
      editorController.formatCode();
      updateValidationUi(editorController.getValue());
      syncDirtyBadge();
      updateDescriptorPill();
    });
  }

  if (minifyBtn) {
    minifyBtn.addEventListener('click', () => {
      if (!editorController) return;
      editorController.compactCode();
      updateValidationUi(editorController.getValue());
      syncDirtyBadge();
      updateDescriptorPill();
    });
  }

  const switchEngine = (engineName) => {
    setEditorEngine(engineName);
    if (editorController) {
      editorController.setEngine(engineName);
      editorController.focus();
    }
    syncEngineToggleButtons();
  };
  if (betaEngineBtn) betaEngineBtn.addEventListener('click', () => switchEngine('highlight-api'));
  if (legacyEngineBtn) legacyEngineBtn.addEventListener('click', () => switchEngine('legacy-duallayer'));

  const breadcrumbsEl = el('workspace-breadcrumbs');
  if (breadcrumbsEl) {
    breadcrumbsEl.addEventListener('click', (e) => {
      const rootCrumb = e.target.closest('[data-bc-root="true"]');
      if (rootCrumb && workspaceMode === 'SECTION_EDITOR') {
        requestCancelSection();
      }
    });
  }

  document.addEventListener('keydown', (e) => {
    if (!isOpen || e.key !== 'Escape') return;
    const confirmModal = el('modal-confirm');
    if (confirmModal && !confirmModal.classList.contains('hidden')) {
      return;
    }
    e.preventDefault();
    requestCloseWorkspace();
  });
}

/**
 * Opens the unified Document Workspace modal.
 *
 * @param {object} [opts]
 * @param {string} [opts.title='Document Workspace']
 * @param {string} [opts.text='']
 * @param {string|null} [opts.format=null]
 * @param {boolean} [opts.editable=false]
 * @param {string} [opts.saveLabel='💾 Save']
 * @param {object|string|string[]|null} [opts.headers=null]
 * @param {string|null} [opts.bufferType=null]
 * @param {Array|object|null} [opts.templates=null]
 * @param {string|null} [opts.defaultTemplateKey=null]
 * @param {string|null} [opts.initialSectionPath=null]
 * @param {boolean} [opts.startInEditor=false]
 * @param {'rich'|'raw'} [opts.mode='rich']
 * @param {Function|null} [opts.onCommit=null]
 * @param {Function|null} [opts.onOpenOpenApi=null]
 */
export function openDocumentWorkspace(opts = {}) {
  initDocumentWorkspace();
  const modal = el('modal-document-workspace');
  if (!modal) return;

  currentTitle = opts.title || 'Document Workspace';
  rootText = opts.text != null ? String(opts.text) : '';
  initialRootText = rootText;
  rootFormat = resolveDocumentFormat(rootText, opts.format || null);
  rootHeaders = opts.headers || null;
  rootBufferType = opts.bufferType || null;
  isEditable = Boolean(opts.editable);
  currentSaveLabel = opts.saveLabel || '💾 Save';
  onCommitCallback = typeof opts.onCommit === 'function' ? opts.onCommit : null;
  onOpenOpenApiCallback = typeof opts.onOpenOpenApi === 'function' ? opts.onOpenOpenApi : null;
  viewerDisplayMode = opts.mode === 'raw' ? 'raw' : 'rich';
  dirty = false;
  saving = false;

  normalizedTemplates = normalizeTemplateList(opts.templates);
  const tplSelect = el('workspace-template-select');
  const openApiBtn = el('btn-workspace-open-openapi');
  if (tplSelect) {
    tplSelect.innerHTML = normalizedTemplates
      .map(t => `<option value="${escapeHtml(t.key)}">${escapeHtml(t.label)}</option>`)
      .join('');
    currentTemplateKey = (opts.defaultTemplateKey && normalizedTemplates.some(t => t.key === opts.defaultTemplateKey))
      ? opts.defaultTemplateKey
      : (normalizedTemplates[0]?.key || '');
    if (currentTemplateKey) {
      tplSelect.value = currentTemplateKey;
    }
  }
  if (openApiBtn) {
    openApiBtn.classList.toggle('hidden', !onOpenOpenApiCallback);
  }

  const titleEl = el('workspace-title');
  if (titleEl) {
    titleEl.textContent = currentTitle;
  }

  isOpen = true;
  modal.classList.remove('hidden');

  if (opts.startInEditor && isEditable) {
    const chain = opts.initialSectionPath
      ? findNodeChainByPath(rootText, opts.initialSectionPath, rootHeaders)
      : null;
    enterSectionEditor(chain);
  } else {
    enterDocumentViewer(opts.initialSectionPath || null);
  }
}

/**
 * Closes the Document Workspace modal.
 * If `force` is false and unsaved changes exist, prompts via confirmation dialog.
 *
 * @param {boolean} [force=false]
 * @returns {boolean} True if closed synchronously
 */
export function closeDocumentWorkspace(force = false) {
  if (!isOpen) return true;
  if (!force) {
    requestCloseWorkspace();
    return false;
  }

  destroyEditor();
  destroyViewer();
  isOpen = false;
  dirty = false;
  saving = false;
  activeSectionChain = null;
  activeSectionInitialText = '';
  onCommitCallback = null;
  onOpenOpenApiCallback = null;

  const modal = el('modal-document-workspace');
  if (modal) modal.classList.add('hidden');
  return true;
}

export function isWorkspaceOpen() {
  return isOpen;
}

export function isWorkspaceDirty() {
  if (!isOpen) return false;
  if (dirty) return true;
  if (workspaceMode === 'SECTION_EDITOR' && editorController) {
    return editorController.getValue() !== activeSectionInitialText;
  }
  return false;
}

export function getWorkspaceRootText() {
  return rootText;
}
