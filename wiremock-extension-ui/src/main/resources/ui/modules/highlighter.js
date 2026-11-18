/**
 * Zero-dependency JSON, XML, and KV syntax highlighter, formatters, validators, and offset tokenizer.
 * Produces color-coded HTML spans and exact character-offset token spans without external dependencies.
 */
import { escapeHtml } from './dom.js';
import { parsePseudoJson, formatPseudoJson } from './pseudo-json.js';

export { parsePseudoJson, formatPseudoJson };

/**
 * Maximum character length of a payload to highlight with CSS Highlight ranges or legacy backdrop check.
 * Payloads exceeding this threshold (50 KB) degrade cleanly to plain escaped text.
 */
export const MAX_HIGHLIGHT_PAYLOAD_CHARS = 50 * 1024;

/**
 * Maximum character length for HTML &lt;span&gt;-based highlighting in read-only viewers.
 * Caps DOM span expansion (16 KB) so multi-viewer tabs (Request/Sent/Response) stay well under DOM budgets.
 */
export const MAX_DOM_SPAN_HIGHLIGHT_CHARS = 16 * 1024;

function isBalancedXmlFallback(trimmed) {
  const stack = [];
  let i = 0;
  const len = trimmed.length;
  while (i < len) {
    const lt = trimmed.indexOf('<', i);
    if (lt < 0) break;
    if (trimmed.startsWith('<!--', lt)) {
      const end = trimmed.indexOf('-->', lt + 4);
      if (end < 0) return false;
      i = end + 3;
      continue;
    }
    if (trimmed.startsWith('<![CDATA[', lt)) {
      const end = trimmed.indexOf(']]>', lt + 9);
      if (end < 0) return false;
      i = end + 3;
      continue;
    }
    if (trimmed.startsWith('<?', lt)) {
      const end = trimmed.indexOf('?>', lt + 2);
      if (end < 0) return false;
      i = end + 2;
      continue;
    }
    if (trimmed.startsWith('<!', lt)) {
      const end = trimmed.indexOf('>', lt + 2);
      if (end < 0) return false;
      i = end + 1;
      continue;
    }
    const gt = findXmlTagClose(trimmed, lt + 1);
    if (gt < 0) return false;
    const rawTag = trimmed.slice(lt + 1, gt).trim();
    if (!rawTag) return false;
    if (rawTag.startsWith('/')) {
      const closeName = rawTag.slice(1).trim();
      if (stack.length === 0 || stack[stack.length - 1] !== closeName) return false;
      stack.pop();
    } else if (!rawTag.endsWith('/')) {
      const m = rawTag.match(/^([a-zA-Z0-9_:-]+)/);
      if (!m) return false;
      stack.push(m[1]);
    }
    i = gt + 1;
  }
  return stack.length === 0;
}

function findXmlTagClose(str, startIdx) {
  let quote = null;
  for (let i = startIdx; i < str.length; i++) {
    const c = str[i];
    if (quote) {
      if (c === quote) quote = null;
    } else if (c === '"' || c === "'") {
      quote = c;
    } else if (c === '>') {
      return i;
    } else if (c === '<') {
      return -1;
    }
  }
  return -1;
}

/**
 * Detects whether an input string is JSON, XML, KV pseudo-JSON, or plain text.
 *
 * @param {any} input
 * @returns {'json' | 'xml' | 'kv' | 'text'}
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
      } else if (isBalancedXmlFallback(trimmed)) {
        return 'xml';
      }
    } catch (_) {}
  }

  // Java Map toString() / Key-Value pseudo-JSON detection ({key=val} or [item1, item2])
  if ((trimmed.startsWith('{') && trimmed.endsWith('}')) || (trimmed.startsWith('[') && trimmed.endsWith(']'))) {
    if (trimmed.includes('=')) {
      const kv = parsePseudoJson(trimmed);
      if (kv && typeof kv === 'object') {
        return 'kv';
      }
    }
  }

  return 'text';
}

/**
 * Validates payload syntax for JSON, XML, or KV.
 *
 * @param {string} text
 * @param {'json' | 'xml' | 'kv' | string} format
 * @returns {{ valid: boolean, errorMessage?: string }}
 */
