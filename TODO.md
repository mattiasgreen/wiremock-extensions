# Project Review & Architecture Roadmap: WireMock Extensions

Comprehensive review of project structure, code style, architecture, and engineering standards for the `wiremock-extensions` suite.

---

## Executive Scorecard

| Area | Current State | Target State | Priority | Status |
| :--- | :--- | :--- | :--- | :--- |
| **Test & Build Hygiene** | `UiDemoGifRecordingTest.java` isolated behind `@Tag("recording")` and standalone `recordDemoGifs` task. | Fast, non-mutating `./gradlew test` runs. | **P0** | **Completed** |
| **Agent Instructions** | `AGENTS.md` established in repo root with architectural guidelines and constraints. | Comprehensive `AGENTS.md` establishing conventions, architecture, test commands, and strict Vanilla JS rules. | **P0** | **Completed** |
| **Java Tooling & Formatting** | Spotless with Palantir Java Format, Gradle Version Catalog (`libs.versions.toml`), `-Xlint:all -Werror`. | Enforced clean, consistent code style and centralized dependency declarations. | **P0** | **Completed** |
| **UI Asset Serving** | Dynamic asset routing in `UiAdminApiEndpoint.java` supporting subdirectories (`/ui/modules/*`) with dev hot-reloading and proper MIME types. | Modular asset serving for Native ES Modules. | **P0** | **Completed** |
| **UI Modularization** | Modular Native ES Modules (`app.js` + `modules/*`), strict 0-NPM/0-bundler constraint, terse agent comments. | Decoupled, maintainable Vanilla JS architecture verified via Playwright. | **P0** | **Completed** |
| **Air-Gapped Self-Containment** | Embedded UI assets are local; requires explicit test contract guaranteeing zero remote/CDN calls. | 100% self-contained, verifiable offline execution for strict enterprise/defense networks. | **P1** | Pending |
| **Distributed Tracing (W3C)** | OpenTelemetry metrics and W3C distributed tracing with server spans, OTLP export, and log correlation. | Distributed Tracing with W3C `traceparent` context extraction, Server Spans, and OTLP export. | **P1** | **Completed** |
| **Packaging & Distribution** | Local development runner (`StandaloneDevServer`) and subproject library JARs. | Maven Central publishing, Standalone Fat JAR distribution, and Docker image. | **P1** | Pending |
| **Interactive Demo (GH Pages)**| README contains animated GIFs; no live web sandbox. | Static interactive browser demo on GitHub Pages with simulated Admin API responses. | **P2** | Pending |
| **CI / CD Pipeline** | Minimal `ci.yml`; lacks Spotless check, Gradle build caching, and headless Playwright OS dependencies. | Full matrix/verification pipeline with `spotlessCheck`, headless test runner, and caching. | **P2** | *Deferred* |
| **Extension Robustness** | Precompiled `Pattern` constants in `HeaderNormalizer.java`; instance-scoped tracing registry. | Instance-scoped metrics and tracing registries with test isolation. | **P2** | **Completed** |
| **Automated Demo Videos** | Manual animated GIFs in `docs/images` with palette limitations and repository bloat risk. | Headless Playwright WebM video recordings starting at frame 0 (no in-video slides/HUD), self-contained E2E flows, and agent-gated local execution via `git log` review. | **P2** | Planned |

---

## Core Principles & Constraints

1. **Zero NPM / Zero JS Bundlers Constraint**:
   - The UI in `wiremock-extension-ui` is intentionally pure Vanilla JavaScript, CSS, and HTML.
   - **No** `package.json`, `npm`, `yarn`, `pnpm`, Webpack, Vite, Rollup, or Babel dependencies.
   - Modularization must use standard modern browser **ES Modules** (`import` / `export` and `<script type="module">`).
2. **Terse, Agent-Friendly Documentation & Comments**:
   - Comments in code must be concise, high-density, and structured for AI coding agents. Focus on:
     - Component contracts & state shapes.
     - DOM selector IDs / data-testid attributes used by automated tests.
     - WireMock Admin API endpoint contracts (`/__admin/...`).
     - Invariants and non-obvious design decisions.
3. **Enterprise Java Standards**:
   - JDK 21 toolchain with strict compilation (`-Xlint:all`, `-Werror`).
   - Unified formatting via Spotless.
   - Centralized dependency management via Gradle Version Catalog.
   - Decoupled, discoverable WireMock SPI extensions (`ExtensionFactory`).
