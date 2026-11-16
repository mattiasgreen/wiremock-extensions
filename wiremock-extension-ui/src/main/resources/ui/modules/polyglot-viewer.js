/**
 * Non-destructive Polyglot Tree Viewer with zero-placeholder segment rendering,
 * recursive collapsible nested payload cards, and single-delegated container events.
 */
import { escapeHtml } from './dom.js';
import { detectCharset } from './charset-codec.js';
import { formatPayload, highlightPayload } from './highlighter.js';
import {
  buildPolyglotSegments,
  formatPayloadDescriptor,
  formatCasualBufferDescriptor
} from './polyglot-ast.js';

export { detectCharset, formatPayloadDescriptor, formatCasualBufferDescriptor };

function ensureViewerStylesInjected() {
  if (typeof document === 'undefined' || !document.head) return;
  if (document.getElementById('wm-polyglot-viewer-styles')) return;

  const styleEl = document.createElement('style');
  styleEl.id = 'wm-polyglot-viewer-styles';
  styleEl.textContent = `
    .polyglot-tree {
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
      font-size: 0.84rem;
      line-height: 1.5;
      color: var(--text-color, #e2e8f0);
    }
    .polyglot-code-seg {
      white-space: pre-wrap;
      word-break: break-word;
    }
    .polyglot-raw-pre,
    .polyglot-rich-pre {
      margin: 0;
      white-space: pre-wrap;
      word-break: break-word;
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
      font-size: 0.84rem;
      line-height: 1.5;
    }
    .nested-payload-block {
      display: block;
      margin: 6px 0 6px 14px;
      border: 1px solid var(--border-color, #334155);
      border-left: 3px solid var(--accent-color, #38bdf8);
      border-radius: 6px;
      background: rgba(15, 23, 42, 0.72);
      white-space: normal;
      overflow: hidden;
    }
    .nested-payload-header {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 6px;
      padding: 4px 8px;
      background: rgba(30, 41, 59, 0.85);
      border-bottom: 1px solid var(--border-color, #334155);
    }
    .nested-payload-block.collapsed > .nested-payload-header {
      border-bottom: none;
    }
    .nested-payload-block.collapsed > .nested-payload-body {
      display: none;
    }
    .nested-payload-key {
      font-weight: 600;
      color: #7dd3fc;
      font-size: 0.78rem;
    }
    .nested-payload-actions {
      margin-left: auto;
      display: inline-flex;
      align-items: center;
      gap: 4px;
    }
    .nested-payload-body {
      padding: 8px 10px;
      overflow-x: auto;
    }
    .nested-payload-code {
      margin: 0;
      white-space: pre-wrap;
      word-break: break-word;
      font-family: inherit;
      font-size: 0.82rem;
      line-height: 1.45;
    }
  `;
  document.head.appendChild(styleEl);
}

function indexNodeChains(nodes, parentChain, nodeMap) {
  for (const node of nodes) {
    const chain = [...parentChain, node];
    nodeMap.set(node.id, chain);
    if (Array.isArray(node.children) && node.children.length > 0) {
      indexNodeChains(node.children, chain, nodeMap);
    }
  }
}

/**
 * Builds polyglot AST segments and indexes every node ID at all nesting depths to its full ancestor chain.
 *
 * @param {string} text
 * @param {number} [maxDepth=5]
 * @param {object|string|string[]|null} [headers=null]
 * @returns {{ formattedText: string, format: string, charset: string, nodes: Array<object>, segments: Array<object>, nodeMap: Map<string, Array<object>> }}
 */
export function collectSegmentsAndNodes(text, maxDepth = 5, headers = null) {
  const built = buildPolyglotSegments(text || '', maxDepth, headers);
  const nodeMap = new Map();
  indexNodeChains(built.nodes, [], nodeMap);
  return {
    ...built,
    nodeMap
  };
}

function sliceSegmentsDirectly(rawText, childNodes, format) {
  const sorted = [...childNodes].sort((a, b) => a.span.start - b.span.start);
  const segments = [];
  let cursor = 0;
  for (const child of sorted) {
    if (!child.span || child.span.start < cursor || child.span.end < child.span.start) continue;
    if (child.span.start > cursor) {
      segments.push({ type: 'code', text: rawText.slice(cursor, child.span.start), format });
    }
    segments.push({ type: 'embedded', node: child });
    cursor = child.span.end;
  }
  if (cursor < rawText.length) {
    segments.push({ type: 'code', text: rawText.slice(cursor), format });
  }
  return segments;
}

function resolveChildDisplaySegments(node) {
  const displayStr = node.formattedValue || formatPayload(node.unescapedValue);
  const built = buildPolyglotSegments(displayStr, 1);
  if (built.nodes.length === node.children.length) {
    let childIdx = 0;
    return built.segments.map(seg => {
      if (seg.type === 'embedded') {
        return { type: 'embedded', node: node.children[childIdx++] };
      }
      return seg;
    });
  }
  return sliceSegmentsDirectly(node.unescapedValue, node.children, node.format);
}

