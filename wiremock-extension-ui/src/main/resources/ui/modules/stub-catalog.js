/**
 * Canonical frontend domain module for WireMock stub management.
 *
 * Encapsulates:
 * - Native priority-tier classification (5 = active stub, 10 = proxy, 50 = offline guard, 100+ = parked/disabled).
 * - Authoritative store for WireMock mappings, selection state, and project derivation.
 * - Multi-token boolean query evaluation and status filtering.
 * - Pure request URL, cURL, and Java WireMock DSL generators.
 */
import { matchesStub } from './filter.js';

export const PRIORITY_ACTIVE_STUB = 5;
export const PRIORITY_ACTIVE_PROXY = 10;
export const PRIORITY_OFFLINE_GUARD = 50;
export const PRIORITY_PARKED = 100;

let catalogOriginResolver = () =>
  (typeof window !== 'undefined' && window.location && window.location.origin && window.location.origin !== 'null'
    ? window.location.origin
    : '') || 'http://localhost:8080';

export function setCatalogOriginResolver(fn) {
  if (typeof fn === 'function') {
    catalogOriginResolver = fn;
  }
}

function getCatalogOrigin(origin) {
  if (origin) return String(origin).replace(/\/+$/, '');
  try {
    const resolved = catalogOriginResolver ? catalogOriginResolver() : '';
    if (resolved) return String(resolved).replace(/\/+$/, '');
  } catch {
    // Ignore resolver failures and fall back to localhost
  }
  return 'http://localhost:8080';
}

export function isStubParked(stub) {
  return Boolean(stub && typeof stub.priority === 'number' && stub.priority >= PRIORITY_PARKED);
}

export function isProxyStub(stub) {
  return Boolean(
    (stub && stub.response && stub.response.proxyBaseUrl) ||
      (stub && stub.metadata && String(stub.metadata.mode || '').toLowerCase() === 'proxy')
  );
}

export function isOfflineGuardStub(stub) {
  return Boolean(stub && stub.metadata && String(stub.metadata.role || '').toLowerCase() === 'offline-guard');
}

export function getStubUrl(req) {
  if (!req) return '/';
  return (
    req.url ||
    req.urlPath ||
    req.urlPathTemplate ||
    req.urlPattern ||
    req.urlPathPattern ||
    req.urlPathMatching ||
    '/'
  );
}

export function extractBaseSegment(path) {
  if (!path || path === '/') return 'Root';
  const clean = path.replace(/^https?:\/\/[^/]+/, '').split('?')[0];
  const parts = clean.split('/').filter(Boolean);
  if (parts.length === 0) return 'Root';
  if (parts[0].toLowerCase() === 'api' && parts.length > 1) {
    if (/^v\d+$/i.test(parts[1]) && parts.length > 2) {
      return parts[2].toLowerCase();
    }
    return parts[1].toLowerCase();
  }
  return parts[0].toLowerCase();
}

export function getStubProject(stub) {
  if (!stub) return 'Ungrouped';
  if (stub.metadata && stub.metadata.project) {
    return String(stub.metadata.project);
  }
  const url = getStubUrl(stub.request);
  const base = extractBaseSegment(url);
  return base ? `api-${base}` : 'Ungrouped';
}

export function getStubApi(stub) {
  if (!stub) return 'General';
  if (stub.metadata && stub.metadata.api) {
    return String(stub.metadata.api);
  }
  if (stub.metadata && Array.isArray(stub.metadata.tags) && stub.metadata.tags.length > 0) {
    return String(stub.metadata.tags[0]);
  }
  return 'General';
}

