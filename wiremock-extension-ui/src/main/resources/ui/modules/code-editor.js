/**
 * Unified Code Editor engine supporting the zero-DOM-bloat CSS Custom Highlight API (`highlight-api`)
 * and the fallback Dual-Layer `<pre>` + `<textarea>` engine (`legacy-duallayer`).
 * Pure in-memory engine preference state (no localStorage).
 */
import {
  detectFormat,
  formatPayload,
  highlightPayload,
  minifyXml,
  tokenizeOffsets,
  MAX_HIGHLIGHT_PAYLOAD_CHARS
} from './highlighter.js';

const HIGHLIGHT_TOKEN_TYPES = [
  'key',
  'string',
  'number',
  'bool',
  'null',
  'punct',
  'xml-tag',
  'xml-attr',
  'xml-val',
  'comment'
];

let defaultEngine = 'highlight-api';
let nextInstanceId = 0;

// Registry of mounted highlight-api instances so global CSS.highlights merges cleanly across editors
const highlightRegistry = new Map();

/**
 * Checks whether the browser supports the CSS Custom Highlight API with StaticRange.
 *
 * @returns {boolean}
 */
export function isHighlightApiSupported() {
  return typeof CSS !== 'undefined'
    && Boolean(CSS.highlights)
    && typeof Highlight !== 'undefined'
    && typeof StaticRange !== 'undefined';
}

/**
 * Returns the current in-memory default code editor engine.
 *
 * @returns {'highlight-api' | 'legacy-duallayer'}
 */
export function getEditorEngine() {
  return defaultEngine;
}

/**
 * Sets the in-memory default code editor engine.
 *
 * @param {'highlight-api' | 'legacy-duallayer'} engine
 * @returns {'highlight-api' | 'legacy-duallayer'}
 */
export function setEditorEngine(engine) {
  if (engine !== 'highlight-api' && engine !== 'legacy-duallayer') {
    throw new Error(`Invalid code editor engine: ${engine}`);
  }
  defaultEngine = engine;
  return defaultEngine;
}

function ensureHighlightStylesInjected() {
  if (typeof document === 'undefined' || !document.head) return;
  if (document.getElementById('wm-highlight-api-styles')) return;

  const styleEl = document.createElement('style');
  styleEl.id = 'wm-highlight-api-styles';
  styleEl.textContent = `
    .code-editor-surface.engine-highlight-api {
      display: flex;
      flex: 1 1 0%;
      width: 100%;
      min-height: 120px;
      background: var(--bg-code, #0b1120);
      border: 1px solid var(--border-color, #334155);
      border-radius: 6px;
      overflow: hidden;
      font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", "Courier New", monospace;
      font-size: 0.85rem;
      line-height: 1.5;
    }
    .code-editor-gutter {
      flex: 0 0 auto;
      min-width: 36px;
      padding: 12px 8px;
      text-align: right;
      color: var(--text-muted, #64748b);
      background: rgba(15, 23, 42, 0.55);
      border-right: 1px solid var(--border-color, #334155);
      user-select: none;
      overflow: hidden;
      white-space: pre;
      font-family: inherit;
      font-size: inherit;
      line-height: inherit;
    }
    .code-editor-pre {
      flex: 1 1 0%;
      margin: 0;
      padding: 12px 14px;
      overflow: auto;
      outline: none;
      white-space: pre;
      tab-size: 2;
      color: var(--text-color, #e2e8f0);
      background: transparent;
      font-family: inherit;
      font-size: inherit;
      line-height: inherit;
    }
    ::highlight(wm-hl-key) { color: #7dd3fc; }
    ::highlight(wm-hl-string) { color: #86efac; }
    ::highlight(wm-hl-number) { color: #fbbf24; }
    ::highlight(wm-hl-bool) { color: #f472b6; }
    ::highlight(wm-hl-null) { color: #94a3b8; }
    ::highlight(wm-hl-punct) { color: #94a3b8; }
    ::highlight(wm-hl-xml-tag) { color: #38bdf8; }
    ::highlight(wm-hl-xml-attr) { color: #fbbf24; }
    ::highlight(wm-hl-xml-val) { color: #34d399; }
    ::highlight(wm-hl-comment) { color: #94a3b8; font-style: italic; }
  `;
  document.head.appendChild(styleEl);
}

