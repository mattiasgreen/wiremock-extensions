/**
 * Zero-DOM Java Map.toString() / Key-Value pseudo-JSON parser and formatter.
 * Preserves {key = value} KV notation without converting structures into JSON.
 */

/**
 * Strict recursive-descent parser for Java Map.toString() and key-value pseudo-JSON.
 * Handles {key=val, nested={a=1, arr=[x, y]}} and {messages=null; deviations=[]}.
 * Returns null on empty, unbalanced, or invalid input.
 *
 * @param {string} input
 * @returns {object|array|null}
 */
export function parsePseudoJson(input) {
  if (typeof input !== 'string') return null;
  const str = input.trim();
  if (!str) return null;
  if (!((str.startsWith('{') && str.endsWith('}')) || (str.startsWith('[') && str.endsWith(']')))) {
    return null;
  }

  let pos = 0;
  const len = str.length;

  function skipWhitespace() {
    while (pos < len && /\s/.test(str[pos])) pos++;
  }

  function parseQuoted(quote) {
    pos++; // skip opening quote
    let res = '';
    while (pos < len) {
      const c = str[pos++];
      if (c === '\\') {
        if (pos >= len) {
          throw new Error('Unterminated escape in quoted string');
        }
        const esc = str[pos++];
        switch (esc) {
          case 'n': res += '\n'; break;
          case 'r': res += '\r'; break;
          case 't': res += '\t'; break;
          case 'b': res += '\b'; break;
          case 'f': res += '\f'; break;
          case '\\': res += '\\'; break;
          case '"': res += '"'; break;
          case "'": res += "'"; break;
          case 'u': {
            const hex = str.slice(pos, pos + 4);
            if (/^[0-9a-fA-F]{4}$/.test(hex)) {
              res += String.fromCharCode(parseInt(hex, 16));
              pos += 4;
            } else {
              res += 'u';
            }
            break;
          }
          default: res += esc; break;
        }
      } else if (c === quote) {
        return res;
      } else {
        res += c;
      }
    }
    throw new Error('Unterminated quoted string');
  }

  function parseKey() {
    skipWhitespace();
    if (pos >= len) {
      throw new Error('Unexpected end of input while reading key');
    }
    if (str[pos] === '"' || str[pos] === "'") {
      return { key: parseQuoted(str[pos]), quoted: true };
    }
    const start = pos;
    while (pos < len) {
      const c = str[pos];
      if (c === '=' || c === ':' || c === '}' || c === ']' || c === '{' || c === '[' || c === ',' || c === ';' || /\s/.test(c)) {
        break;
      }
      pos++;
    }
    const key = str.slice(start, pos).trim();
    if (!key) {
      throw new Error('Empty unquoted key');
    }
    return { key, quoted: false };
  }

  function coerceScalar(raw) {
    if (raw === 'null') return null;
    if (raw === 'true') return true;
    if (raw === 'false') return false;
    if (/^-?(?:0|[1-9]\d*)$/.test(raw)) {
      const num = Number(raw);
      if (Number.isSafeInteger(num)) return num;
    } else if (/^-?(?:0|[1-9]\d*)\.\d+(?:[eE][+-]?\d+)?$/.test(raw)) {
      const num = Number(raw);
      if (Number.isFinite(num)) return num;
    }
    return raw;
  }

  function parseLiteral(allowEmpty) {
    const start = pos;
    while (pos < len) {
      const c = str[pos];
      if (c === ',' || c === ';' || c === '}' || c === ']' || c === '{' || c === '[') {
        break;
      }
      if (c === '"' || c === "'") {
        throw new Error('Unexpected quote inside unquoted literal');
      }
      pos++;
    }
    const raw = str.slice(start, pos).trim();
    if (!raw && !allowEmpty) {
      throw new Error('Expected value');
    }
    return coerceScalar(raw);
  }

  function parseValue(allowEmptyLiteral = false) {
    skipWhitespace();
    if (pos >= len) {
      throw new Error('Unexpected end of input while reading value');
    }
    const c = str[pos];
    if (c === '{') return parseObject();
    if (c === '[') return parseArray();
    if (c === '"' || c === "'") return parseQuoted(c);
    return parseLiteral(allowEmptyLiteral);
  }

  function parseObject() {
    pos++; // skip '{'
    const obj = {};
    skipWhitespace();
    if (pos < len && str[pos] === '}') {
      pos++;
      return obj;
    }
    while (pos < len) {
      const { key } = parseKey();
      skipWhitespace();
      if (pos >= len || (str[pos] !== '=' && str[pos] !== ':')) {
        throw new Error('Expected = or : after key');
      }
      pos++; // skip '=' or ':'
      const val = parseValue(true);
      obj[key] = val;
      skipWhitespace();
      if (pos >= len) {
        throw new Error('Unterminated object');
      }
      if (str[pos] === '}') {
        pos++;
        return obj;
      }
      if (str[pos] === ',' || str[pos] === ';') {
        pos++;
        skipWhitespace();
        if (pos >= len) {
          throw new Error('Trailing separator at end of input');
        }
        continue;
      }
      throw new Error(`Unexpected character '${str[pos]}' in object`);
    }
    throw new Error('Unterminated object');
  }

  function parseArray() {
    pos++; // skip '['
    const arr = [];
    skipWhitespace();
    if (pos < len && str[pos] === ']') {
      pos++;
      return arr;
    }
    while (pos < len) {
      const item = parseValue(false);
      arr.push(item);
      skipWhitespace();
      if (pos >= len) {
        throw new Error('Unterminated array');
      }
      if (str[pos] === ']') {
        pos++;
        return arr;
      }
      if (str[pos] === ',' || str[pos] === ';') {
        pos++;
        skipWhitespace();
        if (pos >= len) {
          throw new Error('Trailing separator at end of input');
        }
        continue;
      }
      throw new Error(`Unexpected character '${str[pos]}' in array`);
    }
    throw new Error('Unterminated array');
  }

  try {
    const parsed = parseValue(false);
    skipWhitespace();
    if (pos !== len) {
      return null;
    }
    return (parsed && typeof parsed === 'object') ? parsed : null;
  } catch (_) {
    return null;
  }
}

