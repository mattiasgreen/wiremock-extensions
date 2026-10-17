# AGENTS.md: `wiremock-extension-tcp-netem` Module Guidelines

Scoped instructions for `wiremock-extension-tcp-netem` (In-process Java 21 virtual-thread L4 TCP network emulation, Toxiproxy v2 REST compatibility, and L7 WireMock chaos interception).

---

## 1. Fast Verification Loops

- **Targeted Test Class (< 4s)**:
  ```bash
  ./gradlew :wiremock-extension-tcp-netem:test --tests "ToxiproxyProtocolCompatibilityTest" -q --console=plain
  ```
- **Full Module Suite (< 8s)**:
  ```bash
  ./gradlew :wiremock-extension-tcp-netem:test -q --console=plain
  ```

---

## 2. Architectural & Concurrency Invariants

1. **Dual-Ingress Architecture**:
   - **L4 TCP Proxy Engine (`TcpNetEmProxyEngine`)**: Uses Java 21 virtual threads (`Thread.ofVirtual()`) to pump sockets across all 7 Toxiproxy toxics (`latency`, `bandwidth`, `slow_close`, `timeout`, `reset_peer`, `slicer`, `limit_data`).
   - **Toxiproxy v2 Admin API (`TcpNetEmAdminEndpoint`)**: Exposes `/__admin/tcp-netem/proxies` and `/__admin/tcp-netem/journal` so unmodified Toxiproxy clients can control chaos.
   - **L7 Stub/Filter Interceptor (`NetEmStubListener`)**: Evaluates declarative chaos rules on WireMock HTTP serve events.
2. **True TCP RST Semantics**: For `reset_peer`, configure `socket.setSoLinger(true, 0)` before closing so the OS kernel dispatches a genuine `RST` packet rather than a graceful `FIN`.
3. **Bounded Telemetry**: Always record impairment events into `NetEmCircularJournal` (lock-free/bounded ring buffer) to prevent heap exhaustion under high load.
