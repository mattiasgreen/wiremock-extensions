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
  highlightXml,
  parsePseudoJson,
  MAX_HIGHLIGHT_PAYLOAD_CHARS
} from './highlighter.js';
import { decodeBase64ToUtf8, tryDecodeBase64Json } from './base64.js';

export { detectFormat };

const CHARSET_ALIAS_MAP = {
  'UTF8': 'UTF-8',
  'UTF-8': 'UTF-8',
  'LATIN1': 'ISO-8859-1',
  'LATIN-1': 'ISO-8859-1',
  'ISO8859-1': 'ISO-8859-1',
  'ISO_8859-1': 'ISO-8859-1',
  'ISO-8859-1': 'ISO-8859-1',
  '8859_1': 'ISO-8859-1',
  'CP1252': 'WINDOWS-1252',
  'WIN1252': 'WINDOWS-1252',
  'WIN-1252': 'WINDOWS-1252',
  'WINDOWS1252': 'WINDOWS-1252',
  'WINDOWS-1252': 'WINDOWS-1252',
  'KOI8': 'KOI8-R',
  'KOI8R': 'KOI8-R',
  'KOI8-R': 'KOI8-R',
  'ASCII': 'US-ASCII',
  'US-ASCII': 'US-ASCII'
};

/**
 * Normalizes a character encoding name to its canonical uppercase identifier.
 *
 * @param {string} [charset]
 * @returns {string} Canonical uppercase charset (e.g. 'UTF-8', 'ISO-8859-1', 'WINDOWS-1252', 'KOI8-R')
 */
function normalizeCharsetName(charset) {
  if (!charset || typeof charset !== 'string') return 'UTF-8';
  const raw = charset.trim().replace(/^["']|["']$/g, '').toUpperCase();
  if (!raw) return 'UTF-8';
  return CHARSET_ALIAS_MAP[raw] || raw;
}

/**
 * Detects character encoding from XML prolog declaration or HTTP/Casual headers.
 * Priority: 1. XML prolog encoding, 2. X-Casual-Charset header, 3. Content-Type charset.
 *
 * @param {string} [text] Payload text
 * @param {object|string|string[]} [headers] Key-value map of headers, raw multi-line header text, or header line array
 * @returns {string} Canonical uppercase charset (defaults to 'UTF-8')
 */
export function detectCharset(text, headers = null) {
  if (typeof text === 'string' && text.length > 0) {
    const xmlPrologMatch = text.match(/<\?xml[^>]+encoding\s*=\s*["']([^"']+)["'][^>]*\?>/i);
    if (xmlPrologMatch && xmlPrologMatch[1]) {
      return normalizeCharsetName(xmlPrologMatch[1]);
    }
  }

  let headerEntries = [];
  if (typeof headers === 'string') {
    for (const line of headers.split(/\r?\n/)) {
      const idx = line.indexOf(':');
      if (idx > 0) headerEntries.push([line.substring(0, idx).trim(), line.substring(idx + 1).trim()]);
    }
  } else if (Array.isArray(headers)) {
    for (const line of headers) {
      const str = String(line || '');
      const idx = str.indexOf(':');
      if (idx > 0) headerEntries.push([str.substring(0, idx).trim(), str.substring(idx + 1).trim()]);
    }
  } else if (headers && typeof headers === 'object') {
    headerEntries = Object.entries(headers);
  }

  if (headerEntries.length > 0) {
    let contentTypeCharset = null;
    for (const [k, v] of headerEntries) {
      if (!v) continue;
      const lowerKey = k.toLowerCase();
      const valStr = Array.isArray(v) ? String(v[0] || '') : String(v);
      if (lowerKey === 'x-casual-charset') {
        return normalizeCharsetName(valStr);
      }
      if (lowerKey === 'content-type') {
        const charsetMatch = valStr.match(/charset\s*=\s*([^\s;,"']+)/i);
        if (charsetMatch && charsetMatch[1]) {
          contentTypeCharset = normalizeCharsetName(charsetMatch[1]);
        }
      }
    }
    if (contentTypeCharset) {
      return contentTypeCharset;
    }
  }

  return 'UTF-8';
}

/**
 * Formats a concise payload descriptor badge combining non-trivial container encoding,
 * syntax format, non-UTF-8 character encoding, and byte size.
 *
 * @param {object} opts
 * @param {string} [opts.format] Syntax format (JSON, XML, TEXT, HEX)
 * @param {string} [opts.encoding] Container encoding envelope (JSON_STRING, BASE64, BASE64_UTF8, XML_CDATA, XML_ENTITY_TEXT)
 * @param {string} [opts.charset] Character set (UTF-8, ISO-8859-1, KOI8-R, etc.)
 * @param {string|number} [opts.sizeKb] Byte size in kilobytes
 * @returns {string} Formatted badge text, e.g. "BASE64 · XML · KOI8-R · 0.3 KB" or "JSON · 0.4 KB"
 */
export function formatPayloadDescriptor({ format = 'TEXT', encoding = null, charset = 'UTF-8', sizeKb = null } = {}) {
  const parts = [];
  if (encoding) {
    const upperEnc = String(encoding).toUpperCase().trim();
    if (upperEnc === 'BASE64' || upperEnc === 'BASE64_UTF8') {
      parts.push('BASE64');
    } else if (upperEnc === 'XML_CDATA' || upperEnc === 'CDATA') {
      parts.push('CDATA');
    } else if (upperEnc === 'XML_ENTITY_TEXT' || upperEnc === 'ENTITY') {
      parts.push('ENTITY');
    }
  }

  const normFormat = (format || 'TEXT').toUpperCase().trim();
  parts.push(normFormat);

  const normCharset = normalizeCharsetName(charset);
  if (normCharset && normCharset !== 'UTF-8') {
    parts.push(normCharset);
  }

  if (sizeKb !== undefined && sizeKb !== null && sizeKb !== '') {
    parts.push(`${sizeKb} KB`);
  }

  return parts.join(' · ');
}

/**
 * Formats a composite Casual Buffer Descriptor badge string combining
 * transport buffer type, concrete payload document syntax, optional non-UTF-8 charset, and byte size.
 * Deduplicates identical names (e.g. JSON + JSON -> CASUAL JSON).
 *
 * @param {string} bufferType Casual buffer type (JSON, FIELDED, CSTRING, FLAT, X_OCTET)
 * @param {string} syntaxFormat Document syntax (JSON, XML, TEXT, HEX)
 * @param {string|number} [sizeKb] Byte size in kilobytes
 * @param {string} [charset] Character encoding (omitted if UTF-8)
 * @returns {string} Formatted label, e.g. "FIELDED · JSON · ISO-8859-1 · 0.4 KB" or "CASUAL JSON · 0.4 KB"
 */
export function formatCasualBufferDescriptor(bufferType, syntaxFormat, sizeKb, charset = 'UTF-8') {
  let normBuffer = (bufferType || 'JSON').toUpperCase().trim();
  if (normBuffer === 'FML' || normBuffer === 'CFIELD/' || normBuffer === '.FIELDED') normBuffer = 'FIELDED';
  else if (normBuffer === 'CSTRING/') normBuffer = 'CSTRING';
  const normFormat = (syntaxFormat || 'JSON').toUpperCase().trim();
  const normCharset = normalizeCharsetName(charset);
  const charsetPart = normCharset && normCharset !== 'UTF-8' ? ` · ${normCharset}` : '';
  const sizePart = sizeKb !== undefined && sizeKb !== null && sizeKb !== '' ? ` · ${sizeKb} KB` : '';

  if (normBuffer === normFormat) {
    return `CASUAL ${normBuffer}${charsetPart}${sizePart}`;
  }
  return `${normBuffer} · ${normFormat}${charsetPart}${sizePart}`;
}

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

const singleByteEncoderCache = new Map();

function getSingleByteEncodeMap(normCharset) {
  if (singleByteEncoderCache.has(normCharset)) {
    return singleByteEncoderCache.get(normCharset);
  }
  const map = new Map();
  try {
    const dec = new TextDecoder(normCharset.toLowerCase());
    const buf = new Uint8Array(256);
    for (let b = 0; b < 256; b++) buf[b] = b;
    const decoded = dec.decode(buf);
    for (let b = 0; b < 256; b++) {
      map.set(decoded.charCodeAt(b), b);
    }
  } catch (_) {
    for (let b = 0; b < 256; b++) map.set(b, b);
  }
  singleByteEncoderCache.set(normCharset, map);
  return map;
}

/**
 * Encodes a string into a Uint8Array using the specified character encoding.
 *
 * @param {string} str
 * @param {string} [charset='UTF-8']
 * @returns {Uint8Array}
 */
function encodeStringToBytes(str, charset = 'UTF-8') {
  const norm = normalizeCharsetName(charset);
  if (norm === 'UTF-8') {
    if (typeof TextEncoder !== 'undefined') {
      return new TextEncoder().encode(str);
    }
    const unescaped = unescape(encodeURIComponent(str));
    const out = new Uint8Array(unescaped.length);
    for (let i = 0; i < unescaped.length; i++) out[i] = unescaped.charCodeAt(i);
    return out;
  }
  if (norm === 'ISO-8859-1' || norm === 'US-ASCII') {
    const out = new Uint8Array(str.length);
    for (let i = 0; i < str.length; i++) {
      out[i] = str.charCodeAt(i) & 0xFF;
    }
    return out;
  }
  const encodeMap = getSingleByteEncodeMap(norm);
  const out = new Uint8Array(str.length);
  for (let i = 0; i < str.length; i++) {
    const code = str.charCodeAt(i);
    out[i] = encodeMap.has(code) ? encodeMap.get(code) : (code <= 0xFF ? code : 0x3F);
  }
  return out;
}

/**
 * Encodes a string into Base64 using the specified character encoding.
 *
 * @param {string} str
 * @param {string} [charset='UTF-8']
 * @returns {string}
 */
export function encodeToBase64(str, charset = 'UTF-8') {
  const bytes = encodeStringToBytes(str, charset);
  let binary = '';
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary);
}

/**
 * Encodes a UTF-8 string into standard Base64.
 *
 * @param {string} str
 * @returns {string}
 */
export function utf8ToBase64(str) {
  return encodeToBase64(str, 'UTF-8');
}

/**
 * Decodes a Base64 string while respecting declared XML prolog charsets or non-UTF-8 single-byte encodings.
 *
 * @param {string} str
 * @returns {{ text: string, charset: string } | null}
 */
function decodeBase64WithCharset(str) {
  if (typeof str !== 'string') return null;
  const cleaned = str.trim().replace(/[\r\n\s]+/g, '');
  if (cleaned.length < 4) return null;

  let normalized = cleaned.replace(/-/g, '+').replace(/_/g, '/');
  while (normalized.length % 4 !== 0) {
    normalized += '=';
  }
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(normalized)) {
    return null;
  }

  let bytes;
  try {
    const binary = atob(normalized);
    if (!binary || binary.length === 0) return null;
    bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
  } catch (_) {
    return null;
  }

  // 1. Inspect ASCII prefix for explicit XML prolog encoding="..."
  let asciiPrefix = '';
  const prefixLen = Math.min(bytes.length, 200);
  for (let i = 0; i < prefixLen; i++) {
    asciiPrefix += String.fromCharCode(bytes[i]);
  }
  const prologMatch = asciiPrefix.match(/<\?xml[^>]+encoding\s*=\s*["']([^"']+)["'][^>]*\?>/i);
  if (prologMatch && prologMatch[1]) {
    const declaredCharset = normalizeCharsetName(prologMatch[1]);
    if (declaredCharset !== 'UTF-8') {
      try {
        if (declaredCharset === 'ISO-8859-1') {
          let isoText = '';
          for (let i = 0; i < bytes.length; i++) {
            isoText += String.fromCharCode(bytes[i]);
          }
          return { text: isoText, charset: 'ISO-8859-1' };
        }
        const decoded = new TextDecoder(declaredCharset.toLowerCase(), { fatal: false }).decode(bytes);
        return { text: decoded, charset: declaredCharset };
      } catch (_) {}
    }
  }

  // 2. Try strict UTF-8
  try {
    const utf8Text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    return { text: utf8Text, charset: detectCharset(utf8Text) };
  } catch (_) {
    // 3. Fallback: check if printable single-byte ISO-8859-1 text/XML/JSON
    let isoText = '';
    for (let i = 0; i < bytes.length; i++) {
      const b = bytes[i];
      if (b < 0x20 && b !== 0x09 && b !== 0x0A && b !== 0x0D) {
        return null;
      }
      isoText += String.fromCharCode(b);
    }
    const detected = detectCharset(isoText);
    return { text: isoText, charset: detected !== 'UTF-8' ? detected : 'ISO-8859-1' };
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

function isPrintableText(str) {
  if (typeof str !== 'string' || !str) return false;
  for (let i = 0; i < str.length; i++) {
    const code = str.charCodeAt(i);
    if (code < 32 && code !== 9 && code !== 10 && code !== 13) {
      return false;
    }
  }
  return true;
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
          charset: detectCharset(trimmedVal),
          rawValue: val,
          unescapedValue: trimmedVal,
          formattedValue: JSON.stringify(parsedInner, null, 2),
          byteLength: typeof Blob !== 'undefined' ? new Blob([val]).size : val.length,
          children
        };
      }
    } catch (_) {}

    // 1b. Check for embedded Java Map / KV pseudo-JSON string
    if (trimmedVal.includes('=')) {
      try {
        const kv = parsePseudoJson(trimmedVal);
        if (kv && typeof kv === 'object') {
          const children = currentDepth + 1 < maxDepth
            ? discoverEmbeddedPayloads(trimmedVal, maxDepth, currentDepth + 1, childPath)
            : [];
          return {
            path: childPath,
            key,
            format: 'kv',
            encoding: 'JSON_STRING',
            charset: detectCharset(trimmedVal),
            rawValue: val,
            unescapedValue: trimmedVal,
            formattedValue: formatPayload(trimmedVal),
            byteLength: typeof Blob !== 'undefined' ? new Blob([val]).size : val.length,
            children
          };
        }
      } catch (_) {}
    }
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
        charset: detectCharset(trimmedVal),
        rawValue: val,
        unescapedValue: trimmedVal,
        formattedValue: formatXml(trimmedVal),
        byteLength: typeof Blob !== 'undefined' ? new Blob([val]).size : val.length,
        children
      };
    }
  }

  // 3. Check for Base64-encoded JSON, XML, KV, or printable Text
  const b64Decoded = decodeBase64WithCharset(val);
  if (b64Decoded && b64Decoded.text && b64Decoded.text.length >= 3) {
    const decodedText = b64Decoded.text;
    const innerFormat = detectFormat(decodedText);
    const isPrintable = isPrintableText(decodedText);
    const trimmedValStr = val.trim();
    const isGenuineBase64 = /^[A-Za-z0-9+/]{8,}={0,2}$/.test(trimmedValStr);

    const isMatch = (innerFormat === 'json' || innerFormat === 'xml' || innerFormat === 'kv') ||
      (innerFormat === 'text' && isPrintable && isGenuineBase64 && trimmedValStr.length >= 24 && isMostlyAsciiText(decodedText));

    if (isMatch) {
      const children = currentDepth + 1 < maxDepth
        ? discoverEmbeddedPayloads(decodedText, maxDepth, currentDepth + 1, childPath)
        : [];
      return {
        path: childPath,
        key,
        format: innerFormat,
        encoding: b64Decoded.charset === 'UTF-8' ? 'BASE64_UTF8' : 'BASE64',
        charset: b64Decoded.charset,
        rawValue: val,
        unescapedValue: decodedText,
        formattedValue: formatPayload(decodedText),
        byteLength: typeof Blob !== 'undefined' ? new Blob([val]).size : val.length,
        children
      };
    }
  }

  return null;
}

