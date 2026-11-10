/**
 * WireMock Admin API client and asynchronous data loader.
 * Endpoints: /__admin/mappings, /__admin/requests, /__admin/scenarios, /__admin/stubs/*
 */
import { state } from './state.js';
import { elements } from './dom.js';
import { renderStubList } from './stubs.js';
import { renderJournal } from './journal.js';
import { renderScenarios } from './scenarios.js';
import { renderProjectSelector, updateFilterCounts } from './projects.js';
import { updateBulkToolbar } from './bulk-actions.js';
import {
  parseHeadersInput,
  getEffectiveTesterHeaders,
  updateTesterRoute,
  displaySentRequest,
  displayResponseResult,
  showSettlementBanner,
  saveTesterHistoryItem,
  loadTesterHistoryFromStorage
} from './tester.js';
import { executeCasualRequest } from './casual-tester.js';
import { applyRouteFromUrl } from './router.js';
import { detectCapabilities } from './capabilities.js';
import { demoBackend } from './demo-backend.js';
import { updateTargetHostDisplay } from './settings.js';

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
          demoBackend.setDemoMode(true);
          updateTargetHostDisplay();
          return demoBackend.handleRequest(url, options);
        }
        return res;
      })
      .catch(err => {
        if (!isTargetingRemote && !state.targetConfig && (url.startsWith('/__admin/') || url.startsWith('/api/'))) {
          demoBackend.setDemoMode(true);
          updateTargetHostDisplay();
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
          demoBackend.setDemoMode(true);
          updateTargetHostDisplay();
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
          demoBackend.setDemoMode(true);
          updateTargetHostDisplay();
          return resolve(demoBackend.handleRequest(url, options));
        }
        reject(new Error('Network error from ' + targetUrl));
      };
      xhr.send(fetchOptions.body || null);
    } catch (err) {
      if (!isTargetingRemote && !state.targetConfig && (url.startsWith('/__admin/') || url.startsWith('/api/'))) {
        demoBackend.setDemoMode(true);
        updateTargetHostDisplay();
        return resolve(demoBackend.handleRequest(url, options));
      }
      reject(err);
    }
  });
}

export function apiGet(endpoint) {
  return doFetch(endpoint, { method: 'GET' }).then(res => {
    if (!res.ok) throw new Error(`HTTP ${res.status} from ${endpoint}`);
    return res.json();
  });
}

export function apiPost(endpoint, body = {}) {
  return doFetch(endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: typeof body === 'string' ? body : JSON.stringify(body)
  }).then(res => {
    if (!res.ok) throw new Error(`HTTP ${res.status} from ${endpoint}`);
    return res.text().then(text => {
      if (!text || !text.trim()) return {};
      try {
        return JSON.parse(text);
      } catch {
        return {};
      }
    });
  });
}

export function apiPut(endpoint, body = {}) {
  return doFetch(endpoint, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: typeof body === 'string' ? body : JSON.stringify(body)
  }).then(res => {
    if (!res.ok) throw new Error(`HTTP ${res.status} from ${endpoint}`);
    return res.text().then(text => {
      if (!text || !text.trim()) return {};
      try {
        return JSON.parse(text);
      } catch {
        return {};
      }
    });
  });
}

export function apiDelete(endpoint) {
  return doFetch(endpoint, { method: 'DELETE' }).then(res => {
    if (!res.ok) throw new Error(`HTTP ${res.status} from ${endpoint}`);
    return res.status === 204 ? {} : res.json().catch(() => ({}));
  });
}

export function loadMappings() {
  return apiGet('/__admin/mappings')
    .then(data => {
      state.currentStubs = data.mappings || [];
    })
    .catch(err => {
      if (elements.stubList) {
        elements.stubList.innerHTML = `<div class="empty-state text-error">Failed to load stubs: ${err.message}</div>`;
      }
    });
}

export function loadDisabledStubs() {
  return apiGet('/__admin/stubs/disabled')
    .then(data => {
      state.disabledStubs = data.mappings || [];
    })
    .catch(err => {
      // In case endpoint is not available or empty
      state.disabledStubs = [];
    });
}

export function toggleStubState(stubId) {
  return apiPost(`/__admin/stubs/${stubId}/toggle`);
}

