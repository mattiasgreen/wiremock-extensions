# AGENTS.md: `wiremock-extension-casual` Module Guidelines

Scoped instructions for `wiremock-extension-casual` (In-process Netty XATMI / Casual protocol domain gateway, transparent L7 TCP proxy, 2PC/XA coordinator forwarding, and synthetic XA participant voting).

---

## 1. Dual SourceSet Test Loops

`wiremock-extension-casual` separates fast Java SE Netty gateway tests (`src/test`) from heavy 3-node Quarkus/Narayana 2PC integration tests (`src/quarkusTest`):

1. **Loop 1 — Fast Gateway & Protocol Unit Tests (< 4s)**:
   ```bash
   ./gradlew :wiremock-extension-casual:test --tests "CasualWireMockGatewayTest" -q --console=plain
   ```
   - Exercises domain handshake, service discovery, `tpcall`, L7 proxy forwarding, `TPESVCFAIL` fault recording, and 2PC protocol handlers using `CasualClient`.
   - In-memory fast unit tests using `InMemoryCasualStoreAdapter`:
   ```bash
   ./gradlew :wiremock-extension-casual:test --tests "CasualWireMockGatewayUnitTest" -q --console=plain
   ```
2. **Loop 2 — 3-Node 2PC/XA MitM Integration Suite (~12–15s)**:
   ```bash
   ./gradlew :wiremock-extension-casual:quarkusIntegrationTest -q --console=plain
   ```
   - Boots the 3-node `CasualIntegrationTestHarness` (Client Initiator $\rightarrow$ WireMock MitM Gateway $\rightarrow$ Upstream Casual Server) verifying distributed transaction commit, rollback, and chaos voting.

---

## 2. Package Architecture & Upstream Reuse

All extension classes live under `com.github.mattiasgreen.wiremock.casual.*` and directly reuse official `se.laz.casual:*` upstream libraries (3.7.3+):

- **Deep Modules & Clear Seams**:
  - `CasualWireMockGateway`: Thin orchestrator binding the network server to mediation and coordination.
  - `CasualStorePort` / `WireMockStoreAdapter` / `HttpCasualStoreAdapter` / `InMemoryCasualStoreAdapter`: Explicit store seam separating in-process WireMock stores (`WireMockServices`/`Options`/`Stores`, zero reflection), remote HTTP Admin access, and pure in-memory unit-test storage.
  - `CasualHttpMediation`: Deep translation between Casual XATMI buffers and HTTP status/headers/payloads.
  - `CasualTransactionCoordinator`: Multi-upstream 2PC transaction branch enlistment, atomic propagation, and synthetic XA voting overrides.
  - `CasualClient`: Unified client leveraging upstream `NettyNetworkConnection` and `CorrelatorImpl` for auto-negotiated outbound framing.
  - `CasualServer`: Slim in-process Netty inbound server using standard upstream `CasualNWMessageDecoder` and `CasualNWMessageEncoder`.
- Upstream `casualcore/casual-java` components are imported directly as library dependencies; no custom classes shadow `se.laz.casual.*`.

---

## 3. Protocol & Concurrency Invariants

