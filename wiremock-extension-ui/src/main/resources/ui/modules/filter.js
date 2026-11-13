/**
 * Pure, stateless text and query matching predicates for Stubs and Journal entries.
 * Supports free-text, deep attribute matching, and boolean (AND, OR) expressions.
 */

/**
 * Tokenizes and evaluates a query expression against a target matcher function.
 * Supports case-insensitive whitespace, AND, and OR operators.
 * AND binds tighter than OR: "A AND B OR C" -> (A && B) || C.
 * Multiple words separated by spaces without operator default to AND: "foo bar" -> foo AND bar.
 *
 * @param {string} query The raw search query
 * @param {function(string): boolean} matcherFn Callback returning true if term matches
 * @returns {boolean}
 */
export function evaluateQuery(query, matcherFn) {
  if (!query || !query.trim()) return true;

  // Split by top-level OR (case-insensitive with word boundaries)
  const orClauses = query.split(/\bOR\b/i);

  return orClauses.some(orClause => {
    // Within each OR branch, split by AND (case-insensitive) or treat space-separated tokens as AND
    const trimmedClause = orClause.trim();
    if (!trimmedClause) return false;

    // Split on explicit AND first
    const andClauses = trimmedClause.split(/\bAND\b/i);

    return andClauses.every(andPart => {
      const trimmedAnd = andPart.trim();
      if (!trimmedAnd) return true;

      // Also split multiple whitespace-separated tokens as implicit AND
      const tokens = trimmedAnd.split(/\s+/).filter(Boolean);
      return tokens.every(token => matcherFn(token.toLowerCase()));
    });
  });
}

/**
 * Extracts all searchable text values from an arbitrary object/value.
 * Converts strings, numbers, booleans, nested objects, and arrays into a lowercase string.
 *
 * @param {any} obj
 * @returns {string} Lowercase concatenated searchable string
 */
export function extractSearchableText(obj) {
  if (obj === null || obj === undefined) return '';
  if (typeof obj === 'string') return obj.toLowerCase();
  if (typeof obj === 'number' || typeof obj === 'boolean') return String(obj).toLowerCase();

  try {
    return JSON.stringify(obj).toLowerCase();
  } catch (e) {
    return '';
  }
}

/**
 * Evaluates whether a stub mapping matches a given query string (with AND / OR support).
 * Searches standard fields (method, URL, name, tags, project, entity) as well as
 * deep attributes (request matchers, headers, queryParameters, bodyPatterns, response body, metadata).
 *
 * @param {object} stub The stub mapping object
 * @param {string} query The raw filter string
 * @param {string} [stubUrl] Optional pre-extracted URL
 * @param {string} [stubProject] Optional project name
 * @returns {boolean}
 */
export function matchesStub(stub, query, stubUrl = '', stubProject = '') {
  if (!query || !query.trim()) return true;

  const method = (stub.request && stub.request.method ? stub.request.method : 'ANY').toLowerCase();
  const url = (stubUrl || '').toLowerCase();
  const name = (stub.name || '').toLowerCase();
  const tags = (stub.metadata && Array.isArray(stub.metadata.tags)) ? stub.metadata.tags.join(' ').toLowerCase() : '';
  const proj = (stubProject || '').toLowerCase();
  const entity = (stub.entity || '').toLowerCase();

  // Lazy cache deep text representation for multi-term queries
  let deepSearchable = null;

  return evaluateQuery(query, token => {
    // 1. Fast shallow check against common attributes
    if (
      method.includes(token) ||
      url.includes(token) ||
      name.includes(token) ||
      tags.includes(token) ||
      proj.includes(token) ||
      entity.includes(token)
    ) {
      return true;
    }

    // 2. Deep attribute & free-text JSON check (headers, queryParams, bodyPatterns, metadata)
    if (deepSearchable === null) {
      deepSearchable = extractSearchableText(stub);
    }
    return deepSearchable.includes(token);
  });
}

function getHeaderFirstValue(headers, name) {
  if (!headers) return '';
  const lower = name.toLowerCase();
  for (const [k, v] of Object.entries(headers)) {
    if (k.toLowerCase() === lower) {
      if (Array.isArray(v)) {
        return v.length > 0 && v[0] !== null && v[0] !== undefined ? String(v[0]) : '';
      }
      return v !== null && v !== undefined ? String(v) : '';
    }
  }
  return '';
}

/**
 * Normalizes a raw IP address or host token by stripping ports, IPv6 brackets/zones, and unknown placeholders.
 *
 * @param {string} raw
 * @returns {string}
 */
export function normalizeIpAddress(raw) {
  if (raw === null || raw === undefined) return '';
  let trimmed = String(raw).trim();
  if (!trimmed || trimmed.toLowerCase() === 'unknown') return '';

  if (trimmed.startsWith('[')) {
    const closeIdx = trimmed.indexOf(']');
    if (closeIdx > 1) {
      trimmed = trimmed.substring(1, closeIdx);
    } else {
      return '';
    }
  } else if (trimmed.includes('.')) {
    const parts = trimmed.split(':');
    if (parts.length === 2) {
      trimmed = parts[0];
    }
  }

  const zoneIdx = trimmed.indexOf('%');
  if (zoneIdx > 0) {
    trimmed = trimmed.substring(0, zoneIdx);
  }

  if (trimmed === '0:0:0:0:0:0:0:1') {
    trimmed = '::1';
  }
  return trimmed;
}

