# PLAN.md: Casual Protocol Support in WireMock

Analysis, architectural design, feasibility assessment, and implementation plan for adding native Casual (XATMI) protocol simulation to `wiremock-extensions`.

---

## 1. Executive Summary & Objective

**The Goal**: Enable WireMock to act as an in-process, lightweight Casual Domain Gateway. Casual clients (Java applications via `casual-java` JCA/Quarkus or native C++ clients) communicate over native Casual TCP sockets, while WireMock intercepts, matches, and stubs responses using familiar WireMock semantics (JSON/Fielded buffer stubs, response templating, stateful scenarios, and journal tracking).

---

## 2. Background: What is Casual & `casual-java`?

* **Casual (`casualcore/casual`)**: A modern distributed application server implementing the Open Group **XATMI** standard (Extensible Architecture for Transaction Management Interworking, akin to Tuxedo/CICS/Endevor).
* **`casual-java` (`casualcore/casual-java` / `se.laz.casual`)**: JCA resource adapter and client libraries for Jakarta EE, Java SE, and Quarkus.
* **Casual Wire Protocol**:
  * Persistent TCP stream.
  * Message envelope: 32-byte header with magic/protocol type (16 bytes), correlation UUID (16 bytes), and 8-byte payload size.
  * Domain Handshake & Discovery: `CasualDomainConnectRequestMessage` / `CasualDomainDiscoveryRequestMessage`.
  * Service Invocations: `CasualServiceCallRequestMessage` carrying service name, execution UUID, transaction XID, XATMI flags, and payload `ServiceBuffer` (JSON, FIELDED, CSTRING, X_OCTET).

---

## 3. Feasibility Analysis & Lightweight Strategy

### Can we avoid a full Java EE / Jakarta EE application server?
**YES, 100%.**
* `casual-java` provides modular artifacts on Maven Central:
  * `se.laz.casual:casual-api:3.6.0` (pure POJOs, buffer models, flags, enums).
  * `se.laz.casual:casual-network-protocol:3.6.0` (pure protocol encoders and decoders: `CasualMessageDecoder`, `CasualMessageEncoder`).
  * `se.laz.casual:casual-network:3.6.0` (Netty handlers: `CasualNWMessageDecoder`, `CasualNWMessageEncoder`).
* **Neither Jakarta EE, WildFly, nor any Application Server is required** for the wire protocol engine. A pure Netty TCP server (`io.netty:netty-all:4.1.x`) or raw NIO server using `casual-network-protocol` provides complete wire compatibility in a headless, zero-container Java SE 21 process.

---

## 4. Architectural Design: Casual $\rightarrow$ WireMock Bridge

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                              Casual-to-HTTP WireMock Ingress                           │
│                                                                                        │
│  Casual Client (Java / C++ / Quarkus)                                                  │
│         │                                                                              │
│         │ Casual TCP Protocol (tpcall: "echoService", JSON / Fielded Buffer)           │
│         ▼                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────────────────┐  │
│  │ wiremock-extension-casual (In-Process Netty / TCP Gateway Server)                 │  │
│  │                                                                                  │  │
│  │   1. Handles Domain Handshake (DomainConnect & DomainDiscovery)                  │  │
│  │   2. Intercepts CasualServiceCallRequestMessage                                  │  │
│  │   3. Translates to Synthetic WireMock HTTP Request:                              │  │
│  │        POST /__casual/services/{serviceName}                                     │  │
│  │        Header: X-Casual-Service: {serviceName}                                   │  │
│  │        Header: X-Casual-Buffer-Type: JSON (or FIELDED)                           │  │
│  │        Body: Buffer bytes or JSON payload                                        │  │
│  │   4. Dispatches to WireMock Stub Matching Engine                                 │  │
│  │   5. Translates WireMock HTTP Response to CasualServiceCallReplyMessage:         │  │
│  │        HTTP 200 -> ErrorState.OK (TPSUCCESS) + Reply ServiceBuffer               │  │
│  │        HTTP 404 -> ErrorState.TPENOENT (Service not found)                       │  │
│  │        HTTP 500 -> ErrorState.TPESVCFAIL (Service failure)                       │  │
│  │   6. Encodes and returns Casual reply frame over TCP socket                      │  │
│  └──────────────────────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. Phased Implementation Plan

### Phase 1: Project Setup & Baseline SPI
- [x] Create feature branch `feat/casual-protocol-wiremock`.
- [x] Create subproject `wiremock-extension-casual`.
- [x] Configure dependencies (`se.laz.casual:casual-network-protocol`, `se.laz.casual:casual-api`, `netty`).
- [x] Push baseline to remote.

### Phase 2: Proof-of-Concept (PoC) & Lightweight Test Suite
- [ ] Implement standalone `CasualWireMockServer` (Netty-based TCP listener).
- [ ] Support handshake and domain discovery requests.
- [ ] Implement `CasualToHttpBridge`: translate `CasualServiceCallRequestMessage` to WireMock HTTP dispatch.
- [ ] Build standalone test client without requiring a real C++ Casual domain.
- [ ] Validate end-to-end: Casual client connects $\rightarrow$ `tpcall` $\rightarrow$ WireMock stub matched $\rightarrow$ Casual client receives stubbed buffer.

### Phase 3: WireMock Extension Integration
- [ ] Implement WireMock `ExtensionFactory` and lifecycle listener.
- [ ] Support Fielded Buffer $\leftrightarrow$ JSON conversion.
- [ ] Wire into `wiremock-extension-bundle` and Admin API.
