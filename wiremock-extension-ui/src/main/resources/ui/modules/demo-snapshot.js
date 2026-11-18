// Auto-generated from live WireMockServer by DemoSnapshotGenerator — do not hand-edit.
export const DEMO_SNAPSHOT = {
  "mappings" : [
    {
      "id" : "10000000-0000-4000-8000-000000000010",
      "name" : "AMQP Queue: orders.inbound",
      "request" : {
        "url" : "/__async/amqp/orders.inbound",
        "method" : "POST"
      },
      "response" : {
        "status" : 200,
        "body" : "{\"status\":\"ACKNOWLEDGED\",\"queue\":\"orders.inbound\"}",
        "headers" : {
          "Content-Type" : "application/json; charset=utf-8"
        }
      },
      "uuid" : "10000000-0000-4000-8000-000000000010",
      "priority" : 5,
      "metadata" : {
        "project" : "Async Messaging",
        "protocol" : "amqp",
        "tags" : [
          "async",
          "amqp",
          "orders"
        ],
        "async" : {
          "mode" : "mock",
          "channel" : "orders.inbound",
          "ackPolicy" : "ack",
          "protocol" : "amqp"
        },
        "channel" : "orders.inbound"
      }
    },
    {
      "id" : "10000000-0000-4000-8000-000000000009",
      "name" : "Kafka Events Sink: orders.events",
      "request" : {
        "url" : "/__async/kafka/orders.events",
        "method" : "POST"
      },
      "response" : {
        "status" : 204,
        "headers" : {
          "X-Async-Ack" : "ack",
          "X-Async-Protocol" : "kafka"
        }
      },
      "uuid" : "10000000-0000-4000-8000-000000000009",
      "priority" : 5,
      "metadata" : {
        "project" : "Async Messaging",
        "protocol" : "kafka",
        "tags" : [
          "async",
          "kafka",
          "events"
        ],
        "async" : {
          "mode" : "mock",
          "channel" : "orders.events",
          "ackPolicy" : "ack",
          "protocol" : "kafka"
        },
        "channel" : "orders.events"
      }
    },
    {
      "id" : "10000000-0000-4000-8000-000000000008",
      "name" : "Casual XATMI: echoService",
      "request" : {
        "url" : "/__casual/services/echoService",
        "method" : "POST"
      },
      "response" : {
        "status" : 200,
        "body" : "{\"service\":\"echoService\",\"status\":\"OK\",\"xatmiReturnCode\":0,\"message\":\"Casual service loopback operational\"}",
        "headers" : {
          "Content-Type" : "application/json; charset=utf-8",
          "X-Casual-Service" : "echoService",
          "X-Casual-Error-State" : "OK"
        }
      },
      "uuid" : "10000000-0000-4000-8000-000000000008",
      "priority" : 5,
      "metadata" : {
        "project" : "Casual Gateway",
        "service" : "echoService",
        "protocol" : "casual",
        "tags" : [
          "casual",
          "xatmi",
          "echo"
        ],
        "exampleRequest" : {
          "headers" : {
            "Content-Type" : "application/json",
            "x-casual-buffer-type" : "JSON",
            "x-casual-service" : "echoService"
          },
          "path" : "/__casual/services/echoService",
          "body" : {
            "ping" : "pong"
          }
        }
      }
    },
    {
      "id" : "10000000-0000-4000-8000-000000000004",
      "name" : "Order Status (SHIPPED -> DELIVERED)",
      "request" : {
        "url" : "/api/v1/orders/ORD-9942/status",
        "method" : "GET"
      },
      "response" : {
        "status" : 200,
        "body" : "{\"orderId\":\"ORD-9942\",\"status\":\"DELIVERED\",\"signedBy\":\"A. Lindqvist\"}",
        "headers" : {
          "Content-Type" : "application/json; charset=utf-8"
        }
      },
      "uuid" : "10000000-0000-4000-8000-000000000004",
      "priority" : 5,
      "scenarioName" : "OrderFulfillment",
      "requiredScenarioState" : "SHIPPED",
      "newScenarioState" : "DELIVERED",
      "metadata" : {
        "tags" : [
          "orders",
          "fsm",
          "scenario"
        ],
        "project" : "orders-api"
      }
    },
    {
      "id" : "10000000-0000-4000-8000-000000000003",
      "name" : "Order Status (Started -> SHIPPED)",
      "request" : {
        "url" : "/api/v1/orders/ORD-9942/status",
        "method" : "GET"
      },
      "response" : {
        "status" : 200,
        "body" : "{\"orderId\":\"ORD-9942\",\"status\":\"SHIPPED\",\"carrier\":\"NordicExpress\"}",
        "headers" : {
          "Content-Type" : "application/json; charset=utf-8"
        }
      },
      "uuid" : "10000000-0000-4000-8000-000000000003",
      "priority" : 5,
      "scenarioName" : "OrderFulfillment",
      "requiredScenarioState" : "Started",
      "newScenarioState" : "SHIPPED",
      "metadata" : {
        "tags" : [
          "orders",
          "fsm",
          "scenario"
        ],
        "project" : "orders-api"
      }
    },
    {
      "id" : "10000000-0000-4000-8000-000000000002",
      "name" : "Create Order (Nested Polyglot Payload)",
      "request" : {
        "url" : "/api/v1/orders",
        "method" : "POST",
        "headers" : {
          "Content-Type" : {
            "contains" : "application/json"
          }
        },
        "bodyPatterns" : [
          {
            "matchesJsonPath" : "$.orderId"
          }
        ]
      },
      "response" : {
        "status" : 201,
        "body" : "{\n  \"totalAmount\" : 1250.0,\n  \"orderId\" : \"ORD-9942\",\n  \"status\" : \"CREATED\",\n  \"currency\" : \"EUR\",\n  \"complianceAudit\" : \"{\\\"auditor\\\":\\\"order-fsm\\\",\\\"score\\\":99,\\\"embeddedXml\\\":\\\"<auditReport xmlns=\\\\\\\"http://example.com/orders\\\\\\\"><orderId>ORD-9942</orderId><status>VALIDATED</status><checksum>a9f4c82b</checksum></auditReport>\\\"}\",\n  \"encodedManifest\" : \"eyJtYW5pZmVzdFZlcnNpb24iOjEsIm9yZGVySWQiOiJPUkQtOTk0MiIsInhtbFN1bW1hcnkiOiI8YXVkaXRSZXBvcnQgeG1sbnM9XCJodHRwOi8vZXhhbXBsZS5jb20vb3JkZXJzXCI+PG9yZGVySWQ+T1JELTk5NDI8L29yZGVySWQ+PHN0YXR1cz5WQUxJREFURUQ8L3N0YXR1cz48Y2hlY2tzdW0+YTlmNGM4MmI8L2NoZWNrc3VtPjwvYXVkaXRSZXBvcnQ+In0=\"\n}",
        "headers" : {
          "Content-Type" : "application/json; charset=utf-8",
          "Location" : "/api/v1/orders/ORD-9942"
        }
      },
      "uuid" : "10000000-0000-4000-8000-000000000002",
      "priority" : 5,
      "metadata" : {
        "tags" : [
          "orders",
          "polyglot",
          "nested-payload"
        ],
        "project" : "orders-api"
      }
    },
    {
      "id" : "10000000-0000-4000-8000-000000000001",
      "name" : "List Users",
      "request" : {
        "urlPath" : "/api/v1/users",
        "method" : "GET"
      },
      "response" : {
        "status" : 200,
        "body" : "[{\"role\":\"ADMIN\",\"name\":\"Ada Lovelace\",\"id\":\"usr-1\"},{\"role\":\"SECURITY\",\"name\":\"Alan Turing\",\"id\":\"usr-2\"},{\"role\":\"DEVELOPER\",\"name\":\"Grace Hopper\",\"id\":\"usr-3\"}]",
        "headers" : {
          "Content-Type" : "application/json; charset=utf-8"
        }
      },
      "uuid" : "10000000-0000-4000-8000-000000000001",
      "priority" : 5,
      "metadata" : {
        "tags" : [
          "users",
          "rest"
        ],
        "project" : "identity-api"
      }
    },
    {
      "id" : "10000000-0000-4000-8000-000000000013",
      "name" : "AMQP Queue: Payments Bridge",
      "request" : {
        "urlPattern" : "/__async/amqp/payments\\..*",
        "method" : "ANY"
      },
      "response" : {
        "status" : 200,
        "proxyBaseUrl" : "async://upstream-amqp"
      },
      "uuid" : "10000000-0000-4000-8000-000000000013",
      "priority" : 10,
      "metadata" : {
        "project" : "Async Messaging",
        "protocol" : "amqp",
        "mode" : "proxy",
        "async" : {
          "broker" : "downstream-artemis",
          "proxyChannel" : "upstream.payments.$1",
          "mode" : "proxy",
          "protocol" : "amqp",
          "channelPattern" : "payments\\.(.*)",
          "proxyBroker" : "upstream-amqp"
        },
        "tags" : [
          "async",
          "amqp",
          "bridge"
        ]
      }
    },
    {
      "id" : "10000000-0000-4000-8000-000000000012",
      "name" : "Kafka Topic: Events Bridge",
      "request" : {
        "urlPattern" : "/__async/kafka/events\\..*",
        "method" : "ANY"
      },
      "response" : {
        "status" : 200,
        "proxyBaseUrl" : "async://upstream-kafka"
      },
      "uuid" : "10000000-0000-4000-8000-000000000012",
      "priority" : 10,
      "metadata" : {
        "project" : "Async Messaging",
        "protocol" : "kafka",
        "mode" : "proxy",
        "async" : {
          "broker" : "downstream-kafka",
          "proxyChannel" : "upstream.events.$1",
          "mode" : "proxy",
          "protocol" : "kafka",
          "channelPattern" : "events\\.(.*)",
          "proxyBroker" : "upstream-kafka"
        },
        "tags" : [
          "async",
          "kafka",
          "bridge"
        ]
      }
    },
    {
      "id" : "10000000-0000-4000-8000-000000000011",
      "name" : "Casual XATMI: inventoryCheck Proxy",
      "request" : {
        "urlPattern" : "/__casual/services/inventory.*",
        "method" : "POST"
      },
      "response" : {
        "status" : 200,
        "proxyBaseUrl" : "casual://127.0.0.1:7773"
      },
      "uuid" : "10000000-0000-4000-8000-000000000011",
      "priority" : 10,
      "metadata" : {
        "project" : "Casual Gateway",
        "targetBaseUrl" : "casual://127.0.0.1:7773",
        "protocol" : "casual",
        "mode" : "proxy",
        "tags" : [
          "casual",
          "proxy",
          "xatmi"
        ]
      }
    },
    {
      "id" : "10000000-0000-4000-8000-000000000005",
      "name" : "ERP System Live Proxy",
      "request" : {
        "urlPattern" : "/erp/api/.*",
        "method" : "ANY"
      },
      "response" : {
        "status" : 200,
        "proxyBaseUrl" : "http://127.0.0.1:9000"
      },
      "uuid" : "10000000-0000-4000-8000-000000000005",
      "priority" : 10,
      "metadata" : {
        "project" : "erp-system",
        "tags" : [
          "proxy",
          "erp"
        ],
        "mode" : "proxy"
      }
    },
    {
      "id" : "10000000-0000-4000-8000-000000000006",
      "name" : "ERP Offline Guard",
      "request" : {
        "urlPattern" : "/erp/api/.*",
        "method" : "ANY"
      },
      "response" : {
        "status" : 501,
        "body" : "{\"error\":\"Offline guard active for erp-system\"}",
        "headers" : {
          "Content-Type" : "application/json; charset=utf-8"
        }
      },
      "uuid" : "10000000-0000-4000-8000-000000000006",
      "priority" : 50,
      "metadata" : {
        "role" : "offline-guard",
        "project" : "erp-system",
        "tags" : [
          "offline-guard",
          "erp"
        ]
      }
    },
    {
      "id" : "10000000-0000-4000-8000-000000000007",
      "name" : "Delete User [PARKED]",
      "request" : {
        "urlPattern" : "/api/v1/users/.*",
        "method" : "DELETE"
      },
      "response" : {
        "status" : 204
      },
      "uuid" : "10000000-0000-4000-8000-000000000007",
      "priority" : 100,
      "metadata" : {
        "tags" : [
          "users",
          "parked"
        ],
        "project" : "identity-api"
      }
    }
  ],
  "requests" : [
    {
      "id" : "616aff11-52ad-440b-b451-c5efe33a39f8",
      "request" : {
        "url" : "/__async/in-memory/orders.notifications",
        "absoluteUrl" : "http://localhost:8080/__async/in-memory/orders.notifications",
        "method" : "POST",
        "headers" : {
          "Content-Type" : "application/json",
          "X-WireMock-Async-Protocol" : "in-memory",
          "X-Async-Destination" : "orders.notifications",
          "X-Async-Key" : "ORD-9942",
          "X-Async-Correlation-Id" : "corr-async-9942"
        },
        "browserProxyRequest" : false,
        "loggedDate" : 1794902298265,
        "bodyAsBase64" : "eyJvcmRlcklkIjoiT1JELTk5NDIiLCJldmVudCI6Ik9SREVSX1NISVBQRUQifQ==",
        "body" : "{\"orderId\":\"ORD-9942\",\"event\":\"ORDER_SHIPPED\"}",
        "scheme" : "http",
        "host" : "localhost",
        "port" : 8080,
        "loggedDateString" : "2026-11-17T07:58:18.265Z",
        "queryParams" : { },
        "formParams" : { }
      },
      "responseDefinition" : {
        "status" : 200,
        "body" : "{\"status\":\"PUBLISHED\",\"destination\":\"orders.notifications\"}",
        "headers" : {
          "Content-Type" : "application/json",
          "X-WireMock-Async-Protocol" : "in-memory",
          "X-Async-Destination" : "orders.notifications"
        }
      },
      "response" : {
        "status" : 200,
        "headers" : {
          "Content-Type" : "application/json",
          "X-WireMock-Async-Protocol" : "in-memory",
          "X-Async-Destination" : "orders.notifications"
        },
        "bodyAsBase64" : "eyJzdGF0dXMiOiJQVUJMSVNIRUQiLCJkZXN0aW5hdGlvbiI6Im9yZGVycy5ub3RpZmljYXRpb25zIn0=",
        "body" : "{\"status\":\"PUBLISHED\",\"destination\":\"orders.notifications\"}"
      },
      "wasMatched" : true,
      "timing" : {
        "addedDelay" : 0,
        "processTime" : 0,
        "responseSendTime" : 0,
        "serveTime" : 0,
        "totalTime" : 0
      },
      "subEvents" : [ ],
      "sourceIp" : "",
      "sourceHost" : ""
    },
    {
      "id" : "8dc9c8b2-1774-4ca5-b67c-24276d62a692",
      "request" : {
        "url" : "/__async/amqp/payments.settled",
        "absoluteUrl" : "http://localhost:8080/__async/amqp/payments.settled",
        "method" : "POST",
        "headers" : {
          "Content-Type" : "application/json",
          "X-WireMock-Async-Protocol" : "amqp",
          "X-Async-Destination" : "payments.settled",
          "X-Async-Correlation-Id" : "corr-amqp-bridge-1",
          "x-source-broker" : "downstream-artemis",
          "X-Forwarded-For" : "10.42.8.14"
        },
        "browserProxyRequest" : false,
        "loggedDate" : 1794902298118,
        "bodyAsBase64" : "eyJwYXltZW50SWQiOiJQQVktNTAxIiwiYW1vdW50IjoxMjUwLjAwLCJjdXJyZW5jeSI6IkVVUiJ9",
        "body" : "{\"paymentId\":\"PAY-501\",\"amount\":1250.00,\"currency\":\"EUR\"}",
        "scheme" : "http",
        "host" : "localhost",
        "port" : 8080,
        "loggedDateString" : "2026-11-17T07:58:18.118Z",
        "queryParams" : { },
        "formParams" : { }
      },
      "responseDefinition" : {
        "status" : 200,
        "body" : "{\"status\":\"BRIDGED\",\"destination\":\"upstream.payments.settled\"}",
        "headers" : {
          "Content-Type" : "application/json",
          "X-WireMock-Async-Protocol" : "amqp",
          "X-Async-Destination" : "payments.settled",
          "X-Async-Bridged-To" : "upstream-amqp/upstream.payments.settled"
        },
        "proxyBaseUrl" : "upstream-amqp/upstream.payments.settled"
      },
      "response" : {
        "status" : 200,
        "headers" : {
          "Content-Type" : "application/json",
          "X-WireMock-Async-Protocol" : "amqp",
          "X-Async-Destination" : "payments.settled",
          "X-Async-Bridged-To" : "upstream-amqp/upstream.payments.settled"
        },
        "bodyAsBase64" : "eyJzdGF0dXMiOiJCUklER0VEIiwiZGVzdGluYXRpb24iOiJ1cHN0cmVhbS5wYXltZW50cy5zZXR0bGVkIn0=",
        "body" : "{\"status\":\"BRIDGED\",\"destination\":\"upstream.payments.settled\"}"
      },
      "wasMatched" : true,
      "timing" : {
        "addedDelay" : 0,
        "processTime" : 0,
        "responseSendTime" : 0,
        "serveTime" : 0,
        "totalTime" : 0
      },
      "subEvents" : [ ],
      "stubMapping" : {
        "id" : "10000000-0000-4000-8000-000000000013",
        "name" : "AMQP Queue: Payments Bridge",
        "request" : {
          "urlPattern" : "/__async/amqp/payments\\..*",
          "method" : "ANY"
        },
        "response" : {
          "status" : 200,
          "proxyBaseUrl" : "async://upstream-amqp"
        },
        "uuid" : "10000000-0000-4000-8000-000000000013",
        "priority" : 10,
        "metadata" : {
          "project" : "Async Messaging",
          "protocol" : "amqp",
          "mode" : "proxy",
          "async" : {
            "broker" : "downstream-artemis",
            "proxyChannel" : "upstream.payments.$1",
            "mode" : "proxy",
            "protocol" : "amqp",
            "channelPattern" : "payments\\.(.*)",
            "proxyBroker" : "upstream-amqp"
          },
          "tags" : [
            "async",
            "amqp",
            "bridge"
          ]
        }
      },
      "sourceIp" : "10.42.8.14",
      "sourceHost" : "payment-gw.internal"
    },
    {
      "id" : "78b2dffe-2504-40a9-a733-c281474a804f",
      "request" : {
        "url" : "/__async/amqp/orders.inbound",
        "absoluteUrl" : "http://localhost:8080/__async/amqp/orders.inbound",
        "method" : "POST",
        "headers" : {
          "Content-Type" : [
            "application/json",
            "application/json"
          ],
          "X-WireMock-Async-Protocol" : "amqp",
          "X-Async-Destination" : "orders.inbound",
          "X-Async-Correlation-Id" : "corr-amqp-stub-1",
          "X-Async-Reply-To" : "orders.replies",
          "X-Forwarded-For" : "10.42.8.13"
        },
        "browserProxyRequest" : false,
        "loggedDate" : 1794902298117,
        "bodyAsBase64" : "eyJvcmRlcklkIjoiT1JELTk5NDIiLCJhY3Rpb24iOiJSRVNFUlZFX1NUT0NLIn0=",
        "body" : "{\"orderId\":\"ORD-9942\",\"action\":\"RESERVE_STOCK\"}",
        "scheme" : "http",
        "host" : "localhost",
        "port" : 8080,
        "loggedDateString" : "2026-11-17T07:58:18.117Z",
        "queryParams" : { },
        "formParams" : { }
      },
      "responseDefinition" : {
        "status" : 200,
        "body" : "{\"status\":\"ACKNOWLEDGED\",\"queue\":\"orders.inbound\"}",
        "headers" : {
          "Content-Type" : "application/json",
          "X-WireMock-Async-Protocol" : "amqp",
          "X-Async-Destination" : "orders.inbound"
        }
      },
      "response" : {
        "status" : 200,
        "headers" : {
          "Content-Type" : "application/json",
          "X-WireMock-Async-Protocol" : "amqp",
          "X-Async-Destination" : "orders.inbound"
        },
        "bodyAsBase64" : "eyJzdGF0dXMiOiJBQ0tOT1dMRURHRUQiLCJxdWV1ZSI6Im9yZGVycy5pbmJvdW5kIn0=",
        "body" : "{\"status\":\"ACKNOWLEDGED\",\"queue\":\"orders.inbound\"}"
      },
      "wasMatched" : true,
      "timing" : {
        "addedDelay" : 0,
        "processTime" : 0,
        "responseSendTime" : 0,
        "serveTime" : 0,
        "totalTime" : 0
      },
      "subEvents" : [ ],
      "stubMapping" : {
        "id" : "10000000-0000-4000-8000-000000000010",
        "name" : "AMQP Queue: orders.inbound",
        "request" : {
          "url" : "/__async/amqp/orders.inbound",
          "method" : "POST"
        },
        "response" : {
          "status" : 200,
          "body" : "{\"status\":\"ACKNOWLEDGED\",\"queue\":\"orders.inbound\"}",
          "headers" : {
            "Content-Type" : "application/json; charset=utf-8"
          }
        },
        "uuid" : "10000000-0000-4000-8000-000000000010",
        "priority" : 5,
        "metadata" : {
          "project" : "Async Messaging",
          "protocol" : "amqp",
          "tags" : [
            "async",
            "amqp",
            "orders"
          ],
          "async" : {
            "mode" : "mock",
            "channel" : "orders.inbound",
            "ackPolicy" : "ack",
            "protocol" : "amqp"
          },
          "channel" : "orders.inbound"
        }
      },
      "sourceIp" : "10.42.8.13",
      "sourceHost" : "checkout-svc.internal"
    },
    {
      "id" : "0ecc7fb3-56bf-420e-8050-96b3953721c3",
      "request" : {
        "url" : "/__async/kafka/events.audit",
        "absoluteUrl" : "http://localhost:8080/__async/kafka/events.audit",
        "method" : "POST",
        "headers" : {
          "Content-Type" : "application/json",
          "X-WireMock-Async-Protocol" : "kafka",
          "X-Async-Destination" : "events.audit",
          "X-Async-Key" : "AUD-700",
          "X-Async-Correlation-Id" : "corr-kafka-bridge-1",
          "x-source-broker" : "downstream-kafka",
          "X-Forwarded-For" : "10.42.8.12"
        },
        "browserProxyRequest" : false,
        "loggedDate" : 1794902298116,
        "bodyAsBase64" : "eyJhdWRpdElkIjoiQVVELTcwMCIsImFjdGlvbiI6IkxPR0lOX1ZFUklGSUVEIn0=",
        "body" : "{\"auditId\":\"AUD-700\",\"action\":\"LOGIN_VERIFIED\"}",
        "scheme" : "http",
        "host" : "localhost",
        "port" : 8080,
        "loggedDateString" : "2026-11-17T07:58:18.116Z",
        "queryParams" : { },
        "formParams" : { }
      },
      "responseDefinition" : {
        "status" : 200,
        "body" : "{\"status\":\"BRIDGED\",\"destination\":\"upstream.events.audit\"}",
        "headers" : {
          "Content-Type" : "application/json",
          "X-WireMock-Async-Protocol" : "kafka",
          "X-Async-Destination" : "events.audit",
          "X-Async-Bridged-To" : "upstream-kafka/upstream.events.audit"
        },
        "proxyBaseUrl" : "upstream-kafka/upstream.events.audit"
      },
      "response" : {
        "status" : 200,
        "headers" : {
          "Content-Type" : "application/json",
          "X-WireMock-Async-Protocol" : "kafka",
          "X-Async-Destination" : "events.audit",
          "X-Async-Bridged-To" : "upstream-kafka/upstream.events.audit"
        },
        "bodyAsBase64" : "eyJzdGF0dXMiOiJCUklER0VEIiwiZGVzdGluYXRpb24iOiJ1cHN0cmVhbS5ldmVudHMuYXVkaXQifQ==",
        "body" : "{\"status\":\"BRIDGED\",\"destination\":\"upstream.events.audit\"}"
      },
      "wasMatched" : true,
      "timing" : {
        "addedDelay" : 0,
        "processTime" : 0,
        "responseSendTime" : 0,
        "serveTime" : 0,
        "totalTime" : 0
      },
      "subEvents" : [ ],
      "stubMapping" : {
        "id" : "10000000-0000-4000-8000-000000000012",
        "name" : "Kafka Topic: Events Bridge",
        "request" : {
          "urlPattern" : "/__async/kafka/events\\..*",
          "method" : "ANY"
        },
        "response" : {
          "status" : 200,
          "proxyBaseUrl" : "async://upstream-kafka"
        },
        "uuid" : "10000000-0000-4000-8000-000000000012",
        "priority" : 10,
        "metadata" : {
          "project" : "Async Messaging",
          "protocol" : "kafka",
          "mode" : "proxy",
          "async" : {
            "broker" : "downstream-kafka",
            "proxyChannel" : "upstream.events.$1",
            "mode" : "proxy",
            "protocol" : "kafka",
            "channelPattern" : "events\\.(.*)",
            "proxyBroker" : "upstream-kafka"
          },
          "tags" : [
            "async",
            "kafka",
            "bridge"
          ]
        }
      },
      "sourceIp" : "10.42.8.12",
      "sourceHost" : "system-b.internal"
    },
    {
      "id" : "00678ca4-7ebb-4000-bd6e-19b82e8085d5",
      "request" : {
        "url" : "/__async/kafka/orders.events",
        "absoluteUrl" : "http://localhost:8080/__async/kafka/orders.events",
        "method" : "POST",
        "headers" : {
          "Content-Type" : "application/json",
          "X-WireMock-Async-Protocol" : "kafka",
          "X-Async-Destination" : "orders.events",
          "X-Async-Key" : "ORD-9942",
          "X-Async-Correlation-Id" : "corr-kafka-stub-1",
          "X-Forwarded-For" : "10.42.8.11",
          "x-tenant-id" : "emea-retail"
        },
        "browserProxyRequest" : false,
        "loggedDate" : 1794902298115,
        "bodyAsBase64" : "eyJvcmRlcklkIjoiT1JELTk5NDIiLCJldmVudCI6Ik9SREVSX0NSRUFURUQifQ==",
        "body" : "{\"orderId\":\"ORD-9942\",\"event\":\"ORDER_CREATED\"}",
        "scheme" : "http",
        "host" : "localhost",
        "port" : 8080,
        "loggedDateString" : "2026-11-17T07:58:18.115Z",
        "queryParams" : { },
        "formParams" : { }
      },
      "responseDefinition" : {
        "status" : 204,
        "headers" : {
          "Content-Type" : "text/plain; charset=UTF-8",
          "X-WireMock-Async-Protocol" : "kafka",
          "X-Async-Destination" : "orders.events"
        }
      },
      "response" : {
        "status" : 204,
        "headers" : {
          "Content-Type" : "text/plain; charset=UTF-8",
          "X-WireMock-Async-Protocol" : "kafka",
          "X-Async-Destination" : "orders.events"
        },
        "bodyAsBase64" : "",
        "body" : ""
      },
      "wasMatched" : true,
      "timing" : {
        "addedDelay" : 0,
        "processTime" : 0,
        "responseSendTime" : 0,
        "serveTime" : 0,
        "totalTime" : 0
      },
      "subEvents" : [ ],
      "stubMapping" : {
        "id" : "10000000-0000-4000-8000-000000000009",
        "name" : "Kafka Events Sink: orders.events",
        "request" : {
          "url" : "/__async/kafka/orders.events",
          "method" : "POST"
        },
        "response" : {
          "status" : 204,
          "headers" : {
            "X-Async-Ack" : "ack",
            "X-Async-Protocol" : "kafka"
          }
        },
        "uuid" : "10000000-0000-4000-8000-000000000009",
        "priority" : 5,
        "metadata" : {
          "project" : "Async Messaging",
          "protocol" : "kafka",
          "tags" : [
            "async",
            "kafka",
            "events"
          ],
          "async" : {
            "mode" : "mock",
            "channel" : "orders.events",
            "ackPolicy" : "ack",
            "protocol" : "kafka"
          },
          "channel" : "orders.events"
        }
      },
      "sourceIp" : "10.42.8.11",
      "sourceHost" : "system-a.internal"
    },
    {
      "id" : "0078062b-9dea-490c-bb46-79ab72e248e4",
      "request" : {
        "url" : "/__casual/services/inventoryCheck",
        "absoluteUrl" : "http://localhost:8080/__casual/services/inventoryCheck",
        "method" : "POST",
        "clientIp" : "127.0.0.1",
        "headers" : {
          "Content-Type" : "application/json",
          "X-WireMock-Protocol" : "casual",
          "X-Casual-Service" : "inventoryCheck",
          "X-Casual-Buffer-Type" : ".json/",
          "X-Casual-Execution" : "0e06e087-3339-40b8-89f8-2bfec2e7625e",
          "X-Forwarded-For" : "127.0.0.1",
          "X-Casual-Parent-Span" : "SpanId{id=593fa26b2f12ea81}"
        },
        "browserProxyRequest" : false,
        "loggedDate" : 1794902298104,
        "bodyAsBase64" : "eyJza3UiOiJTS1UtNDAwIn0=",
        "body" : "{\"sku\":\"SKU-400\"}",
        "scheme" : "http",
        "host" : "localhost",
        "port" : 8080,
        "loggedDateString" : "2026-11-17T07:58:18.104Z",
        "queryParams" : { },
        "formParams" : { }
      },
      "responseDefinition" : {
        "status" : 200,
        "body" : "{\"service\":\"inventoryCheck\",\"inStock\":true,\"warehouse\":\"SE-ARN\"}",
        "headers" : {
          "Content-Type" : "application/json",
          "X-Casual-Error-State" : "OK",
          "X-Casual-Transaction-State" : "TX_ACTIVE",
          "X-Casual-Buffer-Type" : ".json/"
        },
        "proxyBaseUrl" : "casual://127.0.0.1:7773"
      },
      "response" : {
        "status" : 200,
        "headers" : {
          "Content-Type" : "application/json",
          "X-Casual-Error-State" : "OK",
          "X-Casual-Transaction-State" : "TX_ACTIVE",
          "X-Casual-Buffer-Type" : ".json/"
        },
        "bodyAsBase64" : "eyJzZXJ2aWNlIjoiaW52ZW50b3J5Q2hlY2siLCJpblN0b2NrIjp0cnVlLCJ3YXJlaG91c2UiOiJTRS1BUk4ifQ==",
        "body" : "{\"service\":\"inventoryCheck\",\"inStock\":true,\"warehouse\":\"SE-ARN\"}"
      },
      "wasMatched" : true,
      "timing" : {
        "addedDelay" : 0,
        "processTime" : 0,
        "responseSendTime" : 0,
        "serveTime" : 0,
        "totalTime" : 0
      },
      "subEvents" : [
        {
          "type" : "ERROR",
          "data" : {
            "message" : "Exception thrown while configuring webhook: url' not present"
          }
        }
      ],
      "stubMapping" : {
        "id" : "10000000-0000-4000-8000-000000000011",
        "name" : "Casual XATMI: inventoryCheck Proxy",
        "request" : {
          "urlPattern" : "/__casual/services/inventory.*",
          "method" : "POST"
        },
        "response" : {
          "status" : 200,
          "proxyBaseUrl" : "casual://127.0.0.1:7773"
        },
        "uuid" : "10000000-0000-4000-8000-000000000011",
        "priority" : 10,
        "metadata" : {
          "project" : "Casual Gateway",
          "targetBaseUrl" : "casual://127.0.0.1:7773",
          "protocol" : "casual",
          "mode" : "proxy",
          "tags" : [
            "casual",
            "proxy",
            "xatmi"
          ]
        }
      },
      "sourceIp" : "127.0.0.1",
      "sourceHost" : "localhost"
    },
    {
      "id" : "a1f623b3-fb78-4ef3-bfd8-bf391afcdc9e",
      "request" : {
        "url" : "/__casual/services/echoService",
        "absoluteUrl" : "http://localhost:8080/__casual/services/echoService",
        "method" : "POST",
        "clientIp" : "127.0.0.1",
        "headers" : {
          "Content-Type" : "application/json",
          "X-WireMock-Protocol" : "casual",
          "X-Casual-Service" : "echoService",
          "X-Casual-Buffer-Type" : ".json/",
          "X-Casual-Execution" : "6f70a981-bb48-430f-a619-a985df1590ca",
          "X-Forwarded-For" : "127.0.0.1",
          "X-Casual-Parent-Span" : "SpanId{id=714a7d73e6c6fc0c}"
        },
        "browserProxyRequest" : false,
        "loggedDate" : 1794902298041,
        "bodyAsBase64" : "eyJwaW5nIjoicG9uZyJ9",
        "body" : "{\"ping\":\"pong\"}",
        "scheme" : "http",
        "host" : "localhost",
        "port" : 8080,
        "loggedDateString" : "2026-11-17T07:58:18.041Z",
        "queryParams" : { },
        "formParams" : { }
      },
      "responseDefinition" : {
        "status" : 200,
        "body" : "{\"service\":\"echoService\",\"status\":\"OK\",\"xatmiReturnCode\":0,\"message\":\"Casual service loopback operational\"}",
        "headers" : {
          "Content-Type" : "application/json; charset=utf-8",
          "X-Casual-Service" : "echoService",
          "X-Casual-Error-State" : "OK"
        }
      },
      "response" : {
        "status" : 200,
        "headers" : {
          "Content-Type" : "application/json; charset=utf-8",
          "X-Casual-Service" : "echoService",
          "X-Casual-Error-State" : "OK"
        },
        "bodyAsBase64" : "eyJzZXJ2aWNlIjoiZWNob1NlcnZpY2UiLCJzdGF0dXMiOiJPSyIsInhhdG1pUmV0dXJuQ29kZSI6MCwibWVzc2FnZSI6IkNhc3VhbCBzZXJ2aWNlIGxvb3BiYWNrIG9wZXJhdGlvbmFsIn0=",
        "body" : "{\"service\":\"echoService\",\"status\":\"OK\",\"xatmiReturnCode\":0,\"message\":\"Casual service loopback operational\"}"
      },
      "wasMatched" : true,
      "timing" : {
        "addedDelay" : 0,
        "processTime" : 0,
        "responseSendTime" : 0,
        "serveTime" : 0,
        "totalTime" : 0
      },
      "subEvents" : [
        {
          "type" : "ERROR",
          "data" : {
            "message" : "Exception thrown while configuring webhook: url' not present"
          }
        }
      ],
      "stubMapping" : {
        "id" : "10000000-0000-4000-8000-000000000008",
        "name" : "Casual XATMI: echoService",
        "request" : {
          "url" : "/__casual/services/echoService",
          "method" : "POST"
        },
        "response" : {
          "status" : 200,
          "body" : "{\"service\":\"echoService\",\"status\":\"OK\",\"xatmiReturnCode\":0,\"message\":\"Casual service loopback operational\"}",
          "headers" : {
            "Content-Type" : "application/json; charset=utf-8",
            "X-Casual-Service" : "echoService",
            "X-Casual-Error-State" : "OK"
          }
        },
        "uuid" : "10000000-0000-4000-8000-000000000008",
        "priority" : 5,
        "metadata" : {
          "project" : "Casual Gateway",
          "service" : "echoService",
          "protocol" : "casual",
          "tags" : [
            "casual",
            "xatmi",
            "echo"
          ],
          "exampleRequest" : {
            "headers" : {
              "Content-Type" : "application/json",
              "x-casual-buffer-type" : "JSON",
              "x-casual-service" : "echoService"
            },
            "path" : "/__casual/services/echoService",
            "body" : {
              "ping" : "pong"
            }
          }
        }
      },
      "sourceIp" : "127.0.0.1",
      "sourceHost" : "localhost"
    },
    {
      "id" : "1fff8e74-bb17-462a-93f0-7046e6216bd6",
      "request" : {
        "url" : "/api/v1/missing-route",
        "absoluteUrl" : "http://localhost:8080/api/v1/missing-route",
        "method" : "GET",
        "clientIp" : "10.42.8.19",
        "headers" : {
          "Host" : "localhost:8080",
          "User-Agent" : "Java-http-client/21.0.6",
          "X-Correlation-Id" : "corr-missing-404",
          "X-Forwarded-For" : "10.42.8.19"
        },
        "cookies" : { },
        "browserProxyRequest" : false,
        "loggedDate" : 1794902297322,
        "bodyAsBase64" : "",
        "body" : "",
        "protocol" : "HTTP/1.1",
        "scheme" : "http",
        "host" : "localhost",
        "port" : 8080,
        "loggedDateString" : "2026-11-17T07:58:17.322Z",
        "queryParams" : { },
        "formParams" : { }
      },
      "responseDefinition" : {
        "status" : 404,
        "fromConfiguredStub" : false
      },
      "response" : {
        "status" : 404,
        "bodyAsBase64" : "",
        "body" : ""
      },
      "wasMatched" : false,
      "timing" : {
        "addedDelay" : 0,
        "processTime" : 0,
        "responseSendTime" : 0,
        "serveTime" : 0,
        "totalTime" : 0
      },
      "subEvents" : [
        {
          "type" : "REQUEST_NOT_MATCHED",
          "timeOffsetNanos" : 10702800,
          "data" : {
            "status" : 404,
            "contentType" : "text/plain",
            "report" : "\r\n                                               Request was not matched\r\n                                               =======================\r\n\r\n-----------------------------------------------------------------------------------------------------------------------\r\n| Closest stub                                             | Request                                                  |\r\n-----------------------------------------------------------------------------------------------------------------------\r\n                                                           |\r\nList Users                                                 |\r\n                                                           |\r\nGET                                                        | GET\r\n[path] /api/v1/users                                       | /api/v1/missing-route                               <<<<< URL does not match\r\n                                                           |\r\n                                                           |\r\n-----------------------------------------------------------------------------------------------------------------------\r\n"
          }
        }
      ],
      "stubMapping" : {
        "id" : "333a0975-c0e0-43a0-b515-654d7046a1f5",
        "request" : {
          "method" : "ANY"
        },
        "response" : {
          "status" : 404,
          "fromConfiguredStub" : false
        },
        "uuid" : "333a0975-c0e0-43a0-b515-654d7046a1f5"
      },
      "sourceIp" : "10.42.8.19",
      "sourceHost" : ""
    },
    {
      "id" : "b641e654-f112-4a61-a1f2-3ec484d6c282",
      "request" : {
        "url" : "/erp/api/inventory/SKU-400",
        "absoluteUrl" : "http://localhost:8080/erp/api/inventory/SKU-400",
        "method" : "GET",
        "clientIp" : "10.42.8.12",
        "headers" : {
          "Host" : "localhost:8080",
          "User-Agent" : "Java-http-client/21.0.6",
          "X-Correlation-Id" : "corr-erp-proxy-350",
          "X-Forwarded-For" : "10.42.8.12"
        },
        "cookies" : { },
        "browserProxyRequest" : false,
        "loggedDate" : 1794902297095,
        "bodyAsBase64" : "",
        "body" : "",
        "protocol" : "HTTP/1.1",
        "scheme" : "http",
        "host" : "localhost",
        "port" : 8080,
        "loggedDateString" : "2026-11-17T07:58:17.095Z",
        "queryParams" : { },
        "formParams" : { }
      },
      "responseDefinition" : {
        "status" : 200,
        "proxyBaseUrl" : "http://127.0.0.1:9000"
      },
      "response" : {
        "status" : 200,
        "headers" : {
          "Matched-Stub-Id" : "b3d504f5-7314-4fea-a045-1e098d5f20fc",
          "Content-Type" : "application/json; charset=utf-8"
        },
        "bodyAsBase64" : "eyJza3UiOiJTS1UtNDAwIiwiYXZhaWxhYmxlIjo0Miwid2FyZWhvdXNlIjoiU0UtQVJOIn0=",
        "body" : "{\"sku\":\"SKU-400\",\"available\":42,\"warehouse\":\"SE-ARN\"}"
      },
      "wasMatched" : true,
      "timing" : {
        "addedDelay" : 0,
        "processTime" : 195,
        "responseSendTime" : 0,
        "serveTime" : 195,
        "totalTime" : 195
      },
      "subEvents" : [ ],
      "stubMapping" : {
        "id" : "10000000-0000-4000-8000-000000000005",
        "name" : "ERP System Live Proxy",
        "request" : {
          "urlPattern" : "/erp/api/.*",
          "method" : "ANY"
        },
        "response" : {
          "status" : 200,
          "proxyBaseUrl" : "http://127.0.0.1:9000"
        },
        "uuid" : "10000000-0000-4000-8000-000000000005",
        "priority" : 10,
        "metadata" : {
          "project" : "erp-system",
          "tags" : [
            "proxy",
            "erp"
          ],
          "mode" : "proxy"
        }
      },
      "sourceIp" : "10.42.8.12",
      "sourceHost" : "system-b.internal"
    },
    {
      "id" : "af487fbc-a7d3-459e-beab-555472a04676",
      "request" : {
        "url" : "/api/v1/orders/ORD-9942/status",
        "absoluteUrl" : "http://localhost:8080/api/v1/orders/ORD-9942/status",
        "method" : "GET",
        "clientIp" : "10.42.8.13",
        "headers" : {
          "Host" : "localhost:8080",
          "User-Agent" : "Java-http-client/21.0.6",
          "X-Correlation-Id" : "corr-order-fsm-303",
          "X-Forwarded-For" : "10.42.8.13"
        },
        "cookies" : { },
        "browserProxyRequest" : false,
        "loggedDate" : 1794902297054,
        "bodyAsBase64" : "",
        "body" : "",
        "protocol" : "HTTP/1.1",
        "scheme" : "http",
        "host" : "localhost",
        "port" : 8080,
        "loggedDateString" : "2026-11-17T07:58:17.054Z",
        "queryParams" : { },
        "formParams" : { }
      },
      "responseDefinition" : {
        "status" : 200,
        "body" : "{\"orderId\":\"ORD-9942\",\"status\":\"SHIPPED\",\"carrier\":\"NordicExpress\"}",
        "headers" : {
          "Content-Type" : "application/json; charset=utf-8"
        }
      },
      "response" : {
        "status" : 200,
        "headers" : {
          "Content-Type" : "application/json; charset=utf-8",
          "Matched-Stub-Id" : "10000000-0000-4000-8000-000000000003",
          "Matched-Stub-Name" : "Order Status (Started -> SHIPPED)"
        },
        "bodyAsBase64" : "eyJvcmRlcklkIjoiT1JELTk5NDIiLCJzdGF0dXMiOiJTSElQUEVEIiwiY2FycmllciI6Ik5vcmRpY0V4cHJlc3MifQ==",
        "body" : "{\"orderId\":\"ORD-9942\",\"status\":\"SHIPPED\",\"carrier\":\"NordicExpress\"}"
      },
      "wasMatched" : true,
      "timing" : {
        "addedDelay" : 0,
        "processTime" : 0,
        "responseSendTime" : 0,
        "serveTime" : 0,
        "totalTime" : 0
      },
      "subEvents" : [ ],
      "stubMapping" : {
        "id" : "10000000-0000-4000-8000-000000000003",
        "name" : "Order Status (Started -> SHIPPED)",
        "request" : {
          "url" : "/api/v1/orders/ORD-9942/status",
          "method" : "GET"
        },
        "response" : {
          "status" : 200,
          "body" : "{\"orderId\":\"ORD-9942\",\"status\":\"SHIPPED\",\"carrier\":\"NordicExpress\"}",
          "headers" : {
            "Content-Type" : "application/json; charset=utf-8"
          }
        },
        "uuid" : "10000000-0000-4000-8000-000000000003",
        "priority" : 5,
        "scenarioName" : "OrderFulfillment",
        "requiredScenarioState" : "Started",
        "newScenarioState" : "SHIPPED",
        "metadata" : {
          "tags" : [
            "orders",
            "fsm",
            "scenario"
          ],
          "project" : "orders-api"
        }
      },
      "sourceIp" : "10.42.8.13",
      "sourceHost" : "checkout-svc.internal"
    },
    {
      "id" : "cd8a9cc4-e9da-4c7e-8eea-e0142eaf3cd9",
      "request" : {
        "url" : "/api/v1/orders",
        "absoluteUrl" : "http://localhost:8080/api/v1/orders",
        "method" : "POST",
        "clientIp" : "10.42.8.13",
        "headers" : {
          "Content-Length" : "150",
          "Host" : "localhost:8080",
          "User-Agent" : "Java-http-client/21.0.6",
          "Content-Type" : "application/json",
          "X-Correlation-Id" : "corr-orders-202",
          "X-Forwarded-For" : "10.42.8.13"
        },
        "cookies" : { },
        "browserProxyRequest" : false,
        "loggedDate" : 1794902297028,
        "bodyAsBase64" : "eyJjdXN0b21lciI6Ik5vcmRpYyBSZXRhaWwgQUIiLCJvcmRlcklkIjoiT1JELTk5NDIiLCJyYXdEb2N1bWVudCI6IjxvcmRlclJlcXVlc3Q+PG9yZGVySWQ+T1JELTk5NDI8L29yZGVySWQ+PHByaW9yaXR5PkhJR0g8L3ByaW9yaXR5Pjwvb3JkZXJSZXF1ZXN0PiJ9",
        "body" : "{\"customer\":\"Nordic Retail AB\",\"orderId\":\"ORD-9942\",\"rawDocument\":\"<orderRequest><orderId>ORD-9942</orderId><priority>HIGH</priority></orderRequest>\"}",
        "protocol" : "HTTP/1.1",
        "scheme" : "http",
        "host" : "localhost",
        "port" : 8080,
        "loggedDateString" : "2026-11-17T07:58:17.028Z",
        "queryParams" : { },
        "formParams" : { }
      },
      "responseDefinition" : {
        "status" : 201,
        "body" : "{\n  \"totalAmount\" : 1250.0,\n  \"orderId\" : \"ORD-9942\",\n  \"status\" : \"CREATED\",\n  \"currency\" : \"EUR\",\n  \"complianceAudit\" : \"{\\\"auditor\\\":\\\"order-fsm\\\",\\\"score\\\":99,\\\"embeddedXml\\\":\\\"<auditReport xmlns=\\\\\\\"http://example.com/orders\\\\\\\"><orderId>ORD-9942</orderId><status>VALIDATED</status><checksum>a9f4c82b</checksum></auditReport>\\\"}\",\n  \"encodedManifest\" : \"eyJtYW5pZmVzdFZlcnNpb24iOjEsIm9yZGVySWQiOiJPUkQtOTk0MiIsInhtbFN1bW1hcnkiOiI8YXVkaXRSZXBvcnQgeG1sbnM9XCJodHRwOi8vZXhhbXBsZS5jb20vb3JkZXJzXCI+PG9yZGVySWQ+T1JELTk5NDI8L29yZGVySWQ+PHN0YXR1cz5WQUxJREFURUQ8L3N0YXR1cz48Y2hlY2tzdW0+YTlmNGM4MmI8L2NoZWNrc3VtPjwvYXVkaXRSZXBvcnQ+In0=\"\n}",
        "headers" : {
          "Content-Type" : "application/json; charset=utf-8",
          "Location" : "/api/v1/orders/ORD-9942"
        }
      },
      "response" : {
        "status" : 201,
        "headers" : {
          "Content-Type" : "application/json; charset=utf-8",
          "Location" : "/api/v1/orders/ORD-9942",
          "Matched-Stub-Id" : "10000000-0000-4000-8000-000000000002",
          "Matched-Stub-Name" : "Create Order (Nested Polyglot Payload)"
        },
        "bodyAsBase64" : "ewogICJ0b3RhbEFtb3VudCIgOiAxMjUwLjAsCiAgIm9yZGVySWQiIDogIk9SRC05OTQyIiwKICAic3RhdHVzIiA6ICJDUkVBVEVEIiwKICAiY3VycmVuY3kiIDogIkVVUiIsCiAgImNvbXBsaWFuY2VBdWRpdCIgOiAie1wiYXVkaXRvclwiOlwib3JkZXItZnNtXCIsXCJzY29yZVwiOjk5LFwiZW1iZWRkZWRYbWxcIjpcIjxhdWRpdFJlcG9ydCB4bWxucz1cXFwiaHR0cDovL2V4YW1wbGUuY29tL29yZGVyc1xcXCI+PG9yZGVySWQ+T1JELTk5NDI8L29yZGVySWQ+PHN0YXR1cz5WQUxJREFURUQ8L3N0YXR1cz48Y2hlY2tzdW0+YTlmNGM4MmI8L2NoZWNrc3VtPjwvYXVkaXRSZXBvcnQ+XCJ9IiwKICAiZW5jb2RlZE1hbmlmZXN0IiA6ICJleUp0WVc1cFptVnpkRlpsY25OcGIyNGlPakVzSW05eVpHVnlTV1FpT2lKUFVrUXRPVGswTWlJc0luaHRiRk4xYlcxaGNua2lPaUk4WVhWa2FYUlNaWEJ2Y25RZ2VHMXNibk05WENKb2RIUndPaTh2WlhoaGJYQnNaUzVqYjIwdmIzSmtaWEp6WENJK1BHOXlaR1Z5U1dRK1QxSkVMVGs1TkRJOEwyOXlaR1Z5U1dRK1BITjBZWFIxY3o1V1FVeEpSRUZVUlVROEwzTjBZWFIxY3o0OFkyaGxZMnR6ZFcwK1lUbG1OR000TW1JOEwyTm9aV05yYzNWdFBqd3ZZWFZrYVhSU1pYQnZjblErSW4wPSIKfQ==",
        "body" : "{\n  \"totalAmount\" : 1250.0,\n  \"orderId\" : \"ORD-9942\",\n  \"status\" : \"CREATED\",\n  \"currency\" : \"EUR\",\n  \"complianceAudit\" : \"{\\\"auditor\\\":\\\"order-fsm\\\",\\\"score\\\":99,\\\"embeddedXml\\\":\\\"<auditReport xmlns=\\\\\\\"http://example.com/orders\\\\\\\"><orderId>ORD-9942</orderId><status>VALIDATED</status><checksum>a9f4c82b</checksum></auditReport>\\\"}\",\n  \"encodedManifest\" : \"eyJtYW5pZmVzdFZlcnNpb24iOjEsIm9yZGVySWQiOiJPUkQtOTk0MiIsInhtbFN1bW1hcnkiOiI8YXVkaXRSZXBvcnQgeG1sbnM9XCJodHRwOi8vZXhhbXBsZS5jb20vb3JkZXJzXCI+PG9yZGVySWQ+T1JELTk5NDI8L29yZGVySWQ+PHN0YXR1cz5WQUxJREFURUQ8L3N0YXR1cz48Y2hlY2tzdW0+YTlmNGM4MmI8L2NoZWNrc3VtPjwvYXVkaXRSZXBvcnQ+In0=\"\n}"
      },
      "wasMatched" : true,
      "timing" : {
        "addedDelay" : 0,
        "processTime" : 0,
        "responseSendTime" : 0,
        "serveTime" : 0,
        "totalTime" : 0
      },
      "subEvents" : [ ],
      "stubMapping" : {
        "id" : "10000000-0000-4000-8000-000000000002",
        "name" : "Create Order (Nested Polyglot Payload)",
        "request" : {
          "url" : "/api/v1/orders",
          "method" : "POST",
          "headers" : {
            "Content-Type" : {
              "contains" : "application/json"
            }
          },
          "bodyPatterns" : [
            {
              "matchesJsonPath" : "$.orderId"
            }
          ]
        },
        "response" : {
          "status" : 201,
          "body" : "{\n  \"totalAmount\" : 1250.0,\n  \"orderId\" : \"ORD-9942\",\n  \"status\" : \"CREATED\",\n  \"currency\" : \"EUR\",\n  \"complianceAudit\" : \"{\\\"auditor\\\":\\\"order-fsm\\\",\\\"score\\\":99,\\\"embeddedXml\\\":\\\"<auditReport xmlns=\\\\\\\"http://example.com/orders\\\\\\\"><orderId>ORD-9942</orderId><status>VALIDATED</status><checksum>a9f4c82b</checksum></auditReport>\\\"}\",\n  \"encodedManifest\" : \"eyJtYW5pZmVzdFZlcnNpb24iOjEsIm9yZGVySWQiOiJPUkQtOTk0MiIsInhtbFN1bW1hcnkiOiI8YXVkaXRSZXBvcnQgeG1sbnM9XCJodHRwOi8vZXhhbXBsZS5jb20vb3JkZXJzXCI+PG9yZGVySWQ+T1JELTk5NDI8L29yZGVySWQ+PHN0YXR1cz5WQUxJREFURUQ8L3N0YXR1cz48Y2hlY2tzdW0+YTlmNGM4MmI8L2NoZWNrc3VtPjwvYXVkaXRSZXBvcnQ+In0=\"\n}",
          "headers" : {
            "Content-Type" : "application/json; charset=utf-8",
            "Location" : "/api/v1/orders/ORD-9942"
          }
        },
        "uuid" : "10000000-0000-4000-8000-000000000002",
        "priority" : 5,
        "metadata" : {
          "tags" : [
            "orders",
            "polyglot",
            "nested-payload"
          ],
          "project" : "orders-api"
        }
      },
      "sourceIp" : "10.42.8.13",
      "sourceHost" : "checkout-svc.internal"
    },
    {
      "id" : "35255e47-41a5-4208-bfed-7e16bf458953",
      "request" : {
        "url" : "/api/v1/users",
        "absoluteUrl" : "http://127.0.0.1:8081/api/v1/users",
        "method" : "GET",
        "clientIp" : "10.42.8.11",
        "headers" : {
          "Host" : "127.0.0.1:8081",
          "User-Agent" : "Java-http-client/21.0.6",
          "X-Correlation-Id" : "corr-users-101",
          "X-Forwarded-For" : "10.42.8.11"
        },
        "cookies" : { },
        "browserProxyRequest" : false,
        "loggedDate" : 1794902296796,
        "bodyAsBase64" : "",
        "body" : "",
        "protocol" : "HTTP/1.1",
        "scheme" : "http",
        "host" : "127.0.0.1",
        "port" : 8081,
        "loggedDateString" : "2026-11-17T07:58:16.796Z",
        "queryParams" : { },
        "formParams" : { }
      },
      "responseDefinition" : {
        "status" : 200,
        "body" : "[{\"role\":\"ADMIN\",\"name\":\"Ada Lovelace\",\"id\":\"usr-1\"},{\"role\":\"SECURITY\",\"name\":\"Alan Turing\",\"id\":\"usr-2\"},{\"role\":\"DEVELOPER\",\"name\":\"Grace Hopper\",\"id\":\"usr-3\"}]",
        "headers" : {
          "Content-Type" : "application/json; charset=utf-8"
        }
      },
      "response" : {
        "status" : 200,
        "headers" : {
          "Content-Type" : "application/json; charset=utf-8",
          "Matched-Stub-Id" : "10000000-0000-4000-8000-000000000001",
          "Matched-Stub-Name" : "List Users"
        },
        "bodyAsBase64" : "W3sicm9sZSI6IkFETUlOIiwibmFtZSI6IkFkYSBMb3ZlbGFjZSIsImlkIjoidXNyLTEifSx7InJvbGUiOiJTRUNVUklUWSIsIm5hbWUiOiJBbGFuIFR1cmluZyIsImlkIjoidXNyLTIifSx7InJvbGUiOiJERVZFTE9QRVIiLCJuYW1lIjoiR3JhY2UgSG9wcGVyIiwiaWQiOiJ1c3ItMyJ9XQ==",
        "body" : "[{\"role\":\"ADMIN\",\"name\":\"Ada Lovelace\",\"id\":\"usr-1\"},{\"role\":\"SECURITY\",\"name\":\"Alan Turing\",\"id\":\"usr-2\"},{\"role\":\"DEVELOPER\",\"name\":\"Grace Hopper\",\"id\":\"usr-3\"}]"
      },
      "wasMatched" : true,
      "timing" : {
        "addedDelay" : 0,
        "processTime" : 1,
        "responseSendTime" : 0,
        "serveTime" : 1,
        "totalTime" : 1
      },
      "subEvents" : [ ],
      "stubMapping" : {
        "id" : "10000000-0000-4000-8000-000000000001",
        "name" : "List Users",
        "request" : {
          "urlPath" : "/api/v1/users",
          "method" : "GET"
        },
        "response" : {
          "status" : 200,
          "body" : "[{\"role\":\"ADMIN\",\"name\":\"Ada Lovelace\",\"id\":\"usr-1\"},{\"role\":\"SECURITY\",\"name\":\"Alan Turing\",\"id\":\"usr-2\"},{\"role\":\"DEVELOPER\",\"name\":\"Grace Hopper\",\"id\":\"usr-3\"}]",
          "headers" : {
            "Content-Type" : "application/json; charset=utf-8"
          }
        },
        "uuid" : "10000000-0000-4000-8000-000000000001",
        "priority" : 5,
        "metadata" : {
          "tags" : [
            "users",
            "rest"
          ],
          "project" : "identity-api"
        }
      },
      "sourceIp" : "10.42.8.11",
      "sourceHost" : "system-a.internal"
    }
  ],
  "scenarios" : [
    {
      "id" : "OrderFulfillment",
      "name" : "OrderFulfillment",
      "state" : "SHIPPED",
      "possibleStates" : [
        "Started",
        "DELIVERED",
        "SHIPPED"
      ],
      "mappings" : [
        {
          "id" : "10000000-0000-4000-8000-000000000003",
          "name" : "Order Status (Started -> SHIPPED)",
          "request" : {
            "url" : "/api/v1/orders/ORD-9942/status",
            "method" : "GET"
          },
          "response" : {
            "status" : 200,
            "body" : "{\"orderId\":\"ORD-9942\",\"status\":\"SHIPPED\",\"carrier\":\"NordicExpress\"}",
            "headers" : {
              "Content-Type" : "application/json; charset=utf-8"
            }
          },
          "uuid" : "10000000-0000-4000-8000-000000000003",
          "priority" : 5,
          "scenarioName" : "OrderFulfillment",
          "requiredScenarioState" : "Started",
          "newScenarioState" : "SHIPPED",
          "metadata" : {
            "tags" : [
              "orders",
              "fsm",
              "scenario"
            ],
            "project" : "orders-api"
          }
        },
        {
          "id" : "10000000-0000-4000-8000-000000000004",
          "name" : "Order Status (SHIPPED -> DELIVERED)",
          "request" : {
            "url" : "/api/v1/orders/ORD-9942/status",
            "method" : "GET"
          },
          "response" : {
            "status" : 200,
            "body" : "{\"orderId\":\"ORD-9942\",\"status\":\"DELIVERED\",\"signedBy\":\"A. Lindqvist\"}",
            "headers" : {
              "Content-Type" : "application/json; charset=utf-8"
            }
          },
          "uuid" : "10000000-0000-4000-8000-000000000004",
          "priority" : 5,
          "scenarioName" : "OrderFulfillment",
          "requiredScenarioState" : "SHIPPED",
          "newScenarioState" : "DELIVERED",
          "metadata" : {
            "tags" : [
              "orders",
              "fsm",
              "scenario"
            ],
            "project" : "orders-api"
          }
        }
      ]
    }
  ],
  "netemProxies" : [
    {
      "name" : "wiremock-netem",
      "listenPort" : 8081,
      "targetHost" : "127.0.0.1",
      "targetPort" : 8080,
      "enabled" : true,
      "stats" : {
        "activeConnections" : 1,
        "totalConnections" : 1,
        "bytesUpstream" : 153,
        "bytesDownstream" : 354,
        "impairmentsInjected" : 2,
        "normalTransfers" : 0,
        "impairedTransfers" : 1,
        "bytesUpstreamNormal" : 0,
        "bytesUpstreamImpaired" : 153,
        "bytesDownstreamNormal" : 0,
        "bytesDownstreamImpaired" : 354,
        "toxicCounters" : {
          "downstream_latency" : 2
        }
      },
      "toxics" : [
        {
          "name" : "downstream_latency",
          "type" : "latency",
          "stream" : "downstream",
          "toxicity" : 1.0,
          "patternType" : "ASCII",
          "attributes" : {
            "latency" : 25,
            "jitter" : 5
          }
        }
      ]
    }
  ],
  "casualConfig" : {
    "enabled" : true,
    "port" : 7772,
    "running" : true
  },
  "asyncConnections" : [
    {
      "id" : "mainframe-casual",
      "protocol" : "casual",
      "name" : "Casual Domain Gateway",
      "endpoint" : "127.0.0.1:7772",
      "properties" : {
        "domain.id" : "wiremock-casual-domain"
      },
      "status" : "HEALTHY"
    },
    {
      "id" : "corp-ibmmq",
      "protocol" : "ibmmq",
      "name" : "IBM MQ Queue Manager (QM1)",
      "endpoint" : "127.0.0.1:1414",
      "properties" : {
        "channel" : "DEV.APP.SVRCONN",
        "queueManager" : "QM1",
        "password" : "${IBM_MQ_PASSWORD}",
        "username" : "app"
      },
      "status" : "HEALTHY"
    },
    {
      "id" : "local-kafka-sink",
      "protocol" : "kafka",
      "name" : "Kafka Wire Sink (In-Process)",
      "endpoint" : "127.0.0.1:9092",
      "properties" : {
        "client.id" : "wm-kafka-sink"
      },
      "status" : "HEALTHY"
    },
    {
      "id" : "embedded-artemis",
      "protocol" : "amqp",
      "name" : "ActiveMQ Artemis (Embedded)",
      "endpoint" : "127.0.0.1:5672",
      "properties" : {
        "client.id" : "wm-artemis-client",
        "amqp.protocol" : "1.0"
      },
      "status" : "HEALTHY"
    }
  ],
  "asyncDestinations" : [
    {
      "id" : "kafka:orders.events",
      "protocol" : "kafka",
      "name" : "orders.events",
      "brokerId" : "local-kafka-sink",
      "messageCount" : 1,
      "metadata" : {
        "type" : "TOPIC",
        "partitions" : 1
      }
    },
    {
      "id" : "amqp:orders.inbound",
      "protocol" : "amqp",
      "name" : "orders.inbound",
      "brokerId" : "local-amqp-server",
      "messageCount" : 1,
      "metadata" : {
        "type" : "QUEUE"
      }
    },
    {
      "id" : "casual:billing.q.error",
      "protocol" : "casual",
      "name" : "billing.q.error",
      "brokerId" : "local-casual-gateway",
      "messageCount" : 0,
      "metadata" : {
        "dlq" : true
      }
    },
    {
      "id" : "casual:billing.q",
      "protocol" : "casual",
      "name" : "billing.q",
      "brokerId" : "local-casual-gateway",
      "messageCount" : 1,
      "metadata" : {
        "dlq" : false
      }
    }
  ],
  "asyncMessages" : {
    "kafka/orders.events" : [
      {
        "id" : "12f5f0a9-da13-4eb8-969a-0ffd0d846955",
        "correlationId" : "",
        "replyTo" : "",
        "destination" : "orders.events",
        "protocol" : "kafka",
        "key" : "ORD-9942",
        "payload" : "{\n  \"customer\" : \"Acme Corp\",\n  \"orderId\" : \"ORD-9942\",\n  \"legacyXmlInvoice\" : \"<?xml version=\\\"1.0\\\" encoding=\\\"UTF-8\\\"?><invoice id=\\\"INV-9942\\\"><total currency=\\\"EUR\\\">1250.00</total></invoice>\"\n}",
        "headers" : { },
        "timestamp" : 1.7949022986697497E9,
        "size" : 199,
        "partition" : 0,
        "offset" : 0
      }
    ],
    "amqp/orders.inbound" : [
      {
        "id" : "msg-amqp-9942",
        "correlationId" : "corr-amqp-9942",
        "replyTo" : "orders.replies",
        "destination" : "orders.inbound",
        "protocol" : "amqp",
        "key" : "ORD-9942",
        "payload" : "{\"orderId\":\"ORD-9942\",\"action\":\"DISPATCH\"}",
        "headers" : {
          "content-type" : "application/json"
        },
        "timestamp" : 1.7949022981671298E9,
        "size" : 42,
        "partition" : null,
        "offset" : null
      }
    ],
    "casual/billing.q.error" : [ ],
    "casual/billing.q" : [
      {
        "id" : "20000000-0000-4000-8000-000000000001",
        "correlationId" : "corr-casual-billing-1",
        "replyTo" : "",
        "destination" : "billing.q",
        "protocol" : "casual",
        "key" : "",
        "payload" : "{\"invoiceId\":\"INV-2026-9942\",\"amount\":1250.0,\"currency\":\"EUR\"}",
        "headers" : {
          "X-Casual-Buffer-Type" : ".json/"
        },
        "timestamp" : 1.7949022982012012E9,
        "size" : 62,
        "partition" : null,
        "offset" : null
      }
    ]
  }
};
