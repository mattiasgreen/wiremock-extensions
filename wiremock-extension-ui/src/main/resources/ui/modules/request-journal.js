/**
 * Canonical domain model and stateful store for WireMock Request Journal entries.
 *
 * Encapsulates:
 * - Traffic verdict classification (proxied, bridged, stateful, templated stub, stub, proxy_error, unmatched).
 * - Request protocol detection (http, casual, kafka, amqp, async).
 * - Client-side filtering (search expression, verdict, protocol, unmatched-only, time window).
 * - Progressive virtual/lazy-scroll pagination window and fast lookup by request ID.
 */
import { matchesJournalEntry } from './filter.js';

export function getHeader(headers, name) {
  if (!headers) return null;
  const lower = name.toLowerCase();
  for (const [k, v] of Object.entries(headers)) {
    if (k.toLowerCase() === lower) return v;
  }
  return null;
}

/**
 * Classifies a WireMock ServeEvent into one of the core verdicts:
 * - 'proxy_error' (⚠️ PROXY_ERROR): 502 Bad Gateway or x-async-error failure
 * - 'stateful'    (🧠 STATEFUL): Handled by wiremock-extension-stateful AST engine
 * - 'unmatched'   (⚠️ UNMATCHED): No stub or proxy route matched (HTTP 404)
 * - 'proxied'     (🌐 PROXIED / 🔀 BRIDGED): Matched a proxyBaseUrl stub or async bridge
 * - 'stub'        (📌 STUB / 📌 STUB · TPL): Served offline by a static or templated stub
 */
export function getRequestVerdict(req) {
  const resHeaders = (req.response && req.response.headers) || (req.responseDefinition && req.responseDefinition.headers) || {};
  const status = req.response ? req.response.status : (req.responseDefinition ? req.responseDefinition.status : 0);
  const asyncError = getHeader(resHeaders, 'x-async-error');
  if (status === 502 || Boolean(asyncError)) {
    const errorMsg = asyncError || 'Bad Gateway (502)';
    return {
      key: 'proxy_error',
      type: 'proxy_error',
      label: '⚠️ PROXY_ERROR',
      cssClass: 'pill-unmatched pill-proxy-error',
      stubDetail: `Proxy Error (502): ${errorMsg}`,
      tooltip: `Async or proxy forwarding failure (502 Bad Gateway): ${errorMsg}`
    };
  }
  const isStateful = getHeader(resHeaders, 'x-wiremock-stateful') === 'true';
  if (isStateful) {
    const dynamicEntity = getHeader(resHeaders, 'x-wiremock-stateful-entity');
    return {
      key: 'stateful',
      type: 'dynamic',
      label: '🧠 STATEFUL',
      cssClass: 'pill-matched pill-stateful',
      stubDetail: `Stateful: ${dynamicEntity || 'Entity'}`,
      tooltip: 'Simulated by in-memory AST stateful engine (wiremock-extension-stateful)'
    };
  }
  if (!req.wasMatched) {
    return {
      key: 'unmatched',
      type: 'unmatched',
      label: '⚠️ UNMATCHED',
      cssClass: 'pill-unmatched',
      stubDetail: 'Unmatched (404)',
      tooltip: 'No stub or service proxy route matched this request'
    };
  }
  const proxyUrl = (req.responseDefinition && req.responseDefinition.proxyBaseUrl)
    || (req.stubMapping && req.stubMapping.response && req.stubMapping.response.proxyBaseUrl)
    || (req.stubMapping && req.stubMapping.metadata && (req.stubMapping.metadata.proxyBaseUrl || (req.stubMapping.metadata.mode === 'proxy' && req.stubMapping.metadata.targetBaseUrl)));
  const isProxyMode = Boolean(proxyUrl || (req.stubMapping && req.stubMapping.metadata && req.stubMapping.metadata.mode === 'proxy'));
  const rawName = (req.stubMapping && req.stubMapping.name)
    || (req.response && req.response.headers && req.response.headers['Matched-Stub-Name']);
  if (isProxyMode) {
    const isAsync = (req.request && req.request.url && req.request.url.startsWith('/__async/'))
      || Boolean(getHeader(resHeaders, 'x-wiremock-async-protocol'))
      || Boolean(req.request && req.request.headers && getHeader(req.request.headers, 'x-wiremock-async-protocol'));
    const isBridged = isAsync;
    const detail = proxyUrl
      ? (rawName ? `${rawName} (${isBridged ? 'Bridged' : 'Proxied'} to ${proxyUrl})` : `${isBridged ? 'Bridged' : 'Proxied'} to ${proxyUrl}`)
      : (rawName ? `${rawName} (${isBridged ? 'Bridged' : 'Proxied'})` : (isBridged ? 'Bridged' : 'Proxied'));
    return {
      key: 'proxied',
      type: 'proxied',
      label: isBridged ? '🔀 BRIDGED' : '🌐 PROXIED',
      cssClass: isBridged ? 'pill-matched pill-proxied pill-bridged' : 'pill-matched pill-proxied',
      proxyUrl: proxyUrl || 'upstream',
      stubDetail: detail,
      tooltip: isBridged
        ? `Forwarded across queue bridge to (${proxyUrl || 'upstream broker'}) — ready to Snapshot into an offline stub`
        : `Forwarded to live upstream (${proxyUrl || 'proxy route'}) — ready to Snapshot into an offline stub`
    };
  }
  const transformers = (req.stubMapping && req.stubMapping.response && req.stubMapping.response.transformers)
    || (req.responseDefinition && req.responseDefinition.transformers) || [];
  const resBody = (req.stubMapping && req.stubMapping.response && req.stubMapping.response.body) || '';
  const isTemplated = transformers.includes('response-template') || (typeof resBody === 'string' && resBody.includes('{{'));
  return {
    key: 'stub',
    type: 'stubbed',
    label: isTemplated ? '📌 STUB · TPL' : '📌 STUB',
    cssClass: 'pill-matched pill-stub pill-stubbed',
    isTemplated,
    stubDetail: rawName ? `Stub: ${rawName}` : 'Stubbed',
    tooltip: isTemplated
      ? 'Served offline by a parameterized Handlebars response-template stub'
      : 'Served offline by a static WireMock stub mapping'
  };
}

