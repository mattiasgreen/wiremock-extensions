/**
 * In-Browser Mock Backend & Static Showcase Engine.
 *
 * Provides a self-contained, 100% interactive WireMock simulation engine
 * running directly inside the browser. Powers GitHub Pages and static previews
 * with preloaded stubs, rich request journal traffic, scenario state machines,
 * NetEm toxic configs, and Casual XATMI simulation.
 */
import { matchesJournalEntry } from './filter.js';

import { SEED_STUBS, SEED_DISABLED_STUBS } from './demo-fixtures-stubs.js';
import { SEED_REQUESTS } from './demo-fixtures-journal.js';
import {
  SEED_SCENARIOS,
  SEED_STATEFUL_MODELS,
  SEED_NETEM_PROXIES,
  SEED_CASUAL_SERVICES,
  SEED_ASYNC_CONNECTIONS,
  SEED_ASYNC_DESTINATIONS,
  SEED_ASYNC_MESSAGES
} from './demo-fixtures-protocols.js';

function generateUuid() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

class InMemoryDemoBackend {
  constructor() {
    this.stubs = JSON.parse(JSON.stringify([...SEED_STUBS, ...SEED_DISABLED_STUBS]));
    this.requests = JSON.parse(JSON.stringify(SEED_REQUESTS));
    this.scenarios = JSON.parse(JSON.stringify(SEED_SCENARIOS));
    this.statefulModels = JSON.parse(JSON.stringify(SEED_STATEFUL_MODELS));
    this.netemProxies = JSON.parse(JSON.stringify(SEED_NETEM_PROXIES));
    this.casualServices = JSON.parse(JSON.stringify(SEED_CASUAL_SERVICES));
    this.asyncConnections = JSON.parse(JSON.stringify(SEED_ASYNC_CONNECTIONS));
    this.asyncDestinations = JSON.parse(JSON.stringify(SEED_ASYNC_DESTINATIONS));
    this.asyncMessages = JSON.parse(JSON.stringify(SEED_ASYNC_MESSAGES));
    this.enabled = this.shouldAutoEnable();
  }

  shouldAutoEnable() {
    if (typeof window === 'undefined') return false;
    const params = new URLSearchParams(window.location.search || '');
    if (params.get('demo') === 'true') return true;
    if (params.get('demo') === 'false') return false;
    if (window.location.protocol === 'file:') return true;
    if (window.location.hostname && window.location.hostname.endsWith('github.io')) {
      const target = params.get('wiremock') || params.get('target');
      return !target;
    }
    return false;
  }

  isDemoMode() {
    return this.enabled;
  }

  setDemoMode(val) {
    this.enabled = !!val;
  }

  reset() {
    this.stubs = JSON.parse(JSON.stringify([...SEED_STUBS, ...SEED_DISABLED_STUBS]));
    this.requests = JSON.parse(JSON.stringify(SEED_REQUESTS));
    this.scenarios = JSON.parse(JSON.stringify(SEED_SCENARIOS));
    this.statefulModels = JSON.parse(JSON.stringify(SEED_STATEFUL_MODELS));
    this.netemProxies = JSON.parse(JSON.stringify(SEED_NETEM_PROXIES));
    this.casualServices = JSON.parse(JSON.stringify(SEED_CASUAL_SERVICES));
  }

