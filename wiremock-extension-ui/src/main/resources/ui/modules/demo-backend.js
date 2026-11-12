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
    id: 'demo-stub-multiformat-deep',
    name: 'B2B SWIFT Transfer (Nested Multi-Format Showcase)',
    request: {
      method: 'POST',
      urlPattern: '/api/v1/b2b/swift-transfer',
      headers: {
        'Content-Type': { contains: 'application/json' }
      }
    },
    response: {
      status: 200,
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'X-Simulation-Engine': 'in-browser-demo'
      },
      body: JSON.stringify(
        {
          transferId: 'SWIFT-2026-X891',
          status: 'SETTLED',
          settledAt: '2026-11-01T05:00:00Z',
          executionTrace: {
            gatewayEnvelope:
              '{"routing":{"network":"SWIFT_NET","bic":"NDEAFIHH"},"messagePayload":"<swiftMessage xmlns=\\"http://banking.corp/swift\\"><header><sender>SWIFT-EXT</sender></header><body><![CDATA[{\\"account\\":\\"ACC-112233\\",\\"auditTrail\\":\\"{\\\\\\"auditor\\\\\\\":\\\\\\"ISO20022-GATEWAY\\\\\\\",\\\\\\"passed\\\\\\\":true}\\"}]]></body></swiftMessage>"}'
          }
        },
        null,
        2
      )
    },
    metadata: {
      project: 'B2B Payments',
      tags: ['multiformat', 'cdata', 'swift', 'nested-payload']
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
  },
  {
    id: 'demo-stub-casual-credit-inquiry',
    name: 'Casual XATMI: creditScoreInquiry',
    request: {
      method: 'POST',
      urlPattern: '/__casual/services/creditScoreInquiry',
      headers: {
        'x-casual-service': { equalTo: 'creditScoreInquiry' }
      }
    },
    response: {
      status: 200,
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'X-Casual-Service': 'creditScoreInquiry',
        'X-Casual-Error-State': 'OK',
        'X-Simulation-Engine': 'in-browser-demo'
      },
      body: JSON.stringify(
        {
          service: 'creditScoreInquiry',
          status: 'OK',
          xatmiReturnCode: 0,
          transactionId: 'XID-NORDIC-992144',
          creditAssessment: {
            score: 815,
            rating: 'AAA',
            authorizedLimit: 750000.0,
            currency: 'EUR',
            riskTier: 'MINIMAL'
          }
        },
        null,
        2
      )
    },
    metadata: {
      project: 'Casual Gateway',
      protocol: 'casual',
      service: 'creditScoreInquiry',
      exampleRequest: {
        path: '/__casual/services/creditScoreInquiry',
        headers: {
          'Content-Type': 'application/json',
          'x-casual-service': 'creditScoreInquiry',
          'x-casual-buffer-type': 'JSON'
        },
        body: {
          customerId: 'CUST-8812',
          requestedAmount: 150000.0,
          currency: 'EUR'
        }
      },
      tags: ['casual', 'xatmi', 'credit', 'rpc']
    }
  },
  {
    id: 'demo-stub-casual-settle-payment',
    name: 'Casual XATMI: settlePayment (Nested XML Audit)',
    request: {
      method: 'POST',
      urlPattern: '/__casual/services/settlePayment',
      headers: {
        'x-casual-service': { equalTo: 'settlePayment' }
      }
    },
    response: {
      status: 200,
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'X-Casual-Service': 'settlePayment',
        'X-Casual-Error-State': 'OK',
        'X-Simulation-Engine': 'in-browser-demo'
      },
      body: JSON.stringify(
        {
          service: 'settlePayment',
          status: 'OK',
          xatmiReturnCode: 0,
          clearingReference: 'CLR-2026-9901',
          auditTrail:
            '{"auditor":"casual-xatmi-monitor","auditXml":"<?xml version=\\"1.0\\" encoding=\\"ISO-8859-1\\"?><settlementReport id=\\"SR-8819\\"><status>SETTLED</status><beneficiary>Åke Söderström</beneficiary><clearingTime>2026-11-01T05:30:00Z</clearingTime><ledgerId>LEDG-0021</ledgerId></settlementReport>"}'
        },
        null,
        2
      )
    },
    metadata: {
      project: 'Casual Gateway',
      protocol: 'casual',
      service: 'settlePayment',
      exampleRequest: {
        path: '/__casual/services/settlePayment',
        headers: {
          'Content-Type': 'application/json',
          'x-casual-service': 'settlePayment',
          'x-casual-buffer-type': 'JSON'
        },
        body: {
          transferId: 'TX-99012',
          debtorAccount: 'ACC-SE-991823',
          creditorAccount: 'ACC-DE-102938',
          amount: 50000.0
        }
      },
      tags: ['casual', 'xatmi', 'clearing', 'nested-payload']
    }
  },
  {
    id: 'demo-stub-casual-echo',
    name: 'Casual XATMI: echoService (tpcall loopback)',
    request: {
      method: 'POST',
      urlPattern: '/__casual/services/echoService'
    },
    response: {
      status: 200,
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'X-Casual-Service': 'echoService',
        'X-Casual-Error-State': 'OK',
        'X-Simulation-Engine': 'in-browser-demo'
      },
      body: JSON.stringify(
        {
          service: 'echoService',
          status: 'OK',
          xatmiReturnCode: 0,
          message: 'Casual service loopback operational'
        },
        null,
        2
      )
    },
    metadata: {
      project: 'Casual Gateway',
      protocol: 'casual',
      service: 'echoService',
      exampleRequest: {
        path: '/__casual/services/echoService',
        headers: {
          'Content-Type': 'application/json',
          'x-casual-service': 'echoService',
          'x-casual-buffer-type': 'JSON'
        },
        body: { ping: 'pong', timestamp: 1730438400000 }
      },
      tags: ['casual', 'xatmi', 'echo']
    }
  },
  {
    id: 'demo-stub-async-kafka-orders',
    name: 'Kafka Events Sink: orders.events',
    request: {
      method: 'CONSUME',
      urlPattern: '/__async/kafka/orders.events',
      headers: {
        'Content-Type': { contains: 'application/json' }
      },
      bodyPatterns: [{ matchesJsonPath: '$.orderId' }]
    },
    response: {
      status: 204,
      headers: {
        'X-Async-Ack': 'ack',
        'X-Async-Protocol': 'kafka',
        'X-Async-Partition': '0'
      }
    },
    metadata: {
      project: 'Async Messaging',
      protocol: 'kafka',
      channel: 'orders.events',
      ackPolicy: 'ack',
      tags: ['async', 'kafka', 'fire-and-forget', 'events']
    }
  },
  {
    id: 'demo-stub-async-amqp-payments',
    name: 'AMQP 1.0: payments.requests (Request-Reply with Polyglot XML)',
    request: {
      method: 'CONSUME',
      urlPattern: '/__async/amqp/payments.requests',
      headers: {
        'x-currency': { equalTo: 'EUR' }
      },
      bodyPatterns: [{ matchesJsonPath: '$.payment.amount' }]
    },
    response: {
      status: 200,
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'X-Async-Correlation-Id': '{{request.headers.X-Async-Correlation-Id}}',
        'X-Async-Reply-Channel': 'payments.replies',
        'X-Processed-By': 'wiremock-async-engine'
      },
      body: JSON.stringify(
        {
          transactionId: 'TX-AMQP-992144',
          status: 'APPROVED',
          amount: 1500.0,
          currency: 'EUR',
          clearingReference: 'CLR-SEPA-9921',
          auditReceipt:
            '<receipt xmlns="http://bank.example/audit"><status>SETTLED</status><clearedTimestamp>2026-11-09T14:30:00Z</clearedTimestamp><ledgerRef>LEDG-AMQP-009</ledgerRef></receipt>'
        },
        null,
        2
      )
    },
    metadata: {
      project: 'Async Messaging',
      protocol: 'amqp',
      broker: 'amqp://127.0.0.1:5672',
      channel: 'payments.requests',
      ackPolicy: 'ack',
      tags: ['async', 'amqp', 'request-reply', 'payments', 'nested-payload']
    }
  },
  {
    id: 'demo-stub-async-http-trigger',
    name: 'REST Checkout with Asynchronous Kafka Event Trigger',
    request: {
      method: 'POST',
      urlPattern: '/api/v1/orders/async-checkout',
      headers: {
        'Content-Type': { contains: 'application/json' }
      }
    },
    response: {
      status: 202,
      headers: {
        'Content-Type': 'application/json; charset=utf-8'
      },
      body: JSON.stringify(
        {
          orderId: 'ORD-9901-ASYNC',
          status: 'ACCEPTED',
          message: 'Order accepted for asynchronous fulfillment'
        },
        null,
        2
      )
    },
    metadata: {
      project: 'Async Messaging',
      async: {
        produce: [
          {
            protocol: 'kafka',
            destination: 'notifications.broadcast',
            key: 'ORD-9901-ASYNC',
            payload: '{"orderId":"ORD-9901-ASYNC","event":"ORDER_PLACED","status":"PENDING_PAYMENT"}',
            delayMs: 50
          }
        ]
      },
      tags: ['async', 'kafka', 'callback', 'http-trigger']
    }
  },
  {
    id: 'demo-stub-casual-queue-billing',
    name: 'Casual Queue: billing.q (2PC/XA Dequeue)',
    request: {
      method: 'POST',
      urlPattern: '/__casual/queues/billing.q/dequeue'
    },
    response: {
      status: 200,
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'X-WireMock-Protocol': 'casual',
        'X-Casual-Queue': 'billing.q',
        'X-Casual-Operation': 'dequeue',
        'X-Simulation-Engine': 'in-browser-demo'
      },
      body: JSON.stringify(
        [
          {
            id: 'msg-casual-queue-881',
            payload: {
              invoiceId: 'INV-2026-881',
              amount: 7420.0,
              currency: 'SEK',
              status: 'PENDING'
            }
          }
        ],
        null,
        2
      )
    },
    metadata: {
      project: 'Casual Gateway',
      protocol: 'casual',
      queue: 'billing.q',
      tags: ['casual', 'queue', 'dequeue', 'xatmi']
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
    id: 'req-demo-multiformat-deep',
    request: {
      url: '/api/v1/b2b/swift-transfer',
      absoluteUrl: 'http://localhost:8080/api/v1/b2b/swift-transfer',
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-Tenant-Id': 'corp-b2b'
      },
      body: JSON.stringify(
        {
          transferId: 'SWIFT-2026-X891',
          senderBic: 'NDEAFIHH',
          receiverBic: 'HANDSEMM',
          amount: 2500000.0,
          currency: 'EUR'
        },
        null,
        2
      ),
      loggedDate: Date.now() - 25000
    },
    response: {
      status: 200,
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'X-Simulation-Engine': 'in-browser-demo'
      },
      body: JSON.stringify(
        {
          transferId: 'SWIFT-2026-X891',
          status: 'SETTLED',
          settledAt: '2026-11-01T05:00:00Z',
          executionTrace: {
            gatewayEnvelope:
              '{"routing":{"network":"SWIFT_NET","bic":"NDEAFIHH"},"messagePayload":"<swiftMessage xmlns=\\"http://banking.corp/swift\\"><header><sender>SWIFT-EXT</sender></header><body><![CDATA[{\\"account\\\":\\"ACC-112233\\",\\"auditTrail\\\":\\"{\\\\\\"auditor\\\\\\\":\\\\\\"ISO20022-GATEWAY\\\\\\\",\\\\\\"passed\\\\\\\":true}\\"}]]></body></swiftMessage>"}'
          }
        },
        null,
        2
      )
    },
    wasMatched: true,
    timing: { serveTimeMillis: 22, totalTimeMillis: 28 },
    stubMapping: SEED_STUBS[2]
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
  },
  {
    id: 'req-demo-casual-01',
    request: {
      url: '/__casual/services/creditScoreInquiry',
      absoluteUrl: 'http://localhost:8080/__casual/services/creditScoreInquiry',
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-casual-service': 'creditScoreInquiry',
        'x-casual-buffer-type': 'JSON',
        'X-Correlation-Id': '7c891102-12a4-4df1-bc99-234567890def'
      },
      body: JSON.stringify(
        {
          customerId: 'CUST-8812',
          requestedAmount: 150000.0,
          currency: 'EUR'
        },
        null,
        2
      ),
      loggedDate: Date.now() - 32000
    },
    response: {
      status: 200,
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'X-Casual-Service': 'creditScoreInquiry',
        'X-Casual-Error-State': 'OK',
        'X-Simulation-Engine': 'in-browser-demo'
      },
      body: JSON.stringify(
        {
          service: 'creditScoreInquiry',
          status: 'OK',
          xatmiReturnCode: 0,
          transactionId: 'XID-NORDIC-992144',
          creditAssessment: {
            score: 815,
            rating: 'AAA',
            authorizedLimit: 750000.0,
            currency: 'EUR',
            riskTier: 'MINIMAL'
          }
        },
        null,
        2
      )
    },
    wasMatched: true,
    timing: { serveTimeMillis: 8, totalTimeMillis: 11 },
    stubMapping: {
      id: 'demo-stub-casual-credit-inquiry',
      name: 'Casual XATMI: creditScoreInquiry'
    }
  },
  {
    id: 'req-demo-casual-02',
    request: {
      url: '/__casual/services/settlePayment',
      absoluteUrl: 'http://localhost:8080/__casual/services/settlePayment',
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-casual-service': 'settlePayment',
        'x-casual-buffer-type': 'JSON'
      },
      body: JSON.stringify(
        {
          transferId: 'TX-99012',
          debtorAccount: 'ACC-SE-991823',
          creditorAccount: 'ACC-DE-102938',
          amount: 50000.0
        },
        null,
        2
      ),
      loggedDate: Date.now() - 68000
    },
    response: {
      status: 200,
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'X-Casual-Service': 'settlePayment',
        'X-Casual-Error-State': 'OK',
        'X-Simulation-Engine': 'in-browser-demo'
      },
      body: JSON.stringify(
        {
          service: 'settlePayment',
          status: 'OK',
          xatmiReturnCode: 0,
          clearingReference: 'CLR-2026-9901',
          auditTrail:
            '{"auditor":"casual-xatmi-monitor","auditXml":"<?xml version=\\"1.0\\" encoding=\\"ISO-8859-1\\"?><settlementReport id=\\"SR-8819\\"><status>SETTLED</status><beneficiary>Åke Söderström</beneficiary><clearingTime>2026-11-01T05:30:00Z</clearingTime><ledgerId>LEDG-0021</ledgerId></settlementReport>"}'
        },
        null,
        2
      )
    },
    wasMatched: true,
    timing: { serveTimeMillis: 14, totalTimeMillis: 18 },
    stubMapping: {
      id: 'demo-stub-casual-settle-payment',
      name: 'Casual XATMI: settlePayment (Nested XML Audit)'
    }
  },
  {
    id: 'req-demo-async-kafka',
    request: {
      url: '/__async/kafka/orders.events',
      absoluteUrl: 'http://localhost:8080/__async/kafka/orders.events',
      method: 'PRODUCE',
      headers: {
        'Content-Type': 'application/json',
        'X-Async-Channel': 'orders.events',
        'X-Async-Protocol': 'kafka',
        'X-Correlation-Id': 'kafka-corr-8821-ord',
        'X-Async-Partition': '0',
        'X-Async-Offset': '1042'
      },
      body: JSON.stringify(
        {
          orderId: 'ORD-KAFKA-8821',
          customerId: 'CUST-9902',
          event: 'ORDER_CREATED',
          timestamp: 1731165600000,
          items: [
            {
              sku: 'SKU-PRO-01',
              name: 'Mechanical Keyboard',
              price: 149.99,
              quantity: 1
            }
          ]
        },
        null,
        2
      ),
      loggedDate: Date.now() - 18000
    },
    response: {
      status: 204,
      headers: {
        'X-Async-Ack': 'ack',
        'X-Async-Protocol': 'kafka',
        'X-Async-Partition': '0'
      },
      body: ''
    },
    wasMatched: true,
    timing: { serveTimeMillis: 3, totalTimeMillis: 5 },
    stubMapping: {
      id: 'demo-stub-async-kafka-orders',
      name: 'Kafka Events Sink: orders.events'
    }
  },
  {
    id: 'req-demo-async-amqp',
    request: {
      url: '/__async/amqp/payments.requests',
      absoluteUrl: 'http://localhost:8080/__async/amqp/payments.requests',
      method: 'CONSUME',
      headers: {
        'Content-Type': 'application/json',
        'x-currency': 'EUR',
        'X-Async-Channel': 'payments.requests',
        'X-Async-Protocol': 'amqp',
        'X-Async-Correlation-Id': 'amqp-corr-9921-pay',
        'X-Async-Reply-To': 'payments.replies'
      },
      body: JSON.stringify(
        {
          payment: {
            paymentId: 'PAY-EUR-9921',
            amount: 1500.0,
            currency: 'EUR',
            debtorIban: 'SE45500000000583920182',
            creditorIban: 'DE89370400440532013000'
          },
          polyglotPayload:
            '<paymentAuthorization xmlns="http://bank.example/sepa"><mandateId>MND-88219</mandateId><scheme>CORE</scheme><signDate>2026-11-09</signDate></paymentAuthorization>'
        },
        null,
        2
      ),
      loggedDate: Date.now() - 48000
    },
    response: {
      status: 200,
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'X-Async-Correlation-Id': 'amqp-corr-9921-pay',
        'X-Async-Reply-Channel': 'payments.replies',
        'X-Processed-By': 'wiremock-async-engine'
      },
      body: JSON.stringify(
        {
          transactionId: 'TX-AMQP-992144',
          status: 'APPROVED',
          amount: 1500.0,
          currency: 'EUR',
          clearingReference: 'CLR-SEPA-9921',
          auditReceipt:
            '<receipt xmlns="http://bank.example/audit"><status>SETTLED</status><clearedTimestamp>2026-11-09T14:30:00Z</clearedTimestamp><ledgerRef>LEDG-AMQP-009</ledgerRef></receipt>'
        },
        null,
        2
      )
    },
    wasMatched: true,
    timing: { serveTimeMillis: 85, totalTimeMillis: 91 },
    stubMapping: {
      id: 'demo-stub-async-amqp-payments',
      name: 'AMQP 1.0: payments.requests (Request-Reply with Polyglot XML)'
    }
  },
  {
    id: 'req-demo-async-proxy-error',
    request: {
      url: '/__async/amqp/orders.faulty',
      absoluteUrl: 'http://localhost:8080/__async/amqp/orders.faulty',
      method: 'CONSUME',
      headers: {
        'Content-Type': 'application/json',
        'X-Async-Channel': 'orders.faulty',
        'X-Async-Protocol': 'amqp'
      },
      body: JSON.stringify(
        {
          orderId: 'ORD-FAULTY-99',
          action: 'PROCESS_IMMEDIATE'
        },
        null,
        2
      ),
      loggedDate: Date.now() - 75000
    },
    response: {
      status: 502,
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'X-Async-Error': 'Destination queue [orders.faulty] missing on remote broker',
        'X-Async-DLQ-Target': 'orders.faulty.error'
      },
      body: JSON.stringify(
        {
          error: 'ASYNC_PROXY_ERROR',
          status: 502,
          reason: 'QueueNotFoundException',
          details: 'Remote broker rejected link attachment: destination queue [orders.faulty] missing on remote broker'
        },
        null,
        2
      )
    },
    wasMatched: false,
    timing: { serveTimeMillis: 12, totalTimeMillis: 15 }
  },
  {
    id: 'req-demo-casual-queue-01',
    request: {
      url: '/__casual/queues/billing.q/enqueue',
      absoluteUrl: 'http://localhost:8080/__casual/queues/billing.q/enqueue',
      method: 'ENQUEUE',
      headers: {
        'Content-Type': 'application/json',
        'X-WireMock-Protocol': 'casual',
        'X-Casual-Queue': 'billing.q',
        'X-Casual-Operation': 'enqueue'
      },
      body: JSON.stringify(
        {
          invoiceId: 'INV-2026-881',
          amount: 7420.0,
          currency: 'SEK',
          customer: 'Nordic Solutions AB'
        },
        null,
        2
      ),
      loggedDate: Date.now() - 110000
    },
    response: {
      status: 200,
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'X-WireMock-Protocol': 'casual',
        'X-Casual-Queue': 'billing.q',
        'X-Casual-Operation': 'enqueue'
      },
      body: JSON.stringify(
        {
          id: 'msg-casual-queue-881',
          status: 'ENQUEUED',
          queue: 'billing.q',
          enqueuedAt: '2026-11-09T14:40:00Z'
        },
        null,
        2
      )
    },
    wasMatched: true,
    timing: { serveTimeMillis: 6, totalTimeMillis: 8 }
  },
  {
    id: 'req-demo-casual-queue-02',
    request: {
      url: '/__casual/queues/billing.q/dequeue',
      absoluteUrl: 'http://localhost:8080/__casual/queues/billing.q/dequeue',
      method: 'DEQUEUE',
      headers: {
        'Content-Type': 'application/json',
        'X-WireMock-Protocol': 'casual',
        'X-Casual-Queue': 'billing.q',
        'X-Casual-Operation': 'dequeue'
      },
      body: '',
      loggedDate: Date.now() - 135000
    },
    response: {
      status: 200,
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'X-WireMock-Protocol': 'casual',
        'X-Casual-Queue': 'billing.q',
        'X-Casual-Operation': 'dequeue'
      },
      body: JSON.stringify(
        [
          {
            id: 'msg-casual-queue-881',
            payload: {
              invoiceId: 'INV-2026-881',
              amount: 7420.0,
              status: 'PENDING'
            }
          }
        ],
        null,
        2
      )
    },
    wasMatched: true,
    timing: { serveTimeMillis: 9, totalTimeMillis: 12 }
  }
];

