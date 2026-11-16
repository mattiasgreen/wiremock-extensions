/**
 * In-Browser Mock Backend & Static Showcase Engine (loaded lazily in Demo Mode).
 * Clones live-captured DEMO_SNAPSHOT data and delegates matching & filtering to StubCatalog and filter.js.
 */
import { DEMO_SNAPSHOT } from './demo-snapshot.js';
import {
  StubCatalog,
  PRIORITY_ACTIVE_STUB,
  PRIORITY_ACTIVE_PROXY,
  PRIORITY_PARKED,
  isStubParked,
  isProxyStub,
  isOfflineGuardStub,
  getStubProject
} from './stub-catalog.js';
import { matchesJournalEntry } from './filter.js';
import { state } from './state.js';

const uuid = () => (typeof crypto !== 'undefined' && crypto.randomUUID)
  ? crypto.randomUUID()
  : 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => (((c === 'x' ? Math.random() * 16 : (Math.random() * 16 & 0x3) | 0x8)) | 0).toString(16));

const parseBody = (raw) => {
  if (!raw) return {};
  if (typeof raw !== 'string') return raw;
  try { return JSON.parse(raw); } catch { return {}; }
};

function createFetchResponse(status, headersMap, bodyText) {
  const statusTexts = { 200: 'OK', 201: 'Created', 204: 'No Content', 400: 'Bad Request', 404: 'Not Found', 500: 'Internal Server Error' };
  const headers = {
    forEach(cb) { Object.entries(headersMap).forEach(([k, v]) => cb(v, k)); },
    get(name) {
      const lower = name.toLowerCase();
      const found = Object.entries(headersMap).find(([k]) => k.toLowerCase() === lower);
      return found ? found[1] : null;
    }
  };
  return {
    status,
    statusText: statusTexts[status] || (status >= 200 && status < 300 ? 'OK' : 'Error'),
    ok: status >= 200 && status < 300,
    headers,
    text: () => Promise.resolve(bodyText),
    json: () => { try { return Promise.resolve(JSON.parse(bodyText)); } catch (e) { return Promise.reject(e); } }
  };
}

const resJson = (status, obj, headers = {}) =>
  Promise.resolve(createFetchResponse(status, { 'Content-Type': 'application/json', ...headers }, JSON.stringify(obj, null, 2)));
const resText = (status, text, headers = {}) =>
  Promise.resolve(createFetchResponse(status, { 'Content-Type': 'text/plain', ...headers }, text));

class InMemoryDemoBackend {
  constructor() {
    this.catalog = new StubCatalog();
    this.enabled = false;
    this.reset();
  }

  get stubs() { return this.catalog._mappings; }
  set stubs(list) { this.catalog.setMappings(list); }
  isDemoMode() { return this.enabled; }
  setDemoMode(val) {
    this.enabled = Boolean(val);
    state.demoMode = this.enabled;
  }

  reset() {
    const snap = JSON.parse(JSON.stringify(DEMO_SNAPSHOT));
    this.stubs = snap.mappings || [];
    this.requests = snap.requests || [];
    this.scenarios = snap.scenarios || [];
    this.netemProxies = snap.netemProxies || [];
    this.casualConfig = snap.casualConfig || { enabled: true, port: 7772, running: true };
    this.asyncConnections = snap.asyncConnections || [];
    this.asyncDestinations = snap.asyncDestinations || [];
    this.asyncMessages = snap.asyncMessages || {};
    this.netemJournal = [];
  }

  matchStub(method, path) {
    const cleanPath = path.split('?')[0];
    const sorted = this.stubs.filter(s => s && !isStubParked(s))
      .sort((a, b) => (a.priority ?? PRIORITY_ACTIVE_STUB) - (b.priority ?? PRIORITY_ACTIVE_STUB));

    for (const stub of sorted) {
      const req = stub.request || {};
      if (req.method && req.method.toUpperCase() !== method.toUpperCase() && req.method !== 'ANY') continue;
      const matched = (req.url && (req.url === path || req.url === cleanPath))
        || (req.urlPath && req.urlPath === cleanPath)
        || (req.urlPathTemplate && new RegExp(`^${req.urlPathTemplate.replace(/\{[^}]+\}/g, '[^/]+')}$`).test(cleanPath))
        || (req.urlPattern && new RegExp(req.urlPattern).test(path))
        || (req.urlPathPattern && new RegExp(req.urlPathPattern).test(cleanPath))
        || (!req.url && !req.urlPath && !req.urlPathTemplate && !req.urlPattern && !req.urlPathPattern);
      if (!matched) continue;
      if (stub.scenarioName && stub.requiredScenarioState) {
        const sc = this.scenarios.find(s => s.name === stub.scenarioName);
        if ((sc ? sc.state : 'Started') !== stub.requiredScenarioState) continue;
      }
      return stub;
    }
    return null;
  }

