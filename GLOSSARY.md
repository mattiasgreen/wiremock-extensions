# wiremock-extensions

Production extensions and developer tooling for WireMock Standalone, providing protocol mediation (Casual XATMI, Async Kafka/AMQP, OpenAPI), native Scenario workflows, L4/L7 network emulation, adaptive Zstd journaling, and an embedded Web UI and CLI.

## Language

### Casual Protocol & Mediation

**Casual Domain Gateway**:
An in-process Netty TCP server that speaks the binary Casual XATMI network protocol, routing inbound service calls and domain discovery either to local WireMock stubs or over native TCP to an upstream Casual domain.
_Avoid_: Casual proxy, Casual listener, XATMI adapter

**XATMI-to-HTTP Mediation**:
The bidirectional translation between Casual XATMI messages (`ServiceBuffer`, `ErrorState`, `TransactionState`, user return codes, XA vote overrides) and synthetic WireMock HTTP probes (`/ __casual/services/<name>`, `X-Casual-*` headers, HTTP status codes, and `ServeEvent` records).
_Avoid_: HTTP mapping, buffer conversion, protocol bridge

**Protocol Version Normalization**:
Adapting Casual network frames (`SERVICE_CALL_REQUEST`, `SERVICE_CALL_REPLY`, and Queue frames `ENQUEUE_REPLY` 6101 vs. `ENQUEUE_REPLY_V_1_3` 6102, `DEQUEUE_REPLY` 6201 vs. `DEQUEUE_REPLY_V_1_3` 6202) to match the negotiated `ProtocolVersion` (`1.0`/`1.2` with inline `XID` vs. `1.3+` with `SpanId` and `QueueErrorCode`) on a specific TCP connection.
_Avoid_: frame downgrading, version rebuilding

**2PC Transaction Coordinator**:
The module that tracks transactional `Xid` enlistment across upstream Casual domains and synthetic local XA vote overrides (`prepare`, `commit`, `rollback`), aggregating `XAReturnCode` outcomes across participants.
_Avoid_: XA bridge, transaction manager, Xid tracker

**Casual Buffer Descriptor**:
The composite domain descriptor coupling a Casual transport buffer type (`FIELDED`, `JSON`, `CSTRING`, `FLAT`, `X_OCTET`) with its encapsulated payload syntax (`JSON`, `XML`, `TEXT`, `HEX`) and optional non-UTF-8 character encoding (`ISO-8859-1`, `WINDOWS-1252`, `KOI8-R`), presenting transport wire constraints, character encoding, and inner document formatting as a unified inspectable badge.
_Avoid_: buffer label, casual type tag, payload info

**Schema-less Fielded Buffer Mediation**:
The bidirectional translation of Casual FML/FML32 (`.FIELDED` / `CFIELD/`) binary buffers into structured JSON without requiring pre-registered field definitions in a field table (`casual-fields.json`). Derives concrete wire data types directly from the 64-bit real field ID bitmask (`type = realId / 0x2000000`), synthesizes deterministic `FLD_<realId>` JSON properties, auto-unnests embedded binary documents, and enables bidirectional wire re-encoding for offline stubs.
_Avoid_: raw binary fallback, blind Base64 encoding, untyped fielded dump

**Casual Queue Mediation**:
The bidirectional translation of native Casual 1.9 queue wire frames (`ENQUEUE_REQUEST` 6100, `DEQUEUE_REQUEST` 6200) into synthetic WireMock `ServeEvent` probes (`/__casual/queues/<name>/enqueue`, `/__casual/queues/<name>/dequeue`), matching stubs on payload, properties regex selectors, and reply queues, while maintaining stateful retry delays, 2PC XA commit/rollback transitions, and automatic dead-letter error queues (`<name>.error`).
_Avoid_: casual queue proxy, XATMI queue adapter, casual queue listener

### Async Messaging & Queue Mocking

**Async Message Stubbing**:
Inbound queue, topic, or channel message consumption matching header and payload patterns, optionally producing outbound reply messages and logging the exchange into the WireMock journal.
_Avoid_: queue listener, message mock, async spy

**Async Metadata Extension**:
Encapsulating async messaging configuration (protocol, broker URI, channel, message pattern matchers, reply definition, ACK policy) within standard WireMock stub metadata (`metadata.async`), co-locating async stubs with HTTP and Casual stubs without separate file hierarchies.
_Avoid_: queue stub DSL, async mapping file, broker config block

