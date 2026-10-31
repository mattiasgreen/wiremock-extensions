/**
 * Payload Unnesting Engine and Stacked Nested Payload Lens Controller.
 * Recursively parses, formats, and validates embedded JSON, XML, and Base64 payloads
 * with wire-fidelity round-trip serialization and re-escaping.
 * Zero external dependencies.
 */
import { escapeHtml, elements } from './dom.js';
import {
  detectFormat,
  formatPayload,
  formatXml,
  minifyXml,
  highlightPayload,
  highlightJson,
  highlightXml
} from './highlighter.js';
import { decodeBase64ToUtf8, tryDecodeBase64Json } from './base64.js';

/**
 * Validates payload syntax for JSON or XML.
 *
 * @param {string} text
 * @param {'json' | 'xml'} format
 * @returns {{ valid: boolean, errorMessage?: string }}
 */
export function validatePayload(text, format) {
  if (typeof text !== 'string') return { valid: false, errorMessage: 'Payload is not a string' };
  const trimmed = text.trim();
  if (!trimmed) return { valid: true };

  if (format === 'json') {
    try {
      JSON.parse(trimmed);
      return { valid: true };
    } catch (err) {
      return { valid: false, errorMessage: `JSON Syntax Error: ${err.message}` };
    }
  }

  if (format === 'xml') {
    if (typeof DOMParser !== 'undefined') {
      try {
        const doc = new DOMParser().parseFromString(trimmed, 'text/xml');
        const errors = doc.getElementsByTagName('parsererror');
        if (errors.length > 0) {
          const msg = errors[0].textContent || 'Invalid XML syntax';
          return { valid: false, errorMessage: `XML Syntax Error: ${msg.trim()}` };
        }
        if (!doc.documentElement) {
          return { valid: false, errorMessage: 'XML document has no root element' };
        }
        return { valid: true };
      } catch (err) {
        return { valid: false, errorMessage: `XML Parser Error: ${err.message}` };
      }
    }
    // Fallback environment check
    if (!trimmed.startsWith('<') || !trimmed.endsWith('>')) {
      return { valid: false, errorMessage: 'XML must be enclosed in tags' };
    }
    return { valid: true };
  }

  return { valid: true };
}

/**
 * Encodes a UTF-8 string into standard Base64.
 *
 * @param {string} str
 * @returns {string}
 */
export function utf8ToBase64(str) {
  try {
    if (typeof TextEncoder !== 'undefined') {
      const bytes = new TextEncoder().encode(str);
      let binary = '';
      for (let i = 0; i < bytes.length; i++) {
        binary += String.fromCharCode(bytes[i]);
      }
      return btoa(binary);
    }
    return btoa(unescape(encodeURIComponent(str)));
  } catch (_) {
    return btoa(str);
  }
}

/**
 * Recursively discovers embedded JSON, XML, or Base64 payloads inside raw text.
 *
 * @param {string} rawText
 * @param {number} maxDepth
 * @param {number} currentDepth
 * @param {string} currentPath
 * @returns {Array<object>} List of discovered node descriptors
 */
export function discoverEmbeddedPayloads(rawText, maxDepth = 5, currentDepth = 0, currentPath = '$') {
  if (typeof rawText !== 'string' || currentDepth >= maxDepth) return [];
  const trimmed = rawText.trim();
  if (!trimmed) return [];

  const results = [];
  const format = detectFormat(trimmed);

  if (format === 'json') {
    try {
      const parsed = JSON.parse(trimmed);
      inspectJsonObject(parsed, currentPath, results, maxDepth, currentDepth);
    } catch (_) {}
  } else if (format === 'xml') {
    inspectXmlText(trimmed, currentPath, results, maxDepth, currentDepth);
  }

  return results;
}

function inspectJsonObject(obj, path, results, maxDepth, currentDepth) {
  if (!obj || typeof obj !== 'object') return;

  for (const [key, val] of Object.entries(obj)) {
    const childPath = Array.isArray(obj) ? `${path}[${key}]` : `${path}.${key}`;

    if (typeof val === 'string') {
      const descriptor = analyzeStringValue(val, childPath, key, maxDepth, currentDepth);
      if (descriptor) {
        results.push(descriptor);
      }
    } else if (typeof val === 'object' && val !== null) {
      inspectJsonObject(val, childPath, results, maxDepth, currentDepth);
    }
  }
}

