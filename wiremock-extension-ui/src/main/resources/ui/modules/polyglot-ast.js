/**
 * Zero-DOM Polyglot AST scanner, span-exact splice editor, and segment builder.
 * Discovers nested JSON, XML, KV, and Base64 payloads with exact character spans and
 * preserves duplicate siblings, nested same-named keys, and surrounding wire whitespace.
 */
import {
  normalizeCharsetName,
  detectCharset,
  encodeToBase64,
  decodeBase64WithCharset
} from './charset-codec.js';
import { parsePseudoJson } from './pseudo-json.js';
import {
  detectFormat,
  formatPayload,
  formatXml,
  minifyXml,
  validatePayload
} from './highlighter.js';

/**
 * Formats a concise payload descriptor badge combining non-trivial container encoding,
 * syntax format, non-UTF-8 character encoding, and byte size.
 *
 * @param {object} [opts]
 * @param {string} [opts.format='TEXT']
 * @param {string|null} [opts.encoding=null]
 * @param {string} [opts.charset='UTF-8']
 * @param {string|number|null} [opts.sizeKb=null]
 * @returns {string}
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
 * Formats a composite Casual Buffer Descriptor badge string combining transport buffer type,
 * document syntax format, optional non-UTF-8 charset, and byte size.
 *
 * @param {string} bufferType
 * @param {string} syntaxFormat
 * @param {string|number} [sizeKb]
 * @param {string} [charset='UTF-8']
 * @returns {string}
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
 * Escapes XML special characters for text entity content.
 *
 * @param {string} str
 * @returns {string}
 */
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
 * Unescapes XML entity references back to raw characters.
 *
 * @param {string} str
 * @returns {string}
 */
export function unescapeXmlEntities(str) {
  if (!str || typeof str !== 'string') return '';
  return str
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#39;/g, "'")
    .replace(/&amp;/g, '&');
}

/**
 * Walks JSON tokens tracking JSONPath and exact character offsets for every string value literal.
 * `tokenStart` and `tokenEnd` include the surrounding double quotes; `valueStart`/`valueEnd` exclude them.
 *
 * @param {string} jsonText
 * @returns {Array<{ tokenStart: number, tokenEnd: number, valueStart: number, valueEnd: number, rawValue: string, path: string, key: string }>}
 */
