# wiremock-extensions

Production extensions and developer tooling for WireMock Standalone, providing protocol mediation (Casual XATMI, OpenAPI), stateful simulation, L4/L7 network emulation, observability, and an embedded Web UI and CLI.

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

### Web Console & Developer UI

**Stub Catalog**:
An in-process module that aggregates active stubs, disabled stubs, and synthesized dynamic stateful stubs, managing project ownership derivation, multi-token boolean query evaluation, and selection state without leaking internal list mutation across caller boundaries.
_Avoid_: stub state bag, stub cache, stub list manager

**Stub Lifecycle Service**:
A domain module managing the state lifecycle of WireMock stubs (`enable`, `disable`, `toggle`, `upsert`, `delete`, `bulkApply`, `switchProjectMode`) across active admin and parked disabled stores, as well as deduplicated serve event snapshot conversions.
_Avoid_: disabled stub store manager, lifecycle helper, recording converter

**UI Static Asset Resolver**:
A zero-dependency loader resolving embedded console assets (`/ui/*`) from workspace development roots with classpath fallback, path-traversal protection, and MIME content-type mapping, shared across standalone proxy and admin extension adapters.
_Avoid_: asset helper, file server, web resource loader

**UI Domain Driver**:
A high-level test seam encapsulating console navigation, tab switching, stub/journal query evaluation, modal interactions, and assertion waiting without coupling test suites to raw DOM selectors or arbitrary sleep timeouts.
_Avoid_: page object boilerplate, playwright helper, UI tester fixture

**Payload Unnesting Engine**:
A domain module that recursively detects, extracts, and parses embedded JSON, XML, Java Map / Key-Value (`{key=val}`), or Base64 documents inside JSON string properties or XML text/CDATA nodes while preserving structural path addressing and guessing character encodings.
_Avoid_: string unescaper, regex payload cleaner, nested parser

**Maximized Payload Viewport**:
A full-window (100vw × 100vh) overlay viewport wrapping a Canonical Payload Surface with breadcrumb navigation, format descriptors, and action toolbars, providing distraction-free inspection and editing of root documents or unnested child payloads with full syntax highlighting and inline unnested blocks.
_Avoid_: nested payload lens, popup editor, sub-modal, raw string editor

**Auto-Unnested Payload**:
An inline presentation of an escaped nested JSON, XML, Key-Value, or Base64 document rendered directly within the parent syntax flow, expanded and auto-indented by default with a format badge, subtle gutter marking, and action controls to allow immediate reading without manual unrolling.
_Avoid_: auto-expanded string, unpacked blob, inlined object, payload lens

**Truncated Payload Preview**:
An inline syntax-highlighted representation of an escaped nested JSON, XML, Key-Value, or Base64 document rendered within the parent syntax flow when collapsed or folded, featuring a format badge and hover actions to inspect or unfold without disrupting document geometry.
_Avoid_: string ellipsis, clipped text, collapsed string, block preview box

**Detected Embedded Payloads Bar**:
A live overlay toolbar attached to editable JSON/XML textareas that surfaces actionable pills for every nested escaped or Base64-encoded payload discovered in the current editor content.
_Avoid_: helper bar, payload banner, editor chips

**Container Encoding Envelope**:
The structural metadata recorded when unnesting an inner payload (`JSON_STRING`, `XML_ENTITY_TEXT`, `XML_CDATA`, `BASE64_UTF8`, `BASE64`, plus detected character encoding such as `UTF-8`, `ISO-8859-1`, or `KOI8-R`, and original compactness), governing how an edited child payload is re-encoded into its parent layer during round-tripping.
_Avoid_: escape mode, encoding flag, wrapper type

**Canonical Payload Surface**:
The unified UI presentation and editing component shared across all console panels (Stub Catalog detail, Stub Editor form and raw modes, Request Journal rows, and HTTP/Casual Testers), standardizing dual view/edit modes, syntax highlighting, format/charset/size badges, recursive auto-unnested previews, and fullscreen maximize actions.
_Avoid_: code box, custom textarea, raw editor pane

**Unified Payload Component**:
The single canonical UI component instance mounted identically across View Stub cards, Edit Stub modal panels, Maximized Viewports, Testers, and Journal entries. Encapsulates the 2×2 state engine, the standard header toolbar, live nested block unescaping/re-escaping, and bidirectional synchronization.
_Avoid_: custom viewer, preview panel, raw text container

