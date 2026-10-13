/**
 * Remote WireMock capability detection and graceful degradation manager.
 * Probes available extension admin endpoints and adapts UI controls accordingly.
 */
import { state } from './state.js';
import { elements } from './dom.js';
import { activateTab, setRoute } from './router.js';

export const CAPABILITY_METADATA = [
  {
    key: 'core',
    name: 'Core WireMock Engine',
    desc: 'Stub mappings, request journal, and scenario state machine',
    icon: '⚡',
    endpoint: '/__admin/mappings'
  },
  {
    key: 'lifecycle',
    name: 'Stub Lifecycle & Pause',
    desc: 'Non-destructive pause/resume and bulk disable extensions',
    icon: '⏸️',
    endpoint: '/__admin/stubs/disabled'
  },
  {
    key: 'stateful',
    name: 'Dynamic Stateful API',
    desc: 'In-memory stateful AST simulation and multi-tenant rules',
    icon: '🧠',
    endpoint: '/__admin/stateful/models'
  },
  {
    key: 'netem',
    name: 'TCP Network Emulation',
    desc: 'L4 latency, jitter, bandwidth throttle, and packet loss',
    icon: '🌐',
    endpoint: '/__admin/tcp-netem/proxies'
  },
  {
    key: 'metrics',
    name: 'Prometheus & Otel Metrics',
    desc: 'In-process request count histograms and Prometheus scrape route',
    icon: '📊',
    endpoint: '/__admin/metrics/prometheus'
  }
];

/**
 * Probes the upstream target WireMock server for installed extension endpoints.
 * @param {Function} fetchFn - The configured doFetch function.
 * @returns {Promise<Object>} The resolved capability flags.
 */
export function detectCapabilities(fetchFn) {
  if (elements.targetCapabilitiesStatus && !state.capabilitiesDetected) {
    elements.targetCapabilitiesStatus.textContent = 'Probing...';
    elements.targetCapabilitiesStatus.style.color = 'var(--text-muted)';
  }

  const probePromises = CAPABILITY_METADATA.map(meta =>
    fetchFn(meta.endpoint, { method: 'GET' })
      .then(res => ({
        key: meta.key,
        active: res.status >= 200 && res.status < 300
      }))
      .catch(() => ({
        key: meta.key,
        active: false
      }))
  );

  return Promise.all(probePromises)
    .then(results => {
      results.forEach(r => {
        state.capabilities[r.key] = r.active;
      });
      state.capabilitiesDetected = true;
      applyCapabilityVisibility();
      renderTargetCapabilities();
      return state.capabilities;
    })
    .catch(err => {
      console.warn('Capability probing encountered error, falling back to basic defaults', err);
      state.capabilitiesDetected = true;
      applyCapabilityVisibility();
      renderTargetCapabilities();
      return state.capabilities;
    });
}

/**
 * Toggles UI visibility and state depending on detected capabilities.
 */
export function applyCapabilityVisibility() {
  const caps = state.capabilities;

  // 1. TCP NetEm Tab
  if (elements.navTabNetem) {
    if (!caps.netem) {
      elements.navTabNetem.classList.add('hidden');
      if (state.activeTab === 'tab-netem') {
        activateTab('tab-stub-detail');
        setRoute('stubs');
      }
    } else {
      elements.navTabNetem.classList.remove('hidden');
    }
  }

  // 2. Prometheus / Otel Metrics Link
  if (elements.linkMetrics) {
    if (!caps.metrics) {
      elements.linkMetrics.classList.add('hidden');
    } else {
      elements.linkMetrics.classList.remove('hidden');
    }
  }

  // 3. Stateful / Dynamic Stubs Controls
  if (elements.btnCreateDynamicStub) {
    if (!caps.stateful) {
      elements.btnCreateDynamicStub.classList.add('hidden');
    } else {
      elements.btnCreateDynamicStub.classList.remove('hidden');
    }
  }
  if (elements.btnFilterDynamic) {
    if (!caps.stateful) {
      elements.btnFilterDynamic.classList.add('hidden');
      if (state.activeFilter === 'dynamic') {
        state.activeFilter = 'all';
        if (elements.btnFilterAll) {
          document.querySelectorAll('.filter-pill').forEach(p => p.classList.remove('active'));
          elements.btnFilterAll.classList.add('active');
        }
      }
    } else {
      elements.btnFilterDynamic.classList.remove('hidden');
    }
  }

  // 4. Lifecycle / Pause / Disable Controls
  if (elements.btnFilterDisabled) {
    if (!caps.lifecycle) {
      elements.btnFilterDisabled.classList.add('hidden');
      if (state.activeFilter === 'disabled') {
        state.activeFilter = 'all';
        if (elements.btnFilterAll) {
          document.querySelectorAll('.filter-pill').forEach(p => p.classList.remove('active'));
          elements.btnFilterAll.classList.add('active');
        }
      }
    } else {
      elements.btnFilterDisabled.classList.remove('hidden');
    }
  }
  if (elements.btnBulkDisable) {
    if (!caps.lifecycle) {
      elements.btnBulkDisable.classList.add('hidden');
    } else {
      elements.btnBulkDisable.classList.remove('hidden');
    }
  }
  if (elements.btnBulkEnable) {
    if (!caps.lifecycle) {
      elements.btnBulkEnable.classList.add('hidden');
    } else {
      elements.btnBulkEnable.classList.remove('hidden');
    }
  }
  if (elements.btnToggleStub) {
    if (!caps.lifecycle) {
      elements.btnToggleStub.classList.add('hidden');
    } else {
      elements.btnToggleStub.classList.remove('hidden');
    }
  }
}

/**
 * Renders the capability rows inside the Target Host modal.
 */
export function renderTargetCapabilities() {
  if (!elements.targetCapabilitiesList) return;

  const caps = state.capabilities;
  const isDetected = state.capabilitiesDetected;

  if (elements.targetCapabilitiesStatus) {
    if (!isDetected) {
      elements.targetCapabilitiesStatus.textContent = 'Probing...';
      elements.targetCapabilitiesStatus.style.color = 'var(--text-muted)';
    } else {
      const activeCount = Object.values(caps).filter(Boolean).length;
      const totalCount = CAPABILITY_METADATA.length;
      if (activeCount === totalCount) {
        elements.targetCapabilitiesStatus.textContent = `All ${totalCount} extensions active`;
        elements.targetCapabilitiesStatus.style.color = '#34d399';
      } else {
        elements.targetCapabilitiesStatus.textContent = `${activeCount}/${totalCount} active (Degraded)`;
        elements.targetCapabilitiesStatus.style.color = '#f59e0b';
      }
    }
  }

  elements.targetCapabilitiesList.innerHTML = CAPABILITY_METADATA.map(meta => {
    const isActive = isDetected ? !!caps[meta.key] : true;
    const rowClass = isActive ? 'capability-row cap-active' : 'capability-row cap-missing';
    const badgeClass = isActive ? 'cap-badge cap-badge-active' : 'cap-badge cap-badge-missing';
    const badgeText = isActive ? 'Active' : 'Not Installed';

    return `
      <div class="${rowClass}" data-testid="cap-row-${meta.key}">
        <div class="cap-info">
          <span class="cap-icon">${meta.icon}</span>
          <div class="cap-text">
            <span class="cap-name">${meta.name}</span>
            <span class="cap-desc">${meta.desc}</span>
          </div>
        </div>
        <span class="${badgeClass}" data-testid="cap-badge-${meta.key}">${badgeText}</span>
      </div>
    `;
  }).join('');
}