function syncGlobalCssHighlights() {
  if (!isHighlightApiSupported()) return;

  for (const type of HIGHLIGHT_TOKEN_TYPES) {
    const merged = [];
    for (const rangesByType of highlightRegistry.values()) {
      const list = rangesByType.get(type);
      if (list && list.length > 0) {
        for (let i = 0; i < list.length; i++) {
          merged.push(list[i]);
        }
      }
    }
    const key = `wm-hl-${type}`;
    if (merged.length > 0) {
      CSS.highlights.set(key, new Highlight(...merged));
    } else {
      CSS.highlights.delete(key);
    }
  }
}

function computeIndentEdit(text, selStart, selEnd, isShift) {
  const blockStart = text.lastIndexOf('\n', Math.max(0, selStart - 1)) + 1;
  const rawBlockEnd = text.indexOf('\n', selEnd);
  const blockEnd = rawBlockEnd < 0 ? text.length : rawBlockEnd;
  const block = text.slice(blockStart, blockEnd);
  const lines = block.split('\n');

  let removedFirst = 0;
  let totalDelta = 0;
  const updatedLines = lines.map((line, idx) => {
    if (!isShift) {
      totalDelta += 2;
      if (idx === 0) removedFirst = 2;
      return '  ' + line;
    }
    let removeCount = 0;
    if (line.startsWith('  ')) removeCount = 2;
    else if (line.startsWith(' ') || line.startsWith('\t')) removeCount = 1;
    if (idx === 0) removedFirst = -removeCount;
    totalDelta -= removeCount;
    return line.slice(removeCount);
  });

  const replacement = updatedLines.join('\n');
  const nextStart = Math.max(blockStart, selStart + removedFirst);
  const nextEnd = Math.max(nextStart, selEnd + totalDelta);
  return { start: blockStart, end: blockEnd, replacement, nextStart, nextEnd };
}

function computeEnterEdit(text, selStart, selEnd) {
  const lineStart = text.lastIndexOf('\n', Math.max(0, selStart - 1)) + 1;
  const linePrefix = text.slice(lineStart, selStart);
  const indentMatch = linePrefix.match(/^[ ]*/);
  const baseIndent = indentMatch ? indentMatch[0] : '';
  const trimmedBefore = linePrefix.trimEnd();
  const extra = (trimmedBefore.endsWith('{') || trimmedBefore.endsWith('[')) ? '  ' : '';
  const insertion = '\n' + baseIndent + extra;
  const caret = selStart + insertion.length;
  return { start: selStart, end: selEnd, replacement: insertion, nextStart: caret, nextEnd: caret };
}

