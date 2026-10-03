/**
 * URL Hash Router and deep-linking manager.
 * Route contract: #<route>?<queryParams>
 * Supported routes: #stubs, #journal, #tester, #scenarios
 */
import { state } from './state.js';
import { elements } from './dom.js';

export const TAB_ROUTES = {
  'stubs': 'tab-stub-detail',
  'journal': 'tab-journal',
  'tester': 'tab-tester',
  'scenarios': 'tab-scenarios'
};

export const ROUTE_FOR_TAB = {
  'tab-stub-detail': 'stubs',
  'tab-journal': 'journal',
  'tab-tester': 'tester',
  'tab-scenarios': 'scenarios'
};

export function toBase64(str) {
  if (!str) return '';
  try {
    return btoa(unescape(encodeURIComponent(str)))
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '');
  } catch (e) {
    return encodeURIComponent(str);
  }
}

export function fromBase64(b64) {
  if (!b64) return '';
  try {
    let base64 = b64.replace(/-/g, '+').replace(/_/g, '/');
    while (base64.length % 4) base64 += '=';
    return decodeURIComponent(escape(atob(base64)));
  } catch (e) {
    try {
      return decodeURIComponent(b64);
    } catch (_) {
      return b64;
    }
  }
}

export function parseHash() {
  const raw = (window.location.hash || '').replace(/^#\/?/, '');
  if (!raw) return { route: 'stubs', params: new URLSearchParams() };
  const idx = raw.indexOf('?');
  if (idx === -1) {
    return { route: raw || 'stubs', params: new URLSearchParams() };
  }
  return {
    route: raw.substring(0, idx) || 'stubs',
    params: new URLSearchParams(raw.substring(idx + 1))
  };
}

export function setRoute(route, newParams = {}, replace = false) {
  if (state.isSyncingRoute) return;
  const current = parseHash();
  const targetRoute = route !== undefined ? route : current.route;
  const params = new URLSearchParams(current.params);
  for (const [k, v] of Object.entries(newParams)) {
    if (v === null || v === undefined || v === '') {
      params.delete(k);
    } else {
      params.set(k, v);
    }
  }
  const q = params.toString();
  const newHash = `#${targetRoute}${q ? '?' + q : ''}`;
  if (window.location.hash !== newHash) {
    if (replace && window.history && window.history.replaceState) {
      window.history.replaceState(null, '', newHash);
    } else {
      window.location.hash = newHash;
    }
  }
}

export function activateTab(tabId, updateRoute = true) {
  elements.tabs.forEach(t => {
    const tId = t.getAttribute('data-tab') || (t.dataset && t.dataset.tab);
    if (tId === tabId) {
      t.classList.add('active');
    } else {
      t.classList.remove('active');
    }
  });
  document.querySelectorAll('.tab-content').forEach(c => {
    if (c.id === tabId) {
      c.classList.add('active');
    } else {
      c.classList.remove('active');
    }
  });
  if (updateRoute) {
    const route = ROUTE_FOR_TAB[tabId] || 'stubs';
    setRoute(route);
  }
}

// Injected callbacks to avoid circular dependencies
let onApplyStubsRoute = null;
let onApplyJournalRoute = null;
let onApplyTesterRoute = null;

export function registerRouteHandlers({ applyStubs, applyJournal, applyTester }) {
  onApplyStubsRoute = applyStubs;
  onApplyJournalRoute = applyJournal;
  onApplyTesterRoute = applyTester;
}

export function applyRouteFromUrl() {
  state.isSyncingRoute = true;
  try {
    const { route, params } = parseHash();
    const tabId = TAB_ROUTES[route] || 'tab-stub-detail';
    activateTab(tabId, false);

    if (route === 'stubs' || !route) {
      if (onApplyStubsRoute) onApplyStubsRoute(params);
    } else if (route === 'journal') {
      if (onApplyJournalRoute) onApplyJournalRoute(params);
    } else if (route === 'tester') {
      if (onApplyTesterRoute) onApplyTesterRoute(params);
    }
  } finally {
    state.isSyncingRoute = false;
  }
}
