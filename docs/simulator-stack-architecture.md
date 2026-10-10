# Architecture Blueprint: The Unified WireMock Simulator Stack

This document explores how to transform WireMock from an HTTP mock server into a comprehensive, single-process **"Simulator Stack"** that incorporates:
1. **Network Chaos & Fault Injection** (in-process Toxiproxy re-implementation)
2. **Callbacks & Webhooks** (OpenAPI callback synthesis, state-triggered dispatches, and outbox journal)
3. **AsyncAPI & Event-Driven Mocking** (WebSockets, SSE, in-memory topic broker, spec ingestion)
4. **Cross-Protocol State Correlation** (tying HTTP, events, chaos, and webhooks under unified session IDs)

---

## 1. The Problem: Container & Tooling Sprawl

Modern event-driven and microservice architectures require testing across multiple protocols and failure modes:

```
Typical Microservice Under Test
       ├── REST / HTTP APIs               ──> WireMock Container
       ├── Flaky Network / Packet Drops    ──> Toxiproxy Container
       ├── Out-of-band Webhooks           ──> Custom Node.js / Python Webhook Mock
       └── Kafka / WebSockets Event Bus    ──> Redpanda / Kafka / RabbitMQ Container
```

### The Pitfalls of Orchestrated Multi-Container Stacks (Helm / Compose):
* **Resource Heaviness**: Running 4–6 separate containers requires 2–4 GB RAM and dozens of open ports.
* **Flaky CI/CD & Slow Spin-up**: Kafka / Redpanda alone takes 10–20 seconds to boot, causing test timeouts.
* **Siloed State & Lack of Correlation**: A test cannot easily correlate an HTTP request with an event published to Kafka, a subsequent webhook, or a simulated network failure under the same `X-Correlation-Id`.
* **Configuration Overhead**: Complex Helm charts, port collision headaches, and multi-service health checks.

### The Solution: Single-Process "Simulator Studio"
By embedding these capabilities directly into WireMock via modern Java 21 features (Virtual Threads, standard NIO, SPI), we achieve:
* **Zero Container Sprawl**: Single JVM process / single standalone JAR.
* **Sub-second Startup (< 1.5s)** with < 120MB baseline RAM.
* **Unified Correlation & Journal**: A single request trace links the inbound HTTP call, injected chaos, generated async event, and outbound webhook.
* **Unified Web UI**: One console (`/__admin/ui`) to inspect and manipulate the entire simulation.

