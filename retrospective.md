# Architecture Review & Retrospective: WireMock Extensions Suite

This document captures the system retrospective, feature gap analysis, architectural boundaries, failure modes when running subsets of extensions, and the prioritized product roadmap.

---

## 1. Feature Gap Analysis: What Features Are Missing?

While the suite provides a robust operational and simulation layer for WireMock Standalone, the following capabilities represent key feature gaps:

1. **OpenAPI Schema Contract Validation (Request & Response Conformance)**:
   - *Current Behavior*: `wiremock-extension-openapi` generates synthetic stubs from specifications. However, WireMock does not validate whether incoming client requests strictly adhere to the OpenAPI schema (types, required properties, regex patterns).
   - *Impact*: Requests with missing headers or malformed JSON payloads still match stubs unless explicit JSON schema matchers were generated. Tools like Stoplight Prism or Microcks reject non-conforming calls as contract violations.

2. **Persistence for Stub Lifecycle & Dynamic Sessions**:
   - *Current Behavior*: `DisabledStubStore` and `StatefulSession` are held in memory (`ConcurrentHashMap`).
   - *Impact*: Restarting the WireMock server resets all disabled stubs back to active and wipes all dynamic simulation sessions. Production and standalone deployments need optional persistence to disk (`mappings/*.json` with a `"metadata": { "disabled": true }` flag).

3. **Visual Stub & Matcher Builder in the Web UI**:
   - *Current Behavior*: Creating or editing stubs in the UI requires typing or modifying raw JSON.
   - *Impact*: A visual form builder (HTTP method dropdown, URL matcher selector, header rule pills, response status picker, latency delay slider) would lower the barrier for QA, frontend engineers, and non-technical testers.

4. **Per-Session Scenario Visualizer in Web UI**:
   - *Current Behavior*: The Scenarios tab visualizes WireMock's global scenario states (`/__admin/scenarios`).
   - *Impact*: It cannot inspect or visualize dynamic state machines registered via `wiremock-extension-stateful` under specific `X-Correlation-Id` sessions.

5. **Dynamic Mock Templating Helpers (Faker / Realistic Data)**:
   - *Current Behavior*: Dynamic responses rely on standard WireMock Handlebars helpers.
   - *Impact*: Missing zero-dependency realistic data generators (e.g. `{{random.uuid}}`, `{{random.name}}`, `{{random.email}}`, `{{random.creditCard}}`) without requiring external dependencies like `wiremock-faker-extension`.

6. **Authentication & Identity Provider Mock Preset**:
   - *Current Behavior*: Users must manually write stubs for token endpoints.
   - *Impact*: Integration tests frequently require authenticating against OAuth2/OIDC providers (Keycloak, Auth0, Okta). A built-in extension issuing signed JWTs and serving a standard `/.well-known/jwks.json` endpoint would save significant setup time.

---

## 2. Scope Creep Analysis: Are We Suffering from Scope Creep?

The suite evolved from lightweight observability extensions (JSON logging and Prometheus metrics) into an embedded web console, an OpenAPI parser/synthesizer, a project lifecycle manager, and an AST-driven dynamic state simulation engine.

- **Architectural Boundary Strain**:
  - Domain lifecycle logic (`StubLifecycleAdminEndpoint`, `/__admin/projects/*`) was bundled inside `wiremock-extension-ui`. Domain administration logic should not live inside a UI presentation module.
  - The AST state engine is effectively an independent DSL and interpreter.
- **Strategic Reality & Moat**:
  - Standalone logging and metrics extensions have minimal market differentiation.
  - The combination of the **Embedded Web Console + OpenAPI Ingestion + Correlation-Isolated Stateful Simulation** transforms WireMock from a basic mock server into an **on-premises, zero-cloud API simulation studio** that competes directly with commercial platforms.
- **Verdict**: The expanded scope represents our primary competitive advantage. However, module boundaries should be refactored so domain administration logic is decoupled from presentation.

---

## 3. Bundling Strategy: Should We Really Bundle the Extensions?

