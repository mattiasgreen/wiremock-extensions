# Changelog

All notable changes to the `wiremock-extensions` suite are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [Upcoming / In-Flight on Branches]

### Branch `feat/otel-tracing-and-logging`
*Tracking branch for distributed tracing and observability integrations.*

- **`[2026-10-05 05:02:02 +02:00]`** (`dc42695`) **W3C Distributed Tracing (`wiremock-extension-otel`)**:
  - `OtelTracingListener` (`ServeEventListener`): Intercepts all incoming WireMock serve events and starts in-process OpenTelemetry `SERVER` spans.
  - `WireMockRequestTextMapGetter`: Extracts W3C `traceparent` and `tracestate` headers using OpenTelemetry context propagation.
  - Standard OTel semantic conventions: Populates spans with `http.request.method`, `http.response.status_code`, `url.path`, `wiremock.matched`, and `wiremock.stub_name`.
  - Error span tagging: Automatically marks spans as `StatusCode.ERROR` on HTTP 5xx responses or unmatched requests.
  - `OtelTracingRegistry`: Configurable tracer provider with testable in-memory span exporters.
  - `OtelTracingTest`: Comprehensive unit and integration tests verifying context propagation across parent-child spans.
- **`[2026-10-05 05:02:02 +02:00]`** (`dc42695`) **MDC Log-Trace Correlation (`wiremock-extension-json-logging`)**:
  - `MdcRequestFilter` & `JsonLoggingListener`: Injects active OTel `trace_id` and `span_id` directly into SLF4J MDC.
  - `WireMockLogEvent`: Serializes `trace_id` and `span_id` fields into single-line JSON logs for instant log-to-trace correlation in Datadog, Grafana Loki, or Elasticsearch.
  - `MdcAndJsonLoggingTest`: Automated verification of MDC key propagation under traced requests.
- **`[2026-10-05 05:02:02 +02:00]`** (`dc42695`) **Header Normalization Performance**:
  - `HeaderNormalizer`: Precompiles regex `Pattern` constants for header sanitization instead of recompiling on every request.

---

### Branch `feat/openapi-proxy-recording`
*Tracking branch for transparent proxying, traffic recording, and project workflows.*

- **`[2026-10-05 06:52:53 +02:00]`** (`17122a6`) **Roadmap & Wishlist Documentation**:
  - Added configurable outbound HTTPS certificate validation and dynamic downstream certificate forging specifications to `README.md`.
- **`[2026-10-05 05:58:28 +02:00]`** (`163871e`) **Live Transparent Proxying & Traffic Recording (`wiremock-extension-openapi`)**:
  - `GenerationMode` enum: Configurable stub generation modes (`MOCK_RESPONSES`, `LIVE_PROXY`, `RECORD_TRAFFIC`).
  - Upstream proxy routing in `OpenApiStubGenerator`: Automatically configures `proxyBaseUrl` stubs based on OpenAPI `servers` configuration or user input.
  - `OpenApiSpecInspector` & `OpenApiServerInfo`: Parses specification metadata, servers, and base paths for live target selection.
  - Transparent traffic recording: Records live upstream traffic responses into WireMock stub mappings.
- **`[2026-10-05 05:58:28 +02:00]`** (`163871e`) **Stub Lifecycle & Project Mode UI (`wiremock-extension-ui`)**:
  - `StubLifecycleAdminEndpoint`: Dedicated REST endpoint (`/__admin/lifecycle/...`) for project-scoped stub management and promotion.
  - Project workspace switcher (`modules/projects.js`): Toggle between OpenAPI projects and execution modes (Mock vs. Proxy vs. Recording).
  - Request Journal Promotion (`modules/journal.js`): Single-click "Promote to Stub" button converting live proxied transactions directly into permanent mappings.
  - Visual mode indicators in navigation toolbar and stubs list.
- **`[In-Flight / Staged]`** **CI / CD Pipeline Modernization (`.github/workflows/ci.yml`)**:
  - Upgraded Gradle caching with `gradle/actions/setup-gradle@v4`.
  - Added headless Playwright Linux OS dependencies installation (`npx playwright install-deps chromium`).
  - Added automated `spotlessCheck` step to fail fast on formatting regressions.