**Synthetic Async ServeEvent**:
The bidirectional translation of an inbound consumed message and its synthetic outbound reply into WireMock's core `ServeEvent` / `LoggedRequest` journal pipeline, enabling full console search, status badging, and payload unnesting.
_Avoid_: queue log entry, message journal record, async trace

**Message Exchange Pattern (MEP)**:
The structural interaction model of an asynchronous exchange, supporting both `Request-Reply` (consume from queue A, match, and publish correlated reply to queue B or `reply-to`) and `Fire-and-Forget` / event sink (consume, match, journal, with no reply).
_Avoid_: message flow style, queue interaction mode

**Async Queue Bridge**:
An active forwarding conduit linking a test broker destination with an upstream broker destination, supporting live proxy forwarding and 1-click stub harvesting across the test pyramid.
_Avoid_: message pipe, queue router, broker link

**Async Messaging Engine**:
A deep module unifying in-process Kafka wire sinks, queue bridging, stub matching, offline stub harvesting, and telemetry journaling behind a cohesive lifecycle and routing interface.
_Avoid_: async manager, queue coordinator, message helper

**Consumer Feed Stubbing**:
The declarative configuration of staged or triggered message feeds waiting to be polled and consumed by the System Under Test (SUT) from an in-process Kafka topic or AMQP queue, supporting `on-subscribe`, `immediate`, and causal HTTP trigger timing via `metadata.async.feed`.
_Avoid_: consumer mock, topic injector, poll stub

**In-Process Wire Server**:
A lightweight, zero-daemon TCP protocol server running inside the WireMock process (e.g. `KafkaWireSink`, `AmqpServer`) that speaks native binary wire protocols (`bootstrap.servers=localhost:9092`, `amqp://localhost:5672`), enabling real client libraries to connect without external broker containers or consensus engines.
_Avoid_: embedded broker, mock broker, fake server

**Queue Mediation Bridge**:
An architectural pattern where the System Under Test connects to an external or developer queue manager (such as an ephemeral IBM MQ container via port 1414), while WireMock connects as an AMQP 1.0 or native client to the same queue manager, intercepting inbound request queues, evaluating stubs, and dispatching correlated replies without reverse-engineering proprietary wire protocols.
_Avoid_: queue proxy, broker interceptor, middleman

**Queue Rewriting**:
The practice of routing test traffic through virtual or prefix-scoped queue names (e.g. `DEV.QUEUE.1.SUT_STUB` $\to$ `DEV.QUEUE.1`) on a single shared broker to eliminate competing-consumer race conditions between the SUT and live upstream systems.
_Avoid_: queue renaming, alias routing, channel mangling

**Credit-Based Flow Mediation**:
The protocol-governed delivery of asynchronous messages according to AMQP 1.0 `Flow` link credits and disposition settlement states (`Accepted`, `Rejected`, `Released`), ensuring consumer pacing and enabling verification of consumer acknowledgments and dead-letter routing.
_Avoid_: backpressure throttle, link flow mock, disposition tracker

**Async Proxy Error (502)**:
The standardized WireMock `ServeEvent` and UI representation generated when a message cannot be bridged to an upstream destination due to a missing queue, broker connection outage, or authorization failure, preserving the source payload for 1-click inspection, replay, or offline stub snapshotting.
_Avoid_: bridge failure, queue error, broken pipe

**In-Memory AMQP Reference Broker**:
An embedded ActiveMQ Artemis instance running strictly within test scope on a dynamic TCP port, providing authentic multi-queue routing, durable storage, and credit-based flow control for E2E bridge verification without Docker.
_Avoid_: fake broker, test queue stub, mock amqp server

**Asynchronous Journal Inspection**:
The verification of recorded async and queue messages in the Web Console UI via headless Playwright, asserting message payloads, protocol badges, timing, and proxying verdicts (`stubbed`, `proxied`, `proxy_error`).
_Avoid_: UI async test, web tester check, visual queue spy

**Async Destination Explorer**:
The unified Web Console UI surface (`📬 Queues & Topics`) and REST control plane (`/__admin/async/destinations`) for inspecting, browsing, payload editing, and purging queues, topics, and staged message feeds across Kafka, AMQP, Casual, and In-Memory channels.
_Avoid_: queue manager, topic dashboard, async inspector

