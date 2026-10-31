/**
 * In-Browser Mock Backend & Static Showcase Engine.
 *
 * Provides a self-contained, 100% interactive WireMock simulation engine
 * running directly inside the browser. Powers GitHub Pages and static previews
 * with preloaded stubs, rich request journal traffic, scenario state machines,
 * NetEm toxic configs, and Casual XATMI simulation.
 */

// Initial Seed Datasets
const SEED_STUBS = [
  {
    id: 'demo-stub-kyc-verify',
    name: 'Banking KYC Verification',
    request: {
      method: 'POST',
      urlPattern: '/api/v1/kyc/verify',
      headers: {
        'Content-Type': { contains: 'application/json' }
      },
      bodyPatterns: [{ matchesJsonPath: '$.caseId' }]
    },
    response: {
      status: 200,
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'X-Simulation-Engine': 'in-browser-demo'
      },
      body: JSON.stringify(
        {
          caseId: 'KYC-9042',
          status: 'APPROVED',
          verifiedAt: '2026-10-31T06:00:00Z',
          decisionId: 'DEC-8819',
          complianceAudit:
            '{"auditor":"auto-fsm","score":98,"embeddedReport":"<report xmlns=\\"http://example.com/audit\\"><valid>true</valid><level>TIER_1</level><checksum>a9f4c82b</checksum></report>"}',
          metadata: {
            operator: 'ComplianceBot-3',
            riskScore: 12,
            latencyMs: 14
          }
        },
        null,
        2
      )
    },
    metadata: {
      project: 'Banking KYC',
      tags: ['kyc', 'compliance', 'nested-payload']
    }
  },
  {
    id: 'demo-stub-cbs-soap',
    name: 'Core Banking SOAP Account Inquiry',
    request: {
      method: 'POST',
      urlPattern: '/ws/cbs/accounts',
      headers: {
        SOAPAction: { matches: '.*' }
      }
    },
    response: {
      status: 200,
      headers: {
        'Content-Type': 'text/xml; charset=utf-8'
      },
      body:
        '<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:cbs="http://banking.corp/cbs/v1">\n' +
        '  <soapenv:Header>\n' +
        '    <cbs:SecurityContext token="AUTH-SEC-9981"/>\n' +
        '  </soapenv:Header>\n' +
        '  <soapenv:Body>\n' +
        '    <cbs:GetAccountResponse>\n' +
        '      <cbs:AccountId>ACC-SE-991823</cbs:AccountId>\n' +
        '      <cbs:AccountType>CHECKING</cbs:AccountType>\n' +
        '      <cbs:Balance currency="SEK">452500.00</cbs:Balance>\n' +
        '      <cbs:Status>ACTIVE</cbs:Status>\n' +
        '    </cbs:GetAccountResponse>\n' +
        '  </soapenv:Body>\n' +
        '</soapenv:Envelope>'
    },
    metadata: {
      project: 'Core Banking',
      tags: ['soap', 'xml', 'cbs']
    }
  },
  {
    id: 'demo-stub-order-create',
    name: 'Create Order (FSM Start)',
    scenarioName: 'OrderLifecycle',
    requiredScenarioState: 'Started',
    newScenarioState: 'PROCESSING',
    request: {
      method: 'POST',
      urlPattern: '/api/v1/orders'
    },
    response: {
      status: 201,
      headers: {
        'Content-Type': 'application/json',
        Location: '/api/v1/orders/ORD-101'
      },
      body: JSON.stringify(
        {
          orderId: 'ORD-101',
          state: 'PROCESSING',
          totalAmount: 1250.0,
          currency: 'EUR'
        },
        null,
        2
      )
    },
    metadata: {
      project: 'E-Commerce FSM',
      tags: ['orders', 'fsm']
    }
  },
  {
    id: 'demo-stub-order-poll',
    name: 'Poll Order Status (In Progress)',
    scenarioName: 'OrderLifecycle',
    requiredScenarioState: 'PROCESSING',
    request: {
      method: 'GET',
      urlPattern: '/api/v1/orders/ORD-101'
    },
    response: {
      status: 200,
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(
        {
          orderId: 'ORD-101',
          state: 'PROCESSING',
          warehouseStatus: 'PACKING',
          progress: 65
        },
        null,
        2
      )
    },
    metadata: {
      project: 'E-Commerce FSM',
      tags: ['orders', 'fsm']
    }
  },
  {
    id: 'demo-stub-order-complete',
    name: 'Complete Order (FSM Finish)',
    scenarioName: 'OrderLifecycle',
    requiredScenarioState: 'PROCESSING',
    newScenarioState: 'COMPLETED',
    request: {
      method: 'POST',
      urlPattern: '/api/v1/orders/ORD-101/complete'
    },
    response: {
      status: 200,
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(
        {
          orderId: 'ORD-101',
          state: 'COMPLETED',
          trackingNumber: 'DHL-SE-981240'
        },
        null,
        2
      )
    },
    metadata: {
      project: 'E-Commerce FSM',
      tags: ['orders', 'fsm']
    }
  },
  {
    id: 'demo-stub-users-list',
    name: 'List Active Users',
    request: {
      method: 'GET',
      urlPattern: '/api/v1/users(\\?.*)?'
    },
    response: {
      status: 200,
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(
        [
          { id: 'usr-1', name: 'Ada Lovelace', role: 'ADMIN' },
          { id: 'usr-2', name: 'Alan Turing', role: 'SECURITY' },
          { id: 'usr-3', name: 'Grace Hopper', role: 'DEVELOPER' }
        ],
        null,
        2
      )
    },
    metadata: {
      project: 'Identity API',
      tags: ['users', 'rest']
    }
  },
  {
    id: 'demo-stub-credit-eval',
    name: 'Credit Bureau Evaluation',
    request: {
      method: 'POST',
      urlPattern: '/api/v1/credit/evaluate'
    },
    response: {
      status: 200,
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(
        {
          score: 780,
          tier: 'PRIME',
          maxExposure: 1000000,
          evaluatedAt: '2026-10-31T07:00:00Z'
        },
        null,
        2
      )
    },
    metadata: {
      project: 'Credit Bureau',
      tags: ['credit', 'finance']
    }
  }
];

