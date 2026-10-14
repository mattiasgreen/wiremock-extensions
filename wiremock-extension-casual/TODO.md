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
- [ ] Implement Path A: In-process WireMock `Request` adapter (bypass HTTP loopback, evaluate directly against `StubMappings`).
- [ ] Implement Custom Matchers (`CasualFieldedMatcher`, `CasualBufferMatcher`) for non-JSON (`.FIELDED`, `.X_OCTET`) payloads.
- [ ] Implement WireMock `ExtensionFactory` and `ServerLifecycleListener` to bind gateway lifecycle to WireMock start/stop.
- [ ] Admin API and UI visualization for Casual service mappings and stats.

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

