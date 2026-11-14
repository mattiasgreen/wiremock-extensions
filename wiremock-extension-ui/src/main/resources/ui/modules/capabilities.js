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
    name: 'Lifecycle Snapshots & Modes',
    desc: 'Server-side traffic snapshotting and project proxy/stubs mode switching',
    icon: '⏸️',
    endpoint: '/__admin/lifecycle/status'
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
  },
  {
    key: 'casual',
    name: 'Casual Protocol Gateway',
    desc: 'Netty XATMI Casual protocol gateway and transaction emulation',
    icon: '🔌',
    endpoint: '/__admin/casual/config'
  },
  {
    key: 'async',
    name: 'Async Messaging & Queues (Beta)',
    desc: 'In-process Netty Kafka wire sink and HTTP-to-queue trigger mediator',
    icon: '📬',
    endpoint: '/__admin/async/status'
  },
  {
    key: 'journal',
    name: 'Zstandard Journal Compression (Beta)',
    desc: 'High-density adaptive dictionary compression and JVM headroom governor',
    icon: '🗜️',
    endpoint: '/__admin/journal/stats'
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
 * Applies visual degradation state to a button or control when an extension capability is missing.
 * Sets disabled attribute, visual style, and informative tooltip.
 */
export function applyControlState(element, isAvailable, missingTooltip) {
  if (!element) return;
  if (isAvailable) {
    element.removeAttribute('disabled');
    element.classList.remove('cap-disabled');
    if (element.hasAttribute('data-orig-title')) {
      element.setAttribute('title', element.getAttribute('data-orig-title'));
    } else {
      element.removeAttribute('title');
    }
  } else {
    if (!element.hasAttribute('data-orig-title') && element.getAttribute('title')) {
      element.setAttribute('data-orig-title', element.getAttribute('title'));
    }
    element.setAttribute('disabled', 'true');
    element.classList.add('cap-disabled');
    element.setAttribute('title', missingTooltip);
  }
}

/**
 * Toggles UI visual degradation state and informative tooltips depending on detected capabilities.
 */
export function applyCapabilityVisibility() {
  const caps = state.capabilities;

  // 1. TCP NetEm Tab & Container Banner
  if (elements.navTabNetem) {
    applyControlState(
      elements.navTabNetem,
      caps.netem,
      'TCP Network Emulation requires wiremock-extension-tcp-netem (/__admin/tcp-netem/proxies)'
    );
  }
  const tabNetem = document.getElementById('tab-netem');
  if (tabNetem) {
    let missingBanner = document.getElementById('netem-cap-missing-banner');
    if (!caps.netem) {
      if (!missingBanner) {
        missingBanner = document.createElement('div');
        missingBanner.id = 'netem-cap-missing-banner';
        missingBanner.className = 'cap-missing-banner';
        missingBanner.innerHTML = '<span>🌐 <strong>Extension Not Installed:</strong> <code>wiremock-extension-tcp-netem</code> is not active on this WireMock instance. Install the extension to enable L4 TCP latency, jitter, bandwidth throttling, and packet loss emulation.</span>';
        tabNetem.prepend(missingBanner);
      }
      missingBanner.classList.remove('hidden');
    } else if (missingBanner) {
      missingBanner.classList.add('hidden');
    }
  }

  // 1b. Casual Protocol Tester Controls & Container Banner
  syncCasualCapabilityBanner();

  // 2. Prometheus / Otel Metrics Link
  if (elements.linkMetrics) {
    applyControlState(
      elements.linkMetrics,
      caps.metrics,
      'Prometheus scrape route requires wiremock-extension-otel (/__admin/metrics/prometheus)'
    );
  }

  // 3. Stateful / Dynamic Stubs Controls
  if (elements.btnCreateDynamicStub) {
    applyControlState(
      elements.btnCreateDynamicStub,
      caps.stateful,
      'Stateful entity generator requires wiremock-extension-stateful'
    );
  }
  if (elements.btnFilterDynamic) {
    applyControlState(
      elements.btnFilterDynamic,
      caps.stateful,
      'Dynamic stateful simulation requires wiremock-extension-stateful'
    );
    if (!caps.stateful && state.statusFilter === 'dynamic') {
      state.statusFilter = 'all';
      if (elements.btnFilterAll) {
        document.querySelectorAll('.filter-pill').forEach(p => p.classList.remove('active'));
        elements.btnFilterAll.classList.add('active');
      }
    }
  }

  // 4. Lifecycle Snapshots & Project Mode Controls (priority parking & filter work natively on all WireMock hosts)
  if (elements.btnProjectSnapshot) {
    applyControlState(
      elements.btnProjectSnapshot,
      caps.lifecycle,
      'Traffic snapshotting requires wiremock-extension-ui lifecycle endpoint'
    );
  }
  if (elements.btnProjectModeToggle) {
    applyControlState(
      elements.btnProjectModeToggle,
      caps.lifecycle,
      'Project proxy/stubs mode switching requires wiremock-extension-ui lifecycle endpoint'
    );
  }
  if (elements.btnJournalSnapshotFiltered) {
    applyControlState(
      elements.btnJournalSnapshotFiltered,
      caps.lifecycle,
      'Batch snapshotting requires wiremock-extension-ui lifecycle endpoint'
    );
  }
}

/**
 * Toggles Casual gateway send state and missing-extension banner inside #tab-tester.
 */
export function syncCasualCapabilityBanner() {
  const caps = state.capabilities;
  const isCasualActive = (state.testerProtocol || 'http') === 'casual';
  if (elements.btnTesterSendCasual) {
    applyControlState(
      elements.btnTesterSendCasual,
      caps.casual,
      'Casual Tester requires wiremock-extension-casual (/__admin/casual/config)'
    );
  }
  const tabTester = document.getElementById('tab-tester');
  if (tabTester) {
    let casualMissingBanner = document.getElementById('casual-cap-missing-banner');
    if (!caps.casual && isCasualActive) {
      if (!casualMissingBanner) {
        casualMissingBanner = document.createElement('div');
        casualMissingBanner.id = 'casual-cap-missing-banner';
        casualMissingBanner.className = 'cap-missing-banner';
        casualMissingBanner.innerHTML = '<span>🔌 <strong>Extension Not Installed:</strong> <code>wiremock-extension-casual</code> is not active on this WireMock instance. Install the extension to enable Casual XATMI protocol service calls, stubbing, and transaction simulation.</span>';
        tabTester.prepend(casualMissingBanner);
      }
      casualMissingBanner.classList.remove('hidden');
    } else if (casualMissingBanner) {
      casualMissingBanner.classList.add('hidden');
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