**Synchronous Upstream Bridge Acknowledgment**:
The operational contract where WireMock's protocol ingress blocks acknowledgment to the System Under Test until the forwarded message is confirmed by the upstream remote broker (Kafka, AMQP, or Casual), preserving end-to-end delivery guarantees and failing fast with 502 Bad Gateway upon upstream network or queue errors.
_Avoid_: async forward, background relay, optimistic ack

**Compensating Queue Edit**:
The workflow of modifying an unconsumed or log-based message by opening it in the Document Workspace (`PolyglotViewer` + `CodeEditor`), editing document layers with span-exact splicing, and publishing a successor replacement record (with optional cancellation, dequeue, or tombstoning of the superseded message).
_Avoid_: in-place wire edit, log mutation, dirty patch

**Declarative Broker Configuration**:
A read-only, file-backed configuration model where broker connections and cluster coordinates are loaded on startup from a declarative definition (`connections.json` or CLI parameters), supporting export and runtime inspection via the Admin API without permitting mutating writes to the underlying version-controlled file.
_Avoid_: dynamic connection file, self-mutating config, broker state store

**Corpus-Based Message Filtering**:
The real-time client-side evaluation of boolean search queries (`AND`, `OR`, implicit token conjunctions) against a concatenated searchable text corpus extracted from message keys, transport headers, correlation IDs, and polyglot payload bodies.
_Avoid_: payload grep, regex scan, message string filter

**Broker Connection Registry**:
A centralized configuration store (`AsyncBrokerRegistry`) and Admin API (`/__admin/async/connections`) managing remote cluster connection coordinates, authentication credentials (SASL, TLS), and client pools for Kafka, AMQP, and Casual backends, cleanly decoupling broker infrastructure secrets from stub mapping files.
_Avoid_: broker config bag, cluster map, connection helper

**Live Broker Catalog**:
The active discovery mechanism that queries connected broker instances directly (via `AdminClient.listTopics()` for Kafka, `CasualClient.discoverQueues()` for Casual, or AMQP address management) to populate the live destination tree in the UI, avoiding static stub parsing or speculative channel guessing.
_Avoid_: topic scanner, queue crawler, channel discovery daemon

**Detached Transient Consumer**:
A read-only, non-committing Kafka consumer with an ephemeral client ID and direct partition assignment that seeks backwards from partition high-water marks to inspect recent records without joining consumer groups, triggering rebalances, or committing offsets.
_Avoid_: topic spy, shadow consumer, offset snooper

**Casual Remote Queue Inspector**:
An isolated protocol adapter seam encapsulating non-destructive queue message inspection against remote Casual domains via transactional dequeue and rollback, providing an exchange point for upstream non-destructive browse protocol frames without leaking transaction mechanics into the Admin API.
_Avoid_: casual peek helper, rollback inspector, queue snooper

### Web Console & Developer UI

**Stub Catalog**:
An in-process module that partitions standard WireMock mappings into active (`priority < 100`), parked (`priority >= 100`), and `offline-guard` (`priority 50`, `metadata.role === "offline-guard"`) views, managing project ownership derivation, multi-token boolean query evaluation, and selection state without leaking internal list mutation across caller boundaries.
_Avoid_: stub state bag, stub cache, stub list manager

**Stub Lifecycle Service**:
A stateless domain module managing native WireMock mapping priority tiers (`5` active stub, `10` active proxy, `50` `offline-guard`, `>= 100` parked via `metadata.previousPriority`) and deduplicated serve-event snapshot conversions (`POST /__admin/lifecycle/snapshots`, `POST /__admin/lifecycle/snapshot-template`) directly on standard `/__admin/mappings/{id}` records.
_Avoid_: disabled stub store manager, lifecycle helper, recording converter

**UI Static Asset Resolver**:
A zero-dependency loader resolving embedded console assets (`/ui/*`) from workspace development roots with classpath fallback, path-traversal protection, and MIME content-type mapping, shared across standalone proxy and admin extension adapters.
_Avoid_: asset helper, file server, web resource loader

**UI Domain Driver**:
A high-level test seam encapsulating console navigation, tab switching, stub/journal query evaluation, modal interactions, and assertion waiting without coupling test suites to raw DOM selectors or arbitrary sleep timeouts.
_Avoid_: page object boilerplate, playwright helper, UI tester fixture