function renderNodeCardHtml(node, editable, showOpenWorkspace = true) {
  const sizeKb = (node.byteLength / 1024).toFixed(1);
  const badgeLabel = formatPayloadDescriptor({
    format: node.format,
    encoding: node.encoding,
    charset: node.charset,
    sizeKb
  });

  const actionBtn = editable
    ? `<button type="button" class="btn btn-tiny btn-workspace-edit-section" data-node-id="${escapeHtml(node.id)}" title="Edit ${escapeHtml(node.path)}">✏️ Edit Section</button>`
    : (showOpenWorkspace
      ? `<button type="button" class="btn btn-tiny btn-nested-open-workspace" data-node-id="${escapeHtml(node.id)}" title="Open ${escapeHtml(node.path)} in Workspace">⛶ Open in Workspace</button>`
      : '');

  let bodyHtml;
  if (Array.isArray(node.children) && node.children.length > 0) {
    const childSegments = resolveChildDisplaySegments(node);
    bodyHtml = `<div class="polyglot-tree">${renderSegmentsHtml(childSegments, node.format, editable, showOpenWorkspace)}</div>`;
  } else {
    const displayText = node.formattedValue || formatPayload(node.unescapedValue);
    bodyHtml = `<pre class="nested-payload-code"><code>${highlightPayload(displayText, node.format)}</code></pre>`;
  }

  return `<div class="nested-payload-block" data-node-id="${escapeHtml(node.id)}" data-node-path="${escapeHtml(node.path)}" data-format="${escapeHtml(node.format)}" data-encoding="${escapeHtml(node.encoding)}">`
    + `<div class="nested-payload-header">`
    + `<button type="button" class="btn btn-tiny btn-nested-toggle" data-node-id="${escapeHtml(node.id)}" aria-expanded="true" title="Collapse or expand">▾</button>`
    + `<span class="nested-payload-key" title="${escapeHtml(node.path)}">${escapeHtml(node.key || node.path)}</span>`
    + `<span class="preview-pill-badge nested-payload-badge" data-format="${escapeHtml(node.format)}" data-encoding="${escapeHtml(node.encoding)}">${escapeHtml(badgeLabel)}</span>`
    + `<span class="nested-payload-actions">`
    + `<button type="button" class="btn btn-tiny btn-nested-copy" data-node-id="${escapeHtml(node.id)}" title="Copy unescaped payload">📋 Copy</button>`
    + actionBtn
    + `</span>`
    + `</div>`
    + `<div class="nested-payload-body">${bodyHtml}</div>`
    + `</div>`;
}

function renderSegmentsHtml(segments, outerFormat, editable, showOpenWorkspace = true) {
  let html = '';
  for (const seg of segments) {
    if (seg.type === 'code') {
      html += `<span class="polyglot-code-seg">${highlightPayload(seg.text, seg.format || outerFormat)}</span>`;
    } else if (seg.type === 'embedded' && seg.node) {
      html += renderNodeCardHtml(seg.node, editable, showOpenWorkspace);
    }
  }
  return html;
}

function renderCollectedHtml(collected, editable, showOpenWorkspace = true) {
  const { formattedText, format, segments } = collected;
  const hasEmbedded = segments.some(s => s.type === 'embedded');
  if (!hasEmbedded) {
    return `<pre class="polyglot-rich-pre code-block">${highlightPayload(formattedText, format)}</pre>`;
  }
  return `<div class="polyglot-tree code-block">${renderSegmentsHtml(segments, format, editable, showOpenWorkspace)}</div>`;
}

/**
 * Renders a payload as either wire-exact highlighted Raw `<pre>` or a recursive foldable Rich Polyglot Tree.
 * Read-only views never emit disabled Edit buttons.
 *
 * @param {string} text
 * @param {object} [options]
 * @param {'rich'|'raw'} [options.mode='rich']
 * @param {boolean} [options.editable=false]
 * @param {boolean} [options.showOpenWorkspace=true]
 * @param {number} [options.maxDepth=5]
 * @param {object|string|string[]|null} [options.headers=null]
 * @returns {string}
 */
export function renderPolyglotTreeHtml(text, { mode = 'rich', editable = false, showOpenWorkspace = true, maxDepth = 5, headers = null } = {}) {
  if (typeof text !== 'string' || !text.trim()) {
    return '<div class="muted-text text-sm">No payload</div>';
  }

  ensureViewerStylesInjected();

  if (mode === 'raw') {
    return `<pre class="polyglot-raw-pre code-block">${highlightPayload(text)}</pre>`;
  }

  const collected = collectSegmentsAndNodes(text, maxDepth, headers);
  return renderCollectedHtml(collected, Boolean(editable), showOpenWorkspace);
}

/**
 * Mounts an interactive foldable Polyglot Tree Viewer into `containerEl` with a single delegated click handler.
 *
 * @param {HTMLElement} containerEl
 * @param {object} [options]
 * @param {string} [options.text='']
 * @param {'rich'|'raw'} [options.mode='rich']
 * @param {boolean} [options.editable=false]
 * @param {boolean} [options.showOpenWorkspace=true]
 * @param {number} [options.maxDepth=5]
 * @param {object|string|string[]|null} [options.headers=null]
 * @param {Function|null} [options.onOpenWorkspace=null]
 * @param {Function|null} [options.onEditSection=null]
 * @returns {object|null}
 */
