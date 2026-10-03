/**
 * Central UI reactive state store.
 * Holds active data collections, selection pointers, and inspector mode toggles.
 */

export const state = {
  currentStubs: [],
  selectedStubId: null,
  currentRequests: [],
  expandedRequestId: null,
  allScenarios: [],
  testerHistory: [],
  testerViewMode: 'highlighted',
  lastRawResponseBody: '',
  activeInspectorTab: 'response',
  isSyncingRoute: false
};
