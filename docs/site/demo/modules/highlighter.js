/**
 * Zero-dependency JSON and XML syntax highlighter, formatters, and grammar detectors.
 * Produces color-coded HTML spans without external dependencies.
 */
import { escapeHtml } from './dom.js';

/**
 * Detects whether an input string is JSON, XML, or plain text.
 * Uses native JSON.parse and DOMParser with strict validity verification.
 *
 * @param {any} input
 * @returns {'json' | 'xml' | 'text'}
 */
export function detectFormat(input) {
  if (input === null || input === undefined) return 'text';
  if (typeof input === 'object') return 'json';
  if (typeof input !== 'string') return 'text';

  const trimmed = input.trim();
  if (!trimmed) return 'text';

  // Strict JSON detection
  if ((trimmed.startsWith('{') && trimmed.endsWith('}')) || (trimmed.startsWith('[') && trimmed.endsWith(']'))) {
    try {
      const parsed = JSON.parse(trimmed);
      if (typeof parsed === 'object' && parsed !== null) {
        return 'json';
      }
    } catch (_) {}
  }

  // Strict XML detection
  if (trimmed.startsWith('<') && trimmed.endsWith('>')) {
    try {
      if (typeof DOMParser !== 'undefined') {
        const doc = new DOMParser().parseFromString(trimmed, 'text/xml');
        const hasError = doc.getElementsByTagName('parsererror').length > 0;
        if (!hasError && doc.documentElement) {
          return 'xml';
        }
      } else if (trimmed.startsWith('<?xml') || /^<([a-zA-Z0-9_:-]+)[\s>][\s\S]*<\/\1>$/.test(trimmed)) {
        return 'xml';
      }
    } catch (_) {}
  }

  // Java Map toString() / Key-Value pseudo-JSON detection ({key=val} or [item1, item2])
  if ((trimmed.startsWith('{') && trimmed.endsWith('}')) || (trimmed.startsWith('[') && trimmed.endsWith(']'))) {
    if (trimmed.includes('=')) {
      try {
        const kv = parsePseudoJson(trimmed);
        if (kv && typeof kv === 'object') {
          return 'kv';
        }
      } catch (_) {}
    }
  }

  return 'text';
}

/**
 * Lenient Java Map toString() and key-value pseudo-JSON parser.
 * Handles {key=val, nested={a=1}} and {messages=null; deviations=[]; periods=[]}.
 *
 * @param {string} input
 * @returns {object|array|null}
 */
