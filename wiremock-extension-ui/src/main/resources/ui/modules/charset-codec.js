/**
 * Zero-DOM character encoding detection, single-byte codec caching, and Base64 utilities.
 * Supports UTF-8, ISO-8859-1, WINDOWS-1252, KOI8-R, US-ASCII, and XML prolog encoding detection.
 */

const CHARSET_ALIAS_MAP = {
  'UTF8': 'UTF-8',
  'UTF_8': 'UTF-8',
  'UTF-8': 'UTF-8',
  'LATIN1': 'ISO-8859-1',
  'LATIN-1': 'ISO-8859-1',
  'ISO-LATIN-1': 'ISO-8859-1',
  'ISO88591': 'ISO-8859-1',
  'ISO8859-1': 'ISO-8859-1',
  'ISO_8859-1': 'ISO-8859-1',
  'ISO-8859_1': 'ISO-8859-1',
  'ISO-8859-1': 'ISO-8859-1',
  '8859-1': 'ISO-8859-1',
  '8859_1': 'ISO-8859-1',
  'CP1252': 'WINDOWS-1252',
  'CP-1252': 'WINDOWS-1252',
  'WIN1252': 'WINDOWS-1252',
  'WIN-1252': 'WINDOWS-1252',
  'WINDOWS1252': 'WINDOWS-1252',
  'WINDOWS_1252': 'WINDOWS-1252',
  'WINDOWS-1252': 'WINDOWS-1252',
  'KOI8': 'KOI8-R',
  'KOI8R': 'KOI8-R',
  'KOI8_R': 'KOI8-R',
  'KOI8-R': 'KOI8-R',
  'ASCII': 'US-ASCII',
  'US_ASCII': 'US-ASCII',
  'US-ASCII': 'US-ASCII',
  'ANSI_X3.4-1968': 'US-ASCII'
};

// Windows-1252 0x80..0x9F C1 mapping per WHATWG Encoding Standard (Node TextDecoder fast-path omits these)
const WIN1252_C1_DECODE = {
  0x80: 0x20AC, 0x82: 0x201A, 0x83: 0x0192, 0x84: 0x201E, 0x85: 0x2026, 0x86: 0x2020, 0x87: 0x2021,
  0x88: 0x02C6, 0x89: 0x2030, 0x8A: 0x0160, 0x8B: 0x2039, 0x8C: 0x0152, 0x8E: 0x017D,
  0x91: 0x2018, 0x92: 0x2019, 0x93: 0x201C, 0x94: 0x201D, 0x95: 0x2022, 0x96: 0x2013, 0x97: 0x2014,
  0x98: 0x02DC, 0x99: 0x2122, 0x9A: 0x0161, 0x9B: 0x203A, 0x9C: 0x0153, 0x9E: 0x017E, 0x9F: 0x0178
};
const WIN1252_UNDEFINED = new Set([0x81, 0x8D, 0x8F, 0x90, 0x9D]);

const singleByteEncoderCache = new Map();

function getSingleByteEncodeMap(normCharset) {
  if (singleByteEncoderCache.has(normCharset)) {
    return singleByteEncoderCache.get(normCharset);
  }
  const map = new Map();
  if (normCharset === 'WINDOWS-1252') {
    for (let b = 0; b < 256; b++) {
      if (b >= 0x80 && b <= 0x9F) {
        const mapped = WIN1252_C1_DECODE[b];
        if (mapped !== undefined) map.set(mapped, b);
      } else {
        map.set(b, b);
      }
    }
    singleByteEncoderCache.set(normCharset, map);
    return map;
  }
  try {
    const dec = new TextDecoder(normCharset.toLowerCase());
    const buf = new Uint8Array(256);
    for (let b = 0; b < 256; b++) buf[b] = b;
    const decoded = dec.decode(buf);
    for (let b = 0; b < 256; b++) {
      const code = decoded.charCodeAt(b);
      if (code !== 0xFFFD && !map.has(code)) {
        map.set(code, b);
      }
    }
  } catch (_) {
    for (let b = 0; b < 256; b++) map.set(b, b);
  }
  singleByteEncoderCache.set(normCharset, map);
  return map;
}

/**
 * Normalizes a character encoding name to its canonical uppercase identifier.
 *
 * @param {string} [charset]
 * @returns {string} Canonical uppercase charset (e.g. 'UTF-8', 'ISO-8859-1', 'WINDOWS-1252', 'KOI8-R', 'US-ASCII')
 */
