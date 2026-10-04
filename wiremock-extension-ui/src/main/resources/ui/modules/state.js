/**
 * Central UI reactive state store.
 * Holds active data collections, selection pointers, filters, and inspector mode toggles.
 */

export const state = {
  currentStubs: [],
  disabledStubs: [],
  selectedStubId: null,
  selectedProject: null, // null means all projects
  statusFilter: 'all',   // 'all' | 'active' | 'disabled'
  selectedStubIds: new Set(),
  currentRequests: [],
  expandedRequestId: null,
  allScenarios: [],
  testerHistory: [],
  testerHistoryCollapsed: false,
  testerViewMode: 'highlighted',
  lastRawResponseBody: '',
  activeInspectorTab: 'response',
  activeTab: 'tab-stub-detail',
  isSyncingRoute: false
};
