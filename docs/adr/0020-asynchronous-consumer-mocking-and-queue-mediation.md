# 20. Asynchronous Consumer Mocking, Queue Mediation, and Resilient Bridging

Date: 2026-11-09

## Status

Accepted

## Context

While ADR 0009 established the foundation for asynchronous messaging, queue bridging, and producer sinks (`KafkaWireSink`, `AsyncMessagePublishingListener`), enterprise systems under test (SUT) spend equal—if not greater—time acting as **message consumers**:
- Framework-managed consumer groups (`@KafkaListener`, Quarkus SmallRye `@Incoming`, `consumer.poll(...)`).
- Enterprise JMS / AMQP 1.0 message listeners (`@JmsListener`, Spring AMQP, Qpid JMS).
- Classic IBM MQ clients communicating over proprietary binary TCP channels (`SVRCONN` port 1414).

Supporting the **SUT as a Consumer** introduces distinct architectural challenges:
1. **Kafka Consumer Protocol Complexity**: Unlike producers that merely send `PRODUCE` batches, Kafka consumers require partition offset queries (`LIST_OFFSETS`), record batch streaming (`FETCH`), and multi-step Consumer Group coordination (`FIND_COORDINATOR`, `JOIN_GROUP`, `SYNC_GROUP`, `HEARTBEAT`, `OFFSET_COMMIT`). Without minimal group coordination, enterprise container frameworks fail during initialization.
2. **AMQP 1.0 Credit Flow & Settlement**: AMQP 1.0 is credit-governed. A mock server must respect link credits (`Flow`) to avoid buffer overflows, and must track consumer settlement (`Disposition` frames: `Accepted`, `Rejected`, `Released`) to verify delivery acknowledgments and dead-letter queue (DLQ) routing.
3. **Classic IBM MQ Client Compatibility**: Millions of enterprise workloads use IBM MQ's proprietary binary Transmission Segment Header (TSH) wire protocol on port 1414 (`com.ibm.mq.allclient`, C/C++ MQI, Python `pymqi`, COBOL). Reverse-engineering IBM's proprietary framing in-process is fragile and incurs severe maintenance costs, while embedding proprietary IBM client JARs in WireMock Standalone violates open-source redistribution terms.
4. **The Competing Consumer Race Condition**: On point-to-point queues (IBM MQ, AMQP queues), having both the SUT and live upstream systems listening on the same physical queue causes message stealing, preventing deterministic test verification.
5. **Silent Asynchronous Failures**: In messaging, broken bridges, missing target queues, or unreachable brokers fail silently or get swallowed into connection loops, leaving developers with zero observability into why a message was not delivered.

## Decision

We extend `wiremock-extension-async` with a unified consumer mocking, mediation, and resilient bridging architecture:

### 1. In-Process Wire Servers (Zero-Container Consumer Testing)

WireMock hosts lightweight in-process TCP servers enabling standard client libraries to connect without Docker or external broker daemons:

1. **In-Process Kafka Consumer Engine**:
   - Promotes `org.apache.kafka:kafka-clients` to an implementation dependency, leveraging its robust, zero-copy `MemoryRecordsBuilder` and request/response protocol schemas.
   - Implements `FETCH` (API 1) and `LIST_OFFSETS` (API 2) to stream staged and triggered mock record batches to consumers.
   - Implements a lightweight in-memory **Consumer Group Coordinator** (`FIND_COORDINATOR` 10, `JOIN_GROUP` 11, `SYNC_GROUP` 14, `HEARTBEAT` 12, `OFFSET_COMMIT` 8) with single-leader auto-assignment, allowing standard `@KafkaListener` containers to boot and consume mock records out of the box.
2. **In-Process AMQP 1.0 Server (`AmqpServer`)**:
   - Built on the official Apache Qpid `protonj2` protocol engine (~1.2MB, Netty-native) listening on port 5672.
   - Manages connection lifecycle (SASL anonymous, `Open`, `Begin`, `Attach`).
   - Enforces credit-based flow control (`linkCredit`), dispatching mock `Transfer` messages only when credits are available.
   - Captures `Disposition` frames (`Accepted`, `Rejected`, `Released`, `Modified`), recording consumer acknowledgments in the Request Journal.

### 2. Live Queue Mediation Bridge (Native IBM MQ & Central Brokers)

To support native clients using proprietary protocols (e.g. IBM MQ port 1414) without proprietary JAR bloat or protocol reverse-engineering, WireMock employs the **Queue Mediation Bridge** pattern:

```
[ SUT Native Client ] ──(Port 1414 / SVRCONN)──> [ IBM MQ Developer QM ]
                                                        │
                                                        │ (Port 5672 / AMQP 1.0)
                                                        ▼
                                            [ WireMock Standalone ]
                                            (AsyncEngine & Journal)
```

