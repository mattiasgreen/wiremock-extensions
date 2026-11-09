# 21. Casual 1.9 Queue Protocol Gateway, State Machine, and XA Mediation

Date: 2026-11-09

## Status

Accepted

## Context

Casual 1.9 (`casualcore/casual`) provides a first-class, lightweight distributed queue subsystem (`casual-queue`) designed for asynchronous message queuing, guaranteed delivery, and transaction-coordinated workflows. Key capabilities of Casual Queue 1.9 include:
1. **Dedicated Wire Protocol**: Communicates over native TCP using dedicated binary network frames (`ENQUEUE_REQUEST` 6100, `ENQUEUE_REPLY` 6101/6102, `DEQUEUE_REQUEST` 6200, `DEQUEUE_REPLY` 6201/6202) and advertises queue metadata during domain discovery (`DOMAIN_DISCOVERY_REQUEST` 7300 / `DOMAIN_DISCOVERY_REPLY` 7301/7311).
2. **Built-in Error Queues & Retries**: Every queue `<queue>` automatically pairs with an error queue `<queue>.error`. If a message is rolled back more than `retry.count` times, it is automatically moved to `<queue>.error` after a configurable `retry.delay` backoff period.
3. **Deep 2PC / XA Coordination**: Enqueuing and dequeuing within an active transaction (`trid` / `Xid`) transitions messages through three explicit states: `enqueued` (uncommitted, hidden from consumers), `committed` (available for dequeue), and `dequeued` (locked until transaction commit or rollback).
4. **Message Attribute & Regex Selectors**: Messages carry execution UUIDs, correlation properties, reply queues, and availability timestamps. Dequeuers can filter messages using regex properties selectors or specific message IDs.

### The Current Gap in WireMock

In `wiremock-extension-casual`:
- `CasualServer` only dispatches XATMI service calls (`SERVICE_CALL_REQUEST` 1000/1002). If an SUT client attempts to enqueue or dequeue over port 7772, `CasualServer` logs an unsupported message error and immediately terminates the TCP connection.
- `handleDomainDiscovery` hardcodes `reply.setQueues(Collections.emptyList())`, preventing client domains from discovering mock queues.
- `CasualClient` has no methods for outbound queue operations (`enqueue`, `dequeue`, `discoverQueues`).
- While `wiremock-extension-async` conceptually models queue bridging, it has no native seam into Casual's queue subsystem.

Crucially, our upstream dependency `se.laz.casual:casual-network-protocol` (3.4.0+) **already contains** the compiled message structures, encoders, and decoders for enqueue and dequeue frames. The missing layer is WireMock server dispatching, HTTP stub mediation, and stateful queue storage.

## Decision

We introduce native Casual 1.9 Queue Protocol Gateway support across `wiremock-extension-casual` and `wiremock-extension-async`:

### 1. Inbound Netty Frame Dispatching (`CasualServer`)

1. **`QueueHandler` Seam**:
   - Introduce `@FunctionalInterface QueueHandler` on `CasualServer`.
   - Dispatch `ENQUEUE_REQUEST` (6100) and `DEQUEUE_REQUEST` (6200) frames to the queue handler instead of dropping the channel.
2. **Queue Discovery**:
   - Update `handleDomainDiscovery` (7300/7301) to query registered mock and forwarded queues, populating the reply with `QueueInfo` objects containing queue names, retry counts, retry delays, and enable flags (`enable.enqueue`, `enable.dequeue`).

### 2. Synthetic HTTP Probe Mediation (`CasualHttpMediation`)

Queue operations translate into synthetic HTTP probe requests within WireMock's core matching and journal pipeline:

- **Enqueue Probe**:
  - `POST /__casual/queues/<queueName>/enqueue`
  - Headers: `X-Casual-Queue`, `X-Casual-Correlation-Properties`, `X-Casual-Reply-Queue`, `X-Casual-Execution`, `X-Casual-Xid`, `X-Casual-Buffer-Type`
  - Body: Serialized payload buffer (JSON, Fielded, or Binary)
  - Synthetic Status: `200 OK` (with returned message UUID) or `500 Queue Error`
- **Dequeue Probe**:
  - `POST /__casual/queues/<queueName>/dequeue`
  - Headers: `X-Casual-Queue`, `X-Casual-Selector-Properties`, `X-Casual-Selector-Id`, `X-Casual-Block`, `X-Casual-Xid`
  - Synthetic Status: `200 OK` (with dequeued payload and message metadata) or `204 No Content` (`no_message`)

Each enqueue and dequeue produces a `ServeEvent`, enabling real-time search, payload unnesting, and inspection in the Web Console UI (`/__admin/ui`).

### 3. Stateful In-Memory Queue Store (`CasualQueueStore`)

`wiremock-extension-casual` hosts an in-memory, thread-safe queue store managing queue state lifecycles:
1. **Message States**:
   - `ENQUEUED` (uncommitted, visible only within the originating transaction).
   - `COMMITTED` (available for FIFO / selector dequeue).
   - `DEQUEUED` (locked by the dequeuing transaction).
2. **Dead-Letter Error Queue (`<queue>.error`)**:
   - Auto-created alongside every declared queue.
   - On transaction rollback, if `redelivered < retry.count`, the message is returned to `COMMITTED` with `available = now + retry.delay`.
   - If `redelivered >= retry.count`, the message is moved permanently to `<queue>.error`.
3. **2PC Transaction Enlistment**:
   - Enlists with `CasualTransactionCoordinator`:
     - On XA Commit: Enqueued messages transition to `COMMITTED`; dequeued messages are purged.
     - On XA Rollback: Enqueued messages are discarded; dequeued messages apply retry delay or DLQ transition.

### 4. Outbound Client Proxying (`CasualClient`)

- Extend `CasualClient` with `enqueue(String queue, QueueMessage msg, Xid xid)` and `dequeue(String queue, MessageSelector selector, boolean block, Xid xid)`.
- If an incoming queue operation does not match a local mock stub, WireMock proxies the enqueue/dequeue request across to the configured upstream Casual domain, harvesting the exchange for offline replay.

### 5. `wiremock-extension-async` Seam (`CasualMessageBroker`)

- Introduce `CasualMessageBroker` implementing `MessageBroker` in `wiremock-extension-async`.
- Allows `metadata.async.produce` directives with `"protocol": "casual"` to enqueue messages into Casual queues following synchronous HTTP responses.
- Allows `metadata.async.feed` stubs to pre-stage mock messages into Casual queues for SUT consumers.

## Consequences

- **100% Casual Protocol Completeness**: WireMock Standalone natively handles all Casual communication types—services (`tpcall`), 2PC transactions (XA), and queues (`enqueue`/`dequeue`)—over a single TCP port (7772).
- **Zero SUT Modifications**: SUT applications using `casual-queue` C APIs or `casual-jca` connectors interact seamlessly with WireMock without code changes.
- **Unified Observability**: Casual queue operations are visible alongside HTTP and Kafka/AMQP traffic in the Web Console Request Journal and Prometheus metrics.
- **Deterministic Failure Simulation**: Developers can test retry backoffs, rollback handling, and dead-letter queue behavior deterministically in local integration tests.