export function parsePseudoJson(input) {
  if (typeof input !== 'string') return null;
  const str = input.trim();
  if (!((str.startsWith('{') && str.endsWith('}')) || (str.startsWith('[') && str.endsWith(']')))) {
    return null;
  }

  let pos = 0;
  const len = str.length;

  function skipWhitespace() {
    while (pos < len && /\s/.test(str[pos])) pos++;
  }

  function parseQuoted(quote) {
    pos++; // skip quote
    let res = '';
    while (pos < len) {
      const c = str[pos++];
      if (c === '\\' && pos < len) {
        res += str[pos++];
      } else if (c === quote) {
        break;
      } else {
        res += c;
      }
    }
    return res;
  }

  function parseKey() {
    skipWhitespace();
    if (pos < len && (str[pos] === '"' || str[pos] === "'")) {
      return parseQuoted(str[pos]);
    }
    const start = pos;
    while (pos < len) {
      const c = str[pos];
      if (c === '=' || c === ':' || c === '}' || c === ',' || c === ';' || /\s/.test(c)) {
        break;
      }
      pos++;
    }
    return str.substring(start, pos).trim();
  }

  function parseLiteral() {
    const start = pos;
    while (pos < len) {
      const c = str[pos];
      if (c === ',' || c === ';' || c === '}' || c === ']' || c === '=' || c === ':') {
        break;
      }
      pos++;
    }
    const raw = str.substring(start, pos).trim();
    if (raw === 'null') return null;
    if (raw === 'true') return true;
    if (raw === 'false') return false;
    if (/^-?\d+$/.test(raw)) return parseInt(raw, 10);
    if (/^-?\d+\.\d+$/.test(raw)) return parseFloat(raw);
    return raw;
  }

  function parseValue() {
    skipWhitespace();
    if (pos >= len) return null;
    const c = str[pos];
    if (c === '{') return parseObject();
    if (c === '[') return parseArray();
    if (c === '"' || c === "'") return parseQuoted(c);
    return parseLiteral();
  }

  function parseObject() {
    pos++; // skip '{'
    const obj = {};
    while (pos < len) {
      skipWhitespace();
      if (pos >= len || str[pos] === '}') {
        if (pos < len) pos++; // skip '}'
        break;
      }
      const key = parseKey();
      skipWhitespace();
      if (pos < len && (str[pos] === '=' || str[pos] === ':')) {
        pos++;
      }
      const val = parseValue();
      if (key) obj[key] = val;
      skipWhitespace();
      if (pos < len && (str[pos] === ',' || str[pos] === ';')) {
        pos++;
      }
    }
    return obj;
  }

  function parseArray() {
    pos++; // skip '['
    const arr = [];
    while (pos < len) {
      skipWhitespace();
      if (pos >= len || str[pos] === ']') {
        if (pos < len) pos++; // skip ']'
        break;
      }
      const item = parseValue();
      arr.push(item);
      skipWhitespace();
      if (pos < len && (str[pos] === ',' || str[pos] === ';')) {
        pos++;
      }
    }
    return arr;
  }

  try {
    return parseValue();
  } catch (_) {
    return null;
  }
}

/**
 * Maximum character length of a payload to render with full tokenized HTML spans.
 * Payloads exceeding this threshold (~20 KB / ~500 lines) are escaped as plain text
 * to protect browser responsiveness, prevent DOM node explosion, and avoid UI thread freezes.
 */
export const MAX_HIGHLIGHT_PAYLOAD_CHARS = 20 * 1024;

/**
 * High-speed JSON syntax highlighter using regex tokenization.
 * Produces color-coded HTML spans.
 * Payloads larger than MAX_HIGHLIGHT_PAYLOAD_CHARS are safely escaped without span bloat.
 *
 * @param {any} input
 * @returns {string}
 */
export function highlightJson(input) {
  if (input === null || input === undefined) return '';
  let jsonStr;
  let isJson = false;

  if (typeof input === 'object') {
    try {
      jsonStr = JSON.stringify(input, null, 2);
      isJson = true;
    } catch (_) {
      jsonStr = String(input);
    }
  } else if (typeof input === 'string') {
    jsonStr = input;
    const trimmed = input.trim();
    if ((trimmed.startsWith('{') && trimmed.endsWith('}')) || (trimmed.startsWith('[') && trimmed.endsWith(']'))) {
      isJson = true;
    } else {
      try {
        JSON.parse(input);
        isJson = true;
      } catch (_) {
        if (/[{}[\]":]/.test(input)) {
          isJson = true;
        }
      }
    }
  } else {
    jsonStr = String(input);
  }

  if (!isJson) {
    return escapeHtml(jsonStr);
  }

  if (jsonStr.length > MAX_HIGHLIGHT_PAYLOAD_CHARS) {
    return escapeHtml(jsonStr);
  }

  return jsonStr.replace(
    /("(?:\\.|[^\\"])*")(\s*:)?|\b(true|false|null)\b|-?\d+(?:\.\d*)?(?:[eE][+\-]?\d+)?|[{}[\],]/g,
    function (match, strVal, colonVal) {
      if (strVal) {
        if (colonVal) {
          return `<span class="json-key">${escapeHtml(strVal)}</span><span class="json-punct">${colonVal}</span>`;
        }
        return `<span class="json-string">${escapeHtml(strVal)}</span>`;
      }
      if (match === 'true' || match === 'false') {
        return `<span class="json-boolean">${match}</span>`;
      }
      if (match === 'null') {
        return `<span class="json-null">${match}</span>`;
      }
      if (/^[{}[\],]$/.test(match)) {
        return `<span class="json-punct">${match}</span>`;
      }
      return `<span class="json-number">${match}</span>`;
    }
  );
}