**2×2 Payload Experience Matrix**:
The orthogonal state grid governing payload presentation and manipulation across all console surfaces: Mode Axis (`Rich`: unescaped, formatted, indented vs. `Raw`: escaped, unindented wire representation) and Action Axis (`View`: read-only `<pre><code>` vs. `Edit`: Dual-Layer Code Editor), yielding four explicit states (`Rich View`, `Rich Edit`, `Raw View`, `Raw Edit`) with syntax highlighting active across all four.
_Avoid_: display switcher, format mode grid, editor toggle matrix

**In-Place Block Editing**:
The capability within the Unified Payload Component to click `[ ✏️ Edit ]` on any header (the root document header or any nested subsection header) and swap only that level's `<pre>` container in-line into an auto-growing Dual-Layer Code Editor (`overflow-y: hidden`, zero internal vertical scrollbars) containing only that level's unescaped text (or wire text in `Raw` mode), with `[ ✓ Apply | ✕ Cancel ]` controls that validate, re-encode, and transiently update the in-memory `Root Document Session` before returning to the foldable polyglot tree viewer.
_Avoid_: nested edit popup, inline string unescaper, sub-editor modal

**Root Document Session**:
The top-scope state descriptor (`{ rootText, editable, readOnlyReason, onUpdateRoot, context }`) owned by a console surface (such as a WireMock Stub JSON in the Stub Editor, a Request Body in the HTTP/Casual Tester, or an immutable payload in the Request Journal / Stub Catalog) and propagated down through every recursive child node and across any Fullscreen Maximized Viewport layer. Permanent save/discard actions apply strictly to the Root Document at the top-level surface, whereas `[ ✓ Apply ]` on any header level performs a transient in-memory update to the Root Document.
_Avoid_: modal state bag, local textarea state, nested save callback

**Hierarchical In-Line Editing**:
The uniform interaction pattern where every level of a polyglot document tree (root header and nested subsection headers alike) displays the identical toolbar (`[ 🎨 Rich | 📝 Raw ] [ ✏️ Edit ] [ 📋 Copy ] [ ⛶ Maximize ]`), allowing targeted in-line editing at any level when `editable: true` or rendering `[ ✏️ Edit ]` as `disabled` with an explicit contextual tooltip (`readOnlyReason`) when `editable: false`.
_Avoid_: modal-only nested editing, hidden edit buttons, separate viewer and editor widgets

**Dual-Layer Code Editor**:
A zero-dependency editor component coupling a foreground `<textarea>` (configured with transparent text, visible caret, and spellcheck disabled) with a background `<pre class="editor-backdrop"><code>` layer running `highlightPayload()`, with locked font metrics and scroll synchronization across both `Rich Edit` and `Raw Edit`.
_Avoid_: fake code editor, syntax textarea, custom input box

**Code Input Editor Adapter**:
A reusable adapter at the Canonical Payload Surface seam that equips native textareas (such as Stateful AST rules, OpenAPI specifications, and WireMock JSON bundle imports) with Dual-Layer syntax highlighting, format prettifying, live error diagnostics, and fullscreen maximize capabilities.
_Avoid_: textarea wrapper, editor helper, code enhancer

**Editor Experience Mode**:
The Mode Axis (`rich` vs. `raw`) of the 2×2 Payload Experience Matrix, toggling between unescaped/formatted/indented representation (`rich`) and wire-exact escaped/unindented representation (`raw`) while preserving syntax highlighting in both modes.
_Avoid_: editor view switch, textarea toggle, plain-text fallback toggle

**Fullscreen Maximized Viewport**:
A full-window (100vw × 100vh) overlay viewport wrapping a Canonical Payload Surface with breadcrumb navigation, format descriptors, and action toolbars, providing distraction-free inspection and editing of root documents or unnested child payloads.
_Avoid_: big modal, fullscreen dialog, zoom popup

**Viewport Navigation Stack**:
The LIFO (last-in, first-out) state container managing layered Fullscreen Maximized Viewports when drilling into nested payloads, where each nested lens stacks above its parent and pressing `Escape` pops only the topmost layer.
_Avoid_: modal history, lens queue, popup stack

**Contextual Save Handler**:
The strategy module dispatching unsaved edits in a Canonical Payload Surface to their domain destination based on origin context (updating in-memory stubs, prompting to snapshot historical journal entries as stubs, updating tester request editors, or re-escaping child nodes back into parent container envelopes).
_Avoid_: save callback, custom save switch, submit router

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
An immutable domain representation derived from a WireMock `ServeEvent` and `Request` that encapsulates protocol detection (`http`, `casual`, `grpc`), routing outcome (`stubbed`, `proxied`, `fault`, `unmatched`), upstream proxy target, RPC service/error metadata, and elapsed duration.
_Avoid_: request inspector, traffic sniffer, protocol detector