function createHighlightApiAdapter(containerEl, initialValue, initialFormat, readOnly, hooks) {
  ensureHighlightStylesInjected();

  const instanceId = ++nextInstanceId;
  const surfaceEl = document.createElement('div');
  surfaceEl.className = 'code-editor-surface engine-highlight-api';
  surfaceEl.setAttribute('data-engine', 'highlight-api');

  const gutterEl = document.createElement('div');
  gutterEl.className = 'code-editor-gutter';
  gutterEl.setAttribute('aria-hidden', 'true');

  const preEl = document.createElement('pre');
  preEl.className = 'code-editor-pre';
  preEl.spellcheck = false;
  preEl.setAttribute('data-testid', 'code-editor-input');

  let needsPlainTextPolyfill = false;
  if (readOnly) {
    preEl.contentEditable = 'false';
  } else {
    preEl.contentEditable = 'plaintext-only';
    if (preEl.contentEditable !== 'plaintext-only') {
      preEl.contentEditable = 'true';
      needsPlainTextPolyfill = true;
    }
  }

  let textNode = document.createTextNode(initialValue);
  preEl.appendChild(textNode);

  surfaceEl.appendChild(gutterEl);
  surfaceEl.appendChild(preEl);
  containerEl.replaceChildren(surfaceEl);

  let format = initialFormat;
  let lastLineCount = -1;

  function updateGutter(val) {
    const lineCount = Math.max(1, val.split('\n').length);
    if (lineCount === lastLineCount) return;
    lastLineCount = lineCount;
    const nums = new Array(lineCount);
    for (let i = 0; i < lineCount; i++) {
      nums[i] = String(i + 1);
    }
    gutterEl.textContent = nums.join('\n');
  }

  function updateHighlights(val) {
    if (!isHighlightApiSupported()) return;
    const rangesByType = new Map();
    const resolvedFmt = (format === 'json' || format === 'xml') ? format : detectFormat(val);
    if (val.length <= MAX_HIGHLIGHT_PAYLOAD_CHARS && (resolvedFmt === 'json' || resolvedFmt === 'xml')) {
      const tokens = tokenizeOffsets(val, resolvedFmt);
      const textLen = textNode.length;
      for (let i = 0; i < tokens.length; i++) {
        const tok = tokens[i];
        if (tok.start >= 0 && tok.end <= textLen && tok.start < tok.end) {
          let list = rangesByType.get(tok.type);
          if (!list) {
            list = [];
            rangesByType.set(tok.type, list);
          }
          list.push(new StaticRange({
            startContainer: textNode,
            startOffset: tok.start,
            endContainer: textNode,
            endOffset: tok.end
          }));
        }
      }
    }
    highlightRegistry.set(instanceId, rangesByType);
    syncGlobalCssHighlights();
  }

  function extractPlainText(node) {
    if (node.nodeType === 3) return node.data;
    let out = '';
    for (let i = 0; i < node.childNodes.length; i++) {
      const child = node.childNodes[i];
      if (child.nodeType === 3) {
        out += child.data;
      } else if (child.nodeName === 'BR') {
        out += '\n';
      } else {
        if (child.nodeName === 'DIV' && out.length > 0 && !out.endsWith('\n')) {
          out += '\n';
        }
        out += extractPlainText(child);
      }
    }
    return out;
  }

  function resolveOffsetFromDom(targetNode, targetOffset) {
    if (targetNode === textNode) {
      return Math.min(textNode.length, Math.max(0, targetOffset));
    }
    if (targetNode === preEl) {
      return targetOffset === 0 ? 0 : textNode.length;
    }
    // Walk up or fallback
    return textNode.length;
  }

  function getSelectionOffsets() {
    const sel = typeof window !== 'undefined' && window.getSelection ? window.getSelection() : null;
    if (!sel || sel.rangeCount === 0) {
      return { start: textNode.length, end: textNode.length };
    }
    const range = sel.getRangeAt(0);
    if (!preEl.contains(range.startContainer) || !preEl.contains(range.endContainer)) {
      return { start: textNode.length, end: textNode.length };
    }
    const start = resolveOffsetFromDom(range.startContainer, range.startOffset);
    const end = resolveOffsetFromDom(range.endContainer, range.endOffset);
    return start <= end ? { start, end } : { start: end, end: start };
  }

  function setSelectionOffsets(start, end) {
    const sel = typeof window !== 'undefined' && window.getSelection ? window.getSelection() : null;
    if (!sel) return;
    const len = textNode.length;
    const s = Math.max(0, Math.min(len, start));
    const e = Math.max(s, Math.min(len, end));
    const range = document.createRange();
    range.setStart(textNode, s);
    range.setEnd(textNode, e);
    sel.removeAllRanges();
    sel.addRange(range);
  }

  function normalizeTextNode() {
    if (preEl.childNodes.length === 1 && preEl.firstChild && preEl.firstChild.nodeType === 3) {
      textNode = preEl.firstChild;
      return;
    }
    const plain = extractPlainText(preEl);
    textNode = document.createTextNode(plain);
    preEl.replaceChildren(textNode);
    setSelectionOffsets(plain.length, plain.length);
  }

  function spliceEdit(start, end, replacement, nextStart, nextEnd) {
    const prev = textNode.data;
    const next = prev.slice(0, start) + replacement + prev.slice(end);
    textNode.data = next;
    setSelectionOffsets(nextStart, nextEnd);
    updateGutter(next);
    updateHighlights(next);
    hooks.onValueChanged(next);
  }

  const onScroll = () => {
    gutterEl.scrollTop = preEl.scrollTop;
  };

  const onInput = () => {
    normalizeTextNode();
    const val = textNode.data;
    updateGutter(val);
    updateHighlights(val);
    hooks.onValueChanged(val);
  };

  const onKeyDown = (e) => {
    if ((e.ctrlKey || e.metaKey) && (e.key === 's' || e.key === 'S')) {
      e.preventDefault();
      hooks.onSaveShortcut?.(textNode.data);
      return;
    }
    if (readOnly) return;

    if (e.key === 'Tab') {
      e.preventDefault();
      const { start, end } = getSelectionOffsets();
      if (!e.shiftKey && start === end) {
        spliceEdit(start, end, '  ', start + 2, start + 2);
      } else {
        const edit = computeIndentEdit(textNode.data, start, end, e.shiftKey);
        spliceEdit(edit.start, edit.end, edit.replacement, edit.nextStart, edit.nextEnd);
      }
      return;
    }

    if (e.key === 'Enter') {
      e.preventDefault();
      const { start, end } = getSelectionOffsets();
      const edit = computeEnterEdit(textNode.data, start, end);
      spliceEdit(edit.start, edit.end, edit.replacement, edit.nextStart, edit.nextEnd);
    }
  };

  const onPaste = (e) => {
    if (readOnly || !needsPlainTextPolyfill) return;
    e.preventDefault();
    const pasteText = e.clipboardData ? e.clipboardData.getData('text/plain') : '';
    const { start, end } = getSelectionOffsets();
    spliceEdit(start, end, pasteText, start + pasteText.length, start + pasteText.length);
  };

  const onBeforeInput = (e) => {
    if (readOnly || !needsPlainTextPolyfill) return;
    if (e.inputType === 'insertFromPaste' || e.inputType === 'formatBold' || e.inputType === 'formatItalic') {
      e.preventDefault();
      if (e.inputType === 'insertFromPaste' && e.dataTransfer) {
        const pasteText = e.dataTransfer.getData('text/plain') || '';
        const { start, end } = getSelectionOffsets();
        spliceEdit(start, end, pasteText, start + pasteText.length, start + pasteText.length);
      }
    }
  };

  preEl.addEventListener('scroll', onScroll);
  preEl.addEventListener('input', onInput);
  preEl.addEventListener('keydown', onKeyDown);
  preEl.addEventListener('paste', onPaste);
  preEl.addEventListener('beforeinput', onBeforeInput);

  updateGutter(initialValue);
  updateHighlights(initialValue);

  return {
    getValue: () => textNode.data,
    setValue: (val) => {
      textNode.data = val;
      if (preEl.childNodes.length !== 1 || preEl.firstChild !== textNode) {
        preEl.replaceChildren(textNode);
      }
      updateGutter(val);
      updateHighlights(val);
    },
    setFormat: (fmt) => {
      format = fmt;
      updateHighlights(textNode.data);
    },
    focus: () => preEl.focus(),
    destroy: () => {
      preEl.removeEventListener('scroll', onScroll);
      preEl.removeEventListener('input', onInput);
      preEl.removeEventListener('keydown', onKeyDown);
      preEl.removeEventListener('paste', onPaste);
      preEl.removeEventListener('beforeinput', onBeforeInput);
      highlightRegistry.delete(instanceId);
      syncGlobalCssHighlights();
    }
  };
}