export const getRequestResolution = getRequestVerdict;

export function detectRequestProtocol(req) {
  const reqHeaders = (req && req.request && req.request.headers) || {};
  const url = (req && req.request && req.request.url) || '';
  const isCasual = Boolean(
    getHeader(reqHeaders, 'x-casual-service') ||
    getHeader(reqHeaders, 'x-casual-queue') ||
    url.startsWith('/__casual/')
  );
  if (isCasual) return 'casual';
  const isAsync = Boolean(
    getHeader(reqHeaders, 'x-async-channel') ||
    url.startsWith('/__async/')
  );
  if (isAsync) {
    const proto = (getHeader(reqHeaders, 'x-async-protocol')
      || (url.startsWith('/__async/') ? url.split('/')[2] : 'async') || 'async').toLowerCase();
    return proto;
  }
  return 'http';
}

export class RequestJournal {
  constructor() {
    this._requests = [];
    this._meta = null;
    this._serverFiltered = false;
    this._lastFiltered = [];
    this._visibleCount = 50;
    this._pageSize = 50;
  }

  setRequests(requests = [], meta = null, serverFiltered = false) {
    this._requests = Array.isArray(requests) ? requests : [];
    this._meta = meta || null;
    this._serverFiltered = Boolean(serverFiltered);
    this._lastFiltered = [...this._requests];
    this._visibleCount = this._pageSize;
  }

  getRequests() {
    return this._requests;
  }

  getMeta() {
    return this._meta;
  }

  isServerFiltered() {
    return this._serverFiltered;
  }

  getTotalCount() {
    return this._requests.length;
  }

  findById(id) {
    if (!id) return undefined;
    return this._requests.find(r => r && r.id === id);
  }

  query({
    search = '',
    unmatchedOnly = false,
    verdictFilter = 'all',
    protocolFilter = 'all',
    since = null,
    until = null
  } = {}) {
    const filter = (search || '').trim();
    const sinceMs = since ? new Date(since).getTime() : null;
    const untilMs = until ? new Date(until).getTime() : null;

    this._lastFiltered = this._requests.filter(r => {
      const verdict = getRequestVerdict(r);
      if (unmatchedOnly && verdict.key !== 'unmatched') return false;
      if (verdictFilter !== 'all' && verdict.key !== verdictFilter) return false;
      const proto = detectRequestProtocol(r);
      if (protocolFilter !== 'all' && proto !== protocolFilter) return false;
      if (sinceMs !== null || untilMs !== null) {
        const loggedDate = r.request && r.request.loggedDate;
        if (loggedDate) {
          const t = typeof loggedDate === 'number' ? loggedDate : new Date(loggedDate).getTime();
          if (!isNaN(t)) {
            if (sinceMs !== null && t < sinceMs) return false;
            if (untilMs !== null && t > untilMs) return false;
          }
        }
      }
      if (!filter) return true;
      const rHeaders = (r.response && r.response.headers) || (r.responseDefinition && r.responseDefinition.headers) || {};
      const statefulEntity = getHeader(rHeaders, 'x-wiremock-stateful-entity') || '';
      return matchesJournalEntry(r, filter, statefulEntity, `${verdict.key} ${verdict.type} ${verdict.label} ${proto}`);
    });
    return this._lastFiltered;
  }

  getLastFiltered() {
    return this._lastFiltered;
  }

  getFilteredProxied() {
    return (this._lastFiltered || []).filter(r => getRequestVerdict(r).key === 'proxied');
  }

  resetVisibleWindow(pageSize = 50) {
    this._pageSize = pageSize > 0 ? pageSize : 50;
    this._visibleCount = this._pageSize;
  }

  getVisibleSlice() {
    return this._lastFiltered.slice(0, this._visibleCount);
  }

  nextVisibleBatch(batchSize = this._pageSize) {
    const step = batchSize > 0 ? batchSize : this._pageSize;
    if (this._visibleCount >= this._lastFiltered.length) return [];
    const nextBatch = this._lastFiltered.slice(this._visibleCount, this._visibleCount + step);
    this._visibleCount += nextBatch.length;
    return nextBatch;
  }

  getVisibleStats() {
    const filtered = this._lastFiltered.length;
    const shown = Math.min(this._visibleCount, filtered);
    return {
      shown,
      filtered,
      total: this._requests.length
    };
  }
}

export const requestJournal = new RequestJournal();