- **Distribution Strategy (Keep the Bundle)**:
  - WireMock Standalone users (Docker, CLI runner, dev servers) require a single drop-in JAR with unified Java SPI auto-discovery.
  - `wiremock-extension-bundle` ensures all extensions are version-aligned and tested together via Playwright end-to-end suites.
- **Modularity Strategy (Preserve Individual JAR Publishing)**:
  - In JVM unit and integration tests using `@WireMockTest`, teams running hundreds of isolated test cases often only require `wiremock-extension-json-logging` or `wiremock-extension-otel`.
  - Forcing consumers to pull in `swagger-parser-v3`, Jackson YAML, or UI static assets inside lightweight microservice testcontainers would create unnecessary bloat and dependency risks.

---

## 4. Decoupling & UI Graceful Degradation Analysis

When running `wiremock-extension-ui` with only a subset of extensions loaded, the UI currently exhibits fragile coupling:

| Missing Extension | Current Behavior & Failure Mode |
| :--- | :--- |
| **`wiremock-extension-openapi` missing** | Clicking *Import Spec / Bundle* and typing calls `POST /__admin/openapi/inspect` -> returns **404 Not Found**. Submitting calls `POST /__admin/openapi/import` -> returns **404 Not Found**, displaying a red error banner: *"Failed to import OpenAPI spec"*. |
| **`wiremock-extension-stateful` missing** | Initialization calls `GET /__admin/stateful/models` -> 404 is caught and set to `[]`. The *⚡ Dynamic* filter pill displays `(0)` and dynamic features are silently inert. |
| **`wiremock-extension-otel` missing** | The header navbar displays a `📊 Metrics` link pointing to `../metrics/prometheus`. Clicking it opens a browser tab with a **404 error page**. |
| **Standalone UI alone** | `StubLifecycleAdminEndpoint` is packaged inside `wiremock-extension-ui`, so `/stubs/disabled` and `/projects/*` operate. However, external links and modal imports fail as noted above. |

---

## 5. Architectural Findings & Code Smells

1. **Native Swagger UI Endpoint Clarification**:
   - The top navigation link `📖 Swagger UI` (`../swagger-ui/`) is served natively by **WireMock Standalone** (which bundles Swagger UI under `/__admin/swagger-ui/`).
2. **Leaked Domain Responsibility**:
   - `StubLifecycleAdminEndpoint` (managing active/disabled stub stores, bulk enable/disable, and project mode transitions) lives in package `com.github.mattiasgreen.wiremock.ui` inside `wiremock-extension-ui`. Backend integration tests wanting to disable stubs programmatically must pull in the UI module.
3. **In-Memory Volatility**:
   - `DisabledStubStore` uses an in-memory `ConcurrentHashMap`. Restarting WireMock clears all disabled stubs and project modes. This will be resolved when persistence support is added.
4. **Missing Extension Discovery Handshake**:
   - The UI does not query loaded extensions at startup. It should probe `/__admin/ui/features` to dynamically hide or badge optional features (e.g. disabling OpenAPI import with an informative tooltip if the extension is not registered).

---

## 6. Prioritized Roadmap & TODO

### Phase 1: Robustness & Decoupling
- [ ] **UI Feature Discovery Handshake**: Implement `GET /__admin/ui/features` route to advertise active extensions (`hasOpenApi`, `hasOtel`, `hasStateful`). Update `app.js` to conditionally hide or badge links and modal triggers when companion extensions are not loaded.
- [ ] **Separate Domain Lifecycle from UI**: Extract `StubLifecycleAdminEndpoint` and `DisabledStubStore` into a dedicated core/lifecycle module (or merge into `wiremock-extension-stateful`), keeping `wiremock-extension-ui` strictly as a frontend asset presenter.

### Phase 2: Persistence & Storage
- [ ] **File-Backed Persistence for Disabled Stubs**: Persist disabled stubs to WireMock mapping JSON files using `"metadata": { "disabled": true }` or a dedicated `.disabled/` store, ensuring state survives process restarts.
- [ ] **Dynamic Session Snapshotting**: Allow persisting active stateful simulation sessions to JSON for reproducible bug reporting.

