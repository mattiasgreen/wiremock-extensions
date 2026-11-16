/**
 * Pure WireMock Admin API client.
 * Zero DOM dependencies, zero XHR fallback, and lazy on-demand Demo Mode loading.
 */
import { state } from './state.js';
import { detectCapabilities } from './capabilities.js';
import {
  stubCatalog,
  PRIORITY_ACTIVE_PROXY,
  PRIORITY_ACTIVE_STUB,
  PRIORITY_PARKED,
  isProxyStub,
  isStubParked
} from './stub-catalog.js';

let demoBackendInstance = null;
let demoLoadPromise = null;
let startupDemoPromise = null;
let onDemoActivated = null;

export function registerDemoFallbackHandler(cb) {
  onDemoActivated = typeof cb === 'function' ? cb : null;
}

export function isExplicitDemoStartup() {
  if (typeof window === 'undefined') return false;
  const params = new URLSearchParams(window.location.search || '');
  if (params.get('demo') === 'true' || params.get('demo') === '1') return true;
  if (params.get('demo') === 'false') return false;
  if (window.location.protocol === 'file:') return true;
  if (window.location.hostname && window.location.hostname.endsWith('github.io')) {
    return !(params.get('wiremock') || params.get('target'));
  }
  return false;
}

async function loadDemoBackendModule() {
  if (demoBackendInstance) return demoBackendInstance;
  if (!demoLoadPromise) {
    demoLoadPromise = import('./demo-backend.js')
      .then(mod => {
        demoBackendInstance = mod.demoBackend;
        return demoBackendInstance;
      })
      .finally(() => {
        demoLoadPromise = null;
      });
  }
  return demoLoadPromise;
}

export async function enableDemoMode() {
  const instance = await loadDemoBackendModule();
  instance.setDemoMode(true);
  state.demoMode = true;
  if (typeof onDemoActivated === 'function') {
    onDemoActivated();
  }
  return instance;
}

export function disableDemoMode() {
  if (demoBackendInstance) {
    demoBackendInstance.setDemoMode(false);
  }
  state.demoMode = false;
}

export function isDemoModeActive() {
  return Boolean(state.demoMode || (demoBackendInstance && demoBackendInstance.isDemoMode()));
}

export function apiUrl(endpoint) {
  if (!endpoint) return '';
  if (endpoint.startsWith('http://') || endpoint.startsWith('https://')) {
    return endpoint;
  }
  const clean = endpoint.startsWith('/') ? endpoint : `/${endpoint}`;
  if (state.connectionStrategy === 'direct' && state.targetConfig) {
    return `${state.targetConfig.fullUrl}${clean}`;
  }
  return clean;
}

export async function doFetch(url, options = {}) {
  const isTargetingRemote = Boolean(state.targetConfig);

  if (!demoBackendInstance) {
    if (!isTargetingRemote && isExplicitDemoStartup()) {
      if (!startupDemoPromise) {
        startupDemoPromise = enableDemoMode().finally(() => {
          startupDemoPromise = null;
        });
      }
      await startupDemoPromise;
    } else if (state.demoMode) {
      await loadDemoBackendModule();
    }
  }

  const isAbsoluteExternal = typeof url === 'string' &&
    (url.startsWith('http://') || url.startsWith('https://')) &&
    (typeof window === 'undefined' || !window.location || !url.startsWith(window.location.origin));

  if (isDemoModeActive() && demoBackendInstance && !isTargetingRemote && !isAbsoluteExternal) {
    return demoBackendInstance.handleRequest(url, options);
  }

  const targetUrl = apiUrl(url);
  const headers = Object.assign({}, options.headers || {});
  if (state.connectionStrategy === 'proxy' && state.targetConfig) {
    headers['X-Forwarded-Host'] = state.targetConfig.host;
    headers['X-Forwarded-Proto'] = state.targetConfig.proto;
    if (state.targetConfig.prefix) {
      headers['X-Forwarded-Prefix'] = state.targetConfig.prefix;
    }
  }
  const fetchOptions = Object.assign({}, options, { headers });
  return fetch(targetUrl, fetchOptions);
}

export function apiGet(endpoint) {
  return doFetch(endpoint).then(res => {
    if (!res.ok) throw new Error(`HTTP ${res.status} from ${endpoint}`);
    return res.json();
  });
}

export function apiPost(endpoint, payload = null) {
  const opts = { method: 'POST' };
  if (payload !== null) {
    opts.headers = { 'Content-Type': 'application/json' };
    opts.body = typeof payload === 'string' ? payload : JSON.stringify(payload);
  }
  return doFetch(endpoint, opts).then(res => {
    if (!res.ok) throw new Error(`HTTP ${res.status} from ${endpoint}`);
    return res.status === 204 ? {} : res.json().catch(() => ({}));
  });
}

export function apiPut(endpoint, payload) {
  return doFetch(endpoint, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: typeof payload === 'string' ? payload : JSON.stringify(payload)
  }).then(res => {
    if (!res.ok) throw new Error(`HTTP ${res.status} from ${endpoint}`);
    return res.json().catch(() => ({}));
  });
}

export function apiDelete(endpoint) {
  return doFetch(endpoint, { method: 'DELETE' }).then(res => {
    if (!res.ok) throw new Error(`HTTP ${res.status} from ${endpoint}`);
    return res.status === 204 ? {} : res.json().catch(() => ({}));
  });
}