function analyzeStringValue(val, childPath, key, maxDepth, currentDepth) {
  const trimmedVal = val.trim();
  if (trimmedVal.length < 2) return null;

  // 1. Check for embedded JSON string
  if ((trimmedVal.startsWith('{') && trimmedVal.endsWith('}')) || (trimmedVal.startsWith('[') && trimmedVal.endsWith(']'))) {
    try {
      const parsedInner = JSON.parse(trimmedVal);
      if (typeof parsedInner === 'object' && parsedInner !== null) {
        const children = currentDepth + 1 < maxDepth
          ? discoverEmbeddedPayloads(trimmedVal, maxDepth, currentDepth + 1, childPath)
          : [];
        return {
          path: childPath,
          key,
          format: 'json',
          encoding: 'JSON_STRING',
          wasCompact: !val.includes('\n'),
          rawValue: val,
          unescapedValue: trimmedVal,
          formattedValue: JSON.stringify(parsedInner, null, 2),
          byteLength: typeof Blob !== 'undefined' ? new Blob([val]).size : val.length,
          children
        };
      }
    } catch (_) {}
  }

  // 2. Check for embedded XML string
  if (trimmedVal.startsWith('<') && trimmedVal.endsWith('>')) {
    if (detectFormat(trimmedVal) === 'xml') {
      const children = currentDepth + 1 < maxDepth
        ? discoverEmbeddedPayloads(trimmedVal, maxDepth, currentDepth + 1, childPath)
        : [];
      return {
        path: childPath,
        key,
        format: 'xml',
        encoding: 'JSON_STRING',
        wasCompact: !val.includes('\n'),
        rawValue: val,
        unescapedValue: trimmedVal,
        formattedValue: formatXml(trimmedVal),
        byteLength: typeof Blob !== 'undefined' ? new Blob([val]).size : val.length,
        children
      };
    }
  }

  // 3. Check for Base64-encoded UTF-8 JSON or XML
  const decodedUtf8 = decodeBase64ToUtf8(val);
  if (decodedUtf8 && decodedUtf8.length > 3) {
    const innerFormat = detectFormat(decodedUtf8);
    if (innerFormat === 'json' || innerFormat === 'xml') {
      const children = currentDepth + 1 < maxDepth
        ? discoverEmbeddedPayloads(decodedUtf8, maxDepth, currentDepth + 1, childPath)
        : [];
      return {
        path: childPath,
        key,
        format: innerFormat,
        encoding: 'BASE64_UTF8',
        wasCompact: !decodedUtf8.includes('\n'),
        rawValue: val,
        unescapedValue: decodedUtf8,
        formattedValue: innerFormat === 'json' ? formatPayload(decodedUtf8) : formatXml(decodedUtf8),
        byteLength: typeof Blob !== 'undefined' ? new Blob([val]).size : val.length,
        children
      };
    }
  }

  return null;
}

function inspectXmlText(xmlText, path, results, maxDepth, currentDepth) {
  // Inspect CDATA sections: <![CDATA[...]]>
  const cdataRegex = /<([a-zA-Z0-9_:-]+)[^>]*><!\[CDATA\[([\s\S]*?)\]\]><\/\1>/g;
  let match;
  while ((match = cdataRegex.exec(xmlText)) !== null) {
    const tagName = match[1];
    const cdataContent = match[2];
    const childPath = `${path}/${tagName}[cdata()]`;
    const innerFormat = detectFormat(cdataContent);

    if (innerFormat === 'json' || innerFormat === 'xml') {
      const children = currentDepth + 1 < maxDepth
        ? discoverEmbeddedPayloads(cdataContent, maxDepth, currentDepth + 1, childPath)
        : [];
      results.push({
        path: childPath,
        key: tagName,
        format: innerFormat,
        encoding: 'XML_CDATA',
        wasCompact: !cdataContent.includes('\n'),
        rawValue: cdataContent,
        unescapedValue: cdataContent,
        formattedValue: innerFormat === 'json' ? formatPayload(cdataContent) : formatXml(cdataContent),
        byteLength: typeof Blob !== 'undefined' ? new Blob([cdataContent]).size : cdataContent.length,
        children
      });
    }
  }

  // Inspect XML entity-escaped text: &lt;tag...&gt;
  const entityRegex = /<([a-zA-Z0-9_:-]+)[^>]*>([\s\S]*?)<\/\1>/g;
  while ((match = entityRegex.exec(xmlText)) !== null) {
    const tagName = match[1];
    const content = match[2].trim();
    if (content.includes('&lt;') && content.includes('&gt;')) {
      const unescaped = unescapeXmlEntities(content);
      const innerFormat = detectFormat(unescaped);
      if (innerFormat === 'json' || innerFormat === 'xml') {
        const childPath = `${path}/${tagName}[text()]`;
        const children = currentDepth + 1 < maxDepth
          ? discoverEmbeddedPayloads(unescaped, maxDepth, currentDepth + 1, childPath)
          : [];
        results.push({
          path: childPath,
          key: tagName,
          format: innerFormat,
          encoding: 'XML_ENTITY_TEXT',
          wasCompact: !unescaped.includes('\n'),
          rawValue: content,
          unescapedValue: unescaped,
          formattedValue: innerFormat === 'json' ? formatPayload(unescaped) : formatXml(unescaped),
          byteLength: typeof Blob !== 'undefined' ? new Blob([content]).size : content.length,
          children
        });
      }
    }
  }
}

