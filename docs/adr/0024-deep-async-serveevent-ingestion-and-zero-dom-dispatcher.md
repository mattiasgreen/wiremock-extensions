# 24. Deep Async ServeEvent Ingestion and Zero-DOM Protocol Dispatcher Seam

Date: 2026-11-11

## Status

Accepted

## Context

Following the introduction of asynchronous messaging (`wiremock-extension-async`) and the unified tester workbench (`wiremock-extension-ui`), an architectural review surfaced two friction points:

1. **Async Journal Fragmentation**:
   - `AsyncEngine` maintained a private, isolated in-memory `List<String> journal` recording raw log strings (e.g., `KAFKA_PRODUCE:...`, `AMQP_PUBLISH:...`).
   - WireMock's core Request Journal (`RequestJournalStore` / `getAllServeEvents()`) and the adaptive Zstandard journal compressor received zero events from real TCP wire sinks (`KafkaWireSink`, `AmqpServer`) or queue forwarding.
   - Test suites were forced to simulate HTTP POST calls to fake loopback URLs (`/__async/kafka/...`) to test console journal rendering.
   - Furthermore, `AsyncQueueBridge.java` lingered as a shallow 41-line passthrough wrapper forwarding directly to `AsyncEngine`.

2. **UI Protocol Dispatcher Shallowness & Leakage**:
   - `api.js` (intended as the Admin API client) accumulated 350+ lines of DOM scraping, timer measurement, spinner state toggling, and settlement banner rendering across Kafka, AMQP, Casual, and HTTP.
   - `tester.js` and `api.js` formed a bidirectional circular import, scattering DOM ownership and duplicating execution boilerplate.

## Decision

We deepened both modules through architectural restructuring:

1. **Deep `AsyncEngine` with Direct `ServeEvent` Ingestion Seam**:
   - `AsyncExtensionFactory` injects WireMock's core `RequestJournalStore` directly into `AsyncEngine` via `setJournalConsumer(RequestJournalStore::add)`.
   - Inbound messages from `KafkaWireSink` and `AmqpServer`, mock stub responses, and bridge forwarding cycles synthesize authentic `ServeEvent` instances via `AsyncServeEventMediation`.
   - Wire messages immediately and transparently flow through WireMock's Request Journal and the Zstandard compression pipeline, enabling full-text token search, cluster dictionary training, and anti-OOM headroom protection.
   - Deleted the redundant 41-line `AsyncQueueBridge.java` wrapper and retired the isolated `List<String> journal`.

2. **Zero-DOM Deep Module `dispatcher.js`**:
   - Extracted a dedicated ES6 module `dispatcher.js` exposing a single unified interface: `dispatchRequest(options) -> Promise<SettlementResult>`.
   - `dispatcher.js` contains zero references to DOM elements, window, or document. It standardizes protocol adapters for HTTP, Kafka, AMQP, and Casual into an immutable `SettlementResult` (`{ ok, status, statusText, elapsedMs, responseHeaders, responseBody, protocol, method, destination, settlement, banner }`).
   - All DOM scraping, button disablement, banner rendering, history persistence, and journal reloading are strictly localized inside `tester.js`.
   - Restored `api.js` to its singular responsibility as a pure Admin API client.

3. **`🔀 BRIDGED` Domain Verdict**:
   - Asynchronous messages forwarded across broker bridges are badged in the UI journal as `🔀 BRIDGED` (e.g. `Bridged to <targetQueue>`) with `pill-bridged` styling, while internally linking to the `proxied` verdict category so that 1-click snapshotting into offline stubs (`📸 Snapshot Stub`) continues to work seamlessly.

## Consequences

- **Positive**:
  - Tests verify authentic wire sink traffic through standard WireMock assertions (`server.getAllServeEvents()`) with zero fake HTTP POST workarounds.
  - Transparent Zstandard compression and dictionary training on async traffic.
  - Zero-DOM protocol dispatcher allows direct unit testing without DOM fixtures.
  - Eliminated shallow wrappers and circular module dependencies.