export function scanJsonStringLiterals(jsonText) {
  if (typeof jsonText !== 'string' || !jsonText.trim()) return [];
  const literals = [];
  const len = jsonText.length;
  let pos = 0;

  function skipWs() {
    while (pos < len) {
      const c = jsonText[pos];
      if (c === ' ' || c === '\t' || c === '\n' || c === '\r') pos++;
      else break;
    }
  }

  function readStringToken() {
    if (jsonText[pos] !== '"') {
      throw new Error('Expected string');
    }
    const tokenStart = pos;
    pos++;
    while (pos < len) {
      const c = jsonText[pos];
      if (c === '\\') {
        if (pos + 1 >= len) throw new Error('Unterminated escape');
        pos += 2;
      } else if (c === '"') {
        pos++;
        const tokenEnd = pos;
        const rawValue = JSON.parse(jsonText.slice(tokenStart, tokenEnd));
        return {
          tokenStart,
          tokenEnd,
          valueStart: tokenStart + 1,
          valueEnd: tokenEnd - 1,
          rawValue
        };
      } else {
        pos++;
      }
    }
    throw new Error('Unterminated string');
  }

  function readLiteralOrNumber() {
    if (jsonText.startsWith('true', pos)) { pos += 4; return; }
    if (jsonText.startsWith('false', pos)) { pos += 5; return; }
    if (jsonText.startsWith('null', pos)) { pos += 4; return; }
    const start = pos;
    if (jsonText[pos] === '-') pos++;
    if (pos >= len || jsonText[pos] < '0' || jsonText[pos] > '9') {
      throw new Error(`Invalid JSON value at ${start}`);
    }
    while (pos < len && jsonText[pos] >= '0' && jsonText[pos] <= '9') pos++;
    if (pos < len && jsonText[pos] === '.') {
      pos++;
      if (pos >= len || jsonText[pos] < '0' || jsonText[pos] > '9') {
        throw new Error(`Invalid decimal at ${start}`);
      }
      while (pos < len && jsonText[pos] >= '0' && jsonText[pos] <= '9') pos++;
    }
    if (pos < len && (jsonText[pos] === 'e' || jsonText[pos] === 'E')) {
      pos++;
      if (pos < len && (jsonText[pos] === '+' || jsonText[pos] === '-')) pos++;
      if (pos >= len || jsonText[pos] < '0' || jsonText[pos] > '9') {
        throw new Error(`Invalid exponent at ${start}`);
      }
      while (pos < len && jsonText[pos] >= '0' && jsonText[pos] <= '9') pos++;
    }
  }

  function formatMemberPath(parentPath, key) {
    if (/^[a-zA-Z0-9_$-]+$/.test(key)) {
      return `${parentPath}.${key}`;
    }
    return `${parentPath}['${key.replace(/\\/g, '\\\\').replace(/'/g, "\\'")}']`;
  }

  function readValue(currentPath, currentKey) {
    skipWs();
    if (pos >= len) throw new Error('Unexpected EOF');
    const c = jsonText[pos];
    if (c === '"') {
      const tok = readStringToken();
      if (currentPath !== '$') {
        literals.push({
          ...tok,
          path: currentPath,
          key: currentKey
        });
      }
      return;
    }
    if (c === '{') {
      pos++;
      skipWs();
      if (pos < len && jsonText[pos] === '}') {
        pos++;
        return;
      }
      while (pos < len) {
        skipWs();
        const keyTok = readStringToken();
        const key = keyTok.rawValue;
        skipWs();
        if (pos >= len || jsonText[pos] !== ':') {
          throw new Error('Expected :');
        }
        pos++;
        readValue(formatMemberPath(currentPath, key), key);
        skipWs();
        if (pos < len && jsonText[pos] === ',') {
          pos++;
          continue;
        }
        if (pos < len && jsonText[pos] === '}') {
          pos++;
          return;
        }
        throw new Error('Expected , or }');
      }
      throw new Error('Unterminated object');
    }
    if (c === '[') {
      pos++;
      skipWs();
      if (pos < len && jsonText[pos] === ']') {
        pos++;
        return;
      }
      let idx = 0;
      while (pos < len) {
        readValue(`${currentPath}[${idx}]`, String(idx));
        idx++;
        skipWs();
        if (pos < len && jsonText[pos] === ',') {
          pos++;
          continue;
        }
        if (pos < len && jsonText[pos] === ']') {
          pos++;
          return;
        }
        throw new Error('Expected , or ]');
      }
      throw new Error('Unterminated array');
    }
    readLiteralOrNumber();
  }

  try {
    readValue('$', '');
    skipWs();
    if (pos !== len) return [];
    return literals;
  } catch (_) {
    return [];
  }
}

function findTagEndOffset(xmlText, startIdx) {
  let quote = null;
  for (let i = startIdx; i < xmlText.length; i++) {
    const ch = xmlText[i];
    if (quote) {
      if (ch === quote) quote = null;
    } else if (ch === '"' || ch === "'") {
      quote = ch;
    } else if (ch === '>') {
      return i;
    } else if (ch === '<') {
      return -1;
    }
  }
  return -1;
}

/**
 * Scans XML text for matching `<tag><![CDATA[...]]></tag>` and `<tag>...&lt;...&gt;...</tag>`
 * embedded payload regions, tracking exact character spans.
 *
 * @param {string} xmlText
 * @param {string} [basePath='$']
 * @returns {Array<{ tagName: string, path: string, key: string, encoding: 'XML_CDATA'|'XML_ENTITY_TEXT', span: { start: number, end: number }, rawValue: string, unescapedValue: string }>}
 */
