/**
 * Demo Showcase seed fixture dataset: Scenarios, Stateful AST, NetEm, Casual & Async fixtures.
 */
export const SEED_SCENARIOS = [
  {
    id: 'OrderLifecycle',
    name: 'OrderLifecycle',
    state: 'PROCESSING',
    possibleStates: ['Started', 'PROCESSING', 'COMPLETED']
  }
];

export const SEED_STATEFUL_MODELS = [
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

export const SEED_NETEM_PROXIES = [
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

export const SEED_CASUAL_SERVICES = [
  { name: 'echoService', bufferType: 'JSON' },
  { name: 'creditScoreInquiry', bufferType: 'JSON' },
  { name: 'settlePayment', bufferType: 'FLAT' }
];

export const SEED_ASYNC_CONNECTIONS = [
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

export const SEED_ASYNC_DESTINATIONS = [
  { id: 'dest-kafka-orders', name: 'orders.events', protocol: 'kafka', type: 'topic', brokerId: 'prod-cluster-kafka', messageCount: 3 },
  { id: 'dest-amqp-payments', name: 'payments.requests', protocol: 'amqp', type: 'queue', brokerId: 'enterprise-broker', messageCount: 2 },
  { id: 'dest-ibmmq-dev', name: 'DEV.QUEUE.1', protocol: 'ibmmq', type: 'queue', brokerId: 'corp-ibmmq', messageCount: 1 },
  { id: 'dest-casual-billing', name: 'billing.forward', protocol: 'casual', type: 'queue', brokerId: 'mainframe-casual', messageCount: 2 },
  { id: 'dest-inmem-audit', name: 'internal.audit', protocol: 'in-memory', type: 'queue', brokerId: 'local', messageCount: 1 }
];

export const SEED_ASYNC_MESSAGES = {
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