function createLegacyDualLayerAdapter(containerEl, initialValue, initialFormat, readOnly, hooks) {
  const wrapper = document.createElement('div');
  wrapper.className = 'dual-layer-editor-wrapper mode-rich';

  const backdrop = document.createElement('pre');
  backdrop.className = 'dual-layer-backdrop';
  backdrop.setAttribute('aria-hidden', 'true');

  const code = document.createElement('code');
  code.className = 'dual-layer-code';
  backdrop.appendChild(code);

  const textarea = document.createElement('textarea');
  textarea.className = 'dual-layer-input code-input';
  textarea.setAttribute('data-testid', 'code-editor-input');
  textarea.spellcheck = false;
  textarea.readOnly = Boolean(readOnly);
  textarea.value = initialValue;

  wrapper.appendChild(backdrop);
  wrapper.appendChild(textarea);
  containerEl.replaceChildren(wrapper);

  let format = initialFormat;

  function syncScroll() {
    backdrop.scrollTop = textarea.scrollTop;
    backdrop.scrollLeft = textarea.scrollLeft;
  }

  function updateBackdrop() {
    const val = textarea.value;
    if (val.length > MAX_HIGHLIGHT_PAYLOAD_CHARS) {
      wrapper.classList.remove('mode-rich');
      wrapper.classList.add('mode-raw', 'mode-degraded');
      code.textContent = '';
    } else {
      wrapper.classList.remove('mode-raw', 'mode-degraded');
      wrapper.classList.add('mode-rich');
      const resolvedFmt = (format === 'json' || format === 'xml' || format === 'kv') ? format : null;
      code.innerHTML = highlightPayload(val, resolvedFmt) + (val.endsWith('\n') ? ' ' : '');
    }
    syncScroll();
  }

  function applyTextareaEdit(start, end, replacement, nextStart, nextEnd) {
    const prev = textarea.value;
    const next = prev.slice(0, start) + replacement + prev.slice(end);
    textarea.value = next;
    textarea.setSelectionRange(nextStart, nextEnd);
    updateBackdrop();
    hooks.onValueChanged(next);
  }

  const onInput = () => {
    updateBackdrop();
    hooks.onValueChanged(textarea.value);
  };

  const onKeyDown = (e) => {
    if ((e.ctrlKey || e.metaKey) && (e.key === 's' || e.key === 'S')) {
      e.preventDefault();
      hooks.onSaveShortcut?.(textarea.value);
      return;
    }
    if (readOnly) return;

    if (e.key === 'Tab') {
      e.preventDefault();
      const start = textarea.selectionStart ?? textarea.value.length;
      const end = textarea.selectionEnd ?? textarea.value.length;
      if (!e.shiftKey && start === end) {
        applyTextareaEdit(start, end, '  ', start + 2, start + 2);
      } else {
        const edit = computeIndentEdit(textarea.value, start, end, e.shiftKey);
        applyTextareaEdit(edit.start, edit.end, edit.replacement, edit.nextStart, edit.nextEnd);
      }
      return;
    }

    if (e.key === 'Enter') {
      e.preventDefault();
      const start = textarea.selectionStart ?? textarea.value.length;
      const end = textarea.selectionEnd ?? textarea.value.length;
      const edit = computeEnterEdit(textarea.value, start, end);
      applyTextareaEdit(edit.start, edit.end, edit.replacement, edit.nextStart, edit.nextEnd);
    }
  };

  textarea.addEventListener('scroll', syncScroll);
  textarea.addEventListener('input', onInput);
  textarea.addEventListener('keydown', onKeyDown);

  updateBackdrop();

  return {
    getValue: () => textarea.value,
    setValue: (val) => {
      textarea.value = val;
      updateBackdrop();
    },
    setFormat: (fmt) => {
      format = fmt;
      updateBackdrop();
    },
    focus: () => textarea.focus(),
    destroy: () => {
      textarea.removeEventListener('scroll', syncScroll);
      textarea.removeEventListener('input', onInput);
      textarea.removeEventListener('keydown', onKeyDown);
    }
  };
}