export function generateCurl(method, path, headers = {}, body = '', origin = null) {
  const base = getCatalogOrigin(origin);
  const cleanPath = path || '/';
  const fullUrl = cleanPath.startsWith('http') ? cleanPath : `${base}${cleanPath.startsWith('/') ? '' : '/'}${cleanPath}`;
  const parts = [`curl -X ${method || 'GET'} "${fullUrl}"`];
  for (const [k, v] of Object.entries(headers)) {
    if (v) parts.push(`-H "${k}: ${v}"`);
  }
  if (body && ['POST', 'PUT', 'PATCH', 'DELETE'].includes((method || 'GET').toUpperCase())) {
    const escapedBody = String(body).replace(/"/g, '\\"');
    parts.push(`-d "${escapedBody}"`);
  }
  return parts.join(' \\\n  ');
}

export function generateCreateStubCurl(stub, origin = null) {
  if (!stub) return '';
  const base = getCatalogOrigin(origin);
  const endpoint = `${base}/__admin/mappings`;
  const jsonStr = JSON.stringify(stub, null, 2);

  return [
    `curl -X POST "${endpoint}" \\`,
    `  -H "Content-Type: application/json" \\`,
    `  --data-binary @- << 'EOF'`,
    jsonStr,
    `EOF`
  ].join('\n');
}

export function generateJavaStubFor(stub) {
  if (!stub) return '';
  const req = stub.request || {};
  const res = stub.response || {};
  const method = (req.method || 'ANY').toLowerCase();
  const wmMethod = ['get', 'post', 'put', 'delete', 'patch', 'head', 'options'].includes(method) ? method : 'any';

  let urlMatcher = `urlEqualTo("${(req.url || '/').replace(/"/g, '\\"')}")`;
  if (req.urlPathTemplate) {
    urlMatcher = `urlPathTemplate("${req.urlPathTemplate.replace(/"/g, '\\"')}")`;
  } else if (req.urlPattern || req.urlPathPattern || req.urlPathMatching) {
    const pat = req.urlPattern || req.urlPathPattern || req.urlPathMatching;
    urlMatcher = `urlMatching("${pat.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}")`;
  } else if (req.urlPath) {
    urlMatcher = `urlPathEqualTo("${req.urlPath.replace(/"/g, '\\"')}")`;
  }

  const lines = [`stubFor(${wmMethod}(${urlMatcher})`];
  if (stub.name) {
    lines.push(`    .withName("${String(stub.name).replace(/"/g, '\\"')}")`);
  }
  if (stub.priority && stub.priority !== 5) {
    lines.push(`    .atPriority(${stub.priority})`);
  }
  if (req.headers) {
    for (const [k, v] of Object.entries(req.headers)) {
      const val = v.equalTo || v.contains || v.matches || '';
      const op = v.contains ? 'containing' : (v.matches ? 'matching' : 'equalTo');
      if (val) {
        lines.push(`    .withHeader("${k.replace(/"/g, '\\"')}", ${op}("${String(val).replace(/"/g, '\\"')}"))`);
      }
    }
  }
  if (req.bodyPatterns && req.bodyPatterns.length > 0) {
    req.bodyPatterns.forEach(bp => {
      if (bp.equalToJson) {
        const jsonStr = typeof bp.equalToJson === 'string' ? bp.equalToJson : JSON.stringify(bp.equalToJson);
        lines.push(`    .withRequestBody(equalToJson("${jsonStr.replace(/\\/g, '\\\\').replace(/"/g, '\\"')}", true, true))`);
      } else if (bp.contains) {
        lines.push(`    .withRequestBody(containing("${String(bp.contains).replace(/"/g, '\\"')}"))`);
      } else if (bp.matchesJsonPath) {
        if (typeof bp.matchesJsonPath === 'object' && bp.matchesJsonPath !== null) {
          const expr = bp.matchesJsonPath.expression || '';
          const sub = bp.matchesJsonPath.equalTo !== undefined ? bp.matchesJsonPath.equalTo : bp.matchesJsonPath.contains;
          if (sub !== undefined) {
            lines.push(`    .withRequestBody(matchingJsonPath("${String(expr).replace(/"/g, '\\"')}", equalTo("${String(sub).replace(/"/g, '\\"')}")))`);
          } else {
            lines.push(`    .withRequestBody(matchingJsonPath("${String(expr).replace(/"/g, '\\"')}"))`);
          }
        } else if (bp.equalTo) {
          lines.push(`    .withRequestBody(matchingJsonPath("${String(bp.matchesJsonPath).replace(/"/g, '\\"')}", equalTo("${String(bp.equalTo).replace(/"/g, '\\"')}")))`);
        } else {
          lines.push(`    .withRequestBody(matchingJsonPath("${String(bp.matchesJsonPath).replace(/"/g, '\\"')}"))`);
        }
      } else if (bp.equalTo) {
        lines.push(`    .withRequestBody(equalTo("${String(bp.equalTo).replace(/"/g, '\\"')}"))`);
      }
    });
  }

  if (res.proxyBaseUrl) {
    lines.push(`    .willReturn(aResponse().proxiedFrom("${String(res.proxyBaseUrl).replace(/"/g, '\\"')}")));`);
    return lines.join('\n');
  }

  const status = res.status || 200;
  lines.push(`    .willReturn(aResponse()`);
  lines.push(`        .withStatus(${status})`);
  if (res.headers) {
    for (const [k, v] of Object.entries(res.headers)) {
      lines.push(`        .withHeader("${k.replace(/"/g, '\\"')}", "${String(v).replace(/"/g, '\\"')}")`);
    }
  }
  const rawBody = res.jsonBody ? JSON.stringify(res.jsonBody) : (res.body || '');
  if (rawBody) {
    lines.push(`        .withBody("${String(rawBody).replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\r?\n/g, '\\n')}")`);
  }
  if (Array.isArray(res.transformers) && res.transformers.includes('response-template')) {
    lines.push(`        .withTransformers("response-template")`);
  }
  lines.push(`    ));`);
  return lines.join('\n');
}

export class StubCatalog {
  constructor() {
    this._mappings = [];
    this._selectedStubId = null;
    this._selectedIds = new Set();
  }

  setRawCollections({ mappings, active = [], disabled = [] } = {}) {
    if (Array.isArray(mappings)) {
      this._mappings = mappings;
    } else {
      const normalizedActive = Array.isArray(active) ? active : [];
      const normalizedDisabled = (Array.isArray(disabled) ? disabled : []).map(d => ({
        ...d,
        priority: isStubParked(d) ? d.priority : PRIORITY_PARKED
      }));
      this._mappings = [...normalizedActive, ...normalizedDisabled];
    }
  }

  setMappings(mappings) {
    this._mappings = Array.isArray(mappings) ? mappings : [];
  }

  getAll() {
    const seen = new Set();
    const result = [];
    for (const s of this._mappings) {
      if (!s) continue;
      if (s.id) {
        if (seen.has(s.id)) continue;
        seen.add(s.id);
      }
      result.push({ ...s });
    }
    return result;
  }

  getById(id) {
    if (!id) return null;
    return this.getAll().find(s => s.id === id) || null;
  }

  query({ search = '', status = 'all', project = '' } = {}) {
    const filter = (search || '').trim();
    const all = this.getAll();

    return all.filter(s => {
      const isDisabled = isStubParked(s);

      if (status === 'active' && isDisabled) return false;
      if (status === 'disabled' && !isDisabled) return false;

      if (project) {
        const proj = getStubProject(s);
        if (proj !== project) return false;
      }

      if (!filter) return true;
      const url = getStubUrl(s.request);
      const proj = getStubProject(s);
      return matchesStub(s, filter, url, proj);
    });
  }

  getCounts(projectFilter = null) {
    let mappingList = this._mappings;

    if (projectFilter) {
      mappingList = mappingList.filter(s => getStubProject(s) === projectFilter);
    }

    const activeCount = mappingList.filter(s => !isStubParked(s)).length;
    const disabledCount = mappingList.filter(isStubParked).length;

    return {
      all: mappingList.length,
      active: activeCount,
      disabled: disabledCount
    };
  }

  getProjects() {
    const projectsMap = new Map();

    this._mappings.forEach(stub => {
      const proj = getStubProject(stub);
      const parked = isStubParked(stub);
      if (!projectsMap.has(proj)) {
        projectsMap.set(proj, { name: proj, total: 0, active: 0, disabled: 0 });
      }
      const entry = projectsMap.get(proj);
      entry.total++;
      if (parked) entry.disabled++;
      else entry.active++;
    });

    return Array.from(projectsMap.values()).sort((a, b) => a.name.localeCompare(b.name));
  }

  // Single-Stub & Bulk Selection Tracking
  getSelectedStubId() {
    return this._selectedStubId;
  }

  setSelectedStubId(id) {
    this._selectedStubId = id || null;
  }

  getSelectedStub() {
    return this.getById(this._selectedStubId);
  }

  get selectedIds() {
    return this._selectedIds;
  }

  replaceSelectedIds(iterable) {
    this._selectedIds.clear();
    if (iterable) {
      for (const id of iterable) {
        if (id) this._selectedIds.add(id);
      }
    }
  }

  getSelectedIds() {
    return new Set(this._selectedIds);
  }

  isSelected(id) {
    return this._selectedIds.has(id);
  }

  select(id) {
    if (id) this._selectedIds.add(id);
  }

  deselect(id) {
    if (id) this._selectedIds.delete(id);
  }

  toggleSelect(id) {
    if (!id) return false;
    if (this._selectedIds.has(id)) {
      this._selectedIds.delete(id);
      return false;
    } else {
      this._selectedIds.add(id);
      return true;
    }
  }

  selectAll(ids = []) {
    ids.forEach(id => {
      if (id) this._selectedIds.add(id);
    });
  }

  clearSelection() {
    this._selectedIds.clear();
  }

  getSelectedCount() {
    return this._selectedIds.size;
  }
}

export const stubCatalog = new StubCatalog();
