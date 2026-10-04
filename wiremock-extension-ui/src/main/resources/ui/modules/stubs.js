/**
 * Stubs master-detail viewer and cURL generator.
 * Handles search filtering, detail card rendering, and transfer to HTTP tester.
 */
import { state } from './state.js';
import { elements } from './dom.js';
import { highlightJson } from './highlighter.js';
import { setRoute, toBase64, activateTab } from './router.js';

export function getStubUrl(req) {
  if (!req) return '/';
  return (
    req.url ||
    req.urlPath ||
    req.urlPathTemplate ||
    req.urlPattern ||
    req.urlPathPattern ||
    req.urlPathMatching ||
    '/'
  );
}

export function generateCurl(method, path, headers = {}, body = '') {
  const origin = window.location.origin || 'http://localhost:8080';
  const cleanPath = path || '/';
  const fullUrl = cleanPath.startsWith('http') ? cleanPath : `${origin}${cleanPath.startsWith('/') ? '' : '/'}${cleanPath}`;
  const parts = [`curl -X ${method || 'GET'} "${fullUrl}"`];
  for (const [k, v] of Object.entries(headers)) {
    if (v) parts.push(`-H "${k}: ${v}"`);
  }
  if (body && ['POST', 'PUT', 'PATCH', 'DELETE'].includes((method || 'GET').toUpperCase())) {
    const escapedBody = String(body).replace(/"/g, '\\"');
    parts.push(`-d "${escapedBody}"`);
  }
  return parts.join(' \\\n  ');
}

export function renderStubList() {
  const filter = (elements.searchBox.value || '').toLowerCase().trim();
  const filtered = state.currentStubs.filter(s => {
    if (!filter) return true;
    const method = (s.request && s.request.method ? s.request.method : 'ANY').toLowerCase();
    const url = getStubUrl(s.request).toLowerCase();
    const name = (s.name || '').toLowerCase();
    return method.includes(filter) || url.includes(filter) || name.includes(filter);
  });

  if (filtered.length === 0) {
    elements.stubList.innerHTML = '<div class="empty-state">No matching stubs found.</div>';
    return;
  }

  elements.stubList.innerHTML = '';
  filtered.forEach(stub => {
    const card = document.createElement('div');
    card.className = `stub-card ${stub.id === state.selectedStubId ? 'selected' : ''}`;
    card.setAttribute('data-stub-id', stub.id);

    const method = (stub.request && stub.request.method) || 'ANY';
    const url = getStubUrl(stub.request);
    const name = stub.name || (stub.response && stub.response.status ? `Status ${stub.response.status}` : 'Unnamed');

    card.innerHTML = `
      <div class="stub-card-top">
        <span class="http-badge badge-${method}">${method}</span>
        <span class="stub-card-url" title="${url}">${url}</span>
      </div>
      <div class="stub-card-bottom">
        <span>${name}</span>
        <span>Status: ${(stub.response && stub.response.status) || 200}</span>
      </div>
    `;

    card.addEventListener('click', () => selectStub(stub, true));
    elements.stubList.appendChild(card);
  });

  if (state.selectedStubId) {
    const selected = state.currentStubs.find(s => s.id === state.selectedStubId);
    if (selected) selectStub(selected, false);
  }
}

export function selectStub(stub, updateRoute = true) {
  state.selectedStubId = stub.id;
  document.querySelectorAll('.stub-card').forEach(el => {
    const id = el.getAttribute('data-stub-id') || (el.dataset && el.dataset.stubId);
    if (id === stub.id) {
      el.classList.add('selected');
    } else {
      el.classList.remove('selected');
    }
  });

  elements.stubDetailEmpty.classList.add('hidden');
  elements.stubDetailView.classList.remove('hidden');

  const method = (stub.request && stub.request.method) || 'ANY';
  elements.detailMethod.textContent = method;
  elements.detailMethod.className = `http-badge badge-${method}`;
  elements.detailUrl.textContent = getStubUrl(stub.request);
  elements.detailName.textContent = stub.name ? `— ${stub.name}` : '';

  const status = (stub.response && stub.response.status) || 200;
  elements.detailStatus.textContent = status;
  elements.detailStatus.className = `card-value status-${status >= 500 ? '500' : (status >= 400 ? '400' : '200')}`;

  elements.detailPriority.textContent = stub.priority || 5;
  elements.detailScenario.textContent = stub.scenarioName
    ? `${stub.scenarioName} [${stub.requiredScenarioState || 'Start'} -> ${stub.newScenarioState || 'Same'}]`
    : 'None';

  elements.stubJsonViewer.innerHTML = highlightJson(stub);

  if (updateRoute) {
    setRoute('stubs', { stubId: stub.id });
  }
}

export function sendStubToTester(stub) {
  if (!stub) return;
  const method = (stub.request && stub.request.method) || 'GET';
  const path = getStubUrl(stub.request);
  const headerLines = [];
  if (stub.request && stub.request.headers) {
    for (const [k, v] of Object.entries(stub.request.headers)) {
      headerLines.push(`${k}: ${v.equalTo || v.matches || v.contains || Object.values(v)[0] || ''}`);
    }
  }
  let body = '';
  if (stub.request && stub.request.bodyPatterns && stub.request.bodyPatterns.length > 0) {
    const bp = stub.request.bodyPatterns[0];
    body = bp.equalToJson ? JSON.stringify(bp.equalToJson, null, 2) : (bp.equalTo || '');
  }

  if (elements.testerMethod) elements.testerMethod.value = method === 'ANY' ? 'GET' : method;
  if (elements.testerUrl) elements.testerUrl.value = path;
  if (elements.testerHeaders) elements.testerHeaders.value = headerLines.join('\n');
  if (elements.testerBody) elements.testerBody.value = body;

  activateTab('tab-tester', false);
  setRoute('tester', {
    m: method === 'ANY' ? 'GET' : method,
    p: path,
    h: headerLines.length ? toBase64(headerLines.join('\n')) : null,
    b: body ? toBase64(body) : null
  }, false);
}