  handleRequest(url, options = {}) {
    const method = (options.method || 'GET').toUpperCase();
    const cleanUrl = url.startsWith('/') ? url : `/${url}`;
    const urlObj = cleanUrl.includes('?') ? new URL(`http://localhost${cleanUrl}`) : null;
    const pathname = urlObj ? urlObj.pathname : cleanUrl;
    const searchParams = urlObj ? urlObj.searchParams : new URLSearchParams();

    if (pathname === '/__admin/ui/version') {
      return resJson(200, {
        version: '1.14.0',
        branch: 'main',
        commit: 'unknown'
      });
    }

    if (pathname === '/__admin/mappings' || pathname === '/__admin/mappings/') {
      if (method === 'GET') return resJson(200, { mappings: this.stubs, meta: { total: this.stubs.length } });
      if (method === 'POST') {
        const body = parseBody(options.body);
        if (!body.id) body.id = uuid();
        this.stubs.unshift(body);
        return resJson(201, body);
      }
    }
    if (pathname === '/__admin/mappings/import' && method === 'POST') {
      const list = parseBody(options.body).mappings || [];
      list.forEach(m => { if (!m.id) m.id = uuid(); this.stubs.unshift(m); });
      return resJson(200, { imported: list.length });
    }
    const mappingMatch = pathname.match(/^\/__admin\/mappings\/([^/]+)$/);
    if (mappingMatch) {
      const stubId = decodeURIComponent(mappingMatch[1]);
      if (method === 'GET') {
        const found = this.stubs.find(s => s.id === stubId);
        return found ? resJson(200, found) : resText(404, 'Mapping not found');
      }
      if (method === 'PUT') {
        const updated = { ...parseBody(options.body), id: stubId };
        const idx = this.stubs.findIndex(s => s.id === stubId);
        if (idx !== -1) { this.stubs[idx] = updated; return resJson(200, updated); }
        this.stubs.unshift(updated);
        return resJson(201, updated);
      }
      if (method === 'DELETE') {
        this.stubs = this.stubs.filter(s => s.id !== stubId);
        return resText(204, '');
      }
    }

    if (pathname.startsWith('/__admin/lifecycle/')) return this.handleLifecycle(pathname, method, options.body);
    if (pathname === '/__admin/requests' || pathname === '/__admin/ui/api/requests') {
      if (method === 'DELETE') { this.requests = []; return resText(204, ''); }
      let list = [...this.requests];
      const q = (searchParams.get('q') || '').trim();
      if (q) list = list.filter(r => matchesJournalEntry(r, q, ''));
      if (searchParams.get('unmatched') === 'true') list = list.filter(r => !r.wasMatched);
      const limit = parseInt(searchParams.get('limit') || '100', 10);
      return resJson(200, { requests: list.slice(0, limit), meta: { total: this.requests.length, filtered: list.length } });
    }
    if (pathname === '/__admin/scenarios' && method === 'GET') return resJson(200, { scenarios: this.scenarios });
    if (pathname === '/__admin/scenarios/reset' && method === 'POST') {
      this.scenarios.forEach(sc => { sc.state = 'Started'; });
      return resJson(200, { scenarios: this.scenarios });
    }
    const scMatch = pathname.match(/^\/__admin\/scenarios\/([^/]+)\/state$/);
    if (scMatch && method === 'PUT') {
      const sc = this.scenarios.find(s => s.name === decodeURIComponent(scMatch[1]));
      if (!sc) return resText(404, 'Scenario not found');
      sc.state = parseBody(options.body).state || 'Started';
      return resJson(200, sc);
    }

    if (pathname.startsWith('/__admin/tcp-netem/')) return this.handleNetEm(pathname, method, options.body);
    if (pathname.startsWith('/__admin/casual/')) return this.handleCasual(pathname, method, options.body);
    if (pathname.startsWith('/__admin/async/')) return this.handleAsync(pathname, method, options.body);
    if (pathname === '/__admin/journal/stats' && method === 'GET') {
      return resJson(200, { totalEvents: this.requests.length, rawBytesTotal: this.requests.length * 1024, compressedBytesTotal: Math.round((this.requests.length * 1024) / 16.5), compressionRatio: 16.5, activeDictionaries: 2, totalEvictions: 0, usedMemoryBytes: 1048576, maxMemoryBytes: 104857600 });
    }
    if (pathname === '/__admin/openapi/inspect' && method === 'POST') {
      return resJson(200, { title: 'Demo OpenAPI Spec', version: '1.0.0', endpoints: [{ method: 'GET', path: '/api/v1/users', summary: 'List Users' }, { method: 'POST', path: '/api/v1/orders', summary: 'Create Order' }] });
    }
    if (pathname === '/__admin/metrics/prometheus') {
      return resText(200, '# HELP wiremock_http_requests_total Total HTTP requests handled\n# TYPE wiremock_http_requests_total counter\nwiremock_http_requests_total{status="200"} 42\nwiremock_http_requests_total{status="201"} 8\nwiremock_http_requests_total{status="404"} 3\n', { 'Content-Type': 'text/plain; version=0.0.4' });
    }

    return this.executeHttp(method, pathname, cleanUrl, options);
  }