  matchStub(method, path) {
    const cleanPath = path.split('?')[0];
    const sorted = [...this.stubs].sort((a, b) => (a.priority ?? 5) - (b.priority ?? 5));
    for (const stub of sorted) {
      const req = stub.request || {};
      if (req.method && req.method.toUpperCase() !== method.toUpperCase() && req.method !== 'ANY') {
        continue;
      }
      let matched = false;
      if (req.url && (req.url === path || req.url === cleanPath)) {
        matched = true;
      } else if (req.urlPath && req.urlPath === cleanPath) {
        matched = true;
      } else if (req.urlPattern && new RegExp(req.urlPattern).test(path)) {
        matched = true;
      } else if (req.urlPathPattern && new RegExp(req.urlPathPattern).test(cleanPath)) {
        matched = true;
      } else if (!req.url && !req.urlPath && !req.urlPattern && !req.urlPathPattern) {
        matched = true;
      }

      if (!matched) continue;

      if (stub.scenarioName && stub.requiredScenarioState) {
        const sc = this.scenarios.find(s => s.name === stub.scenarioName);
        const curr = sc ? sc.state : 'Started';
        if (curr !== stub.requiredScenarioState) {
          continue;
        }
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

    // Helper to produce mock responses
    const responseJson = (status, obj, headers = {}) => {
      const body = JSON.stringify(obj, null, 2);
      const respHeaders = Object.assign({ 'Content-Type': 'application/json' }, headers);
      return Promise.resolve(createFetchResponse(status, respHeaders, body));
    };

    const responseText = (status, text, headers = {}) => {
      const respHeaders = Object.assign({ 'Content-Type': 'text/plain' }, headers);
      return Promise.resolve(createFetchResponse(status, respHeaders, text));
    };

    // 1. Admin Mappings
    if (pathname === '/__admin/mappings' || pathname === '/__admin/mappings/') {
      if (method === 'GET') {
        return responseJson(200, {
          mappings: this.stubs,
          meta: { total: this.stubs.length }
        });
      }
      if (method === 'POST') {
        const body = typeof options.body === 'string' ? JSON.parse(options.body || '{}') : options.body || {};
        if (!body.id) body.id = generateUuid();
        this.stubs.unshift(body);
        return responseJson(201, body);
      }
    }

    if (pathname === '/__admin/mappings/import' && method === 'POST') {
      const payload = typeof options.body === 'string' ? JSON.parse(options.body || '{}') : options.body || {};
      const newMappings = payload.mappings || [];
      newMappings.forEach(m => {
        if (!m.id) m.id = generateUuid();
        this.stubs.unshift(m);
      });
      return responseJson(200, { imported: newMappings.length });
    }

    // Mapping by ID
    const mappingMatch = pathname.match(/^\/__admin\/mappings\/([^/]+)$/);
    if (mappingMatch) {
      const stubId = mappingMatch[1];
      if (method === 'GET') {
        const found = this.stubs.find(s => s.id === stubId);
        return found ? responseJson(200, found) : responseText(404, 'Mapping not found');
      }
      if (method === 'PUT') {
        const body = typeof options.body === 'string' ? JSON.parse(options.body || '{}') : options.body || {};
        const idx = this.stubs.findIndex(s => s.id === stubId);
        if (idx !== -1) {
          this.stubs[idx] = Object.assign({}, body, { id: stubId });
          return responseJson(200, this.stubs[idx]);
        }
        this.stubs.unshift(Object.assign({}, body, { id: stubId }));
        return responseJson(201, body);
      }
      if (method === 'DELETE') {
        this.stubs = this.stubs.filter(s => s.id !== stubId);
        return responseText(204, '');
      }
    }

    // 2. Lifecycle Status Probe (parking/enable/disable use /__admin/mappings/{id})
    if (pathname === '/__admin/lifecycle/status' && method === 'GET') {
      return responseJson(200, { status: 'UP' });
    }

    // 3. Request Journal
    if (pathname === '/__admin/requests' || pathname === '/__admin/ui/api/requests') {
      if (method === 'GET') {
        let list = [...this.requests];
        const q = (searchParams.get('q') || '').trim();
        if (q) {
          list = list.filter(r => matchesJournalEntry(r, q));
        }
        if (searchParams.get('unmatched') === 'true') {
          list = list.filter(r => !r.wasMatched);
        }
        const limit = parseInt(searchParams.get('limit') || '100', 10);
        const paged = list.slice(0, limit);
        return responseJson(200, {
          requests: paged,
          meta: {
            total: this.requests.length,
            filtered: list.length
          }
        });
      }
      if (method === 'DELETE') {
        this.requests = [];
        return responseText(204, '');
      }
    }

    // 4. Scenarios
    if (pathname === '/__admin/scenarios') {
      if (method === 'GET') {
        return responseJson(200, { scenarios: this.scenarios });
      }
    }

    if (pathname === '/__admin/scenarios/reset' && method === 'POST') {
      this.scenarios.forEach(sc => {
        sc.state = 'Started';
      });
      return responseJson(200, { scenarios: this.scenarios });
    }

    const scStateMatch = pathname.match(/^\/__admin\/scenarios\/([^/]+)\/state$/);
    if (scStateMatch && method === 'PUT') {
      const scName = decodeURIComponent(scStateMatch[1]);
      const body = typeof options.body === 'string' ? JSON.parse(options.body || '{}') : options.body || {};
      const sc = this.scenarios.find(s => s.name === scName);
      if (sc) {
        sc.state = body.state || 'Started';
        return responseJson(200, sc);
      }
      return responseText(404, 'Scenario not found');
    }

    // 5. Stateful Dynamic Rules
    if (pathname === '/__admin/stateful/models') {
      if (method === 'GET') {
        return responseJson(200, this.statefulModels);
      }
    }

    if (pathname === '/__admin/stateful/rules' && method === 'POST') {
      const body = typeof options.body === 'string' ? JSON.parse(options.body || '{}') : options.body || {};
      this.statefulModels.unshift(body);
      return responseJson(200, body);
    }

    if (pathname.startsWith('/__admin/stateful/sessions/') && method === 'DELETE') {
      return responseText(204, '');
    }

    // 6. NetEm Proxies
    if (pathname === '/__admin/tcp-netem/proxies') {
      if (method === 'GET') {
        return responseJson(200, this.netemProxies);
      }
    }

    if (pathname === '/__admin/tcp-netem/reset' && method === 'POST') {
      return responseJson(200, { reset: true });
    }

    if (pathname === '/__admin/tcp-netem/journal') {
      if (method === 'GET') return responseJson(200, { journal: [] });
      if (method === 'DELETE') return responseText(204, '');
    }

    // 7. Casual Protocol Gateway
    if (pathname === '/__admin/casual/config') {
      if (method === 'GET') {
        return responseJson(200, { services: this.casualServices });
      }
    }

    // 8. Async Messaging & Queue Simulation (Beta)
    if (pathname === '/__admin/async/status') {
      if (method === 'GET') {
        const asyncRequests = this.requests.filter(r => {
          const u = (r.request && r.request.url) || '';
          return u.startsWith('/__async/') || u.startsWith('/__casual/queues/');
        });
        return responseJson(200, {
          status: 'UP',
          beta: true,
          mode: 'MOCK',
          kafkaSinkPort: 9092,
          messagesConsumed: asyncRequests.length,
          messagesPublished: 24,
          activeBrokers: 2
        });
      }
    }

    if (pathname === '/__admin/async/journal') {
      if (method === 'GET') {
        return responseJson(200, {
          journal: [
            'KAFKA_CONSUME:orders.events:{"orderId":"ORD-KAFKA-8821"}',
            'AMQP_CONSUME:payments.requests:{"payment":{"paymentId":"PAY-EUR-9921"}}',
            'PUBLISH:payments.replies:{"transactionId":"TX-AMQP-992144"}',
            'ASYNC_PROXY_ERROR:502:orders.faulty:Destination queue missing on remote broker'
          ]
        });
      }
    }

    if (pathname === '/__admin/async/harvested' || pathname === '/__admin/async/stubs') {
      if (method === 'GET') {
        return responseJson(200, { count: 0, harvested: [] });
      }
    }

    if (pathname === '/__admin/async/publish' && method === 'POST') {
      const payload = typeof options.body === 'string' ? JSON.parse(options.body || '{}') : options.body || {};
      const dest = payload.destination || 'orders.inbound';
      const proto = (payload.protocol || 'amqp').toLowerCase();
      const opMethod = proto === 'kafka' ? 'PRODUCE' : 'PUBLISH';
      const syntheticReq = {
        id: `req-demo-pub-${Date.now()}`,
        request: {
          url: `/__async/${proto}/${dest}`,
          absoluteUrl: `http://localhost:8080/__async/${proto}/${dest}`,
          method: opMethod,
          headers: {
            'Content-Type': 'application/json',
            'X-Async-Channel': dest,
            'X-Async-Protocol': proto,
            'X-Async-Correlation-Id': payload.correlationId || `corr-${Date.now()}`
          },
          body: typeof payload.payload === 'string' ? payload.payload : JSON.stringify(payload.payload || {}, null, 2),
          loggedDate: Date.now()
        },
        response: {
          status: 200,
          headers: { 'Content-Type': 'application/json; charset=utf-8' },
          body: JSON.stringify({ status: 'ACKNOWLEDGED', destination: dest })
        },
        wasMatched: true,
        timing: { serveTimeMillis: 4, totalTimeMillis: 6 }
      };
      this.requests.unshift(syntheticReq);

      if (proto === 'kafka') {
        const partition = (payload.partition !== undefined && payload.partition !== '') ? parseInt(payload.partition, 10) : 0;
        return responseJson(200, {
          published: true,
          id: syntheticReq.id,
          protocol: 'kafka',
          destination: dest,
          partition: partition,
          offset: 42 + Math.floor(Math.random() * 100),
          key: payload.key || null,
          publishedAt: new Date().toISOString()
        });
      } else {
        const hasReplyTo = !!payload.replyTo;
        if (hasReplyTo) {
          return responseJson(200, {
            published: true,
            id: syntheticReq.id,
            protocol: 'amqp',
            destination: dest,
            settlement: 'ACCEPTED',
            replyReceived: true,
            replyQueue: payload.replyTo,
            replyCorrelationId: payload.correlationId || `corr-${Date.now()}`,
            replyPayload: JSON.stringify({
              orderId: 'ORD-' + Math.floor(Math.random() * 9000 + 1000),
              status: 'CONFIRMED',
              processedAt: new Date().toISOString()
            }, null, 2)
          });
        }
        return responseJson(200, {
          published: true,
          id: syntheticReq.id,
          protocol: 'amqp',
          destination: dest,
          settlement: 'ACCEPTED',
          publishedAt: new Date().toISOString()
        });
      }
    }

    // 8b. Async Destinations & Messages Explorer (ADR 0023)
    if (pathname === '/__admin/async/destinations' && method === 'GET') {
      return responseJson(200, {
        destinations: this.asyncDestinations
      });
    }

    const destMsgMatch = pathname.match(/^\/__admin\/async\/destinations\/([^/]+)\/([^/]+)\/messages$/);
    if (destMsgMatch) {
      const proto = decodeURIComponent(destMsgMatch[1]);
      const destName = decodeURIComponent(destMsgMatch[2]);
      const key = `${proto}/${destName}`;

      if (method === 'GET') {
        const msgs = this.asyncMessages[key] || [];
        return responseJson(200, { messages: msgs });
      }

      if (method === 'POST') {
        const body = typeof options.body === 'string' ? JSON.parse(options.body || '{}') : options.body || {};
        if (!body.id) body.id = 'msg-demo-' + Date.now();
        body.protocol = proto;
        body.destination = destName;
        body.timestamp = Date.now();
        body.size = body.payload ? body.payload.length : 0;
        if (!this.asyncMessages[key]) {
          this.asyncMessages[key] = [];
        }
        const existingIdx = this.asyncMessages[key].findIndex(m => m.id === body.id);
        if (existingIdx !== -1) {
          this.asyncMessages[key][existingIdx] = body;
        } else {
          this.asyncMessages[key].unshift(body);
        }
        const d = this.asyncDestinations.find(x => x.protocol === proto && x.name === destName);
        if (d) d.messageCount = this.asyncMessages[key].length;
        return responseJson(201, body);
      }
    }

    const singleMsgMatch = pathname.match(/^\/__admin\/async\/destinations\/([^/]+)\/([^/]+)\/messages\/([^/]+)$/);
    if (singleMsgMatch && method === 'DELETE') {
      const proto = decodeURIComponent(singleMsgMatch[1]);
      const destName = decodeURIComponent(singleMsgMatch[2]);
      const msgId = decodeURIComponent(singleMsgMatch[3]);
      const key = `${proto}/${destName}`;
      if (this.asyncMessages[key]) {
        this.asyncMessages[key] = this.asyncMessages[key].filter(m => m.id !== msgId);
        const d = this.asyncDestinations.find(x => x.protocol === proto && x.name === destName);
        if (d) d.messageCount = this.asyncMessages[key].length;
      }
      return responseText(204, '');
    }

    const purgeDestMatch = pathname.match(/^\/__admin\/async\/destinations\/([^/]+)\/([^/]+)$/);
    if (purgeDestMatch && method === 'DELETE') {
      const proto = decodeURIComponent(purgeDestMatch[1]);
      const destName = decodeURIComponent(purgeDestMatch[2]);
      const key = `${proto}/${destName}`;
      this.asyncMessages[key] = [];
      const d = this.asyncDestinations.find(x => x.protocol === proto && x.name === destName);
      if (d) d.messageCount = 0;
      return responseText(204, '');
    }

    // Async Connections (ADR 0025)
    if (pathname === '/__admin/async/connections') {
      if (method === 'GET') {
        return responseJson(200, {
          count: this.asyncConnections.length,
          connections: this.asyncConnections
        });
      }
      if (method === 'POST') {
        const body = typeof options.body === 'string' ? JSON.parse(options.body || '{}') : options.body || {};
        const incoming = Array.isArray(body)
          ? body
          : (Array.isArray(body.connections) ? body.connections : [body]);

        const registered = [];
        for (const item of incoming) {
          if (!item || !item.id) continue;
          const conn = {
            id: String(item.id).trim(),
            protocol: (item.protocol || 'kafka').toLowerCase(),
            name: item.name || item.id,
            endpoint: item.endpoint || '',
            status: 'HEALTHY',
            properties: item.properties || {}
          };
          this.asyncConnections = this.asyncConnections.filter(c => c.id !== conn.id);
          this.asyncConnections.push(conn);
          registered.push(conn);
        }

        if (registered.length === 0) {
          return responseJson(400, { error: 'No valid connection objects found (id is required)' });
        }
        return responseJson(201, {
          count: registered.length,
          connections: registered
        });
      }
    }

    const deleteConnMatch = pathname.match(/^\/__admin\/async\/connections\/([^/]+)$/);
    if (deleteConnMatch && method === 'DELETE') {
      const connId = decodeURIComponent(deleteConnMatch[1]);
      this.asyncConnections = this.asyncConnections.filter(c => c.id !== connId);
      return responseText(204, '');
    }

    // 9. Journal Compression & Anti-OOM (Beta)
    if (pathname === '/__admin/journal/stats') {
      if (method === 'GET') {
        return responseJson(200, {
          totalEvents: this.requests.length,
          rawBytesTotal: this.requests.length * 1024,
          compressedBytesTotal: Math.round(this.requests.length * 1024 / 16.5),
          compressionRatio: 16.5,
          activeDictionaries: 2,
          totalEvictions: 0,
          usedMemoryBytes: 1048576,
          maxMemoryBytes: 104857600
        });
      }
    }

    if (pathname === '/__admin/casual/call' && method === 'POST') {
      const payload = typeof options.body === 'string' ? JSON.parse(options.body || '{}') : options.body || {};
      const service = payload.service || 'echoService';
      const bufferType = payload.bufferType || 'JSON';
      const reqData = payload.payload || '';

      const matchedStub = this.stubs.find(s => {
        const u = s.request && (s.request.url || s.request.urlPattern || s.request.urlPath);
        return u === `/__casual/services/${service}` || (s.metadata && s.metadata.service === service);
      });

      let replyPayload = reqData;
      let replyStatus = 200;
      let replyHeaders = {
        'Content-Type': 'application/json; charset=utf-8',
        'X-Casual-Service': service,
        'X-Casual-Error-State': 'OK',
        'X-Simulation-Engine': 'in-browser-demo'
      };

      if (matchedStub && matchedStub.response) {
        replyStatus = matchedStub.response.status || 200;
        replyPayload = matchedStub.response.body || '';
        if (service === 'echoService' && reqData) {
          try {
            const parsed = JSON.parse(reqData);
            const resObj = JSON.parse(replyPayload);
            resObj.echoData = parsed;
            replyPayload = JSON.stringify(resObj, null, 2);
          } catch {
            // retain static body
          }
        }
        if (matchedStub.response.headers) {
          Object.assign(replyHeaders, matchedStub.response.headers);
        }
      } else if (bufferType === 'JSON') {
        try {
          const parsed = JSON.parse(reqData);
          replyPayload = JSON.stringify(
            {
              status: 'SUCCESS',
              xatmiReturnCode: 0,
              service,
              transactionId: `XID-${Date.now()}`,
              echoData: parsed,
              serverTimestamp: new Date().toISOString()
            },
            null,
            2
          );
        } catch {
          replyPayload = JSON.stringify({ status: 'SUCCESS', service });
        }
      }

      // Record in request journal so it appears immediately in the Request Journal view
      const entry = {
        id: generateUuid(),
        request: {
          url: `/__casual/services/${service}`,
          absoluteUrl: `http://localhost:8080/__casual/services/${service}`,
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'x-casual-service': service,
            'x-casual-buffer-type': bufferType
          },
          body: reqData,
          loggedDate: Date.now()
        },
        response: {
          status: replyStatus,
          headers: replyHeaders,
          body: replyPayload
        },
        wasMatched: Boolean(matchedStub),
        timing: { serveTimeMillis: 10, totalTimeMillis: 14 },
        stubMapping: matchedStub || undefined
      };
      this.requests.unshift(entry);

      const isOk = replyStatus >= 200 && replyStatus < 300;
      return responseJson(200, {
        status: isOk ? 0 : -1,
        errorState: isOk ? 'OK' : 'TPESVCFAIL',
        errorCode: isOk ? 0 : 11,
        userCode: 0,
        durationMs: 12,
        execution: entry.id,
        service,
        bufferType,
        payload: replyPayload,
        isBinary: false,
        sizeBytes: replyPayload ? new Blob([replyPayload]).size : 0,
        headers: replyHeaders,
        requestHeaders: entry.request.headers,
        executionTimeMs: 12
      });
    }

    // 8. Otel / Prometheus Metrics Scrape
    if (pathname === '/__admin/metrics/prometheus') {
      const scrape =
        '# HELP wiremock_http_requests_total Total HTTP requests handled\n' +
        '# TYPE wiremock_http_requests_total counter\n' +
        'wiremock_http_requests_total{status="200"} 42\n' +
        'wiremock_http_requests_total{status="201"} 8\n' +
        'wiremock_http_requests_total{status="404"} 3\n' +
        '# HELP wiremock_request_duration_seconds Request duration in seconds\n' +
        '# TYPE wiremock_request_duration_seconds histogram\n' +
        'wiremock_request_duration_seconds_bucket{le="0.05"} 45\n' +
        'wiremock_request_duration_seconds_bucket{le="0.1"} 52\n' +
        'wiremock_request_duration_seconds_count 53\n';
      return responseText(200, scrape, { 'Content-Type': 'text/plain; version=0.0.4' });
    }

    // 9. Version endpoint
    if (pathname === '/__admin/ui/version') {
      return responseJson(200, {
        version: '1.14.0',
        branch: 'refactor/deepen-architecture',
        commit: 'fc189ca'
      });
    }

    // 10. OpenAPI Inspection
    if (pathname === '/__admin/openapi/inspect' && method === 'POST') {
      return responseJson(200, {
        title: 'Demo OpenAPI Spec',
        version: '1.0.0',
        endpoints: [
          { method: 'GET', path: '/api/v1/users', summary: 'List Users' },
          { method: 'POST', path: '/api/v1/kyc/verify', summary: 'Verify KYC' }
        ]
      });
    }

    // 11. Project Snapshots & Mode
    const projModeMatch = pathname.match(/^\/__admin\/projects\/([^/]+)\/mode$/);
    if (projModeMatch && method === 'POST') {
      const project = decodeURIComponent(projModeMatch[1]);
      const body = typeof options.body === 'string' ? JSON.parse(options.body || '{}') : options.body || {};
      const requestedMode = String(body.mode || 'stubs').toLowerCase();
      const allProjects = !project || project === '*' || project.toLowerCase() === '_all';
      let stubsEnabled = 0;
      let stubsDisabled = 0;
      this.stubs.forEach(stub => {
        if (stub.metadata && String(stub.metadata.role || '').toLowerCase() === 'offline-guard') return;
        const stubProject = (stub.metadata && stub.metadata.project) || '';
        if (!allProjects && stubProject !== project) return;
        const isProxy = Boolean((stub.response && stub.response.proxyBaseUrl) || (stub.metadata && stub.metadata.mode === 'proxy'));
        const isParked = typeof stub.priority === 'number' && stub.priority >= 100;
        const shouldBeActive = requestedMode === 'proxy' ? isProxy : !isProxy;
        if (shouldBeActive && isParked) {
          stub.priority = isProxy ? 10 : 5;
          stubsEnabled++;
        } else if (!shouldBeActive && !isParked) {
          stub.priority = 100;
          stubsDisabled++;
        }
      });
      return responseJson(200, {
        project: allProjects ? '_all' : project,
        activeMode: requestedMode,
        stubsEnabled,
        stubsDisabled
      });
    }

    if (pathname.includes('/recordings/snapshot') || pathname.includes('/snapshot-filtered')) {
      return responseJson(200, { totalRecorded: 1, snapshotted: 1, status: 'disabled', mappings: [] });
    }

    // 12. Simulation Engine for HTTP Requests (e.g. /api/v1/...)
    const matchedStub = this.matchStub(method, pathname);
    const reqHeaders = Object.assign({}, options.headers || {});
    const reqBody = options.body || '';

    if (matchedStub) {
      if (matchedStub.scenarioName && matchedStub.newScenarioState) {
        const sc = this.scenarios.find(s => s.name === matchedStub.scenarioName);
        if (sc) {
          sc.state = matchedStub.newScenarioState;
        }
      }

      const respStatus = (matchedStub.response && matchedStub.response.status) || 200;
      const respHeaders = (matchedStub.response && matchedStub.response.headers) || {
        'Content-Type': 'application/json'
      };
      let respBody = (matchedStub.response && matchedStub.response.body) || '';
      if (!respBody && matchedStub.response && matchedStub.response.jsonBody) {
        respBody = JSON.stringify(matchedStub.response.jsonBody, null, 2);
      }

      const journalEntry = {
        id: generateUuid(),
        request: {
          url: cleanUrl,
          absoluteUrl: `http://localhost:8080${cleanUrl}`,
          method,
          headers: reqHeaders,
          body: reqBody,
          loggedDate: Date.now()
        },
        response: {
          status: respStatus,
          headers: respHeaders,
          body: respBody
        },
        wasMatched: true,
        timing: {
          serveTimeMillis: Math.floor(Math.random() * 15) + 5,
          totalTimeMillis: Math.floor(Math.random() * 15) + 8
        },
        stubMapping: matchedStub
      };
      this.requests.unshift(journalEntry);

      return Promise.resolve(createFetchResponse(respStatus, respHeaders, respBody));
    }

    // Unmatched Request
    const unmatchedEntry = {
      id: generateUuid(),
      request: {
        url: cleanUrl,
        absoluteUrl: `http://localhost:8080${cleanUrl}`,
        method,
        headers: reqHeaders,
        body: reqBody,
        loggedDate: Date.now()
      },
      response: {
        status: 404,
        headers: { 'Content-Type': 'text/plain' },
        body: 'Request was not matched by any stub mapping.'
      },
      wasMatched: false,
      timing: {
        serveTimeMillis: 3,
        totalTimeMillis: 4
      }
    };
    this.requests.unshift(unmatchedEntry);

    return Promise.resolve(
      createFetchResponse(404, { 'Content-Type': 'text/plain' }, 'Request was not matched by any stub mapping.')
    );
  }
}

function createFetchResponse(status, headersMap, bodyText) {
  const statusTexts = {
    200: 'OK',
    201: 'Created',
    204: 'No Content',
    400: 'Bad Request',
    404: 'Not Found',
    500: 'Internal Server Error'
  };

  const headers = {
    forEach(cb) {
      Object.entries(headersMap).forEach(([k, v]) => cb(v, k));
    },
    get(name) {
      const lower = name.toLowerCase();
      for (const [k, v] of Object.entries(headersMap)) {
        if (k.toLowerCase() === lower) return v;
      }
      return null;
    }
  };

  return {
    status,
    statusText: statusTexts[status] || (status >= 200 && status < 300 ? 'OK' : 'Error'),
    ok: status >= 200 && status < 300,
    headers,
    text: () => Promise.resolve(bodyText),
    json: () => {
      try {
        return Promise.resolve(JSON.parse(bodyText));
      } catch (err) {
        return Promise.reject(err);
      }
    }
  };
}

export const demoBackend = new InMemoryDemoBackend();
