/**
 * Canonical frontend domain module for WireMock stub management.
 *
 * Encapsulates:
 * - Native priority-tier classification (5 = active stub, 10 = proxy, 50 = offline guard, 100+ = parked/disabled).
 * - Aggregation of WireMock mappings and dynamic stateful stubs.
 * - Project ownership derivation and distinct project extraction.
 * - Multi-token boolean query evaluation and status filtering.
 * - Selection tracking for bulk operations.
 */
import { matchesStub } from './filter.js';
import { normalizeStatefulModelsToStubs } from './dynamic-stubs.js';

export const PRIORITY_ACTIVE_STUB = 5;
export const PRIORITY_ACTIVE_PROXY = 10;
export const PRIORITY_OFFLINE_GUARD = 50;
export const PRIORITY_PARKED = 100;

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

export class StubCatalog {
  constructor() {
    this._mappings = [];
    this._dynamic = [];
    this._selectedIds = new Set();
  }

  setRawCollections({ mappings, active = [], disabled = [], statefulModels = [] }) {
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
    this._dynamic = normalizeStatefulModelsToStubs(statefulModels || []);
  }

  setActiveStubs(active) {
    this._mappings = Array.isArray(active) ? active : [];
  }

  setMappings(mappings) {
    this._mappings = Array.isArray(mappings) ? mappings : [];
  }

  setStatefulModels(models) {
    this._dynamic = normalizeStatefulModelsToStubs(models || []);
  }

  getAll() {
    const mappings = this._mappings.map(s => ({
      ...s,
      _disabled: isStubParked(s),
      _dynamic: false
    }));
    const dynamic = this._dynamic.map(s => ({ ...s, _disabled: false, _dynamic: true }));

    const seen = new Set();
    const result = [];
    for (const s of [...mappings, ...dynamic]) {
      if (s && s.id && !seen.has(s.id)) {
        seen.add(s.id);
        result.push(s);
      }
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
      const isDynamic = !!s._dynamic;
      const isDisabled = isDynamic ? false : isStubParked(s);

      if (status === 'active' && (isDisabled || isDynamic)) return false;
      if (status === 'disabled' && (!isDisabled || isDynamic)) return false;
      if (status === 'dynamic' && !isDynamic) return false;

      if (project) {
        const proj = isDynamic ? (s.metadata && s.metadata.project) : getStubProject(s);
        if (proj !== project) return false;
      }

      if (!filter) return true;
      const url = getStubUrl(s.request);
      const proj = isDynamic ? (s.metadata && s.metadata.project || '') : getStubProject(s);
      return matchesStub(s, filter, url, proj);
    });
  }

  getCounts(projectFilter = null) {
    let mappingList = this._mappings;
    let dynamicList = this._dynamic;

    if (projectFilter) {
      mappingList = mappingList.filter(s => getStubProject(s) === projectFilter);
      dynamicList = dynamicList.filter(s => (s.metadata && s.metadata.project) === projectFilter);
    }

    const activeCount = mappingList.filter(s => !isStubParked(s)).length;
    const disabledCount = mappingList.filter(isStubParked).length;

    return {
      all: mappingList.length + dynamicList.length,
      active: activeCount,
      disabled: disabledCount,
      dynamic: dynamicList.length
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

  // Selection Tracking
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