const SEED_DISABLED_STUBS = [
  {
    id: 'demo-stub-disabled-legacy',
    name: 'Legacy Payment Gateway [DEPRECATED]',
    request: {
      method: 'POST',
      urlPattern: '/api/v0/payments'
    },
    response: {
      status: 410,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ error: 'Endpoint permanently retired' }, null, 2)
    },
    metadata: {
      project: 'Core Banking',
      tags: ['deprecated', 'disabled']
    }
  },
  {
    id: 'demo-stub-disabled-chaos',
    name: 'Chaos Latency Spike [PAUSED]',
    request: {
      method: 'GET',
      urlPattern: '/api/v1/health'
    },
    response: {
      status: 503,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ error: 'Injected fault' }, null, 2)
    },
    metadata: {
      project: 'Infrastructure',
      tags: ['chaos', 'disabled']
    }
  }
];

const SEED_SCENARIOS = [
  {
    id: 'OrderLifecycle',
    name: 'OrderLifecycle',
    state: 'PROCESSING',
    possibleStates: ['Started', 'PROCESSING', 'COMPLETED']
  }
];

const SEED_STATEFUL_MODELS = [
  {
    modelId: 'BankingCaseManagement',
    name: 'BankingCaseManagement',
    rules: [
      {
        ruleId: 'auto-approval-tier-1',
        description: 'Auto-approves low-risk KYC cases under SEK 10,000 threshold',
        condition: 'input.riskScore < 20 && input.amount <= 10000',
        action: 'state.status = "APPROVED"; state.tier = "TIER_1";'
      },
      {
        ruleId: 'manual-review-tier-2',
        description: 'Flags borderline KYC cases for senior compliance officer review',
        condition: 'input.riskScore >= 20 || input.amount > 10000',
        action: 'state.status = "PENDING_REVIEW"; state.tier = "TIER_2";'
      }
    ]
  }
];

