/**
 * Utility for detecting, parsing, and deep-decoding Base64-encoded JSON payloads.
 * Supports standard Base64, URL-safe Base64, MIME wrapped lines, and UTF-8 multi-byte characters.
 */

/**
 * Safely decodes a Base64 string to a UTF-8 string.
 * Returns null if the string is not valid Base64 or not valid UTF-8.
 *
 * @param {string} str
 * @returns {string|null}
 */
export function decodeBase64ToUtf8(str) {
  if (typeof str !== 'string') return null;
  const cleaned = str.trim().replace(/[\r\n\s]+/g, '');
  if (cleaned.length < 2) return null;

  // Normalize URL-safe Base64
  let normalized = cleaned.replace(/-/g, '+').replace(/_/g, '/');
  while (normalized.length % 4 !== 0) {
    normalized += '=';
  }

  // Must match Base64 character set
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
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch (_) {
    return null;
  }
}

/**
 * Tests if a string is a Base64-encoded JSON object or array, returning the parsed JSON if so.
 * Returns null if not Base64 or not a valid JSON object/array.
 *
 * @param {string} val
 * @returns {object|array|null}
 */
export function tryDecodeBase64Json(val) {
  if (typeof val !== 'string') return null;
  const utf8 = decodeBase64ToUtf8(val);
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

/**
 * Recursively inspects an object or array and decodes any string values
 * that contain Base64-encoded JSON.
 *
 * @param {any} input
 * @returns {{ modified: boolean, decoded: any }}
 */
export function deepDecodeBase64InObject(input) {
  let modified = false;

  function walk(node) {
    if (node === null || typeof node !== 'object') {
      return node;
    }
    if (Array.isArray(node)) {
      return node.map(item => {
        if (typeof item === 'string') {
          const decoded = tryDecodeBase64Json(item);
          if (decoded !== null) {
            modified = true;
            return walk(decoded);
          }
          return item;
        }
        return walk(item);
      });
    }
    const result = {};
    for (const [key, value] of Object.entries(node)) {
      if (typeof value === 'string') {
        const decoded = tryDecodeBase64Json(value);
        if (decoded !== null) {
          modified = true;
          result[key] = walk(decoded);
        } else {
          result[key] = value;
        }
      } else if (typeof value === 'object' && value !== null) {
        result[key] = walk(value);
      } else {
        result[key] = value;
      }
    }
    return result;
  }

  const decoded = walk(input);
  return { modified, decoded };
}

/**
 * Parses and decodes a request or response body if it contains Base64-encoded JSON.
 * Handles:
 * - JSON payloads containing string fields with Base64 JSON
 * - Direct Base64-encoded JSON strings
 * - Already-parsed JSON objects
 *
 * @param {any} rawInput
 * @returns {{ hasBase64: boolean, raw: any, decoded: any, decodedFormatted: string }}
 */
export function decodeBase64Payload(rawInput) {
  if (rawInput === null || rawInput === undefined) {
    return { hasBase64: false, raw: '', decoded: null, decodedFormatted: '' };
  }

  const str = typeof rawInput === 'string' ? rawInput.trim() : '';

  // 1. Direct Base64 string that decodes to a JSON object or array
  if (str) {
    const directDecoded = tryDecodeBase64Json(str);
    if (directDecoded !== null) {
      const { decoded: finalDecoded } = deepDecodeBase64InObject(directDecoded);
      return {
        hasBase64: true,
        raw: rawInput,
        decoded: finalDecoded,
        decodedFormatted: JSON.stringify(finalDecoded, null, 2)
      };
    }
  }

  // 2. Structured JSON (string or object) containing nested Base64 JSON fields
  let parsedJson = null;
  if (typeof rawInput === 'object' && rawInput !== null) {
    parsedJson = rawInput;
  } else if (str && ((str.startsWith('{') && str.endsWith('}')) || (str.startsWith('[') && str.endsWith(']')))) {
    try {
      parsedJson = JSON.parse(str);
    } catch (_) {}
  }

  if (parsedJson !== null) {
    const { modified, decoded } = deepDecodeBase64InObject(parsedJson);
    if (modified) {
      return {
        hasBase64: true,
        raw: rawInput,
        decoded,
        decodedFormatted: JSON.stringify(decoded, null, 2)
      };
    }
  }

  return {
    hasBase64: false,
    raw: rawInput,
    decoded: null,
    decodedFormatted: ''
  };
}

/**
 * Normalizes a payload into a parsed JSON object/array (with all nested Base64-encoded JSON strings
 * such as FML_ARCHIVE_JSON decoded into nested JSON structures), or returns null if not JSON.
 *
 * @param {any} input
 * @returns {any|null} Decoded JSON object or array, or null if input is not valid JSON
 */
export function normalizeJsonPayload(input) {
  if (input === null || input === undefined) return null;

  let parsed = null;
  if (typeof input === 'object') {
    parsed = input;
  } else if (typeof input === 'string') {
    const trimmed = input.trim();
    if ((trimmed.startsWith('{') && trimmed.endsWith('}')) || (trimmed.startsWith('[') && trimmed.endsWith(']'))) {
      try {
        parsed = JSON.parse(trimmed);
      } catch (_) {
        return null;
      }
    } else {
      // Check if the raw string is a direct Base64-encoded JSON string
      const directDecoded = tryDecodeBase64Json(trimmed);
      if (directDecoded !== null) {
        parsed = directDecoded;
      } else {
        return null;
      }
    }
  } else {
    return null;
  }

  if (parsed !== null && typeof parsed === 'object') {
    const { decoded } = deepDecodeBase64InObject(parsed);
    return decoded;
  }
  return null;
}

