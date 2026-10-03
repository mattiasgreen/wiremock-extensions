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
| **Distributed Tracing (W3C)** | In-process OpenTelemetry metrics only (`wiremock_requests_total`, histograms). | Distributed Tracing with W3C `traceparent` context extraction, Server Spans, and OTLP export. | **P1** | Pending |
| **Packaging & Distribution** | Local development runner (`StandaloneDevServer`) and subproject library JARs. | Maven Central publishing, Standalone Fat JAR distribution, and Docker image. | **P1** | Pending |
| **Interactive Demo (GH Pages)**| README contains animated GIFs; no live web sandbox. | Static interactive browser demo on GitHub Pages with simulated Admin API responses. | **P2** | Pending |
| **CI / CD Pipeline** | Minimal `ci.yml`; lacks Spotless check, Gradle build caching, and headless Playwright OS dependencies. | Full matrix/verification pipeline with `spotlessCheck`, headless test runner, and caching. | **P2** | *Deferred* |
| **Extension Robustness** | `HeaderNormalizer.java` recompiles regexes on every call; `OtelMetricsRegistry.java` uses JVM-wide singleton. | Precompiled `Pattern` constants; instance-scoped metrics registry with optional shared fallback. | **P2** | *Deferred* |

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

---

### Phase 3: Extension Hardening & CI/CD Pipeline (Deferred)

- [ ] **3.1. Performance & Robustness Optimizations**
  - [ ] In `HeaderNormalizer.java`: Precompile `Pattern` constants for header normalization regexes instead of invoking `String.replaceAll` on every incoming request.
  - [ ] In `OtelMetricsRegistry.java`: Support instance-based registration and lifecycle teardown alongside the singleton fallback to improve multi-server test isolation.
  - [ ] In `MdcRequestFilter.java` & `JsonLoggingListener.java`: Ensure MDC cleanup guarantees even when unhandled exceptions occur in filter pipelines.

- [ ] **3.2. Upgrade GitHub Actions (`.github/workflows/ci.yml`)**
  - [ ] Add `spotlessCheck` step to fail fast on formatting violations.
  - [ ] Configure Gradle build cache and dependency verification.
  - [ ] Ensure headless Playwright Linux dependencies are installed or cached for CI runs.
  - [ ] Ensure `test` task runs efficiently with recording tests excluded.

---

### Phase 4: Observability, Packaging & Enterprise Readiness

- [ ] **4.1. Distributed Tracing with W3C Context Propagation (`wiremock-extension-otel`)**
  - [ ] Extract incoming W3C `traceparent` and `tracestate` headers using standard OpenTelemetry `TextMapGetter`.
  - [ ] Start an in-process OpenTelemetry `SERVER` Span for each served request in `ServeEventListener`.
  - [ ] Populate span attributes with semantic conventions: `http.request.method`, `http.response.status_code`, `url.path`, `wiremock.matched`, `wiremock.stub_name`.
  - [ ] Record exceptions and set span status (`ERROR`) on 5xx responses or unmatched requests.
  - [ ] Support optional OpenTelemetry trace export (OTLP over gRPC / HTTP) so WireMock spans appear in Jaeger, Grafana Tempo, or Zipkin.
  - [ ] Integrate trace context into MDC logging (`trace_id`, `span_id`) in `wiremock-extension-json-logging` for end-to-end log-trace correlation.

- [ ] **4.2. Air-Gapped Enterprise Self-Containment**
  - [ ] Perform asset audit confirming zero outbound CDN requests, external web fonts, or remote analytic beacons.
  - [ ] Add an automated Playwright network interception test in `UiPlaywrightTest` verifying 100% of network requests remain strictly within `/__admin/ui/*` and `/__admin/*`.

- [ ] **4.3. Packaging & Distribution (Maven Central, Docker Image etc.)**
  - [ ] Configure Maven Central publishing workflow via Sonatype Central Portal.
  - [ ] Configure `wiremock-extension-bundle` standalone fat JAR / shadow distribution for single-command CLI execution (`java -jar wiremock-standalone-qol-all.jar`).
  - [ ] Provide production-ready `Dockerfile` and `docker-compose.yml` reference.

- [ ] **4.4. Interactive GitHub Pages Demo**
  - [ ] Build a zero-dependency static demo site hosted on GitHub Pages with simulated Admin API responses to allow instant evaluation of the UI, scenario DAG visualizer, and HTTP tester without running Java.