export function buildToggledStubPayload(stub, forceParked = null) {
  const payload = JSON.parse(JSON.stringify(stub || {}));
  delete payload._disabled;

  const targetParked = forceParked !== null ? Boolean(forceParked) : !isStubParked(stub);
  const meta = payload.metadata && typeof payload.metadata === 'object' ? { ...payload.metadata } : {};
  const defaultActivePriority = isProxyStub(stub) ? PRIORITY_ACTIVE_PROXY : PRIORITY_ACTIVE_STUB;

  if (targetParked) {
    if (
      typeof stub.priority === 'number' &&
      stub.priority < PRIORITY_PARKED &&
      stub.priority !== defaultActivePriority
    ) {
      meta.previousPriority = stub.priority;
    }
    payload.priority = PRIORITY_PARKED;
  } else {
    const prev = meta.previousPriority;
    const restored =
      typeof prev === 'number' && prev > 0 && prev < PRIORITY_PARKED ? prev : defaultActivePriority;
    delete meta.previousPriority;
    payload.priority = restored;
  }

  if (Object.keys(meta).length > 0) {
    payload.metadata = meta;
  } else {
    delete payload.metadata;
  }
  return payload;
}

async function resolveStubById(stubId) {
  const cached = stubCatalog.getById(stubId);
  if (cached) return cached;
  return apiGet(`/__admin/mappings/${stubId}`);
}

export async function toggleStubState(stubId) {
  const stub = await resolveStubById(stubId);
  const updated = buildToggledStubPayload(stub);
  return apiPut(`/__admin/mappings/${stubId}`, updated);
}

export function deleteStubMapping(stubId) {
  return apiDelete(`/__admin/mappings/${stubId}`);
}

export function saveStubMapping(stubId, stubData) {
  return apiPut(`/__admin/mappings/${stubId}`, stubData);
}

export function createStubMapping(stubData) {
  return apiPost('/__admin/mappings', stubData);
}

export async function bulkStubs(action, ids) {
  const targetIds = Array.isArray(ids) ? ids.filter(Boolean) : [];
  if (action === 'delete') {
    await Promise.all(targetIds.map(id => deleteStubMapping(id)));
    return { action, processed: targetIds.length };
  }
  if (action === 'disable' || action === 'enable') {
    const wantParked = action === 'disable';
    let processed = 0;
    await Promise.all(
      targetIds.map(async id => {
        const stub = await resolveStubById(id);
        if (isStubParked(stub) === wantParked) return;
        await saveStubMapping(id, buildToggledStubPayload(stub, wantParked));
        processed++;
      })
    );
    return { action, processed };
  }
  throw new Error(`Unsupported bulk action: ${action}`);
}

export function importMappings(payload) {
  return apiPost('/__admin/mappings/import', payload);
}

export function publishAsyncMessage(payload) {
  return apiPost('/__admin/async/publish', payload);
}

export function fetchAsyncDestinations() {
  return apiGet('/__admin/async/destinations')
    .then(data => data.destinations || [])
    .catch(() => []);
}

export function fetchAsyncMessages(protocol, destination) {
  return apiGet(`/__admin/async/destinations/${encodeURIComponent(protocol)}/${encodeURIComponent(destination)}/messages`)
    .then(data => data.messages || [])
    .catch(() => []);
}

export function deleteAsyncMessage(protocol, destination, messageId) {
  return doFetch(`/__admin/async/destinations/${encodeURIComponent(protocol)}/${encodeURIComponent(destination)}/messages/${encodeURIComponent(messageId)}`, {
    method: 'DELETE'
  })
    .then(res => res.status === 204 || res.ok)
    .catch(() => false);
}

export function purgeAsyncDestination(protocol, destination) {
  return doFetch(`/__admin/async/destinations/${encodeURIComponent(protocol)}/${encodeURIComponent(destination)}`, {
    method: 'DELETE'
  })
    .then(res => res.status === 204 || res.ok)
    .catch(() => false);
}

export function publishAsyncDestinationMessage(protocol, destination, messageObj) {
  return doFetch(`/__admin/async/destinations/${encodeURIComponent(protocol)}/${encodeURIComponent(destination)}/messages`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(messageObj)
  })
    .then(res => res.status === 201 || res.ok)
    .catch(() => false);
}

export function fetchAsyncConnections() {
  return apiGet('/__admin/async/connections')
    .then(data => data.connections || [])
    .catch(() => []);
}

export function registerAsyncConnection(connPayload) {
  return doFetch('/__admin/async/connections', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(connPayload)
  })
    .then(res => res.status === 201 || res.ok)
    .catch(() => false);
}

export function deleteAsyncConnection(id) {
  return apiDelete(`/__admin/async/connections/${encodeURIComponent(id)}`)
    .then(() => true)
    .catch(() => false);
}

export function callCasualService(payload) {
  return doFetch('/__admin/casual/call', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  }).then(res => res.json());
}

export function inspectOpenApi(spec) {
  return apiPost('/__admin/openapi/inspect', { spec });
}

export function snapshotProjectRecordings(project, excludedJsonPaths = null, useEqualToJson = false) {
  return apiPost('/__admin/lifecycle/snapshots', {
    project: project || '_all',
    excludedJsonPaths,
    useEqualToJson
  });
}

export function snapshotFilteredRecordings(requestIds, project, activate = false, excludedJsonPaths = null, useEqualToJson = false) {
  return apiPost('/__admin/lifecycle/snapshots', {
    requestIds,
    project,
    activate,
    excludedJsonPaths,
    useEqualToJson
  });
}

export function getSnapshotStubTemplate(requestId, project = null, excludedJsonPaths = null, useEqualToJson = false) {
  return apiPost('/__admin/lifecycle/snapshot-template', {
    requestId,
    project,
    excludedJsonPaths,
    useEqualToJson
  });
}

export function setProjectMode(project, mode) {
  const enc = encodeURIComponent(project);
  return apiPost(`/__admin/lifecycle/projects/${enc}/mode`, { mode });
}

export { detectCapabilities };