- **`[In-Flight / Staged]`** **Distribution & Deployment Reference (`README.md`)**:
  - Added Zero-Build Consumer Runner guide using published Gradle dependencies.
  - Added production-ready Alpine Dockerfile reference architecture.

---

## [Committed on `main`]

### [0.1.0] - 2026-10-05

#### Added / Enhanced
- **`[2026-10-05 04:11:11 +02:00]`** (`0e1eaa0`) **E2E Scenario & Demo Recording**:
  - Updated complete E2E scenario in `UiPlaywrightTest` and `UiDemoGifRecordingTest` covering stub clearing, GET/POST execution, and unmatched journal review.
  - Regenerated all demonstration GIFs for documentation.
- **`[2026-10-04 17:08:24 +02:00]`** (`385e864`) **Compact Tester Execution History**:
  - Redesigned HTTP tester history into a compact collapsible vertical sidebar with status badges, method tags, and latencies.
- **`[2026-10-04 15:46:26 +02:00]`** (`3e7271b`) **Stub Card Actions Cleanup**:
  - Polished stub card action buttons and right-aligned project badge tags.
- **`[2026-10-04 08:10:37 +02:00]`** (`d23d3dc`) **Workbench Layout Redesign**:
  - Redesigned console workbench layout featuring a persistent Stubs Explorer sidebar, top Request/Response split view, and bottom history dock.
- **`[2026-10-04 06:08:11 +02:00]`** (`c9326aa`) **Stub Portfolio Management & Bulk Hub**:
  - Added stub lifecycle states (`draft`, `active`, `deprecated`), project grouping, bulk actions, and bundle import/export hub.
- **`[2026-10-04 05:38:50 +02:00]`** (`b38b80e`) **Merge pull request #1**:
  - Merged feature branch `feat/openapi-importer` into `main`.
- **`[2026-10-04 05:31:04 +02:00]`** (`b3c0090`) **Ready-Made Request Payloads**:
  - Synthesized format-aware JSON request bodies with substituted path parameters in `wiremock-extension-openapi` for one-click tester prefill.
- **`[2026-10-04 05:17:47 +02:00]`** (`87aa97c`) **URL Matcher Path Resolution**:
  - Extended UI URL resolution (`getStubUrl`) to support `urlPathTemplate` and `urlPathMatching`.
- **`[2026-10-04 04:47:05 +02:00]`** (`3c38d0a`) **OpenAPI 3.0/3.1 Ingestion & Stub Synthesis**:
  - Created `wiremock-extension-openapi` subproject with OpenAPI 3.0/3.1 YAML/JSON parsing, schema dereferencing, `SchemaDataSynthesizer`, Admin API route (`POST /__admin/openapi/import`), and UI import modal.
- **`[2026-10-03 06:44:39 +02:00]`** (`1e4afcd`) **Roadmap Documentation**:
  - Updated `TODO.md` with enterprise readiness tasks and added product `ROADMAP.md`.
- **`[2026-10-03 06:30:13 +02:00]`** (`4cfcbdd`) **Documentation Maintenance**:
  - Updated `TODO.md` with completed Phase 1 and Phase 2 items.
- **`[2026-10-03 06:29:31 +02:00]`** (`c8eb686`) **Native ES-Module Decomposition**:
  - Decomposed monolithic `app.js` into modular native ES modules (`dom.js`, `highlighter.js`, `state.js`, `router.js`, `stubs.js`, `journal.js`, `tester.js`, `scenarios.js`, `api.js`) under strict Zero-NPM / Zero-Bundler constraints.
- **`[2026-10-03 06:26:17 +02:00]`** (`77063f7`) **Dynamic Modular Asset Routing**:
  - Upgraded `UiAdminApiEndpoint` to dynamically resolve module paths (`/ui/modules/*`), detect MIME types, and enforce path traversal guards.
- **`[2026-10-03 06:25:07 +02:00]`** (`d27bc49`) **Strict Compiler Flags**:
  - Configured Java compilation across subprojects with `-Xlint:all` and `-Werror`.
- **`[2026-10-03 06:23:28 +02:00]`** (`0e138d9`) **Spotless Code Formatting**:
  - Integrated Spotless with Palantir Java Format and unified formatting across Java, Gradle, Markdown, and web assets.
- **`[2026-10-03 06:21:52 +02:00]`** (`52dbbbe`) **Gradle Version Catalog**:
  - Centralized dependency management into `gradle/libs.versions.toml`.
