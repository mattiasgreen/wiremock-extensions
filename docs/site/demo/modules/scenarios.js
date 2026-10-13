/**
 * Scenario DAG state visualizer and transition controls.
 * Visualizes FSM pipeline flow, nodes, in-state invariants, and transitions.
 */
import { state } from './state.js';
import { elements, escapeHtml } from './dom.js';
import { getStubUrl, sendStubToTester } from './stubs.js';

let doFetchFn = null;
let loadScenariosFn = null;

export function registerScenarioFetchers({ doFetch, loadScenarios }) {
  doFetchFn = doFetch;
  loadScenariosFn = loadScenarios;
}

export function renderScenarios(scenarios) {
  if (!scenarios || scenarios.length === 0) {
    elements.scenariosContainer.innerHTML = '<div class="empty-state">No active stateful scenarios defined.</div>';
    return;
  }

  elements.scenariosContainer.innerHTML = scenarios.map(sc => {
    const scenarioStubs = state.currentStubs.filter(s => s.scenarioName === sc.name);

    const stateSet = new Set(['Started', ...(sc.possibleStates || [])]);
    scenarioStubs.forEach(s => {
      if (s.requiredScenarioState) stateSet.add(s.requiredScenarioState);
      if (s.newScenarioState) stateSet.add(s.newScenarioState);
    });
    const states = Array.from(stateSet);
    const startedIdx = states.indexOf('Started');
    if (startedIdx > 0) {
      states.splice(startedIdx, 1);
      states.unshift('Started');
    }

    const stateData = {};
    states.forEach(st => {
      stateData[st] = {
        name: st,
        isActive: (sc.state === st),
        outboundTransitions: scenarioStubs.filter(s => (s.requiredScenarioState || 'Started') === st && s.newScenarioState && s.newScenarioState !== st),
        inStateOperations: scenarioStubs.filter(s => (s.requiredScenarioState || 'Started') === st && (!s.newScenarioState || s.newScenarioState === st)),
        inboundTransitions: scenarioStubs.filter(s => s.newScenarioState === st && (s.requiredScenarioState || 'Started') !== st)
      };
    });

    const pipelineStepsHtml = states.map((st, idx) => {
      const isCurrent = sc.state === st;
      const arrowHtml = (idx < states.length - 1) ? '<span class="fsm-pipeline-arrow">➔</span>' : '';
      return `
        <div class="fsm-pipeline-step ${isCurrent ? 'active' : ''}">
          <span class="fsm-step-dot ${isCurrent ? 'pulsing' : ''}"></span>
          <span class="fsm-step-name">${escapeHtml(st)}</span>
        </div>
        ${arrowHtml}
      `;
    }).join('');

    const stateOptionsHtml = states.map(st => `
      <option value="${escapeHtml(st)}" ${st === sc.state ? 'selected' : ''}>${escapeHtml(st)}</option>
    `).join('');

    const nodesHtml = states.map(st => {
      const d = stateData[st];
      const isTerminal = (d.outboundTransitions.length === 0 && st !== 'Started');
      const isOrphan = (st !== 'Started' && d.inboundTransitions.length === 0);

      let diagBadges = '';
      if (isOrphan) {
        diagBadges += `<span class="fsm-badge fsm-badge-warning" title="No stubs transition into this state">⚠️ Orphan</span>`;
      }
      if (isTerminal) {
        diagBadges += `<span class="fsm-badge fsm-badge-info" title="Terminal state with no outbound transitions">🏁 Terminal</span>`;
      }

      let outboundHtml = '';
      if (d.outboundTransitions.length > 0) {
        outboundHtml = `
          <div class="fsm-section-label">State Transitions (${d.outboundTransitions.length})</div>
          <div class="fsm-transitions-list">
            ${d.outboundTransitions.map(s => {
              const method = (s.request && s.request.method) || 'ANY';
              const url = getStubUrl(s.request);
              const status = (s.response && s.response.status) || 200;
              const statusCls = status >= 200 && status < 300 ? 'status-2xx' : (status >= 400 && status < 500 ? 'status-4xx' : 'status-5xx');
              return `
                <div class="fsm-transition-item">
                  <div class="fsm-transition-meta">
                    <span class="http-badge badge-${method.toLowerCase()}">${escapeHtml(method)}</span>
                    <span class="fsm-transition-url" title="${escapeHtml(url)}">${escapeHtml(url)}</span>
                    <span class="fsm-transition-arrow-symbol">➔</span>
                    <span class="fsm-target-state-pill">${escapeHtml(s.newScenarioState)}</span>
                    <span class="card-value ${statusCls}" style="font-size: 0.75rem; margin-left: auto;">${status}</span>
                  </div>
                  <button class="btn btn-tiny btn-secondary btn-test-fsm-stub" data-stub-id="${s.id}" title="Send request to HTTP Tester to trigger this transition">⚡ Test</button>
                </div>
              `;
            }).join('')}
          </div>
        `;
      }

      let operationsHtml = '';
      if (d.inStateOperations.length > 0) {
        operationsHtml = `
          <div class="fsm-section-label">In-State Operations & Invariants (${d.inStateOperations.length})</div>
          <div class="fsm-operations-list">
            ${d.inStateOperations.map(s => {
              const method = (s.request && s.request.method) || 'ANY';
              const url = getStubUrl(s.request);
              const status = (s.response && s.response.status) || 200;
              const statusCls = status >= 200 && status < 300 ? 'status-2xx' : (status >= 400 && status < 500 ? 'status-4xx' : 'status-5xx');
              const isRejection = status >= 400;
              return `
                <div class="fsm-op-item ${isRejection ? 'op-rejection' : ''}">
                  <div class="fsm-op-meta">
                    <span class="http-badge badge-${method.toLowerCase()}">${escapeHtml(method)}</span>
                    <span class="fsm-op-url" title="${escapeHtml(url)}">${escapeHtml(url)}</span>
                    <span class="card-value ${statusCls}" style="font-size: 0.75rem; margin-left: auto;">${status}</span>
                  </div>
                  <button class="btn btn-tiny btn-secondary btn-test-fsm-stub" data-stub-id="${s.id}" title="Send this operation to HTTP Tester">⚡ Test</button>
                </div>
              `;
            }).join('')}
          </div>
        `;
      } else if (d.outboundTransitions.length === 0) {
        operationsHtml = `<div class="fsm-empty-ops muted-text">No in-state operations configured</div>`;
      }

      return `
        <div class="fsm-node-card ${d.isActive ? 'active' : ''}">
          <div class="fsm-node-header">
            <div class="fsm-node-title-group">
              <span class="fsm-status-indicator ${d.isActive ? 'active' : ''}">●</span>
              <span class="fsm-node-name">${escapeHtml(st)}</span>
            </div>
            <div class="fsm-node-badges">
              ${d.isActive ? '<span class="fsm-badge fsm-badge-active">Active</span>' : ''}
              ${diagBadges}
            </div>
          </div>
          <div class="fsm-node-body">
            ${outboundHtml}
            ${operationsHtml}
          </div>
        </div>
      `;
    }).join('');

    return `
      <div class="scenario-fsm-card">
        <div class="scenario-fsm-header">
          <div class="scenario-title-area">
            <span class="scenario-icon">🔀</span>
            <span class="scenario-name">${escapeHtml(sc.name)}</span>
            <span class="scenario-state-pill active">
              <span class="pulse-indicator"></span> Current: <strong>${escapeHtml(sc.state)}</strong>
            </span>
          </div>
          <div class="scenario-fsm-controls">
            <label class="control-label">Override State:</label>
            <select class="scenario-target-state-select form-input-tiny" data-scenario="${escapeHtml(sc.name)}">
              ${stateOptionsHtml}
            </select>
            <button class="btn btn-tiny btn-primary btn-set-scenario-state" data-scenario="${escapeHtml(sc.name)}">Set State</button>
            <button class="btn btn-tiny btn-secondary btn-reset-single-scenario" data-scenario="${escapeHtml(sc.name)}" title="Reset this scenario to Started">↺ Reset</button>
          </div>
        </div>

        <div class="fsm-pipeline-container">
          <div class="fsm-pipeline-title">State Machine Flow (DAG)</div>
          <div class="fsm-pipeline-track">
            ${pipelineStepsHtml}
          </div>
        </div>

        <div class="fsm-nodes-grid">
          ${nodesHtml}
        </div>
      </div>
    `;
  }).join('');

  attachScenarioEventListeners();
}

