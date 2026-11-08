# 9. Asynchronous Messaging, Queue Mocking, and Protocol Mediation

Date: 2026-11-08

## Status

Accepted

## Context

WireMock Standalone excels at HTTP request/response mocking and, via `wiremock-extension-casual`, binary synchronous XATMI RPC. However, modern distributed architectures rely heavily on asynchronous event-driven messaging and enterprise message queues (AMQP 1.0, Apache Kafka, Casual Queue, IBM MQ).

Testing systems that interact with message brokers introduces several acute challenges:
1. **The Competing Consumer Problem**: In shared test brokers, if WireMock and the System Under Test's upstream dependency (System B) both listen to the same queue, messages are arbitrarily round-robined, preventing deterministic mocking.
2. **Heavy Broker Dependencies**: Traditional solutions require running full broker daemons (ZooKeeper/KRaft, Kafka, RabbitMQ, ActiveMQ Artemis), consuming significant memory, disk, and CPU in test environments.
3. **DSL & Storage Fragmentation**: Creating a separate queue-stub file hierarchy or parallel journal subsystem fragments the developer experience, breaking existing Web Console search, filtering, and payload unnesting.
4. **Virtual Thread Pinning**: Legacy JMS clients (e.g. IBM MQ) and Kafka consumer pollers use internal `synchronized` blocks that pin Java 21 carrier threads during blocking socket I/O.
5. **Hybrid HTTP/Async Enterprise Flows**: Bespoke enterprise patterns often involve sending an HTTP request (receiving a `202 Accepted` or `204 No Content`), followed by an asynchronous event emitted onto a broker queue.

## Decision

We introduce an asynchronous messaging and queue mocking architecture across `wiremock-extensions`:

1. **Self-Contained Stub DSL (`metadata.async`)**:
   - Async stubs are first-class WireMock stubs co-located in `mappings/` alongside HTTP and Casual stubs.
   - Matching uses standard WireMock `request` blocks (`method: "CONSUME"`, `urlPath: "/__async/<protocol>/<channel>"`, and standard `bodyPatterns` and `headers`).
   - Responses support two primary Message Exchange Patterns (MEPs):
     - **Request-Reply**: `status: 200`, `jsonBody`/`body`, `headers` (with `X-Async-Reply-Channel` and `X-Async-Correlation-Id`), Handlebars templating (`{{jsonPath ...}}`), and native `fixedDelayMilliseconds`.
     - **Fire-and-Forget Sink**: `status: 204`, with `headers: { "X-Async-Ack": "ack" | "nack" | "reject" }`.
   - Broker coordinates, channels, ACK policies, and proxy modes are self-contained in `metadata.async`, requiring zero global server pre-configuration.

2. **Synthetic `ServeEvent` Journal Mediation**:
   - Inbound consumed messages and outbound synthetic replies are translated into synthetic `ServeEvent` records.
   - In the Web Console Request Journal (`/__admin/ui`), async events render with `CONSUME` badges alongside protocol badges (`AMQP 1.0`, `Kafka`, `Casual Queue`, `IBM MQ`), full text search, and bidirectional payload unnesting in the Canonical Payload Surface.

3. **Dual-Topology Hybrid Mocking (Zero Production Code Changes)**:
   - **Topology 1: Dual-Broker / Virtual-Host Bridge**:
     - System A points to a Test Broker/VHost; System B points to an Upstream Broker/VHost. Queue names remain 100% identical on both sides.
     - WireMock acts as an intelligent client bridge: serving mock responses on match, or transparently forwarding across the bridge to System B in proxy mode while harvesting stubs into the catalog.
   - **Topology 2: In-Process Netty Gateways**:
     - For Casual (port 7772), pure AMQP 1.0 (port 5672), and in-process Kafka Sinks (port 9092), WireMock accepts direct TCP connections in-memory with zero external broker daemons.

4. **In-Process Kafka Event Sink**:
   - For event publishing flows (System A emits events to Kafka), WireMock hosts a zero-daemon Netty pipeline (< 500 lines Java 21, < 2 MB memory) handling `ApiVersions` (18), `Metadata` (3), `InitProducerId` (22), and `Produce` (0) frames. Standard `KafkaProducer` clients connect out of the box with zero external broker setup.

5. **Decoupled Protocol Drivers & Virtual Thread Concurrency**:
   - **AMQP 1.0**: Apache Qpid ProtonJ2 (`protonj2-client:1.4.0`), < 1 MB incremental footprint, unpinned on virtual threads.
   - **Kafka**: Official `kafka-clients:3.9.0` with a dedicated platform-thread poller safeguarding consumer group heartbeats while dispatching record processing to virtual worker threads.
   - **Casual Queue**: Full integration with `casual-java` 3.4.0 queue wire framing (`ENQUEUE 6100`, `DEQUEUE 6200`) and 2PC XA transaction enlistment.
   - **IBM MQ**: Pure AMQP 1.0 driver out of the box for MQ 9.3+ LTS; pluggable decoupled `wiremock-extension-ibmmq` adapter on platform threads for legacy queue managers, z/OS mainframes, and `SVRCONN` channels.

6. **HTTP-to-Queue Asynchronous Triggering**:
   - A dedicated `ServeEventListener` (`AsyncMessagePublishingListener`) detects `metadata.async.produce` in HTTP stub definitions, dispatching asynchronous events to broker destinations after returning immediate HTTP responses.

7. **AsyncAPI v3 Operations Inversion**:
   - Importer parses AsyncAPI v3 YAML/JSON and inverts operations to model WireMock as the mock peer (client `send` ➔ WireMock `CONSUME` stub; client `receive` ➔ WireMock reply/event publisher).

8. **OpenTelemetry & Observability**:
   - Connection pool states, reconnect counters, and message metrics are exported to Prometheus via `wiremock-extension-otel`. W3C `traceparent` contexts propagate bidirectionally across message headers.

## Consequences

- **Zero Production Code Changes**: Test suites run against identical queue and topic names without modifying application source code.
- **Unified Observability**: All HTTP, Casual XATMI, and Async Queue exchanges are visible, searchable, and editable in a single consolidated Web Console UI.
- **Zero JVM Bloat**: Standalone distributions remain lightweight by avoiding embedded broker daemons, while providing instant in-memory sinks where protocol simplicity permits.
- **Full Test Pyramid Flexibility**: Supports isolated unit/component tests (in-process mocks) and full end-to-end integration tests (transparent queue bridges) within the same environment.