  handleLifecycle(pathname, method, rawBody) {
    if (pathname === '/__admin/lifecycle/status' && method === 'GET') return resJson(200, { status: 'UP' });
    const projMatch = pathname.match(/^\/__admin\/lifecycle\/projects\/([^/]+)\/mode$/);
    if (projMatch && method === 'POST') {
      const project = decodeURIComponent(projMatch[1]);
      const requestedMode = String(parseBody(rawBody).mode || 'stubs').toLowerCase();
      const allProjects = !project || project === '*' || project.toLowerCase() === '_all';
      let stubsEnabled = 0;
      let stubsDisabled = 0;
      this.stubs.forEach(stub => {
        if (isOfflineGuardStub(stub) || (!allProjects && getStubProject(stub) !== project)) return;
        const isProxy = isProxyStub(stub);
        const parked = isStubParked(stub);
        const wantActive = requestedMode === 'proxy' ? isProxy : !isProxy;
        if (wantActive && parked) { stub.priority = isProxy ? PRIORITY_ACTIVE_PROXY : PRIORITY_ACTIVE_STUB; stubsEnabled++; }
        else if (!wantActive && !parked) { stub.priority = PRIORITY_PARKED; stubsDisabled++; }
      });
      return resJson(200, { project: allProjects ? '_all' : project, activeMode: requestedMode, stubsEnabled, stubsDisabled });
    }
    if (pathname === '/__admin/lifecycle/snapshots' && method === 'POST') {
      const body = parseBody(rawBody);
      const count = Array.isArray(body.requestIds) ? this.requests.filter(r => body.requestIds.includes(r.id)).length : 1;
      return resJson(200, { totalRecorded: count, snapshotted: count, status: body.activate ? 'active' : 'disabled', mappings: [] });
    }
    if (pathname === '/__admin/lifecycle/snapshot-template' && method === 'POST') {
      const body = parseBody(rawBody);
      const found = this.requests.find(r => r.id === body.requestId);
      const req = (found && found.request) || {};
      const res = (found && found.response) || {};
      return resJson(200, {
        name: `[RECORDED ${req.method || 'GET'} ${res.status || 200}] ${req.url || '/'}`,
        priority: PRIORITY_ACTIVE_STUB,
        request: { method: req.method || 'GET', url: req.url || '/' },
        response: { status: res.status || 200, body: res.body || '' },
        metadata: { source: 'recorded-proxy', project: body.project || 'Recorded Traffic', mode: 'static' }
      });
    }
    return resText(404, 'Not found');
  }