export function validatePayload(text, format) {
  if (typeof text !== 'string') {
    return { valid: false, errorMessage: 'Payload is not a string' };
  }
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
    if (!trimmed.startsWith('<') || !trimmed.endsWith('>')) {
      return { valid: false, errorMessage: 'XML must be enclosed in tags' };
    }
    if (!isBalancedXmlFallback(trimmed)) {
      return { valid: false, errorMessage: 'XML Syntax Error: mismatched or unclosed tags' };
    }
    return { valid: true };
  }

  if (format === 'kv') {
    const parsed = parsePseudoJson(trimmed);
    if (!parsed) {
      return { valid: false, errorMessage: 'KV Syntax Error: invalid key-value structure' };
    }
    return { valid: true };
  }

  return { valid: true };
}

/**
 * Pure character-offset token scanner for JSON and XML.
 * Returns empty array if text is empty, exceeds MAX_HIGHLIGHT_PAYLOAD_CHARS, or format is unsupported.
 *
 * @param {string} text
 * @param {'json' | 'xml'} format
 * @returns {Array<{ type: 'key'|'string'|'number'|'bool'|'null'|'punct'|'xml-tag'|'xml-attr'|'xml-val'|'comment', start: number, end: number }>}
 */
export function tokenizeOffsets(text, format) {
  if (!text || typeof text !== 'string' || text.length > MAX_HIGHLIGHT_PAYLOAD_CHARS) {
    return [];
  }
  if (format !== 'json' && format !== 'xml') {
    return [];
  }

  if (format === 'json') {
    return tokenizeJsonOffsets(text);
  }
  return tokenizeXmlOffsets(text);
}

function tokenizeJsonOffsets(text) {
  const tokens = [];
  const len = text.length;
  let i = 0;

  while (i < len) {
    const c = text[i];

    // Skip whitespace
    if (c === ' ' || c === '\t' || c === '\n' || c === '\r') {
      i++;
      continue;
    }

    // Punctuation
    if (c === '{' || c === '}' || c === '[' || c === ']' || c === ',' || c === ':') {
      tokens.push({ type: 'punct', start: i, end: i + 1 });
      i++;
      continue;
    }

    // String literal (key or string value)
    if (c === '"') {
      const start = i;
      i++;
      while (i < len) {
        const sc = text[i];
        if (sc === '\\') {
          i += (i + 1 < len) ? 2 : 1;
        } else if (sc === '"') {
          i++;
          break;
        } else {
          i++;
        }
      }
      const end = i;
      let look = end;
      while (look < len && (text[look] === ' ' || text[look] === '\t' || text[look] === '\n' || text[look] === '\r')) {
        look++;
      }
      const type = (look < len && text[look] === ':') ? 'key' : 'string';
      tokens.push({ type, start, end });
      continue;
    }

    // Number literal
    if ((c >= '0' && c <= '9') || (c === '-' && i + 1 < len && text[i + 1] >= '0' && text[i + 1] <= '9')) {
      const start = i;
      if (text[i] === '-') i++;
      while (i < len && text[i] >= '0' && text[i] <= '9') i++;
      if (i < len && text[i] === '.') {
        i++;
        while (i < len && text[i] >= '0' && text[i] <= '9') i++;
      }
      if (i < len && (text[i] === 'e' || text[i] === 'E')) {
        i++;
        if (i < len && (text[i] === '+' || text[i] === '-')) i++;
        while (i < len && text[i] >= '0' && text[i] <= '9') i++;
      }
      tokens.push({ type: 'number', start, end: i });
      continue;
    }

    // Identifiers / keywords (true, false, null)
    if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c === '_' || c === '$') {
      const start = i;
      i++;
      while (i < len) {
        const ch = text[i];
        if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch === '_' || ch === '$') {
          i++;
        } else {
          break;
        }
      }
      const word = text.slice(start, i);
      if (word === 'true' || word === 'false') {
        tokens.push({ type: 'bool', start, end: i });
      } else if (word === 'null') {
        tokens.push({ type: 'null', start, end: i });
      }
      continue;
    }

    // Other unknown character
    i++;
  }

  return tokens;
}