### Phase 3: Contract Conformance & Security
- [ ] **OpenAPI Request/Response Schema Validation**: Add a request filter that validates incoming payloads against the imported OpenAPI schema and returns HTTP 422 / 400 on violations.
- [ ] **OAuth2 & OIDC Mock Provider Extension**: Built-in mock authorization server issuing RSA-signed JWTs with a standard `/.well-known/jwks.json` endpoint.

### Phase 4: UI Authoring & Visual Tools
- [ ] **Visual Stub & Matcher Builder**: Add a guided visual form editor to create and edit stubs without manual JSON editing.
- [ ] **Per-Correlation Dynamic Scenario Visualizer**: Graph dynamic state models and per-correlation session state machines directly inside the Scenarios tab.
- [ ] **Realistic Data Generation Helpers**: Introduce lightweight, zero-dependency data synthesis Handlebars helpers (`{{random.uuid}}`, `{{random.email}}`, `{{random.dateTime}}`).

### Phase 5: Network Chaos & Fault Injection (`wiremock-extension-chaos`)
*Architecture Blueprint: [docs/simulator-stack-architecture.md](docs/simulator-stack-architecture.md)*
- [ ] **In-Process Virtual Thread TCP Proxy**: Re-implement Toxiproxy engine natively in Java 21 (`Thread.ofVirtual()`) without external Go/C sidecars, supporting all 7 toxics (`latency`, `bandwidth`, `slow_close`, `timeout`, `reset_peer` / TCP RST via `setSoLinger(true,0)`, `slicer`, `limit_data`).
- [ ] **Toxiproxy v2 REST API Compatibility**: Expose `/__admin/chaos/proxies` endpoints so existing Toxiproxy SDKs (Java, Python, Go, Node) can configure chaos directly against WireMock.
- [ ] **WireMock L7 Chaos Interceptor**: Declarative stub/filter chaos injection matching paths, headers, and `X-Correlation-Id` with probability weighting.
- [ ] **Web UI Chaos Controller**: Dedicated dashboard tab with one-click presets ("3G Mobile", "Flaky Wi-Fi", "Chaos Monkey") and live drop counters.

### Phase 6: AsyncAPI & Event-Driven Mocking (`wiremock-extension-asyncapi`)
*Architecture Blueprint: [docs/simulator-stack-architecture.md](docs/simulator-stack-architecture.md)*
- [ ] **AsyncAPI 2.x & 3.x Ingestion**: Parse `asyncapi.yaml` channels, operations, and message payload schemas via `POST /__admin/asyncapi/import`.
- [ ] **WebSockets & SSE Event Streaming**: In-process event stream channels (`ws://localhost:8080/__async/channels/{name}` and `GET /__async/channels/{name}` SSE) with zero external message broker dependencies.
- [ ] **In-Memory Virtual Topic Broker**: Lightweight pub/sub topic broker with wildcard routing (`orders.*`), offset cursors, and consumer group simulation.
- [ ] **Bidirectional HTTP ⇄ Event Bridge**: Trigger async topic publications automatically upon matching HTTP stubs (e.g. `POST /orders` -> publish `order-created`), and vice versa.
- [ ] **Web UI Event Stream Console**: Live event inspector, manual event publishing console, and channel message schema viewer.

### Phase 7: Webhook Outbox & OpenAPI Callbacks (`wiremock-extension-webhooks`)
*Architecture Blueprint: [docs/simulator-stack-architecture.md](docs/simulator-stack-architecture.md)*
- [ ] **OpenAPI `callbacks` Ingestion**: Automatically synthesize stubs returning `202 Accepted` paired with async webhook dispatches targeting `{{jsonPath request.body '$.callbackUrl'}}`.
- [ ] **Stateful AST Webhook Actions**: Support `Action.webhook(...)` in `wiremock-extension-stateful` to dispatch callbacks upon workflow state transitions.
- [ ] **Webhook Outbox Journal**: Audit log tracking dispatched callbacks, target URLs, latency, retries, and delivery status with an interactive "Replay / Trigger Now" button in the Web UI.
