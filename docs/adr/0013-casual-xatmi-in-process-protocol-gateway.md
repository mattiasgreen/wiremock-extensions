# 13. Casual XATMI In-Process Protocol Gateway

Date: 2026-11-07

## Status

Accepted

## Context

Legacy enterprise and banking applications frequently rely on mainframe and middleware transaction processing systems based on X/Open DTP and XATMI protocols, specifically Casual (`casualcore`). Testing applications communicating over Casual TCP sockets historically required full native environments, external mock daemons, or heavy bespoke test harnesses.

WireMock Standalone provides an industry-standard HTTP/HTTPS mock engine, request journal, and declarative stub mapping model. However, out-of-the-box WireMock has no awareness of non-HTTP protocols, binary network buffers, or two-phase commit (2PC / XA) transaction mechanics.

## Decision

We introduce `wiremock-extension-casual`, an in-process Netty-based Casual protocol gateway that runs alongside WireMock Standalone, translating binary XATMI traffic into canonical WireMock stub matching and request journal records:

### 1. In-Process Netty Gateway Lifecycle
- `CasualWireMockGateway` boots an in-process Netty TCP server listening on configurable ports (default 7772).
- Leverages official upstream `se.laz.casual` framing, encoders (`CasualNWMessageEncoder`), and decoders (`CasualNWMessageDecoder`).
- Negotiates protocol handshakes (`DOMAIN_DISCOVERY_REQUEST`, `DOMAIN_DISCOVERY_REPLY`) dynamically, advertising mocked services defined in WireMock stubs.

### 2. Deep Buffer Mediation (`CasualHttpMediation`)
- Transparently maps between binary XATMI buffers and standard WireMock HTTP representations:
  - Maps Casual service names to HTTP paths: `/casual/{serviceName}`.
  - Translates Fielded binary buffers (`.FIELDED`, `CFIELD/`) to and from structured JSON trees.
  - Translates `CSTRING/` null-terminated strings and `X_OCTET/` binary buffers safely into UTF-8 text or Base64 payloads.
- Enables developers to write stubs, assertions, and journal inspections using canonical JSON and HTTP patterns while interacting natively over Casual TCP sockets.

### 3. Store Port Seam (`CasualStorePort`)
- Isolates WireMock matching mechanics behind `CasualStorePort` and `WireMockStoreAdapter`.
- Evaluates stub matching in-memory with sub-millisecond overhead, avoiding HTTP loopback socket hops.
- Provides `InMemoryCasualStoreAdapter` for fast, headless unit tests (< 4s execution).

### 4. Distributed 2PC/XA Transaction Coordination (`CasualTransactionCoordinator`)
- Supports Man-in-the-Middle (MitM) dynamic L7 proxying (`casual://host:port`).
- Tracks enlisted upstream connections per transaction ID (`Xid`).
- Atomically forwards two-phase commit frames (`PREPARE_REQUEST`, `COMMIT_REQUEST`, `ROLLBACK_REQUEST`) across multiple enlisted upstreams.
- Enables synthetic chaos injection and forced XA participant voting overrides (`XA_OK`, `XA_RBLONLY`, `TPESVCFAIL`) directly from stub configurations.

## Consequences

### Positive
- **Zero External Mock Daemons**: Developers test distributed transaction architectures entirely within JVM test suites or standalone WireMock instances.
- **Unified Observability**: All Casual calls appear in WireMock's Request Journal with fully decoded payloads, available for assertion, export, and UI visualization.
- **High Performance**: In-memory store adapter yields microsecond matching; in-process Netty gateway delivers low latency.

### Negative / Trade-offs
- Netty channel byte lifecycle requires careful ref-counting management (`ReferenceCountUtil.release`).
- 2PC integration tests require multi-node harnesses (Quarkus/Narayana) isolated into dedicated test loops.