const SEED_ASYNC_CONNECTIONS = [
  {
    id: 'embedded-artemis',
    protocol: 'amqp',
    name: 'ActiveMQ Artemis (Embedded)',
    endpoint: '127.0.0.1:61616',
    status: 'HEALTHY',
    properties: {
      'amqp.protocol': '1.0',
      'client.id': 'wm-artemis-client',
      'amqp.idleTimeout': '30000'
    }
  },
  {
    id: 'prod-cluster-kafka',
    protocol: 'kafka',
    name: 'Kafka Event Backbone',
    endpoint: 'localhost:9092',
    status: 'HEALTHY',
    properties: {
      'client.id': 'wm-kafka-sink',
      'compression.type': 'snappy'
    }
  },
  {
    id: 'mainframe-casual',
    protocol: 'casual',
    name: 'Casual Domain Gateway',
    endpoint: '127.0.0.1:7772',
    status: 'HEALTHY',
    properties: {
      'domain.id': 'finance-xatmi'
    }
  },
  {
    id: 'corp-ibmmq',
    protocol: 'ibmmq',
    name: 'IBM MQ Queue Manager (QM1)',
    endpoint: '127.0.0.1:1414',
    status: 'HEALTHY',
    properties: {
      queueManager: 'QM1',
      channel: 'DEV.APP.SVRCONN',
      ccsid: '1208',
      username: 'app',
      password: '${IBM_MQ_PASSWORD}'
    }
  }
];