4. **Air-Gapped & Offline Independence**:
   - Web assets must never link to external CDNs, Google Fonts, or telemetry. All styles, icons, and scripts must execute completely offline in restricted corporate VPCs.

---

## Phased Implementation Roadmap

### Phase 1: Build Modernization & Agent Governance

- [x] **1.1. Create `AGENTS.md` in Repo Root**
  - [x] Define project philosophy, strict constraints (Vanilla JS only, 0-NPM).
  - [x] Document project module layout and responsibilities.
  - [x] Specify key development and testing commands (`test`, `runStandalone`, `spotlessApply`, etc.).
  - [x] Detail extension points (`AdminApiExtension`, `ServeEventListener`, `RequestFilterV2`, `ExtensionFactory`).
  - [x] Establish coding comment style: terse, contract-focused, agent-oriented.

- [x] **1.2. Adopt Gradle Version Catalog (`gradle/libs.versions.toml`)**
  - [x] Centralize versions: WireMock (`3.12.1`), JUnit 5 (`5.11.4`), AssertJ (`3.27.3`), Logback (`1.5.16`), Jackson (`2.18.2`), Playwright (`1.49.0`), OpenTelemetry (`1.47.0`), Prometheus (`1.3.6`).
  - [x] Refactor root and subproject `build.gradle` files to use version catalog aliases (`libs.wiremock`, `libs.junit.jupiter`, etc.).

- [x] **1.3. Configure Spotless for Java & Web Assets**
  - [x] Add `id 'com.diffplug.spotless' version '7.0.2'` to root `build.gradle`.
  - [x] Configure `spotless.java` with Palantir Java Format.
  - [x] Configure `spotless.format('misc')` for Gradle, Markdown, JSON, CSS, and HTML files.
  - [x] Run `./gradlew spotlessApply` and verify `./gradlew spotlessCheck`.

- [x] **1.4. Isolate GIF Recording Test**
  - [x] Add `@Tag("recording")` to `UiDemoGifRecordingTest.java`.
  - [x] Configure test task to exclude `recording` tag by default during regular test runs.
  - [x] Register dedicated Gradle task `recordDemoGifs` for explicit generation when documentation assets need updating.

- [x] **1.5. Enable Strict Compiler Flags**
  - [x] Configure Java compilation in `subprojects` with `-Xlint:all`, `-Xlint:-processing`, `-Werror`, `-parameters`.
  - [x] Resolve compiler warnings (added `serialVersionUID` to `CaseConflictException`).

---

### Phase 2: UI Asset Serving & Native ES-Module Decomposition

- [x] **2.1. Upgrade `UiAdminApiEndpoint.java` for Dynamic Modular Asset Serving**
  - [x] Dynamic static file resolution supporting subpaths (`/ui/modules/{module}`) and root files (`/ui/{file}`).
  - [x] Automatic MIME type mapping (`resolveContentType`) for JS, CSS, HTML, JSON, SVG, PNG, GIF, ICO.
  - [x] Path traversal safety check (`..` check returning 400).
  - [x] Maintained dual-resolution: dev filesystem hot-reload + production classpath fallback.
  - [x] Added contract test coverage in `UiAssetAndContractTest.java`.

- [x] **2.2. Decompose Monolithic `app.js` into Native ES Modules**
  - Created domain modules under `wiremock-extension-ui/src/main/resources/ui/modules/`:
  - [x] `modules/dom.js`: Cached DOM element queries, string escaping, byte formatting.
  - [x] `modules/highlighter.js`: Terse JSON syntax highlighter token generator.
  - [x] `modules/state.js`: Central application state store.
  - [x] `modules/router.js`: Hash-based tab routing (`#stubs`, `#journal`, `#tester`, `#scenarios`), query parameter deep-linking.
  - [x] `modules/stubs.js`: Stub list filtering, search, selection, detail card rendering, and cURL generation.
  - [x] `modules/journal.js`: Request journal polling, table rendering, diff modal, copy actions, and auto-refresh controls.
  - [x] `modules/tester.js`: HTTP request runner, dynamic headers key-value editor, execution history, and response viewer.
  - [x] `modules/scenarios.js`: Scenario state badges, manual transition controls, and SVG/DAG graph visualizer.
  - [x] `modules/api.js`: WireMock Admin API client (`/mappings`, `/requests`, `/scenarios`, reset endpoints).
  - [x] `app.js`: Main ES module entry point wiring event listeners and public API exports.
  - [x] `index.html`: Updated script tag to `<script type="module" src="/__admin/ui/app.js"></script>`.