export function deleteStubMapping(stubId) {
  // Try /__admin/stubs/${stubId} first (cleans up active and disabled), fallback to mappings
  return apiDelete(`/__admin/stubs/${stubId}`)
    .catch(() => apiDelete(`/__admin/mappings/${stubId}`).catch(() => bulkStubs('delete', [stubId])));
}

export function saveStubMapping(stubId, stubData) {
  // Use /__admin/stubs/${stubId} which supports both active and disabled stubs safely
  return apiPut(`/__admin/stubs/${stubId}`, stubData)
    .catch(err => {
      // Fallback to core /__admin/mappings/${stubId} if stubs endpoint unavailable
      return apiPut(`/__admin/mappings/${stubId}`, stubData);
    });
}

export function createStubMapping(stubData) {
  return apiPost('/__admin/mappings', stubData);
}

export function bulkStubs(action, ids) {
  return apiPost('/__admin/stubs/bulk', { action, ids });
}

export function importMappings(payload) {
  return apiPost('/__admin/mappings/import', payload);
}

export function loadJournal() {
  const limit = state.journalLimit || 100;
  const unmatchedOnly = elements.filterUnmatchedOnly && elements.filterUnmatchedOnly.checked;
  const q = (elements.journalSearch ? elements.journalSearch.value : '').trim();
  const verdict = state.journalVerdictFilter || 'all';

  const params = new URLSearchParams();
  params.set('limit', limit);
  if (q) params.set('q', q);
  if (verdict && verdict !== 'all') params.set('verdict', verdict);
  if (unmatchedOnly) params.set('unmatched', 'true');
  if (state.journalSince) params.set('since', state.journalSince);
  if (state.journalUntil) params.set('until', state.journalUntil);

  const serverEndpoint = `/__admin/ui/api/requests?${params.toString()}`;

  return apiGet(serverEndpoint)
    .then(data => {
      state.currentRequests = data.requests || [];
      state.journalMeta = data.meta || null;
      state.journalServerFiltered = true;
      if (elements.statRequests) {
        elements.statRequests.textContent = (data.meta && data.meta.total !== undefined)
          ? data.meta.total
          : state.currentRequests.length;
      }
      if (elements.journalCount) {
        elements.journalCount.textContent = (data.meta && data.meta.filtered !== undefined)
          ? data.meta.filtered
          : state.currentRequests.length;
      }
      renderJournal();
    })
    .catch(() => {
      // Fallback for vanilla WireMock instances without /ui/api/requests
      const fallbackEndpoint = unmatchedOnly
        ? `/__admin/requests?limit=${limit}&unmatched=true`
        : `/__admin/requests?limit=${limit}`;
      return apiGet(fallbackEndpoint).then(data => {
        state.currentRequests = data.requests || [];
        state.journalMeta = null;
        state.journalServerFiltered = false;
        if (elements.statRequests) elements.statRequests.textContent = state.currentRequests.length;
        if (elements.journalCount) elements.journalCount.textContent = state.currentRequests.length;
        renderJournal();
      });
    })
    .catch(err => {
      console.warn('Could not load journal', err);
    });
}

export function loadScenarios() {
  return apiGet('/__admin/scenarios')
    .then(data => {
      const apiScenarios = data.scenarios || [];
      const scenarioMap = new Map();

      apiScenarios.forEach(sc => {
        scenarioMap.set(sc.name, {
          id: sc.id || sc.name,
          name: sc.name,
          state: sc.state || 'Started',
          possibleStates: new Set(sc.possibleStates || ['Started'])
        });
      });

      const allStubs = [...state.currentStubs, ...state.disabledStubs];
      allStubs.forEach(stub => {
        if (stub.scenarioName) {
          if (!scenarioMap.has(stub.scenarioName)) {
            scenarioMap.set(stub.scenarioName, {
              id: stub.scenarioName,
              name: stub.scenarioName,
              state: 'Started',
              possibleStates: new Set(['Started'])
            });
          }
          const entry = scenarioMap.get(stub.scenarioName);
          if (stub.requiredScenarioState) entry.possibleStates.add(stub.requiredScenarioState);
          if (stub.newScenarioState) entry.possibleStates.add(stub.newScenarioState);
        }
      });

      const allScenarios = Array.from(scenarioMap.values()).map(sc => ({
        ...sc,
        possibleStates: Array.from(sc.possibleStates)
      }));

      state.allScenarios = allScenarios;
      if (elements.statScenarios) elements.statScenarios.textContent = allScenarios.length;
      renderScenarios(allScenarios);
    })
    .catch(err => {
      console.warn('Could not load scenarios', err);
    });
}