export function unescapeXmlEntities(str) {
  if (!str || typeof str !== 'string') return '';
  return str
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&amp;/g, '&');
}

export function escapeXmlEntities(str) {
  if (!str || typeof str !== 'string') return '';
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

/**
 * Replaces a nested property in an object or array given a JSON path (e.g. $.payload.soapBody or $[0].data).
 *
 * @param {object|array} root
 * @param {string} path
 * @param {any} newValue
 */
export function setJsonPathValue(root, path, newValue) {
  if (!path || path === '$') return newValue;

  // Tokenize path into keys/indices
  const cleanPath = path.startsWith('$.') ? path.substring(2) : (path.startsWith('$') ? path.substring(1) : path);
  const tokens = [];
  const tokenRegex = /([a-zA-Z0-9_-]+)|\[(\d+)\]/g;
  let m;
  while ((m = tokenRegex.exec(cleanPath)) !== null) {
    if (m[1] !== undefined) tokens.push(m[1]);
    else if (m[2] !== undefined) tokens.push(parseInt(m[2], 10));
  }

  let curr = root;
  for (let i = 0; i < tokens.length - 1; i++) {
    const t = tokens[i];
    if (curr[t] === undefined || curr[t] === null) {
      curr[t] = typeof tokens[i + 1] === 'number' ? [] : {};
    }
    curr = curr[t];
  }

  if (tokens.length > 0) {
    curr[tokens[tokens.length - 1]] = newValue;
  }
  return root;
}

/**
 * Applies edited text of a child node back into its parent container text,
 * properly re-escaping according to the node's Container Encoding Envelope.
 *
 * @param {string} parentText
 * @param {object} nodeDescriptor
 * @param {string} editedChildText
 * @param {object} options { forceInvalid: boolean, minifyOnSave: boolean }
 * @returns {string} Re-escaped parent text
 */
export function applyNestedPayloadEdit(parentText, nodeDescriptor, editedChildText, options = {}) {
  if (!nodeDescriptor) return editedChildText;

  // 1. Validation check
  if (!options.forceInvalid) {
    const valResult = validatePayload(editedChildText, nodeDescriptor.format);
    if (!valResult.valid) {
      throw new Error(valResult.errorMessage || 'Invalid payload syntax');
    }
  }

  // 2. Whitespace formatting
  let processedText = editedChildText;
  if (options.minifyOnSave || (nodeDescriptor.wasCompact && options.minifyOnSave !== false)) {
    if (nodeDescriptor.format === 'json') {
      try {
        processedText = JSON.stringify(JSON.parse(editedChildText));
      } catch (_) {}
    } else if (nodeDescriptor.format === 'xml') {
      processedText = minifyXml(editedChildText);
    }
  } else {
    processedText = formatPayload(editedChildText);
  }

  // 3. Re-encode according to Container Encoding Envelope
  if (nodeDescriptor.encoding === 'BASE64_UTF8') {
    const encoded = utf8ToBase64(processedText);
    return replaceNodeValueInParent(parentText, nodeDescriptor, encoded);
  }

  if (nodeDescriptor.encoding === 'XML_CDATA') {
    const cdataWrapper = `<![CDATA[${processedText}]]>`;
    if (nodeDescriptor.rawValue && parentText.includes(nodeDescriptor.rawValue)) {
      return parentText.replace(`<![CDATA[${nodeDescriptor.rawValue}]]>`, cdataWrapper);
    }
    return cdataWrapper;
  }

  if (nodeDescriptor.encoding === 'XML_ENTITY_TEXT') {
    const entityEscaped = escapeXmlEntities(processedText);
    if (nodeDescriptor.rawValue && parentText.includes(nodeDescriptor.rawValue)) {
      return parentText.replace(nodeDescriptor.rawValue, entityEscaped);
    }
    return entityEscaped;
  }

  // Standard JSON_STRING
  return replaceNodeValueInParent(parentText, nodeDescriptor, processedText);
}

function replaceNodeValueInParent(parentText, nodeDescriptor, newStringValue) {
  try {
    const parsedParent = JSON.parse(parentText);
    if (nodeDescriptor.key && Object.prototype.hasOwnProperty.call(parsedParent, nodeDescriptor.key)) {
      parsedParent[nodeDescriptor.key] = newStringValue;
    } else {
      setJsonPathValue(parsedParent, nodeDescriptor.path, newStringValue);
    }
    // Retain indentation if parent was indented
    const isIndented = parentText.includes('\n');
    return JSON.stringify(parsedParent, null, isIndented ? 2 : undefined);
  } catch (_) {
    // String replacement fallback if parent is not parseable JSON
    if (nodeDescriptor.rawValue && parentText.includes(nodeDescriptor.rawValue)) {
      const escapedNew = JSON.stringify(newStringValue);
      // Strip outer quotes from JSON.stringify
      const innerEscaped = escapedNew.substring(1, escapedNew.length - 1);
      return parentText.replace(nodeDescriptor.rawValue, innerEscaped);
    }
    return parentText;
  }
}

/**
 * Cascades edits from the deepest stack frame up through all intermediate parents,
 * returning the final updated root text.
 *
 * @param {Array<{ text: string, descriptor?: object }>} stackFrames
 * @param {string} deepestEditedText
 * @param {object} options
 * @returns {string} Root updated wire text
 */
export function cascadeLensStackToRoot(stackFrames, deepestEditedText, options = {}) {
  if (!stackFrames || stackFrames.length === 0) return deepestEditedText;
  if (stackFrames.length === 1) return deepestEditedText;

  let currentText = deepestEditedText;
  for (let i = stackFrames.length - 1; i > 0; i--) {
    const childFrame = stackFrames[i];
    const parentFrame = stackFrames[i - 1];
    currentText = applyNestedPayloadEdit(
      parentFrame.text,
      childFrame.descriptor,
      currentText,
      options
    );
    parentFrame.text = currentText;
  }

  return currentText;
}

/* ==========================================================================
   UI VIEWERS, TEXTAREA OVERLAYS, AND STACKED LENS MODAL
   ========================================================================== */

/**
 * Renders a dual-mode payload viewer with Raw (Wire) vs Unescaped (Nested) toggle,
 * Truncated Payload Previews, and syntax highlighting.
 *
 * @param {HTMLElement} containerEl
 * @param {string} rawText
 * @param {object} options { title?: string, defaultUnescaped?: boolean }
 */
export function renderPayloadViewer(containerEl, rawText, options = {}) {
  if (!containerEl) return;
  if (!rawText || typeof rawText !== 'string' || !rawText.trim()) {
    containerEl.innerHTML = '<div class="muted-text text-sm">No payload</div>';
    return;
  }

  const trimmed = rawText.trim();
  const format = detectFormat(trimmed);
  const discovered = discoverEmbeddedPayloads(trimmed);
  const hasEmbedded = discovered.length > 0;

  // Create state container if not already initialized
  const viewerId = 'viewer_' + Math.random().toString(36).substring(2, 9);
  let isUnescaped = options.defaultUnescaped !== false && (hasEmbedded || format !== 'text');

  containerEl.innerHTML = `
    <div class="payload-viewer-box" id="${viewerId}">
      <div class="payload-viewer-header">
        <div class="payload-viewer-toggles">
          <button type="button" class="btn btn-tiny toggle-mode-btn ${isUnescaped ? 'active' : ''}" data-mode="unescaped">
            ✨ Unescaped ${hasEmbedded ? `(${discovered.length} nested)` : ''}
          </button>
          <button type="button" class="btn btn-tiny toggle-mode-btn ${!isUnescaped ? 'active' : ''}" data-mode="raw">
            📦 Raw (Wire)
          </button>
        </div>
        <div class="payload-viewer-actions">
          <button type="button" class="btn btn-tiny btn-copy-raw" title="Copy exact raw wire bytes">
            📋 Copy Raw
          </button>
          <button type="button" class="btn btn-tiny btn-copy-formatted" title="Copy pretty-printed formatted content">
            ✨ Copy Formatted
          </button>
        </div>
      </div>
      <div class="payload-viewer-body">
        <pre class="syntax-view"><code class="payload-code-output"></code></pre>
      </div>
    </div>
  `;

  const boxEl = containerEl.querySelector(`#${viewerId}`);
  const codeEl = boxEl.querySelector('.payload-code-output');

  function renderContent() {
    if (!isUnescaped) {
      codeEl.innerHTML = escapeHtml(trimmed);
      return;
    }

    if (!hasEmbedded) {
      codeEl.innerHTML = highlightPayload(trimmed);
      return;
    }

    // Render formatted root with interactive Truncated Payload Previews
    codeEl.innerHTML = renderFormattedWithNestedPreviews(trimmed, discovered, viewerId);
    attachViewerEventListeners(boxEl, trimmed, discovered);
  }

  // Bind Mode Toggles
  boxEl.querySelectorAll('.toggle-mode-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      isUnescaped = btn.getAttribute('data-mode') === 'unescaped';
      boxEl.querySelectorAll('.toggle-mode-btn').forEach(b => b.classList.remove('active'));
      btn.classList.add('active');
      renderContent();
    });
  });

  // Bind Copy Buttons
  const copyRawBtn = boxEl.querySelector('.btn-copy-raw');
  const copyFormattedBtn = boxEl.querySelector('.btn-copy-formatted');

  copyRawBtn.addEventListener('click', () => {
    navigator.clipboard.writeText(trimmed).then(() => {
      copyRawBtn.textContent = '✓ Copied';
      setTimeout(() => { copyRawBtn.textContent = '📋 Copy Raw'; }, 1500);
    }).catch(() => {});
  });

  copyFormattedBtn.addEventListener('click', () => {
    const formatted = formatPayload(trimmed);
    navigator.clipboard.writeText(formatted).then(() => {
      copyFormattedBtn.textContent = '✓ Copied';
      setTimeout(() => { copyFormattedBtn.textContent = '✨ Copy Formatted'; }, 1500);
    }).catch(() => {});
  });

  renderContent();
}