```
┌────────────────────────────────────────────────────────────────────────┐
│                   Unified WireMock Simulator Stack                     │
│                                                                        │
│   ┌───────────────────┐  ┌───────────────────┐  ┌──────────────────┐   │
│   │   REST / HTTP     │  │   State Machine   │  │   Network Chaos  │   │
│   │   (WireMock Core  │  │ (wiremock-        │  │ (Virtual-Thread  │   │
│   │   + OpenAPI)      │  │  extension-       │  │  Toxiproxy Proxy │   │
│   │                   │  │  stateful AST)    │  │  + L7 Faults)    │   │
│   └─────────┬─────────┘  └─────────┬─────────┘  └────────┬─────────┘   │
│             │                      │                     │             │
│             ▼                      ▼                     ▼             │
│   ┌────────────────────────────────────────────────────────────────┐   │
│   │              Unified Correlation & Event Bus                   │   │
│   │        Session Isolation Engine (X-Correlation-Id)             │   │
│   └─────────┬──────────────────────┬─────────────────────┬─────────┘   │
│             │                      │                     │             │
│             ▼                      ▼                     ▼             │
│   ┌───────────────────┐  ┌───────────────────┐  ┌──────────────────┐   │
│   │  AsyncAPI Engine  │  │ Webhook Outbox    │  │ Embedded Web UI  │   │
│   │  (WebSockets,     │  │ (OpenAPI Callbacks│  │ (Unified Console │   │
│   │   SSE, Virtual    │  │  + Retries        │  │  Zero NPM/Bundler│   │
│   │   Topic Broker)   │  │  + Journal)       │  │  Single SPA)     │   │
│   └───────────────────┘  └───────────────────┘  └──────────────────┘   │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Network Chaos Engine (`wiremock-extension-chaos`)

### Re-writing Toxiproxy in Java 21
Shopify's Toxiproxy is a TCP proxy written in Go that intercepts connections between a client and an upstream target to simulate network degradation. 

In Java 21, **Virtual Threads (`Thread.ofVirtual()`)** allow us to write a lightweight, non-blocking TCP proxy directly inside WireMock with no external C or Go dependencies:
* Each inbound client connection spawns two virtual threads (Upstream -> Downstream, Downstream -> Upstream).
* Memory overhead per connection is negligible (~few kilobytes vs ~1MB for platform threads).
* Can run on any designated proxy port (e.g. 8474, 9090) forwarding to WireMock's own HTTP port or to arbitrary external services (PostgreSQL, Redis, gRPC).

### The Toxic Pipeline
All 7 standard Toxiproxy toxics can be implemented natively:

| Toxic | Mechanism in Java 21 | Simulation Use Case |
| :--- | :--- | :--- |
| **`latency`** | `Thread.sleep(latencyMs + randomJitter)` before forwarding bytes. | High latency, 3G mobile network, cross-region lag. |
| **`bandwidth`** | Token-bucket or chunk-timed rate limiter restricting byte throughput. | Slow connection, mobile bandwidth throttling. |
| **`slow_close`** | Delay socket closure when EOF / FIN is detected (`Thread.sleep()`). | Graceful shutdown timeout testing. |
| **`timeout`** | Hold connection open without forwarding data until timeout or indefinitely. | Silent network partition, black hole. |
| **`reset_peer`** | `socket.setSoLinger(true, 0); socket.close();` — forces immediate TCP `RST`. | Hard connection crash, process killed without FIN. |
| **`slicer`** | Split byte buffers into small fragments (e.g. 64 bytes) with intermittent delay. | TCP packet fragmentation and framing bugs. |
| **`limit_data`** | Abruptly close connection after $N$ bytes have been transferred. | Truncated responses, download failures. |

### Dual-Layer Chaos Architecture:
1. **L4 Layer (TCP Chaos Proxy)**:
   - Fronts any TCP connection.
   - Exposes Toxiproxy v2 compatible REST API (`POST /proxies/{name}/toxics`, `DELETE /proxies/{name}/toxics/{toxic}`), allowing existing Toxiproxy SDKs (Java, Python, Node, Go) to talk directly to WireMock!
2. **L7 Layer (WireMock HTTP Chaos Interceptor)**:
   - WireMock `RequestFilterV2` and `ResponseTransformerV2` that injects chaos conditionally based on HTTP path, headers, or `X-Correlation-Id`.
   - Allows declaring chaos directly in stub mappings:
     ```json
     {
       "request": { "url": "/api/v1/payments" },
       "response": { "status": 200, "body": "OK" },
       "postServeActions": [{
         "name": "chaos",
         "parameters": {
           "toxic": "reset_peer",
           "probability": 0.25
         }
       }]
     }
     ```

---

## 3. Asynchronous Callbacks & Webhooks Engine

WireMock includes basic webhook support (`org.wiremock.webhooks.Webhooks`), but in modern enterprise architectures, webhooks must be:
1. **Synthesized automatically from OpenAPI 3.0 / 3.1 specifications**.
2. **Triggered by stateful state-machine transitions**.
3. **Auditable with an Outbox Journal and manual re-triggering**.

### 1. OpenAPI 3.0/3.1 `callbacks` Ingestion
OpenAPI explicitly supports asynchronous callbacks via the `callbacks` object:
```yaml
/v1/subscriptions:
  post:
    responses:
      '202':
        description: Subscription pending
    callbacks:
      subscriptionNotification:
        '{$request.body#/callbackUrl}':
          post:
            requestBody:
              content:
                application/json:
                  schema:
                    $ref: '#/components/schemas/SubscriptionEvent'
            responses:
              '200':
                description: Webhook received