export function loadStatefulModels() {
  return apiGet('/__admin/stateful/models')
    .then(data => {
      state.statefulModels = Array.isArray(data) ? data : [];
    })
    .catch(() => {
      state.statefulModels = [];
    });
}

export function createStatefulModel(modelData) {
  return apiPost('/__admin/stateful/rules', modelData);
}

export function deleteStatefulSession(correlationId) {
  return apiDelete(`/__admin/stateful/sessions/${encodeURIComponent(correlationId)}`);
}

export function loadData() {
  loadTesterHistoryFromStorage();
  return detectCapabilities(doFetch)
    .then(caps => {
      const promises = [loadMappings(), loadJournal()];
      if (caps.lifecycle) {
        promises.push(loadDisabledStubs());
      } else {
        state.disabledStubs = [];
      }
      if (caps.stateful) {
        promises.push(loadStatefulModels());
      } else {
        state.statefulModels = [];
      }
      return Promise.all(promises);
    })
    .then(() => {
      renderStubList();
      renderProjectSelector();
      updateFilterCounts();
      updateBulkToolbar();
      const dynamicCount = (state.statefulModels || []).reduce((acc, m) => acc + ((m.rules && m.rules.length) || 0), 0);
      const totalStubs = state.currentStubs.length + state.disabledStubs.length + dynamicCount;
      if (elements.statStubs) elements.statStubs.textContent = totalStubs;
      if (elements.stubsCountLabel) {
        if (!state.capabilities.lifecycle && !state.capabilities.stateful) {
          elements.stubsCountLabel.textContent = `${state.currentStubs.length} active`;
        } else if (!state.capabilities.lifecycle) {
          elements.stubsCountLabel.textContent = dynamicCount > 0
            ? `${state.currentStubs.length} active · ${dynamicCount} dynamic`
            : `${state.currentStubs.length} active`;
        } else {
          elements.stubsCountLabel.textContent = dynamicCount > 0
            ? `${state.currentStubs.length} active · ${state.disabledStubs.length} disabled · ${dynamicCount} dynamic`
            : `${state.currentStubs.length} active · ${state.disabledStubs.length} disabled`;
        }
      }
      return loadScenarios();
    })
    .then(() => {
      applyRouteFromUrl();
    })
    .catch(err => {
      console.error('Error refreshing data', err);
    });
}

