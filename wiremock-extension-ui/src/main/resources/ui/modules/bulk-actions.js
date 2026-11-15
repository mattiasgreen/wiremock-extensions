/**
 * Multi-selection and bulk operations toolbar.
 * Native priority parking/deletion via /__admin/mappings/{id}.
 */
import { state, uiActions } from './state.js';
import { elements } from './dom.js';
import { bulkStubs } from './api.js';
import { stubCatalog } from './stub-catalog.js';
import { exportStubs } from './import-export.js';

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
  const search = (elements.searchBox && elements.searchBox.value) || '';
  return stubCatalog.query({
    search,
    status: state.statusFilter,
    project: state.selectedProject
  });
}

export function handleSelectAllToggle(checked) {
  const visible = getCurrentlyVisibleStubs();
  if (checked) {
    visible.forEach(s => state.selectedStubIds.add(s.id));
  } else {
    visible.forEach(s => state.selectedStubIds.delete(s.id));
  }
  if (uiActions.renderStubList) uiActions.renderStubList();
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
      return uiActions.loadData ? uiActions.loadData() : Promise.resolve();
    })
    .catch(err => {
      alert(`Bulk ${action} failed: ${err.message}`);
    });
}