const SEED_NETEM_PROXIES = [
  {
    name: 'cbs_upstream_proxy',
    listen: '0.0.0.0:9090',
    upstream: 'localhost:8080',
    enabled: true,
    toxics: [
      {
        name: 'cbs_latency',
        type: 'latency',
        stream: 'downstream',
        toxicity: 1.0,
        attributes: { latency: 120, jitter: 25 }
      }
    ]
  }
];

const SEED_CASUAL_SERVICES = [
  { name: 'echoService', bufferType: 'JSON' },
  { name: 'creditScoreInquiry', bufferType: 'JSON' },
  { name: 'settlePayment', bufferType: 'FLAT' }
];

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

const SEED_REQUESTS = [
  {
    id: 'req-demo-01',
    request: {
      url: '/api/v1/kyc/verify',
      absoluteUrl: 'http://localhost:8080/api/v1/kyc/verify',
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Correlation-Id': 'c9e82104-58a1-4328-bfa9-123456789abc',
        'X-Tenant-Id': 'corp-nordic'
      },
      body: JSON.stringify(
        {
          caseId: 'KYC-9042',
          applicant: {
            name: 'Alice Lindqvist',
            ssn: '19850412-1234'
          },
          documents: [
            {
              type: 'PASSPORT',
              rawXmlPayload:
                '<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><VerificationReport id="VR-992"><Status>VERIFIED</Status><RiskScore>12</RiskScore></VerificationReport></soap:Body></soap:Envelope>'
            }
          ]
        },
        null,
        2
      ),
      loggedDate: Date.now() - 15000
    },
    response: {
      status: 200,
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'X-Simulation-Engine': 'in-browser-demo'
      },
      body: JSON.stringify(
        {
          caseId: 'KYC-9042',
          status: 'APPROVED',
          verifiedAt: '2026-10-31T06:00:00Z',
          decisionId: 'DEC-8819',
          complianceAudit:
            '{"auditor":"auto-fsm","score":98,"embeddedReport":"<report xmlns=\\"http://example.com/audit\\"><valid>true</valid><level>TIER_1</level><checksum>a9f4c82b</checksum></report>"}',
          metadata: {
            operator: 'ComplianceBot-3',
            riskScore: 12,
            latencyMs: 14
          }
        },
        null,
        2
      )
    },
    wasMatched: true,
    timing: { serveTimeMillis: 14, totalTimeMillis: 18 },
    stubMapping: SEED_STUBS[0]
  },
  {
    id: 'req-demo-02',
    request: {
      url: '/api/v1/orders/ORD-101',
      absoluteUrl: 'http://localhost:8080/api/v1/orders/ORD-101',
      method: 'GET',
      headers: {
        Accept: 'application/json',
        'X-Correlation-Id': '8129e011-39da-44ba-bc12-987654321def'
      },
      body: '',
      loggedDate: Date.now() - 42000
    },
    response: {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(
        {
          orderId: 'ORD-101',
          state: 'PROCESSING',
          warehouseStatus: 'PACKING',
          progress: 65
        },
        null,
        2
      )
    },
    wasMatched: true,
    timing: { serveTimeMillis: 9, totalTimeMillis: 12 },
    stubMapping: SEED_STUBS[3]
  },
  {
    id: 'req-demo-03',
    request: {
      url: '/ws/cbs/accounts',
      absoluteUrl: 'http://localhost:8080/ws/cbs/accounts',
      method: 'POST',
      headers: {
        'Content-Type': 'text/xml; charset=utf-8',
        SOAPAction: '"http://banking.corp/cbs/v1/GetAccount"'
      },
      body:
        '<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:cbs="http://banking.corp/cbs/v1">\n' +
        '  <soapenv:Body>\n' +
        '    <cbs:GetAccountRequest>\n' +
        '      <cbs:AccountId>ACC-SE-991823</cbs:AccountId>\n' +
        '    </cbs:GetAccountRequest>\n' +
        '  </soapenv:Body>\n' +
        '</soapenv:Envelope>',
      loggedDate: Date.now() - 95000
    },
    response: {
      status: 200,
      headers: { 'Content-Type': 'text/xml; charset=utf-8' },
      body: SEED_STUBS[1].response.body
    },
    wasMatched: true,
    timing: { serveTimeMillis: 22, totalTimeMillis: 27 },
    stubMapping: SEED_STUBS[1]
  },
  {
    id: 'req-demo-04',
    request: {
      url: '/api/v1/auth/token',
      absoluteUrl: 'http://localhost:8080/api/v1/auth/token',
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: 'Basic YWRtaW46c2VjcmV0MTIz'
      },
      body: JSON.stringify(
        {
          grantType: 'client_credentials',
          assertion:
            'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c3ItMSIsIm5hbWUiOiJBZGEgTG92ZWxhY2UiLCJyb2xlIjoiQURNSU4iLCJpYXQiOjE3Mjk4NTI4MDB9.c2lnbmF0dXJl'
        },
        null,
        2
      ),
      loggedDate: Date.now() - 140000
    },
    response: {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(
        {
          accessToken: 'demo-bearer-token-xyz998',
          tokenType: 'Bearer',
          expiresIn: 3600
        },
        null,
        2
      )
    },
    wasMatched: true,
    timing: { serveTimeMillis: 6, totalTimeMillis: 8 }
  },
  {
    id: 'req-demo-05',
    request: {
      url: '/api/v1/unknown-endpoint/test',
      absoluteUrl: 'http://localhost:8080/api/v1/unknown-endpoint/test',
      method: 'GET',
      headers: {
        Accept: 'application/json'
      },
      body: '',
      loggedDate: Date.now() - 210000
    },
    response: {
      status: 404,
      headers: { 'Content-Type': 'text/plain' },
      body: 'Request was not matched by any stub mapping.'
    },
    wasMatched: false,
    timing: { serveTimeMillis: 2, totalTimeMillis: 3 }
  }
];

