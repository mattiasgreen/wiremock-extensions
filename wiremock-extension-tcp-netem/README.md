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
* **Per-Request `X-Correlation-Id` Scoping**: Selectively injects impairments only for matching HTTP sessions or test threads across persistent HTTP/1.1 keep-alive connections.
* **Toxiproxy v2 REST API Compatibility**: Exposes endpoints compatible with official Toxiproxy SDKs (`/proxies`, `/proxies/{name}/toxics`, `/proxies/reset`).
* **Dedicated NetEm Admin Routes**: `/__admin/tcp-netem/proxies`, `/__admin/tcp-netem/reset`, and one-click presets (`3G_MOBILE`, `FLAKY_WIFI`, `BLACK_HOLE`, `CHAOS_MONKEY`).

---

## 2. Admin API Endpoints

| Method | Route | Description |
| :--- | :--- | :--- |
| `GET` | `/__admin/tcp-netem/proxies` | List all active proxies, ports, toxics, and live traffic stats. |
| `POST` | `/__admin/tcp-netem/proxies` | Register a new proxy (`name`, `listenPort`, `targetHost`, `targetPort`). |
| `GET` | `/__admin/tcp-netem/proxies/{name}` | Inspect proxy details and active toxics. |
| `DELETE` | `/__admin/tcp-netem/proxies/{name}` | Stop and remove a proxy. |
| `POST` | `/__admin/tcp-netem/proxies/{name}/toxics` | Add or update a toxic impairment on the proxy. |
| `DELETE` | `/__admin/tcp-netem/proxies/{name}/toxics/{toxic}` | Remove an active toxic from the proxy. |
| `POST` | `/__admin/tcp-netem/proxies/{name}/presets/{preset}` | Apply a preset (`3G_MOBILE`, `FLAKY_WIFI`, `BLACK_HOLE`, `CHAOS_MONKEY`). |
| `POST` | `/__admin/tcp-netem/reset` | Clear all toxics and re-enable all proxies globally. |

---

## 3. Example: Adding a Selective Latency Toxic

```bash
curl -X POST http://localhost:8080/__admin/tcp-netem/proxies/wiremock-netem/toxics \
  -H "Content-Type: application/json" \
  -d '{
    "name": "mobile-delay",
    "type": "latency",
    "stream": "downstream",
    "correlationId": "session-test-42",
    "attributes": {
      "latency": 300,
      "jitter": 50
    }
  }'
```
Requests carrying `X-Correlation-Id: session-test-42` will experience 300ms $\pm$ 50ms latency, while untargeted traffic completes with normal performance.