/**
 * Pretty-prints an XML string with indentation.
 * Correctly preserves prologs, CDATA sections, comments, and inline text elements.
 *
 * @param {string} xmlStr
 * @param {string} indent
 * @returns {string}
 */
export function formatXml(xmlStr, indent = '  ') {
  if (typeof xmlStr !== 'string' || !xmlStr.trim()) return '';
  const trimmed = xmlStr.trim();
  let formatted = '';
  let depth = 0;

  // Split tags, comments, CDATA, declarations, and text content
  const tokens = trimmed.split(/(<!\[CDATA\[[\s\S]*?\]\]>|<!--[\s\S]*?-->|<\?[^?]*\?>|<\/?[^>]+>)/g);

  for (let i = 0; i < tokens.length; i++) {
    const token = tokens[i];
    if (!token) continue;
    const tTrim = token.trim();
    if (!tTrim) continue;

    if (tTrim.startsWith('<!--') || tTrim.startsWith('<?') || tTrim.startsWith('<![CDATA[')) {
      formatted += (formatted ? '\n' : '') + indent.repeat(depth) + tTrim;
    } else if (tTrim.startsWith('</')) {
      depth = Math.max(0, depth - 1);
      formatted += (formatted ? '\n' : '') + indent.repeat(depth) + tTrim;
    } else if (tTrim.startsWith('<') && tTrim.endsWith('/>')) {
      formatted += (formatted ? '\n' : '') + indent.repeat(depth) + tTrim;
    } else if (tTrim.startsWith('<')) {
      // Check if this tag has immediate text content followed directly by its closing tag
      let nextIdx = i + 1;
      while (nextIdx < tokens.length && !tokens[nextIdx].trim()) nextIdx++;
      let afterNextIdx = nextIdx + 1;
      while (afterNextIdx < tokens.length && !tokens[afterNextIdx].trim()) afterNextIdx++;

      const tagNameMatch = tTrim.match(/^<([a-zA-Z0-9_:-]+)/);
      const tagName = tagNameMatch ? tagNameMatch[1] : null;

      if (tagName && nextIdx < tokens.length && afterNextIdx < tokens.length) {
        const nextToken = tokens[nextIdx].trim();
        const afterToken = tokens[afterNextIdx].trim();
        if (!nextToken.startsWith('<') && afterToken === `</${tagName}>`) {
          formatted += (formatted ? '\n' : '') + indent.repeat(depth) + tTrim + nextToken + afterToken;
          i = afterNextIdx;
          continue;
        }
      }
      formatted += (formatted ? '\n' : '') + indent.repeat(depth) + tTrim;
      depth++;
    } else {
      formatted += (formatted ? '\n' : '') + indent.repeat(depth) + tTrim;
    }
  }

  return formatted || trimmed;
}

/**
 * Compacts/minifies an XML string by removing whitespace between tags,
 * while safely preserving CDATA sections intact.
 *
 * @param {string} xmlStr
 * @returns {string}
 */
export function minifyXml(xmlStr) {
  if (typeof xmlStr !== 'string' || !xmlStr.trim()) return '';
  const cdataParts = [];
  let placeholderXml = xmlStr.replace(/<!\[CDATA\[[\s\S]*?\]\]>/g, match => {
    cdataParts.push(match);
    return `___XML_CDATA_${cdataParts.length - 1}___`;
  });

  placeholderXml = placeholderXml
    .replace(/>\s+</g, '><')
    .replace(/\s+/g, ' ')
    .trim();

  return placeholderXml.replace(/___XML_CDATA_(\d+)___/g, (_, idx) => cdataParts[parseInt(idx, 10)]);
}

