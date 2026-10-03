/**
 * WireMock Admin API client and asynchronous data loader.
 * Endpoints: /__admin/mappings, /__admin/requests, /__admin/scenarios
 */
import { state } from './state.js';
import { elements } from './dom.js';
import { renderStubList } from './stubs.js';
import { renderJournal } from './journal.js';
import { renderScenarios } from './scenarios.js';
import {
  parseHeadersInput,
  updateTesterRoute,
  displaySentRequest,
  displayResponseResult,
  saveTesterHistoryItem,
  loadTesterHistoryFromStorage
} from './tester.js';
import { applyRouteFromUrl } from './router.js';

export function doFetch(url, options = {}) {
  if (typeof fetch !== 'undefined') {
    return fetch(url, options);
  }
  return new Promise((resolve, reject) => {
    try {
      const xhr = new XMLHttpRequest();
      xhr.open(options.method || 'GET', url);
      if (options.headers) {
        Object.entries(options.headers).forEach(([k, v]) => xhr.setRequestHeader(k, v));
      }
      xhr.onload = () => {
        const headers = {
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
          headers: headers,
          text: () => Promise.resolve(xhr.responseText),
          json: () => Promise.resolve(JSON.parse(xhr.responseText)),
          ok: xhr.status >= 200 && xhr.status < 300
        });
      };
      xhr.onerror = () => reject(new Error('Network error from ' + url));
      xhr.send(options.body || null);
    } catch (err) {
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

export function loadMappings() {
  return apiGet('/__admin/mappings')
    .then(data => {
      state.currentStubs = data.mappings || [];
      renderStubList();
      if (elements.statStubs) elements.statStubs.textContent = state.currentStubs.length;
      if (elements.stubsCountLabel) elements.stubsCountLabel.textContent = `${state.currentStubs.length} mappings`;
    })
    .catch(err => {
      if (elements.stubList) {
        elements.stubList.innerHTML = `<div class="empty-state text-error">Failed to load stubs: ${err.message}</div>`;
      }
    });
}

export function loadJournal() {
  return apiGet('/__admin/requests?limit=100')
    .then(data => {
      state.currentRequests = data.requests || [];
      if (elements.statRequests) elements.statRequests.textContent = state.currentRequests.length;
      if (elements.journalCount) elements.journalCount.textContent = state.currentRequests.length;
      renderJournal();
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

      state.currentStubs.forEach(stub => {
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

export function loadData() {
  loadTesterHistoryFromStorage();
  return Promise.all([loadMappings(), loadJournal(), loadScenarios()])
    .then(() => {
      applyRouteFromUrl();
    })
    .catch(err => {
      console.error('Error refreshing data', err);
    });
}

export function executeTesterRequest() {
  if (!elements.testerUrl || !elements.testerMethod) return;
  const method = elements.testerMethod.value;
  let url = elements.testerUrl.value.trim();
  if (!url.startsWith('/') && !url.startsWith('http')) {
    url = '/' + url;
  }
  const headersText = elements.testerHeaders ? elements.testerHeaders.value : '';
  const headers = parseHeadersInput(headersText);
  const bodyText = elements.testerBody ? elements.testerBody.value.trim() : '';

  updateTesterRoute(false);
  displaySentRequest(method, url, headersText, bodyText);

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
    .then(() => {
      if (elements.btnTesterSend) {
        elements.btnTesterSend.disabled = false;
        elements.btnTesterSend.textContent = '🚀 Send';
      }
    });
}