function isXmlNameChar(ch) {
  return (ch >= 'a' && ch <= 'z')
    || (ch >= 'A' && ch <= 'Z')
    || (ch >= '0' && ch <= '9')
    || ch === '_' || ch === '-' || ch === ':' || ch === '.';
}

function tokenizeXmlOffsets(text) {
  const tokens = [];
  const len = text.length;
  let i = 0;

  while (i < len) {
    const lt = text.indexOf('<', i);
    if (lt < 0) break;
    i = lt;

    // Comment <!-- ... -->
    if (text.startsWith('<!--', i)) {
      const close = text.indexOf('-->', i + 4);
      const end = close >= 0 ? close + 3 : len;
      tokens.push({ type: 'comment', start: i, end });
      i = end;
      continue;
    }

    // CDATA <![CDATA[ ... ]]>
    if (text.startsWith('<![CDATA[', i)) {
      tokens.push({ type: 'punct', start: i, end: i + 9 });
      const close = text.indexOf(']]>', i + 9);
      const bodyEnd = close >= 0 ? close : len;
      if (bodyEnd > i + 9) {
        tokens.push({ type: 'string', start: i + 9, end: bodyEnd });
      }
      if (close >= 0) {
        tokens.push({ type: 'punct', start: close, end: close + 3 });
        i = close + 3;
      } else {
        i = len;
      }
      continue;
    }

    // Processing instruction / Prolog <?...?>
    if (text.startsWith('<?', i)) {
      tokens.push({ type: 'punct', start: i, end: i + 2 });
      i += 2;
      if (i < len && isXmlNameChar(text[i])) {
        const tStart = i;
        while (i < len && isXmlNameChar(text[i])) i++;
        tokens.push({ type: 'xml-tag', start: tStart, end: i });
      }
      while (i < len) {
        if (text.startsWith('?>', i)) {
          tokens.push({ type: 'punct', start: i, end: i + 2 });
          i += 2;
          break;
        }
        const ch = text[i];
        if (ch === ' ' || ch === '\t' || ch === '\n' || ch === '\r') {
          i++;
        } else if (ch === '=') {
          tokens.push({ type: 'punct', start: i, end: i + 1 });
          i++;
        } else if (ch === '"' || ch === "'") {
          const q = ch;
          const vStart = i;
          i++;
          while (i < len && text[i] !== q) i++;
          if (i < len && text[i] === q) i++;
          tokens.push({ type: 'xml-val', start: vStart, end: i });
        } else if (isXmlNameChar(ch)) {
          const aStart = i;
          while (i < len && isXmlNameChar(text[i])) i++;
          tokens.push({ type: 'xml-attr', start: aStart, end: i });
        } else if (ch === '<') {
          break;
        } else {
          i++;
        }
      }
      continue;
    }

    // DOCTYPE / declaration <! ... >
    if (text.startsWith('<!', i)) {
      const close = text.indexOf('>', i + 2);
      const end = close >= 0 ? close + 1 : len;
      tokens.push({ type: 'comment', start: i, end });
      i = end;
      continue;
    }

    // Start or closing tag
    const isClosing = text.startsWith('</', i);
    const openEnd = isClosing ? i + 2 : i + 1;
    if (openEnd < len && isXmlNameChar(text[openEnd])) {
      tokens.push({ type: 'punct', start: i, end: openEnd });
      i = openEnd;
      const tagStart = i;
      while (i < len && isXmlNameChar(text[i])) i++;
      tokens.push({ type: 'xml-tag', start: tagStart, end: i });

      while (i < len) {
        const ch = text[i];
        if (ch === ' ' || ch === '\t' || ch === '\n' || ch === '\r') {
          i++;
        } else if (text.startsWith('/>', i)) {
          tokens.push({ type: 'punct', start: i, end: i + 2 });
          i += 2;
          break;
        } else if (ch === '>') {
          tokens.push({ type: 'punct', start: i, end: i + 1 });
          i++;
          break;
        } else if (ch === '=') {
          tokens.push({ type: 'punct', start: i, end: i + 1 });
          i++;
        } else if (ch === '"' || ch === "'") {
          const q = ch;
          const vStart = i;
          i++;
          while (i < len && text[i] !== q) i++;
          if (i < len && text[i] === q) i++;
          tokens.push({ type: 'xml-val', start: vStart, end: i });
        } else if (isXmlNameChar(ch)) {
          const aStart = i;
          while (i < len && isXmlNameChar(text[i])) i++;
          tokens.push({ type: 'xml-attr', start: aStart, end: i });
        } else if (ch === '<') {
          break;
        } else {
          i++;
        }
      }
      continue;
    }

    i++;
  }

  return tokens;
}