export function scanXmlEmbeddedRegions(xmlText, basePath = '$') {
  if (typeof xmlText !== 'string' || !xmlText.trim()) return [];
  const regions = [];
  const len = xmlText.length;
  let i = 0;

  while (i < len) {
    const lt = xmlText.indexOf('<', i);
    if (lt < 0) break;

    if (xmlText.startsWith('<!--', lt)) {
      const end = xmlText.indexOf('-->', lt + 4);
      i = end >= 0 ? end + 3 : len;
      continue;
    }
    if (xmlText.startsWith('<?', lt)) {
      const end = xmlText.indexOf('?>', lt + 2);
      i = end >= 0 ? end + 2 : len;
      continue;
    }
    if (xmlText.startsWith('<![CDATA[', lt)) {
      const end = xmlText.indexOf(']]>', lt + 9);
      i = end >= 0 ? end + 3 : len;
      continue;
    }
    if (xmlText.startsWith('</', lt) || xmlText.startsWith('<!', lt)) {
      const gt = findTagEndOffset(xmlText, lt + 2);
      i = gt >= 0 ? gt + 1 : lt + 2;
      continue;
    }

    const gt = findTagEndOffset(xmlText, lt + 1);
    if (gt < 0) {
      i = lt + 1;
      continue;
    }

    const openTagInner = xmlText.slice(lt + 1, gt);
    if (openTagInner.trimEnd().endsWith('/')) {
      i = gt + 1;
      continue;
    }

    const tagNameMatch = openTagInner.match(/^([a-zA-Z0-9_:-]+)(?:[\s]|$)/);
    if (!tagNameMatch) {
      i = gt + 1;
      continue;
    }

    const tagName = tagNameMatch[1];
    const bodyStart = gt + 1;

    // Skip whitespace inside element before checking CDATA
    let firstNonWs = bodyStart;
    while (firstNonWs < len && /\s/.test(xmlText[firstNonWs])) firstNonWs++;

    if (xmlText.startsWith('<![CDATA[', firstNonWs)) {
      const cdataStart = firstNonWs + 9;
      const cdataEnd = xmlText.indexOf(']]>', cdataStart);
      if (cdataEnd >= 0) {
        let afterCdata = cdataEnd + 3;
        while (afterCdata < len && /\s/.test(xmlText[afterCdata])) afterCdata++;
        const closePrefix = `</${tagName}`;
        if (xmlText.startsWith(closePrefix, afterCdata)) {
          let closePos = afterCdata + closePrefix.length;
          while (closePos < len && /\s/.test(xmlText[closePos])) closePos++;
          if (closePos < len && xmlText[closePos] === '>') {
            const cdataBody = xmlText.slice(cdataStart, cdataEnd);
            regions.push({
              tagName,
              path: `${basePath}/${tagName}[cdata()]`,
              key: tagName,
              encoding: 'XML_CDATA',
              span: { start: cdataStart, end: cdataEnd },
              rawValue: cdataBody,
              unescapedValue: cdataBody
            });
            i = closePos + 1;
            continue;
          }
        }
      }
    }

    // Check leaf element text containing entity-escaped markup (&lt;...&gt;)
    const nextLt = xmlText.indexOf('<', bodyStart);
    if (nextLt > bodyStart) {
      const closePrefix = `</${tagName}`;
      if (xmlText.startsWith(closePrefix, nextLt)) {
        let closePos = nextLt + closePrefix.length;
        while (closePos < len && /\s/.test(xmlText[closePos])) closePos++;
        if (closePos < len && xmlText[closePos] === '>') {
          const inner = xmlText.slice(bodyStart, nextLt);
          if (inner.includes('&lt;') && inner.includes('&gt;')) {
            const lead = inner.length - inner.trimStart().length;
            const trail = inner.length - inner.trimEnd().length;
            const spanStart = bodyStart + lead;
            const spanEnd = nextLt - trail;
            const rawValue = xmlText.slice(spanStart, spanEnd);
            regions.push({
              tagName,
              path: `${basePath}/${tagName}[text()]`,
              key: tagName,
              encoding: 'XML_ENTITY_TEXT',
              span: { start: spanStart, end: spanEnd },
              rawValue,
              unescapedValue: unescapeXmlEntities(rawValue)
            });
            i = closePos + 1;
            continue;
          }
        }
      }
    }

    i = gt + 1;
  }

  return regions;
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

function makeNodeId(depth, idx, spanStart, path) {
  const safePath = String(path || 'root').replace(/[^a-zA-Z0-9_.-]/g, '_');
  return `d${depth}_i${idx}_s${spanStart}_${safePath}`;
}

function analyzeJsonStringLiteral(lit, maxDepth, currentDepth, basePath, idx) {
  const val = lit.rawValue;
  const trimmedVal = val.trim();
  if (trimmedVal.length < 2) return null;

  const span = { start: lit.tokenStart, end: lit.tokenEnd };

  // 1. Embedded JSON or KV pseudo-JSON string
  if ((trimmedVal.startsWith('{') && trimmedVal.endsWith('}')) || (trimmedVal.startsWith('[') && trimmedVal.endsWith(']'))) {
    try {
      const parsedInner = JSON.parse(trimmedVal);
      if (typeof parsedInner === 'object' && parsedInner !== null) {
        const children = currentDepth + 1 < maxDepth
          ? discoverEmbeddedPayloads(trimmedVal, maxDepth, currentDepth + 1, lit.path)
          : [];
        return {
          id: makeNodeId(currentDepth, idx, span.start, lit.path),
          path: lit.path,
          parentPath: basePath,
          key: lit.key,
          format: 'json',
          encoding: 'JSON_STRING',
          charset: detectCharset(trimmedVal),
          span,
          rawValue: val,
          unescapedValue: trimmedVal,
          formattedValue: JSON.stringify(parsedInner, null, 2),
          wireStyle: trimmedVal.includes('\n') ? 'multiline' : 'compact',
          byteLength: utf8ByteLength(val),
          children
        };
      }
    } catch (_) {}

    if (trimmedVal.includes('=')) {
      const kv = parsePseudoJson(trimmedVal);
      if (kv && typeof kv === 'object') {
        return {
          id: makeNodeId(currentDepth, idx, span.start, lit.path),
          path: lit.path,
          parentPath: basePath,
          key: lit.key,
          format: 'kv',
          encoding: 'JSON_STRING',
          charset: detectCharset(trimmedVal),
          span,
          rawValue: val,
          unescapedValue: trimmedVal,
          formattedValue: formatPayload(trimmedVal),
          wireStyle: trimmedVal.includes('\n') ? 'multiline' : 'compact',
          byteLength: utf8ByteLength(val),
          children: []
        };
      }
    }
  }

  // 2. Embedded XML string
  if (trimmedVal.startsWith('<') && trimmedVal.endsWith('>')) {
    if (detectFormat(trimmedVal) === 'xml') {
      const children = currentDepth + 1 < maxDepth
        ? discoverEmbeddedPayloads(trimmedVal, maxDepth, currentDepth + 1, lit.path)
        : [];
      return {
        id: makeNodeId(currentDepth, idx, span.start, lit.path),
        path: lit.path,
        parentPath: basePath,
        key: lit.key,
        format: 'xml',
        encoding: 'JSON_STRING',
        charset: detectCharset(trimmedVal),
        span,
        rawValue: val,
        unescapedValue: trimmedVal,
        formattedValue: formatXml(trimmedVal),
        wireStyle: trimmedVal.includes('\n') ? 'multiline' : 'compact',
        byteLength: utf8ByteLength(val),
        children
      };
    }
  }

  // 3. Base64-encoded JSON, XML, KV, or printable Text
  if (val.length >= 8) {
    const cleanedB64 = val.trim().replace(/[\r\n\s]+/g, '');
    const isGenuineBase64 = cleanedB64.length >= 8 && cleanedB64.length % 4 !== 1 && /^[A-Za-z0-9+/_-]{8,}={0,2}$/.test(cleanedB64);
    if (isGenuineBase64) {
      const b64Decoded = decodeBase64WithCharset(cleanedB64);
      if (b64Decoded && b64Decoded.text && b64Decoded.text.length >= 3) {
        const decodedText = b64Decoded.text;
        const innerFormat = detectFormat(decodedText);
        const isPrintable = isPrintableText(decodedText);

        const isMatch = (innerFormat === 'json' || innerFormat === 'xml' || innerFormat === 'kv') ||
          (innerFormat === 'text' && isPrintable && cleanedB64.length >= 24 && isMostlyAsciiText(decodedText));

        if (isMatch) {
          const children = (innerFormat !== 'text' && currentDepth + 1 < maxDepth)
            ? discoverEmbeddedPayloads(decodedText, maxDepth, currentDepth + 1, lit.path)
            : [];
          return {
            id: makeNodeId(currentDepth, idx, span.start, lit.path),
            path: lit.path,
            parentPath: basePath,
            key: lit.key,
            format: innerFormat,
            encoding: b64Decoded.charset === 'UTF-8' ? 'BASE64_UTF8' : 'BASE64',
            charset: b64Decoded.charset,
            span,
            rawValue: val,
            unescapedValue: decodedText,
            formattedValue: formatPayload(decodedText),
            wireStyle: decodedText.trim().includes('\n') ? 'multiline' : 'compact',
            byteLength: utf8ByteLength(val),
            children
          };
        }
      }
    }
  }

  return null;
}

/**
 * Recursively discovers embedded JSON, XML, KV, or Base64 payloads inside raw text with exact spans.
 *
 * @param {string} text
 * @param {number} [maxDepth=5]
 * @param {number} [currentDepth=0]
 * @param {string} [basePath='$']
 * @returns {Array<object>}
 */
export function discoverEmbeddedPayloads(text, maxDepth = 5, currentDepth = 0, basePath = '$') {
  if (typeof text !== 'string' || currentDepth >= maxDepth) return [];
  if (!text.trim()) return [];

  const results = [];
  const outerFormat = detectFormat(text);

  if (outerFormat === 'json') {
    const literals = scanJsonStringLiterals(text);
    for (const lit of literals) {
      const node = analyzeJsonStringLiteral(lit, maxDepth, currentDepth, basePath, results.length);
      if (node) {
        results.push(node);
      }
    }
  } else if (outerFormat === 'xml') {
    const regions = scanXmlEmbeddedRegions(text, basePath);
    for (const reg of regions) {
      const innerFormat = detectFormat(reg.unescapedValue);
      if (innerFormat === 'json' || innerFormat === 'xml' || innerFormat === 'kv') {
        const children = currentDepth + 1 < maxDepth
          ? discoverEmbeddedPayloads(reg.unescapedValue, maxDepth, currentDepth + 1, reg.path)
          : [];
        results.push({
          id: makeNodeId(currentDepth, results.length, reg.span.start, reg.path),
          path: reg.path,
          parentPath: basePath,
          key: reg.key,
          format: innerFormat,
          encoding: reg.encoding,
          charset: detectCharset(reg.unescapedValue),
          span: reg.span,
          rawValue: reg.rawValue,
          unescapedValue: reg.unescapedValue,
          formattedValue: formatPayload(reg.unescapedValue),
          wireStyle: reg.unescapedValue.trim().includes('\n') ? 'multiline' : 'compact',
          byteLength: utf8ByteLength(reg.rawValue),
          children
        });
      }
    }
  }

  return results;
}

function parseJsonPathTokens(path) {
  if (!path || path === '$') return [];
  let clean = path;
  if (clean.startsWith('$.')) clean = clean.slice(2);
  else if (clean.startsWith('$')) clean = clean.slice(1);

  const tokens = [];
  let i = 0;
  while (i < clean.length) {
    if (clean[i] === '.') {
      i++;
      continue;
    }
    if (clean[i] === '[') {
      i++;
      if (clean[i] === "'" || clean[i] === '"') {
        const q = clean[i++];
        let key = '';
        while (i < clean.length && clean[i] !== q) {
          if (clean[i] === '\\' && i + 1 < clean.length) i++;
          key += clean[i++];
        }
        if (clean[i] === q) i++;
        if (clean[i] === ']') i++;
        tokens.push(key);
      } else {
        const start = i;
        while (i < clean.length && clean[i] !== ']') i++;
        const idxStr = clean.slice(start, i);
        if (clean[i] === ']') i++;
        tokens.push(/^\d+$/.test(idxStr) ? parseInt(idxStr, 10) : idxStr);
      }
      continue;
    }
    const start = i;
    while (i < clean.length && clean[i] !== '.' && clean[i] !== '[') i++;
    const seg = clean.slice(start, i);
    if (seg) tokens.push(seg);
  }
  return tokens;
}

function setJsonPathValue(root, path, newValue) {
  const tokens = parseJsonPathTokens(path);
  if (tokens.length === 0) return newValue;

  let curr = root;
  for (let i = 0; i < tokens.length - 1; i++) {
    const t = tokens[i];
    if (curr[t] === undefined || curr[t] === null || typeof curr[t] !== 'object') {
      curr[t] = typeof tokens[i + 1] === 'number' ? [] : {};
    }
    curr = curr[t];
  }

  curr[tokens[tokens.length - 1]] = newValue;
  return root;
}

function getRelativeJsonPath(childPath, parentPath) {
  if (!childPath) return '';
  if (!parentPath || parentPath === '$') {
    return childPath.startsWith('$.') ? childPath.slice(2) : (childPath.startsWith('$') ? childPath.slice(1) : childPath);
  }
  if (childPath.startsWith(parentPath)) {
    let rel = childPath.slice(parentPath.length);
    while (rel.startsWith('.') || rel.startsWith('/')) {
      rel = rel.slice(1);
    }
    return rel;
  }
  return childPath.startsWith('$.') ? childPath.slice(2) : childPath;
}

function resolveWireText(editedUnescapedText, format, effectiveStyle) {
  if (format === 'kv' || format === 'text') {
    return editedUnescapedText;
  }
  if (effectiveStyle === 'compact') {
    if (format === 'json') {
      try {
        return JSON.stringify(JSON.parse(editedUnescapedText));
      } catch (_) {
        return editedUnescapedText;
      }
    }
    if (format === 'xml') {
      return minifyXml(editedUnescapedText);
    }
  }
  return editedUnescapedText;
}

function encodeNodeWireToken(wireText, node, isParentJson) {
  const enc = node.encoding || 'JSON_STRING';
  if (enc === 'BASE64' || enc === 'BASE64_UTF8') {
    const prologCharset = detectCharset(wireText);
    const targetCharset = prologCharset !== 'UTF-8' ? prologCharset : (node.charset || 'UTF-8');
    const encoded = encodeToBase64(wireText, targetCharset);
    return {
      wireStringVal: encoded,
      encodedToken: isParentJson ? JSON.stringify(encoded) : encoded
    };
  }
  if (enc === 'XML_CDATA') {
    return { wireStringVal: wireText, encodedToken: wireText };
  }
  if (enc === 'XML_ENTITY_TEXT') {
    const escaped = escapeXmlEntities(wireText);
    return { wireStringVal: escaped, encodedToken: escaped };
  }
  // JSON_STRING
  return {
    wireStringVal: wireText,
    encodedToken: JSON.stringify(wireText)
  };
}

/**
 * Surgically splices an edited child payload back into its parent container text using exact character spans.
 * Falls back to strict JSONPath traversal (never top-level key lookup) only when parentText was externally reformatted.
 *
 * @param {string} parentText
 * @param {object} node
 * @param {string} editedUnescapedText
 * @param {object} [options]
 * @param {'auto'|'compact'|'multiline'|null} [options.wireStyleOverride=null]
 * @param {string|null} [options.parentPath=null]
 * @returns {string}
 */
export function spliceEmbeddedPayloadEdit(parentText, node, editedUnescapedText, { wireStyleOverride = null, parentPath = null } = {}) {
  if (!node) return editedUnescapedText;
  if (typeof parentText !== 'string') return editedUnescapedText;

  const effectiveStyle = (wireStyleOverride && wireStyleOverride !== 'auto')
    ? wireStyleOverride
    : (node.wireStyle || 'compact');

  const wireText = resolveWireText(editedUnescapedText, node.format, effectiveStyle);
  const enc = node.encoding || 'JSON_STRING';
  const trimmedParent = parentText.trimStart();
  const isParentJson = trimmedParent.startsWith('{') || trimmedParent.startsWith('[')
    || (node.span && parentText[node.span.start] === '"');

  const { wireStringVal, encodedToken } = encodeNodeWireToken(wireText, node, isParentJson);

  // 1. Primary exact-span splice
  if (node.span
    && Number.isInteger(node.span.start)
    && Number.isInteger(node.span.end)
    && node.span.start >= 0
    && node.span.end <= parentText.length
    && node.span.start <= node.span.end) {
    const slice = parentText.slice(node.span.start, node.span.end);
    if (enc === 'XML_CDATA' || enc === 'XML_ENTITY_TEXT') {
      if (node.rawValue === undefined || slice === node.rawValue) {
        return parentText.slice(0, node.span.start) + encodedToken + parentText.slice(node.span.end);
      }
    } else if (slice.startsWith('"') && slice.endsWith('"')) {
      try {
        const parsedSlice = JSON.parse(slice);
        if (node.rawValue === undefined || parsedSlice === node.rawValue) {
          return parentText.slice(0, node.span.start) + encodedToken + parentText.slice(node.span.end);
        }
      } catch (_) {}
    }
  }

  // 2. Fallback: strict JSONPath traversal (never Object.hasOwnProperty on node.key)
  if (enc !== 'XML_CDATA' && enc !== 'XML_ENTITY_TEXT') {
    try {
      const parsedParent = JSON.parse(parentText);
      const effectiveParentPath = parentPath || node.parentPath || '$';
      const relPath = getRelativeJsonPath(node.path, effectiveParentPath);
      if (relPath) {
        setJsonPathValue(parsedParent, relPath, wireStringVal);
        const isIndented = parentText.includes('\n');
        return JSON.stringify(parsedParent, null, isIndented ? 2 : undefined);
      }
    } catch (_) {}
  }

  // 3. XML fallback if parent XML was reformatted externally
  if (enc === 'XML_CDATA' || enc === 'XML_ENTITY_TEXT') {
    const regions = scanXmlEmbeddedRegions(parentText, '$');
    const matched = regions.find(r => r.encoding === enc && r.key === node.key && (node.rawValue === undefined || r.rawValue === node.rawValue));
    if (matched) {
      return parentText.slice(0, matched.span.start) + encodedToken + parentText.slice(matched.span.end);
    }
  }

  return parentText;
}

/**
 * Cascades an edit from the deepest node in `nodePathChain` ([depth0Node, depth1Node, ...])
 * up through all intermediate parents back to `rootText`.
 *
 * @param {string} rootText
 * @param {Array<object>|object} nodePathChain
 * @param {string} editedLeafText
 * @param {object} [options]
 * @returns {string}
 */
export function applyNestedPathEdit(rootText, nodePathChain, editedLeafText, options = {}) {
  const chain = Array.isArray(nodePathChain) ? nodePathChain : (nodePathChain ? [nodePathChain] : []);
  if (chain.length === 0) return editedLeafText;

  let currentText = editedLeafText;
  for (let i = chain.length - 1; i >= 0; i--) {
    const node = chain[i];
    const parentText = i === 0 ? rootText : chain[i - 1].unescapedValue;
    const isLeaf = i === chain.length - 1;
    const stepOpts = isLeaf
      ? options
      : { parentPath: i > 0 ? chain[i - 1].path : '$' };
    currentText = spliceEmbeddedPayloadEdit(parentText, node, currentText, stepOpts);
  }
  return currentText;
}

/**
 * Applies edited text of a single child node back into its parent container text with optional validation.
 *
 * @param {string} parentText
 * @param {object} nodeDescriptor
 * @param {string} editedChildText
 * @param {object} [options]
 * @param {object|null} [parentDescriptor=null]
 * @returns {string}
 */
export function applyNestedPayloadEdit(parentText, nodeDescriptor, editedChildText, options = {}, parentDescriptor = null) {
  if (!nodeDescriptor) return editedChildText;

  if (!options.forceInvalid) {
    const valResult = validatePayload(editedChildText, nodeDescriptor.format);
    if (!valResult.valid) {
      throw new Error(valResult.errorMessage || 'Invalid payload syntax');
    }
  }

  let wireStyleOverride = options.wireStyleOverride || null;
  if (!wireStyleOverride && options.minifyOnSave === true) {
    wireStyleOverride = 'compact';
  }

  return spliceEmbeddedPayloadEdit(parentText, nodeDescriptor, editedChildText, {
    wireStyleOverride,
    parentPath: parentDescriptor ? parentDescriptor.path : undefined
  });
}

/**
 * Cascades edits from a legacy lens stack frame list up to root wire text.
 *
 * @param {Array<{ text?: string, payload?: string, descriptor?: object }>} stackFrames
 * @param {string} deepestEditedText
 * @param {object} [options={}]
 * @returns {string}
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
      i === stackFrames.length - 1 ? options : { forceInvalid: Boolean(options.forceInvalid) },
      parentDescriptor
    );
    if (parentFrame.text !== undefined) parentFrame.text = currentText;
    if (parentFrame.payload !== undefined) parentFrame.payload = currentText;
  }

  return currentText;
}

/**
 * Formats input text, discovers embedded payloads directly against that formatted text,
 * filters any overlapping spans, and slices into ordered code/embedded segments with zero placeholders.
 *
 * @param {string} text
 * @param {number} [maxDepth=5]
 * @param {object|string|string[]|null} [headers=null]
 * @returns {{ formattedText: string, format: string, charset: string, nodes: Array<object>, segments: Array<{ type: 'code'|'embedded', text?: string, format?: string, node?: object }> }}
 */
export function buildPolyglotSegments(text, maxDepth = 5, headers = null) {
  if (typeof text !== 'string' || !text) {
    return { formattedText: '', format: 'text', charset: 'UTF-8', nodes: [], segments: [] };
  }

  const formattedText = formatPayload(text);
  const format = detectFormat(formattedText);
  const charset = detectCharset(formattedText, headers);
  const rawNodes = (format === 'json' || format === 'xml')
    ? discoverEmbeddedPayloads(formattedText, maxDepth, 0, '$')
    : [];

  const sorted = [...rawNodes].sort((a, b) => a.span.start - b.span.start);
  const nodes = [];
  let lastEnd = 0;
  for (const node of sorted) {
    if (!node.span || node.span.start < lastEnd || node.span.end < node.span.start) continue;
    nodes.push(node);
    lastEnd = node.span.end;
  }

  const segments = [];
  let cursor = 0;
  for (const node of nodes) {
    if (node.span.start > cursor) {
      segments.push({
        type: 'code',
        text: formattedText.slice(cursor, node.span.start),
        format
      });
    }
    segments.push({
      type: 'embedded',
      node
    });
    cursor = node.span.end;
  }
  if (cursor < formattedText.length) {
    segments.push({
      type: 'code',
      text: formattedText.slice(cursor),
      format
    });
  }

  return { formattedText, format, charset, nodes, segments };
}