- [x] **2.3. Add Terse, Agent-Friendly Comments across UI Modules**
  - [x] Compact JSDoc annotations detailing state structures, DOM ID contracts, and API routes.

- [x] **2.4. Verify Playwright and Integration Test Suites**
  - [x] Full Playwright headless browser test suite (`UiPlaywrightTest`) passed with zero regressions.

- [x] **2.5. Stub Lifecycle Management & Ad-Hoc Stub Creation**
  - [x] Resolved 404 error when editing stubs by providing unified `PUT /stubs/{id}` in `StubLifecycleAdminEndpoint` supporting active stubs, disabled stubs, and upsert fallback.
  - [x] Added ad-hoc stub creation button (`➕ New` / `#btn-create-stub`) in UI header with clean modal defaults.
  - [x] Added Playwright integration test Scenario 12 verifying active editing, disabled editing without 404, and ad-hoc creation.

- [ ] **2.6. UI/UX Audit Overhaul & Progressive Simulation Workflows**
  - [x] Remove 2.5 MB of pitch deck GIF/PNG bloat from `wiremock-extension-ui/src/main/resources/ui/pitch/` and deduplicate `docs/site/demo/` from Git.
  - [x] **Verdict Taxonomy Unification (`PROXIED` / `STUB` / `STATEFUL` / `UNMATCHED`)**:
    - [x] Distinguish `🌐 PROXIED` (forwarded via `proxyBaseUrl`), `📌 STUB` (local static or `STUB · TPL` Handlebars mapping), `🧠 STATEFUL` (AST stateful engine, replacing ambiguous `"DYNAMIC"` label), and `⚠️ UNMATCHED` (HTTP 404) across Request Journal badges, filter pills, and Stubs Catalog filters.
  - [x] **End-to-End Request Journal Triage, Harvesting & Level-1 Java Codegen**:
    - [x] Add `🚀 Replay in Tester` (`.btn-journal-to-tester`), `📋 Copy cURL` (`.btn-journal-copy-curl`), and `☕ Copy Java` (`#btn-copy-java` / `.btn-journal-copy-java` generating `stubFor(...)` DSL) on Stubs and Journal entries.
    - [x] On `⚠️ UNMATCHED` entries: replace `📸 Freeze Stub` (which would freeze a 404) with `➕ Create Stub from Request` (`.btn-create-stub-from-req`, opens Stub Editor pre-populated with incoming method, URL, and body pattern, defaulting response to `200 OK`) and display an Unmatched Diagnostic banner.
    - [x] On `🌐 PROXIED` entries: display target upstream origin and `📸 Freeze Stub`.
    - [x] On `📌 STUB` / `🧠 STATEFUL` entries: add `🔗 Inspect Stub` link to jump directly to the matched mapping, and render `X-Correlation-Id` as a 1-click session filter chip (`.btn-journal-filter-corr`).
  - [ ] **Zero-Bloat Request Matcher Builder & Handlebars Token Inserter**:
    - [ ] Two-way synced Form $\leftrightarrow$ Raw JSON editor with a generic repeatable matcher row builder (`Header`, `Query`, `Cookie`, `Body` $\times$ `equalTo`, `contains`, `matches`, `equalToJson`, `matchesJsonPath`).
    - [ ] 1-click Handlebars token bar (`{{request.pathSegments.[1]}}`, `{{request.query.id}}`, `{{jsonPath request.body '$.id'}}`, `{{randomValue type='UUID'}}`) that automatically enables `"transformers": ["response-template"]`.
  - [ ] **Service-Scoped Proxy Bootstrapper & Generic Stateful CRUD Generator**:
    - [x] Allow creating a service-scoped `proxyBaseUrl` route directly from `➕ New` (`🌐 Live Service Proxy (ERP System)` preset + `#editor-proxy-url` field) without requiring an OpenAPI specification.
    - [ ] Replace hardcoded domain templates in `dynamic-stubs.js` with a parameterized CRUD Entity Generator and add a Live Entity State Inspector & Reset view per `X-Correlation-Id`.