const JSON_CLASS_MAP = {
  key: 'json-key',
  string: 'json-string',
  number: 'json-number',
  bool: 'json-boolean',
  null: 'json-null',
  punct: 'json-punct'
};

/**
 * High-speed JSON syntax highlighter producing semantic HTML spans.
 * Payloads larger than MAX_HIGHLIGHT_PAYLOAD_CHARS are safely escaped without span bloat.
 *
 * @param {any} input
 * @param {boolean} [forceJson=false] Treat string fragments as JSON without full-document heuristics
 * @returns {string}
 */
export function highlightJson(input, forceJson = false) {
  if (input === null || input === undefined) return '';
  let jsonStr;
  let isJson = forceJson;

  if (typeof input === 'object') {
    try {
      jsonStr = JSON.stringify(input, null, 2);
      isJson = true;
    } catch (_) {
      jsonStr = String(input);
    }
  } else if (typeof input === 'string') {
    jsonStr = input;
    if (!isJson) {
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
    }
  } else {
    jsonStr = String(input);
  }

  if (!isJson || jsonStr.length > MAX_DOM_SPAN_HIGHLIGHT_CHARS) {
    return escapeHtml(jsonStr);
  }

  const tokens = tokenizeOffsets(jsonStr, 'json');
  if (tokens.length === 0) {
    return escapeHtml(jsonStr);
  }

  let html = '';
  let cursor = 0;
  for (const tok of tokens) {
    if (tok.start > cursor) {
      html += escapeHtml(jsonStr.slice(cursor, tok.start));
    }
    const cls = JSON_CLASS_MAP[tok.type];
    const slice = escapeHtml(jsonStr.slice(tok.start, tok.end));
    html += cls ? `<span class="${cls}">${slice}</span>` : slice;
    cursor = tok.end;
  }
  if (cursor < jsonStr.length) {
    html += escapeHtml(jsonStr.slice(cursor));
  }
  return html;
}

/**
 * Pretty-prints an XML string with indentation.
 * Correctly preserves prologs, CDATA sections, comments, and inline text elements.
 *
 * @param {string} xmlStr
 * @param {string} [indent='  ']
 * @returns {string}
 */