- **`[2026-10-03 06:20:34 +02:00]`** (`2853f2c`) **Agent Guidelines (`AGENTS.md`)**:
  - Added comprehensive `AGENTS.md` specifying repository conventions, constraints, and architecture.
- **`[2026-10-03 06:20:13 +02:00]`** (`b5a8a95`) **Demo GIF Recording Isolation**:
  - Isolated heavy GIF generation tests behind Jupiter `@Tag("recording")` and dedicated `recordDemoGifs` task.
- **`[2026-10-03 06:10:13 +02:00]`** (`c013ddc`) **Architecture Review & Scorecard**:
  - Created initial executive scorecard and prioritized phased roadmap in `TODO.md`.
- **`[2026-10-03 06:10:05 +02:00]`** (`3ff84fd`) **Stateful Scenarios & DAG Visualizer**:
  - Implemented interactive SVG Scenario DAG visualizer in UI and created `wiremock-examples` Case Management reference implementation.
- **`[2026-10-03 05:46:35 +02:00]`** (`4f22aa6`) **Automated Demo Walkthroughs**:
  - Added automated Playwright demo GIF recording tests and embedded walkthrough animations in READMEs.
- **`[2026-10-03 04:35:04 +02:00]`** (`7ce1fa4`) **Domain-First UI Topology**:
  - Restructured UI navigation layout into domain tabs with scoped stubs sidebar.
- **`[2026-10-03 04:20:40 +02:00]`** (`f5b8e33`) **Subproject Documentation Guides**:
  - Restructured README documentation into dedicated modular guides for all subprojects.
- **`[2026-10-03 04:09:05 +02:00]`** (`ef4ccd6`) **Syntax Highlighting Fix**:
  - Corrected JSON syntax highlighting tokenization and escaping in `highlighter.js`.
- **`[2026-10-02 08:28:34 +02:00]`** (`361e942`) **Swagger UI Link**:
  - Added relative navigation link to neighbor `/__admin/swagger-ui/`.
- **`[2026-10-02 08:12:23 +02:00]`** (`6da3dc1`) **HTTP Tester Enhancements**:
  - Added dual inspection view, permanent headers, presets (`+ JSON`, `+ Bearer`), and execution history dock.
- **`[2026-10-02 07:59:15 +02:00]`** (`6bf1bd2`) **Hot-Reload Dev Static Files**:
  - Added parent relative candidate path checking for development static file hot reloading.
- **`[2026-10-02 07:58:36 +02:00]`** (`b050b45`) **Playwright UI Testing**:
  - Migrated UI test suite to Playwright headless Chromium and enabled live file hot-reload testing.
- **`[2026-10-02 06:59:14 +02:00]`** (`dd5112f`) **UI Test Coverage Expansion**:
  - Added test coverage for tester inputs, journal search filtering, and tab routing.
- **`[2026-10-02 06:47:35 +02:00]`** (`f1a8dee`) **Interactive HTTP Tester**:
  - Added built-in HTTP client in the web UI with URL hash deep-linking and **⚡ Test Stub** shortcut.
- **`[2026-10-02 06:45:36 +02:00]`** (`770e184`) **JSON Syntax Highlighting & cURL Export**:
  - Added client-side JSON syntax coloring and one-click copy as `curl` utility.
- **`[2026-10-02 06:44:03 +02:00]`** (`7a213cc`) **Filterable Request Journal**:
  - Added journal table viewer with match status badges, expandable detail rows, and auto-refresh.
- **`[2026-10-02 06:42:33 +02:00]`** (`b78c294`) **URL Hash Router & Deep Linking**:
  - Implemented client-side hash router (`#stubs`, `#journal`, `#tester`, `#scenarios`) with bookmarkable query parameters.
- **`[2026-10-02 06:26:44 +02:00]`** (`3d7a020`) **Standalone Runner Dependencies**:
  - Configured standalone runtime dependencies for the `runStandalone` task in `wiremock-extension-bundle`.
- **`[2026-10-02 06:08:13 +02:00]`** (`920c1bf`) **Initial Commit: Modular WireMock Extensions Suite**:
  - Initialized repository with `wiremock-extension-json-logging`, `wiremock-extension-otel`, `wiremock-extension-ui`, and `wiremock-extension-bundle`.
