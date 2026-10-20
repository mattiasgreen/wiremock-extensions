# `wiremock-extension-tcp-netem`

In-process **Layer 4 TCP Network Emulator & Fault Injector** for WireMock Standalone (JDK 21).

Re-implements Shopify's Toxiproxy primitives natively inside the JVM using **Java 21 Virtual Threads (`Thread.ofVirtual()`)** without requiring external Go or Docker sidecar containers.

---

## 1. Key Capabilities

* **Dual-Ingress Architecture**: Dedicated NetEm ingress port (`8081` / dynamic) forwarding to WireMock's HTTP port (`8080`), ensuring the WireMock Admin API and Web UI remain fully insulated against connection resets and blackhole timeouts.
* **All 7 Toxiproxy Toxic Primitives**:
  * `latency`: Configurable base delay with random jitter.
  * `bandwidth`: Rate-limited byte streaming (KB/s).
  * `reset_peer`: Hard TCP `RST` via `socket.setSoLinger(true, 0); socket.close();`.
  * `timeout`: Indefinite or timed network blackhole.
  * `slow_close`: Delays socket teardown upon EOF/FIN detection.
  * `slicer`: Fragments packet buffers into micro-slices with inter-slice pauses.
  * `limit_data`: Abruptly cuts connection after transferring $N$ bytes.
* **Zero Blast-Radius Protocol-Agnostic Stream Scoping**: Selectively injects impairments only for matching raw byte stream sequences (ASCII substrings or raw Hex sequences for binary protocols like Casual/Tuxedo) or client socket IP/CIDR masks across persistent TCP connections. Untargeted test traffic running concurrently runs at full line rate with zero blast radius.
* **High-Throughput Lock-Free Circular Journal**: Pre-allocated ring buffer in `AtomicReferenceArray` tracking transfer sizes, latency, client IP, target scope, and toxic verdicts without object churning or heap fragmentation.
* **Declarative Stub Mapping Metadata**: Define network impairments directly inside WireMock stub mapping JSON under `metadata.netem`.
* **Toxiproxy v2 REST API Compatibility**: Exposes endpoints compatible with official Toxiproxy SDKs (`/proxies`, `/proxies/{name}/toxics`, `/proxies/reset`).
* **Dedicated NetEm Admin Routes**: `/__admin/tcp-netem/proxies`, `/__admin/tcp-netem/reset`, and one-click presets (`3G_MOBILE`, `FLAKY_WIFI`, `BLACK_HOLE`, `CHAOS_MONKEY`).

---

## 2. L4 TCP NetEm vs. WireMock L7 Fault Injection

WireMock has built-in response delay (`withFixedDelay()`) and HTTP faults (`Fault.MALFORMED_RESPONSE_CHUNK`, `EMPTY_RESPONSE`). How does `tcp-netem` compare?

| Dimension | WireMock L7 Built-in Faults | `wiremock-extension-tcp-netem` (L4 NetEm) |
| :--- | :--- | :--- |
| **OSI Layer** | Layer 7 (Application / HTTP servlet layer). | Layer 4 (Transport / Raw TCP stream). |
| **Bandwidth Throttling** | Not supported (delays whole response; cannot drip bytes at $N$ KB/s). | True streaming rate limiting per socket or packet slice. |
| **TCP RST Simulation** | Closes HTTP response socket (often perceived as clean FIN). | Sends hard TCP `RST` packet (`SO_LINGER = 0`), triggering `ECONNRESET` / `SocketException: Connection reset`. |
| **Packet Fragmentation** | Sends entire HTTP chunks. | `slicer` breaks TCP frames into micro-packets with delay, triggering TCP reassembly timeouts. |
| **Connection Blackhole** | Keeps HTTP request pending until server thread times out. | Drops incoming/outgoing TCP traffic entirely (`timeout`), testing OS socket read timeouts. |
| **Directionality** | Downstream only (response delay). | Both **upstream** (client payload/upload) and **downstream** (response stream). |
| **Blast Radius Isolation** | Requires mapping per stub or global delay. | Scoped down to byte patterns (ASCII/Hex) or Client IP/CIDR on shared sockets! |

---

## 3. Dual-Ingress Architecture

```
                      ┌──────────────────────────────────────────────┐
                      │            Client Test Suites                │
                      └───────┬──────────────────────────────┬───────┘
                              │                              │
                     [Direct HTTP Traffic]          [Impaired Traffic]
                     [Admin API & Web UI]        [Pattern/IP Matched Traffic]
                              │                              │
                              ▼                              ▼
                     ┌──────────────────┐          ┌──────────────────┐
                     │  WireMock Core   │          │   NetEm Proxy    │
                     │    Port 8080     │          │    Port 8081     │
                     └────────▲─────────┘          └────────┬─────────┘
                              │                             │
                              │     L4 Forwarding Socket    │
                              └─────────────────────────────┘
```