class InMemoryDemoBackend {
  constructor() {
    this.stubs = JSON.parse(JSON.stringify(SEED_STUBS));
    this.disabledStubs = JSON.parse(JSON.stringify(SEED_DISABLED_STUBS));
    this.requests = JSON.parse(JSON.stringify(SEED_REQUESTS));
    this.scenarios = JSON.parse(JSON.stringify(SEED_SCENARIOS));
    this.statefulModels = JSON.parse(JSON.stringify(SEED_STATEFUL_MODELS));
    this.netemProxies = JSON.parse(JSON.stringify(SEED_NETEM_PROXIES));
    this.casualServices = JSON.parse(JSON.stringify(SEED_CASUAL_SERVICES));
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
    this.stubs = JSON.parse(JSON.stringify(SEED_STUBS));
    this.disabledStubs = JSON.parse(JSON.stringify(SEED_DISABLED_STUBS));
    this.requests = JSON.parse(JSON.stringify(SEED_REQUESTS));
    this.scenarios = JSON.parse(JSON.stringify(SEED_SCENARIOS));
    this.statefulModels = JSON.parse(JSON.stringify(SEED_STATEFUL_MODELS));
    this.netemProxies = JSON.parse(JSON.stringify(SEED_NETEM_PROXIES));
    this.casualServices = JSON.parse(JSON.stringify(SEED_CASUAL_SERVICES));
  }