export function executeKafkaProduce() {
  const topic = (elements.testerKafkaTopic ? elements.testerKafkaTopic.value.trim() : '') || 'orders.events';
  const key = elements.testerKafkaKey ? elements.testerKafkaKey.value.trim() : '';
  const partitionVal = elements.testerKafkaPartition ? elements.testerKafkaPartition.value.trim() : '';
  const partition = partitionVal ? parseInt(partitionVal, 10) : undefined;
  const headersText = elements.testerKafkaHeaders ? elements.testerKafkaHeaders.value.trim() : '';
  const headers = parseHeadersInput(headersText);
  const bodyText = (elements.testerBody ? elements.testerBody.value.trim() : '') || '{}';

  const sendBtn = elements.btnTesterSendKafka || elements.btnTesterSend;
  if (sendBtn) {
    sendBtn.disabled = true;
    sendBtn.textContent = '⏳ Producing...';
  }

  const startTime = performance.now();
  displaySentRequest('PRODUCE', topic, headersText, bodyText);

  const payload = {
    protocol: 'kafka',
    destination: topic,
    key: key,
    partition: partition,
    headers: headers,
    payload: bodyText
  };

  return doFetch('/__admin/async/publish', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
    .then(res => {
      if (!res.ok) throw new Error(`HTTP ${res.status}: ${res.statusText}`);
      return res.json();
    })
    .then(data => {
      const elapsed = Math.round(performance.now() - startTime);
      const partitionOut = data.partition !== undefined ? data.partition : (partition !== undefined ? partition : 0);
      const offsetOut = data.offset !== undefined ? data.offset : 0;
      const statusText = `ACKNOWLEDGED (Partition ${partitionOut} @ Offset ${offsetOut})`;
      const resBody = JSON.stringify(data, null, 2);
      const resHeaders = [`Protocol: Kafka`, `Topic: ${topic}`, `Key: ${key || '(none)'}`, `Status: Published`];

      displayResponseResult(200, statusText, elapsed, resHeaders, resBody);
      showSettlementBanner(`✅ Kafka Message Acknowledged: Partition ${partitionOut} @ Offset ${offsetOut} (Topic: "${topic}")`, 'status-settled');

      saveTesterHistoryItem({
        timestamp: Date.now(),
        protocol: 'kafka',
        method: 'PRODUCE',
        url: topic,
        destination: topic,
        key: key,
        partition: partitionOut,
        headersText: headersText,
        bodyText: bodyText,
        status: 200,
        statusText: statusText,
        duration: elapsed,
        size: new Blob([resBody]).size,
        responseHeaders: resHeaders,
        responseBody: resBody
      });

      loadJournal();
    })
    .catch(err => {
      const elapsed = Math.round(performance.now() - startTime);
      displayResponseResult('ERROR', err.message, elapsed, [], 'Publish error: ' + err.message);
      showSettlementBanner(`❌ Kafka Error: ${err.message}`, 'status-error');

      saveTesterHistoryItem({
        timestamp: Date.now(),
        protocol: 'kafka',
        method: 'PRODUCE',
        url: topic,
        destination: topic,
        key: key,
        headersText: headersText,
        bodyText: bodyText,
        status: 'ERR',
        statusText: err.message,
        duration: elapsed,
        size: 0,
        responseHeaders: [],
        responseBody: 'Publish error: ' + err.message
      });
    })
    .finally(() => {
      if (sendBtn) {
        sendBtn.disabled = false;
        sendBtn.textContent = '🚀 Produce Message';
      }
    });
}

export function executeAmqpPublish() {
  const dest = (elements.testerAmqpDest ? elements.testerAmqpDest.value.trim() : '') || 'orders.inbound';
  const correlationId = elements.testerAmqpCorrelation ? elements.testerAmqpCorrelation.value.trim() : '';
  const replyTo = elements.testerAmqpReplyTo ? elements.testerAmqpReplyTo.value.trim() : '';
  const headersText = elements.testerAmqpHeaders ? elements.testerAmqpHeaders.value.trim() : '';
  const headers = parseHeadersInput(headersText);
  const bodyText = (elements.testerBody ? elements.testerBody.value.trim() : '') || '{}';

  const sendBtn = elements.btnTesterSendAmqp || elements.btnTesterSend;
  if (sendBtn) {
    sendBtn.disabled = true;
    sendBtn.textContent = '⏳ Publishing...';
  }

  const startTime = performance.now();
  displaySentRequest('PUBLISH', dest, headersText, bodyText);

  const payload = {
    protocol: 'amqp',
    destination: dest,
    correlationId: correlationId,
    replyTo: replyTo,
    headers: headers,
    payload: bodyText
  };

  return doFetch('/__admin/async/publish', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  })
    .then(res => {
      if (!res.ok) throw new Error(`HTTP ${res.status}: ${res.statusText}`);
      return res.json();
    })
    .then(data => {
      const elapsed = Math.round(performance.now() - startTime);
      const isReqRep = data.replyReceived && data.replyPayload;
      const statusText = isReqRep ? 'REPLY RECEIVED' : (data.settlement || 'SETTLED: ACCEPTED');
      const resBody = isReqRep ? data.replyPayload : JSON.stringify(data, null, 2);
      const resHeaders = [`Protocol: AMQP 1.0`, `Destination: ${dest}`, `Correlation-Id: ${data.replyCorrelationId || correlationId || '(none)'}`];
      if (replyTo) resHeaders.push(`Reply-To: ${replyTo}`);

      displayResponseResult(200, statusText, elapsed, resHeaders, resBody);
      if (isReqRep) {
        showSettlementBanner(`📨 Correlated Reply Received from "${data.replyQueue || replyTo}" (CorrelationId: ${data.replyCorrelationId || correlationId})`, 'status-settled');
      } else {
        showSettlementBanner(`✅ AMQP Message Settled: ACCEPTED (Destination: "${dest}")`, 'status-settled');
      }

      saveTesterHistoryItem({
        timestamp: Date.now(),
        protocol: 'amqp',
        method: 'PUBLISH',
        url: dest,
        destination: dest,
        correlationId: correlationId,
        replyTo: replyTo,
        headersText: headersText,
        bodyText: bodyText,
        status: 200,
        statusText: statusText,
        duration: elapsed,
        size: new Blob([resBody]).size,
        responseHeaders: resHeaders,
        responseBody: resBody
      });

      loadJournal();
    })
    .catch(err => {
      const elapsed = Math.round(performance.now() - startTime);
      displayResponseResult('ERROR', err.message, elapsed, [], 'Publish error: ' + err.message);
      showSettlementBanner(`❌ AMQP Error: ${err.message}`, 'status-error');

      saveTesterHistoryItem({
        timestamp: Date.now(),
        protocol: 'amqp',
        method: 'PUBLISH',
        url: dest,
        destination: dest,
        correlationId: correlationId,
        headersText: headersText,
        bodyText: bodyText,
        status: 'ERR',
        statusText: err.message,
        duration: elapsed,
        size: 0,
        responseHeaders: [],
        responseBody: 'Publish error: ' + err.message
      });
    })
    .finally(() => {
      if (sendBtn) {
        sendBtn.disabled = false;
        sendBtn.textContent = '🚀 Publish Message';
      }
    });
}

