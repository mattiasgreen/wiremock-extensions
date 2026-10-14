# Upstream Contribution Roadmap & Extension TODO

This document tracks items intended for upstream contribution to [`casualcore/casual-java`](https://github.com/casualcore/casual-java) to minimize Casual protocol internal knowledge within `wiremock-extension-casual`.

---

## 1. Upstream Contributions to `casualcore/casual-java`

When accepted upstream, these components can be deleted from this extension and consumed directly as Gradle dependencies.

### Item 1: Reusable Netty Framing Decoder (`CasualFrameDecoder`)
* **Target Module**: `se.laz.casual:casual-network` or `casual-network-protocol`
* **Description**: A standard Netty `ByteToMessageDecoder` assembling 32-byte header + variable payload chunks into `CasualNWMessage<?>` instances without downstream projects needing custom byte buffering.
* **Current Local Class**: `com.github.mattiasgreen.wiremock.casual.CasualFrameDecoder`

### Item 2: Standalone Java SE TCP Client (`CasualTcpClient`)
* **Target Module**: `se.laz.casual:casual-client-standalone` (new module in `casual-java`)
* **Description**: A lightweight client library for pure Java SE applications without JCA, WildFly, or Quarkus container dependencies.
  * Multiplexed asynchronous request/reply with correlation UUIDs.
  * Outbound TCP connection establishment and domain connect handshake (`DOMAIN_CONNECT_REQUEST`).
  * Dynamic service discovery (`DOMAIN_DISCOVERY_REQUEST`).
  * High-level synchronous/asynchronous XATMI invocations (`tpcall`, `tpacall`).
* **Current Local Class**: `com.github.mattiasgreen.wiremock.casual.CasualTcpClient`

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

---

## 2. WireMock Extension Roadmap

- [x] Initial PoC: In-process Casual Domain Gateway with WireMock HTTP bridge.
- [x] Lightweight test client (`CasualTcpClient`) without Jakarta EE or real C++ domain.
- [x] Stub match discrimination (`Matched-Stub-Id`) distinguishing deliberate 404/error mocks from unmatched requests.
- [x] Transparent L7 Casual TCP proxy forwarding to upstream Casual domains.
- [x] Automatic stub record-and-replay via WireMock Admin API (`POST /__admin/mappings`).
- [ ] Refactor upstream candidate classes into isolated packages for seamless deletion once upstreamed.
- [ ] Implement WireMock `ExtensionFactory` and `ServerLifecycleListener` to bind gateway lifecycle to WireMock start/stop.
- [ ] Support `fielded` buffer conversion via `casual-fielded-annotations`.
- [ ] Admin API and UI visualization for Casual service mappings and stats.