const viewerRegistry = new Map();

export function registerPayloadViewer(viewerId, rawText, discovered, onSaveRoot = null) {
  viewerRegistry.set(viewerId, { rawText, discovered, onSaveRoot });
}

export function renderFormattedWithNestedPreviews(rawText, discovered, viewerId, onSaveRoot = null) {
  registerPayloadViewer(viewerId, rawText, discovered, onSaveRoot);
  let formatted = formatPayload(rawText);
  const replacements = [];

  // Replace large escaped strings with Truncated Payload Previews
  for (let i = 0; i < discovered.length; i++) {
    const node = discovered[i];
    if (node.rawValue.length > 80 || node.wasCompact) {
      const previewSnippet = node.unescapedValue.substring(0, 60).replace(/\s+/g, ' ');
      const sizeKb = (node.byteLength / 1024).toFixed(1);
      const previewHtml = `
        <span class="truncated-payload-preview" data-node-idx="${i}" data-viewer-id="${viewerId}">
          <span class="preview-snippet">"${escapeHtml(previewSnippet)}..."</span>
          <span class="preview-pill-badge">${escapeHtml(node.format.toUpperCase())} · ${sizeKb} KB</span>
          <button type="button" class="btn-lens-open-pill" data-node-idx="${i}" title="Open focused Nested Payload Lens">
            🔍 Open in Lens
          </button>
          <button type="button" class="btn-lens-expand-pill" data-node-idx="${i}" title="Expand pretty-printed inline">
            ▾ Expand
          </button>
          <div class="inline-expanded-payload hidden" id="${viewerId}_expanded_${i}">
            <pre class="inline-code"><code>${highlightPayload(node.unescapedValue)}</code></pre>
          </div>
        </span>
      `;

      const token = `__LENS_TOKEN_${i}__`;
      const escapedInJson = JSON.stringify(node.rawValue);
      if (formatted.includes(escapedInJson)) {
        formatted = formatted.replace(escapedInJson, JSON.stringify(token));
        replacements.push({ token, html: previewHtml });
      } else if (formatted.includes(node.rawValue)) {
        formatted = formatted.replace(node.rawValue, token);
        replacements.push({ token, html: previewHtml });
      }
    }
  }

  const detected = detectFormat(rawText);
  let highlighted = detected === 'xml' ? highlightXml(formatted) : highlightJson(formatted);

  for (const { token, html } of replacements) {
    const jsonSpanToken = `<span class="json-string">&quot;${token}&quot;</span>`;
    if (highlighted.includes(jsonSpanToken)) {
      highlighted = highlighted.replace(jsonSpanToken, html);
    } else {
      highlighted = highlighted.split(token).join(html);
    }
  }

  return highlighted;
}