export function executeTesterCasualCall() {
  const service = (elements.testerCasualService ? elements.testerCasualService.value.trim() : '') || 'echoService';
  const bufferType = (elements.testerCasualBuffer ? elements.testerCasualBuffer.value : '') || 'JSON';
  const headersText = elements.testerCasualHeaders ? elements.testerCasualHeaders.value.trim() : '';
  const headers = parseHeadersInput(headersText);
  const bodyText = (elements.testerBody ? elements.testerBody.value.trim() : '') || '{}';

  const sendBtn = elements.btnTesterSendCasual || elements.btnTesterSend;
  if (sendBtn) {
    sendBtn.disabled = true;
    sendBtn.textContent = '⏳ Calling...';
  }

  const startTime = performance.now();
  displaySentRequest('CALL', service, headersText, bodyText);

  return doFetch('/__admin/casual/call', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      service: service,
      bufferType: bufferType,
      payload: bodyText,
      headers: headers
    })
  })
    .then(res => res.json())
    .then(data => {
      const elapsed = Math.round(performance.now() - startTime);
      const isOk = !data.errorState || data.errorState === 'OK';
      const statusText = isOk ? 'OK (0)' : `${data.errorState} (${data.errorCode || 0})`;
      const resBody = data.payload || '';
      const resHeaders = [`Buffer-Type: ${data.bufferType || bufferType}`, `Error-State: ${data.errorState || 'OK'}`];
      if (data.headers) {
        Object.entries(data.headers).forEach(([k, v]) => resHeaders.push(`${k}: ${v}`));
      }

      displayResponseResult(isOk ? 200 : 500, statusText, elapsed, resHeaders, resBody);
      showSettlementBanner(`✅ Casual Call Settled: ${service} -> ${statusText}`, isOk ? 'status-settled' : 'status-error');

      saveTesterHistoryItem({
        timestamp: Date.now(),
        protocol: 'casual',
        method: 'CALL',
        url: service,
        serviceName: service,
        bufferType: bufferType,
        headersText: headersText,
        bodyText: bodyText,
        status: isOk ? 200 : 'ERR',
        statusText: statusText,
        duration: elapsed,
        size: new Blob([resBody]).size,
        responseHeaders: resHeaders,
        responseBody: resBody
      });
      loadJournal();
    })
    .catch(err => {
      const elapsed = Math.round(performance.now() - startTime);
      displayResponseResult('ERROR', err.message, elapsed, [], 'Call error: ' + err.message);
      showSettlementBanner(`❌ Casual Call Error: ${err.message}`, 'status-error');

      saveTesterHistoryItem({
        timestamp: Date.now(),
        protocol: 'casual',
        method: 'CALL',
        url: service,
        serviceName: service,
        bufferType: bufferType,
        headersText: headersText,
        bodyText: bodyText,
        status: 'ERR',
        statusText: err.message,
        duration: elapsed,
        size: 0,
        responseHeaders: [],
        responseBody: 'Call error: ' + err.message
      });
    })
    .finally(() => {
      if (sendBtn) {
        sendBtn.disabled = false;
        sendBtn.textContent = '🔌 Execute tpcall';
      }
    });
}