function formatPseudoKey(key) {
  if (/^[a-zA-Z0-9_.-]+$/.test(key)) {
    return key;
  }
  return JSON.stringify(key);
}

function formatPseudoString(val) {
  if (val === '' || val === 'null' || val === 'true' || val === 'false') {
    return JSON.stringify(val);
  }
  if (/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(val)) {
    return JSON.stringify(val);
  }
  if (/^\s|\s$/.test(val) || /[,;{}[\]="\'\n\r\t]/.test(val)) {
    return JSON.stringify(val);
  }
  return val;
}

function formatPseudoValue(val, indentUnit, depth) {
  if (val === null || val === undefined) return 'null';
  if (typeof val === 'boolean' || typeof val === 'number') return String(val);
  if (typeof val === 'string') return formatPseudoString(val);

  if (Array.isArray(val)) {
    if (val.length === 0) return '[]';
    const childIndent = indentUnit.repeat(depth + 1);
    const closeIndent = indentUnit.repeat(depth);
    const items = val.map(item => `${childIndent}${formatPseudoValue(item, indentUnit, depth + 1)}`);
    return `[\n${items.join(',\n')}\n${closeIndent}]`;
  }

  const entries = Object.entries(val);
  if (entries.length === 0) return '{}';
  const childIndent = indentUnit.repeat(depth + 1);
  const closeIndent = indentUnit.repeat(depth);
  const lines = entries.map(
    ([k, v]) => `${childIndent}${formatPseudoKey(k)} = ${formatPseudoValue(v, indentUnit, depth + 1)}`
  );
  return `{\n${lines.join(',\n')}\n${closeIndent}}`;
}

/**
 * Formats a Java Map.toString() / KV pseudo-JSON structure for readable multi-line {key = value} display
 * without converting it into JSON.
 *
 * @param {string|object|array} input
 * @param {number|string} [indent=2]
 * @returns {string}
 */
export function formatPseudoJson(input, indent = 2) {
  if (input === null || input === undefined) return '';
  let parsed;
  if (typeof input === 'string') {
    parsed = parsePseudoJson(input);
    if (parsed === null) return input;
  } else if (typeof input === 'object') {
    parsed = input;
  } else {
    return String(input);
  }

  const indentUnit = typeof indent === 'number'
    ? ' '.repeat(Math.max(0, indent))
    : (typeof indent === 'string' ? indent : '  ');
  return formatPseudoValue(parsed, indentUnit, 0);
}