1. **Port 8080 (WireMock Core)**: Serves standard HTTP stubs, Admin API (`/__admin/...`), metrics, and Web Console UI. Completely insulated against TCP resets, drops, or bandwidth choking.
2. **Port 8081 (NetEm Ingress)**: Transparent Layer 4 TCP proxy forwarding to port 8080 while executing virtual thread impairment pipes (`NetEmPipe`).

---

## 4. Circular Event Journal & JVM Heap Sizing

The NetEm engine maintains an in-memory, circular ring buffer (`NetEmCircularJournal`) to record traffic metrics and impairment verdicts for observability and debugging.

### Memory Footprint & Compact Representation
Each event is recorded as an immutable Java record `NetEmJournalEntry`:
- **Fields**: `id` (long), `timestamp` (long), `direction` (enum), `bytesTransferred` (int), `toxicApplied` (String), `scope` (String), `clientIp` (String), `durationMs` (long).
- **Size**: Approximately **80 to 128 bytes** per entry on 64-bit JVMs with Compressed OOPs (`-XX:+UseCompressedOops`).
- **Data Structure**: Pre-allocated `AtomicReferenceArray<NetEmJournalEntry>`. Zero allocations on circular wrap-around, avoiding garbage collection pauses and heap fragmentation under heavy test loads.

### Sizing Formula & Recommendations

$$\text{Heap Allocation for Journal} = N_{\text{capacity}} \times 128 \text{ bytes}$$

| Ring Buffer Size ($N$) | Approx. Memory Footprint | Recommended Use Case | Recommended JVM Heap |
| :--- | :--- | :--- | :--- |
| **10,000** | ~1.2 MB | Local development & single-suite CI | `-Xms256m -Xmx512m` |
| **50,000** *(Default)* | ~6.0 MB | Shared CI runners with parallel suites | `-Xms512m -Xmx1g` |
| **200,000** | ~24.0 MB | High-throughput central enterprise cluster | `-Xms1g -Xmx2g` |

To configure the journal size programmatically:
```java
TcpNetEmProxy proxy = TcpNetEmProxy.builder()
    .name("wiremock-netem")
    .listenPort(8081)
    .targetPort(8080)
    .journalCapacity(50_000)
    .build();
```

---

## 5. Admin API Endpoints

| Method | Route | Description |
| :--- | :--- | :--- |
| `GET` | `/__admin/tcp-netem/proxies` | List all active proxies, ports, toxics, and live traffic stats. |
| `POST` | `/__admin/tcp-netem/proxies` | Register a new proxy (`name`, `listenPort`, `targetHost`, `targetPort`). |
| `GET` | `/__admin/tcp-netem/proxies/{name}` | Inspect proxy details and active toxics. |
| `DELETE` | `/__admin/tcp-netem/proxies/{name}` | Stop and remove a proxy. |
| `POST` | `/__admin/tcp-netem/proxies/{name}/toxics` | Add or update a toxic impairment on the proxy (`pattern`, `patternType`, `clientIp`). |
| `DELETE` | `/__admin/tcp-netem/proxies/{name}/toxics/{toxic}` | Remove an active toxic from the proxy. |
| `POST` | `/__admin/tcp-netem/proxies/{name}/presets/{preset}` | Apply a preset (`3G_MOBILE`, `FLAKY_WIFI`, `BLACK_HOLE`, `CHAOS_MONKEY`). |
| `GET` | `/__admin/tcp-netem/proxies/{name}/journal` | Query recorded network events. Supports `?limit=100` and `?scope=...` (or `?correlationId=...`). |
| `DELETE` | `/__admin/tcp-netem/proxies/{name}/journal` | Clear the circular event journal for this proxy. |
| `POST` | `/__admin/tcp-netem/reset` | Clear all toxics and re-enable all proxies globally. |

---

## 6. Declarative Stub Metadata (`metadata.netem`)

You can attach network impairments directly to WireMock stub definitions. When the stub matches, `NetEmStubListener` automatically installs the toxic scoped to the upstream stream:

```json
{
  "request": {
    "method": "GET",
    "url": "/api/payments/slow-gateway"
  },
  "response": {
    "status": 200,
    "body": "{\"status\":\"PAID\"}",
    "headers": {
      "Content-Type": "application/json"
    }
  },
  "metadata": {
    "netem": {
      "type": "latency",
      "stream": "downstream",
      "attributes": {
        "latency": 500,
        "jitter": 50
      }
    }
  }
}
```

---

## 7. Zero Blast-Radius Parallel Testing with Resilience4j

See the end-to-end integration test in [`wiremock-examples`](../wiremock-examples/src/test/java/com/github/mattiasgreen/wiremock/examples/NetEmResilienceParallelIntegrationTest.java) for a complete reference implementation demonstrating:
- A Resilience4j Circuit Breaker / Retry client calling WireMock via NetEm port `8081`.
- Thread A injecting TCP resets or blackhole timeouts tagged with stream pattern `chaos-session-77`.
- Thread B simultaneously executing fast functional queries with a different session identifier.
- Thread A trips the circuit breaker cleanly while Thread B succeeds with 100% throughput and 0% errors.
