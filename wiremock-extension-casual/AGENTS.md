# AGENTS.md: `wiremock-extension-casual` Module Guidelines

Scoped instructions for `wiremock-extension-casual` (In-process Netty XATMI / Casual protocol domain gateway, transparent L7 TCP proxy, 2PC/XA coordinator forwarding, and synthetic XA participant voting).

---

## 1. Dual SourceSet Test Loops

`wiremock-extension-casual` separates fast Java SE Netty gateway tests (`src/test`) from heavy 3-node Quarkus/Narayana 2PC integration tests (`src/quarkusTest`):

1. **Loop 1 — Fast Gateway & Protocol Unit Tests (< 4s)**:
   ```bash
   ./gradlew :wiremock-extension-casual:test --tests "CasualWireMockGatewayTest" -q --console=plain
   ```
   - Exercises domain handshake, service discovery, `tpcall`, L7 proxy forwarding, `TPESVCFAIL` fault recording, and 2PC protocol handlers using the lightweight `CasualTcpClient`.
2. **Loop 2 — 3-Node 2PC/XA MitM Integration Suite (~12–15s)**:
   ```bash
   ./gradlew :wiremock-extension-casual:quarkusIntegrationTest -q --console=plain
   ```
   - Boots the 3-node `CasualIntegrationTestHarness` (Client Initiator $\rightarrow$ WireMock MitM Gateway $\rightarrow$ Upstream Casual Server) verifying distributed transaction commit, rollback, and chaos voting.

---

## 2. Package Isolation & Upstream Candidate Rules

To minimize Casual protocol maintenance inside `wiremock-extensions`, reusable protocol components live in target `se.laz.casual.*` packages slated for upstream contribution to [`casualcore/casual-java`](https://github.com/casualcore/casual-java):

- **Strict Dependency Direction**:
  - `com.github.mattiasgreen.wiremock.casual.*` **may** depend on `se.laz.casual.*` and WireMock.
  - `se.laz.casual.*` (`CasualFrameDecoder`, `CasualTcpClient`, `CasualServer`, `CasualBufferUtils`) must **NEVER** import `com.github.tomakehurst.wiremock.*` or `com.github.mattiasgreen.wiremock.*`.
- Activate the `casual-upstream-sync` skill when refactoring classes under `se.laz.casual.*`.

---

## 3. Protocol & Concurrency Invariants

1. **Netty `ByteBuf` Lifecycle**: Ensure all inbound `ByteBuf` instances in `CasualFrameDecoder` and channel handlers are properly released across both normal decoding and exception paths.
2. **Stub Match Discrimination**: Use the WireMock `Matched-Stub-Id` response header to distinguish a deliberate stubbed error (e.g., HTTP 404/500 mapped to `TPENOENT`/`TPESVCFAIL`) from an genuinely unmatched request that should be proxied upstream.
3. **Fault Recording Defaults**: Record upstream `TPESVCFAIL` application faults via `X-Casual-Error-State` header mappings, but never auto-record `TPENOENT` (service not found) by default.
