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
export function normalizeCharsetName(charset) {
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
export function encodeStringToBytes(str, charset = 'UTF-8') {
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
export function decodeBase64WithCharset(str) {
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

export function isPrintableText(str) {
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
          wasCompact: !val.includes('\n'),
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
            wasCompact: !val.includes('\n'),
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
        wasCompact: !val.includes('\n'),
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
        wasCompact: !decodedText.includes('\n'),
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
          charset: detectCharset(unescaped),
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
  if (nodeDescriptor.encoding === 'BASE64_UTF8' || nodeDescriptor.encoding === 'BASE64') {
    const editedPrologCharset = detectCharset(processedText);
    const targetCharset = editedPrologCharset !== 'UTF-8' ? editedPrologCharset : (nodeDescriptor.charset || 'UTF-8');
    const encoded = encodeToBase64(processedText, targetCharset);
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
    const parentText = parentFrame.text !== undefined ? parentFrame.text : parentFrame.payload;
    const descriptor = childFrame.descriptor || childFrame.targetNode;
    currentText = applyNestedPayloadEdit(
      parentText,
      descriptor,
      currentText,
      options
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

  // Create state container if not already initialized
  const viewerId = 'viewer_' + Math.random().toString(36).substring(2, 9);
  let isUnescaped = options.defaultUnescaped !== false && (hasEmbedded || format !== 'text');

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
      codeEl.innerHTML = highlightPayload(trimmed);
      return;
    }

    if (!hasEmbedded) {
      codeEl.innerHTML = highlightPayload(trimmed);
      return;
    }

    // Render formatted root with interactive Truncated Payload Previews
    codeEl.innerHTML = renderFormattedWithNestedPreviews(trimmed, discovered, viewerId, options.onSaveRoot);
    attachViewerEventListeners(boxEl, trimmed, discovered, options.onSaveRoot);
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
const nodeRegistry = new Map();

export function registerPayloadViewer(viewerId, rawText, discovered, onSaveRoot = null) {
  viewerRegistry.set(viewerId, { rawText, discovered, onSaveRoot });
}

export function renderFormattedWithNestedPreviews(rawText, discovered, viewerId, onSaveRoot = null, ancestorFrames = null, rootText = null) {
  const actualRootText = rootText !== null ? rootText : rawText;
  const currentAncestorFrames = ancestorFrames !== null ? ancestorFrames : [
    { text: rawText, descriptor: null, title: 'Root Wire Document', format: detectFormat(rawText) }
  ];

  registerPayloadViewer(viewerId, rawText, discovered, onSaveRoot);
  let formatted = formatPayload(rawText);
  const replacements = [];

  // Replace escaped strings with Auto-Unnested Previews (recursive descent)
  for (let i = 0; i < discovered.length; i++) {
    const node = discovered[i];
    const fullNodeId = `${viewerId}_n${i}`;
    nodeRegistry.set(fullNodeId, {
      node,
      rootText: actualRootText,
      ancestorFrames: currentAncestorFrames,
      onSaveRoot
    });

    // Recursively render child payloads if discovered has children
    let innerHtml = '';
    if (node.children && node.children.length > 0) {
      const childAncestorFrames = [
        ...currentAncestorFrames,
        { text: node.unescapedValue, descriptor: node, title: node.path, format: node.format }
      ];
      innerHtml = renderFormattedWithNestedPreviews(
        node.unescapedValue,
        node.children,
        `${fullNodeId}_c`,
        onSaveRoot,
        childAncestorFrames,
        actualRootText
      );
    } else {
      innerHtml = highlightPayload(node.unescapedValue);
    }

    const previewSnippet = node.unescapedValue.substring(0, 60).replace(/\s+/g, ' ');
    const sizeKb = (node.byteLength / 1024).toFixed(1);
    const pillLabel = formatPayloadDescriptor({
      format: node.format,
      encoding: node.encoding,
      charset: node.charset,
      sizeKb
    });
    const canEdit = typeof onSaveRoot === 'function';
    const editBtnHtml = canEdit
      ? `<button type="button" class="btn-payload-maximize btn-nested-edit" data-node-id="${fullNodeId}" data-node-idx="${i}" title="Edit unescaped payload in-place">✏️ Edit</button>`
      : '';
    const previewHtml = `<span class="truncated-payload-preview auto-expanded" data-node-id="${fullNodeId}" data-node-idx="${i}" data-viewer-id="${viewerId}" tabindex="0"><span class="preview-snippet">"${escapeHtml(previewSnippet)}..."</span><span class="preview-pill-group"><span class="preview-pill-badge btn-lens-expand-pill" data-node-id="${fullNodeId}" data-node-idx="${i}" role="button" tabindex="0" title="Click to collapse">${escapeHtml(pillLabel)}</span>${editBtnHtml}<button type="button" class="btn-payload-maximize btn-nested-maximize btn-lens-open-pill" data-node-id="${fullNodeId}" data-node-idx="${i}" title="Maximize in Fullscreen Viewport">⛶ Maximize</button></span><div class="inline-expanded-payload" id="${viewerId}_expanded_${i}"><pre class="inline-code"><code>${innerHtml}</code></pre></div></span>`;

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
        openPayloadLens(entry.rootText, entry.node, entry.onSaveRoot, entry.ancestorFrames, 'viewer');
      } else {
        const parentPreview = openBtn.closest('.truncated-payload-preview');
        if (parentPreview) {
          const viewerId = parentPreview.getAttribute('data-viewer-id');
          const nodeIdx = parseInt(openBtn.getAttribute('data-node-idx'), 10);
          const stored = viewerRegistry.get(viewerId);
          if (stored && stored.discovered && stored.discovered[nodeIdx]) {
            openPayloadLens(stored.rawText, stored.discovered[nodeIdx], stored.onSaveRoot, null, 'viewer');
          }
        }
      }
      return;
    }

    const editBtn = e.target.closest('.btn-nested-edit');
    if (editBtn) {
      e.stopPropagation();
      activateInlineBlockEditor(editBtn);
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

  document.addEventListener('dblclick', (e) => {
    const inlinePayload = e.target.closest('.inline-expanded-payload');
    if (inlinePayload && !inlinePayload.querySelector('.inline-block-editor')) {
      const parentPreview = inlinePayload.closest('.truncated-payload-preview');
      if (parentPreview) {
        const editBtn = parentPreview.querySelector('.btn-nested-edit');
        if (editBtn) {
          e.stopPropagation();
          activateInlineBlockEditor(editBtn);
        }
      }
    }
  });
}

/**
 * Activates an in-place format-aware Dual-Layer code editor for an embedded payload subsection.
 * Preserves the enclosing tree structure while allowing targeted unescaped editing.
 */
export function activateInlineBlockEditor(triggerEl) {
  const parentPreview = triggerEl.closest('.truncated-payload-preview');
  if (!parentPreview) return;
  const nodeId = triggerEl.getAttribute('data-node-id') || parentPreview.getAttribute('data-node-id');
  let entry = nodeRegistry.get(nodeId);
  if (!entry) {
    const viewerId = parentPreview.getAttribute('data-viewer-id');
    const nodeIdx = parseInt(triggerEl.getAttribute('data-node-idx'), 10);
    const stored = viewerRegistry.get(viewerId);
    if (stored && stored.discovered && stored.discovered[nodeIdx]) {
      entry = {
        node: stored.discovered[nodeIdx],
        rootText: stored.rawText,
        ancestorFrames: [
          { text: stored.rawText, descriptor: null, title: 'Root Wire Document', format: detectFormat(stored.rawText) }
        ],
        onSaveRoot: stored.onSaveRoot
      };
    }
  }
  if (!entry) return;

  const expandedDiv = parentPreview.querySelector(':scope > .inline-expanded-payload') || parentPreview.querySelector('.inline-expanded-payload');
  if (!expandedDiv) return;

  const existingTextarea = expandedDiv.querySelector('.inline-nested-textarea');
  if (existingTextarea) {
    existingTextarea.focus();
    return;
  }

  expandedDiv.classList.remove('hidden');
  parentPreview.classList.add('auto-expanded');

  const originalHtml = expandedDiv.innerHTML;
  const initialText = formatPayload(entry.node.unescapedValue);
  const lines = initialText.split('\n').length;
  const heightPx = Math.min(420, Math.max(140, lines * 21 + 36));
  const formatUpper = (entry.node.format || detectFormat(initialText)).toUpperCase();

  expandedDiv.innerHTML = `
    <div class="inline-block-editor" data-node-id="${escapeHtml(nodeId)}">
      <div class="inline-block-editor-toolbar">
        <div class="inline-toolbar-left">
          <span class="inline-edit-tag">✏️ ${escapeHtml(formatUpper)} Block</span>
          <span class="inline-edit-dirty-indicator hidden">● Unsaved Changes</span>
          <span class="inline-edit-lint-pill hidden"></span>
        </div>
        <div class="inline-toolbar-right">
          <button type="button" class="btn btn-tiny btn-secondary btn-inline-format" title="Format/Prettify block">✨ Format</button>
          <button type="button" class="btn btn-tiny btn-primary btn-inline-done" title="Apply changes (Ctrl+Enter / Ctrl+S)">✓ Done</button>
          <button type="button" class="btn btn-tiny btn-secondary btn-inline-cancel" title="Cancel changes (Esc)">Cancel</button>
        </div>
      </div>
      <div class="dual-layer-editor-wrapper mode-rich" style="height: ${heightPx}px;">
        <pre class="dual-layer-backdrop" aria-hidden="true"><code class="dual-layer-code">${highlightPayload(initialText)}</code></pre>
        <textarea class="dual-layer-input inline-nested-textarea" spellcheck="false">${escapeHtml(initialText)}</textarea>
      </div>
    </div>
  `;

  const editorContainer = expandedDiv.querySelector('.inline-block-editor');
  const textarea = editorContainer.querySelector('.inline-nested-textarea');
  const backdrop = editorContainer.querySelector('.dual-layer-backdrop');
  const code = backdrop.querySelector('code');
  const dirtyIndicator = editorContainer.querySelector('.inline-edit-dirty-indicator');
  const lintPill = editorContainer.querySelector('.inline-edit-lint-pill');
  const btnFormat = editorContainer.querySelector('.btn-inline-format');
  const btnDone = editorContainer.querySelector('.btn-inline-done');
  const btnCancel = editorContainer.querySelector('.btn-inline-cancel');

  let isDirty = false;

  const syncScroll = () => {
    if (backdrop) {
      backdrop.scrollTop = textarea.scrollTop;
      backdrop.scrollLeft = textarea.scrollLeft;
    }
  };

  const validate = () => {
    const val = textarea.value;
    const fmt = entry.node.format || detectFormat(val);
    const res = validatePayload(val, fmt);
    if (!res.valid) {
      lintPill.textContent = `⚠️ Invalid ${fmt.toUpperCase()}: ${res.errorMessage}`;
      lintPill.classList.remove('hidden');
    } else {
      lintPill.classList.add('hidden');
      lintPill.textContent = '';
    }
    return res.valid;
  };

  const updateBackdrop = () => {
    const val = textarea.value;
    code.innerHTML = highlightPayload(val) + (val.endsWith('\n') ? ' ' : '');
    syncScroll();
  };

  textarea.addEventListener('scroll', syncScroll);
  textarea.addEventListener('input', () => {
    if (!isDirty) {
      isDirty = true;
      dirtyIndicator.classList.remove('hidden');
    }
    updateBackdrop();
    validate();
  });

  btnFormat.addEventListener('click', (e) => {
    e.stopPropagation();
    textarea.value = formatPayload(textarea.value);
    updateBackdrop();
    validate();
  });

  const finish = (save) => {
    if (!save) {
      expandedDiv.innerHTML = originalHtml;
      return;
    }

    if (!validate()) {
      textarea.focus();
      return;
    }

    const editedVal = textarea.value;
    entry.node.unescapedValue = editedVal;

    const stack = [
      ...entry.ancestorFrames,
      { text: editedVal, descriptor: entry.node, title: entry.node.path, format: entry.node.format }
    ];

    const newRoot = cascadeLensStackToRoot(stack, editedVal, { forceInvalid: false, minifyOnSave: false });
    expandedDiv.innerHTML = `<pre class="inline-code"><code>${highlightPayload(editedVal)}</code></pre>`;
    if (typeof entry.onSaveRoot === 'function') {
      entry.onSaveRoot(newRoot);
    }
  };

  btnDone.addEventListener('click', (e) => {
    e.stopPropagation();
    finish(true);
  });

  btnCancel.addEventListener('click', (e) => {
    e.stopPropagation();
    finish(false);
  });

  textarea.addEventListener('keydown', (e) => {
    if ((e.ctrlKey || e.metaKey) && (e.key === 'Enter' || e.key === 's')) {
      e.preventDefault();
      finish(true);
    } else if (e.key === 'Escape') {
      e.preventDefault();
      finish(false);
    }
  });

  textarea.focus();
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

  let currentMode = 'rich'; // in-memory experience mode: 'rich' or 'raw'
  let toggleGroup = null;

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
      return;
    } else {
      wrapper.classList.remove('mode-degraded');
      if (currentMode === 'rich') {
        wrapper.classList.remove('mode-raw');
        wrapper.classList.add('mode-rich');
      } else {
        wrapper.classList.remove('mode-rich');
        wrapper.classList.add('mode-raw');
      }
      updateToggleButtons(currentMode, false);
    }
    const html = highlightPayload(val) + (val.endsWith('\n') ? ' ' : '');
    code.innerHTML = html;
    syncScroll();
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
  textareaEl.addEventListener('input', updateBackdrop);

  const instance = {
    wrapper,
    backdrop,
    code,
    textarea: textareaEl,
    setMode,
    getMode: () => currentMode,
    refresh: updateBackdrop,
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
 *
 * @param {HTMLElement} containerEl
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
  defaultMode = 'rich',
  defaultAction = 'view',
  backingTextarea = null,
  backingPre = null,
  onSave = null,
  onChange = null,
  onMaximize = null
} = {}) {
  if (!containerEl) return null;

  let currentText = backingTextarea ? (backingTextarea.value || '') : (text || '');
  let currentMode = defaultMode; // 'rich' | 'raw'
  let currentAction = readOnly ? 'view' : defaultAction; // 'view' | 'edit'
  const componentId = 'upc_' + Math.random().toString(36).substring(2, 9);

  const cleanFormat = format || detectFormat(currentText);
  const cleanCharset = charset || detectCharset(currentText);

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
          ${!readOnly ? `
          <div class="action-mode-toggle">
            <button type="button" class="btn btn-tiny ${currentAction === 'view' ? 'active' : ''} btn-action-view" data-action="view" title="Read-only view">👁️ View</button>
            <button type="button" class="btn btn-tiny ${currentAction === 'edit' ? 'active' : ''} btn-action-edit" data-action="edit" title="Editable mode">✏️ Edit</button>
          </div>
          ` : ''}
          ${!readOnly ? `<button type="button" class="btn btn-tiny btn-format-payload ${currentAction === 'edit' ? '' : 'hidden'}" title="Format/re-indent JSON/XML">✨ Format</button>` : ''}
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

  const rootEl = containerEl.querySelector(`#${componentId}`);
  const viewContainer = rootEl.querySelector('.payload-view-container');
  const editContainer = rootEl.querySelector('.payload-edit-container');
  const textareaEl = rootEl.querySelector('.payload-component-textarea');
  const descPill = rootEl.querySelector('.payload-desc-pill');
  const lintPill = rootEl.querySelector('.payload-lint-pill');
  const btnRich = rootEl.querySelector('.btn-mode-rich');
  const btnRaw = rootEl.querySelector('.btn-mode-raw');
  const btnActionView = rootEl.querySelector('.btn-action-view');
  const btnActionEdit = rootEl.querySelector('.btn-action-edit');
  const btnFormat = rootEl.querySelector('.btn-format-payload');
  const btnCopy = rootEl.querySelector('.btn-copy-payload');
  const btnMax = rootEl.querySelector('.btn-maximize-payload');

  let dualLayerInstance = null;
  if (!readOnly && textareaEl) {
    dualLayerInstance = attachDualLayerEditor(textareaEl);
  }

  function updateDescriptor() {
    if (!descPill) return;
    const sizeKb = (new Blob([currentText]).size / 1024).toFixed(1);
    const fmt = (cleanFormat || detectFormat(currentText)).toUpperCase();
    descPill.textContent = formatPayloadDescriptor({
      format: fmt,
      charset: cleanCharset,
      sizeKb
    });
  }

  function renderView() {
    if (!viewContainer) return;
    const discovered = discoverEmbeddedPayloads(currentText);
    if (currentMode === 'rich') {
      if (discovered.length > 0) {
        viewContainer.innerHTML = `<pre class="syntax-view"><code>${renderFormattedWithNestedPreviews(currentText, discovered, `${componentId}_v`, (newRoot) => {
          syncText(newRoot);
        })}</code></pre>`;
      } else {
        viewContainer.innerHTML = `<pre class="syntax-view"><code>${highlightPayload(formatPayload(currentText))}</code></pre>`;
      }
    } else {
      // Raw view: wire-exact escaped string with syntax highlighting
      viewContainer.innerHTML = `<pre class="syntax-view"><code>${highlightPayload(currentText)}</code></pre>`;
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
      const discovered = discoverEmbeddedPayloads(newText);
      if (currentMode === 'rich' && discovered.length > 0) {
        backingPre.innerHTML = renderFormattedWithNestedPreviews(newText, discovered, `${componentId}_bp`);
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
    if (!lintPill) return;
    const fmt = detectFormat(currentText);
    if (fmt === 'json' || fmt === 'xml') {
      const res = validatePayload(currentText, fmt);
      if (!res.valid) {
        lintPill.classList.remove('hidden');
        lintPill.textContent = `⚠️ Invalid ${fmt.toUpperCase()}: ${res.errorMessage}`;
        return;
      }
    }
    lintPill.classList.add('hidden');
    lintPill.textContent = '';
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

  function setAction(action) {
    if (readOnly) return;
    currentAction = action === 'edit' ? 'edit' : 'view';
    if (btnActionView && btnActionEdit) {
      btnActionView.classList.toggle('active', currentAction === 'view');
      btnActionEdit.classList.toggle('active', currentAction === 'edit');
    }
    if (btnFormat) {
      btnFormat.classList.toggle('hidden', currentAction !== 'edit');
    }
    if (currentAction === 'view') {
      editContainer.classList.add('hidden');
      editContainer.style.display = 'none';
      viewContainer.classList.remove('hidden');
      renderView();
    } else {
      viewContainer.classList.add('hidden');
      editContainer.classList.remove('hidden');
      editContainer.style.display = 'flex';
      if (textareaEl) {
        textareaEl.value = currentText;
        if (dualLayerInstance) dualLayerInstance.refresh();
      }
    }
  }

  // Bind Listeners
  if (btnRich) btnRich.onclick = () => setMode('rich');
  if (btnRaw) btnRaw.onclick = () => setMode('raw');
  if (btnActionView) btnActionView.onclick = () => setAction('view');
  if (btnActionEdit) btnActionEdit.onclick = () => setAction('edit');

  if (btnFormat) {
    btnFormat.onclick = () => {
      const formatted = formatPayload(currentText);
      syncText(formatted);
    };
  }

  if (btnCopy) {
    btnCopy.onclick = () => {
      const copyVal = currentMode === 'rich' ? formatPayload(currentText) : currentText;
      navigator.clipboard.writeText(copyVal).then(() => {
        btnCopy.textContent = '✓ Copied';
        setTimeout(() => { btnCopy.textContent = '📋 Copy'; }, 1500);
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
          mode: currentAction === 'edit' ? 'editor' : 'viewer',
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

  if (descPill) {
    descPill.onclick = () => {
      const bodyEl = rootEl.querySelector('.unified-payload-body');
      if (bodyEl) {
        bodyEl.classList.toggle('hidden');
      }
    };
  }

  if (textareaEl) {
    textareaEl.value = currentText;
    textareaEl.oninput = () => {
      syncText(textareaEl.value, true);
    };
  }

  // Initial render
  updateDescriptor();
  if (currentAction === 'view') {
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
    refresh: () => {
      updateDescriptor();
      if (currentAction === 'view') renderView();
      else if (dualLayerInstance) dualLayerInstance.refresh();
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
  descriptor = null,
  onSave = null
}) {
  const cleanFormat = format || detectFormat(text);
  const cleanCharset = charset || detectCharset(text);
  const layer = {
    id: `vp_${Date.now()}_${viewportStack.length}`,
    title,
    initialText: text,
    currentText: text,
    isDirty: false,
    format: cleanFormat,
    charset: cleanCharset,
    context,
    mode,
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

export function updateViewportDirtyIndicator() {
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

export function showUnsavedChangesPrompt() {
  const modal = document.getElementById('modal-unsaved-changes');
  if (modal) modal.classList.remove('hidden');
}

export function closeUnsavedChangesPrompt() {
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
          { forceInvalid: false, minifyOnSave: false }
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

export function saveActiveViewportInPlace() {
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

export function openPayloadLens(rootText, initialNodeDescriptor, onSaveRootCallback, ancestorFrames = null, mode = 'editor') {
  // If ancestorFrames exist, seed the stack with ancestor frames
  if (viewportStack.length === 0) {
    if (ancestorFrames && ancestorFrames.length > 0) {
      for (const frame of ancestorFrames) {
        pushViewportLayer({
          title: frame.title || 'Root Document',
          text: frame.text,
          format: frame.format,
          context: 'nested',
          mode: 'viewer',
          descriptor: frame.descriptor,
          onSave: onSaveRootCallback
        });
      }
    } else {
      pushViewportLayer({
        title: 'Root Wire Document',
        text: rootText,
        format: detectFormat(rootText),
        context: 'nested',
        mode: 'viewer',
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
    context: 'nested',
    mode,
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
          { forceInvalid: false, minifyOnSave: false }
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

export function renderViewportModal() {
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
      bHtml += `
        <span class="breadcrumb-item ${isLast ? 'active' : ''}" data-stack-idx="${i}" role="button" tabindex="0">
          ${escapeHtml(frame.title)} <span class="format-tag">(${escapeHtml(frameTag)})</span>
        </span>
        ${!isLast ? '<span class="breadcrumb-sep">▸</span>' : ''}
      `;
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

  const isViewerMode = currentFrame.mode === 'viewer';
  const modeToggleBtn = elements.btnLensViewEditToggle || document.getElementById('btn-lens-view-edit-toggle');

  if (modeToggleBtn) {
    if (currentFrame.context === 'journal') {
      modeToggleBtn.classList.add('hidden');
    } else {
      modeToggleBtn.classList.remove('hidden');
      modeToggleBtn.innerHTML = isViewerMode ? '✏️ Edit' : '👁️ View';
      modeToggleBtn.title = isViewerMode ? 'Switch to Fullscreen Editor' : 'Switch to Syntax Highlighted Viewer';
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
        onSaveRoot: currentFrame.onSave
      });
    }
    if (cancelBtn) cancelBtn.textContent = 'Close';
    if (saveRootBtn) {
      if (currentFrame.context === 'journal' || currentFrame.context === 'tester-response') {
        saveRootBtn.textContent = '📸 Snapshot as Stub';
        saveRootBtn.title = 'Snapshot this payload as an active WireMock mock stub';
        saveRootBtn.classList.remove('hidden');
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
        const updatedParent = applyNestedPayloadEdit(parentFrame.currentText, childFrame.descriptor, editorEl.value, {
          forceInvalid: false,
          minifyOnSave
        });
        parentFrame.currentText = updatedParent;
        parentFrame.isDirty = true;
      }
      renderViewportModal();
    });
  }
}
