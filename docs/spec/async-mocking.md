# Asynchronous Messaging & Queue Mocking Specification

> **Implementation Module**: [`wiremock-extension-async`](../../wiremock-extension-async/README.md) *(Beta — single unified module housing the Kafka wire sink, AMQP 1.0 client/broker, optional `compileOnly` IBM MQ SVRCONN driver, HTTP-to-async trigger listener, and queue bridge)*

## 1. Overview & Core Philosophy

This specification establishes the architectural foundation for asynchronous event-driven and queue-based mocking in `wiremock-extensions`. It brings the full fidelity of WireMock Standalone—dynamic stub matching, templated response synthesis, fault injection, the Request Journal, and the Web Console UI—to asynchronous enterprise message brokers.

### Core Principles

1. **Zero Production Code Changes**: Systems under test interact with their standard queue and topic names. WireMock achieves isolation and hybrid integration via transparent connection routing and queue bridging, never by requiring test-specific headers or modified application code.
2. **Unified Stub & Journal Surfaces**: Async stubs live as first-class WireMock stubs in `mappings/` alongside HTTP and Casual XATMI definitions. Inbound and reply messages mediate directly into WireMock's core `ServeEvent` journal pipeline.
3. **Zero Standalone JVM Bloat**: WireMock never embeds heavyweight broker daemons (ZooKeeper, KRaft, RabbitMQ, Artemis). It operates strictly as a high-performance peer client or as an ultra-lightweight in-memory wire sink.
4. **Virtual-Thread Native**: Socket I/O and message dispatching are designed specifically for JDK 21 virtual threads, isolating blocking operations and preventing carrier-thread starvation.

---

## 2. Ubiquitous Domain Language

- **Async Message Stubbing**: Inbound queue/topic message consumption matching header and payload patterns, optionally producing outbound reply messages and logging the exchange into the WireMock journal.
- **Async Metadata Extension (`metadata.async`)**: The domain block encapsulating protocol, broker connection URI, channel, and ACK policy within standard WireMock stub metadata.
- **Synthetic Async `ServeEvent`**: The bidirectional translation of an inbound consumed message and its synthetic reply into WireMock's core `ServeEvent` / `LoggedRequest` journal pipeline (`CONSUME /__async/<protocol>/<channel>`).
- **Message Exchange Pattern (MEP)**: The structural interaction model:
  - `Request-Reply`: Inbound message triggers a correlated reply message published to an outbound destination or `reply-to` channel.
  - `Fire-and-Forget (Sink)`: Inbound message is consumed, matched, acknowledged, and recorded in the journal without producing an outbound message.
- **Async Queue Bridge**: An active forwarding conduit linking a test broker destination with an upstream broker destination, supporting live proxy forwarding and 1-click stub harvesting.

---

## 3. Stub Schema Specification

Async stubs are standard WireMock `StubMapping` documents. Transport configuration is housed under `metadata.async`, while message matching and payload generation leverage WireMock's existing `request` and `response` engines.

### 3.1. Request-Reply Stub Example

```json
{
  "name": "Process Payment Order",
  "request": {
    "method": "CONSUME",
    "urlPath": "/__async/amqp/payments.requests",
    "headers": {
      "x-currency": { "equalTo": "SEK" }
    },
    "bodyPatterns": [
      { "matchesJsonPath": "$.payment.amount" }
    ]
  },
  "response": {
    "status": 200,
    "headers": {
      "content-type": "application/json",
      "X-Async-Correlation-Id": "{{request.headers.X-Async-Correlation-Id}}",
      "X-Async-Reply-Channel": "{{request.headers.X-Async-Reply-To}}",
      "x-processed-by": "wiremock-async-engine"
    },
    "jsonBody": {
      "paymentId": "{{randomValue type='UUID'}}",
      "status": "APPROVED",
      "amount": "{{jsonPath request.body '$.payment.amount'}}"
    },
    "fixedDelayMilliseconds": 75,
    "transformers": ["response-template"]
  },
  "metadata": {
    "async": {
      "protocol": "amqp",
      "broker": "amqp://guest:guest@localhost:5672",
      "channel": "payments.requests",
      "ackPolicy": "ack"
    }
  }
}
```

### 3.2. Fire-and-Forget (Sink) Stub Example

When an inbound event requires no reply message, the stub returns HTTP `204 (No Content)`:

```json
{
  "name": "Order Created Event Sink",
  "request": {
    "method": "CONSUME",
    "urlPath": "/__async/kafka/orders.events",
    "bodyPatterns": [
      { "matchesJsonPath": "$.orderId" }
    ]
  },
  "response": {
    "status": 204,
    "headers": {
      "X-Async-Ack": "ack"
    }
  },
  "metadata": {
    "async": {
      "protocol": "kafka",
      "broker": "kafka://localhost:9092",
      "channel": "orders.events",
      "ackPolicy": "ack"
    }
  }
}
```

