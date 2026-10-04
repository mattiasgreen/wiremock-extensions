/**
 * Multi-selection and bulk operations toolbar.
 * Endpoints: POST /__admin/stubs/bulk
 */
import { state } from './state.js';
import { elements } from './dom.js';
import { bulkStubs, loadData } from './api.js';
import { exportStubs } from './import-export.js';
import { renderStubList } from './stubs.js';

export function updateBulkToolbar() {
  const bar = document.getElementById('bulk-actions-bar');
  const countEl = document.getElementById('bulk-selected-count');
  const selectAllChk = document.getElementById('chk-select-all');

  const count = state.selectedStubIds.size;
  if (!bar) return;

  if (count > 0) {
    bar.classList.remove('hidden');
    if (countEl) countEl.textContent = `${count} selected`;
  } else {
    bar.classList.add('hidden');
  }

  // Update master checkbox state
  if (selectAllChk) {
    const allVisible = getCurrentlyVisibleStubs();
    if (allVisible.length > 0 && allVisible.every(s => state.selectedStubIds.has(s.id))) {
      selectAllChk.checked = true;
      selectAllChk.indeterminate = false;
    } else if (allVisible.some(s => state.selectedStubIds.has(s.id))) {
      selectAllChk.checked = false;
      selectAllChk.indeterminate = true;
    } else {
      selectAllChk.checked = false;
      selectAllChk.indeterminate = false;
    }
  }
}

export function getCurrentlyVisibleStubs() {
  const filter = (elements.searchBox && elements.searchBox.value || '').toLowerCase().trim();
  const allStubs = [...state.currentStubs, ...state.disabledStubs];

  return allStubs.filter(s => {
    const isDisabled = state.disabledStubs.some(d => d.id === s.id);
    if (state.statusFilter === 'active' && isDisabled) return false;
    if (state.statusFilter === 'disabled' && !isDisabled) return false;

    if (state.selectedProject) {
      const proj = (s.metadata && s.metadata.project) ? String(s.metadata.project) : 'Ungrouped';
      if (proj !== state.selectedProject) return false;
    }

    if (!filter) return true;
    const method = (s.request && s.request.method ? s.request.method : 'ANY').toLowerCase();
    const url = (s.request && (s.request.url || s.request.urlPath || s.request.urlPathTemplate || ''))
      .toLowerCase();
    const name = (s.name || '').toLowerCase();
    return method.includes(filter) || url.includes(filter) || name.includes(filter);
  });
}

export function handleSelectAllToggle(checked) {
  const visible = getCurrentlyVisibleStubs();
  if (checked) {
    visible.forEach(s => state.selectedStubIds.add(s.id));
  } else {
    visible.forEach(s => state.selectedStubIds.delete(s.id));
  }
  renderStubList();
  updateBulkToolbar();
}

export function handleStubCheckboxClick(stubId, isChecked) {
  if (isChecked) {
    state.selectedStubIds.add(stubId);
  } else {
    state.selectedStubIds.delete(stubId);
  }
  updateBulkToolbar();
}

export function executeBulkAction(action) {
  const ids = Array.from(state.selectedStubIds);
  if (ids.length === 0) return;

  if (action === 'export') {
    exportStubs('selected');
    return;
  }

  if (action === 'delete') {
    if (!confirm(`Are you sure you want to permanently delete ${ids.length} selected stubs?`)) {
      return;
    }
  }

  return bulkStubs(action, ids)
    .then(() => {
      state.selectedStubIds.clear();
      return loadData();
    })
    .catch(err => {
      alert(`Bulk ${action} failed: ${err.message}`);
    });
}
