/**
 * Demo Showcase seed fixture dataset: Preloaded request journal ServeEvents.
 */
import { SEED_STUBS } from './demo-fixtures-stubs.js';

export const SEED_REQUESTS = [
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