/**
 * Returns true if the normalized IP or host represents loopback/localhost.
 *
 * @param {string} ip
 * @returns {boolean}
 */
export function isLoopbackIp(ip) {
  if (!ip) return false;
  const lower = String(ip).toLowerCase();
  return lower === '127.0.0.1' || lower === '::1' || lower === '0:0:0:0:0:0:0:1' || lower === 'localhost';
}

/**
 * Extracts caller source IP, resolved hostname, and display labels from a journal entry.
 * Supports both enriched `/__admin/ui/api/requests` payloads and vanilla `/__admin/requests` fallback.
 *
 * @param {object} req
 * @returns {{ ip: string, host: string, shortHost: string, displayPrimary: string, displaySecondary: string, tooltip: string }}
 */
export function extractRequestSource(req) {
  const reqObj = (req && req.request) || {};
  const headers = reqObj.headers || {};

  let ip = normalizeIpAddress(req && req.sourceIp);
  if (!ip) {
    const xff = getHeaderFirstValue(headers, 'x-forwarded-for');
    if (xff) {
      ip = normalizeIpAddress(xff.split(',')[0]);
    }
  }
  if (!ip) {
    ip = normalizeIpAddress(getHeaderFirstValue(headers, 'x-real-ip'));
  }
  if (!ip) {
    ip = normalizeIpAddress(reqObj.clientIp);
  }

  let host = req && req.sourceHost ? String(req.sourceHost).trim() : '';
  if (!host && isLoopbackIp(ip)) {
    host = 'localhost';
  }

  const shortHost = host ? host.split('.')[0] : '';
  const displayPrimary = shortHost || ip || '-';
  const displaySecondary = (shortHost && ip) ? ip : '';
  let tooltip = 'Unknown source';
  if (host && ip) {
    tooltip = `${host} (${ip})`;
  } else if (host || ip) {
    tooltip = host || ip;
  }

  return {
    ip,
    host,
    shortHost,
    displayPrimary,
    displaySecondary,
    tooltip
  };
}

function matchPrefixedToken(token, prefixes) {
  for (const prefix of prefixes) {
    if (token.startsWith(prefix)) {
      return token.substring(prefix.length).trim();
    }
  }
  return null;
}

/**
 * Evaluates whether a journal entry matches a given query string (with AND / OR support).
 * Searches standard fields (source IP/host, method, URL, status, stubName, entity) as well as
 * deep attributes (request headers, query params, request body, response body, response headers).
 *
 * @param {object} req The journal request serve event
 * @param {string} query The raw filter string
 * @param {string} [statefulEntity] Optional extracted stateful entity header
 * @param {string} [resolution] Optional resolution verdict string
 * @returns {boolean}
 */
export function matchesJournalEntry(req, query, statefulEntity = '', resolution = '') {
  if (!query || !query.trim()) return true;

  const src = extractRequestSource(req);
  const srcIp = (src.ip || '').toLowerCase();
  const srcHost = (src.host || '').toLowerCase();
  const srcShort = (src.shortHost || '').toLowerCase();

  const method = (req.request && req.request.method ? req.request.method : '').toLowerCase();
  const url = (req.request && req.request.url ? req.request.url : '').toLowerCase();
  const status = String((req.response && req.response.status) || (req.responseDefinition && req.responseDefinition.status) || '');
  const stubName = (req.stubMapping && req.stubMapping.name ? req.stubMapping.name : '').toLowerCase();
  const entity = (statefulEntity || '').toLowerCase();
  const res = (resolution || '').toLowerCase();

  // Lazy cache deep text representation for multi-term queries
  let deepSearchable = null;

  return evaluateQuery(query, token => {
    // 0. Prefixed source filters: src:, src=, source:, source=, ip:, ip=, host:, host=
    const srcVal = matchPrefixedToken(token, ['src:', 'src=', 'source:', 'source=']);
    if (srcVal !== null) {
      return Boolean(srcVal) && (srcIp.includes(srcVal) || srcHost.includes(srcVal) || srcShort.includes(srcVal));
    }
    const ipVal = matchPrefixedToken(token, ['ip:', 'ip=']);
    if (ipVal !== null) {
      return Boolean(ipVal) && srcIp.includes(ipVal);
    }
    const hostVal = matchPrefixedToken(token, ['host:', 'host=']);
    if (hostVal !== null) {
      return Boolean(hostVal) && (srcHost.includes(hostVal) || srcShort.includes(hostVal));
    }

    // 1. Fast shallow check against common row attributes
    if (
      (srcIp && srcIp.includes(token)) ||
      (srcHost && srcHost.includes(token)) ||
      method.includes(token) ||
      url.includes(token) ||
      status.includes(token) ||
      stubName.includes(token) ||
      entity.includes(token) ||
      res.includes(token)
    ) {
      return true;
    }

    // 2. Deep check across request headers, query, body, and response body
    if (deepSearchable === null) {
      deepSearchable = extractSearchableText(req);
    }
    return deepSearchable.includes(token);
  });
}