  handleNetEm(pathname, method, rawBody) {
    if (pathname === '/__admin/tcp-netem/proxies' && method === 'GET') return resJson(200, this.netemProxies);
    if (pathname === '/__admin/tcp-netem/reset' && method === 'POST') {
      this.netemProxies.forEach(p => { p.toxics = []; });
      return resJson(200, { reset: true });
    }
    if (pathname === '/__admin/tcp-netem/journal') {
      if (method === 'GET') return resJson(200, this.netemJournal);
      if (method === 'DELETE') { this.netemJournal = []; return resText(204, ''); }
    }
    const findProxy = (name) => this.netemProxies.find(p => p.name === decodeURIComponent(name)) || this.netemProxies[0];
    const toxicList = pathname.match(/^\/__admin\/tcp-netem\/proxies\/([^/]+)\/toxics$/);
    if (toxicList && method === 'POST') {
      const proxy = findProxy(toxicList[1]);
      if (!proxy) return resText(404, 'Proxy not found');
      const toxic = parseBody(rawBody);
      proxy.toxics = (proxy.toxics || []).filter(t => t.name !== toxic.name).concat(toxic);
      return resJson(200, toxic);
    }
    const toxicItem = pathname.match(/^\/__admin\/tcp-netem\/proxies\/([^/]+)\/toxics\/([^/]+)$/);
    if (toxicItem && method === 'DELETE') {
      const proxy = findProxy(toxicItem[1]);
      if (proxy) proxy.toxics = (proxy.toxics || []).filter(t => t.name !== decodeURIComponent(toxicItem[2]));
      return resText(204, '');
    }
    const presetMatch = pathname.match(/^\/__admin\/tcp-netem\/proxies\/([^/]+)\/presets\/([^/]+)$/);
    if (presetMatch && method === 'POST') {
      const proxy = findProxy(presetMatch[1]);
      if (proxy) proxy.toxics = [{ name: decodeURIComponent(presetMatch[2]).toLowerCase(), type: 'latency', stream: 'downstream', toxicity: 1.0, attributes: { latency: 120, jitter: 30 } }];
      return resJson(200, { applied: true });
    }
    return resText(404, 'Not found');
  }

  handleCasual(pathname, method, rawBody) {
    if (pathname === '/__admin/casual/config' && method === 'GET') return resJson(200, this.casualConfig);
    if (pathname === '/__admin/casual/call' && method === 'POST') {
      const payload = parseBody(rawBody);
      const service = payload.service || 'echoService';
      const bufferType = payload.bufferType || 'JSON';
      const reqData = payload.payload || '';
      const matched = this.stubs.find(s => !isStubParked(s) && ((s.request && (s.request.url || s.request.urlPattern || s.request.urlPath) === `/__casual/services/${service}`) || s.metadata?.service === service));
      let replyPayload = reqData;
      let replyStatus = 200;
      const replyHeaders = { 'Content-Type': 'application/json; charset=utf-8', 'X-Casual-Service': service, 'X-Casual-Error-State': 'OK', 'X-Simulation-Engine': 'in-browser-demo' };

      if (matched?.response) {
        replyStatus = matched.response.status || 200;
        replyPayload = matched.response.body || '';
        if (service === 'echoService' && reqData) {
          try { const resObj = JSON.parse(replyPayload); resObj.echoData = JSON.parse(reqData); replyPayload = JSON.stringify(resObj, null, 2); } catch { /* static */ }
        }
        if (matched.response.headers) Object.assign(replyHeaders, matched.response.headers);
      } else if (bufferType === 'JSON') {
        try { replyPayload = JSON.stringify({ status: 'SUCCESS', xatmiReturnCode: 0, service, transactionId: `XID-${Date.now()}`, echoData: JSON.parse(reqData) }, null, 2); }
        catch { replyPayload = JSON.stringify({ status: 'SUCCESS', service }); }
      }

      const entry = {
        id: uuid(), sourceIp: '127.0.0.1', sourceHost: 'localhost',
        request: { url: `/__casual/services/${service}`, absoluteUrl: `http://localhost:8080/__casual/services/${service}`, method: 'POST', headers: { 'Content-Type': 'application/json', 'x-casual-service': service, 'x-casual-buffer-type': bufferType }, body: reqData, loggedDate: Date.now() },
        response: { status: replyStatus, headers: replyHeaders, body: replyPayload },
        wasMatched: Boolean(matched), timing: { serveTime: 10, totalTime: 14 }, stubMapping: matched || undefined
      };
      this.requests.unshift(entry);
      const isOk = replyStatus >= 200 && replyStatus < 300;
      return resJson(200, { status: isOk ? 0 : -1, errorState: isOk ? 'OK' : 'TPESVCFAIL', errorCode: isOk ? 0 : 11, userCode: 0, durationMs: 12, execution: entry.id, service, bufferType, payload: replyPayload, isBinary: false, sizeBytes: replyPayload ? new Blob([replyPayload]).size : 0, headers: replyHeaders, requestHeaders: entry.request.headers, executionTimeMs: 12 });
    }
    return resText(404, 'Not found');
  }