---

### Phase 3: Extension Hardening & CI/CD Pipeline (Deferred)

- [ ] **3.1. Performance & Robustness Optimizations**
  - [ ] In `HeaderNormalizer.java`: Precompile `Pattern` constants for header normalization regexes instead of invoking `String.replaceAll` on every incoming request.
  - [ ] In `OtelMetricsRegistry.java`: Support instance-based registration and lifecycle teardown alongside the singleton fallback to improve multi-server test isolation.
  - [ ] In `MdcRequestFilter.java` & `JsonLoggingListener.java`: Ensure MDC cleanup guarantees even when unhandled exceptions occur in filter pipelines.

- [x] **3.2. Upgrade GitHub Actions (`.github/workflows/ci.yml`)**
  - [x] Add `spotlessCheck` step to fail fast on formatting violations.
  - [x] Configure Gradle build cache and dependency verification via `gradle/actions/setup-gradle`.
  - [x] Ensure headless Playwright execution on CI runners.
  - [x] Ensure `test` task runs efficiently with recording tests excluded.

---

### Phase 4: Observability, Packaging & Enterprise Readiness

- [x] **4.1. Distributed Tracing with W3C Context Propagation (`wiremock-extension-otel`)**
  - [x] Extract incoming W3C `traceparent` and `tracestate` headers using standard OpenTelemetry `TextMapGetter`.
  - [x] Start an in-process OpenTelemetry `SERVER` Span for each served request in `ServeEventListener`.
  - [x] Populate span attributes with semantic conventions: `http.request.method`, `http.response.status_code`, `url.path`, `wiremock.matched`, `wiremock.stub_name`.
  - [x] Record exceptions and set span status (`ERROR`) on 5xx responses or unmatched requests.
  - [x] Support optional OpenTelemetry trace export (OTLP over gRPC / HTTP) so WireMock spans appear in Jaeger, Grafana Tempo, or Zipkin.
  - [x] Integrate trace context into MDC logging (`trace_id`, `span_id`) in `wiremock-extension-json-logging` for end-to-end log-trace correlation.

- [ ] **4.2. Air-Gapped Enterprise Self-Containment**
  - [ ] Perform asset audit confirming zero outbound CDN requests, external web fonts, or remote analytic beacons.
  - [ ] Add an automated Playwright network interception test in `UiPlaywrightTest` verifying 100% of network requests remain strictly within `/__admin/ui/*` and `/__admin/*`.

- [ ] **4.3. Packaging & Distribution (Maven Central, Docker Image etc.)**
  - [ ] Configure Maven Central publishing workflow via Sonatype Central Portal.
  - [ ] Configure `wiremock-extension-bundle` standalone fat JAR / shadow distribution for single-command CLI execution (`java -jar wiremock-standalone-qol-all.jar`).
  - [ ] Provide production-ready `Dockerfile` and `docker-compose.yml` reference.

- [ ] **4.4. Interactive GitHub Pages Demo & Documentation Hub**
  - [ ] Build a zero-dependency static documentation and landing page hosted on GitHub Pages with simulated Admin API responses to allow instant evaluation of the UI, scenario DAG visualizer, and HTTP tester without running Java.

---

### Phase 5: Architecture Decoupling & Storage Persistence

- [x] **5.1. UI Feature Discovery Handshake & Graceful Degradation**
  - [x] Implement remote capability probing across Core, Lifecycle, Stateful, NetEm, and Metrics endpoints.
  - [x] Automatic UI degradation and visual capability matrix in Target Server modal.

- [ ] **5.2. Decouple Domain Lifecycle from UI Presentation**
  - [ ] Extract `StubLifecycleAdminEndpoint` and `DisabledStubStore` into a dedicated core/lifecycle module or merge with stateful domain logic, keeping `wiremock-extension-ui` purely presentation-focused.

- [ ] **5.3. File-Backed Persistence for Disabled Stubs & Stateful Sessions**
  - [ ] Persist disabled stubs to WireMock mapping JSON files using `"metadata": { "disabled": true }` or a dedicated `.disabled/` store across server restarts.
  - [ ] Allow stateful simulation sessions to be serialized/deserialized to JSON for bug reporting and replay.