// Delegated click handler for Truncated Payload Previews across all DOM surfaces
if (typeof document !== 'undefined') {
  document.addEventListener('click', (e) => {
    const expandBtn = e.target.closest('.btn-lens-expand-pill');
    if (expandBtn) {
      e.stopPropagation();
      const parentPreview = expandBtn.closest('.truncated-payload-preview');
      if (parentPreview) {
        const expandedDiv = parentPreview.querySelector('.inline-expanded-payload');
        if (expandedDiv) {
          const isHidden = expandedDiv.classList.contains('hidden');
          if (isHidden) {
            expandedDiv.classList.remove('hidden');
            expandBtn.textContent = '▴ Collapse';
          } else {
            expandedDiv.classList.add('hidden');
            expandBtn.textContent = '▾ Expand';
          }
        }
      }
      return;
    }

    const openBtn = e.target.closest('.btn-lens-open-pill');
    if (openBtn) {
      e.stopPropagation();
      const parentPreview = openBtn.closest('.truncated-payload-preview');
      if (parentPreview) {
        const viewerId = parentPreview.getAttribute('data-viewer-id');
        const nodeIdx = parseInt(openBtn.getAttribute('data-node-idx'), 10);
        const stored = viewerRegistry.get(viewerId);
        if (stored && stored.discovered && stored.discovered[nodeIdx]) {
          openPayloadLens(stored.rawText, stored.discovered[nodeIdx], stored.onSaveRoot);
        }
      }
    }
  });
}