**Document Workspace**:
The unified fullscreen modal workbench (`#modal-document-workspace`, `document-workspace.js`) shared across Stubs, Tester Request Payloads, OpenAPI specs, Bundles, and Queue Messages. Opens viewer-first (`DOCUMENT_VIEWER` via `PolyglotViewer`) and transitions to focused `SECTION_EDITOR` (`CodeEditor`) with ancestor breadcrumb navigation, wire-style controls (`auto`, `compact`, `multiline`), dirty-state tracking (`#workspace-dirty-badge`), and root commit/discard confirmation.
_Avoid_: maximized payload viewport, nested payload lens, popup editor, sub-modal

**`PolyglotViewer`**:
The canonical read-only and editable foldable tree renderer (`polyglot-viewer.js`) mounted across Stub Detail, Request Journal, Multi-Protocol Tester, Queues, and Document Workspace. Renders syntax-highlighted `Rich` and `Raw` views, format/charset/size descriptor badges, collapsible nested payload cards with single-delegated events, `📋 Copy`, and `⛶ Open in Workspace` (emitting zero disabled Edit buttons on read-only surfaces).
_Avoid_: canonical payload surface, unified payload component, payload lens, custom preview panel

**`PolyglotAST`**:
The recursive payload unnesting and span-exact splicing engine (`polyglot-ast.js`, paired with `charset-codec.js` and `pseudo-json.js`) that detects embedded JSON, XML, Java `Map.toString()` KV (`{k=v}`), and Base64 payloads with character-encoding detection (`UTF-8`, `ISO-8859-1`, `WINDOWS-1252`, `KOI8-R`, `US-ASCII`), recording exact `[start, end]` character spans so `applyNestedPathEdit` splices leaf edits back up the ancestor chain without `.replace()` collisions.
_Avoid_: string unescaper, regex payload cleaner, nested parser

**`CodeEditor` (`highlight-api`)**:
The zero-dependency syntax-highlighted editor component (`code-editor.js`) hosted inside `#modal-document-workspace`. Defaults to `<pre contenteditable="plaintext-only">` painted via the CSS Custom Highlight API (`CSS.highlights` + `tokenizeOffsets`), with a hot-swappable `legacy-duallayer` (`<textarea>` + backdrop `<pre>`) fallback toggle (`#workspace-engine-toggle`).
_Avoid_: fake code editor, dual-layer textarea wrapper, syntax input box

**Container Encoding Envelope**:
The structural metadata recorded by `PolyglotAST` when unnesting an inner payload (`JSON_STRING`, `XML_ENTITY_TEXT`, `XML_CDATA`, `BASE64_UTF8`, `BASE64`, plus detected character encoding and original compact vs. multi-line style), governing how an edited child section is re-encoded into its parent layer during round-tripping.
_Avoid_: escape mode, encoding flag, wrapper type

**`wiremock-cli` / `WireMockConsoleProxy`**:
The zero-compile JDK 21 JEP 330 terminal CLI (`wiremock-cli`, invoked via `./wiremock-cli/bin/wm` for `info`, `stubs`, `tail`, `toxics`, `proxy`) and the headless reverse-proxy console server (`WireMockConsoleProxy`, housed in `wiremock-extension-ui` and launched via `wm proxy`) that serves `/__admin/ui/` against remote WireMock targets without browser CORS restrictions.
_Avoid_: CLI wrapper, standalone UI proxy script

**UI Performance Quality Gate**:
An automated outer loop verification seam in headless Chromium that instruments the Chrome DevTools Protocol (`Performance`, `Profiler`) and in-page `PerformanceObserver` to enforce strict client-side performance budgets: zero Long Tasks (>50ms) during user interactions, bounded DOM element generation, and isolated CSS layout containment.
_Avoid_: UI benchmark, profiler test, speed check

### Observability & Telemetry

**Telemetry Engine**:
An in-process observability module that encapsulates a unified OpenTelemetry SDK (MeterProvider, TracerProvider, and Resource), metric instruments, asynchronous observable gauges for WireMock journal/stubs/NetEm state, and W3C trace span lifecycle management, scoped per WireMock server instance.
_Avoid_: metrics registry, tracing registry, telemetry helper, monitoring service

### Request Journal & Memory Optimization

**Compressed Serve Event**:
An in-memory representation of a WireMock `ServeEvent` holding its request and response bodies compressed via Zstandard, decompressing transparently on-demand during query evaluations or UI inspection without re-caching raw payloads on the heap.
_Avoid_: zipped event, cached serve event, packed record

**Adaptive Journal Compactor**:
An in-memory journal store decorator and engine that immediately compresses inbound request/response payloads and trains cluster-specific Zstandard dictionaries on high-volume endpoints to maximize compression density.
_Avoid_: journal cleaner, background zipper, journal evictor