  matchStub(method, path) {
    const cleanPath = path.split('?')[0];
    for (const stub of this.stubs) {
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

    // 2. Lifecycle Disabled Stubs & Toggle
    if (pathname === '/__admin/stubs/disabled') {
      if (method === 'GET') {
        return responseJson(200, {
          mappings: this.disabledStubs,
          meta: { total: this.disabledStubs.length }
        });
      }
    }

    const toggleMatch = pathname.match(/^\/__admin\/stubs\/([^/]+)\/toggle$/);
    if (toggleMatch && method === 'POST') {
      const stubId = toggleMatch[1];
      const activeIdx = this.stubs.findIndex(s => s.id === stubId);
      if (activeIdx !== -1) {
        const stub = this.stubs.splice(activeIdx, 1)[0];
        this.disabledStubs.unshift(stub);
        return responseJson(200, { id: stubId, disabled: true });
      }
      const disabledIdx = this.disabledStubs.findIndex(s => s.id === stubId);
      if (disabledIdx !== -1) {
        const stub = this.disabledStubs.splice(disabledIdx, 1)[0];
        this.stubs.unshift(stub);
        return responseJson(200, { id: stubId, disabled: false });
      }
      return responseText(404, 'Stub not found');
    }

    const stubByIdMatch = pathname.match(/^\/__admin\/stubs\/([^/]+)$/);
    if (stubByIdMatch) {
      const stubId = stubByIdMatch[1];
      if (method === 'PUT') {
        const body = typeof options.body === 'string' ? JSON.parse(options.body || '{}') : options.body || {};
        const activeIdx = this.stubs.findIndex(s => s.id === stubId);
        if (activeIdx !== -1) {
          this.stubs[activeIdx] = Object.assign({}, body, { id: stubId });
          return responseJson(200, this.stubs[activeIdx]);
        }
        const disabledIdx = this.disabledStubs.findIndex(s => s.id === stubId);
        if (disabledIdx !== -1) {
          this.disabledStubs[disabledIdx] = Object.assign({}, body, { id: stubId });
          return responseJson(200, this.disabledStubs[disabledIdx]);
        }
        this.stubs.unshift(Object.assign({}, body, { id: stubId }));
        return responseJson(201, body);
      }
      if (method === 'DELETE') {
        this.stubs = this.stubs.filter(s => s.id !== stubId);
        this.disabledStubs = this.disabledStubs.filter(s => s.id !== stubId);
        return responseText(204, '');
      }
    }

    if (pathname === '/__admin/stubs/bulk' && method === 'POST') {
      const body = typeof options.body === 'string' ? JSON.parse(options.body || '{}') : options.body || {};
      const { action, ids } = body;
      const targetIds = new Set(ids || []);
      if (action === 'disable') {
        const toMove = this.stubs.filter(s => targetIds.has(s.id));
        this.stubs = this.stubs.filter(s => !targetIds.has(s.id));
        this.disabledStubs.unshift(...toMove);
      } else if (action === 'enable') {
        const toMove = this.disabledStubs.filter(s => targetIds.has(s.id));
        this.disabledStubs = this.disabledStubs.filter(s => !targetIds.has(s.id));
        this.stubs.unshift(...toMove);
      } else if (action === 'delete') {
        this.stubs = this.stubs.filter(s => !targetIds.has(s.id));
        this.disabledStubs = this.disabledStubs.filter(s => !targetIds.has(s.id));
      }
      return responseJson(200, { success: true, count: targetIds.size });
    }

    // 3. Request Journal
    if (pathname === '/__admin/requests' || pathname === '/__admin/ui/api/requests') {
      if (method === 'GET') {
        let list = [...this.requests];
        const q = (searchParams.get('q') || '').toLowerCase();
        if (q) {
          list = list.filter(r => {
            const str = `${r.request.method} ${r.request.url} ${JSON.stringify(r.request.body || '')}`.toLowerCase();
            return str.includes(q);
          });
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

    if (pathname === '/__admin/casual/call' && method === 'POST') {
      const payload = typeof options.body === 'string' ? JSON.parse(options.body || '{}') : options.body || {};
      const service = payload.service || 'echoService';
      const bufferType = payload.bufferType || 'JSON';
      const reqData = payload.payload || '';

      let replyPayload = reqData;
      if (bufferType === 'JSON') {
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

      return responseJson(200, {
        status: 0,
        service,
        bufferType,
        payload: replyPayload,
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
        version: '1.9.0-demo',
        branch: 'main',
        commit: 'gh-pages'
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
    if (pathname.includes('/recordings/snapshot') || pathname.includes('/snapshot-filtered')) {
      return responseJson(200, { snapshotted: 1 });
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