/**
 * Mounts a unified syntax-highlighted code editor inside `containerEl`.
 * Supports hot-swapping between `'highlight-api'` and `'legacy-duallayer'` adapters while preserving buffer state.
 *
 * @param {HTMLElement} containerEl
 * @param {object} [options]
 * @param {string} [options.value='']
 * @param {string} [options.format='json']
 * @param {boolean} [options.readOnly=false]
 * @param {'highlight-api'|'legacy-duallayer'|null} [options.engine=null]
 * @param {Function|null} [options.onChange=null]
 * @param {Function|null} [options.onSaveShortcut=null]
 * @returns {object|null}
 */
export function mountCodeEditor(containerEl, {
  value = '',
  format = 'json',
  readOnly = false,
  engine = null,
  onChange = null,
  onSaveShortcut = null
} = {}) {
  if (!containerEl) return null;

  let currentValue = typeof value === 'string' ? value : String(value ?? '');
  let currentFormat = format || detectFormat(currentValue);
  let destroyed = false;

  function resolveEngine(requested) {
    const target = requested === 'legacy-duallayer' ? 'legacy-duallayer' : 'highlight-api';
    if (target === 'highlight-api' && !isHighlightApiSupported()) {
      return 'legacy-duallayer';
    }
    return target;
  }

  let activeEngine = resolveEngine(engine || defaultEngine);
  let adapter = null;

  const hooks = {
    onValueChanged: (nextVal) => {
      currentValue = nextVal;
      if (typeof onChange === 'function') {
        onChange(nextVal);
      }
    },
    onSaveShortcut: (val) => {
      if (typeof onSaveShortcut === 'function') {
        onSaveShortcut(val);
      }
    }
  };

  function mountAdapter(targetEngine) {
    activeEngine = resolveEngine(targetEngine);
    adapter = activeEngine === 'highlight-api'
      ? createHighlightApiAdapter(containerEl, currentValue, currentFormat, readOnly, hooks)
      : createLegacyDualLayerAdapter(containerEl, currentValue, currentFormat, readOnly, hooks);
  }

  mountAdapter(activeEngine);

  return {
    getValue: () => (adapter && !destroyed ? adapter.getValue() : currentValue),
    setValue: (text) => {
      if (destroyed) return;
      currentValue = typeof text === 'string' ? text : String(text ?? '');
      adapter.setValue(currentValue);
    },
    setFormat: (fmt) => {
      if (destroyed) return;
      currentFormat = fmt;
      adapter.setFormat(fmt);
    },
    formatCode: () => {
      if (destroyed) return currentValue;
      const latest = adapter.getValue();
      const formatted = formatPayload(latest);
      currentValue = formatted;
      adapter.setValue(formatted);
      if (formatted !== latest && typeof onChange === 'function') {
        onChange(formatted);
      }
      return formatted;
    },
    compactCode: () => {
      if (destroyed) return currentValue;
      const latest = adapter.getValue();
      const fmt = currentFormat || detectFormat(latest);
      let compacted = latest;
      if (fmt === 'json') {
        try {
          compacted = JSON.stringify(JSON.parse(latest));
        } catch (_) {}
      } else if (fmt === 'xml') {
        compacted = minifyXml(latest);
      }
      currentValue = compacted;
      adapter.setValue(compacted);
      if (compacted !== latest && typeof onChange === 'function') {
        onChange(compacted);
      }
      return compacted;
    },
    getEngine: () => activeEngine,
    setEngine: (nextEngine) => {
      if (destroyed) return activeEngine;
      if (nextEngine !== 'highlight-api' && nextEngine !== 'legacy-duallayer') {
        throw new Error(`Invalid code editor engine: ${nextEngine}`);
      }
      currentValue = adapter.getValue();
      adapter.destroy();
      mountAdapter(nextEngine);
      return activeEngine;
    },
    focus: () => {
      if (!destroyed && adapter) adapter.focus();
    },
    destroy: () => {
      if (destroyed) return;
      destroyed = true;
      if (adapter) {
        adapter.destroy();
        adapter = null;
      }
      containerEl.innerHTML = '';
    }
  };
}