**Cluster Signature**:
The structural classification identifier (`stub:<id>`, `proxy:<target>`, or `unmatched:<method>`) used to group structurally homogeneous payloads for targeted dictionary training without cross-endpoint schema contamination.
_Avoid_: endpoint tag, payload group, route bucket

**Transient Decompression**:
The execution model where compressed journal payloads are decompressed strictly on-the-fly to satisfy reader queries (assertions, searches, UI views) and instantly garbage-collected, preventing heap re-inflation.
_Avoid_: lazy caching, query inflation, memory rehydration

**Adaptive Anti-OOM Guard**:
The memory protection module monitoring real-time JVM available heap headroom and post-GC memory pool thresholds, executing FIFO pruning of historical journal entries when memory approaches critical limits.
_Avoid_: memory watchdog, heap killer, OOM checker

**Retroactive Compaction**:
The process of upgrading historical journal events that were initially recorded with baseline compression to specialized dictionary compression once their cluster dictionary completes training.
_Avoid_: re-zipping, back-compression, late encoding

**Dynamic Rebalancing**:
The automatic background adjustment of cluster dictionary assignments and stored payload density triggered by event-count watermarks, eviction thresholds, or newly trained dictionaries.
_Avoid_: journal defrag, store rebalancing, memory garbage clean

**Traffic Classification**:
An immutable domain representation derived from a WireMock `ServeEvent` and `Request` that encapsulates protocol detection (`http`, `casual`, `async`), routing outcome (`stubbed`, `proxied`, `fault`, `unmatched`), upstream proxy target, RPC service/error metadata, and elapsed duration.
_Avoid_: request inspector, traffic sniffer, protocol detector

**`ClientSourceResolver`**:
The caller-identity module (`wiremock-extension-common`) that extracts the effective caller IP (`X-Forwarded-For` first hop -> `X-Real-IP` -> socket `clientIp`) and performs non-blocking reverse-DNS lookups on virtual threads with a bounded batch wait and 15-minute positive/negative TTL cache (fast-pathing loopback to `localhost`), powering the Request Journal `Source` column and `src:`/`ip:`/`host:` search predicates.
_Avoid_: IP helper, DNS util, client address parser

### Unified Multi-Protocol Tester & Workbench

**Unified Multi-Protocol Tester**:
A single consolidated interactive workbench in the WireMock Console UI capable of dispatching and testing across HTTP, Kafka, AMQP, and Casual XATMI protocols from a unified address bar and execution history.
_Avoid_: HTTP tester, Casual tester, multi-tab tester

**Multi-Protocol Dispatch Workbench**:
The shared execution controller that morphs address bar inputs and headers drawers dynamically to match the selected protocol (HTTP URL/method, Kafka topic/key, AMQP destination/correlation-id, Casual service/buffer-type) while preserving `PolyglotViewer` and `#modal-document-workspace` (`CodeEditor`) across all transports.
_Avoid_: request bar, protocol switcher, input form

**Protocol-Native Settlement Inspector**:
The dual-pane execution inspector rendering protocol-specific outcome banners (HTTP status codes, Kafka partition/offset acknowledgments, AMQP transfer settlements and correlated replies, Casual XATMI return codes and buffer descriptors) alongside full-fidelity request and response payload lenses.
_Avoid_: response viewer, ack inspector, result box

**Protocol Segmented Control**:
The top-level interactive selector (`[HTTP] [Kafka] [AMQP] [Casual]`) within the Tester workbench that controls protocol-specific input morphing, quick templates filtering, and dispatch seam delegation.
_Avoid_: protocol pills, tab picker, protocol tabs

**Protocol Dispatcher Seam**:
A deep, zero-DOM client module (`dispatcher.js`) standardizing multi-protocol execution (HTTP, Kafka, AMQP, Casual XATMI) behind a single unified interface returning an immutable `SettlementResult` without coupling to browser DOM elements or UI controllers.
_Avoid_: request handler, protocol executor, test runner helper

**Bridged Message Verdict**:
The domain classification and visual badge (`🔀 BRIDGED`) applied to asynchronous Kafka or AMQP messages forwarded across a queue bridge to an upstream broker, participating seamlessly in WireMock's `proxied` lifecycle category and 1-click offline stub snapshotting.
_Avoid_: proxied queue, routed message, forwarded event
