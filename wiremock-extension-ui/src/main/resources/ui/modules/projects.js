/**
 * Project & API grouping, inference, and filter management.
 * Invariant: Stubs without metadata are grouped via URL base segment heuristic or 'Ungrouped'.
 */
import { state } from './state.js';
import { elements } from './dom.js';
import { getCatalogCounts } from './stubs.js';
import { setProjectMode, snapshotProjectRecordings } from './api.js';
import { loadExcludedJsonPaths } from './settings.js';
import { stubCatalog, getStubProject, getStubApi, extractBaseSegment, getStubUrl } from './stub-catalog.js';

export { getStubProject, getStubApi, extractBaseSegment };

export function computeProjects() {
  stubCatalog.setRawCollections({
    active: state.currentStubs,
    disabled: state.disabledStubs,
    statefulModels: state.statefulModels
  });
  return stubCatalog.getProjects();
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
  const countDynamic = document.getElementById('count-filter-dynamic');

  const counts = getCatalogCounts(state.selectedProject);

  if (countAll) countAll.textContent = counts.all;
  if (countActive) countActive.textContent = counts.active;
  if (countDisabled) countDisabled.textContent = counts.disabled;
  if (countDynamic) countDynamic.textContent = counts.dynamic;

  updateProjectLifecycleBar();
}

export function updateProjectLifecycleBar() {
  const bar = elements.projectLifecycleBar;
  if (!bar) return;

  const allStubs = [...state.currentStubs, ...state.disabledStubs];
  const projStubs = state.selectedProject
    ? allStubs.filter(s => getStubProject(s) === state.selectedProject)
    : allStubs;
  const proxyStubs = projStubs.filter(s => (s.response && !!s.response.proxyBaseUrl) || (s.metadata && s.metadata.mode === 'proxy'));

  if (proxyStubs.length === 0) {
    bar.classList.add('hidden');
    return;
  }

  bar.classList.remove('hidden');

  if (elements.projectActiveName) {
    elements.projectActiveName.textContent = state.selectedProject || 'All Projects';
  }

  const activeProxy = proxyStubs.some(s => state.currentStubs.some(c => c.id === s.id));

  if (elements.projectModeBadge) {
    if (activeProxy) {
      elements.projectModeBadge.textContent = 'LIVE PROXY';
      elements.projectModeBadge.className = 'project-mode-badge mode-proxy';
    } else {
      elements.projectModeBadge.textContent = 'STUBS MODE';
      elements.projectModeBadge.className = 'project-mode-badge mode-stubs';
    }
  }

  if (elements.btnProjectModeToggle) {
    elements.btnProjectModeToggle.textContent = activeProxy ? '🟣 Switch to Stubs' : '🟢 Switch to Proxy';
    elements.btnProjectModeToggle.title = activeProxy
      ? 'Disable proxy stubs and activate recorded static stubs'
      : 'Disable static stubs and activate live proxy stubs';
  }
}

export function setupProjectLifecycleBar(loadDataFn) {
  if (elements.btnProjectModeToggle) {
    elements.btnProjectModeToggle.addEventListener('click', async () => {
      const targetProject = state.selectedProject || '_all';
      const allStubs = [...state.currentStubs, ...state.disabledStubs];
      const projStubs = state.selectedProject
        ? allStubs.filter(s => getStubProject(s) === state.selectedProject)
        : allStubs;
      const proxyStubs = projStubs.filter(s => (s.response && !!s.response.proxyBaseUrl) || (s.metadata && s.metadata.mode === 'proxy'));
      const activeProxy = proxyStubs.some(s => state.currentStubs.some(c => c.id === s.id));
      const targetMode = activeProxy ? 'stubs' : 'proxy';

      try {
        elements.btnProjectModeToggle.disabled = true;
        await setProjectMode(targetProject, targetMode);
        if (loadDataFn) await loadDataFn();
      } catch (err) {
        alert('Failed to switch project mode: ' + err.message);
      } finally {
        if (elements.btnProjectModeToggle) elements.btnProjectModeToggle.disabled = false;
      }
    });
  }

  if (elements.btnProjectSnapshot) {
    elements.btnProjectSnapshot.addEventListener('click', async () => {
      const targetProject = state.selectedProject || '_all';
      try {
        elements.btnProjectSnapshot.disabled = true;
        elements.btnProjectSnapshot.textContent = 'Capturing...';
        const res = await snapshotProjectRecordings(targetProject, loadExcludedJsonPaths());
        if (loadDataFn) await loadDataFn();
        alert(`Snapshot complete! Captured ${res.totalRecorded || 0} stubs in disabled state for review.`);
      } catch (err) {
        alert('Failed to snapshot project recordings: ' + err.message);
      } finally {
        if (elements.btnProjectSnapshot) {
          elements.btnProjectSnapshot.disabled = false;
          elements.btnProjectSnapshot.textContent = '📸 Snapshot';
        }
      }
    });
  }
}
