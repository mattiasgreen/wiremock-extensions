/**
 * Central UI reactive state store.
 * Holds active data collections, selection pointers, filters, and inspector mode toggles.
 */

export function parseTargetUrl(rawUrl) {
  if (!rawUrl || !rawUrl.trim()) return null;
  let clean = rawUrl.trim().replace(/\/+$/, '');
  if (!clean.startsWith('http://') && !clean.startsWith('https://')) {
    clean = 'http://' + clean;
  }
  try {
    const parsed = new URL(clean);
    const proto = parsed.protocol.replace(':', '');
    const host = parsed.host;
    const prefix = parsed.pathname && parsed.pathname !== '/' ? parsed.pathname.replace(/\/+$/, '') : '';
    return {
      proto,
      host,
      prefix,
      fullUrl: `${parsed.protocol}//${parsed.host}${prefix}`
    };
  } catch {
    return null;
  }
}

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

export function getInitialConnectionStrategy() {
  if (typeof window === 'undefined') return 'proxy';
  const searchParams = new URLSearchParams(window.location.search || '');
  const strat = searchParams.get('strategy');
  if (strat === 'direct' || strat === 'proxy') return strat;
  const hash = window.location.hash || '';
  const qIdx = hash.indexOf('?');
  if (qIdx !== -1) {
    const hashParams = new URLSearchParams(hash.substring(qIdx + 1));
    const hashStrat = hashParams.get('strategy');
    if (hashStrat === 'direct' || hashStrat === 'proxy') return hashStrat;
  }
  return 'proxy';
}

export function setTargetUrlInLocation(newTarget, strategy = 'proxy') {
  if (typeof window === 'undefined') return;
  const clean = (newTarget || '').trim().replace(/\/+$/, '');
  state.apiBaseUrl = clean;
  state.targetConfig = parseTargetUrl(clean);
  state.connectionStrategy = strategy === 'direct' ? 'direct' : 'proxy';

  const url = new URL(window.location.href);
  if (clean) {
    url.searchParams.set('wiremock', clean);
    if (state.connectionStrategy === 'direct') {
      url.searchParams.set('strategy', 'direct');
    } else {
      url.searchParams.delete('strategy');
    }
  } else {
    url.searchParams.delete('wiremock');
    url.searchParams.delete('target');
    url.searchParams.delete('strategy');
  }
  if (url.hash && url.hash.includes('?')) {
    const parts = url.hash.split('?');
    const hashParams = new URLSearchParams(parts[1]);
    hashParams.delete('wiremock');
    hashParams.delete('target');
    hashParams.delete('strategy');
    const newHashQ = hashParams.toString();
    url.hash = parts[0] + (newHashQ ? '?' + newHashQ : '');
  }

  if (window.history && window.history.replaceState) {
    window.history.replaceState(null, '', url.pathname + url.search + url.hash);
  } else {
    window.location.search = url.search;
  }
}

export function getWireMockUri() {
  if (state && state.targetConfig && state.targetConfig.fullUrl) {
    return state.targetConfig.fullUrl;
  }
  if (state && state.apiBaseUrl) {
    return state.apiBaseUrl.replace(/\/+$/, '');
  }
  if (typeof window !== 'undefined' && window.location) {
    const origin = window.location.origin && window.location.origin !== 'null'
      ? window.location.origin
      : '';
    const pathname = window.location.pathname || '';
    const idx = pathname.indexOf('/__admin/ui');
    const prefix = idx > 0 ? pathname.substring(0, idx) : '';
    const combined = (origin + prefix).replace(/\/+$/, '');
    if (combined) {
      return combined;
    }
  }
  return 'http://localhost:8080';
}

const initialTarget = getInitialTargetUrl();
const initialStrategy = getInitialConnectionStrategy();

export const state = {
  apiBaseUrl: initialTarget,
  targetConfig: parseTargetUrl(initialTarget),
  connectionStrategy: initialStrategy,
  currentStubs: [],
  disabledStubs: [],
  selectedStubId: null,
  selectedProject: null, // null means all projects
  statusFilter: 'all',   // 'all' | 'active' | 'disabled' | 'dynamic'
  selectedStubIds: new Set(),
  statefulModels: [],
  currentRequests: [],
  journalVerdictFilter: 'all', // 'all' | 'proxied' | 'stub' | 'stateful' | 'unmatched'
  expandedRequestId: null,
  journalLimit: 100,
  allScenarios: [],
  testerHistory: [],
  testerHistoryCollapsed: false,
  testerViewMode: 'highlighted',
  lastRawResponseBody: '',
  activeInspectorTab: 'response',
  activeTab: 'tab-stub-detail',
  isSyncingRoute: false,
  capabilities: {
    core: true,
    lifecycle: true,
    stateful: true,
    netem: true,
    metrics: true
  },
  capabilitiesDetected: false
};

