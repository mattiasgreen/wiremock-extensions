/**
 * Central UI reactive state store.
 * Holds active data collections, selection pointers, filters, and inspector mode toggles.
 */

export function getInitialTargetUrl() {
  if (typeof window === 'undefined') return '';
  const searchParams = new URLSearchParams(window.location.search || '');
  const target = searchParams.get('wiremock') || searchParams.get('target');
  if (target) {
    return target.trim().replace(/\/+$/, '');
  }
  const hash = window.location.hash || '';
  const qIdx = hash.indexOf('?');
  if (qIdx !== -1) {
    const hashParams = new URLSearchParams(hash.substring(qIdx + 1));
    const hashTarget = hashParams.get('wiremock') || hashParams.get('target');
    if (hashTarget) {
      return hashTarget.trim().replace(/\/+$/, '');
    }
  }
  return '';
}

export function setTargetUrlInLocation(newTarget) {
  if (typeof window === 'undefined') return;
  const clean = (newTarget || '').trim().replace(/\/+$/, '');
  state.apiBaseUrl = clean;

  const url = new URL(window.location.href);
  if (clean) {
    url.searchParams.set('wiremock', clean);
  } else {
    url.searchParams.delete('wiremock');
    url.searchParams.delete('target');
  }
  if (url.hash && url.hash.includes('?')) {
    const parts = url.hash.split('?');
    const hashParams = new URLSearchParams(parts[1]);
    hashParams.delete('wiremock');
    hashParams.delete('target');
    const newHashQ = hashParams.toString();
    url.hash = parts[0] + (newHashQ ? '?' + newHashQ : '');
  }

  if (window.history && window.history.replaceState) {
    window.history.replaceState(null, '', url.pathname + url.search + url.hash);
  } else {
    window.location.search = url.search;
  }
}

export const state = {
  apiBaseUrl: getInitialTargetUrl(),
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

