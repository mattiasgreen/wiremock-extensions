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
A domain module that recursively detects, extracts, and parses embedded JSON, XML, or Base64 documents inside JSON string properties or XML text/CDATA nodes while preserving structural path addressing.
_Avoid_: string unescaper, regex payload cleaner, nested parser

**Nested Payload Lens**:
A focused inspection and editing surface for a single unwrapped inner payload (with breadcrumb path context, syntax validation, and round-trip re-escaping back into its parent container).
_Avoid_: popup editor, sub-modal, raw string editor

**Truncated Payload Preview**:
An inline summary representation of a large escaped single-line XML/JSON blob in a parent viewer, displaying an interactive hint pill to open the **Nested Payload Lens** without flooding the viewport with horizontal scroll.
_Avoid_: string ellipsis, clipped text, collapsed string

**Detected Embedded Payloads Bar**:
A live overlay toolbar attached to editable JSON/XML textareas that surfaces actionable pills for every nested escaped or Base64-encoded payload discovered in the current editor content.
_Avoid_: helper bar, payload banner, editor chips

**Container Encoding Envelope**:
The structural metadata recorded when unnesting an inner payload (`JSON_STRING`, `XML_ENTITY_TEXT`, `XML_CDATA`, `BASE64_UTF8`, plus original compactness), governing how an edited child payload is re-encoded into its parent layer during round-tripping.
_Avoid_: escape mode, encoding flag, wrapper type

### Observability & Telemetry

**Telemetry Engine**:
An in-process observability module that encapsulates a unified OpenTelemetry SDK (MeterProvider, TracerProvider, and Resource), metric instruments, asynchronous observable gauges for WireMock journal/stubs/NetEm state, and W3C trace span lifecycle management, scoped per WireMock server instance.
_Avoid_: metrics registry, tracing registry, telemetry helper, monitoring service

**Traffic Classification**:
An immutable domain representation derived from a WireMock `ServeEvent` and `Request` that encapsulates protocol detection (`http`, `casual`, `grpc`), routing outcome (`stubbed`, `proxied`, `fault`, `unmatched`), upstream proxy target, RPC service/error metadata, and elapsed duration.
_Avoid_: request inspector, traffic sniffer, protocol detector