export function mountPolyglotViewer(containerEl, {
  text = '',
  mode = 'rich',
  editable = false,
  showOpenWorkspace = true,
  maxDepth = 5,
  headers = null,
  onOpenWorkspace = null,
  onEditSection = null
} = {}) {
  if (!containerEl) return null;

  ensureViewerStylesInjected();

  let currentText = typeof text === 'string' ? text : String(text ?? '');
  let currentMode = mode === 'raw' ? 'raw' : 'rich';
  let currentEditable = Boolean(editable);
  let currentShowOpenWorkspace = Boolean(showOpenWorkspace);
  let currentHeaders = headers;
  let currentNodeMap = new Map();
  let destroyed = false;

  function getSnapshotState() {
    return {
      text: currentText,
      mode: currentMode,
      editable: currentEditable,
      headers: currentHeaders
    };
  }

  function render() {
    if (destroyed) return;
    if (typeof currentText !== 'string' || !currentText.trim()) {
      currentNodeMap = new Map();
      containerEl.innerHTML = '<div class="muted-text text-sm">No payload</div>';
      return;
    }

    if (currentMode === 'raw') {
      currentNodeMap = new Map();
      containerEl.innerHTML = `<pre class="polyglot-raw-pre code-block">${highlightPayload(currentText)}</pre>`;
      return;
    }

    const collected = collectSegmentsAndNodes(currentText, maxDepth, currentHeaders);
    currentNodeMap = collected.nodeMap;
    containerEl.innerHTML = renderCollectedHtml(collected, currentEditable, currentShowOpenWorkspace);
  }

  const onClick = (e) => {
    const toggleBtn = e.target.closest('.btn-nested-toggle');
    if (toggleBtn && containerEl.contains(toggleBtn)) {
      e.stopPropagation();
      const block = toggleBtn.closest('.nested-payload-block');
      if (block) {
        const isCollapsed = block.classList.toggle('collapsed');
        const body = block.querySelector(':scope > .nested-payload-body');
        if (body) body.classList.toggle('hidden', isCollapsed);
        toggleBtn.textContent = isCollapsed ? '▸' : '▾';
        toggleBtn.setAttribute('aria-expanded', String(!isCollapsed));
      }
      return;
    }

    const copyBtn = e.target.closest('.btn-nested-copy');
    if (copyBtn && containerEl.contains(copyBtn)) {
      e.stopPropagation();
      const nodeId = copyBtn.getAttribute('data-node-id');
      const chain = currentNodeMap.get(nodeId);
      const node = chain && chain.length > 0 ? chain[chain.length - 1] : null;
      if (node) {
        const copyText = node.unescapedValue ?? node.formattedValue ?? '';
        if (typeof navigator !== 'undefined' && navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(copyText).catch(() => {});
        }
        copyBtn.textContent = '✅ Copied!';
        setTimeout(() => {
          if (!destroyed) copyBtn.textContent = '📋 Copy';
        }, 1500);
      }
      return;
    }

    const openBtn = e.target.closest('.btn-nested-open-workspace');
    if (openBtn && containerEl.contains(openBtn)) {
      e.stopPropagation();
      const nodeId = openBtn.getAttribute('data-node-id');
      const chain = currentNodeMap.get(nodeId);
      if (chain && typeof onOpenWorkspace === 'function') {
        onOpenWorkspace(chain, getSnapshotState());
      }
      return;
    }

    const editBtn = e.target.closest('.btn-workspace-edit-section');
    if (editBtn && containerEl.contains(editBtn)) {
      e.stopPropagation();
      const nodeId = editBtn.getAttribute('data-node-id');
      const chain = currentNodeMap.get(nodeId);
      if (chain && typeof onEditSection === 'function') {
        onEditSection(chain, getSnapshotState());
      }
    }
  };

  containerEl.addEventListener('click', onClick);
  render();

  return {
    update: (opts = {}) => {
      if (destroyed) return;
      if (opts.text !== undefined) currentText = typeof opts.text === 'string' ? opts.text : String(opts.text ?? '');
      if (opts.mode !== undefined) currentMode = opts.mode === 'raw' ? 'raw' : 'rich';
      if (opts.headers !== undefined) currentHeaders = opts.headers;
      if (opts.editable !== undefined) currentEditable = Boolean(opts.editable);
      render();
    },
    getText: () => currentText,
    getMode: () => currentMode,
    setMode: (m) => {
      if (destroyed) return;
      currentMode = m === 'raw' ? 'raw' : 'rich';
      render();
    },
    destroy: () => {
      if (destroyed) return;
      destroyed = true;
      containerEl.removeEventListener('click', onClick);
      currentNodeMap.clear();
      containerEl.innerHTML = '';
    }
  };
}