export function executeTesterRequest() {
  const proto = (state.testerProtocol || 'http').toLowerCase();
  if (proto === 'kafka') {
    return executeKafkaProduce();
  }
  if (proto === 'amqp') {
    return executeAmqpPublish();
  }
  if (proto === 'casual') {
    return executeTesterCasualCall();
  }

  if (!elements.testerUrl || !elements.testerMethod) return;
  const method = elements.testerMethod.value;
  let url = elements.testerUrl.value.trim();
  if (!url.startsWith('/') && !url.startsWith('http')) {
    url = '/' + url;
  }
  const headersText = elements.testerHeaders ? elements.testerHeaders.value : '';
  const { headers } = getEffectiveTesterHeaders(headersText, true);
  const bodyText = elements.testerBody ? elements.testerBody.value.trim() : '';

  const sentHeadersLines = [];
  for (const [k, v] of Object.entries(headers)) {
    sentHeadersLines.push(`${k}: ${v}`);
  }
  const sentHeadersText = sentHeadersLines.join('\n');

  updateTesterRoute(false);
  displaySentRequest(method, url, sentHeadersText, bodyText);

  if (elements.btnTesterSend) {
    elements.btnTesterSend.disabled = true;
    elements.btnTesterSend.textContent = '⏳ Sending...';
  }
  if (elements.testerResponseStatus) {
    elements.testerResponseStatus.textContent = '...';
    elements.testerResponseStatus.className = 'card-value';
  }
  if (elements.testerResponseTime) {
    elements.testerResponseTime.textContent = 'measuring...';
  }
  if (elements.testerResponseSize) {
    elements.testerResponseSize.textContent = '—';
  }

  const startTime = performance.now();
  const fetchOptions = {
    method: method,
    headers: headers
  };
  if (bodyText && ['POST', 'PUT', 'PATCH', 'DELETE'].indexOf(method) !== -1) {
    fetchOptions.body = bodyText;
  }

  let responseMeta = { status: 0, statusText: '', headerLines: [] };

  doFetch(url, fetchOptions)
    .then(res => {
      responseMeta.status = res.status;
      responseMeta.statusText = res.statusText || '';
      const headerLines = [];
      if (res.headers && res.headers.forEach) {
        res.headers.forEach((val, key) => {
          headerLines.push(`${key}: ${val}`);
        });
      }
      responseMeta.headerLines = headerLines;
      return res.text();
    })
    .then(body => {
      const elapsed = Math.round(performance.now() - startTime);
      displayResponseResult(responseMeta.status, responseMeta.statusText, elapsed, responseMeta.headerLines, body);

      saveTesterHistoryItem({
        timestamp: Date.now(),
        method: method,
        url: url,
        headersText: headersText,
        bodyText: bodyText,
        status: responseMeta.status,
        statusText: responseMeta.statusText,
        duration: elapsed,
        size: new Blob([body]).size,
        responseHeaders: responseMeta.headerLines,
        responseBody: body
      });

      loadJournal();
    })
    .catch(err => {
      const elapsed = Math.round(performance.now() - startTime);
      displayResponseResult('ERROR', err.message, elapsed, [], 'Fetch error: ' + err.message);

      saveTesterHistoryItem({
        timestamp: Date.now(),
        method: method,
        url: url,
        headersText: headersText,
        bodyText: bodyText,
        status: 0,
        statusText: err.message,
        duration: elapsed,
        size: 0,
        responseHeaders: [],
        responseBody: 'Fetch error: ' + err.message
      });
    })
    .finally(() => {
      if (elements.btnTesterSend) {
        elements.btnTesterSend.disabled = false;
        elements.btnTesterSend.textContent = '🚀 Send';
      }
    });
}

export function inspectOpenApi(spec) {
  return doFetch('/__admin/openapi/inspect', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ spec })
  }).then(res => res.json());
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