1. The SUT connects natively to an ephemeral or shared IBM MQ queue manager over port 1414 using standard IBM drivers.
2. WireMock connects to the **same queue manager via its standard AMQP 1.0 channel (`SYSTEM.AMQP.SERVICE` on port 5672)** using Apache Qpid Proton-J2.
3. WireMock acts as the mock consumer on request queues, matches stubs, records exchanges into the Request Journal, and posts correlated replies onto reply queues.
4. **Competing Consumer Elimination**:
   - **Multi-Broker Topology**: SUT connects to Local Broker A; live upstream connects to Remote Broker B. WireMock bridges across A and B.
   - **Single-Broker Topology (Shadow Queues)**: SUT addresses a virtual shadow queue (e.g. `DEV.QUEUE.1.SUT_STUB`), and WireMock's bridge rewrites it to the canonical queue (`DEV.QUEUE.1`) for upstream delivery.

### 3. Priority-Based Bridging DSL with Wildcard Routing

Async bridging adopts the exact mental model of WireMock's HTTP `proxyBaseUrl`:

1. **High-Priority Stubs (`priority: 1-9`)**: Match specific queue names and payload patterns (JSONPath, regex) and return offline synthetic responses.
2. **Low-Priority Bridging Rules (`priority: 10+`)**: Declare wildcard destination patterns and upstream targets (`proxyBroker` / `proxyChannel`):
   ```json
   {
     "priority": 10,
     "name": "Orders Queue Fallback Bridge",
     "metadata": {
       "async": {
         "channelPattern": "orders.(.*)",
         "proxyBroker": "amqp://central-broker:5672",
         "proxyChannel": "upstream-orders-$1"
       }
     }
   }
   ```
   Unmatched messages automatically stream through the bridge, recording live traffic and enabling 1-click snapshotting into offline stubs.

### 4. Consumer Feed Stub Specification (`metadata.async.feed`)

To declaratively mock messages waiting to be consumed by the SUT:

```json
{
  "name": "Staged Pending Orders Feed",
  "metadata": {
    "async": {
      "feed": {
        "destination": "orders.events",
        "protocol": "kafka",
        "trigger": "on-subscribe",
        "delayMs": 50,
        "records": [
          {
            "key": "ord-101",
            "payload": "{\"orderId\":\"ORD-101\",\"amount\":99.50}",
            "headers": {
              "x-event-type": "OrderCreated"
            }
          }
        ]
      }
    }
  }
}
```

Supported triggers:
- `on-subscribe`: Dispatched as soon as a consumer establishes a link or assigns a partition.
- `immediate`: Pre-queued into the topic before consumer arrival.
- `call-triggered`: Dispatched causally following an HTTP call via `metadata.async.produce`.

### 5. Four-Pillar Troubleshooting & Failure Semantics

When bridging fails (e.g. target queue missing, broker outage, authentication failure):

1. **Synthetic `ServeEvent` (502 Bad Gateway)**:
   - Method: `CONSUME`, URL: `/__async/<protocol>/<channel>`.
   - Status: `502 Bad Gateway` (`traffic_type: "proxy_error"`).
   - Headers: `X-Async-Error: QUEUE_NOT_FOUND`, `X-Async-Target: amqp://central:5672/MISSING.QUEUE`.
   - Response Body: Clear diagnostic root cause with preserved original payload.
2. **Web Console UI**:
   - Highlighted in the Request Journal with an amber `⚠️ PROXY_ERROR` badge.
   - Diagnostic inspector displays source message, target broker, root cause exception, and offers **"🚀 Retry Forward"** and **"📸 Snapshot into Mock"** actions.
3. **Structured Single-Line JSON Logging**:
   - Emits structured log with MDC diagnostic context: `error_type="ASYNC_PROXY_ERROR"`, `source_channel`, `target_channel`, `broker_url`.
4. **OpenTelemetry Observability**:
   - Span status set to `StatusCode.ERROR` with event `queue.missing`.
   - Metrics recorded under `wiremock_requests_total{protocol="amqp", traffic_type="proxy_error", http_response_status_code="502"}`.

## Consequences

- **Complete Consumer Emulation**: Enterprise applications running `@KafkaListener`, Spring AMQP, or JMS test full consumer lifecycles without embedded broker daemons.
- **Zero-Friction IBM MQ Testing**: Native IBM MQ clients achieve 100% wire-protocol fidelity via developer container mediation with zero proprietary IBM JARs in WireMock.
- **Zero Competing Consumer Conflicts**: Queue rewriting and dual-broker topologies allow safe parallel test execution on shared enterprise queue managers.
- **Unified Observability & Fast Diagnosis**: Missing queues and broken bridges produce clear 502 journal events and visual UI diagnostics instead of silent drops.
