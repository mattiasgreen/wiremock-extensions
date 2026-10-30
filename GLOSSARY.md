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