1. **Netty `ByteBuf` Lifecycle**: Ensure all inbound `ByteBuf` instances in channel handlers and encoders/decoders are properly released across both normal and exception paths.
2. **Stub-Driven Dynamic Proxying & Microsecond Probing**: Use WireMock proxy stubs (`targetBaseUrl: casual://host:port`) with in-memory probing via `CasualStorePort` to evaluate priority, headers, and body patterns with zero HTTP loopback overhead. Forward matching calls natively socket-to-socket over TCP.
3. **Multi-Upstream 2PC Stickiness**: 2PC frames (`prepare`, `commit`, `rollback`) lack `serviceName` and only contain `Xid`. Enlisted upstream clients are tracked per `Xid` inside `CasualTransactionCoordinator` to ensure atomic multi-upstream 2PC propagation.
4. **Human-Readable Buffer Mediation**: Convert Fielded buffers (`.FIELDED`/`CFIELD/`) to/from structured JSON, strip null-terminators for `CSTRING/`, and preserve text in WireMock's Request Journal and offline stubs via `CasualHttpMediation`.
5. **Bidirectional L7 Protocol Normalization Invariant**:
   - Outbound service replies sent by `CasualServer` MUST strictly match the client's negotiated `connVersion` (`SERVICE_CALL_REPLY` / 3101 with `XID` for `< 1.3`, `SERVICE_CALL_REPLY_V_1_3` / 3103 for `1.3–1.4`, `SERVICE_CALL_REPLY_V_1_5` / 3105 for `>= 1.5`).
   - Outbound proxied service requests sent by `CasualClient` to upstream domains MUST match `upstreamClient.getProtocolVersion()` (`SERVICE_CALL_REQUEST` / 3100 for `< 1.3`, `SERVICE_CALL_REQUEST_V_1_3` / 3102 for `1.3–1.4`, `SERVICE_CALL_REQUEST_V_1_5` / 3104 for `>= 1.5`).
   - `CasualProtocolConfig` defaults `CASUAL_NETWORK_PROTOCOL_STRICT_VALIDATION` to `false` for legacy WildFly `<= 3.4.4` 8-byte padding interoperability unless overridden via system property/env/config file.
   - Never leak mismatched protocol frames (such as `3103` or `3105` to a Protocol 1.2 client, or `3105` to a Protocol 1.4 client); doing so triggers `DecoderException: Unknown message type`, `CasualProtocolException`, or permanent TCP byte-stream desynchronization.
6. **Virtual-Thread Request Offload, Fire-and-Forget (`TPNOREPLY`), & Split-Horizon Loop Safety**:
   - `CasualServer` keeps `DOMAIN_CONNECT_REQUEST` synchronous on the Netty I/O event loop (protocol negotiation) and offloads service, discovery, queue, and 2PC request handlers onto `Executors.newVirtualThreadPerTaskExecutor()`, preventing callback deadlocks when proxied calls re-enter the same gateway.
   - `CasualClient.forwardServiceCall` and `CasualServer.handleServiceCall` honor `AtmiFlags.TPNOREPLY` via `requestNoReply(...)`, suppressing unsolicited wire replies and skipping XA enlistment/transaction recording on `TPNOREPLY`/`TPNOTRAN`.
   - `CasualWireMockGateway` advertises only real local stub/pattern matches or upstream-discovered services (no synthetic catch-all fabrication), applies split-horizon domain filtering (`requesterDomainId == remoteDomainId`) + execution deduplication during discovery, and fails fast with `ErrorState.TPENOENT` on in-flight proxy routing cycles.

---

## 4. Direct Git Upstream Repositories (`casualcore/casual-java` & `casualcore/casual-quarkus`)

Instead of decompiling bytecode or downloading source JARs manually, work directly with the actual upstream Git repositories cloned locally under `.upstream/` (git-ignored):

1. **One-Time Sync / Clone**:
   Run `.agents/scripts/sync-upstream-casual.ps1` (or clone manually with `core.longpaths=true`):
   ```bash
   powershell -ExecutionPolicy Bypass -File .agents/scripts/sync-upstream-casual.ps1
   # Or manual clone (Windows requires core.longpaths true due to deep Java EE package paths):
   git clone -c core.longpaths=true --filter=blob:none https://github.com/casualcore/casual-java.git .upstream/casual-java
   git clone -c core.longpaths=true --filter=blob:none https://github.com/casualcore/casual-quarkus.git .upstream/casual-quarkus
   ```
2. **Git Log, Diffs & Release Tags**:
   - Inspect commit logs and PR history directly:
     ```bash
     cd .upstream/casual-java
     git log --oneline -S "DecoderReaderValidator"
     git show <commit_hash>
     ```
   - Checkout specific version tags matching `gradle/libs.versions.toml`:
     ```bash
     git checkout 3.7.3
     git diff 3.4.0..3.5.0 -- casual/casual-network-protocol/
     ```
3. **Upstream Contribution Preparation**:
   - Create branches in `.upstream/casual-java` when proposing enhancements or bug fixes for `se.laz.casual.*`.
   - Never decompile bytecode; always use full git sources, commit history, and upstream test suites.
