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
        "channel" : "orders.inbound",
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
        }
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
        "channel" : "orders.events",
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
        }
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
        },
        "project" : "Casual Gateway",
        "service" : "echoService",
        "protocol" : "casual",
        "tags" : [
          "casual",
          "xatmi",
          "echo"
        ]
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
        "project" : "orders-api",
        "tags" : [
          "orders",
          "fsm",
          "scenario"
        ]
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
        "project" : "orders-api",
        "tags" : [
          "orders",
          "fsm",
          "scenario"
        ]
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
        "project" : "orders-api",
        "tags" : [
          "orders",
          "polyglot",
          "nested-payload"
        ]
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
        "project" : "identity-api",
        "tags" : [
          "users",
          "rest"
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
        "proxyBaseUrl" : "http://erp.internal:9000"
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
        "project" : "identity-api",
        "tags" : [
          "users",
          "parked"
        ]
      }
    }
  ],
  "requests" : [
    {
      "id" : "ede6fbc5-01ef-42e1-b416-c99fd1b66013",
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
        "loggedDate" : 1794813506869,
        "bodyAsBase64" : "eyJvcmRlcklkIjoiT1JELTk5NDIiLCJldmVudCI6Ik9SREVSX1NISVBQRUQifQ==",
        "body" : "{\"orderId\":\"ORD-9942\",\"event\":\"ORDER_SHIPPED\"}",
        "scheme" : "http",
        "host" : "localhost",
        "port" : 8080,
        "loggedDateString" : "2026-11-16T07:18:26.869Z",
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
      "id" : "e87b1f75-f3e6-4eba-aeea-fc38bfdd9689",
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
          "X-Casual-Execution" : "4410818c-e170-43c7-b899-1456aa8cdfb9",
          "X-Forwarded-For" : "127.0.0.1",
          "X-Casual-Parent-Span" : "SpanId{id=1ad37d13960ca471}"
        },
        "browserProxyRequest" : false,
        "loggedDate" : 1794813506732,
        "bodyAsBase64" : "eyJwaW5nIjoicG9uZyJ9",
        "body" : "{\"ping\":\"pong\"}",
        "scheme" : "http",
        "host" : "localhost",
        "port" : 8080,
        "loggedDateString" : "2026-11-16T07:18:26.732Z",
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
          },
          "project" : "Casual Gateway",
          "service" : "echoService",
          "protocol" : "casual",
          "tags" : [
            "casual",
            "xatmi",
            "echo"
          ]
        }
      },
      "sourceIp" : "127.0.0.1",
      "sourceHost" : "localhost"
    },
    {
      "id" : "c1608506-0c53-4c9f-8f43-ac37ab3f6c47",
      "request" : {
        "url" : "/api/v1/missing-route",
        "absoluteUrl" : "http://localhost:8080/api/v1/missing-route",
        "method" : "GET",
        "clientIp" : "127.0.0.1",
        "headers" : {
          "Host" : "localhost:8080",
          "User-Agent" : "Java-http-client/21.0.6",
          "X-Correlation-Id" : "corr-missing-404",
          "X-Forwarded-For" : "127.0.0.1"
        },
        "cookies" : { },
        "browserProxyRequest" : false,
        "loggedDate" : 1794813506267,
        "bodyAsBase64" : "",
        "body" : "",
        "protocol" : "HTTP/1.1",
        "scheme" : "http",
        "host" : "localhost",
        "port" : 8080,
        "loggedDateString" : "2026-11-16T07:18:26.267Z",
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
          "timeOffsetNanos" : 12319900,
          "data" : {
            "status" : 404,
            "contentType" : "text/plain",
            "report" : "\r\n                                               Request was not matched\r\n                                               =======================\r\n\r\n-----------------------------------------------------------------------------------------------------------------------\r\n| Closest stub                                             | Request                                                  |\r\n-----------------------------------------------------------------------------------------------------------------------\r\n                                                           |\r\nList Users                                                 |\r\n                                                           |\r\nGET                                                        | GET\r\n[path] /api/v1/users                                       | /api/v1/missing-route                               <<<<< URL does not match\r\n                                                           |\r\n                                                           |\r\n-----------------------------------------------------------------------------------------------------------------------\r\n"
          }
        }
      ],
      "stubMapping" : {
        "id" : "a8a891f7-c5cf-4f43-b2ff-8646291693d3",
        "request" : {
          "method" : "ANY"
        },
        "response" : {
          "status" : 404,
          "fromConfiguredStub" : false
        },
        "uuid" : "a8a891f7-c5cf-4f43-b2ff-8646291693d3"
      },
      "sourceIp" : "127.0.0.1",
      "sourceHost" : "localhost"
    },
    {
      "id" : "181cc507-6ccc-449b-a4ae-f8657fafaa7b",
      "request" : {
        "url" : "/api/v1/orders/ORD-9942/status",
        "absoluteUrl" : "http://localhost:8080/api/v1/orders/ORD-9942/status",
        "method" : "GET",
        "clientIp" : "127.0.0.1",
        "headers" : {
          "Host" : "localhost:8080",
          "User-Agent" : "Java-http-client/21.0.6",
          "X-Correlation-Id" : "corr-order-fsm-303",
          "X-Forwarded-For" : "127.0.0.1"
        },
        "cookies" : { },
        "browserProxyRequest" : false,
        "loggedDate" : 1794813506239,
        "bodyAsBase64" : "",
        "body" : "",
        "protocol" : "HTTP/1.1",
        "scheme" : "http",
        "host" : "localhost",
        "port" : 8080,
        "loggedDateString" : "2026-11-16T07:18:26.239Z",
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
          "project" : "orders-api",
          "tags" : [
            "orders",
            "fsm",
            "scenario"
          ]
        }
      },
      "sourceIp" : "127.0.0.1",
      "sourceHost" : "localhost"
    },
    {
      "id" : "ed41404a-6fbe-4a91-bcc2-483027c44279",
      "request" : {
        "url" : "/api/v1/orders",
        "absoluteUrl" : "http://localhost:8080/api/v1/orders",
        "method" : "POST",
        "clientIp" : "127.0.0.1",
        "headers" : {
          "Content-Length" : "150",
          "Host" : "localhost:8080",
          "User-Agent" : "Java-http-client/21.0.6",
          "Content-Type" : "application/json",
          "X-Correlation-Id" : "corr-orders-202",
          "X-Forwarded-For" : "127.0.0.1"
        },
        "cookies" : { },
        "browserProxyRequest" : false,
        "loggedDate" : 1794813506215,
        "bodyAsBase64" : "eyJjdXN0b21lciI6Ik5vcmRpYyBSZXRhaWwgQUIiLCJvcmRlcklkIjoiT1JELTk5NDIiLCJyYXdEb2N1bWVudCI6IjxvcmRlclJlcXVlc3Q+PG9yZGVySWQ+T1JELTk5NDI8L29yZGVySWQ+PHByaW9yaXR5PkhJR0g8L3ByaW9yaXR5Pjwvb3JkZXJSZXF1ZXN0PiJ9",
        "body" : "{\"customer\":\"Nordic Retail AB\",\"orderId\":\"ORD-9942\",\"rawDocument\":\"<orderRequest><orderId>ORD-9942</orderId><priority>HIGH</priority></orderRequest>\"}",
        "protocol" : "HTTP/1.1",
        "scheme" : "http",
        "host" : "localhost",
        "port" : 8080,
        "loggedDateString" : "2026-11-16T07:18:26.215Z",
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
          "project" : "orders-api",
          "tags" : [
            "orders",
            "polyglot",
            "nested-payload"
          ]
        }
      },
      "sourceIp" : "127.0.0.1",
      "sourceHost" : "localhost"
    },
    {
      "id" : "b82501df-b445-4996-901f-170ec7d8fea2",
      "request" : {
        "url" : "/api/v1/users",
        "absoluteUrl" : "http://127.0.0.1:8081/api/v1/users",
        "method" : "GET",
        "clientIp" : "127.0.0.1",
        "headers" : {
          "Host" : "127.0.0.1:8081",
          "User-Agent" : "Java-http-client/21.0.6",
          "X-Correlation-Id" : "corr-users-101",
          "X-Forwarded-For" : "127.0.0.1"
        },
        "cookies" : { },
        "browserProxyRequest" : false,
        "loggedDate" : 1794813506038,
        "bodyAsBase64" : "",
        "body" : "",
        "protocol" : "HTTP/1.1",
        "scheme" : "http",
        "host" : "127.0.0.1",
        "port" : 8081,
        "loggedDateString" : "2026-11-16T07:18:26.038Z",
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
          "project" : "identity-api",
          "tags" : [
            "users",
            "rest"
          ]
        }
      },
      "sourceIp" : "127.0.0.1",
      "sourceHost" : "localhost"
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
            "project" : "orders-api",
            "tags" : [
              "orders",
              "fsm",
              "scenario"
            ]
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
            "project" : "orders-api",
            "tags" : [
              "orders",
              "fsm",
              "scenario"
            ]
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
        "bytesUpstream" : 152,
        "bytesDownstream" : 354,
        "impairmentsInjected" : 2,
        "normalTransfers" : 0,
        "impairedTransfers" : 1,
        "bytesUpstreamNormal" : 0,
        "bytesUpstreamImpaired" : 152,
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
      "id" : "in-memory:orders.notifications",
      "protocol" : "in-memory",
      "name" : "orders.notifications",
      "brokerId" : "local-broker",
      "messageCount" : 1,
      "metadata" : {
        "type" : "FIFO"
      }
    },
    {
      "id" : "kafka:orders.events",
      "protocol" : "kafka",
      "name" : "orders.events",
      "brokerId" : "local-kafka-sink",
      "messageCount" : 1,
      "metadata" : {
        "partitions" : 1,
        "type" : "TOPIC"
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
    "in-memory/orders.notifications" : [
      {
        "id" : "msg-inmem-9942",
        "correlationId" : "corr-async-9942",
        "replyTo" : "",
        "destination" : "orders.notifications",
        "protocol" : "in-memory",
        "key" : "ORD-9942",
        "payload" : "{\"orderId\":\"ORD-9942\",\"event\":\"ORDER_SHIPPED\"}",
        "headers" : { },
        "timestamp" : 1.7948135068671796E9,
        "size" : 46,
        "partition" : null,
        "offset" : null
      }
    ],
    "kafka/orders.events" : [
      {
        "id" : "7b3fb181-755f-482e-8a2e-f9751c152573",
        "correlationId" : "",
        "replyTo" : "",
        "destination" : "orders.events",
        "protocol" : "kafka",
        "key" : "ORD-9942",
        "payload" : "{\n  \"customer\" : \"Acme Corp\",\n  \"orderId\" : \"ORD-9942\",\n  \"legacyXmlInvoice\" : \"<?xml version=\\\"1.0\\\" encoding=\\\"UTF-8\\\"?><invoice id=\\\"INV-9942\\\"><total currency=\\\"EUR\\\">1250.00</total></invoice>\"\n}",
        "headers" : { },
        "timestamp" : 1.7948135071032684E9,
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
        "timestamp" : 1.7948135068334725E9,
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
        "timestamp" : 1.794813506850063E9,
        "size" : 62,
        "partition" : null,
        "offset" : null
      }
    ]
  }
};
