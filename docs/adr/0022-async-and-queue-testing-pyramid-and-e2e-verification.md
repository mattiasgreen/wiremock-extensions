# 22. Multi-Protocol Async & Queue Testing Strategy, Pyramid, and E2E Playwright Verification

Date: 2026-11-09

## Status

Accepted

## Context

With the expansion of `wiremock-extension-async` and `wiremock-extension-casual` to cover:
- Kafka consumer group coordination and partition record batch fetching.
- AMQP 1.0 link credit flow control and settlement tracking.
- Classic IBM MQ developer mediation (Approach 2) and shadow queue rewriting.
- Casual 1.9 and legacy Casual (1.2, 1.3) queue protocol gateways (`ENQUEUE`/`DEQUEUE` 6100–6202).

We require an exhaustive testing strategy adhering to the test pyramid. The testing architecture must satisfy four essential requirements:
1. **Zero-Docker Portability**: Must execute cleanly in lightweight CI environments (GitHub Actions, restricted Kubernetes pods) without requiring Docker daemons or container management privileges.
2. **Fast Inner-Loop Developer Velocity**: Unit and codec tests must run in <1s; component tests in <3s.
3. **Real-Broker Fidelity**: High-level integration tests must prove interoperability against an authentic, independent AMQP 1.0 broker daemon, a real multi-node Casual topology, and real Kafka consumer group clients.
4. **End-to-End Journal & UI Observability**: Must verify that all asynchronous and Casual queue interactions are captured in the Request Journal and render with complete fidelity in the embedded Web Console UI (`/__admin/ui`), verified via headless Playwright browser automation.

## Decision

We establish a comprehensive, four-tiered testing architecture and E2E verification suite:

```
                  ┌──────────────────────────────┐
                  │    Tier 4: Playwright E2E    │
                  │   Console Journal Inspector  │
                  └──────────────┬───────────────┘
                                 │
                  ┌──────────────┴───────────────┐
                  │ Tier 3: Real Broker & Harness│
                  │  Embedded Artemis & Casual 3N│
                  └──────────────┬───────────────┘
                                 │
                  ┌──────────────┴───────────────┐
                  │    Tier 2: Component Tests   │
                  │   WireMockServer DynamicPort │
                  └──────────────┬───────────────┘
                                 │
                  ┌──────────────┴───────────────┐
                  │   Tier 1: Fast Codec Units   │
                  │   Netty Frames & Matchers    │
                  └──────────────────────────────┘
```

---

### 1. The Four-Tier Test Pyramid

#### Tier 1: Fast Codec & State Unit Tests (Sub-second, Inner Loop)
- **Scope**: Subproject `src/test/java` (`KafkaWireCodecTest`, `AmqpFlowControlTest`, `CasualQueueDecoderTest`, `AsyncEngineTest`).
- **Execution**: Pure in-memory unit tests; zero network sockets.
- **Verification**: ByteBuf decoding, CRC32C checksums, regex properties selectors, and stub priority ordering.

#### Tier 2: Single-Node Component Tests (1–3s, Inner Loop)
- **Scope**: Subproject `src/test/java` (`KafkaWireSinkTest`, `AmqpConsumerServerTest`, `CasualServerQueueTest`).
- **Execution**: WireMockServer started with `dynamicPort()`.
- **Verification**: Single-node client/server interactions using standard client libraries (`KafkaProducer`, `qpid-jms-client`, `se.laz.casual.network`).

#### Tier 3: Multi-Node Real Broker & Topology Suites (3–8s, Outer Loop)
1. **Embedded ActiveMQ Artemis (`testImplementation`)**:
   - Added strictly in test scope (`artemis-server` and `artemis-amqp-protocol`).
   - Starts an in-memory AMQP 1.0 broker on a dynamic TCP port in ~800ms.
   - Validates that WireMock's `AmqpMessageBroker` bridges traffic to and from a real, third-party enterprise AMQP 1.0 broker with authentic credit flow, queue durability, and routing.
2. **Casual Multi-Node Netty Harness (`CasualIntegrationTestHarness`)**:
   - Extended in `wiremock-extension-casual/src/quarkusTest/`.
   - Runs a 3-node topology: `ClientInitiatorNode` $\to$ `CasualWireMockGateway` $\to$ `UpstreamCasualNode`.
   - Validates 2PC XA transactional enqueue/dequeue, rollback redelivery delay (`retry.delay`), and transition to `<queue>.error`.