function attachViewerEventListeners(boxEl, rawText, discovered) {
  // Open Lens Button
  boxEl.querySelectorAll('.btn-lens-open-pill').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const idx = parseInt(btn.getAttribute('data-node-idx'), 10);
      const node = discovered[idx];
      if (node) {
        openPayloadLens(rawText, node, (newRootText) => {
          renderPayloadViewer(boxEl.parentElement, newRootText);
        });
      }
    });
  });

  // Expand Inline Button
  boxEl.querySelectorAll('.btn-lens-expand-pill').forEach(btn => {
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      const idx = btn.getAttribute('data-node-idx');
      const expandedDiv = boxEl.querySelector(`#${boxEl.id}_expanded_${idx}`);
      if (expandedDiv) {
        const isHidden = expandedDiv.classList.contains('hidden');
        if (isHidden) {
          expandedDiv.classList.remove('hidden');
          btn.textContent = '▴ Collapse';
        } else {
          expandedDiv.classList.add('hidden');
          btn.textContent = '▾ Expand';
        }
      }
    });
  });
}

/**
 * Attaches a Detected Embedded Payloads Bar to an editable textarea.
 *
 * @param {HTMLTextAreaElement} textareaEl
 * @param {HTMLElement} barContainerEl
 * @param {Function} onTextareaUpdated
 */
export function attachEmbeddedPayloadsBar(textareaEl, barContainerEl, onTextareaUpdated) {
  if (!textareaEl || !barContainerEl) return;

  let debounceTimer = null;

  function refreshBar() {
    const text = textareaEl.value;
    const discovered = discoverEmbeddedPayloads(text);

    if (discovered.length === 0) {
      barContainerEl.innerHTML = '';
      barContainerEl.classList.add('hidden');
      return;
    }

    barContainerEl.classList.remove('hidden');
    let pillsHtml = `
      <div class="embedded-payloads-bar">
        <span class="bar-title">✨ Nested Payloads:</span>
    `;

    for (let i = 0; i < discovered.length; i++) {
      const node = discovered[i];
      const sizeKb = (node.byteLength / 1024).toFixed(1);
      pillsHtml += `
        <button type="button" class="btn btn-tiny embedded-node-chip" data-node-idx="${i}" title="Edit embedded ${node.format.toUpperCase()} in focused lens">
          🔍 ${escapeHtml(node.path)} <span class="chip-badge">${escapeHtml(node.format.toUpperCase())} · ${sizeKb} KB</span>
        </button>
      `;
    }

    pillsHtml += '</div>';
    barContainerEl.innerHTML = pillsHtml;

    barContainerEl.querySelectorAll('.embedded-node-chip').forEach(chip => {
      chip.addEventListener('click', () => {
        const idx = parseInt(chip.getAttribute('data-node-idx'), 10);
        const node = discovered[idx];
        if (node) {
          openPayloadLens(textareaEl.value, node, (newRootText) => {
            textareaEl.value = newRootText;
            if (onTextareaUpdated) onTextareaUpdated(newRootText);
            textareaEl.dispatchEvent(new Event('input', { bubbles: true }));
          });
        }
      });
    });
  }

  textareaEl.addEventListener('input', () => {
    clearTimeout(debounceTimer);
    debounceTimer = setTimeout(refreshBar, 150);
  });

  refreshBar();
}

/**
 * State and Controller for the Stacked Nested Payload Lens Modal (#modal-payload-lens).
 */
let lensState = {
  stack: [], // Array<{ text: string, descriptor?: object, title: string, format: string }>
  onSaveRootCallback: null
};

export function openPayloadLens(rootText, initialNodeDescriptor, onSaveRootCallback) {
  const modal = elements.modalPayloadLens || document.getElementById('modal-payload-lens');
  if (!modal) return;

  lensState = {
    stack: [
      {
        text: rootText,
        descriptor: null,
        title: 'Root Wire Document',
        format: detectFormat(rootText)
      },
      {
        text: initialNodeDescriptor.unescapedValue,
        descriptor: initialNodeDescriptor,
        title: initialNodeDescriptor.path,
        format: initialNodeDescriptor.format
      }
    ],
    onSaveRootCallback
  };

  renderLensModal();
  modal.classList.remove('hidden');
}