### 3.3. HTTP-to-Queue Hybrid Trigger Example

Synchronous HTTP calls (e.g. `POST /api/orders`) can trigger asynchronous queue messages via `metadata.async.produce`:

```json
{
  "name": "Submit Order HTTP with Async Callback",
  "request": {
    "method": "POST",
    "urlPath": "/api/orders",
    "bodyPatterns": [{ "matchesJsonPath": "$.customerId" }]
  },
  "response": {
    "status": 202,
    "jsonBody": {
      "orderId": "{{randomValue type='UUID'}}",
      "status": "ACCEPTED"
    },
    "transformers": ["response-template"]
  },
  "metadata": {
    "async": {
      "produce": {
        "protocol": "kafka",
        "broker": "kafka://localhost:9092",
        "channel": "orders.events",
        "headers": {
          "x-event-type": "OrderSubmitted"
        },
        "jsonBody": {
          "orderId": "{{jsonPath response.body '$.orderId'}}",
          "customerId": "{{jsonPath request.body '$.customerId'}}",
          "timestamp": "{{now format='epoch'}}"
        },
        "delayMilliseconds": 150
      }
    }
  }
}
```

---

## 4. Synthetic `ServeEvent` Journal Mediation

Every consumed message generates a `ServeEvent` record:
- **`method`**: `"CONSUME"`
- **`url`**: `"/__async/<protocol>/<channel>"`
- **`headers`**: All wire application properties + standardized synthetic headers:
  - `X-Async-Protocol`: `"amqp" | "kafka" | "casual" | "ibmmq"`
  - `X-Async-Broker`: URI string (`"amqp://localhost:5672"`)
  - `X-Async-Channel`: Destination name (`"payments.requests"`)
  - `X-Async-Message-Id`: Broker or producer message identifier
  - `X-Async-Correlation-Id`: Correlation ID (if present)
- **`body`**: Raw payload (JSON, XML, or string; binary buffers fall back to safe Base64)

### Web Console Presentation
In the Web Console UI (`/__admin/ui`):
- **Request Journal Rows**: Render with `CONSUME` badges alongside protocol badges (`AMQP`, `KAFKA`, `CASUAL`, `IBMMQ`).
- **Drawer Left Pane**: Inbound message headers and payload, rendered in `PolyglotViewer` with syntax highlighting, recursive auto-unnesting, and `#modal-document-workspace` drill-down.
- **Drawer Right Pane**: Produced reply headers and payload, or `SINK (Fire-and-Forget) - ACKED` status badge.

---

## 5. Architectural Topologies & Hybrid Mocking

### 5.1. Topology 1: Dual-Broker / Virtual-Host Bridge (Zero-Bloat)

Solves the competing-consumer problem across Kafka, IBM MQ, and RabbitMQ without modifying application code:

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│  System A (SUT)             WireMock Extensions                     System B (Upstream)│
│  [orders.inbound]          [Zero-Bloat Client Bridge]              [orders.inbound]    │
│         │                                                                   ▲          │
│         ▼                                                                   │          │
│  ┌──────────────┐         ┌─────────────────────────┐                ┌──────────────┐  │
│  │ Test Broker  │────────▶│ WireMock Async Consumer │                │ Real Broker  │  │
│  │ (or VHost A) │         │   - Matches Stub?       │                │ (or VHost B) │  │
│  └──────────────┘         │     YES ➔ Mock Reply    │                └──────────────┘  │
│                           │     NO  ➔ Bridge Forward│───────────────────────┘          │
│                           └─────────────────────────┘                                  │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

1. **Test Broker**: System A connects to the test broker (or Virtual Host `/test`).
2. **Real Broker**: System B connects to the upstream broker (or Virtual Host `/prod`).
3. **Queue Names Identical**: Both systems interact with `orders.inbound` and `orders.responses`.
4. **Mock vs. Proxy**:
   - When a mock stub matches, WireMock consumes the message and replies to System A. System B never sees the message.
   - When configured as a proxy bridge rule in `metadata.async` (`"mode": "PROXY"` with `channelPattern`, `proxyChannel`, and `proxyBroker`), WireMock rewrites and forwards the message across the bridge to System B, records a `🔀 BRIDGED` (`PROXIED`) `ServeEvent` (or `502` `ASYNC_PROXY_ERROR` on upstream failure), and supports 1-click offline stub harvesting.

### 5.2. Topology 2: In-Process Netty Gateways

