/**
 * Canonical frontend domain module for WireMock stub management.
 *
 * Encapsulates:
 * - Aggregation of active stubs, parked disabled stubs, and dynamic stateful stubs.
 * - Project ownership derivation and distinct project extraction.
 * - Multi-token boolean query evaluation and status filtering.
 * - Selection tracking for bulk operations.
 */
import { matchesStub } from './filter.js';
import { normalizeStatefulModelsToStubs } from './dynamic-stubs.js';

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
    this._active = [];
    this._disabled = [];
    this._dynamic = [];
    this._selectedIds = new Set();
  }

  setRawCollections({ active = [], disabled = [], statefulModels = [] }) {
    this._active = Array.isArray(active) ? active : [];
    this._disabled = Array.isArray(disabled) ? disabled : [];
    this._dynamic = normalizeStatefulModelsToStubs(statefulModels || []);
  }

  setActiveStubs(active) {
    this._active = Array.isArray(active) ? active : [];
  }

  setDisabledStubs(disabled) {
    this._disabled = Array.isArray(disabled) ? disabled : [];
  }

  setStatefulModels(models) {
    this._dynamic = normalizeStatefulModelsToStubs(models || []);
  }

  getAll() {
    const active = this._active.map(s => ({ ...s, _disabled: false, _dynamic: false }));
    const disabled = this._disabled.map(s => ({ ...s, _disabled: true, _dynamic: false }));
    const dynamic = this._dynamic.map(s => ({ ...s, _disabled: false, _dynamic: true }));

    const seen = new Set();
    const result = [];
    for (const s of [...active, ...disabled, ...dynamic]) {
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

  query({ search = '', status = 'all', project = null } = {}) {
    const filter = (search || '').trim();
    const allStubs = this.getAll();

    return allStubs.filter(s => {
      const isDynamic = !!s.isDynamic || !!s._dynamic;
      const isDisabled = !!s._disabled || this._disabled.some(d => d.id === s.id);

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
    let activeList = this._active;
    let disabledList = this._disabled;
    let dynamicList = this._dynamic;

    if (projectFilter) {
      activeList = activeList.filter(s => getStubProject(s) === projectFilter);
      disabledList = disabledList.filter(s => getStubProject(s) === projectFilter);
      dynamicList = dynamicList.filter(s => (s.metadata && s.metadata.project) === projectFilter);
    }

    return {
      all: activeList.length + disabledList.length + dynamicList.length,
      active: activeList.length,
      disabled: disabledList.length,
      dynamic: dynamicList.length
    };
  }

  getProjects() {
    const all = [...this._active, ...this._disabled];
    const projectsMap = new Map();

    all.forEach(stub => {
      const proj = getStubProject(stub);
      const isDisabled = this._disabled.some(d => d.id === stub.id);
      if (!projectsMap.has(proj)) {
        projectsMap.set(proj, { name: proj, total: 0, active: 0, disabled: 0 });
      }
      const entry = projectsMap.get(proj);
      entry.total++;
      if (isDisabled) entry.disabled++;
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