  handleAsync(pathname, method, rawBody) {
    const syncCount = (proto, name, key) => {
      const dest = this.asyncDestinations.find(x => x.protocol === proto && x.name === name);
      if (dest) dest.messageCount = (this.asyncMessages[key] || []).length;
    };
    if (pathname === '/__admin/async/status' && method === 'GET') {
      const consumed = this.requests.filter(r => { const u = r.request?.url || ''; return u.startsWith('/__async/') || u.startsWith('/__casual/queues/'); }).length;
      return resJson(200, { status: 'UP', beta: true, mode: 'MOCK', kafkaSinkPort: 9092, messagesConsumed: consumed, messagesPublished: 24, activeBrokers: 2 });
    }
    if (pathname === '/__admin/async/publish' && method === 'POST') {
      const payload = parseBody(rawBody);
      const dest = payload.destination || 'orders.inbound';
      const proto = (payload.protocol || 'amqp').toLowerCase();
      const id = `req-demo-pub-${Date.now()}`;
      this.requests.unshift({
        id, sourceIp: '127.0.0.1', sourceHost: 'localhost',
        request: { url: `/__async/${proto}/${dest}`, absoluteUrl: `http://localhost:8080/__async/${proto}/${dest}`, method: proto === 'kafka' ? 'PRODUCE' : 'PUBLISH', headers: { 'Content-Type': 'application/json', 'X-Async-Channel': dest, 'X-Async-Protocol': proto, 'X-Async-Correlation-Id': payload.correlationId || `corr-${Date.now()}` }, body: typeof payload.payload === 'string' ? payload.payload : JSON.stringify(payload.payload || {}, null, 2), loggedDate: Date.now() },
        response: { status: 200, headers: { 'Content-Type': 'application/json; charset=utf-8' }, body: JSON.stringify({ status: 'ACKNOWLEDGED', destination: dest }) },
        wasMatched: true, timing: { serveTime: 4, totalTime: 6 }
      });
      if (proto === 'kafka') {
        const partition = payload.partition !== undefined && payload.partition !== '' ? parseInt(payload.partition, 10) : 0;
        return resJson(200, { published: true, id, protocol: 'kafka', destination: dest, partition, offset: 42 + Math.floor(Math.random() * 100), key: payload.key || null, publishedAt: new Date().toISOString() });
      }
      if (payload.replyTo) {
        return resJson(200, { published: true, id, protocol: 'amqp', destination: dest, settlement: 'ACCEPTED', replyReceived: true, replyQueue: payload.replyTo, replyCorrelationId: payload.correlationId || `corr-${Date.now()}`, replyPayload: JSON.stringify({ orderId: `ORD-${Math.floor(Math.random() * 9000 + 1000)}`, status: 'CONFIRMED', processedAt: new Date().toISOString() }, null, 2) });
      }
      return resJson(200, { published: true, id, protocol: 'amqp', destination: dest, settlement: 'ACCEPTED', publishedAt: new Date().toISOString() });
    }
    if (pathname === '/__admin/async/destinations' && method === 'GET') return resJson(200, { destinations: this.asyncDestinations });

    const destMsg = pathname.match(/^\/__admin\/async\/destinations\/([^/]+)\/([^/]+)\/messages$/);
    if (destMsg) {
      const [proto, name] = [decodeURIComponent(destMsg[1]), decodeURIComponent(destMsg[2])];
      const key = `${proto}/${name}`;
      if (method === 'GET') return resJson(200, { messages: this.asyncMessages[key] || [] });
      if (method === 'POST') {
        const body = parseBody(rawBody);
        if (!body.id) body.id = `msg-demo-${Date.now()}`;
        Object.assign(body, { protocol: proto, destination: name, timestamp: Date.now(), size: body.payload ? body.payload.length : 0 });
        this.asyncMessages[key] = this.asyncMessages[key] || [];
        const idx = this.asyncMessages[key].findIndex(m => m.id === body.id);
        if (idx !== -1) this.asyncMessages[key][idx] = body; else this.asyncMessages[key].unshift(body);
        syncCount(proto, name, key);
        return resJson(201, body);
      }
    }
    const singleMsg = pathname.match(/^\/__admin\/async\/destinations\/([^/]+)\/([^/]+)\/messages\/([^/]+)$/);
    if (singleMsg && method === 'DELETE') {
      const [proto, name, msgId] = [decodeURIComponent(singleMsg[1]), decodeURIComponent(singleMsg[2]), decodeURIComponent(singleMsg[3])];
      const key = `${proto}/${name}`;
      if (this.asyncMessages[key]) { this.asyncMessages[key] = this.asyncMessages[key].filter(m => m.id !== msgId); syncCount(proto, name, key); }
      return resText(204, '');
    }
    const purgeDest = pathname.match(/^\/__admin\/async\/destinations\/([^/]+)\/([^/]+)$/);
    if (purgeDest && method === 'DELETE') {
      const [proto, name] = [decodeURIComponent(purgeDest[1]), decodeURIComponent(purgeDest[2])];
      this.asyncMessages[`${proto}/${name}`] = [];
      syncCount(proto, name, `${proto}/${name}`);
      return resText(204, '');
    }
    if (pathname === '/__admin/async/connections') {
      if (method === 'GET') return resJson(200, { count: this.asyncConnections.length, connections: this.asyncConnections });
      if (method === 'POST') {
        const body = parseBody(rawBody);
        const list = Array.isArray(body) ? body : (Array.isArray(body.connections) ? body.connections : [body]);
        const registered = list.filter(i => i && i.id).map(i => ({ id: String(i.id).trim(), protocol: (i.protocol || 'kafka').toLowerCase(), name: i.name || i.id, endpoint: i.endpoint || '', status: 'HEALTHY', properties: i.properties || {} }));
        registered.forEach(c => { this.asyncConnections = this.asyncConnections.filter(x => x.id !== c.id).concat(c); });
        return registered.length ? resJson(201, { count: registered.length, connections: registered }) : resJson(400, { error: 'id is required' });
      }
    }
    const delConn = pathname.match(/^\/__admin\/async\/connections\/([^/]+)$/);
    if (delConn && method === 'DELETE') {
      const id = decodeURIComponent(delConn[1]);
      this.asyncConnections = this.asyncConnections.filter(c => c.id !== id);
      return resText(204, '');
    }
    return resText(404, 'Not found');
  }

