/**
 * Multi-selection and bulk operations toolbar.
 * Native priority parking/deletion via /__admin/mappings/{id}.
 */
import { state, uiActions } from './state.js';
import { elements } from './dom.js';
import { confirmDialog, showToast } from './dialog.js';
import { bulkStubs } from './api.js';
import { stubCatalog } from './stub-catalog.js';
import { exportStubs } from './import-export.js';

export function getCurrentlyVisibleStubs() {
  const search = (elements.searchBox && elements.searchBox.value) || '';
  return stubCatalog.query({
    search,
    status: state.statusFilter
  });
}

export function getVisibleSelectedStubIds() {
  const visible = getCurrentlyVisibleStubs();
  return visible.filter(s => state.selectedStubIds.has(s.id)).map(s => s.id);
}

export function updateBulkToolbar() {
  const bar = document.getElementById('bulk-actions-bar');
  const countEl = document.getElementById('bulk-selected-count');
  const selectAllChk = document.getElementById('chk-select-all');

  const visibleSelectedIds = getVisibleSelectedStubIds();
  const count = visibleSelectedIds.length;
  if (!bar) return;

  if (count > 0) {
    bar.classList.remove('hidden');
    if (countEl) countEl.textContent = `${count} selected`;
  } else {
    bar.classList.add('hidden');
  }

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

export async function executeBulkAction(action) {
  const ids = getVisibleSelectedStubIds();
  if (ids.length === 0) return;

  if (action === 'export') {
    exportStubs('selected', ids);
    return;
  }

  if (action === 'delete') {
    const outcome = await confirmDialog({
      title: 'Delete Selected Stubs',
      message: `Are you sure you want to permanently delete ${ids.length} selected stub(s)?`,
      confirmLabel: 'Delete',
      cancelLabel: 'Cancel',
      danger: true
    });
    if (outcome !== 'confirm') {
      return;
    }
  }

  try {
    await bulkStubs(action, ids);
    ids.forEach(id => state.selectedStubIds.delete(id));
    if (uiActions.loadData) {
      await uiActions.loadData();
    }
    showToast(`Bulk ${action}: updated ${ids.length} stub(s)`, { type: 'success' });
  } catch (err) {
    showToast(`Bulk ${action} failed: ${err.message}`, { type: 'error' });
  }
}