const SEED_ASYNC_DESTINATIONS = [
  { id: 'dest-kafka-orders', name: 'orders.events', protocol: 'kafka', type: 'topic', brokerId: 'prod-cluster-kafka', messageCount: 3 },
  { id: 'dest-amqp-payments', name: 'payments.requests', protocol: 'amqp', type: 'queue', brokerId: 'enterprise-broker', messageCount: 2 },
  { id: 'dest-ibmmq-dev', name: 'DEV.QUEUE.1', protocol: 'ibmmq', type: 'queue', brokerId: 'corp-ibmmq', messageCount: 1 },
  { id: 'dest-casual-billing', name: 'billing.forward', protocol: 'casual', type: 'queue', brokerId: 'mainframe-casual', messageCount: 2 },
  { id: 'dest-inmem-audit', name: 'internal.audit', protocol: 'in-memory', type: 'queue', brokerId: 'local', messageCount: 1 }
];

const SEED_ASYNC_MESSAGES = {
  'kafka/orders.events': [
    {
      id: 'msg-k-101',
      protocol: 'kafka',
      destination: 'orders.events',
      key: 'ORD-8821',
      partition: 0,
      offset: 1042,
      timestamp: Date.now() - 120000,
      correlationId: 'corr-ord-8821',
      replyTo: null,
      headers: {
        'x-tenant-id': 'emea-retail',
        'traceparent': '00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01'
      },
      payload: JSON.stringify({
        orderId: 'ORD-8821',
        customer: 'Acme Corp',
        legacyXmlInvoice: '<?xml version="1.0" encoding="UTF-8"?>\n<invoice id="INV-9921">\n  <total currency="USD">1250.00</total>\n  <item sku="SKU-441" qty="2">Widget</item>\n</invoice>'
      }, null, 2),
      size: 260
    },
    {
      id: 'msg-k-102',
      protocol: 'kafka',
      destination: 'orders.events',
      key: 'ORD-8822',
      partition: 1,
      offset: 412,
      timestamp: Date.now() - 60000,
      correlationId: 'corr-ord-8822',
      replyTo: null,
      headers: {
        'x-tenant-id': 'apac-shipping'
      },
      payload: JSON.stringify({
        orderId: 'ORD-8822',
        customer: 'Global Logistics',
        items: [
          { sku: 'ITEM-99', quantity: 5 }
        ],
        status: 'SHIPPED'
      }, null, 2),
      size: 145
    },
    {
      id: 'msg-k-103',
      protocol: 'kafka',
      destination: 'orders.events',
      key: 'ORD-8823',
      partition: 0,
      offset: 1043,
      timestamp: Date.now() - 10000,
      correlationId: 'corr-ord-8823',
      replyTo: null,
      headers: {
        'x-tenant-id': 'emea-retail'
      },
      payload: JSON.stringify({
        orderId: 'ORD-8823',
        customer: 'Starlight Retail',
        status: 'PENDING'
      }, null, 2),
      size: 98
    }
  ],
  'amqp/payments.requests': [
    {
      id: 'msg-amqp-201',
      protocol: 'amqp',
      destination: 'payments.requests',
      key: 'PAY-EUR-9921',
      partition: null,
      offset: 0,
      timestamp: Date.now() - 45000,
      correlationId: 'corr-pay-9921',
      replyTo: 'payments.replies',
      headers: {
        'content-type': 'application/json',
        'x-amqp-delivery-mode': '2'
      },
      payload: JSON.stringify({
        paymentId: 'PAY-EUR-9921',
        amount: 850.5,
        currency: 'EUR',
        method: 'SEPA'
      }, null, 2),
      size: 112
    },
    {
      id: 'msg-amqp-202',
      protocol: 'amqp',
      destination: 'payments.requests',
      key: 'PAY-USD-9922',
      partition: null,
      offset: 1,
      timestamp: Date.now() - 20000,
      correlationId: 'corr-pay-9922',
      replyTo: 'payments.replies',
      headers: {
        'content-type': 'application/json'
      },
      payload: JSON.stringify({
        paymentId: 'PAY-USD-9922',
        amount: 2200.0,
        currency: 'USD',
        method: 'CARD'
      }, null, 2),
      size: 114
    }
  ],
  'casual/billing.forward': [
    {
      id: 'msg-casual-301',
      protocol: 'casual',
      destination: 'billing.forward',
      key: 'INV-2026-881',
      partition: null,
      offset: 0,
      timestamp: Date.now() - 80000,
      correlationId: 'corr-casual-billing-881',
      replyTo: null,
      headers: {
        'X-Casual-Service': 'billingService',
        'X-Casual-Buffer-Type': 'JSON'
      },
      payload: JSON.stringify({
        invoiceId: 'INV-2026-881',
        amount: 7420.0,
        status: 'PENDING'
      }, null, 2),
      size: 88
    },
    {
      id: 'msg-casual-302',
      protocol: 'casual',
      destination: 'billing.forward',
      key: 'INV-2026-882',
      partition: null,
      offset: 1,
      timestamp: Date.now() - 30000,
      correlationId: 'corr-casual-billing-882',
      replyTo: null,
      headers: {
        'X-Casual-Service': 'billingService',
        'X-Casual-Buffer-Type': 'JSON'
      },
      payload: JSON.stringify({
        invoiceId: 'INV-2026-882',
        amount: 1950.0,
        status: 'PROCESSED'
      }, null, 2),
      size: 90
    }
  ],
  'ibmmq/DEV.QUEUE.1': [
    {
      id: 'ID:414d5120514d3120202020202020202081b45a6701004020',
      protocol: 'ibmmq',
      destination: 'DEV.QUEUE.1',
      key: 'MQ-TX-401',
      partition: null,
      offset: 0,
      timestamp: Date.now() - 45000,
      correlationId: 'corr-ibmmq-401',
      replyTo: 'DEV.QUEUE.2',
      headers: {
        JMS_IBM_Format: 'MQSTR   ',
        x_tenant_id: 'nordic-clearing'
      },
      payload: JSON.stringify({
        settlementId: 'SET-2026-401',
        queueManager: 'QM1',
        currency: 'SEK',
        amount: 12500.0
      }, null, 2),
      size: 112
    }
  ],
  'in-memory/internal.audit': [
    {
      id: 'msg-inmem-401',
      protocol: 'in-memory',
      destination: 'internal.audit',
      key: 'AUD-001',
      partition: null,
      offset: 0,
      timestamp: Date.now() - 15000,
      correlationId: 'corr-aud-001',
      replyTo: null,
      headers: {},
      payload: JSON.stringify({
        event: 'USER_LOGIN',
        user: 'admin',
        ip: '127.0.0.1'
      }, null, 2),
      size: 72
    }
  ]
};

class InMemoryDemoBackend {
  constructor() {
    this.stubs = JSON.parse(JSON.stringify(SEED_STUBS));
    this.disabledStubs = JSON.parse(JSON.stringify(SEED_DISABLED_STUBS));
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

      return responseJson(200, {
        status: replyStatus >= 200 && replyStatus < 300 ? 0 : -1,
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
        version: '1.14.0',
        branch: 'feat/async-ibmmq-support',
        commit: 'c477cd0'
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
