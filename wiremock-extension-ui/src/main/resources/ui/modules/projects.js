/**
 * Project & API grouping, inference, and filter management.
 * Invariant: Stubs without metadata are grouped via URL base segment heuristic or 'Ungrouped'.
 */
import { state } from './state.js';
import { elements } from './dom.js';
import { getStubUrl } from './stubs.js';

export function extractBaseSegment(path) {
  if (!path || path === '/') return 'Root';
  const clean = path.replace(/^https?:\/\/[^/]+/, '').split('?')[0];
  const parts = clean.split('/').filter(Boolean);
  if (parts.length === 0) return 'Root';
  // If first segment is 'api', check second segment (e.g. /api/v1/orders -> orders)
  if (parts[0].toLowerCase() === 'api' && parts.length > 1) {
    if (/^v\d+$/i.test(parts[1]) && parts.length > 2) {
      return parts[2].toLowerCase();
    }
    return parts[1].toLowerCase();
  }
  return parts[0].toLowerCase();
}

export function getStubProject(stub) {
  if (stub.metadata && stub.metadata.project) {
    return String(stub.metadata.project);
  }
  const url = getStubUrl(stub.request);
  const base = extractBaseSegment(url);
  return base ? `api-${base}` : 'Ungrouped';
}

export function getStubApi(stub) {
  if (stub.metadata && stub.metadata.api) {
    return String(stub.metadata.api);
  }
  if (stub.metadata && Array.isArray(stub.metadata.tags) && stub.metadata.tags.length > 0) {
    return String(stub.metadata.tags[0]);
  }
  return 'General';
}

export function computeProjects() {
  const all = [...state.currentStubs, ...state.disabledStubs];
  const projectsMap = new Map();

  all.forEach(stub => {
    const proj = getStubProject(stub);
    const isDisabled = state.disabledStubs.some(d => d.id === stub.id);
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

export function renderProjectSelector() {
  const select = document.getElementById('project-filter-select');
  if (!select) return;

  const projects = computeProjects();
  const currentVal = state.selectedProject || '';

  select.innerHTML = '<option value="">📁 All Projects (' + (state.currentStubs.length + state.disabledStubs.length) + ')</option>';
  projects.forEach(p => {
    const opt = document.createElement('option');
    opt.value = p.name;
    opt.textContent = `📁 ${p.name} (${p.total} · ${p.active} active)`;
    if (p.name === currentVal) opt.selected = true;
    select.appendChild(opt);
  });

  updateFilterCounts();
}

export function updateFilterCounts() {
  const countAll = document.getElementById('count-filter-all');
  const countActive = document.getElementById('count-filter-active');
  const countDisabled = document.getElementById('count-filter-disabled');

  let activeList = state.currentStubs;
  let disabledList = state.disabledStubs;

  if (state.selectedProject) {
    activeList = activeList.filter(s => getStubProject(s) === state.selectedProject);
    disabledList = disabledList.filter(s => getStubProject(s) === state.selectedProject);
  }

  if (countAll) countAll.textContent = activeList.length + disabledList.length;
  if (countActive) countActive.textContent = activeList.length;
  if (countDisabled) countDisabled.textContent = disabledList.length;
}