function isMostlyAsciiText(str) {
  if (!str) return false;
  let asciiCount = 0;
  for (let i = 0; i < str.length; i++) {
    const code = str.charCodeAt(i);
    if ((code >= 32 && code <= 126) || code === 9 || code === 10 || code === 13) {
      asciiCount++;
    }
  }
  return (asciiCount / str.length) >= 0.9;
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
        charset: detectCharset(cdataContent),
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
          charset: detectCharset(unescaped),
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

function unescapeXmlEntities(str) {
  if (!str || typeof str !== 'string') return '';
  return str
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&amp;/g, '&');
}

function escapeXmlEntities(str) {
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
function setJsonPathValue(root, path, newValue) {
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

function getRelativeJsonPath(childPath, parentPath) {
  if (!childPath) return '';
  if (!parentPath || parentPath === '$') {
    return childPath.startsWith('$.') ? childPath.substring(2) : (childPath.startsWith('$') ? childPath.substring(1) : childPath);
  }
  if (childPath.startsWith(parentPath)) {
    let rel = childPath.substring(parentPath.length);
    while (rel.startsWith('.') || rel.startsWith('/')) {
      rel = rel.substring(1);
    }
    return rel;
  }
  return childPath.startsWith('$.') ? childPath.substring(2) : childPath;
}

function hasJsonPath(obj, path) {
  if (!obj || !path) return false;
  const cleanPath = path.startsWith('$.') ? path.substring(2) : (path.startsWith('$') ? path.substring(1) : path);
  const parts = cleanPath.split('.');
  let curr = obj;
  for (const p of parts) {
    if (!curr || typeof curr !== 'object' || !(p in curr)) return false;
    curr = curr[p];
  }
  return true;
}

function findKeyPathInObject(obj, targetKey, currentPath = '') {
  if (!obj || typeof obj !== 'object') return null;
  if (Object.prototype.hasOwnProperty.call(obj, targetKey)) {
    return currentPath ? `${currentPath}.${targetKey}` : targetKey;
  }
  for (const k of Object.keys(obj)) {
    if (obj[k] && typeof obj[k] === 'object') {
      const found = findKeyPathInObject(obj[k], targetKey, currentPath ? `${currentPath}.${k}` : k);
      if (found) return found;
    }
  }
  return null;
}

/**
 * Applies edited text of a child node back into its parent container text,
 * properly re-escaping according to the node's Container Encoding Envelope.
 *
 * @param {string} parentText
 * @param {object} nodeDescriptor
 * @param {string} editedChildText
 * @param {object} options { forceInvalid: boolean, minifyOnSave: boolean }
 * @param {object} [parentDescriptor=null]
 * @returns {string} Re-escaped parent text
 */
export function applyNestedPayloadEdit(parentText, nodeDescriptor, editedChildText, options = {}, parentDescriptor = null) {
  if (!nodeDescriptor) return editedChildText;

  // 1. Validation check
  if (!options.forceInvalid) {
    const valResult = validatePayload(editedChildText, nodeDescriptor.format);
    if (!valResult.valid) {
      throw new Error(valResult.errorMessage || 'Invalid payload syntax');
    }
  }

  // 2. Whitespace handling: ONLY minify if explicitly requested; NEVER re-format intermediate envelopes
  let processedText = editedChildText;
  if (options.minifyOnSave) {
    if (nodeDescriptor.format === 'json') {
      try {
        processedText = JSON.stringify(JSON.parse(editedChildText));
      } catch (_) {}
    } else if (nodeDescriptor.format === 'xml') {
      processedText = minifyXml(editedChildText);
    }
  }

  // 3. Re-encode according to Container Encoding Envelope (surgical in-place substitution)
  if (nodeDescriptor.encoding === 'BASE64_UTF8' || nodeDescriptor.encoding === 'BASE64') {
    const editedPrologCharset = detectCharset(processedText);
    const targetCharset = editedPrologCharset !== 'UTF-8' ? editedPrologCharset : (nodeDescriptor.charset || 'UTF-8');
    const encoded = encodeToBase64(processedText, targetCharset);
    if (nodeDescriptor.rawValue && parentText.includes(nodeDescriptor.rawValue)) {
      return parentText.replace(nodeDescriptor.rawValue, encoded);
    }
    return replaceNodeValueInParent(parentText, nodeDescriptor, encoded, parentDescriptor);
  }

  if (nodeDescriptor.encoding === 'XML_CDATA') {
    const cdataWrapper = `<![CDATA[${processedText}]]>`;
    if (nodeDescriptor.rawValue && parentText.includes(`<![CDATA[${nodeDescriptor.rawValue}]]>`)) {
      return parentText.replace(`<![CDATA[${nodeDescriptor.rawValue}]]>`, cdataWrapper);
    }
    if (nodeDescriptor.rawValue && parentText.includes(nodeDescriptor.rawValue)) {
      return parentText.replace(nodeDescriptor.rawValue, processedText);
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
  return replaceNodeValueInParent(parentText, nodeDescriptor, processedText, parentDescriptor);
}

function replaceNodeValueInParent(parentText, nodeDescriptor, newStringValue, parentDescriptor = null) {
  // 1. Surgical in-place substitution: if parent contains JSON-serialized rawValue, replace directly!
  if (nodeDescriptor && nodeDescriptor.rawValue !== undefined && nodeDescriptor.rawValue !== null) {
    const oldSerialized = JSON.stringify(nodeDescriptor.rawValue);
    const newSerialized = JSON.stringify(newStringValue);
    if (parentText.includes(oldSerialized)) {
      return parentText.replace(oldSerialized, newSerialized);
    }
  }

  // 2. Structured fallback if direct string match fails (e.g. altered whitespace in parent)
  const parentPath = parentDescriptor ? parentDescriptor.path : '$';
  let targetPath = getRelativeJsonPath(nodeDescriptor ? nodeDescriptor.path : null, parentPath);
  try {
    const parsedParent = JSON.parse(parentText);
    if (nodeDescriptor && nodeDescriptor.key && Object.prototype.hasOwnProperty.call(parsedParent, nodeDescriptor.key)) {
      parsedParent[nodeDescriptor.key] = newStringValue;
    } else {
      if (!targetPath || !hasJsonPath(parsedParent, targetPath)) {
        const found = (nodeDescriptor && nodeDescriptor.key) ? findKeyPathInObject(parsedParent, nodeDescriptor.key) : null;
        if (found) targetPath = found;
      }
      setJsonPathValue(parsedParent, targetPath, newStringValue);
    }
    // Retain indentation if parent was indented
    const isIndented = parentText.includes('\n');
    return JSON.stringify(parsedParent, null, isIndented ? 2 : undefined);
  } catch (_) {
    // String replacement fallback if parent is not parseable JSON
    if (nodeDescriptor && nodeDescriptor.rawValue && parentText.includes(nodeDescriptor.rawValue)) {
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
    const parentText = parentFrame.text !== undefined ? parentFrame.text : parentFrame.payload;
    const descriptor = childFrame.descriptor || childFrame.targetNode;
    const parentDescriptor = parentFrame.descriptor || parentFrame.targetNode;
    currentText = applyNestedPayloadEdit(
      parentText,
      descriptor,
      currentText,
      options,
      parentDescriptor
    );
    if (parentFrame.text !== undefined) parentFrame.text = currentText;
    if (parentFrame.payload !== undefined) parentFrame.payload = currentText;
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
  const editable = options.editable !== undefined ? Boolean(options.editable) : Boolean(options.onSaveRoot);
  const readOnlyReason = options.readOnlyReason || 'Read-only payload view';
  const session = options.session || {
    editable,
    readOnlyReason,
    onSaveRoot: options.onSaveRoot || null
  };

  // Create state container if not already initialized
  const viewerId = 'viewer_' + Math.random().toString(36).substring(2, 9);
  let isUnescaped = options.defaultUnescaped !== false && (hasEmbedded || format !== 'text');
  let currentViewerText = trimmed;

  const editDisabledAttr = editable
    ? 'title="Edit root document in-line"'
    : `disabled title="${escapeHtml(readOnlyReason)}"`;

  containerEl.innerHTML = `
    <div class="payload-viewer-box" id="${viewerId}">
      <div class="payload-viewer-header">
        <div class="payload-viewer-toggles">
          <button type="button" class="btn btn-tiny toggle-mode-btn ${isUnescaped ? 'active' : ''} btn-mode-rich" data-mode="unescaped" title="Unescaped, formatted, indented">
            🎨 Rich ${hasEmbedded ? `(${discovered.length} nested)` : ''}
          </button>
          <button type="button" class="btn btn-tiny toggle-mode-btn ${!isUnescaped ? 'active' : ''} btn-mode-raw" data-mode="raw" title="Wire-exact escaped syntax view">
            📝 Raw
          </button>
        </div>
        <div class="payload-viewer-actions">
          <button type="button" class="btn btn-tiny btn-viewer-inline-edit" ${editDisabledAttr}>
            ✏️ Edit
          </button>
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
  const bodyEl = boxEl.querySelector('.payload-viewer-body');

  function renderContent() {
    const activeDiscovered = discoverEmbeddedPayloads(currentViewerText);
    const activeHasEmbedded = activeDiscovered.length > 0;
    bodyEl.innerHTML = '<pre class="syntax-view"><code class="payload-code-output"></code></pre>';
    const codeEl = bodyEl.querySelector('.payload-code-output');

    if (!isUnescaped || !activeHasEmbedded) {
      codeEl.innerHTML = highlightPayload(isUnescaped ? formatPayload(currentViewerText) : currentViewerText);
      return;
    }

    const updatedSession = {
      ...session,
      editable,
      readOnlyReason,
      onSaveRoot: (newRootText) => {
        currentViewerText = newRootText;
        if (typeof options.onSaveRoot === 'function') {
          options.onSaveRoot(newRootText);
        }
        renderContent();
      }
    };

    // Render formatted root with interactive Truncated Payload Previews
    codeEl.innerHTML = renderFormattedWithNestedPreviews(
      currentViewerText,
      activeDiscovered,
      viewerId,
      updatedSession.onSaveRoot,
      null,
      currentViewerText,
      updatedSession
    );
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

  // Bind Top-level In-line Edit
  const inlineEditBtn = boxEl.querySelector('.btn-viewer-inline-edit');
  if (inlineEditBtn && editable) {
    inlineEditBtn.addEventListener('click', () => {
      if (typeof options.onRequestTopEdit === 'function') {
        options.onRequestTopEdit();
        return;
      }
      mountInlineBlockEditor({
        containerEl: bodyEl,
        initialText: isUnescaped ? formatPayload(currentViewerText) : currentViewerText,
        format: detectFormat(currentViewerText),
        mode: isUnescaped ? 'rich' : 'raw',
        onApply: (editedText) => {
          currentViewerText = editedText;
          if (typeof options.onSaveRoot === 'function') {
            options.onSaveRoot(editedText);
          }
          renderContent();
        },
        onCancel: () => {
          renderContent();
        }
      });
    });
  }

  // Bind Copy Buttons
  const copyRawBtn = boxEl.querySelector('.btn-copy-raw');
  const copyFormattedBtn = boxEl.querySelector('.btn-copy-formatted');

  copyRawBtn.addEventListener('click', () => {
    navigator.clipboard.writeText(currentViewerText).then(() => {
      copyRawBtn.textContent = '✓ Copied';
      setTimeout(() => { copyRawBtn.textContent = '📋 Copy Raw'; }, 1500);
    }).catch(() => {});
  });

  copyFormattedBtn.addEventListener('click', () => {
    const formatted = formatPayload(currentViewerText);
    navigator.clipboard.writeText(formatted).then(() => {
      copyFormattedBtn.textContent = '✓ Copied';
      setTimeout(() => { copyFormattedBtn.textContent = '✨ Copy Formatted'; }, 1500);
    }).catch(() => {});
  });

  renderContent();
}

const viewerRegistry = new Map();
const nodeRegistry = new Map();

function registerPayloadViewer(viewerId, rawText, discovered, onSaveRoot = null, session = null) {
  viewerRegistry.set(viewerId, { rawText, discovered, onSaveRoot, session });
}

/**
 * Mounts an auto-growing, zero-vertical-scrollbar Dual-Layer Code Editor in-line inside a container
 * with [✨ Format] [✓ Apply] [✕ Cancel] action bar and live syntax validation.
 */
export function mountInlineBlockEditor({
  containerEl,
  initialText = '',
  format = 'json',
  mode = 'rich',
  actionBarHostEl = null,
  onApply,
  onCancel
}) {
  if (!containerEl) return null;
  containerEl.classList.add('is-editing');

  containerEl.innerHTML = `
    <div class="inline-editor-shell" data-testid="inline-editor-shell">
      <div class="inline-editor-toolbar">
        <span class="payload-lint-pill hidden inline-editor-lint"></span>
        <div class="inline-editor-actions">
          <button type="button" class="btn btn-tiny btn-inline-format" title="Format / Prettify (${escapeHtml((format || 'TEXT').toUpperCase())})">✨ Format</button>
          <button type="button" class="btn btn-tiny btn-primary btn-inline-apply" data-testid="btn-inline-apply" title="Apply changes transiently to Root Document">✓ Apply</button>
          <button type="button" class="btn btn-tiny btn-secondary btn-inline-cancel" data-testid="btn-inline-cancel" title="Discard in-line edits and return to viewer">✕ Cancel</button>
        </div>
      </div>
      <div class="inline-editor-body">
        <textarea class="code-input inline-editor-textarea" data-testid="inline-editor-textarea" rows="2" spellcheck="false"></textarea>
      </div>
    </div>
  `;

  const shellEl = containerEl.querySelector('.inline-editor-shell');
  const textarea = shellEl.querySelector('.inline-editor-textarea');
  const lintEl = shellEl.querySelector('.inline-editor-lint');
  const btnFormat = shellEl.querySelector('.btn-inline-format');
  const btnApply = shellEl.querySelector('.btn-inline-apply');
  const btnCancel = shellEl.querySelector('.btn-inline-cancel');

  textarea.value = initialText;
  const dualLayer = attachDualLayerEditor(textarea, null, {
    autoGrow: true,
    initialMode: mode === 'raw' ? 'raw' : 'rich'
  });

  function checkValidation() {
    const val = textarea.value;
    const fmt = format || detectFormat(val);
    if (mode !== 'raw' && (fmt === 'json' || fmt === 'xml')) {
      const res = validatePayload(val, fmt);
      if (!res.valid) {
        if (lintEl) {
          lintEl.textContent = `⚠️ ${res.errorMessage}`;
          lintEl.classList.remove('hidden');
        }
        return false;
      }
    }
    if (lintEl) {
      lintEl.textContent = '';
      lintEl.classList.add('hidden');
    }
    return true;
  }

  textarea.addEventListener('input', checkValidation);

  if (btnFormat) {
    btnFormat.addEventListener('click', (e) => {
      e.stopPropagation();
      textarea.value = formatPayload(textarea.value);
      if (dualLayer) dualLayer.refresh();
      checkValidation();
    });
  }

  if (btnApply) {
    btnApply.addEventListener('click', (e) => {
      e.stopPropagation();
      if (!checkValidation()) return;
      containerEl.classList.remove('is-editing');
      if (typeof onApply === 'function') {
        onApply(textarea.value);
      }
    });
  }

  if (btnCancel) {
    btnCancel.addEventListener('click', (e) => {
      e.stopPropagation();
      containerEl.classList.remove('is-editing');
      if (typeof onCancel === 'function') {
        onCancel();
      }
    });
  }

  checkValidation();
  return { shellEl, textarea, dualLayer, checkValidation };
}

export function renderFormattedWithNestedPreviews(
  rawText,
  discovered,
  viewerId,
  onSaveRoot = null,
  ancestorFrames = null,
  rootText = null,
  session = null
) {
  let actualAncestorFrames = ancestorFrames;
  let actualRootText = rootText;
  let actualSession = session;

  if (ancestorFrames && !Array.isArray(ancestorFrames) && typeof ancestorFrames === 'object') {
    actualSession = ancestorFrames;
    actualAncestorFrames = null;
  } else if (onSaveRoot && typeof onSaveRoot === 'object' && !Array.isArray(onSaveRoot) && typeof onSaveRoot !== 'function') {
    actualSession = onSaveRoot;
    onSaveRoot = actualSession.onSaveRoot || null;
  }

  if (actualRootText === null || typeof actualRootText !== 'string') {
    actualRootText = rawText;
  }
  const currentAncestorFrames = Array.isArray(actualAncestorFrames) ? actualAncestorFrames : [
    { text: rawText, descriptor: null, title: 'Root Wire Document', format: detectFormat(rawText) }
  ];
  const resolvedEditable = actualSession && actualSession.editable !== undefined
    ? Boolean(actualSession.editable)
    : Boolean(onSaveRoot);
  const resolvedReadOnlyReason = (actualSession && actualSession.readOnlyReason)
    || 'Read-only preview — open in editor to modify';
  const resolvedSession = {
    editable: resolvedEditable,
    readOnlyReason: resolvedReadOnlyReason,
    onSaveRoot: onSaveRoot || (actualSession && actualSession.onSaveRoot) || null,
    reRenderRoot: actualSession && actualSession.reRenderRoot ? actualSession.reRenderRoot : null,
    context: (actualSession && actualSession.context) || 'stub'
  };

  registerPayloadViewer(viewerId, rawText, discovered, resolvedSession.onSaveRoot, resolvedSession);
  let formatted = formatPayload(rawText);
  const replacements = [];

  // Replace escaped strings with Auto-Unnested Previews (recursive descent)
  for (let i = 0; i < discovered.length; i++) {
    const node = discovered[i];
    const fullNodeId = `${viewerId}_n${i}`;
    const nodeAncestorFrames = [
      ...currentAncestorFrames,
      { text: node.unescapedValue, descriptor: node, title: node.path, format: node.format }
    ];
    nodeRegistry.set(fullNodeId, {
      node,
      viewerId,
      parentText: rawText,
      rootText: actualRootText,
      ancestorFrames: currentAncestorFrames,
      nodeAncestorFrames,
      onSaveRoot: resolvedSession.onSaveRoot,
      session: resolvedSession
    });

    // Recursively render child payloads if discovered has children
    let innerHtml = '';
    if (node.children && node.children.length > 0) {
      innerHtml = renderFormattedWithNestedPreviews(
        node.unescapedValue,
        node.children,
        `${fullNodeId}_c`,
        resolvedSession.onSaveRoot,
        nodeAncestorFrames,
        actualRootText,
        resolvedSession
      );
    } else {
      innerHtml = highlightPayload(formatPayload(node.unescapedValue));
    }

    const previewSnippet = node.unescapedValue.substring(0, 60).replace(/\s+/g, ' ');
    const sizeKb = (node.byteLength / 1024).toFixed(1);
    const pillLabel = formatPayloadDescriptor({
      format: node.format,
      encoding: node.encoding,
      charset: node.charset,
      sizeKb
    });
    const editBtnAttrs = resolvedEditable
      ? `title="Edit ${escapeHtml(node.path)} in-line"`
      : `disabled title="${escapeHtml(resolvedReadOnlyReason)}"`;

    const previewHtml = `<span class="truncated-payload-preview auto-expanded" data-node-id="${fullNodeId}" data-node-idx="${i}" data-viewer-id="${viewerId}" tabindex="0"><span class="preview-snippet">"${escapeHtml(previewSnippet)}..."</span><span class="preview-pill-group"><span class="preview-pill-badge btn-lens-expand-pill" data-node-id="${fullNodeId}" data-node-idx="${i}" role="button" tabindex="0" title="Click to collapse">${escapeHtml(pillLabel)}</span><div class="editor-mode-toggle nested-mode-toggle" data-node-id="${fullNodeId}"><button type="button" class="btn btn-tiny active btn-nested-mode-rich" data-mode="rich" title="Unescaped, formatted tree">🎨 Rich</button><button type="button" class="btn btn-tiny btn-nested-mode-raw" data-mode="raw" title="Wire-exact escaped raw representation">📝 Raw</button></div><button type="button" class="btn-payload-maximize btn-nested-inline-edit" data-node-id="${fullNodeId}" data-node-idx="${i}" ${editBtnAttrs}>✏️ Edit</button><button type="button" class="btn-payload-maximize btn-nested-copy" data-node-id="${fullNodeId}" data-node-idx="${i}" title="Copy subsection payload">📋 Copy</button><button type="button" class="btn-payload-maximize btn-nested-maximize btn-lens-open-pill" data-node-id="${fullNodeId}" data-node-idx="${i}" title="Maximize in Fullscreen Viewport">⛶ Maximize</button></span><div class="inline-expanded-payload" id="${viewerId}_expanded_${i}"><pre class="inline-code"><code>${innerHtml}</code></pre></div></span>`;

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
      const expandedDiv = parentPreview
        ? (parentPreview.querySelector(':scope > .inline-expanded-payload') || parentPreview.querySelector('.inline-expanded-payload'))
        : null;
      if (expandedDiv) {
        const isHidden = expandedDiv.classList.contains('hidden');
        if (isHidden) {
          expandedDiv.classList.remove('hidden');
          parentPreview.classList.add('auto-expanded');
          expandBtn.title = 'Click to collapse';
        } else {
          expandedDiv.classList.add('hidden');
          parentPreview.classList.remove('auto-expanded');
          expandBtn.title = 'Click to expand';
        }
      }
      return;
    }

    const openBtn = e.target.closest('.btn-lens-open-pill');
    if (openBtn) {
      e.stopPropagation();
      const nodeId = openBtn.getAttribute('data-node-id');
      const entry = nodeRegistry.get(nodeId);
      if (entry) {
        openPayloadLens(entry.rootText, entry.node, entry.onSaveRoot, entry.ancestorFrames, 'viewer', entry.session);
      } else {
        const parentPreview = openBtn.closest('.truncated-payload-preview');
        if (parentPreview) {
          const viewerId = parentPreview.getAttribute('data-viewer-id');
          const nodeIdx = parseInt(openBtn.getAttribute('data-node-idx'), 10);
          const stored = viewerRegistry.get(viewerId);
          if (stored && stored.discovered && stored.discovered[nodeIdx]) {
            openPayloadLens(stored.rawText, stored.discovered[nodeIdx], stored.onSaveRoot, null, 'viewer', stored.session);
          }
        }
      }
      return;
    }

    const nestedCopyBtn = e.target.closest('.btn-nested-copy');
    if (nestedCopyBtn) {
      e.stopPropagation();
      const nodeId = nestedCopyBtn.getAttribute('data-node-id');
      const entry = nodeRegistry.get(nodeId);
      if (entry && entry.node) {
        const parentPreview = nestedCopyBtn.closest('.truncated-payload-preview');
        const toggle = parentPreview ? parentPreview.querySelector(':scope > .preview-pill-group .nested-mode-toggle') : null;
        const isRaw = toggle && toggle.querySelector('.btn-nested-mode-raw.active');
        const textToCopy = isRaw ? entry.node.rawValue : formatPayload(entry.node.unescapedValue);
        if (navigator.clipboard && navigator.clipboard.writeText) {
          navigator.clipboard.writeText(textToCopy).catch(() => {});
        }
        nestedCopyBtn.textContent = '✓ Copied';
        setTimeout(() => { nestedCopyBtn.textContent = '📋 Copy'; }, 1500);
      }
      return;
    }

    const nestedEditBtn = e.target.closest('.btn-nested-inline-edit');
    if (nestedEditBtn) {
      e.stopPropagation();
      if (nestedEditBtn.disabled || nestedEditBtn.hasAttribute('disabled')) return;
      const nodeId = nestedEditBtn.getAttribute('data-node-id');
      const entry = nodeRegistry.get(nodeId);
      const parentPreview = nestedEditBtn.closest('.truncated-payload-preview');
      if (!entry || !parentPreview) return;

      const expandedDiv = parentPreview.querySelector(':scope > .inline-expanded-payload') || parentPreview.querySelector('.inline-expanded-payload');
      if (!expandedDiv) return;

      // Ensure expanded div is visible when entering edit
      expandedDiv.classList.remove('hidden');
      parentPreview.classList.add('auto-expanded');

      const toggle = parentPreview.querySelector(':scope > .preview-pill-group .nested-mode-toggle');
      const isRaw = toggle && toggle.querySelector('.btn-nested-mode-raw.active');
      const initialVal = isRaw ? entry.node.rawValue : formatPayload(entry.node.unescapedValue);

      nestedEditBtn.classList.add('hidden');
      const restoreNestedView = () => {
        nestedEditBtn.classList.remove('hidden');
        if (isRaw) {
          expandedDiv.innerHTML = `<pre class="inline-code mode-raw"><code>${highlightPayload(entry.node.rawValue)}</code></pre>`;
        } else {
          const hasKids = entry.node.children && entry.node.children.length > 0;
          const content = hasKids
            ? renderFormattedWithNestedPreviews(entry.node.unescapedValue, entry.node.children, `${nodeId}_c`, entry.onSaveRoot, entry.nodeAncestorFrames, entry.rootText, entry.session)
            : highlightPayload(formatPayload(entry.node.unescapedValue));
          expandedDiv.innerHTML = `<pre class="inline-code"><code>${content}</code></pre>`;
        }
      };

      mountInlineBlockEditor({
        containerEl: expandedDiv,
        initialText: initialVal,
        format: entry.node.format,
        mode: isRaw ? 'raw' : 'rich',
        onApply: (editedChildText) => {
          nestedEditBtn.classList.remove('hidden');
          const newRootText = cascadeLensStackToRoot(
            entry.nodeAncestorFrames.map(f => ({
              text: f.text,
              descriptor: f.descriptor,
              title: f.title,
              format: f.format
            })),
            editedChildText,
            { forceInvalid: Boolean(isRaw) }
          );
          if (typeof entry.onSaveRoot === 'function') {
            entry.onSaveRoot(newRootText);
          } else if (entry.session && typeof entry.session.reRenderRoot === 'function') {
            entry.session.reRenderRoot(newRootText);
          } else {
            entry.node.unescapedValue = editedChildText;
            restoreNestedView();
          }
        },
        onCancel: () => {
          restoreNestedView();
        }
      });
      return;
    }

    const nestedModeBtn = e.target.closest('.btn-nested-mode-rich, .btn-nested-mode-raw');
    if (nestedModeBtn) {
      e.stopPropagation();
      const toggle = nestedModeBtn.closest('.nested-mode-toggle');
      const parentPreview = toggle ? toggle.closest('.truncated-payload-preview') : null;
      if (parentPreview && toggle) {
        const nodeId = toggle.getAttribute('data-node-id');
        const entry = nodeRegistry.get(nodeId);
        const mode = nestedModeBtn.getAttribute('data-mode');
        const btnRich = toggle.querySelector('.btn-nested-mode-rich');
        const btnRaw = toggle.querySelector('.btn-nested-mode-raw');
        if (btnRich) btnRich.classList.toggle('active', mode === 'rich');
        if (btnRaw) btnRaw.classList.toggle('active', mode === 'raw');

        const expandedDiv = parentPreview.querySelector(':scope > .inline-expanded-payload') || parentPreview.querySelector('.inline-expanded-payload');
        if (expandedDiv && entry) {
          const inlineTextarea = expandedDiv.querySelector('.inline-editor-textarea');
          if (inlineTextarea && inlineTextarea._dualLayer) {
            inlineTextarea._dualLayer.setMode(mode);
          } else if (mode === 'raw') {
            expandedDiv.innerHTML = `<pre class="inline-code mode-raw"><code>${highlightPayload(entry.node.rawValue)}</code></pre>`;
          } else {
            const hasKids = entry.node.children && entry.node.children.length > 0;
            const content = hasKids
              ? renderFormattedWithNestedPreviews(entry.node.unescapedValue, entry.node.children, `${nodeId}_c`, entry.onSaveRoot, entry.nodeAncestorFrames, entry.rootText, entry.session)
              : highlightPayload(formatPayload(entry.node.unescapedValue));
            expandedDiv.innerHTML = `<pre class="inline-code"><code>${content}</code></pre>`;
          }
        }
      }
      return;
    }

    const snippetSpan = e.target.closest('.preview-snippet');
    if (snippetSpan) {
      const parentPreview = snippetSpan.closest('.truncated-payload-preview');
      const badge = parentPreview ? parentPreview.querySelector('.btn-lens-expand-pill') : null;
      if (badge) badge.click();
      return;
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
        }, null, 'viewer');
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
 * Attaches a synchronized Dual-Layer Code Editor to a native <textarea>.
 * Places an invisible foreground textarea over an identical syntax-highlighted
 * <pre class="dual-layer-backdrop"><code> backdrop with scroll locking, typography matching,
 * and an in-memory [🎨 Rich | 📝 Raw] experience mode toggle.
 *
 * @param {HTMLTextAreaElement} textareaEl
 * @param {HTMLElement} [toggleContainerEl]
 * @param {object} [options]
 * @returns {object} Dual-layer editor controller
 */
export function attachDualLayerEditor(textareaEl, toggleContainerEl = null, options = {}) {
  if (!textareaEl) return null;
  if (textareaEl._dualLayer) {
    if (toggleContainerEl && !textareaEl._dualLayer.hasToggle) {
      textareaEl._dualLayer.bindToggle(toggleContainerEl);
    }
    textareaEl._dualLayer.refresh();
    return textareaEl._dualLayer;
  }

  const isAutoGrow = Boolean(options.autoGrow);

  // Ensure wrapper and backdrop exist
  let wrapper = textareaEl.parentElement;
  if (!wrapper || !wrapper.classList.contains('dual-layer-editor-wrapper')) {
    wrapper = document.createElement('div');
    wrapper.className = 'dual-layer-editor-wrapper mode-rich';
    if (textareaEl.parentNode) {
      textareaEl.parentNode.insertBefore(wrapper, textareaEl);
    }
    wrapper.appendChild(textareaEl);
  } else {
    wrapper.classList.add('mode-rich');
  }

  if (isAutoGrow) {
    wrapper.style.flex = 'none';
    wrapper.style.overflow = 'hidden';
  }

  let backdrop = wrapper.querySelector('.dual-layer-backdrop');
  let code = backdrop ? backdrop.querySelector('code') : null;
  if (!backdrop) {
    backdrop = document.createElement('pre');
    backdrop.className = 'dual-layer-backdrop';
    backdrop.setAttribute('aria-hidden', 'true');
    code = document.createElement('code');
    code.className = 'dual-layer-code';
    backdrop.appendChild(code);
    wrapper.insertBefore(backdrop, textareaEl);
  }

  textareaEl.classList.add('dual-layer-input');
  textareaEl.spellcheck = false;

  let currentMode = options.initialMode || 'rich'; // in-memory experience mode: 'rich' or 'raw'
  let toggleGroup = null;

  const adjustHeight = () => {
    if (!isAutoGrow) return;
    const lines = (textareaEl.value || '').split('\n').length;
    const isInlineShell = Boolean(wrapper.closest('.inline-editor-shell'));
    const calculatedHeight = isInlineShell
      ? Math.max(24, lines * 19 + 8)
      : Math.max(48, lines * 21 + 26);
    wrapper.style.height = `${calculatedHeight}px`;
    if (backdrop) backdrop.style.height = `${calculatedHeight}px`;
  };

  const syncScroll = () => {
    if (backdrop) {
      backdrop.scrollTop = textareaEl.scrollTop;
      backdrop.scrollLeft = textareaEl.scrollLeft;
    }
  };

  const updateToggleButtons = (mode, isAutoDegraded = false) => {
    if (!toggleGroup) return;
    const btnRich = toggleGroup.querySelector('.btn-mode-rich');
    const btnRaw = toggleGroup.querySelector('.btn-mode-raw');
    if (btnRich && btnRaw) {
      if (mode === 'rich' && !isAutoDegraded) {
        btnRich.classList.add('active');
        btnRaw.classList.remove('active');
      } else {
        btnRich.classList.remove('active');
        btnRaw.classList.add('active');
        if (isAutoDegraded) {
          btnRaw.title = 'Plain Native Textarea (Payload > 20 KB)';
        }
      }
    }
  };

  const updateBackdrop = () => {
    if (!code) return;
    const val = textareaEl.value;
    if (val.length > MAX_HIGHLIGHT_PAYLOAD_CHARS) {
      wrapper.classList.remove('mode-rich');
      wrapper.classList.add('mode-raw', 'mode-degraded');
      updateToggleButtons('raw', true);
      adjustHeight();
      return;
    } else {
      wrapper.classList.remove('mode-degraded');
      if (currentMode === 'rich') {
        wrapper.classList.remove('mode-raw');
        wrapper.classList.add('mode-rich');
        textareaEl.style.whiteSpace = 'pre';
        textareaEl.style.wordBreak = 'normal';
        if (backdrop) {
          backdrop.style.whiteSpace = 'pre';
          backdrop.style.wordBreak = 'normal';
        }
      } else {
        wrapper.classList.remove('mode-rich');
        wrapper.classList.add('mode-raw');
        textareaEl.style.whiteSpace = 'pre-wrap';
        textareaEl.style.wordBreak = 'break-all';
        if (backdrop) {
          backdrop.style.whiteSpace = 'pre-wrap';
          backdrop.style.wordBreak = 'break-all';
        }
      }
      updateToggleButtons(currentMode, false);
    }
    const html = highlightPayload(val) + (val.endsWith('\n') ? ' ' : '');
    code.innerHTML = html;
    syncScroll();
    adjustHeight();
  };

  const setMode = (mode) => {
    currentMode = mode === 'raw' ? 'raw' : 'rich';
    updateBackdrop();
    updateToggleButtons(currentMode, false);
  };

  const bindToggle = (container) => {
    if (!container) return;
    toggleGroup = container.classList.contains('editor-mode-toggle') ? container : container.querySelector('.editor-mode-toggle');
    if (!toggleGroup) {
      toggleGroup = document.createElement('div');
      toggleGroup.className = 'editor-mode-toggle';
      toggleGroup.innerHTML = `
        <button type="button" class="btn btn-tiny ${currentMode === 'rich' ? 'active' : ''} btn-mode-rich" data-mode="rich" title="Syntax Highlighted Dual-Layer Editor">🎨 Rich</button>
        <button type="button" class="btn btn-tiny ${currentMode === 'raw' ? 'active' : ''} btn-mode-raw" data-mode="raw" title="Plain Native Textarea">📝 Raw</button>
      `;
      container.appendChild(toggleGroup);
    }
    const btnRich = toggleGroup.querySelector('.btn-mode-rich');
    const btnRaw = toggleGroup.querySelector('.btn-mode-raw');
    if (btnRich) {
      btnRich.onclick = (e) => {
        e.preventDefault();
        setMode('rich');
      };
    }
    if (btnRaw) {
      btnRaw.onclick = (e) => {
        e.preventDefault();
        setMode('raw');
      };
    }
    instance.hasToggle = true;
  };

  textareaEl.addEventListener('scroll', syncScroll);
  textareaEl.addEventListener('input', () => {
    updateBackdrop();
    if (typeof options.onChange === 'function') {
      options.onChange(textareaEl.value);
    }
  });

  const instance = {
    wrapper,
    backdrop,
    code,
    textarea: textareaEl,
    setMode,
    getMode: () => currentMode,
    refresh: updateBackdrop,
    adjustHeight,
    bindToggle,
    hasToggle: false
  };

  if (toggleContainerEl) {
    bindToggle(toggleContainerEl);
  }

  textareaEl._dualLayer = instance;
  updateBackdrop();
  return instance;
}

/**
 * Factory function creating a Unified Payload Component with the 2x2 Payload Experience Matrix:
 * - Mode: Rich (unescaped, formatted, indented) vs Raw (wire-exact, escaped)
 * - Action: View (read-only syntax block) vs Edit (Dual-Layer Code Editor)
 * Supports both standalone mounting into a container and binding to existing console surface DOM elements.
 *
 * @param {HTMLElement|null} containerEl
 * @param {object} options
 * @returns {object} Controller instance
 */
export function createPayloadComponent(containerEl, {
  title = '',
  text = '',
  format = null,
  charset = null,
  context = 'stub',
  readOnly = false,
  editable = !readOnly,
  readOnlyReason = 'Read-only payload view',
  defaultMode = 'rich',
  defaultAction = 'view',
  backingTextarea = null,
  backingPre = null,
  onSave = null,
  onChange = null,
  onMaximize = null,
  viewerEl = null,
  toggleEl = null,
  pillEl = null,
  pillGroupEl = null,
  lintPillEl = null,
  formatBtn = null,
  maximizeBtn = null,
  editBtn = null,
  applyBtn = null,
  cancelEditBtn = null,
  copyBtn = null,
  onEdit = null,
  bufferType = null,
  autoGrow = false
} = {}) {
  const isEditable = Boolean(editable && !readOnly);
  let currentText = backingTextarea ? (backingTextarea.value || '') : (text || '');
  let preEditText = currentText;
  let currentMode = defaultMode; // 'rich' | 'raw'
  let currentAction = !isEditable ? 'view' : defaultAction; // 'view' | 'edit'
  const componentId = 'upc_' + Math.random().toString(36).substring(2, 9);

  const cleanFormat = format || detectFormat(currentText);
  const cleanCharset = charset || detectCharset(currentText);

  const isExternal = Boolean(viewerEl || backingTextarea);
  let rootEl = null;
  let viewContainer = viewerEl;
  let editContainer = null;
  let textareaEl = backingTextarea;
  let descPill = pillEl;
  let lintPill = lintPillEl;
  let btnRich = null;
  let btnRaw = null;
  let btnActionView = null;
  let btnActionEdit = editBtn;
  let btnApplyEdit = applyBtn;
  let btnCancelEdit = cancelEditBtn;
  let btnFormat = formatBtn;
  let btnCopy = copyBtn;
  let btnMax = maximizeBtn;

  const session = {
    editable: isEditable,
    readOnlyReason: readOnlyReason || 'Read-only payload view',
    context,
    onSaveRoot: (newRootText) => syncText(newRootText, true),
    reRenderRoot: (newRootText) => syncText(newRootText, true)
  };

  if (isExternal) {
    if (toggleEl) {
      btnRich = toggleEl.querySelector('.btn-mode-rich');
      btnRaw = toggleEl.querySelector('.btn-mode-raw');
    }
    if (!lintPill && pillGroupEl) {
      lintPill = pillGroupEl.querySelector('.payload-lint-pill');
      if (!lintPill) {
        lintPill = document.createElement('span');
        lintPill.className = 'payload-lint-pill hidden';
        if (maximizeBtn && maximizeBtn.parentNode === pillGroupEl) {
          pillGroupEl.insertBefore(lintPill, maximizeBtn);
        } else {
          pillGroupEl.appendChild(lintPill);
        }
      }
    }
  } else if (containerEl) {
    containerEl.innerHTML = `
      <div class="unified-payload-surface" id="${componentId}" data-context="${escapeHtml(context)}">
        <div class="unified-payload-header">
          <div class="unified-payload-header-left">
            ${title ? `<strong class="payload-title">${escapeHtml(title)}:</strong>` : ''}
            <span class="preview-pill-badge payload-desc-pill" role="button" tabindex="0" title="Click to collapse/expand"></span>
            <span class="payload-lint-pill hidden"></span>
          </div>
          <div class="unified-payload-header-right">
            <div class="editor-mode-toggle">
              <button type="button" class="btn btn-tiny ${currentMode === 'rich' ? 'active' : ''} btn-mode-rich" data-mode="rich" title="Unescaped, formatted, indented">🎨 Rich</button>
              <button type="button" class="btn btn-tiny ${currentMode === 'raw' ? 'active' : ''} btn-mode-raw" data-mode="raw" title="Wire-exact escaped syntax view">📝 Raw</button>
            </div>
            <div class="action-mode-toggle">
              <button type="button" class="btn btn-tiny ${currentAction === 'view' ? 'active' : ''} btn-action-view ${!isEditable ? 'hidden' : ''}" data-action="view" title="Read-only view">👁️ View</button>
              <button type="button" class="btn btn-tiny ${currentAction === 'edit' ? 'active' : ''} btn-action-edit" data-action="edit" ${!isEditable ? `disabled title="${escapeHtml(session.readOnlyReason)}"` : 'title="Switch to in-line editor"'}>✏️ Edit</button>
            </div>
            ${isEditable ? `<button type="button" class="btn btn-tiny btn-format-payload ${currentAction === 'edit' ? '' : 'hidden'}" title="Format/re-indent JSON/XML">✨ Format</button>` : ''}
            <button type="button" class="btn btn-tiny btn-copy-payload" title="Copy payload text">📋 Copy</button>
            <button type="button" class="btn-payload-maximize btn-maximize-payload" title="Maximize in Fullscreen Viewport">⛶ Maximize</button>
          </div>
        </div>
        <div class="unified-payload-body">
          <div class="payload-view-container ${currentAction === 'view' ? '' : 'hidden'}"></div>
          <div class="payload-edit-container ${currentAction === 'edit' ? '' : 'hidden'}" style="flex: 1 1 0%; display: ${currentAction === 'edit' ? 'flex' : 'none'}; min-height: 0;">
            <textarea class="code-input payload-component-textarea" rows="12" spellcheck="false" style="font-family: monospace; font-size: 0.85rem; width: 100%; flex: 1 1 0%; resize: none;"></textarea>
          </div>
        </div>
      </div>
    `;

    rootEl = containerEl.querySelector(`#${componentId}`);
    viewContainer = rootEl.querySelector('.payload-view-container');
    editContainer = rootEl.querySelector('.payload-edit-container');
    textareaEl = rootEl.querySelector('.payload-component-textarea');
    descPill = rootEl.querySelector('.payload-desc-pill');
    lintPill = rootEl.querySelector('.payload-lint-pill');
    btnRich = rootEl.querySelector('.btn-mode-rich');
    btnRaw = rootEl.querySelector('.btn-mode-raw');
    btnActionView = rootEl.querySelector('.btn-action-view');
    btnActionEdit = rootEl.querySelector('.btn-action-edit');
    btnFormat = rootEl.querySelector('.btn-format-payload');
    btnCopy = rootEl.querySelector('.btn-copy-payload');
    btnMax = rootEl.querySelector('.btn-maximize-payload');
  }

  let dualLayerInstance = null;
  if (isEditable && textareaEl) {
    dualLayerInstance = attachDualLayerEditor(textareaEl, toggleEl, { autoGrow });
    if (isExternal && viewContainer) {
      editContainer = textareaEl.closest('.dual-layer-editor-wrapper') || textareaEl.parentElement || textareaEl;
    }
  }

  function updateDescriptor() {
    if (pillGroupEl) {
      pillGroupEl.style.display = currentText && currentText.trim() ? 'inline-flex' : 'none';
    }
    if (!descPill) return;
    const sizeKb = (new Blob([currentText]).size / 1024).toFixed(1);
    const fmt = (cleanFormat || detectFormat(currentText)).toUpperCase();
    if (bufferType) {
      descPill.textContent = formatCasualBufferDescriptor(bufferType, fmt, sizeKb, cleanCharset);
    } else {
      descPill.textContent = formatPayloadDescriptor({
        format: fmt,
        charset: cleanCharset,
        sizeKb
      });
    }
  }

  function renderView() {
    if (!viewContainer) return;
    viewContainer._payloadSession = session;
    viewContainer._rootText = currentText;
    viewContainer.classList.toggle('mode-raw', currentMode === 'raw');
    if (currentMode === 'rich') {
      const discovered = discoverEmbeddedPayloads(currentText);
      if (discovered.length > 0) {
        viewContainer.innerHTML = renderFormattedWithNestedPreviews(currentText, discovered, `${componentId}_v`, null, session);
      } else {
        viewContainer.innerHTML = highlightPayload(formatPayload(currentText));
      }
    } else {
      // Raw view: wire-exact representation
      viewContainer.innerHTML = highlightPayload(currentText);
    }
  }

  function syncText(newText, triggerChange = true) {
    currentText = newText;
    updateDescriptor();
    if (textareaEl && textareaEl.value !== newText) {
      textareaEl.value = newText;
      if (dualLayerInstance) dualLayerInstance.refresh();
    }
    if (backingTextarea && backingTextarea.value !== newText) {
      backingTextarea.value = newText;
      if (backingTextarea._dualLayer) backingTextarea._dualLayer.refresh();
      if (triggerChange) {
        backingTextarea.dispatchEvent(new Event('input', { bubbles: true }));
      }
    }
    if (backingPre) {
      backingPre._payloadSession = session;
      backingPre._rootText = newText;
      const discovered = discoverEmbeddedPayloads(newText);
      if (currentMode === 'rich' && discovered.length > 0) {
        backingPre.innerHTML = renderFormattedWithNestedPreviews(newText, discovered, `${componentId}_bp`, null, session);
      } else {
        backingPre.innerHTML = highlightPayload(currentMode === 'rich' ? formatPayload(newText) : newText);
      }
    }
    if (currentAction === 'view') {
      renderView();
    }
    validate();
    if (triggerChange && typeof onChange === 'function') {
      onChange(currentText);
    }
    if (typeof onSave === 'function' && !triggerChange) {
      onSave(currentText);
    }
  }

  function validate() {
    if (!lintPill) return true;
    const trimmed = (currentText || '').trim();
    if (!trimmed) {
      lintPill.classList.add('hidden');
      lintPill.textContent = '';
      return true;
    }
    let targetFormat = cleanFormat;
    if (!targetFormat || targetFormat === 'text') {
      if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
        targetFormat = 'json';
      } else if (trimmed.startsWith('<')) {
        targetFormat = 'xml';
      }
    }
    if (targetFormat === 'json' || targetFormat === 'xml') {
      const res = validatePayload(currentText, targetFormat);
      if (!res.valid) {
        lintPill.classList.remove('hidden');
        lintPill.textContent = `⚠️ Invalid ${targetFormat.toUpperCase()}: ${res.errorMessage}`;
        return false;
      }
    }
    lintPill.classList.add('hidden');
    lintPill.textContent = '';
    return true;
  }

  function setMode(mode) {
    currentMode = mode === 'raw' ? 'raw' : 'rich';
    if (btnRich && btnRaw) {
      btnRich.classList.toggle('active', currentMode === 'rich');
      btnRaw.classList.toggle('active', currentMode === 'raw');
    }
    if (dualLayerInstance) {
      dualLayerInstance.setMode(currentMode);
    }
    if (currentAction === 'view') {
      renderView();
    }
  }

  function updateToolbarActionState() {
    if (btnActionView && btnActionEdit && !isExternal) {
      btnActionView.classList.toggle('active', currentAction === 'view');
      btnActionEdit.classList.toggle('active', currentAction === 'edit');
    }
    if (isExternal && viewContainer && editContainer) {
      if (btnActionEdit) {
        btnActionEdit.classList.toggle('hidden', currentAction === 'edit');
      }
      if (btnApplyEdit) {
        btnApplyEdit.classList.toggle('hidden', currentAction !== 'edit');
      }
      if (btnCancelEdit) {
        btnCancelEdit.classList.toggle('hidden', currentAction !== 'edit');
      }
      if (btnFormat) {
        btnFormat.classList.toggle('hidden', currentAction !== 'edit');
      }
    } else if (btnFormat && !isExternal) {
      btnFormat.classList.toggle('hidden', currentAction !== 'edit');
    }
  }

  function setAction(action) {
    if (!isEditable) return;
    const nextAction = action === 'edit' ? 'edit' : 'view';
    if (nextAction === 'edit' && currentAction !== 'edit') {
      preEditText = currentText;
    }
    currentAction = nextAction;
    updateToolbarActionState();
    if (currentAction === 'view') {
      if (editContainer) {
        editContainer.classList.add('hidden');
        editContainer.style.display = 'none';
      }
      if (viewContainer) {
        viewContainer.classList.remove('hidden');
        viewContainer.style.display = '';
      }
      renderView();
    } else {
      if (viewContainer) {
        viewContainer.classList.add('hidden');
        viewContainer.style.display = 'none';
      }
      if (editContainer) {
        editContainer.classList.remove('hidden');
        editContainer.style.display = 'flex';
      }
      if (textareaEl) {
        textareaEl.value = currentText;
        if (dualLayerInstance) dualLayerInstance.refresh();
      }
    }
  }

  // Bind Listeners
  if (btnRich) btnRich.onclick = (e) => { e.stopPropagation(); setMode('rich'); };
  if (btnRaw) btnRaw.onclick = (e) => { e.stopPropagation(); setMode('raw'); };
  if (btnActionView) btnActionView.onclick = (e) => { e.stopPropagation(); setAction('view'); };
  if (btnActionEdit && !isExternal) {
    btnActionEdit.onclick = (e) => {
      e.stopPropagation();
      if (!isEditable) return;
      setAction('edit');
    };
  }

  if (btnFormat) {
    btnFormat.onclick = (e) => {
      e.stopPropagation();
      const formatted = formatPayload(currentText);
      syncText(formatted);
    };
  }

  if (btnCopy) {
    btnCopy.onclick = (e) => {
      e.stopPropagation();
      const copyVal = currentMode === 'rich' ? formatPayload(currentText) : currentText;
      navigator.clipboard.writeText(copyVal).then(() => {
        const orig = btnCopy.textContent;
        btnCopy.textContent = '✓ Copied';
        setTimeout(() => { btnCopy.textContent = orig; }, 1500);
      }).catch(() => {});
    };
  }

  if (btnMax) {
    btnMax.onclick = (e) => {
      e.stopPropagation();
      if (typeof onMaximize === 'function') {
        onMaximize();
      } else {
        pushViewportLayer({
          title: title || 'Payload Viewport',
          text: currentText,
          format: cleanFormat,
          charset: cleanCharset,
          context,
          editable: isEditable,
          readOnlyReason: session.readOnlyReason,
          mode: 'viewer',
          onSave: (savedText) => {
            syncText(savedText);
            if (typeof onSave === 'function') {
              onSave(savedText);
            }
          }
        });
      }
    };
  }

  if (editBtn && isExternal) {
    if (!isEditable && !onEdit) {
      editBtn.disabled = true;
      editBtn.title = session.readOnlyReason;
    } else {
      editBtn.disabled = false;
      editBtn.onclick = (e) => {
        e.stopPropagation();
        if (typeof onEdit === 'function') {
          onEdit();
        } else if (viewContainer && editContainer) {
          setAction('edit');
        }
      };
    }
  }

  if (btnApplyEdit) {
    btnApplyEdit.onclick = (e) => {
      e.stopPropagation();
      if (!validate()) return;
      setAction('view');
      if (typeof onChange === 'function') {
        onChange(currentText);
      }
    };
  }

  if (btnCancelEdit) {
    btnCancelEdit.onclick = (e) => {
      e.stopPropagation();
      syncText(preEditText, false);
      setAction('view');
    };
  }

  if (descPill) {
    descPill.onclick = () => {
      if (viewContainer && currentAction === 'view') {
        const isHidden = viewContainer.classList.toggle('hidden');
        descPill.title = isHidden ? 'Click to expand' : 'Click to collapse';
      }
    };
  }

  if (textareaEl) {
    if (!isExternal) {
      textareaEl.value = currentText;
    }
    textareaEl.addEventListener('input', () => {
      syncText(textareaEl.value, false);
    });
  }

  // Initial render
  updateDescriptor();
  if (isExternal && viewContainer && editContainer) {
    setAction(currentAction);
  } else if (currentAction === 'view') {
    renderView();
  } else {
    validate();
  }

  return {
    getText: () => currentText,
    setText: (newText) => syncText(newText, false),
    getMode: () => currentMode,
    setMode,
    getAction: () => currentAction,
    setAction,
    session,
    refresh: () => {
      updateDescriptor();
      if (currentAction === 'view') renderView();
      else if (dualLayerInstance) dualLayerInstance.refresh();
    }
  };
}

/**
 * Mounts a Dual-Layer Code Editor onto an input textarea with standardized syntax highlighting,
 * format prettifying, live error diagnostics, and fullscreen maximize capabilities.
 *
 * @param {HTMLTextAreaElement} textareaEl
 * @param {object} [options]
 * @returns {object} Controller instance
 */
export function mountCodeInputEditor(textareaEl, options = {}) {
  if (!textareaEl) return null;
  const {
    title = 'Code Editor',
    format = null,
    context = 'code-input',
    toggleContainer = null,
    formatBtn = null,
    maximizeBtn = null,
    lintPill = null,
    autoGrow = false,
    onChange = null
  } = options;

  const dualLayer = attachDualLayerEditor(textareaEl, toggleContainer, { autoGrow, onChange });

  function validate() {
    if (!lintPill) return true;
    const val = textareaEl.value;
    const fmt = format || detectFormat(val);
    if (fmt === 'json' || fmt === 'xml') {
      const res = validatePayload(val, fmt);
      if (!res.valid) {
        lintPill.textContent = `⚠️ Invalid ${fmt.toUpperCase()}: ${res.errorMessage}`;
        lintPill.classList.remove('hidden');
        return false;
      }
    }
    lintPill.textContent = '';
    lintPill.classList.add('hidden');
    return true;
  }

  if (formatBtn) {
    formatBtn.onclick = (e) => {
      e.stopPropagation();
      textareaEl.value = formatPayload(textareaEl.value);
      if (dualLayer) dualLayer.refresh();
      validate();
      if (typeof onChange === 'function') onChange(textareaEl.value);
    };
  }

  if (maximizeBtn) {
    maximizeBtn.onclick = (e) => {
      e.preventDefault();
      const val = textareaEl.value;
      const fmt = format || detectFormat(val);
      pushViewportLayer({
        title,
        text: val,
        format: fmt,
        context,
        mode: 'editor',
        onSave: (savedText) => {
          textareaEl.value = savedText;
          if (dualLayer) dualLayer.refresh();
          validate();
          textareaEl.dispatchEvent(new Event('input', { bubbles: true }));
          if (typeof onChange === 'function') onChange(savedText);
        }
      });
    };
  }

  textareaEl.addEventListener('input', validate);
  validate();

  return {
    dualLayer,
    validate,
    refresh: () => {
      if (dualLayer) dualLayer.refresh();
      validate();
    },
    getValue: () => textareaEl.value,
    setValue: (val) => {
      textareaEl.value = val;
      if (dualLayer) dualLayer.refresh();
      validate();
    }
  };
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
      const chipLabel = formatPayloadDescriptor({
        format: node.format,
        encoding: node.encoding,
        charset: node.charset,
        sizeKb
      });
      pillsHtml += `
        <button type="button" class="btn btn-tiny embedded-node-chip" data-node-idx="${i}" title="Edit embedded ${node.format.toUpperCase()} in focused lens">
          🔍 ${escapeHtml(node.path)} <span class="chip-badge">${escapeHtml(chipLabel)}</span>
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
 * State and Controller for the Fullscreen Maximized Viewport & Stacked Lens Layers (#modal-payload-lens).
 */
export const viewportStack = [];

let lensState = {
  stack: [], // Legacy compatibility reference
  onSaveRootCallback: null
};

export function getActiveViewportLayer() {
  return viewportStack.length > 0 ? viewportStack[viewportStack.length - 1] : null;
}

export function pushViewportLayer({
  title = 'Payload Viewport',
  text = '',
  format = null,
  charset = null,
  context = 'stub',
  mode = 'viewer',
  editable = undefined,
  readOnlyReason = 'Read-only payload view',
  descriptor = null,
  onSave = null
}) {
  const cleanFormat = format || detectFormat(text);
  const cleanCharset = charset || detectCharset(text);
  const isEditable = editable !== undefined
    ? Boolean(editable)
    : (Boolean(onSave) && context !== 'journal' && context !== 'tester-response');
  const layer = {
    id: `vp_${Date.now()}_${viewportStack.length}`,
    title,
    initialText: text,
    currentText: text,
    isDirty: false,
    format: cleanFormat,
    charset: cleanCharset,
    context,
    mode: isEditable ? mode : 'viewer',
    editable: isEditable,
    readOnlyReason: readOnlyReason || 'Read-only payload view',
    descriptor,
    onSave
  };
  viewportStack.push(layer);

  // Sync legacy lensState stack for backward compatibility with nested cascade functions
  lensState.stack = viewportStack.map(f => ({
    text: f.currentText,
    descriptor: f.descriptor,
    title: f.title,
    format: f.format
  }));

  renderViewportModal();
  return layer;
}

export function popViewportLayer() {
  if (viewportStack.length === 0) return null;
  const popped = viewportStack.pop();

  lensState.stack = viewportStack.map(f => ({
    text: f.currentText,
    descriptor: f.descriptor,
    title: f.title,
    format: f.format
  }));

  if (viewportStack.length === 0) {
    closeViewportModal();
  } else {
    renderViewportModal();
  }
  return popped;
}

export function setViewportContent(text) {
  const active = getActiveViewportLayer();
  if (!active) return;
  active.currentText = text;
  active.isDirty = active.currentText !== active.initialText;
  updateViewportDirtyIndicator();
}

function updateViewportDirtyIndicator() {
  const active = getActiveViewportLayer();
  const dirtyIndicator = document.getElementById('payload-lens-dirty-indicator');
  if (dirtyIndicator) {
    if (active && active.isDirty) {
      dirtyIndicator.classList.remove('hidden');
      dirtyIndicator.textContent = '● Unsaved Changes';
    } else {
      dirtyIndicator.classList.add('hidden');
    }
  }
}

export function requestCloseViewport() {
  const active = getActiveViewportLayer();
  if (!active) return { closed: true, needsPrompt: false };
  if (active.isDirty) {
    showUnsavedChangesPrompt();
    return { closed: false, needsPrompt: true };
  }
  popViewportLayer();
  return { closed: true, needsPrompt: false };
}

function showUnsavedChangesPrompt() {
  const modal = document.getElementById('modal-unsaved-changes');
  if (modal) modal.classList.remove('hidden');
}

function closeUnsavedChangesPrompt() {
  const modal = document.getElementById('modal-unsaved-changes');
  if (modal) modal.classList.add('hidden');
}

export function saveActiveViewport() {
  const active = getActiveViewportLayer();
  if (!active) return;
  const savedText = active.currentText;

  if (typeof active.onSave === 'function') {
    active.onSave(savedText, active.context);
  }

  // If this was a nested lens layer with a parent layer below it in the stack
  if (viewportStack.length > 1) {
    const parent = viewportStack[viewportStack.length - 2];
    if (parent && active.descriptor) {
      try {
        const updatedParent = applyNestedPayloadEdit(
          parent.currentText,
          active.descriptor,
          savedText,
          { forceInvalid: false, minifyOnSave: false },
          parent.descriptor
        );
        parent.currentText = updatedParent;
        parent.isDirty = parent.currentText !== parent.initialText;
      } catch (_) {}
    }
  }

  closeUnsavedChangesPrompt();
  popViewportLayer();
}

export function discardActiveViewport() {
  const active = getActiveViewportLayer();
  if (!active) return;
  active.currentText = active.initialText;
  active.isDirty = false;
  closeUnsavedChangesPrompt();
  popViewportLayer();
}

function saveActiveViewportInPlace() {
  const active = getActiveViewportLayer();
  if (!active) return;
  const savedText = active.currentText;
  if (typeof active.onSave === 'function') {
    active.onSave(savedText, active.context);
  }
  active.initialText = savedText;
  active.isDirty = false;
  updateViewportDirtyIndicator();

  const toast = document.createElement('div');
  toast.className = 'save-toast';
  toast.textContent = '✓ Saved';
  document.body.appendChild(toast);
  setTimeout(() => toast.remove(), 1500);
}

export function openPayloadLens(rootText, initialNodeDescriptor, onSaveRootCallback, ancestorFrames = null, mode = 'editor', session = null) {
  const isEditable = session ? Boolean(session.editable) : Boolean(onSaveRootCallback);
  const readOnlyReason = session && session.readOnlyReason ? session.readOnlyReason : 'Read-only payload view';
  const context = session && session.context ? session.context : 'nested';

  // If ancestorFrames exist, seed the stack with ancestor frames
  if (viewportStack.length === 0) {
    if (ancestorFrames && ancestorFrames.length > 0) {
      for (const frame of ancestorFrames) {
        pushViewportLayer({
          title: frame.title || 'Root Document',
          text: frame.text,
          format: frame.format,
          context,
          mode: 'viewer',
          editable: isEditable,
          readOnlyReason,
          descriptor: frame.descriptor,
          onSave: onSaveRootCallback
        });
      }
    } else {
      pushViewportLayer({
        title: 'Root Wire Document',
        text: rootText,
        format: detectFormat(rootText),
        context,
        mode: 'viewer',
        editable: isEditable,
        readOnlyReason,
        descriptor: null,
        onSave: onSaveRootCallback
      });
    }
  }

  // Push the target node descriptor layer
  const formattedNodeText = formatPayload(initialNodeDescriptor.unescapedValue);
  pushViewportLayer({
    title: initialNodeDescriptor.path,
    text: formattedNodeText,
    format: initialNodeDescriptor.format,
    charset: initialNodeDescriptor.charset,
    context,
    mode: isEditable ? mode : 'viewer',
    editable: isEditable,
    readOnlyReason,
    descriptor: initialNodeDescriptor,
    onSave: (savedText) => {
      if (typeof onSaveRootCallback === 'function') {
        const finalRoot = cascadeLensStackToRoot(
          viewportStack.map(f => ({
            text: f.currentText,
            descriptor: f.descriptor,
            title: f.title,
            format: f.format
          })),
          savedText,
          { forceInvalid: false }
        );
        onSaveRootCallback(finalRoot);
      }
    }
  });
}

export function closePayloadLens(force = false) {
  const active = getActiveViewportLayer();
  if (!force && active && active.isDirty) {
    showUnsavedChangesPrompt();
    return;
  }
  while (viewportStack.length > 0) {
    viewportStack.pop();
  }
  lensState.stack = [];
  closeViewportModal();
}

function closeViewportModal() {
  const modal = elements.modalPayloadLens || document.getElementById('modal-payload-lens');
  if (modal) {
    modal.classList.add('hidden');
    modal.classList.remove('fullscreen-viewport');
  }
}

function renderViewportModal() {
  const modal = elements.modalPayloadLens || document.getElementById('modal-payload-lens');
  if (!modal || viewportStack.length === 0) {
    if (modal) {
      modal.classList.add('hidden');
      modal.classList.remove('fullscreen-viewport');
    }
    return;
  }

  modal.classList.add('fullscreen-viewport');
  modal.style.zIndex = String(1100 + (viewportStack.length - 1) * 10);
  modal.classList.remove('hidden');

  const currentFrame = getActiveViewportLayer();
  const breadcrumbsEl = document.getElementById('payload-lens-breadcrumbs');
  const editorEl = document.getElementById('payload-lens-editor');
  const titleEl = document.getElementById('payload-lens-title');
  const nestedBarEl = document.getElementById('payload-lens-nested-bar');
  const saveRootBtn = document.getElementById('btn-lens-save-root');
  const cancelBtn = document.getElementById('btn-cancel-payload-lens');
  const viewerContainerEl = document.getElementById('payload-viewport-viewer-container');
  const editorContainerEl = document.getElementById('payload-viewport-editor-container');

  if (titleEl) {
    titleEl.innerHTML = `<span>⛶</span> ${escapeHtml(currentFrame.title)}`;
  }

  // Render Stacked Breadcrumbs
  if (breadcrumbsEl) {
    let bHtml = '';
    for (let i = 0; i < viewportStack.length; i++) {
      const frame = viewportStack[i];
      const isLast = i === viewportStack.length - 1;
      const sizeKb = (new Blob([frame.currentText]).size / 1024).toFixed(1);
      const frameTag = formatPayloadDescriptor({
        format: frame.format,
        encoding: frame.descriptor ? frame.descriptor.encoding : null,
        charset: frame.charset,
        sizeKb
      });
      if (i === 0 && viewportStack.length === 1) {
        bHtml += `
          <span class="breadcrumb-item active" data-stack-idx="0" role="button" tabindex="0">
            <span class="sr-only">${escapeHtml(frame.title)}</span>
            <span class="format-tag">(${escapeHtml(frameTag)})</span>
          </span>
        `;
      } else {
        bHtml += `
          <span class="breadcrumb-item ${isLast ? 'active' : ''}" data-stack-idx="${i}" role="button" tabindex="0">
            ${escapeHtml(frame.title)} <span class="format-tag">(${escapeHtml(frameTag)})</span>
          </span>
          ${!isLast ? '<span class="breadcrumb-sep">▸</span>' : ''}
        `;
      }
    }
    breadcrumbsEl.innerHTML = bHtml;

    breadcrumbsEl.querySelectorAll('.breadcrumb-item:not(.active)').forEach(item => {
      item.addEventListener('click', () => {
        const targetIdx = parseInt(item.getAttribute('data-stack-idx'), 10);
        while (viewportStack.length - 1 > targetIdx) {
          const res = requestCloseViewport();
          if (res.needsPrompt) break;
        }
      });
    });
  }

  // Always populate Editor Textarea so underlying state and DOM access remain synchronized
  if (editorEl) {
    editorEl.value = currentFrame.currentText;
    validateCurrentEditor();
  }

  const isEditable = Boolean(currentFrame.editable);
  const isViewerMode = !isEditable || currentFrame.mode === 'viewer';
  const modeToggleBtn = elements.btnLensViewEditToggle || document.getElementById('btn-lens-view-edit-toggle');

  if (modeToggleBtn) {
    modeToggleBtn.classList.remove('hidden');
    if (!isEditable) {
      modeToggleBtn.innerHTML = '✏️ Edit';
      modeToggleBtn.disabled = true;
      modeToggleBtn.title = currentFrame.readOnlyReason || 'Read-only payload view';
      modeToggleBtn.onclick = null;
    } else {
      modeToggleBtn.disabled = false;
      modeToggleBtn.innerHTML = isViewerMode ? '✏️ Edit' : '👁️ View';
      modeToggleBtn.title = isViewerMode ? 'Switch to Fullscreen Editor' : 'Switch to Foldable Polyglot Viewer';
      modeToggleBtn.onclick = (e) => {
        e.preventDefault();
        currentFrame.mode = isViewerMode ? 'editor' : 'viewer';
        renderViewportModal();
      };
    }
  }

  if (isViewerMode) {
    if (editorContainerEl) editorContainerEl.classList.add('hidden');
    if (viewerContainerEl) {
      viewerContainerEl.classList.remove('hidden');
      renderPayloadViewer(viewerContainerEl, currentFrame.currentText, {
        editable: isEditable,
        readOnlyReason: currentFrame.readOnlyReason,
        context: currentFrame.context,
        onSaveRoot: isEditable ? (updatedFrameText) => {
          setViewportContent(updatedFrameText);
          if (editorEl) editorEl.value = updatedFrameText;
          renderViewportModal();
        } : null
      });
    }
    if (cancelBtn) cancelBtn.textContent = 'Close';
    if (saveRootBtn) {
      if (isEditable && currentFrame.isDirty) {
        saveRootBtn.classList.remove('hidden');
        if (currentFrame.context === 'tester-request') {
          saveRootBtn.textContent = '💾 Save to Request';
        } else if (currentFrame.context === 'stub') {
          saveRootBtn.textContent = '💾 Save Mapping';
        } else {
          saveRootBtn.textContent = viewportStack.length > 1 ? '💾 Save All to Root' : '💾 Save';
        }
      } else {
        saveRootBtn.classList.add('hidden');
      }
    }
  } else {
    if (viewerContainerEl) viewerContainerEl.classList.add('hidden');
    if (editorContainerEl) {
      editorContainerEl.classList.remove('hidden');
      if (editorEl) {
        attachDualLayerEditor(editorEl, elements.payloadLensModeToggle || document.getElementById('payload-lens-mode-toggle'));
        if (editorEl._dualLayer) {
          editorEl._dualLayer.refresh();
        }
      }
    }
    if (cancelBtn) cancelBtn.textContent = 'Cancel';
    if (saveRootBtn) {
      saveRootBtn.classList.remove('hidden');
      if (currentFrame.context === 'tester-request') {
        saveRootBtn.textContent = '💾 Save to Request';
        saveRootBtn.title = 'Apply modifications back to HTTP/Casual Tester request payload';
      } else if (currentFrame.context === 'stub') {
        saveRootBtn.textContent = '💾 Save Mapping';
        saveRootBtn.title = 'Commit modifications to in-memory stub definition';
      } else {
        saveRootBtn.textContent = viewportStack.length > 1 ? '💾 Save All to Root' : '💾 Save';
        saveRootBtn.title = 'Re-escape and apply changes';
      }
    }
  }

  updateViewportDirtyIndicator();

  // Check for even deeper nested payloads inside this active frame in editor mode
  if (nestedBarEl && !isViewerMode) {
    const innerDiscovered = discoverEmbeddedPayloads(currentFrame.currentText);
    if (innerDiscovered.length > 0) {
      nestedBarEl.classList.remove('hidden');
      let innerHtml = '<div class="embedded-payloads-bar"><span class="bar-title">✨ Deep Nested:</span>';
      for (let j = 0; j < innerDiscovered.length; j++) {
        const innerNode = innerDiscovered[j];
        const innerTag = formatPayloadDescriptor({
          format: innerNode.format,
          encoding: innerNode.encoding,
          charset: innerNode.charset
        });
        innerHtml += `
          <button type="button" class="btn btn-tiny inner-drill-chip" data-inner-idx="${j}">
            🔍 ${escapeHtml(innerNode.path)} (${escapeHtml(innerTag)})
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
            pushViewportLayer({
              title: nextNode.path,
              text: formatPayload(nextNode.unescapedValue),
              format: nextNode.format,
              charset: nextNode.charset,
              context: 'nested',
              descriptor: nextNode,
              onSave: currentFrame.onSave
            });
          }
        });
      });
    } else {
      nestedBarEl.classList.add('hidden');
      nestedBarEl.innerHTML = '';
    }
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

  // Unsaved Changes Confirmation Modal Elements
  const unsavedModal = document.getElementById('modal-unsaved-changes');
  const btnUnsavedSave = document.getElementById('btn-unsaved-save');
  const btnUnsavedDiscard = document.getElementById('btn-unsaved-discard');
  const btnUnsavedCancel = document.getElementById('btn-unsaved-cancel');
  const btnCloseUnsaved = document.getElementById('btn-close-unsaved-modal');

  if (btnUnsavedSave) btnUnsavedSave.addEventListener('click', saveActiveViewport);
  if (btnUnsavedDiscard) btnUnsavedDiscard.addEventListener('click', discardActiveViewport);
  if (btnUnsavedCancel) btnUnsavedCancel.addEventListener('click', closeUnsavedChangesPrompt);
  if (btnCloseUnsaved) btnCloseUnsaved.addEventListener('click', closeUnsavedChangesPrompt);

  if (editorEl) {
    editorEl.addEventListener('input', () => {
      setViewportContent(editorEl.value);
      validateCurrentEditor();
    });
  }

  if (closeBtn) closeBtn.addEventListener('click', closePayloadLens);
  if (cancelBtn) cancelBtn.addEventListener('click', requestCloseViewport);

  // Global Keyboard Navigation (Escape to pop, Ctrl+S to save in place)
  if (typeof document !== 'undefined') {
    document.addEventListener('keydown', (e) => {
      if (unsavedModal && !unsavedModal.classList.contains('hidden')) {
        if (e.key === 'Escape') {
          e.preventDefault();
          closeUnsavedChangesPrompt();
        } else if (e.key === 's' || e.key === 'S') {
          e.preventDefault();
          saveActiveViewport();
        } else if (e.key === 'd' || e.key === 'D') {
          e.preventDefault();
          discardActiveViewport();
        }
        return;
      }

      if (viewportStack.length > 0) {
        if ((e.ctrlKey || e.metaKey) && (e.key === 's' || e.key === 'S')) {
          e.preventDefault();
          saveActiveViewportInPlace();
        } else if (e.key === 'Escape') {
          e.preventDefault();
          requestCloseViewport();
        }
      }
    });
  }

  if (prettifyBtn && editorEl) {
    prettifyBtn.addEventListener('click', () => {
      editorEl.value = formatPayload(editorEl.value);
      setViewportContent(editorEl.value);
      validateCurrentEditor();
    });
  }

  if (minifyBtn && editorEl) {
    minifyBtn.addEventListener('click', () => {
      const currentFrame = getActiveViewportLayer();
      if (currentFrame && currentFrame.format === 'json') {
        try {
          editorEl.value = JSON.stringify(JSON.parse(editorEl.value));
        } catch (_) {}
      } else if (currentFrame && currentFrame.format === 'xml') {
        editorEl.value = minifyXml(editorEl.value);
      }
      setViewportContent(editorEl.value);
      validateCurrentEditor();
    });
  }

  if (copyRawBtn && editorEl) {
    copyRawBtn.addEventListener('click', () => {
      const currentFrame = getActiveViewportLayer();
      const raw = currentFrame && currentFrame.descriptor ? currentFrame.descriptor.rawValue : editorEl.value;
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
    if (!editorEl || viewportStack.length === 0) return;
    if (!force && !validateCurrentEditor()) return;

    const active = getActiveViewportLayer();
    if (active) {
      if (typeof active.onSave === 'function') {
        active.onSave(editorEl.value);
      }
      active.isDirty = false;
    }
    closePayloadLens(true);
  }

  if (saveRootBtn) {
    saveRootBtn.addEventListener('click', () => handleSave(false));
  }

  if (forceApplyBtn) {
    forceApplyBtn.addEventListener('click', () => handleSave(true));
  }

  if (applyParentBtn) {
    applyParentBtn.addEventListener('click', () => {
      if (!editorEl || viewportStack.length < 2) return;
      if (!validateCurrentEditor()) return;

      const minifyOnSave = minifyOnSaveChk ? minifyOnSaveChk.checked : false;
      const childFrame = viewportStack.pop();
      const parentFrame = getActiveViewportLayer();
      if (parentFrame && childFrame && childFrame.descriptor) {
        const updatedParent = applyNestedPayloadEdit(
          parentFrame.currentText,
          childFrame.descriptor,
          editorEl.value,
          { forceInvalid: false, minifyOnSave },
          parentFrame.descriptor
        );
        parentFrame.currentText = updatedParent;
        parentFrame.isDirty = true;
      }
      renderViewportModal();
    });
  }
}
