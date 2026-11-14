/**
 * Pure WireMock Admin API client.
 * Zero DOM dependencies and zero circular UI imports.
 */
import { state } from './state.js';
import { detectCapabilities } from './capabilities.js';
import { demoBackend } from './demo-backend.js';
import { PRIORITY_ACTIVE_PROXY, PRIORITY_ACTIVE_STUB, PRIORITY_PARKED, isProxyStub, isStubParked } from './stub-catalog.js';

let onDemoFallbackHandler = null;

export function registerDemoFallbackHandler(fn) {
  onDemoFallbackHandler = typeof fn === 'function' ? fn : null;
}

function activateDemoFallback() {
  demoBackend.setDemoMode(true);
  if (onDemoFallbackHandler) {
    onDemoFallbackHandler();
  }
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

export function doFetch(url, options = {}) {
  if (demoBackend.isDemoMode()) {
    return demoBackend.handleRequest(url, options);
  }
  const isTargetingRemote = Boolean(state.targetConfig);
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

  if (typeof fetch !== 'undefined') {
    return fetch(targetUrl, fetchOptions)
      .then(res => {
        if (!isTargetingRemote && !state.targetConfig && res.status === 404 && url === '/__admin/mappings') {
          activateDemoFallback();
          return demoBackend.handleRequest(url, options);
        }
        return res;
      })
      .catch(err => {
        if (!isTargetingRemote && !state.targetConfig && (url.startsWith('/__admin/') || url.startsWith('/api/'))) {
          activateDemoFallback();
          return demoBackend.handleRequest(url, options);
        }
        throw err;
      });
  }
  return new Promise((resolve, reject) => {
    try {
      const xhr = new XMLHttpRequest();
      xhr.open(fetchOptions.method || 'GET', targetUrl);
      if (fetchOptions.headers) {
        Object.entries(fetchOptions.headers).forEach(([k, v]) => xhr.setRequestHeader(k, v));
      }
      xhr.onload = () => {
        if (!isTargetingRemote && !state.targetConfig && xhr.status === 404 && url === '/__admin/mappings') {
          activateDemoFallback();
          return resolve(demoBackend.handleRequest(url, options));
        }
        const respHeaders = {
          forEach(cb) {
            const raw = xhr.getAllResponseHeaders() || '';
            raw.trim().split(/[\r\n]+/).forEach(line => {
              const parts = line.split(': ');
              if (parts.length >= 2) cb(parts.slice(1).join(': '), parts[0]);
            });
          }
        };
        resolve({
          status: xhr.status,
          statusText: xhr.statusText,
          headers: respHeaders,
          text: () => Promise.resolve(xhr.responseText),
          json: () => Promise.resolve(JSON.parse(xhr.responseText)),
          ok: xhr.status >= 200 && xhr.status < 300
        });
      };
      xhr.onerror = () => {
        if (!isTargetingRemote && !state.targetConfig && (url.startsWith('/__admin/') || url.startsWith('/api/'))) {
          activateDemoFallback();
          return resolve(demoBackend.handleRequest(url, options));
        }
        reject(new Error('Network error from ' + targetUrl));
      };
      xhr.send(fetchOptions.body || null);
    } catch (err) {
      if (!isTargetingRemote && !state.targetConfig && (url.startsWith('/__admin/') || url.startsWith('/api/'))) {
        activateDemoFallback();
        return resolve(demoBackend.handleRequest(url, options));
      }
      reject(err);
    }
  });
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
  delete payload._dynamic;
  delete payload.isDynamic;

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
  const cached = (state.currentStubs || []).find(s => s.id === stubId);
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

export function createStatefulModel(modelData) {
  return apiPost('/__admin/stateful/rules', modelData);
}

export function deleteStatefulSession(correlationId) {
  return apiDelete(`/__admin/stateful/sessions/${encodeURIComponent(correlationId)}`);
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
  const enc = encodeURIComponent(project || '*');
  return apiPost(`/__admin/projects/${enc}/recordings/snapshot`, {
    excludedJsonPaths,
    useEqualToJson
  });
}

export function snapshotFilteredRecordings(requestIds, project, activate = false, excludedJsonPaths = null, useEqualToJson = false) {
  return apiPost('/__admin/stubs/snapshot-filtered', {
    requestIds,
    project,
    activate,
    excludedJsonPaths,
    useEqualToJson
  });
}

export function getSnapshotStubTemplate(requestId, project = null, excludedJsonPaths = null, useEqualToJson = false) {
  return apiPost('/__admin/stubs/snapshot-template', {
    requestId,
    project,
    excludedJsonPaths,
    useEqualToJson
  });
}

export function setProjectMode(project, mode) {
  const enc = encodeURIComponent(project);
  return apiPost(`/__admin/projects/${enc}/mode`, { mode });
}

export { detectCapabilities, demoBackend };
