# 23. Unified Multi-Protocol Tester & Dispatch Workbench

Date: 2026-11-10

## Status

Accepted

## Context

Previously, the WireMock Console Web UI (`wiremock-extension-ui`) featured two distinct, fragmented testing tabs:
1. **HTTP Tester** (`#tab-tester`): Focused exclusively on REST/HTTP requests, with headers presets, local storage persistent headers, and response status inspection.
2. **Casual Tester** (`#tab-casual`): A separate full-width tab dedicated to Casual XATMI `tpcall` invocations, buffer encoding selection, and ATMI error code banners.

With the addition of asynchronous messaging capabilities (`wiremock-extension-async`), developers need to interactively produce and test:
- **Kafka** topic messages (`PRODUCE`) with partition keys and record headers.
- **AMQP 1.0** messages (`PUBLISH`) with correlation IDs, reply-to routing, and application properties.

Maintaining distinct top-level tabs for each protocol (`HTTP Tester`, `Casual Tester`, `Kafka Tester`, `AMQP Tester`) would lead to severe navigation fragmentation, code duplication in payload editing and history tracking, and inconsistent user experience.

## Decision

We consolidate all protocol testing tools into a single, unified **Multi-Protocol Dispatch Workbench** (`#tab-tester`) powered by a **Protocol Segmented Control** (`[HTTP] [Kafka] [AMQP] [Casual]`):

1. **Morphing Address Bar**:
   - The address bar dynamically morphs its input controls to fit the selected protocol while maintaining a uniform, familiar dual-column workbench layout:
     - **HTTP**: Method selector (`GET`, `POST`, `PUT`, `DELETE`, `PATCH`, `HEAD`, `OPTIONS`), Request path input (`/api/v1/...`), and `🚀 Send Request`.
     - **Kafka**: Operation (`PRODUCE`), Topic name input (`orders.events`), Partition Key input (`ORD-101`), and `🚀 Produce Message`.
     - **AMQP**: Operation (`PUBLISH`), Destination/Queue input (`orders.inbound`), Correlation ID input (`test-corr-101`), Reply-To queue input (`orders.replies`), and `🚀 Publish Message`.
     - **Casual**: Operation (`CALL`, `ENQUEUE`, `DEQUEUE`), Host & Port inputs, Buffer Type dropdown (`JSON`, `FIELDED`, `CSTRING`, `X_OCTET`), Service/Queue name input, and `🔌 Execute Casual`.

2. **Shared Payload Editor (`DualLayerCodeEditor` & `PayloadLens`)**:
   - The rich payload editor is shared seamlessly across all protocols, providing format auto-detection (JSON, XML, text, hex bytes), payload nesting previews, and formatters.
   - Dynamic headers and properties drawers adjust per protocol (HTTP request headers, Kafka record headers, AMQP application properties, Casual XATMI call flags).

3. **Protocol-Native Settlement Inspector**:
   - The response inspection pane adapts to protocol outcomes:
     - HTTP: Status code pill (`200 OK`, `404 Not Found`), latency, response headers, and body lens.
     - Kafka: Settlement acknowledgment badge (`ACKNOWLEDGED: Partition 0 @ Offset 42`), duration, and publish metadata.
     - AMQP: Delivery settlement banner (`SETTLED: ACCEPTED`), correlation tracking, and automatic request-reply payload rendering when a reply queue message is received.
     - Casual: ATMI status code pill (`TPSUCCESS` / `TPESVCFAIL`), UserCode, transaction UUID, and reply buffer lens.

4. **Unified Execution History with Protocol Filters**:
   - A single unified history panel logs all dispatched requests across protocols.
   - Interactive filter chips (`[All] [HTTP] [Kafka] [AMQP] [Casual]`) allow filtering execution history.
   - Clicking any history card restores the protocol, address inputs, headers, and payload into the workbench.

5. **Always-Available Quick Templates**:
   - A quick templates dropdown in the address bar provides instantaneous pre-fills for HTTP, Kafka, AMQP, and Casual interactions in both live and demo mode.

6. **Backward Compatibility & Seams**:
   - Legacy routes (such as `#casual`) automatically activate the Tester tab with the Casual protocol pre-selected (`#tester?proto=casual`).
   - Dispatch seams:
     - HTTP: browser native `fetch()`.
     - Kafka & AMQP: browser `fetch('/__admin/async/publish', { method: 'POST', body: ... })`.
     - Casual: browser `fetch('/__admin/casual/call', { method: 'POST', body: ... })`.

## Consequences

- **Positive**: Eliminates tab sprawl; creates a coherent, unified developer testing experience across synchronous, asynchronous, and legacy RPC architectures.
- **Positive**: Reuses the high-density `DualLayerCodeEditor` and `PayloadLens` across all four protocol transports without code duplication.
- **Positive**: Simplifies end-to-end integration testing and automated verification.
