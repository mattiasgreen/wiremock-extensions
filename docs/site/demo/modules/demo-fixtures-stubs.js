/**
 * Demo Showcase seed fixture dataset: Stub mappings (active & parked tiers).
 */
export const SEED_STUBS = [
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

export const SEED_DISABLED_STUBS = [
  {
    id: 'demo-stub-disabled-legacy',
    name: 'Legacy Payment Gateway [DEPRECATED]',
    priority: 100,
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
    priority: 100,
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
