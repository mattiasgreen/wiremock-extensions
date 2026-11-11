# Cross-Protocol Async Destination Explorer and Broker Connection Registry

## Context
As asynchronous messaging expands across Kafka, AMQP 1.0, Casual XATMI Queues, and in-memory channels, developers require visual observability into queues, topics, and staged messages without operating broker-specific CLI tools. At the same time, bridge topologies that forward test traffic to remote Kafka clusters, enterprise AMQP brokers (ActiveMQ Artemis, IBM MQ), or Casual domains need authenticated network connectivity without leaking infrastructure secrets into Git-committed stub mapping files.

## Decision

1. **Dedicated Web Console Surface (`📬 Queues & Topics`)**:
   Introduce a top-level tab in the Vanilla ES6 Console (`data-tab="tab-queues"`, `id="nav-tab-queues"`) with a two-pane layout: a sidebar destination tree filtered by protocol (`[All] [Kafka] [AMQP] [Casual] [In-Memory]`) and a main message explorer featuring real-time corpus-based search (`filter.js`), message inspection, inline metadata drawers, and safe destination purging.

2. **Declarative Broker Connection Registry & Template-Driven Ingestion**:
   Centralize external broker credentials, SASL/TLS coordinates, and bootstrap endpoints in an isolated configuration model (`connections.json` / `--async-connections=<file>`), managed in-process by `AsyncBrokerRegistry` and inspectable/exportable via `/__admin/async/connections`.
   - **Pure JSON Configuration Interface**: In the Web Console (`#modal-queues-brokers`), registration and import/export eschew rigid multi-field forms in favor of a declarative JSON editor with built-in protocol templates (`AMQP Artemis (Local)`, `AMQP Artemis (K8s / Secret)`, `Kafka (Local)`, `Kafka SASL / Plaintext`, `Casual Domain Gateway`).
   - **Environment Variable Secret Tokens (`${VAR_NAME}`)**: Passwords, SASL jaas configs, and connection tokens can be defined as `${VAR_NAME}` (e.g. `${AMQP_PASSWORD}`). The backend resolves `${VAR_NAME}` dynamically against the host/container environment (`System.getenv()`) at connection time while preserving declarative tokens during JSON export and UI inspection, enabling direct integration with Kubernetes Secrets and 12-factor application workflows without credential leakage. Plaintext passwords in export streams are masked with `******`.
   - **Batch & Upsert Semantics**: Supports single connection objects, array lists, or `{ "connections": [...] }` envelopes with idempotent upsert keyed by connection `id`. Stub mappings reference connections purely by ID (`"metadata": { "async": { "connection": "corp-kafka" } }`), keeping sensitive infrastructure configuration entirely out of stub definitions.

3. **Live Broker Catalog Discovery**:
   Populate the destination tree strictly through the **Live Broker Catalog** by querying connected broker instances directly (`AdminClient.listTopics()` for Kafka, `CasualClient.discoverQueues()` for Casual, and AMQP management addresses), coupled with local in-process sinks and queue stores. Eliminate static stub scraping and speculative runtime journal crawling to keep architectural complexity minimal.

4. **Synchronous Upstream Bridge Acknowledgment**:
   In bridge topologies, WireMock's protocol ingress blocks acknowledgment to the System Under Test until the forwarded message is acknowledged by the upstream remote broker (Kafka `ProducerRecord` confirmation, AMQP delivery settlement, or Casual enqueue reply). This avoids optimistic ack discrepancies and preserves deterministic test assertions, recording synthetic `502 Bad Gateway` events immediately upon upstream network or queue errors.

5. **Detached Non-Committing Kafka Inspection**:
   Peek recent messages from remote Kafka topics using an ephemeral, detached consumer with manual partition assignment and backwards seek from high-water marks (`seekToEnd - N`). Offsets are never committed, ensuring zero interference with production or SUT consumer group coordination.

6. **Compensating Queue Editing & Polyglot Payload Lens Integration**:
   Integrate message inspection and editing with the existing **Polyglot Payload Lens** (`#modal-payload-lens`):
   - **Local In-Memory / Staged Feeds**: Supports in-place payload and header updates.
   - **Remote Streams & Immutable Logs**: Formalize editing as a **Compensating Queue Edit** ("Modify & Re-Publish"), where the modified payload is published as a successor record (with optional original message cancellation or tombstoning).
   - Multi-layered nested unnesting (JSON, XML SOAP strings, Base64, fielded buffers) automatically re-serializes and cascades edits up to the root document.

7. **Isolated Remote Casual Inspection Seam**:
   Confine the interim remote Casual queue inspection mechanism (transactional `dequeue` followed by `rollback`) to an isolated adapter seam (`CasualRemoteQueueInspector`). This prevents protocol-specific workarounds from leaking into the Admin API or Web Console while establishing a clear transition point for upstream Casual non-destructive browse frames (`CASUAL_QUEUE_BROWSE`).

## Status
Accepted

## Consequences
- Developers inspect, search, and purge queues across all four enterprise protocols from a single unified UI surface.
- Infrastructure credentials and connection pools live in a central, declarative GitOps registry, decoupling environment setup from portable test stubs.
- Message inspection and editing on immutable streams (Kafka) adhere to log-native semantics ("Modify & Re-Publish") rather than corrupting wire guarantees.
- Zero NPM and zero new backend JAR dependencies are introduced (`org.apache.kafka:kafka-clients:3.9.0` and `org.apache.qpid:protonj2-client:1.4.0` are already in the classpath).