---

### Phase 6: Contract Conformance & Security

- [ ] **6.1. OpenAPI Request & Response Schema Validation**
  - [ ] Request filter validating incoming payloads and headers against imported OpenAPI specs, returning HTTP 400/422 on violations.

- [ ] **6.2. In-Process OAuth2 & OIDC Mock Provider**
  - [ ] Built-in mock authorization server issuing RSA-signed JWTs with standard `/.well-known/jwks.json` endpoint.

- [ ] **6.3. Zero-Dependency Realistic Mock Data Templating Helpers**
  - [ ] Introduce Handlebars helpers (`{{random.uuid}}`, `{{random.email}}`, `{{random.dateTime}}`, `{{random.name}}`).

- [ ] **6.4. Per-Correlation Stateful Session Visualizer & Form Builder**
  - [ ] Visual form editor in Web UI for crafting stubs and matchers without writing raw JSON.
  - [ ] Inspect stateful entity graphs registered via `wiremock-extension-stateful` under specific `X-Correlation-Id` sessions in the Scenarios tab.

---

### Phase 7: Event-Driven Mocking & Webhooks

- [ ] **7.1. AsyncAPI & Event-Driven Mocking (`wiremock-extension-asyncapi`)**
  - [ ] Parse `asyncapi.yaml` channels and schemas via `POST /__admin/asyncapi/import`.
  - [ ] In-process SSE and WebSocket stream channels (`ws://localhost:8080/__async/channels/{name}` and `GET /__async/channels/{name}`).
  - [ ] Virtual in-memory pub/sub broker with wildcard routing (`orders.*`) and offset simulation.
  - [ ] Bidirectional HTTP ⇄ Event Bridge (HTTP stub match triggers event publication).

- [ ] **7.2. Webhook Outbox & OpenAPI Callbacks (`wiremock-extension-webhooks`)**
  - [ ] Automatically synthesize callback stubs returning `202 Accepted` paired with async webhook dispatches targeting `{{jsonPath request.body '$.callbackUrl'}}`.
  - [ ] Support `Action.webhook(...)` in stateful AST transitions.
  - [ ] Webhook Outbox Journal in UI for auditing and replaying dispatches.

---

### Phase 8: Developer Experience & Inner Loop Performance

- [x] **8.1. Gradle Parallelism & Build Caching**
  - [x] Add `org.gradle.parallel=true` to enable concurrent execution across subprojects.
  - [x] Add `org.gradle.caching=true` (Build Cache) to eliminate redundant Java compilation.
  - [x] Tune JVM memory (`org.gradle.jvmargs=-Xmx2048m -XX:+UseParallelGC -Dfile.encoding=UTF-8`) to prevent daemon crashes and memory thrashing.
  - [x] Configure default `WARN` test logging (`config/logback-test.xml`) and C1 JIT (`-XX:TieredStopAtLevel=1`) to eliminate 95 MB of Jetty/Netty DEBUG test report bloat.

- [x] **8.2. Fast Inner-Loop Test Strategy**
  - [x] Enforce strict method-level test filtering (`--tests "MyTest.method"`) during active authoring instead of running multi-suite E2E tests.
  - [x] Split monolithic 94 KB `UiPlaywrightTest.java` into 4 domain suites sharing a single Chromium browser (`AbstractUiPlaywrightTest`).
  - [x] Defer code formatting (`spotlessApply`) and static analysis (`spotbugsMain`, `check`) exclusively to pre-commit / pre-push gates.
  - [x] Leverage zero-rebuild disk asset hot-reloading for Vanilla JS / CSS tuning rather than repetitive screenshot capture runs.

<<<<<<< HEAD
- [x] **8.3. Hierarchical Agent Instructions, Skills & Subagent Delegation**
  - [x] Refactor root `AGENTS.md` and add module-scoped `AGENTS.md` files (`ui`, `casual`, `tcp-netem`, `stateful`, `openapi`) plus on-demand `.agents/skills/` runbooks.
  - [x] Utilize concurrent subagents for independent tasks (e.g. documentation/spec authoring running in parallel with test verification).

---

### Phase 9: High-Scale Journal Architecture (Server-Side Paginated API & Hybrid Fallback)

