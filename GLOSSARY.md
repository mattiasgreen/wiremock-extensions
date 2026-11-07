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
Adapting Casual network frames (`SERVICE_CALL_REQUEST`, `SERVICE_CALL_REPLY`) to match the negotiated `ProtocolVersion` (`1.0`/`1.2` with inline `XID` vs. `1.3+` with `SpanId` and no reply `XID`) on a specific TCP connection.
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
The orthogonal state grid governing payload presentation and manipulation: Mode Axis (`Rich` vs. `Raw`) and Action Axis (`View` vs. `Edit`), yielding four explicit operational states (Rich View, Rich Edit, Raw View, Raw Edit).
_Avoid_: display switcher, format mode grid, editor toggle matrix

**In-Place Block Editing**:
The capability within Rich View to edit an unescaped, formatted sub-payload directly without manual escaping, with automatic structural re-serialization and re-escaping into the parent document upon save.
_Avoid_: nested edit popup, inline string unescaper, sub-editor modal

**Dual-Layer Code Editor**:
A zero-dependency editor component coupling a foreground `<textarea>` (configured with transparent text, visible caret, and spellcheck disabled) with a background `<pre class="editor-backdrop"><code>` layer running `highlightPayload()`, with locked font metrics and scroll synchronization.
_Avoid_: fake code editor, syntax textarea, custom input box

**Editor Experience Mode**:
An in-memory developer setting (`rich` vs. `raw`) toggling between the Dual-Layer Code Editor and a native raw `<textarea>` across console editing surfaces, providing zero-risk operational fallback for edge cases without persistent storage side-effects.
_Avoid_: editor view switch, textarea toggle

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

**Traffic Classification**:
An immutable domain representation derived from a WireMock `ServeEvent` and `Request` that encapsulates protocol detection (`http`, `casual`, `grpc`), routing outcome (`stubbed`, `proxied`, `fault`, `unmatched`), upstream proxy target, RPC service/error metadata, and elapsed duration.
_Avoid_: request inspector, traffic sniffer, protocol detector
