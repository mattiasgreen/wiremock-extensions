/**
 * Top-level UI data orchestrator coordinating Admin API fetches and console view refreshes.
 */
import { state } from './state.js';
import { elements } from './dom.js';
import { apiGet, doFetch, detectCapabilities } from './api.js';
import { isStubParked } from './stub-catalog.js';
import { renderStubList } from './stubs.js';
import { renderJournal } from './journal.js';
import { renderScenarios } from './scenarios.js';
import { renderProjectSelector, updateFilterCounts } from './projects.js';
import { updateBulkToolbar } from './bulk-actions.js';
import { loadTesterHistoryFromStorage } from './tester.js';
import { applyRouteFromUrl } from './router.js';

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

let journalFetchSeq = 0;

export function loadJournal() {
  const seq = ++journalFetchSeq;
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
      if (seq !== journalFetchSeq) return;
      state.currentRequests = data.requests || [];
      state.journalMeta = data.meta || null;
      state.journalServerFiltered = true;
      if (elements.statRequests) {
        elements.statRequests.textContent =
          data.meta && data.meta.total !== undefined ? data.meta.total : state.currentRequests.length;
      }
      if (elements.journalCount) {
        elements.journalCount.textContent =
          data.meta && data.meta.filtered !== undefined ? data.meta.filtered : state.currentRequests.length;
      }
      renderJournal();
    })
    .catch(() => {
      const fallbackEndpoint = unmatchedOnly
        ? `/__admin/requests?limit=${limit}&unmatched=true`
        : `/__admin/requests?limit=${limit}`;
      return apiGet(fallbackEndpoint).then(data => {
        if (seq !== journalFetchSeq) return;
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
          id: sc.name,
          name: sc.name,
          state: sc.state || 'Started',
          possibleStates: new Set(sc.possibleStates || ['Started'])
        });
      });

      const allStubs = state.currentStubs || [];
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

export function loadData() {
  loadTesterHistoryFromStorage();
  return detectCapabilities(doFetch)
    .then(caps => {
      const promises = [loadMappings(), loadJournal()];
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
      const dynamicCount = (state.statefulModels || []).reduce(
        (acc, m) => acc + ((m.rules && m.rules.length) || 0),
        0
      );
      const activeCount = state.currentStubs.filter(s => !isStubParked(s)).length;
      const disabledCount = state.currentStubs.filter(isStubParked).length;
      const totalStubs = state.currentStubs.length + dynamicCount;
      if (elements.statStubs) elements.statStubs.textContent = totalStubs;
      if (elements.stubsCountLabel) {
        const parts = [`${activeCount} active`];
        if (disabledCount > 0 || state.capabilities.lifecycle) {
          parts.push(`${disabledCount} disabled`);
        }
        if (dynamicCount > 0) {
          parts.push(`${dynamicCount} dynamic`);
        }
        elements.stubsCountLabel.textContent = parts.join(' · ');
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