```
**Simulator Stack Feature**:
- When `wiremock-extension-openapi` imports a spec with `callbacks`, it synthesizes:
  - An HTTP stub responding with `202 Accepted`.
  - An automatic Post-Serve Webhook action sending a generated synthetic payload to `{{jsonPath request.body '$.callbackUrl'}}` after a configurable delay (e.g., 500ms).

### 2. Stateful Event-Triggered Webhooks
In `wiremock-extension-stateful`, workflows often require async callbacks upon state changes:
```java
// Java AST DSL:
model.on(post("/api/v1/loans/apply"))
     .mutate(applyForLoan())
     .webhook(
         post("https://client-service/webhooks/loan-decision")
             .delayMs(1500)
             .body("{\"loanId\": \"${state.loanId}\", \"status\": \"APPROVED\"}")
     )
     .respond(json("{\"status\": \"PENDING\"}").status(202));
```

### 3. Webhook Outbox Journal in Web UI
- A dedicated **Outbox** tab in WireMock Console:
  - Shows pending, scheduled, succeeded, and failed webhooks.
  - Displays payload, target URL, response status code, and delivery latency.
  - Interactive "🚀 Replay / Trigger Webhook" button for manual test verification.

---

## 4. AsyncAPI & Event-Driven Mocking (`wiremock-extension-asyncapi`)

AsyncAPI is the open standard for event-driven systems (Kafka, RabbitMQ, WebSockets, MQTT, SSE). Bringing event mocking inside WireMock eliminates the single biggest driver of container sprawl in integration testing.

### Supported Protocols (Zero External Infrastructure)

#### Protocol A: WebSockets & Server-Sent Events (SSE)
- **Zero footprint**: Runs directly on WireMock's Jetty server or an in-process Java 21 WebSocket endpoint.
- Clients connect to `ws://localhost:8080/__async/channels/{channelName}` or `GET /__async/channels/{channelName}` (SSE).
- Can broadcast simulated events periodically, in response to HTTP requests, or on demand via the UI.

#### Protocol B: In-Process Virtual Topic Broker
- Lightweight, in-memory topic broker with:
  - Pub/Sub channels with topic wildcards (e.g. `orders.*`, `payments.completed`).
  - Message retention & offset cursor tracking.
  - Optional embedded Kafka/MQTT protocol adapters (or REST/WebSocket API wrappers).
- Allows applications to publish messages via HTTP (`POST /__admin/async/publish`) or WebSockets, and consume them via long-polling, SSE, or WebSocket subscriptions.

### Bidirectional Event Bridge: HTTP ⇄ Async
In real systems, HTTP and events are tightly coupled:
1. **HTTP -> Event**:
   - Calling `POST /api/v1/orders` returns `201 Created` and automatically publishes an `OrderPlaced` event to channel `orders.created`.
2. **Event -> HTTP / Webhook**:
   - Publishing an event `PaymentSettled` triggers an outbound webhook or advances a stateful workflow in `wiremock-extension-stateful`.

### AsyncAPI Spec Ingestion (`POST /__admin/asyncapi/import`)
- Ingests `asyncapi.yaml` (2.x and 3.x).
- Parses `channels`, `operations`, and `messages` (JSON Schema / Avro payloads).
- Generates:
  - Topic endpoints with synthetic payload generation (leveraging existing schema synthesis logic from `wiremock-extension-openapi`).
  - Automated publishers (e.g. emit every $N$ seconds).
  - Mock subscribers that validate inbound message schemas.

---

## 5. UI Integration & Developer Experience

The Web UI (`wiremock-extension-ui`) expands to provide a single pane of glass:

