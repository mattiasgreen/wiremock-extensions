# Upstream Contribution Roadmap & Extension TODO

This document tracks items intended for upstream contribution to [`casualcore/casual-java`](https://github.com/casualcore/casual-java) to minimize Casual protocol internal knowledge within `wiremock-extension-casual`.

---

## 1. Upstream Contributions to `casualcore/casual-java`

When accepted upstream, these components can be deleted from this extension and consumed directly as Gradle dependencies.

### Item 1: Reusable Netty Framing Decoder (`CasualFrameDecoder`)
* **Target Module**: `se.laz.casual:casual-network` or `casual-network-protocol`
* **Description**: A standard Netty `ByteToMessageDecoder` assembling 32-byte header + variable payload chunks into `CasualNWMessage<?>` instances without downstream projects needing custom byte buffering.
* **Current Local Class**: `se.laz.casual.network.protocol.decoding.CasualFrameDecoder`

### Item 2: Standalone Java SE TCP Client (`CasualTcpClient`)
* **Target Module**: `se.laz.casual:casual-client-standalone` (new module in `casual-java`)
* **Description**: A lightweight client library for pure Java SE applications without JCA, WildFly, or Quarkus container dependencies.
  * Multiplexed asynchronous request/reply with correlation UUIDs.
  * Outbound TCP connection establishment and domain connect handshake (`DOMAIN_CONNECT_REQUEST`).
  * Dynamic service discovery (`DOMAIN_DISCOVERY_REQUEST`).
  * High-level synchronous/asynchronous XATMI invocations (`tpcall`, `tpacall`).
* **Current Local Class**: `se.laz.casual.client.CasualTcpClient`

### Item 3: Standalone Inbound Server Engine & SPI (`CasualServer`)
* **Target Module**: `se.laz.casual:casual-server-standalone` (new module in `casual-java`)
* **Description**: A lightweight in-process Casual Domain Gateway server engine accepting inbound TCP connections and dispatching service calls via a simple interface:
  ```java
  public interface CasualServiceHandler {
      CasualServiceCallReplyMessage handle(CasualServiceCallRequestMessage request);
  }

  public interface CasualDiscoveryHandler {
      List<Service> discover(List<String> serviceNames);
  }
  ```
* **Current Local Classes**: `se.laz.casual.server.CasualServer`, `se.laz.casual.server.CasualServiceHandler`, `se.laz.casual.server.CasualDiscoveryHandler`
* **Benefit to WireMock**: `wiremock-extension-casual` reduces to an adapter implementing `CasualServiceHandler` that translates requests to WireMock HTTP endpoints.

### Item 4: Protocol Builder Defaults & Fluent Helpers
* **Target Module**: `se.laz.casual:casual-network-protocol`
* **Description**:
  * Default `protocolVersion` to `ProtocolVersion.VERSION_1_0` in `CasualServiceCallRequestMessage.Builder` and `CasualServiceCallReplyMessage.Builder` (prevent NPE on omission).
  * Default `xid` to `XID.NULL_XID`.
  * Default `execution` to `UUID.randomUUID()`.
  * Default `transactionState` to `TransactionState.TX_ACTIVE`.
  * Add fluent helpers like `CasualServiceCallReplyMessage.ok(buffer)` and `CasualServiceCallReplyMessage.error(ErrorState.TPENOENT)`.

### Item 5: Buffer Conversion Helpers (`ServiceBuffer` / `JsonBuffer`)
* **Target Module**: `se.laz.casual:casual-api`
* **Description**:
  * Add `buffer.asString(Charset charset)` and `buffer.asBytes()` to `ServiceBuffer` to eliminate manual byte-chunk iteration loops.
  * Direct payload extraction on `JsonBuffer`.
* **Current Local Class**: `se.laz.casual.api.buffer.CasualBufferUtils`

---

## 2. WireMock Extension Roadmap

- [x] Initial PoC: In-process Casual Domain Gateway with WireMock HTTP bridge.
- [x] Lightweight test client (`CasualTcpClient`) without Jakarta EE or real C++ domain.
- [x] Stub match discrimination (`Matched-Stub-Id`) distinguishing deliberate 404/error mocks from unmatched requests.
- [x] Transparent L7 Casual TCP proxy forwarding to upstream Casual domains.
- [x] Automatic stub record-and-replay via WireMock Admin API (`POST /__admin/mappings`).
- [x] Refactor upstream candidate classes into their target `se.laz.casual.*` packages for seamless deletion once upstreamed.
- [x] Record and replay upstream faults (`TPESVCFAIL`) with sensible defaults (`TPENOENT` not recorded by default).
- [x] Implement Transparent 2PC Protocol Forwarding (`PREPARE_REQUEST`, `COMMIT_REQUEST`, `REQUEST_ROLLBACK`) with multi-upstream `Xid` stickiness.
- [x] Implement Synthetic XA Resource Participant (automatic `XA_OK` voting for offline stubs) and chaos simulation (`XAER_RMERR`).
- [x] Implement Path A: In-process WireMock `Request` adapter (bypass HTTP loopback, evaluate directly against `StubMappings`).
- [x] Implement Human-readable Buffer Mediation (`.FIELDED`/`CFIELD/` <-> JSON via `FieldedTypeBuffer`, `CSTRING/` null-terminator handling, `X_OCTET` Base64).
- [x] Implement WireMock `ExtensionFactory` and `ServerLifecycleListener` to bind gateway lifecycle to WireMock start/stop.
- [x] Admin API and UI visualization for Casual service mappings and stats.
- [x] Implement Stub-Driven Dynamic Proxying (`targetBaseUrl: casual://...`) replacing out-of-band writers and hardcoded upstreams.

---

## 3. Investigation: Native Casual/XATMI Stubbing vs. HTTP Loopback Bridge

### Current Architecture: HTTP Loopback Gateway
$$\text{Casual TCP Client} \xrightarrow{\text{XATMI}} \text{Gateway} \xrightarrow{\text{HTTP Loopback (Jetty)}} \text{WireMock} \xrightarrow{\text{HTTP}} \text{Gateway} \xrightarrow{\text{XATMI}} \text{Casual TCP Client}$$
* **Pros**: Simple PoC, reuses standard WireMock JSON mappings (`POST /__casual/services/<name>`) and Admin API out of the box.
* **Cons**: Incurs TCP loopback socket hops and HTTP parsing overhead ($\sim 2\text{--}10\text{ms}$); requires converting all payloads to JSON strings, making non-JSON buffers awkward.

### Evaluated Alternatives:

#### Path A: The In-Process WireMock `Request` Adapter (Recommended Next Step)
Wrap incoming `CasualServiceCallRequestMessage` in a class implementing WireMock's core SPI `com.github.tomakehurst.wiremock.http.Request`:
```java
Request wiremockRequest = new CasualWireMockRequestAdapter(casualMsg);
ServeEvent serveEvent = wireMockServer.serveStubFor(wiremockRequest);
```
* **Latency**: Sub-millisecond ($\sim 50\mu\text{s}$) via direct in-memory JVM calls.
* **Zero HTTP Overhead**: Bypasses sockets, ALPN, TLS, and Jetty servlets entirely.
* **Journal & Web UI Integration**: 100% native; requests appear directly in WireMock's Request Journal and Web UI (`/__admin/ui/`).
* **Scenarios & State**: WireMock Scenarios (`newScenarioState`), priority, and response transformations work out of the box.

#### Path B: Dedicated Native Matcher Engine
A standalone matcher engine inside the extension using WireMock's `ValueMatcher` pattern library (`com.github.tomakehurst.wiremock.matching.*`):
* **When to use**: When first-class support for legacy binary buffers is required:
  * `.FIELDED` / FML: Match directly on integer field IDs (e.g., `field(1001).equalTo("ACC-99")`).
  * `.X_OCTET` / Raw bytes: Match byte chunks or binary schemas.
  * XATMI-specific attributes: Native matchers on `Xid`, `flags`, or conversational call descriptors (`cd`).
* **Trade-off**: Requires custom stub storage, custom admin endpoints, and custom UI panels; diverges from standard WireMock mappings unless bridged.

### Evolutionary Roadmap Recommendation:
1. **Milestone 1**: Transition from HTTP loopback to **Path A (In-Process `Request` Adapter)** to eliminate socket overhead while preserving 100% WireMock feature parity.
2. **Milestone 2**: Introduce a `RequestMatcherExtension` (`CasualFieldedMatcher`) to support native `.FIELDED` buffer inspection without breaking standard JSON stub definitions.

---

## 4. Architectural Analysis: 2PC / XA & JEE Transaction Managers (Coordinator vs. Participant)

### Executive Summary: Coordinator vs. Participant
In distributed 2PC/XA architectures, there is a fundamental distinction between two roles:
1. **The Transaction Coordinator (Transaction Manager / TM)**: Initiates transactions (`tpbegin` / `@Transactional`), manages transaction boundaries, and coordinates 2PC across multiple downstream resources by issuing `PREPARE` and `COMMIT/ROLLBACK` orders.
2. **The Resource Participant (Resource Manager / RM / Gateway)**: Intercepts service calls, associates work with an `Xid`, reports transaction health (`TransactionState`), votes during `PREPARE`, and finalizes changes upon `COMMIT`.

* **`casual-quarkus` is a client application runtime**: A Quarkus app executes business logic, writes to local databases (e.g. PostgreSQL via Hibernate), and calls remote Casual services within `@Transactional`. It **must** have a JEE Transaction Manager (Narayana JTA via `quarkus-narayana-jta`) to coordinate 2PC across local database `XAResource`s and outbound Casual JCA resource adapters.
* **`wiremock-extension-casual` is a mock server / test double**: WireMock is the **target participant / transit gateway**, not the coordinator. The application under test (e.g. Quarkus, Spring Boot with Narayana, or Tuxedo C services) is already the coordinator.

> **Conclusion**: Introducing a JEE/JTA Transaction Manager into WireMock would add **heavy overhead and zero architectural value**. WireMock needs to be a **faithful XA protocol participant and proxy**, not a transaction coordinator.

---

### Comparison: Embedding a JEE TM vs. Native Protocol Participant

| Dimension | Embedding a JEE TM (Narayana / Atomikos / JCA) | Lightweight Protocol Participant (Our Native Engine) |
| :--- | :--- | :--- |
| **Classpath & Binary Weight** | **Heavy (+25–40 MB)**: JBoss Logging, JTA APIs, Arjuna Core, JBoss Remoting, JCA specs. | **Zero additional deps**: Pure Java SE 21 + existing Netty framing. |
| **Filesystem Side-Effects** | **Intrusive**: Requires writing persistent XA transaction log files (`ObjectStore` directory) to disk on every test. | **Zero footprint**: 100% in-memory and ephemeral. |
| **Startup / Teardown Latency** | **Slow (+500–1500ms)**: Recovery daemons, reaper threads, and transaction log initialization. | **Instant (< 5ms)**: Starts alongside WireMock dynamically. |
| **Chaos & Fault Injection** | **Extremely difficult**: Hardcoded to adhere strictly to XA spec; cannot easily force a timeout or partial heuristic hazard. | **Trivial & Declarative**: Easily configured in WireMock stubs (`withHeader("X-Casual-XA-Prepare-Vote", "XAER_RMERR")`). |

---

### What's Supported vs. What Breaks During Proxying

#### Current Support:
* **XID Propagation**: `req.getXid()` is extracted from incoming requests, passed through during transparent proxying, and mirrored in stub replies (`CasualServiceCallReplyMessage.setXid(...)`).
* **Transaction State Reporting**: Stubs can return `X-Casual-Transaction-State`: `TX_ACTIVE`, `ROLLBACK_ONLY`, `TIMEOUT_ROLLBACK_ONLY`, or `ERROR`.
* **Transaction State Proxying & Recording**: Transparent proxying records upstream `TransactionState` into WireMock stubs and faithfully replays it.
* **Transaction Error Codes**: `ErrorState.TPETRAN` can be stubbed or proxied.

#### Why Upstream 2PC Currently Breaks During Proxying:
1. **During `tpcall`**: The RPC executes fine; `req.getXid()` is forwarded to upstream, and upstream marks the transaction branch active.
2. **During `tpcommit()` / `tpabort()`**: The client’s Transaction Manager sends a Casual 2PC protocol message (`PREPARE_REQUEST`, `COMMIT_REQUEST`, or `REQUEST_ROLLBACK`) to the gateway.
3. **The Gateway Drops It**: Because `CasualServer` only listens for `DOMAIN_CONNECT`, `DOMAIN_DISCOVERY`, and `SERVICE_CALL`, it logs a warning (`"Unsupported Casual message type received: PREPARE_REQUEST"`) and drops the message.
4. **The Consequence**: The client’s Transaction Coordinator times out waiting for `PREPARE_REPLY`, aborts the global transaction with `XAER_RMERR` or `TPESYSTEM`, and the upstream domain is left with an in-doubt / dangling transaction branch.

---

### The Recommended 2PC Roadmap

1. **Feature A: Transparent 2PC Protocol Forwarding (Fixes Proxying)**:
   * Add handlers in `CasualServer` and `CasualWireMockGateway` for:
     * `PREPARE_REQUEST` $\rightarrow$ forward to upstream $\rightarrow$ return `PREPARE_REQUEST_REPLY` (`XAReturnCode`).
     * `COMMIT_REQUEST` $\rightarrow$ forward to upstream $\rightarrow$ return `COMMIT_REQUEST_REPLY`.
     * `REQUEST_ROLLBACK` $\rightarrow$ forward to upstream $\rightarrow$ return `REQUEST_ROLLBACK_REPLY`.
   * **Result**: Proxied distributed transactions will commit and roll back cleanly without breaking!

2. **Feature B: Synthetic XA Resource Participant (For Offline Stubbing)**:
   * When running offline (no upstream), WireMock automatically responds to `PREPARE_REQUEST` with `XA_OK` (or `XA_RDONLY` if no state was modified) and to `COMMIT_REQUEST` with `XA_OK`.
   * Enables full end-to-end `@Transactional` test suites in Quarkus / Spring Boot against WireMock stubs without requiring a real Casual backend.

3. **Feature C: 2PC Chaos & Failure Injection**:
   * Allow stubs to simulate 2PC failures:
     * Simulate `XAER_RMERR` or `XAER_NOTA` during Phase 1 prepare to test client rollback logic.
     * Simulate timeout during Phase 2 commit to test how client applications handle **Heuristic Hazards (`HEURHAZ`)**.

4. **Feature D: Transaction Stickiness / Connection Affinity**:
   * Like `casual-quarkus` (`transactionStickyEnabled`), track connections per `Xid` so multiple calls within the same transaction branch route over the same upstream channel.