- [ ] **9.1. Server-Side Lightweight Journal Endpoint (`UiAdminApiEndpoint`)**
  - [ ] Implement `GET /__admin/ui/api/requests` (or `/ui/api/requests`) supporting query params: `limit`, `offset`/`page`, `q` (server-side substring search), and `unmatched` (boolean).
  - [ ] **Lightweight Metadata Serialization**: Strip heavy request/response bodies from list results to shrink multi-thousand event payloads from 50MB+ down to <100KB JSON.
  - [ ] Return pagination envelope: `{ total, page, limit, requests: [...] }`.
- [ ] **9.2. On-Demand Lazy Detail Fetching**
  - [ ] Fetch complete request/response bodies, headers, and diffs on demand via `GET /__admin/requests/{id}` only when a user expands a specific row in the table.
- [ ] **9.3. Dynamic Capability Detection & Graceful Fallback**
  - [ ] Probe for `/__admin/ui/api/requests` via `capabilities.js`.
  - [ ] If available, execute server-side paginated queries with instant sub-millisecond search across tens of thousands of records.
  - [ ] If connecting to a vanilla/remote WireMock instance without custom extensions, automatically degrade to native `GET /__admin/requests?limit=...` and server-filtered `unmatched=true` with client-side windowing.

---

### Phase 10: Automated Self-Contained E2E Video Tour & Asset Pipeline

- [ ] **10.1. Headless Playwright Native Video Recorder (`UiVideoRecordingTest.java`)**
  - [ ] Leverage Playwright's native Chromium video recording (`Browser.NewContextOptions.setRecordVideoDir(...)`, `.setRecordVideoSize(1280, 720)`).
  - [ ] Output native high-efficiency WebM format for `docs/site/` with 24-bit color depth and 10x-20x smaller footprint compared to animated GIFs (retaining lightweight static/GIF preview in `README.md` due to GitHub Markdown `<video>` sanitizer).
  - [ ] Tag with `@Tag("recording")` to keep standard `./gradlew test` fast and non-mutating.
  - [ ] Provide dedicated Gradle task: `./gradlew :wiremock-extension-ui:recordDemoVideos`.

- [ ] **10.2. Zero-Clutter Presentation (Frame-0 Start + HTML Story Context)**
  - [ ] Place scenario titles and `Given / When / Then` steps from `docs/spec/functional-specification.md` directly in the documentation HTML/Markdown above each `<video autoplay loop muted playsinline>` tag—never bake text slides or HUD overlays into video pixels.
  - [ ] Start video recordings at **frame 0 directly inside `/__admin/ui/`** so viewers see immediate UI action and documentation wording can be edited without re-recording videos.
  - [ ] Inject a lightweight (~15-line) CSS/JS click ripple indicator (`page.addInitScript(...)`) with zero coupling to DOM IDs, paired with humanized pacing (`slowMo(200)` + brief inspection pauses).

- [ ] **10.3. Self-Contained Bite-Sized E2E Story Clips (Create ➔ Exercise ➔ Inspect)**
  - [ ] **Story A (OpenAPI Ingestion to Verified Request)**:
    - Start from clean slate (empty stubs).
    - Import OpenAPI specification via modal.
    - Synthesized endpoints appear with metadata badges.
    - Send request in HTTP Tester (`200 OK`).
    - Inspect serve event in Request Journal.
  - [ ] **Story B (FSM Scenario Simulation & Reset)**:
    - Display initial state `"Started"` in Scenarios DAG.
    - Dispatch transition request in HTTP Tester.
    - DAG node visibly transitions to `"CLOSED"`.
    - Click *Reset Scenarios* returning all state machines to `"Started"`.
  - [ ] **Story C (Unmatched Traffic Diagnostics & Diff)**:
    - Dispatch near-miss request with path typo (`/api/case` vs `/api/cases`).
    - Filter Request Journal by unmatched.
    - Open diagnostic diff modal inspecting path distance and matcher diagnostics.
    - Copy reproducible cURL command to clipboard.