export function attachScenarioEventListeners() {
  document.querySelectorAll('.btn-set-scenario-state').forEach(btn => {
    btn.addEventListener('click', () => {
      const scenarioName = btn.getAttribute('data-scenario');
      const select = document.querySelector(`.scenario-target-state-select[data-scenario="${scenarioName}"]`);
      if (!select) return;
      const targetState = select.value;
      btn.disabled = true;
      if (doFetchFn) {
        doFetchFn(`/__admin/scenarios/${encodeURIComponent(scenarioName)}/state`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ state: targetState })
        })
        .then(() => { if (loadScenariosFn) loadScenariosFn(); })
        .catch(err => alert('Failed to set scenario state: ' + err.message))
        .finally(() => { btn.disabled = false; });
      }
    });
  });

  document.querySelectorAll('.btn-reset-single-scenario').forEach(btn => {
    btn.addEventListener('click', () => {
      const scenarioName = btn.getAttribute('data-scenario');
      btn.disabled = true;
      if (doFetchFn) {
        doFetchFn(`/__admin/scenarios/${encodeURIComponent(scenarioName)}/state`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ state: 'Started' })
        })
        .then(() => { if (loadScenariosFn) loadScenariosFn(); })
        .catch(err => alert('Failed to reset scenario: ' + err.message))
        .finally(() => { btn.disabled = false; });
      }
    });
  });

  document.querySelectorAll('.btn-test-fsm-stub').forEach(btn => {
    btn.addEventListener('click', () => {
      const stubId = btn.getAttribute('data-stub-id');
      const stub = state.currentStubs.find(s => s.id === stubId);
      if (stub) {
        sendStubToTester(stub);
      }
    });
  });
}