For protocols with clean wire framing, WireMock hosts in-process Netty TCP endpoints:
- **Casual Queue** (`wiremock-extension-casual`): Port 7772, intercepting `ENQUEUE (6100)` and `DEQUEUE (6200)` frames.
- **In-Process Kafka Sink & AMQP 1.0** (`wiremock-extension-async`): Accepts native Kafka producer wire connections (`bootstrap.servers=localhost:9092`) and AMQP 1.0 client links directly inside the WireMock process.

---

## 6. Protocol Drivers & Concurrency Architecture

All async protocol drivers, in-process wire sinks, and queue bridges are packaged in the single **`wiremock-extension-async`** module (except Casual Queues, which live in `wiremock-extension-casual`).

### 6.1. AMQP 1.0 Driver (inside `wiremock-extension-async`)
- **Engine**: Apache Qpid ProtonJ2 (`protonj2-client`).
- **Footprint**: < 1.0 MB incremental JAR size (shares Netty 4 transport with `wiremock-extension-casual`).
- **Concurrency**: Fully non-blocking; virtual threads unmount cleanly during lock waits without carrier-thread pinning.
- **Brokers Supported**: ActiveMQ Artemis, RabbitMQ (with AMQP 1.0 plugin), Azure Service Bus, IBM MQ 9.3+ LTS.

### 6.2. Apache Kafka Driver (inside `wiremock-extension-async`)
- **Engine**: Official `org.apache.kafka:kafka-clients`.
- **Dedicated Poller Pattern**:
  - `KafkaConsumer.poll()` runs on a dedicated platform thread to safeguard consumer group heartbeats and prevent carrier-thread pinning from internal `synchronized` blocks.
  - Consumed record batches are dispatched immediately to unbounded virtual worker threads (`Thread.ofVirtual().start(...)`) for stub matching, journal recording, and reply publishing via thread-safe `KafkaProducer`.

### 6.3. In-Process Kafka Wire Sink (inside `wiremock-extension-async`)
- **Engine**: Pure Netty ByteBuf frame codec (< 2 MB memory overhead).
- **Wire APIs Supported**:
  - `ApiVersions (18)`: Version negotiation with KIP-511 compliant v0 header.
  - `Metadata (3)`: Reports WireMock node 0 as leader for requested topics and partitions.
  - `InitProducerId (22)`: Satisfies Kafka 3.x+ default idempotence requirements (`enable.idempotence=true`).
  - `Produce (0)`: Ingests `RecordBatch v2` with Castagnoli CRC32C validation.

### 6.4. Casual Queue (`wiremock-extension-casual`)
- **Engine**: Upstream `casualcore/casual-java` integration.
- **Features**: Direct handling of `ENQUEUE_REQUEST (6100)` and `DEQUEUE_REQUEST (6200)` with full XA 2PC transaction coordinator enlistment.

### 6.5. IBM MQ Connectivity Strategy (Optional `compileOnly` + `fatJarIbmMq`)
- **Tier 1 (Built-In Default)**: Connects to modern IBM MQ 9.3+ LTS via the pure AMQP 1.0 driver (`amqp://host:5672/DEV.QUEUE.1`) with zero proprietary dependencies.
- **Tier 2 (Optional Native SVRCONN)**: `wiremock-extension-async` declares `com.ibm.mq:com.ibm.mq.allclient` as `compileOnly` and auto-registers `IbmMqMessageBroker` (`protocol = "ibmmq"`) via reflection when `com.ibm.mq.jms.MQConnectionFactory` is present on the classpath. In `wiremock-extension-bundle`, `./gradlew fatJar` builds the lean standalone JAR without IBM MQ libraries, while `./gradlew fatJarIbmMq` builds the standalone JAR with IBM MQ bundled.

---

## 7. Dynamic Connection Manager & Broker Registry

- **Broker Registry (`AsyncBrokerRegistry`)**: Manages remote cluster connection coordinates, credentials, and client pools via `/__admin/async/connections`, decoupling broker infrastructure from stub mapping files.
- **URI-Based Reference Counting**: Connection leases are tracked by normalized broker URI with idle cooldown cleanup and exponential backoff reconnects on virtual threads.

---

## 8. Observability & Control Plane

- **Synthetic `ServeEvent` Pipeline**: All consumed, published, bridged, and failed (`502`) async exchanges flow through WireMock's standard Request Journal (`/__admin/requests`, `/__admin/ui/api/requests`) and `wiremock-extension-otel` request metrics (`wiremock_requests_total` and latency histograms) with W3C `traceparent` propagation.
- **Admin API**: Runtime status, Kafka sink control, synthetic publishing, broker connections, and destination browsing/purging are exposed under `/__admin/async/{status,publish,sinks/kafka,connections,destinations}` (see [`wiremock-extension-async/README.md`](../../wiremock-extension-async/README.md) for full examples and schemas).