export function closePayloadLens() {
  const modal = elements.modalPayloadLens || document.getElementById('modal-payload-lens');
  if (modal) modal.classList.add('hidden');
  lensState.stack = [];
}

function renderLensModal() {
  const modal = elements.modalPayloadLens || document.getElementById('modal-payload-lens');
  if (!modal || lensState.stack.length === 0) return;

  const currentFrame = lensState.stack[lensState.stack.length - 1];
  const breadcrumbsEl = document.getElementById('payload-lens-breadcrumbs');
  const editorEl = document.getElementById('payload-lens-editor');
  const errorEl = document.getElementById('payload-lens-validation-error');
  const forceApplyBtn = document.getElementById('btn-lens-force-apply');
  const applyParentBtn = document.getElementById('btn-lens-apply-parent');
  const saveRootBtn = document.getElementById('btn-lens-save-root');
  const nestedBarEl = document.getElementById('payload-lens-nested-bar');

  // Render Breadcrumbs
  if (breadcrumbsEl) {
    let bHtml = '';
    for (let i = 0; i < lensState.stack.length; i++) {
      const frame = lensState.stack[i];
      const isLast = i === lensState.stack.length - 1;
      bHtml += `
        <span class="breadcrumb-item ${isLast ? 'active' : ''}" data-stack-idx="${i}">
          ${escapeHtml(frame.title)} <span class="format-tag">(${frame.format.toUpperCase()})</span>
        </span>
        ${!isLast ? '<span class="breadcrumb-sep">▸</span>' : ''}
      `;
    }
    breadcrumbsEl.innerHTML = bHtml;

    breadcrumbsEl.querySelectorAll('.breadcrumb-item:not(.active)').forEach(item => {
      item.addEventListener('click', () => {
        const targetIdx = parseInt(item.getAttribute('data-stack-idx'), 10);
        lensState.stack = lensState.stack.slice(0, targetIdx + 1);
        renderLensModal();
      });
    });
  }

  // Populate Editor with formatted content
  if (editorEl) {
    editorEl.value = formatPayload(currentFrame.text);
  }

  // Validate initially
  validateCurrentEditor();

  // Check for even deeper nested payloads inside this active frame
  if (nestedBarEl) {
    const innerDiscovered = discoverEmbeddedPayloads(currentFrame.text);
    if (innerDiscovered.length > 0) {
      nestedBarEl.classList.remove('hidden');
      let innerHtml = '<div class="embedded-payloads-bar"><span class="bar-title">✨ Deep Nested:</span>';
      for (let j = 0; j < innerDiscovered.length; j++) {
        const innerNode = innerDiscovered[j];
        innerHtml += `
          <button type="button" class="btn btn-tiny inner-drill-chip" data-inner-idx="${j}">
            🔍 ${escapeHtml(innerNode.path)} (${escapeHtml(innerNode.format.toUpperCase())})
          </button>
        `;
      }
      innerHtml += '</div>';
      nestedBarEl.innerHTML = innerHtml;

      nestedBarEl.querySelectorAll('.inner-drill-chip').forEach(btn => {
        btn.addEventListener('click', () => {
          const idx = parseInt(btn.getAttribute('data-inner-idx'), 10);
          const nextNode = innerDiscovered[idx];
          if (nextNode) {
            lensState.stack.push({
              text: nextNode.unescapedValue,
              descriptor: nextNode,
              title: nextNode.path,
              format: nextNode.format
            });
            renderLensModal();
          }
        });
      });
    } else {
      nestedBarEl.classList.add('hidden');
      nestedBarEl.innerHTML = '';
    }
  }

  // Single Frame vs Stack Footer Buttons
  if (applyParentBtn) {
    applyParentBtn.style.display = lensState.stack.length > 2 ? 'inline-block' : 'none';
  }
}

function validateCurrentEditor() {
  const editorEl = document.getElementById('payload-lens-editor');
  const errorEl = document.getElementById('payload-lens-validation-error');
  const forceApplyBtn = document.getElementById('btn-lens-force-apply');
  const applyParentBtn = document.getElementById('btn-lens-apply-parent');
  const saveRootBtn = document.getElementById('btn-lens-save-root');
  if (!editorEl || lensState.stack.length === 0) return true;

  const currentFrame = lensState.stack[lensState.stack.length - 1];
  const valResult = validatePayload(editorEl.value, currentFrame.format);

  if (!valResult.valid) {
    if (errorEl) {
      errorEl.textContent = valResult.errorMessage || 'Invalid payload syntax';
      errorEl.classList.remove('hidden');
    }
    if (forceApplyBtn) forceApplyBtn.classList.remove('hidden');
    if (applyParentBtn) applyParentBtn.disabled = true;
    if (saveRootBtn) saveRootBtn.disabled = true;
    return false;
  }

  if (errorEl) errorEl.classList.add('hidden');
  if (forceApplyBtn) forceApplyBtn.classList.add('hidden');
  if (applyParentBtn) applyParentBtn.disabled = false;
  if (saveRootBtn) saveRootBtn.disabled = false;
  return true;
}