export function formatXml(xmlStr, indent = '  ') {
  if (typeof xmlStr !== 'string' || !xmlStr.trim()) return '';
  const trimmed = xmlStr.trim();
  let formatted = '';
  let depth = 0;

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

function renderXmlTagNameHtml(tagText, isClosing) {
  const prefixIdx = tagText.indexOf(':');
  if (prefixIdx > 0) {
    const prefix = tagText.slice(0, prefixIdx);
    const localName = tagText.slice(prefixIdx + 1);
    return `${isClosing ? '&lt;/' : '&lt;'}<span class="xml-prefix">${escapeHtml(prefix)}</span>:<span class="xml-tag">${escapeHtml(localName)}</span>`;
  }
  return `<span class="xml-tag">${escapeHtml((isClosing ? '</' : '<') + tagText)}</span>`;
}

/**
 * High-speed XML syntax highlighter producing semantic HTML spans without altering whitespace.
 * Safely escapes all character content and text slices.
 *
 * @param {string} xmlStr
 * @returns {string}
 */
export function highlightXml(xmlStr) {
  if (!xmlStr || typeof xmlStr !== 'string') return '';
  if (xmlStr.length > MAX_DOM_SPAN_HIGHLIGHT_CHARS) {
    return escapeHtml(xmlStr);
  }

  const len = xmlStr.length;
  let html = '';
  let i = 0;

  while (i < len) {
    const lt = xmlStr.indexOf('<', i);
    if (lt < 0) {
      html += escapeHtml(xmlStr.slice(i));
      break;
    }
    if (lt > i) {
      html += escapeHtml(xmlStr.slice(i, lt));
      i = lt;
    }

    if (xmlStr.startsWith('<!--', i)) {
      const close = xmlStr.indexOf('-->', i + 4);
      const end = close >= 0 ? close + 3 : len;
      html += `<span class="xml-comment">${escapeHtml(xmlStr.slice(i, end))}</span>`;
      i = end;
      continue;
    }

    if (xmlStr.startsWith('<![CDATA[', i)) {
      const close = xmlStr.indexOf(']]>', i + 9);
      const end = close >= 0 ? close + 3 : len;
      html += `<span class="xml-cdata">${escapeHtml(xmlStr.slice(i, end))}</span>`;
      i = end;
      continue;
    }

    if (xmlStr.startsWith('<?', i)) {
      const close = xmlStr.indexOf('?>', i + 2);
      const end = close >= 0 ? close + 2 : len;
      html += `<span class="xml-prolog">${escapeHtml(xmlStr.slice(i, end))}</span>`;
      i = end;
      continue;
    }

    if (xmlStr.startsWith('<!', i)) {
      const close = xmlStr.indexOf('>', i + 2);
      const end = close >= 0 ? close + 1 : len;
      html += `<span class="xml-comment">${escapeHtml(xmlStr.slice(i, end))}</span>`;
      i = end;
      continue;
    }

    const isClosing = xmlStr.startsWith('</', i);
    const nameStart = isClosing ? i + 2 : i + 1;
    if (nameStart < len && isXmlNameChar(xmlStr[nameStart])) {
      let nameEnd = nameStart;
      while (nameEnd < len && isXmlNameChar(xmlStr[nameEnd])) nameEnd++;
      html += renderXmlTagNameHtml(xmlStr.slice(nameStart, nameEnd), isClosing);
      i = nameEnd;

      while (i < len) {
        const ch = xmlStr[i];
        if (ch === ' ' || ch === '\t' || ch === '\n' || ch === '\r') {
          html += ch;
          i++;
        } else if (xmlStr.startsWith('/>', i)) {
          html += `<span class="xml-punct">/&gt;</span>`;
          i += 2;
          break;
        } else if (ch === '>') {
          html += `<span class="xml-punct">&gt;</span>`;
          i++;
          break;
        } else if (ch === '=') {
          html += '=';
          i++;
        } else if (ch === '"' || ch === "'") {
          const q = ch;
          const vStart = i;
          i++;
          while (i < len && xmlStr[i] !== q) i++;
          if (i < len && xmlStr[i] === q) i++;
          html += `<span class="xml-attr-val">${escapeHtml(xmlStr.slice(vStart, i))}</span>`;
        } else if (isXmlNameChar(ch)) {
          const aStart = i;
          while (i < len && isXmlNameChar(xmlStr[i])) i++;
          const attrText = xmlStr.slice(aStart, i);
          if (i < len && xmlStr[i] === '=') {
            html += `<span class="xml-attr-name">${escapeHtml(attrText)}</span>`;
          } else {
            html += escapeHtml(attrText);
          }
        } else if (ch === '<') {
          break;
        } else {
          html += escapeHtml(ch);
          i++;
        }
      }
      continue;
    }

    html += escapeHtml(xmlStr[i]);
    i++;
  }

  return html;
}

function highlightKv(kvStr) {
  if (!kvStr || typeof kvStr !== 'string') return '';
  if (kvStr.length > MAX_DOM_SPAN_HIGHLIGHT_CHARS) {
    return escapeHtml(kvStr);
  }
  const len = kvStr.length;
  let html = '';
  let i = 0;
  let expectKey = false;
  const stack = [];

  while (i < len) {
    const ch = kvStr[i];
    if (ch === ' ' || ch === '\t' || ch === '\n' || ch === '\r') {
      html += ch;
      i++;
      continue;
    }
    if (ch === '{') {
      html += '<span class="json-punct">{</span>';
      stack.push('{');
      expectKey = true;
      i++;
      continue;
    }
    if (ch === '}') {
      html += '<span class="json-punct">}</span>';
      stack.pop();
      expectKey = false;
      i++;
      continue;
    }
    if (ch === '[') {
      html += '<span class="json-punct">[</span>';
      stack.push('[');
      expectKey = false;
      i++;
      continue;
    }
    if (ch === ']') {
      html += '<span class="json-punct">]</span>';
      stack.pop();
      expectKey = false;
      i++;
      continue;
    }
    if (ch === ',' || ch === ';') {
      html += `<span class="json-punct">${ch}</span>`;
      expectKey = stack[stack.length - 1] === '{';
      i++;
      continue;
    }
    if (ch === '=' || ch === ':') {
      html += `<span class="json-punct">${ch}</span>`;
      expectKey = false;
      i++;
      continue;
    }
    if (ch === '"' || ch === "'") {
      const start = i;
      const q = ch;
      i++;
      while (i < len) {
        if (kvStr[i] === '\\') {
          i += (i + 1 < len) ? 2 : 1;
        } else if (kvStr[i] === q) {
          i++;
          break;
        } else {
          i++;
        }
      }
      const cls = expectKey ? 'json-key' : 'json-string';
      html += `<span class="${cls}">${escapeHtml(kvStr.slice(start, i))}</span>`;
      expectKey = false;
      continue;
    }
    if (expectKey) {
      const start = i;
      while (i < len && !/[=:{}\[\],;\s]/.test(kvStr[i])) i++;
      html += `<span class="json-key">${escapeHtml(kvStr.slice(start, i))}</span>`;
      expectKey = false;
      continue;
    }
    // Unquoted scalar value
    const start = i;
    while (i < len && !/[{}\[\],;]/.test(kvStr[i])) i++;
    const rawSlice = kvStr.slice(start, i);
    const trimmed = rawSlice.trim();
    const leadWs = rawSlice.slice(0, rawSlice.length - rawSlice.trimStart().length);
    const trailWs = rawSlice.slice(rawSlice.trimEnd().length);
    if (trimmed === 'null') {
      html += `${leadWs}<span class="json-null">null</span>${trailWs}`;
    } else if (trimmed === 'true' || trimmed === 'false') {
      html += `${leadWs}<span class="json-boolean">${trimmed}</span>${trailWs}`;
    } else if (/^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?$/.test(trimmed)) {
      html += `${leadWs}<span class="json-number">${escapeHtml(trimmed)}</span>${trailWs}`;
    } else {
      html += `${leadWs}<span class="json-string">${escapeHtml(trimmed)}</span>${trailWs}`;
    }
  }
  return html;
}

/**
 * Unified payload highlighter: dispatches to JSON, KV, XML, or plain text escaping.
 *
 * @param {any} input
 * @param {'json'|'xml'|'kv'|'text'|null} [format=null] Optional explicit grammar override
 * @returns {string}
 */
export function highlightPayload(input, format = null) {
  const fmt = format || detectFormat(input);
  if (fmt === 'json') {
    return highlightJson(input, format === 'json');
  }
  if (fmt === 'kv') {
    const str = typeof input === 'string' ? input : formatPseudoJson(input);
    return highlightKv(str);
  }
  if (fmt === 'xml') {
    return highlightXml(typeof input === 'string' ? input : String(input ?? ''));
  }
  return escapeHtml(typeof input === 'string' ? input : String(input ?? ''));
}

/**
 * Unified payload pretty-printer: formats JSON, KV, or XML based on detected grammar.
 * Preserves KV {k = v} notation without converting to JSON.
 *
 * @param {any} input
 * @returns {string}
 */
export function formatPayload(input) {
  const format = detectFormat(input);
  if (format === 'json') {
    if (typeof input === 'object' && input !== null) return JSON.stringify(input, null, 2);
    try {
      return JSON.stringify(JSON.parse(input), null, 2);
    } catch (_) {
      return String(input);
    }
  }
  if (format === 'kv') {
    return formatPseudoJson(typeof input === 'string' ? input : String(input));
  }
  if (format === 'xml') {
    return formatXml(typeof input === 'string' ? input : String(input));
  }
  return typeof input === 'string' ? input : String(input ?? '');
}
