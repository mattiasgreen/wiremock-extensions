/**
 * High-speed JSON syntax highlighter using regex tokenization.
 * Produces color-coded HTML spans without external dependencies.
 */
import { escapeHtml } from './dom.js';

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
    const trimmed = input.trim();
    if ((trimmed.startsWith('{') && trimmed.endsWith('}')) || (trimmed.startsWith('[') && trimmed.endsWith(']'))) {
      try {
        const parsed = JSON.parse(input);
        jsonStr = JSON.stringify(parsed, null, 2);
        isJson = true;
      } catch (_) {
        jsonStr = input;
      }
    } else {
      jsonStr = input;
    }
  } else {
    jsonStr = String(input);
  }

  if (!isJson) {
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
