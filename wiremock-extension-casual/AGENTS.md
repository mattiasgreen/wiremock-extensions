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

All extension classes live under `com.github.mattiasgreen.wiremock.casual.*` and directly reuse official `se.laz.casual:*` upstream libraries (3.4.0+):

- **Deep Modules & Clear Seams**:
  - `CasualWireMockGateway`: Thin orchestrator binding the network server to mediation and coordination.
  - `CasualStorePort` / `WireMockStoreAdapter` / `InMemoryCasualStoreAdapter`: Clean store seam isolating WireMock HTTP matching from reflection and internal state.
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
   - Outbound service replies sent by `CasualServer` MUST strictly match the client's negotiated `connVersion` (`SERVICE_CALL_REPLY` / 3101 with `XID` for `< 1.3`, `SERVICE_CALL_REPLY_V_1_3` / 3103 for `>= 1.3`).
   - Outbound proxied service requests sent by `CasualClient` to upstream domains MUST match `upstreamClient.getProtocolVersion()`.
   - Never leak Protocol 1.3+ frames (such as `3103`) to a Protocol 1.2 client or vice versa; doing so triggers `DecoderException: Unknown message type` and permanent TCP byte-stream desynchronization.

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
     git checkout 3.4.0
     git diff 3.4.0..3.5.0 -- casual/casual-network-protocol/
     ```
3. **Upstream Contribution Preparation**:
   - Create branches in `.upstream/casual-java` when proposing enhancements or bug fixes for `se.laz.casual.*`.
   - Never decompile bytecode; always use full git sources, commit history, and upstream test suites.
