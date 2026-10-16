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

/**
 * Evaluates whether a journal entry matches a given query string (with AND / OR support).
 * Searches standard fields (method, URL, status, stubName, entity) as well as
 * deep attributes (request headers, query params, request body, response body, response headers).
 *
 * @param {object} req The journal request serve event
 * @param {string} query The raw filter string
 * @param {string} [statefulEntity] Optional extracted stateful entity header
 * @returns {boolean}
 */
export function matchesJournalEntry(req, query, statefulEntity = '', resolution = '') {
  if (!query || !query.trim()) return true;

  const method = (req.request && req.request.method ? req.request.method : '').toLowerCase();
  const url = (req.request && req.request.url ? req.request.url : '').toLowerCase();
  const status = String((req.response && req.response.status) || (req.responseDefinition && req.responseDefinition.status) || '');
  const stubName = (req.stubMapping && req.stubMapping.name ? req.stubMapping.name : '').toLowerCase();
  const entity = (statefulEntity || '').toLowerCase();
  const res = (resolution || '').toLowerCase();

  // Lazy cache deep text representation for multi-term queries
  let deepSearchable = null;

  return evaluateQuery(query, token => {
    // 1. Fast shallow check against common row attributes
    if (
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