```
┌────────────────────────────────────────────────────────────────────────┐
│ WireMock Console | 🌐 Stubs  ⚡ Dynamic  🌪️ Chaos  📬 Events  🪝 Outbox  📜 Journal │
├────────────────────────────────────────────────────────────────────────┤
│ 🌪️ Chaos / Toxiproxy Controller                                        │
│                                                                        │
│ Proxy: [ wiremock-http (port 8474 -> 8080) ▼ ]                         │
│                                                                        │
│ Active Toxics:                                                         │
│ ┌────────────────────────────────────────────────────────────────────┐ │
│ │ ⏱️ Latency: 350ms (±50ms jitter)                   [Enabled] [🗑️]   │ │
│ │ ⚡ Bandwidth: 64 KB/s                             [Disabled] [🗑️]   │ │
│ │ 💥 Peer Reset (TCP RST): 10% probability           [Enabled] [🗑️]   │ │
│ └────────────────────────────────────────────────────────────────────┘ │
│                                                                        │
│ Quick Presets:                                                         │
│ [ 📶 3G Mobile Network ] [ ✈️ Flaky Airplane Wi-Fi ] [ 💥 Chaos Monkey ] │
└────────────────────────────────────────────────────────────────────────┘
```

1. **🌪️ Chaos Tab**:
   - Enable/disable toxics with one toggle.
   - Presets: "Flaky Connection" (15% drop), "Mobile 3G" (250ms latency + 64KB/s bandwidth), "Blackhole Timeout".
   - Live traffic & dropped packet counters.
2. **📬 Events / AsyncAPI Tab**:
   - List channels (`orders.created`, `payments.settled`).
   - Spec viewer & synthetic payload inspector.
   - Interactive Event Publisher & Live WebSocket/SSE streaming terminal.
3. **🪝 Outbox Tab**:
   - Live table of asynchronous webhooks dispatched by WireMock.
   - Inspect payloads, delivery status, retry attempts.
   - Button to manually trigger a callback.

---

## 6. Subproject Module Topology

To keep the suite decoupled, modular, and adhering to our zero-NPM constraint:

| Subproject | Responsibility | Primary Dependencies |
| :--- | :--- | :--- |
| **`wiremock-extension-chaos`** | In-process TCP proxy with Virtual Threads + Toxiproxy REST API + WireMock L7 chaos filters. | Standard Java 21 `java.net`, WireMock core (no external proxy libs). |
| **`wiremock-extension-asyncapi`** | AsyncAPI spec parser, WebSocket / SSE endpoints, in-memory topic broker, HTTP-to-event bridge. | Jackson YAML, WireMock core. |
| **`wiremock-extension-stateful`** | AST dynamic state simulation (already exists; gains webhook & async action support). | WireMock core. |
| **`wiremock-extension-ui`** | Single-page console with new tabs for Chaos, Events, and Webhooks Outbox. | Pure Vanilla JS / CSS (Zero NPM). |
| **`wiremock-extension-bundle`** | Meta-jar packaging all extensions into a single drop-in standalone server. | All subprojects. |

---

## 7. Next Steps & Recommended Implementation Phases

1. **Phase 1: In-Process Chaos Engine (`wiremock-extension-chaos`)**:
   - Implement `TcpChaosProxy` using Java 21 Virtual Threads supporting the 7 toxic types.
   - Implement Toxiproxy REST API (`/__admin/chaos/proxies`, `/__admin/chaos/proxies/{name}/toxics`).
   - Add L7 HTTP fault trigger filter with probability and correlation-id matching.
2. **Phase 2: Enhanced Webhooks & OpenAPI Callbacks**:
   - Implement OpenAPI `callbacks` parser in `wiremock-extension-openapi`.
   - Implement Webhook Outbox store and Admin API (`/__admin/webhooks/outbox`).
   - Add `Action.webhook(...)` to `wiremock-extension-stateful` AST.
3. **Phase 3: AsyncAPI Spec Ingestion & Event Simulation (`wiremock-extension-asyncapi`)**:
   - Implement `AsyncApiParser` for 2.x and 3.x specifications.
   - Implement in-memory topic broker + WebSocket / SSE streaming endpoints.
   - Connect HTTP post-serve actions to async topic publication.
4. **Phase 4: Web UI Integration**:
   - Add Chaos controller tab, Events streaming console, and Webhooks Outbox log to `wiremock-extension-ui`.
   - Update demo recordings and documentation.