  executeHttp(method, pathname, cleanUrl, options) {
    const matched = this.matchStub(method, pathname);
    const status = matched ? (matched.response?.status || 200) : 404;
    const headers = matched ? (matched.response?.headers || { 'Content-Type': 'application/json' }) : { 'Content-Type': 'text/plain' };
    let body = matched ? (matched.response?.body || '') : 'Request was not matched by any stub mapping.';
    if (matched && !body && matched.response?.jsonBody) body = JSON.stringify(matched.response.jsonBody, null, 2);
    if (matched?.scenarioName && matched?.newScenarioState) {
      const sc = this.scenarios.find(s => s.name === matched.scenarioName);
      if (sc) sc.state = matched.newScenarioState;
    }
    this.requests.unshift({
      id: uuid(), sourceIp: '127.0.0.1', sourceHost: 'localhost',
      request: { url: cleanUrl, absoluteUrl: `http://localhost:8080${cleanUrl}`, method, headers: { ...(options.headers || {}) }, body: options.body || '', loggedDate: Date.now() },
      response: { status, headers, body },
      wasMatched: Boolean(matched), timing: { serveTime: matched ? 8 : 3, totalTime: matched ? 10 : 4 }, stubMapping: matched || undefined
    });
    return Promise.resolve(createFetchResponse(status, headers, body));
  }
}

const demoBackend = new InMemoryDemoBackend();
export { demoBackend };