/**
 * High-speed XML syntax highlighter producing semantic HTML spans.
 * Tokenizes tags, namespace prefixes, attributes, values, prologs, CDATA, and comments.
 *
 * @param {string} xmlStr
 * @returns {string}
 */
export function highlightXml(xmlStr) {
  if (!xmlStr || typeof xmlStr !== 'string') return '';
  const formatted = formatXml(xmlStr);
  if (formatted.length > MAX_HIGHLIGHT_PAYLOAD_CHARS) {
    return escapeHtml(formatted);
  }

  return formatted.replace(
    /(<!\[CDATA\[[\s\S]*?\]\]>)|(<!--[\s\S]*?-->)|(<\?[^?]*\?>)|(<\/?[a-zA-Z0-9_:-]+)|(\/?>)|([a-zA-Z0-9_:-]+)=("[^"]*"|'[^']*')/g,
    function (match, cdata, comment, prolog, tagStart, tagEnd, attrName, attrVal) {
      if (cdata) return `<span class="xml-cdata">${escapeHtml(cdata)}</span>`;
      if (comment) return `<span class="xml-comment">${escapeHtml(comment)}</span>`;
      if (prolog) return `<span class="xml-prolog">${escapeHtml(prolog)}</span>`;
      if (tagStart) {
        const prefixIdx = tagStart.indexOf(':');
        if (prefixIdx > 0 && !tagStart.startsWith('</')) {
          const prefix = tagStart.substring(1, prefixIdx);
          const name = tagStart.substring(prefixIdx + 1);
          return `&lt;<span class="xml-prefix">${escapeHtml(prefix)}</span>:<span class="xml-tag">${escapeHtml(name)}</span>`;
        }
        if (prefixIdx > 0 && tagStart.startsWith('</')) {
          const prefix = tagStart.substring(2, prefixIdx);
          const name = tagStart.substring(prefixIdx + 1);
          return `&lt;/<span class="xml-prefix">${escapeHtml(prefix)}</span>:<span class="xml-tag">${escapeHtml(name)}</span>`;
        }
        return `<span class="xml-tag">${escapeHtml(tagStart)}</span>`;
      }
      if (tagEnd) return `<span class="xml-punct">${escapeHtml(tagEnd)}</span>`;
      if (attrName && attrVal) {
        return `<span class="xml-attr-name">${escapeHtml(attrName)}</span>=<span class="xml-attr-val">${escapeHtml(attrVal)}</span>`;
      }
      return escapeHtml(match);
    }
  );
}

/**
 * Unified payload highlighter: automatically dispatches to JSON or XML highlighter.
 *
 * @param {any} input
 * @returns {string}
 */
export function highlightPayload(input) {
  const format = detectFormat(input);
  if (format === 'json') {
    return highlightJson(input);
  }
  if (format === 'kv') {
    const parsed = parsePseudoJson(typeof input === 'string' ? input : String(input));
    if (parsed) return highlightJson(parsed);
  }
  if (format === 'xml') {
    return highlightXml(typeof input === 'string' ? input : String(input));
  }
  return escapeHtml(typeof input === 'string' ? input : String(input || ''));
}

/**
 * Unified payload pretty-printer: formats JSON, KV, or XML based on detected grammar.
 *
 * @param {any} input
 * @returns {string}
 */
export function formatPayload(input) {
  const format = detectFormat(input);
  if (format === 'json') {
    if (typeof input === 'object') return JSON.stringify(input, null, 2);
    try {
      return JSON.stringify(JSON.parse(input), null, 2);
    } catch (_) {
      return String(input);
    }
  }
  if (format === 'kv') {
    const parsed = parsePseudoJson(typeof input === 'string' ? input : String(input));
    if (parsed) return JSON.stringify(parsed, null, 2);
  }
  if (format === 'xml') {
    return formatXml(typeof input === 'string' ? input : String(input));
  }
  return typeof input === 'string' ? input : String(input || '');
}