3. **Official `KafkaConsumer` Group Loop**:
   - Uses `org.apache.kafka:kafka-clients` with `group.id=test-group`.
   - Connects to WireMock's `KafkaWireSink`, performs partition assignment, calls `consumer.poll(...)`, asserts record receipt, and commits offsets.

#### Tier 4: Playwright E2E Request Journal Inspection (5–10s, UI Quality Gate)
- **Scope**: `wiremock-extension-ui/src/test/java` (`UiAsyncJournalPlaywrightTest`).
- **Execution**: Headless Chromium via Playwright inspecting the live Web Console (`/__admin/ui/#journal`).
- **Verification Matrix**:
  - **Protocol Badges**: Renders correct badges (`KAFKA`, `AMQP`, `CASUAL`).
  - **Method Badges**: Displays `CONSUME`, `ENQUEUE`, `DEQUEUE`.
  - **Verdict Badges**: Asserts visual styling for `📌 STUB` (mocked), `🌐 PROXIED` (forwarded to live broker), and `⚠️ PROXY_ERROR` (502 destination not found).
  - **Inspector Panels**: Expands journal rows to verify both **Request** tab (inbound payload, headers) and **Response** tab (outbound reply payload, status code, timing), format descriptors (`JSON`, `FIELDED`, `BINARY`), and Canonical Payload Surface auto-unnesting.

---

### 2. Multi-Version Casual Protocol Support & Matrix

WireMock's Casual Gateway explicitly tests and supports protocol version negotiation across client and server versions:

| Casual Version | Protocol Version | Enqueue Reply Frame | Dequeue Reply Frame | Transaction Context |
| :--- | :--- | :--- | :--- | :--- |
| **Casual 1.2 / Legacy** | `VERSION_1_2` | `ENQUEUE_REPLY` (`6101`) | `DEQUEUE_REPLY` (`6201`) | Inline `XID` inside frame header |
| **Casual 1.3** | `VERSION_1_3` | `ENQUEUE_REPLY_V_1_3` (`6102`) | `DEQUEUE_REPLY_V_1_3` (`6202`) | External `SpanId`, returns `QueueErrorCode` |
| **Casual 1.9 / Current**| `VERSION_1_4+`| `ENQUEUE_REPLY_V_1_3` (`6102`) | `DEQUEUE_REPLY_V_1_3` (`6202`) | Full 2PC state machine + `<queue>.error` |

`CasualIntegrationTestHarness` executes matrixed test cases with clients configured at `ProtocolVersion.VERSION_1_2` and `ProtocolVersion.VERSION_1_3+`, ensuring backward compatibility for legacy enterprise clients.

---

### 3. Comprehensive Troubleshooting Verification

To prevent silent failures in asynchronous testing, the test suite asserts all four pillars of the 502 troubleshooting specification:
1. **Missing Queue / Broker Down Assertion**: When a bridge target does not exist, test asserts HTTP status `502 Bad Gateway` on the synthetic `CONSUME` ServeEvent.
2. **Journal Detail Assertion**: Verifies `X-Async-Error: QUEUE_NOT_FOUND` header and detailed diagnostic body.
3. **UI Playwright Assertion**: Inspects the UI row for amber `⚠️ PROXY_ERROR` styling and verifies that the "🚀 Retry Forward" button is clickable.
4. **OTEL Metric Assertion**: Asserts `wiremock_requests_total{traffic_type="proxy_error", http_response_status_code="502"}` is incremented.

## Consequences

- **100% Deterministic & Portable**: Runs anywhere Java 21 runs with zero external Docker or OS-level dependencies.
- **Fast Developer Inner Loop**: Preserves sub-5s inner loop execution by running heavy multi-broker suites only in Tier 3/4.
- **Complete End-to-End Confidence**: Tests every layer from raw binary Netty bytes up to the final pixel rendered in the Web Console UI.
- **Enterprise Longevity**: Multi-version Casual protocol testing protects against regressions for legacy Casual 1.2/1.3 installations.