- [ ] **10.4. Agent-Gated Local Workflow & Zero-Bloat Hosting**
  - [ ] **No CI / GitHub Actions Bloat**: Never run video generation automatically in GitHub Actions (`ci.yml`, `pages.yml`, or `release.yml`).
  - [ ] **Semantic Detection via `git log`**: During PR or release preparation, the coding agent inspects `git log` / `git diff` on `wiremock-extension-ui/src/main/resources/ui/**` to judge whether user-visible demo flows meaningfully changed (ignoring minor CSS tweaks or non-visual refactors).
  - [ ] **Manual Gateway (Ask First)**: If a demo scenario is outdated, the agent notifies the user and asks for confirmation before running `./gradlew :wiremock-extension-ui:recordDemoVideos` locally on the workstation.
  - [ ] **Zero-Bloat Publishing**: After local visual inspection and user approval, publish generated `.webm` files to GitHub Pages / Release assets without committing binary media revisions into `main` Git history.

---

### Phase 11: Bottom-Up Simulation SDK & `TestDataBuilder` Synthesis

Enable teams testing against 10+ downstream dependencies (ERP System, CRM System, Billing Platform, Risk Engine, Legacy ESB) to progress from live proxied integration tests to contract-verified, domain-driven Java Simulation SDKs—so the exact same BDD/Cucumber/JUnit test executes unmodified as **fully integrated**, **hybrid**, or **fully simulated**.

- [ ] **11.1. Runtime-Parameterized Hybrid Simulation Mode for BDD / JUnit Suites**
  - [ ] Provide a lightweight Java test harness / extension configuration allowing per-service simulation modes to be set via runtime parameters or environment variables (e.g., `-Dsim.mode.erp-system=proxy -Dsim.mode.notification-gateway=stub -Dsim.mode.crm-service=stateful`).
  - [ ] Synchronize runtime service modes with `/__admin/projects/{service}/mode` so automated BDD suites and the Web UI Service Catalog reflect the exact same hybrid topology.

- [ ] **11.2. Level 1 — Deterministic Codegen (`stubFor()`, Model DTOs & Property Builders)**
  - [x] **UI `☕ Copy Java stubFor()` Action**: Generate idiomatic WireMock Java DSL snippets directly from any Stub or recorded Journal entry.
  - [ ] **Schema + Recorded Payload $\rightarrow$ DTO & `TestDataBuilder` Generator**: Combine OpenAPI 3.0/3.1 schemas (or inferred JSON shapes from recorded `PROXIED` journal entries) with captured baseline payloads to generate:
    - [ ] Jackson-annotated Java 21 `record` / POJO model classes.
    - [ ] Fluent `TestDataBuilder` classes pre-populated with realistic baseline defaults harvested from live service recordings.
    - [ ] Low-level typed `*ServiceMock` endpoint wrappers with built-in `X-Correlation-Id` session scoping.

- [ ] **11.3. Level 2 — Semantic Domain Builders & Scenario Orchestration (GenAI / Agent-Assisted Synthesis)**
  - [ ] Elevate property-level builders (`withStatus("BLOCKED").withReasonCode("R-409")`) into high-level domain-intent methods (`org.crm.sdk.test.customer.CustomerBuilder.aCustomer().isVip()`, `.isBlockedDueToRisk()`, `.hasUpsellRecommendation()`).
  - [ ] **Multi-Trace Scenario Synthesis**: Analyze and diff multiple recorded `X-Correlation-Id` journal traces (e.g., happy-path checkout vs. ERP business-rule rejection) alongside OpenAPI descriptions using an Agentic / GenAI synthesis workflow to generate cohesive scenario setup helpers that orchestrate multi-endpoint WireMock stubs, stateful AST rules, and L4/L7 fault expectations behind a single domain verb.

---

### Phase 12: Advanced Observability & Telemetry Governance

- [ ] **12.1. AST Evaluation & Stateful Engine Metrics**
  - [ ] Deferred until DSL operators and stateful simulation usage patterns stabilize.
  - [ ] Counters for `wiremock_ast_evaluations_total{status="success|syntax_error|runtime_error"}`.
  - [ ] Histograms for AST expression execution duration and multi-tenant entity count gauges.

- [ ] **12.2. High-Cardinality Defense for OpenAPI & Dynamic Paths**
  - [ ] Normalize parametrized route templates (`/api/orders/{id}`) from OpenAPI specs in metric attributes to prevent Prometheus cardinality explosions.
  - [ ] Bounded metric label cache and dynamic stub name truncation/hashing.
  - [ ] Configurable maximum distinct series threshold.