export function initPayloadLensModal() {
  const modal = elements.modalPayloadLens || document.getElementById('modal-payload-lens');
  if (!modal) return;

  const editorEl = document.getElementById('payload-lens-editor');
  const closeBtn = document.getElementById('btn-close-payload-lens');
  const cancelBtn = document.getElementById('btn-cancel-payload-lens');
  const prettifyBtn = document.getElementById('btn-lens-prettify');
  const minifyBtn = document.getElementById('btn-lens-minify');
  const copyRawBtn = document.getElementById('btn-lens-copy-raw');
  const copyFormattedBtn = document.getElementById('btn-lens-copy-formatted');
  const minifyOnSaveChk = document.getElementById('chk-lens-minify-on-save');
  const forceApplyBtn = document.getElementById('btn-lens-force-apply');
  const applyParentBtn = document.getElementById('btn-lens-apply-parent');
  const saveRootBtn = document.getElementById('btn-lens-save-root');

  if (editorEl) {
    editorEl.addEventListener('input', validateCurrentEditor);
  }

  if (closeBtn) closeBtn.addEventListener('click', closePayloadLens);
  if (cancelBtn) cancelBtn.addEventListener('click', closePayloadLens);

  if (prettifyBtn && editorEl) {
    prettifyBtn.addEventListener('click', () => {
      editorEl.value = formatPayload(editorEl.value);
      validateCurrentEditor();
    });
  }

  if (minifyBtn && editorEl) {
    minifyBtn.addEventListener('click', () => {
      const currentFrame = lensState.stack[lensState.stack.length - 1];
      if (currentFrame.format === 'json') {
        try {
          editorEl.value = JSON.stringify(JSON.parse(editorEl.value));
        } catch (_) {}
      } else if (currentFrame.format === 'xml') {
        editorEl.value = minifyXml(editorEl.value);
      }
      validateCurrentEditor();
    });
  }

  if (copyRawBtn && editorEl) {
    copyRawBtn.addEventListener('click', () => {
      const currentFrame = lensState.stack[lensState.stack.length - 1];
      const raw = currentFrame.descriptor ? currentFrame.descriptor.rawValue : editorEl.value;
      navigator.clipboard.writeText(raw).then(() => {
        copyRawBtn.textContent = '✓ Copied';
        setTimeout(() => { copyRawBtn.textContent = '📋 Copy Raw'; }, 1500);
      }).catch(() => {});
    });
  }

  if (copyFormattedBtn && editorEl) {
    copyFormattedBtn.addEventListener('click', () => {
      const formatted = formatPayload(editorEl.value);
      navigator.clipboard.writeText(formatted).then(() => {
        copyFormattedBtn.textContent = '✓ Copied';
        setTimeout(() => { copyFormattedBtn.textContent = '✨ Copy Formatted'; }, 1500);
      }).catch(() => {});
    });
  }

  function handleSave(force = false) {
    if (!editorEl || lensState.stack.length === 0) return;
    if (!force && !validateCurrentEditor()) return;

    const minifyOnSave = minifyOnSaveChk ? minifyOnSaveChk.checked : false;
    const finalRoot = cascadeLensStackToRoot(lensState.stack, editorEl.value, {
      forceInvalid: force,
      minifyOnSave
    });

    if (lensState.onSaveRootCallback) {
      lensState.onSaveRootCallback(finalRoot);
    }
    closePayloadLens();
  }

  if (saveRootBtn) {
    saveRootBtn.addEventListener('click', () => handleSave(false));
  }

  if (forceApplyBtn) {
    forceApplyBtn.addEventListener('click', () => handleSave(true));
  }

  if (applyParentBtn) {
    applyParentBtn.addEventListener('click', () => {
      if (!editorEl || lensState.stack.length < 2) return;
      if (!validateCurrentEditor()) return;

      const minifyOnSave = minifyOnSaveChk ? minifyOnSaveChk.checked : false;
      const childFrame = lensState.stack.pop();
      const parentFrame = lensState.stack[lensState.stack.length - 1];
      const updatedParent = applyNestedPayloadEdit(parentFrame.text, childFrame.descriptor, editorEl.value, {
        forceInvalid: false,
        minifyOnSave
      });
      parentFrame.text = updatedParent;
      renderLensModal();
    });
  }
}
