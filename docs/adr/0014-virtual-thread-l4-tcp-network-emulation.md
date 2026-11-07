# 14. Virtual-Thread L4 TCP Network Emulation

Date: 2026-11-07

## Status

Accepted

## Context

Distributed systems resilience testing requires simulating degraded and chaotic network conditions: latency spikes, bandwidth throttling, packet slicing, connection timeouts, and connection resets. Historically, teams either relied on external Docker containers running Shopify Toxiproxy or operating-system-level packet filtering (`tc-netem`, `iptables`).

External tools introduce significant friction in CI/CD pipelines, container orchestration overhead, and platform incompatibility (especially on macOS and Windows). Furthermore, existing mock tools only simulate application-layer (L7) delays (e.g. WireMock's fixed or logarithmic response delays), failing to reproduce transport-layer (L4) network pathologies such as TCP RST aborts, half-close socket hangs, or packet slicing during streaming.

## Decision

We introduce `wiremock-extension-tcp-netem`, an in-process transport-layer (L4) and application-layer (L7) chaos engine powered by Java 21 virtual threads:

### 1. Java 21 Virtual-Thread TCP Proxy Engine (`TcpNetEmProxyEngine`)
- Spawns lightweight virtual threads (`Thread.ofVirtual()`) per connection and directional stream (`upstream` and `downstream`).
- Scales to thousands of concurrent degraded connections without platform thread exhaustion or blocking OS kernel threads.
- Implements all 7 canonical Toxiproxy toxics:
  1. `latency`: Injects fixed latency and jitter distributions.
  2. `bandwidth`: Throttles throughput using token-bucket rate limiting.
  3. `slow_close`: Delays socket FIN handshake.
  4. `timeout`: Stalls traffic until client or server read timeouts trigger.
  5. `reset_peer`: Emits genuine TCP `RST` packets via `socket.setSoLinger(true, 0)`.
  6. `slicer`: Fragments payload byte streams into irregular packet chunks with interspersed delays.
  7. `limit_data`: Closes connections abruptly after transferring a set byte quota.

### 2. Full Toxiproxy v2 REST Compatibility (`TcpNetEmAdminEndpoint`)
- Mounts REST endpoints under `/__admin/tcp-netem/proxies` conforming directly to the Shopify Toxiproxy v2 API.
- Enables existing test suites, Toxiproxy client libraries (Java, Go, Python, Ruby, Node), and scripts to target WireMock directly without modification.

### 3. Integrated L7 Chaos Interceptor (`NetEmStubListener`)
- Bridges L4 socket chaos directly with WireMock stub mappings.
- Developers can configure network impairments declaratively inside standard WireMock stub JSON definitions or via ServeEventListeners.

### 4. Bounded Telemetry (`NetEmCircularJournal`)
- Records all injected impairments into a bounded, lock-free ring buffer.
- Protects against JVM heap exhaustion under high throughput while providing real-time auditing and UI visualization via `/__admin/tcp-netem/journal`.

## Consequences

### Positive
- **Zero Docker/Container Overhead**: Complete network chaos simulation runs inside standard JVM test runners, IDEs, and standalone WireMock binaries across Linux, Windows, and macOS.
- **True Kernel-Level TCP Behaviors**: Genuine `RST` packets and byte slicing reproduce edge cases impossible with L7 HTTP delay headers.
- **Drop-In Ecosystem Compatibility**: Replaces external Toxiproxy instances without modifying existing test client code.

### Negative / Trade-offs
- Requires Java 21 runtime for virtual threads (`Thread.ofVirtual`).
- In-process emulation operates in user space; cannot simulate low-level IP packet checksum corruption or hardware-level NIC drops.
