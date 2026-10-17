# Multi-Node Casual / WireMock 2PC Integration Test Suite (`quarkusTest`)

This directory (`src/quarkusTest/`) contains the isolated 3-node integration test suite for `wiremock-extension-casual`. It validates end-to-end Casual binary wire protocol interactions, Two-Phase Commit (2PC/XA) transaction coordination, transparent Man-in-the-Middle (MitM) proxying/recording, and synthetic XA chaos injection.

---

## 1. Environment Requirements

* **Zero External Infrastructure Required**:
  * **JDK 21** is the only requirement.
  * **No external Casual C++ domain, no Docker daemon, and no corporate Nexus credentials** are required.
  * All dependencies (`se.laz.casual:casual-api`, `se.laz.casual:casual-network-protocol`, Netty, and WireMock) are resolved directly from **Maven Central**.
* **Why In-Process Protocol Nodes Instead of External `-runner.jar` Binaries?**
  * While the `se.laz.casual:casual-quarkus` extension library is published to Maven Central, the sample applications in [`casualcore/casual-quarkus/tree/dev/example`](https://github.com/casualcore/casual-quarkus/tree/dev/example) (`casual-quarkus-example-client` and `casual-quarkus-example-server`) are **not** published to Maven Central as standalone executable `-runner.jar` artifacts.
  * Rather than requiring developers or CI pipelines to clone and build `casualcore/casual-quarkus` locally or fetch internal binaries, [`CasualIntegrationTestHarness`](java/com/github/mattiasgreen/wiremock/casual/integration/CasualIntegrationTestHarness.java) spins up all three Casual domain nodes in-process bound to real localhost TCP sockets using the official `se.laz.casual:casual-network-protocol` encoders and decoders.

---

## 2. How the 3-Node Topology is Orchestrated

[`CasualIntegrationTestHarness`](java/com/github/mattiasgreen/wiremock/casual/integration/CasualIntegrationTestHarness.java) starts and wires three distinct nodes in strict dependency order using ephemeral ports (`port(0)`) to guarantee zero port collisions:

```
┌─────────────────────────────────────────────────────────────────────────────────────────────────┐
│                         CasualIntegrationTestHarness Startup & Wiring                           │
├─────────────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                                 │
│  [3] ClientInitiatorNode           [2] CasualWireMockGateway           [1] UpstreamDomainNode   │
│      (Node 1: Coordinator)             (Node 2: MitM + WireMock)           (Node 3: Server B)   │
│                                                                                                 │
│  • Allocates global XID            • Netty Casual Server (:ephemeral)  • Netty Casual Server    │
│  • Sends SERVICE_CALL_REQUEST ───► • WireMock HTTP Server (:ephemeral)   (:ephemeral)           │
│  • Drives 2PC state machine:       • Routes unmatched calls & 2PC ───► • Hosts "casual/example" │
│    - PREPARE_REQUEST                 messages to Node 3 when enlisted  • Stateful XA Ledger:    │
│    - COMMIT_REQUEST                • Votes synthetic XA_OK (or chaos     - serviceCallCount     │
│    - REQUEST_ROLLBACK                override) when matched locally      - preparedXids         │
│                                                                          - committedXids        │
│                                                                          - rolledBackXids       │
└─────────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Startup Sequence (`@BeforeEach`)
1. **Node 3 — `UpstreamDomainNode` (Starts 1st)**:
   * Binds a Netty [`CasualServer`](../../main/java/se/laz/casual/server/CasualServer.java) to an ephemeral TCP port (`127.0.0.1:<upstreamPort>`).
   * Registers the `casual/example` service (`TransactionType.AUTOMATIC`) and implements [`CasualTransactionHandler`](../../main/java/se/laz/casual/server/CasualTransactionHandler.java).
   * Maintains an in-memory XA Resource Manager ledger (`getServiceCallCount()`, `getPreparedXids()`, `getCommittedXids()`, `getRolledBackXids()`) so tests can assert whether global transactions actually committed or rolled back on the upstream server.
2. **Node 2 — `CasualWireMockGateway` + `WireMockServer` (Starts 2nd)**:
   * Starts an embedded `WireMockServer` on a dynamic HTTP port.
   * Starts [`CasualWireMockGateway`](../../main/java/com/github/mattiasgreen/wiremock/casual/CasualWireMockGateway.java) on an ephemeral TCP port (`127.0.0.1:<mitmPort>`) configured with:
     * `.upstream("127.0.0.1", upstreamNode.getPort())`
     * `.recordStubs(true)`
     * `.recordFaults(true)`
     * `.recordNotFound(false)`
3. **Node 1 — `ClientInitiatorNode` (Starts 3rd)**:
   * Connects a [`CasualTcpClient`](../../main/java/se/laz/casual/client/CasualTcpClient.java) to `127.0.0.1:<mitmPort>` and performs the `DOMAIN_CONNECT_REQUEST` / `DOMAIN_CONNECT_REPLY` handshake.
   * Provides `executeInTransaction(serviceName, jsonPayload)` which models the Narayana/JTA + `casual-jca` 2PC coordinator lifecycle:
     1. Allocates a unique global `Xid` (`gtrid-quarkus-N`, `bqual-quarkus-N`, formatId `42`).
     2. Sends `SERVICE_CALL_REQUEST` carrying the `Xid` and JSON buffer.
     3. If the reply returns `ErrorState.OK` and `TransactionState.TX_ACTIVE`:
        * Sends `PREPARE_REQUEST(xid, resourceId)`.
        * If prepare returns `XA_OK`, sends `COMMIT_REQUEST(xid, resourceId)` and marks the transaction committed.
        * If prepare returns an error (e.g. `XAER_RMERR`), aborts the transaction without committing.
     4. If the reply returns an application error (e.g. `TPESVCFAIL`) or `TransactionState.ROLLBACK_ONLY`:
        * Sends `REQUEST_ROLLBACK(xid, resourceId)` and marks the transaction rolled back.

### Mid-Test Server Decoupling (`harness.stopUpstreamServer()`)
To prove that recorded stubs and synthetic XA voting completely decouple the client from the upstream Casual server, tests can call `harness.stopUpstreamServer()` mid-test. This closes Node 3's Netty listener while keeping Node 1 and Node 2 active.

---

## 3. Running the Integration Tests

Because this suite spins up multiple Netty event loops and TCP servers per test, it is intentionally isolated from the inner-loop `./gradlew test` and `./gradlew check` tasks.

Run the suite explicitly via:

```bash
./gradlew :wiremock-extension-casual:quarkusIntegrationTest
```

---

## 4. Test Scenarios Covered ([`CasualEndToEndIntegrationTest`](java/com/github/mattiasgreen/wiremock/casual/integration/CasualEndToEndIntegrationTest.java))

| Scenario | Test Method | What It Verifies |
| :--- | :--- | :--- |
| **1. Transparent 2PC Proxy Pass-Through** | `shouldProxyServiceCallAndTwoPhaseCommitTransparentlyToUpstreamServer` | With an empty WireMock stub store, a transactional call from Node 1 flows through Node 2 to Node 3, and both `PREPARE_REQUEST` and `COMMIT_REQUEST` for the `Xid` are forwarded to Node 3 (`committedXids` contains `xid`). |
| **2. Live Recording & Offline Replay** | `shouldRecordUpstreamCallAndReplayWithSynthetic2pcWhenUpstreamServerIsStopped` | Call #1 is proxied to Node 3 and recorded as a WireMock stub. Node 3 is then shut down (`stopUpstreamServer()`). Call #2 is served locally from the recorded stub and committed via Node 2's synthetic `XA_OK` voter while Node 3 is offline. |
| **3. Business Fault & Rollback Simulation** | `shouldTriggerClientRollbackWhenStubReturnsTpesvcfailAndRollbackOnly` | With Node 3 stopped, a stub returning `TPESVCFAIL`, user code `4001`, and `ROLLBACK_ONLY` causes Node 1 to abort the transaction and issue `REQUEST_ROLLBACK`, which Node 2 acknowledges with synthetic `XA_OK`. |
| **4. 2PC Phase 1 Chaos (`XAER_RMERR`)** | `shouldAbortGlobalTransactionWhenStubInjectsXaerRmerrDuringPrepare` | A stub succeeds during `SERVICE_CALL_REQUEST` (`ErrorState.OK`, `TX_ACTIVE`) but includes `X-Casual-XA-Prepare-Vote: XAER_RMERR`. When Node 1 sends `PREPARE_REQUEST`, Node 2 votes `XAER_RMERR`, aborting the global transaction during 2PC Phase 1. |