export function normalizeCharsetName(charset) {
  if (!charset || typeof charset !== 'string') return 'UTF-8';
  const raw = charset.trim().replace(/^["']|["']$/g, '').toUpperCase();
  if (!raw) return 'UTF-8';
  return CHARSET_ALIAS_MAP[raw] || raw;
}

/**
 * Detects character encoding from XML prolog declaration or HTTP/Casual headers.
 * Priority: 1. XML prolog encoding, 2. X-Casual-Charset header, 3. Content-Type charset, 4. 'UTF-8'.
 *
 * @param {string} [text] Payload text
 * @param {object|string|string[]} [headers] Header map, raw multi-line header text, or header line array
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
      if (idx > 0) {
        headerEntries.push([line.substring(0, idx).trim(), line.substring(idx + 1).trim()]);
      }
    }
  } else if (Array.isArray(headers)) {
    for (const line of headers) {
      const str = String(line || '');
      const idx = str.indexOf(':');
      if (idx > 0) {
        headerEntries.push([str.substring(0, idx).trim(), str.substring(idx + 1).trim()]);
      }
    }
  } else if (headers && typeof headers === 'object') {
    headerEntries = Object.entries(headers);
  }

  if (headerEntries.length > 0) {
    let contentTypeCharset = null;
    for (const [k, v] of headerEntries) {
      if (!v) continue;
      const lowerKey = String(k).toLowerCase();
      const valStr = Array.isArray(v) ? String(v[0] || '') : String(v);
      if (lowerKey === 'x-casual-charset') {
        return normalizeCharsetName(valStr);
      }
      if (lowerKey === 'content-type') {
        const charsetMatch = valStr.match(/charset\s*=\s*(?:"([^"]+)"|'([^']+)'|([^\s;,"']+))/i);
        const matchedVal = charsetMatch && (charsetMatch[1] || charsetMatch[2] || charsetMatch[3]);
        if (matchedVal) {
          contentTypeCharset = normalizeCharsetName(matchedVal);
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
 * Encodes a string into a Uint8Array using the specified character encoding.
 *
 * @param {string} str
 * @param {string} [charset='UTF-8']
 * @returns {Uint8Array}
 */
export function encodeStringToBytes(str, charset = 'UTF-8') {
  const input = typeof str === 'string' ? str : (str == null ? '' : String(str));
  const norm = normalizeCharsetName(charset);
  if (norm === 'UTF-8') {
    if (typeof TextEncoder !== 'undefined') {
      return new TextEncoder().encode(input);
    }
    const unescaped = unescape(encodeURIComponent(input));
    const out = new Uint8Array(unescaped.length);
    for (let i = 0; i < unescaped.length; i++) out[i] = unescaped.charCodeAt(i);
    return out;
  }
  if (norm === 'US-ASCII') {
    const out = new Uint8Array(input.length);
    for (let i = 0; i < input.length; i++) {
      const code = input.charCodeAt(i);
      out[i] = code <= 0x7F ? code : 0x3F;
    }
    return out;
  }
  if (norm === 'ISO-8859-1') {
    const out = new Uint8Array(input.length);
    for (let i = 0; i < input.length; i++) {
      const code = input.charCodeAt(i);
      out[i] = code <= 0xFF ? code : 0x3F;
    }
    return out;
  }
  const encodeMap = getSingleByteEncodeMap(norm);
  const out = new Uint8Array(input.length);
  for (let i = 0; i < input.length; i++) {
    const code = input.charCodeAt(i);
    out[i] = encodeMap.has(code) ? encodeMap.get(code) : (code <= 0xFF ? code : 0x3F);
  }
  return out;
}

/**
 * Decodes a byte buffer into a string using the specified character encoding.
 *
 * @param {Uint8Array|ArrayBuffer|number[]} bytes
 * @param {string} [charset='UTF-8']
 * @returns {string}
 */
export function decodeBytesToString(bytes, charset = 'UTF-8') {
  if (!bytes) return '';
  const u8 = bytes instanceof Uint8Array
    ? bytes
    : (bytes instanceof ArrayBuffer ? new Uint8Array(bytes) : Uint8Array.from(bytes));
  const norm = normalizeCharsetName(charset);

  if (norm === 'ISO-8859-1') {
    // WHATWG TextDecoder maps 'iso-8859-1' to windows-1252; decode 0x00..0xFF 1:1 directly.
    let isoText = '';
    for (let i = 0; i < u8.length; i++) {
      isoText += String.fromCharCode(u8[i]);
    }
    return isoText;
  }

  if (norm === 'WINDOWS-1252') {
    let winText = '';
    for (let i = 0; i < u8.length; i++) {
      const b = u8[i];
      if (b >= 0x80 && b <= 0x9F) {
        const mapped = WIN1252_C1_DECODE[b];
        winText += String.fromCharCode(mapped !== undefined ? mapped : b);
      } else {
        winText += String.fromCharCode(b);
      }
    }
    return winText;
  }

  try {
    return new TextDecoder(norm.toLowerCase(), { fatal: false }).decode(u8);
  } catch (_) {
    let fallback = '';
    for (let i = 0; i < u8.length; i++) {
      fallback += String.fromCharCode(u8[i]);
    }
    return fallback;
  }
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

function base64ToBytes(b64Str) {
  if (typeof b64Str !== 'string') return null;
  const cleaned = b64Str.trim().replace(/[\r\n\s]+/g, '');
  if (cleaned.length < 2) return null;

  let normalized = cleaned.replace(/-/g, '+').replace(/_/g, '/');
  while (normalized.length % 4 !== 0) {
    normalized += '=';
  }
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(normalized)) {
    return null;
  }

  try {
    const binary = atob(normalized);
    if (!binary || binary.length === 0) return null;
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
  } catch (_) {
    return null;
  }
}

/**
 * Decodes a Base64 string while respecting declared XML prolog charsets or non-UTF-8 single-byte encodings.
 *
 * @param {string} b64Str
 * @returns {{ text: string, charset: string, bytes: Uint8Array } | null}
 */
export function decodeBase64WithCharset(b64Str) {
  const bytes = base64ToBytes(b64Str);
  if (!bytes) return null;

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
      const decoded = decodeBytesToString(bytes, declaredCharset);
      return { text: decoded, charset: declaredCharset, bytes };
    }
  }

  // 2. Try strict UTF-8
  try {
    const utf8Text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
    return { text: utf8Text, charset: detectCharset(utf8Text), bytes };
  } catch (_) {
    // 3. Fallback: check if printable single-byte text (WINDOWS-1252 vs ISO-8859-1)
    let isoText = '';
    let hasWin1252HighC1 = false;
    for (let i = 0; i < bytes.length; i++) {
      const b = bytes[i];
      if (b < 0x20 && b !== 0x09 && b !== 0x0A && b !== 0x0D) {
        return null;
      }
      if (b >= 0x80 && b <= 0x9F && !WIN1252_UNDEFINED.has(b)) {
        hasWin1252HighC1 = true;
      }
      isoText += String.fromCharCode(b);
    }

    const detected = detectCharset(isoText);
    if (detected !== 'UTF-8') {
      return { text: decodeBytesToString(bytes, detected), charset: detected, bytes };
    }
    if (hasWin1252HighC1) {
      return { text: decodeBytesToString(bytes, 'WINDOWS-1252'), charset: 'WINDOWS-1252', bytes };
    }
    return { text: isoText, charset: 'ISO-8859-1', bytes };
  }
}

/**
 * Safely decodes a Base64 string to a UTF-8 string.
 * Returns null if the string is not valid Base64 or not valid UTF-8.
 *
 * @param {string} b64Str
 * @returns {string|null}
 */
export function decodeBase64ToUtf8(b64Str) {
  const bytes = base64ToBytes(b64Str);
  if (!bytes) return null;
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch (_) {
    return null;
  }
}

/**
 * Tests if a string is a Base64-encoded JSON object or array, returning the parsed JSON if so.
 * Returns null if not Base64 or not a valid JSON object/array.
 *
 * @param {string} b64Str
 * @returns {object|array|null}
 */
export function tryDecodeBase64Json(b64Str) {
  if (typeof b64Str !== 'string') return null;
  const utf8 = decodeBase64ToUtf8(b64Str);
  if (!utf8) return null;
  const trimmed = utf8.trim();
  if ((trimmed.startsWith('{') && trimmed.endsWith('}')) || (trimmed.startsWith('[') && trimmed.endsWith(']'))) {
    try {
      const parsed = JSON.parse(trimmed);
      if (typeof parsed === 'object' && parsed !== null) {
        return parsed;
      }
    } catch (_) {}
  }
  return null;
}
