# Changelog

All notable changes to the `wiremock-extensions` suite are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## 1.0.0 (2026-10-08)


### Features

* **examples:** add case management stateful reference example and scenarios DAG visualizer ([3ff84fd](https://github.com/mattiasgreen/wiremock-extensions/commit/3ff84fdf9b599e378d968d7deb31511effe44174))
* **openapi,ui:** add live transparent proxying, traffic snapshot recording, and project mode toggle ([a0c0a7f](https://github.com/mattiasgreen/wiremock-extensions/commit/a0c0a7febef778a40299ff71fd2bbc05635bb10c))
* **openapi:** Add OpenAPI 3.0/3.1 spec ingestion, stub synthesis engine, Admin API route, and embedded Web UI modal ([b38b80e](https://github.com/mattiasgreen/wiremock-extensions/commit/b38b80e13fc9de1da4f46d4405c5d4c70cf6354b))
* **openapi:** Add OpenAPI 3.0/3.1 spec ingestion, stub synthesizer, Admin API route, and UI modal ([3c38d0a](https://github.com/mattiasgreen/wiremock-extensions/commit/3c38d0a6b0948a257fb64cabb0c652c602454531))
* **openapi:** Synthesize ready-made request examples with substituted parameters and prefill HTTP Tester ([b3c0090](https://github.com/mattiasgreen/wiremock-extensions/commit/b3c00902ce321a27267aea775534aa63c9590881))
* **otel,logging:** implement W3C distributed tracing and log correlation ([bfb6e69](https://github.com/mattiasgreen/wiremock-extensions/commit/bfb6e69983769ca22753a3516fa612437f79912e))
* **otel,logging:** implement W3C distributed tracing and log correlation ([dc42695](https://github.com/mattiasgreen/wiremock-extensions/commit/dc4269560748b987b1137cd203803cbddc56ffe0))
* **quality:** enforce SpotBugs/FindSecBugs gate and fix security findings ([a0d5a4b](https://github.com/mattiasgreen/wiremock-extensions/commit/a0d5a4b049cd29874386d8be9bd57d85d6aeb6e3))
* **ui:** add filterable journal, expandable detail rows and auto-refresh ([7a213cc](https://github.com/mattiasgreen/wiremock-extensions/commit/7a213cc28b1e86f46de3139c19a168ed65eea5ab))
* **ui:** add interactive HTTP request tester with deep linking and stub testing shortcut ([f1a8dee](https://github.com/mattiasgreen/wiremock-extensions/commit/f1a8dee861db545ca308522e6fb88aa578aec3c0))
* **ui:** add JSON syntax highlighting and copy as cURL utility ([770e184](https://github.com/mattiasgreen/wiremock-extensions/commit/770e18476712b5e8e22ba888a9bc9dd742c2cef5))
* **ui:** add relative URL link to neighbor /__admin/swagger-ui/ ([361e942](https://github.com/mattiasgreen/wiremock-extensions/commit/361e9424e1d8b74447f9b1f61745200b13e24e1d))
* **ui:** add stub lifecycle, project grouping, import/export hub, and bulk actions ([c9326aa](https://github.com/mattiasgreen/wiremock-extensions/commit/c9326aaff7c9c9f9dc9b04ddf4a32a7bf093eb45))
* **ui:** add URL hash router and deep linking for tabs and stubs ([b78c294](https://github.com/mattiasgreen/wiremock-extensions/commit/b78c2941ab41fce0520d8a0f9b7f28f93908d8a7))
* **ui:** clean up stub card actions and right-align project tag ([3e7271b](https://github.com/mattiasgreen/wiremock-extensions/commit/3e7271bba7aa68adecad1d0752741d123f953015))
* **ui:** display version and git branch/commit next to brand logo ([1f21d1b](https://github.com/mattiasgreen/wiremock-extensions/commit/1f21d1ba0a9eb3cfacf3008b16c8fa5973b08b41))
* **ui:** enhance HTTP tester with dual inspection, permanent headers, presets and history ([6da3dc1](https://github.com/mattiasgreen/wiremock-extensions/commit/6da3dc15b2ba9fe88ce1330e48a658af8e3a7dab))
* **ui:** redesign http tester history into compact collapsible vertical sidebar ([385e864](https://github.com/mattiasgreen/wiremock-extensions/commit/385e86486768502385f1f230346997b00fd6e2c7))
* **ui:** redesign workbench layout with persistent stubs sidebar, bottom history dock, and E2E demo ([d23d3dc](https://github.com/mattiasgreen/wiremock-extensions/commit/d23d3dcaecb23c3da8dbe992423d710896ea0416))
* **ui:** restructure topology into domain-first navigation and scoped stubs sidebar ([7ce1fa4](https://github.com/mattiasgreen/wiremock-extensions/commit/7ce1fa41df7ad8c60d9d695723dfdf32b39f16e9))
* **ui:** support dynamic modular asset routing in UiAdminApiEndpoint ([77063f7](https://github.com/mattiasgreen/wiremock-extensions/commit/77063f73f276590b7a8db90e110c2bd4a6b6e369))
* **ui:** update complete E2E scenario with stub clearing, GET/POST testing, and unmatched journal review ([0e1eaa0](https://github.com/mattiasgreen/wiremock-extensions/commit/0e1eaa00f45d4134e525bfc2e6d32115628bd81d))


### Bug Fixes

* **bundle:** add standalone runtime dependencies for runStandalone ([3d7a020](https://github.com/mattiasgreen/wiremock-extensions/commit/3d7a020b06ccf1e865675ed2432ff25e390ffd42))
* **otel:** resolve SpotBugs singleton constructor and uncalled method findings ([6474d11](https://github.com/mattiasgreen/wiremock-extensions/commit/6474d11027a0b43ce7de204dbc9d5d07e60d1636))
* **ui:** add parent relative path candidate for dev static file hot reload ([6bf1bd2](https://github.com/mattiasgreen/wiremock-extensions/commit/6bf1bd26df3ecf90d3e756be98360aabef98fdd6))
* **ui:** correct JSON syntax highlighting tokenization and escaping ([ef4ccd6](https://github.com/mattiasgreen/wiremock-extensions/commit/ef4ccd6adb26ecb1e8bdf455fa05230c69207e71))
* **ui:** prioritize /ui/version route before /ui/{file} ([c078dce](https://github.com/mattiasgreen/wiremock-extensions/commit/c078dce6e68b6a50e018911e7a0c88e8b64f7a8c))
* **ui:** Support urlPathTemplate and urlPathMatching in getStubUrl ([87aa97c](https://github.com/mattiasgreen/wiremock-extensions/commit/87aa97c3a3f0d05225abb8ad47b63b325fc7de8b))

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
---

## [Committed on `main`]

### [0.1.0] - 2026-10-05

#### Added / Enhanced
- **`[2026-10-05 08:16:00 +02:00]`** **CI / CD Pipeline Modernization (`.github/workflows/ci.yml`)**:
  - Upgraded to `actions/setup-java@v5` and `gradle/actions/setup-gradle@v4` with automatic dependency and build caching.
  - Added automated `spotlessCheck` step to fail fast on formatting regressions.
  - Maintained 100% Zero-NPM pure Java and Vanilla JS pipeline execution.
- **`[2026-10-05 08:16:00 +02:00]`** **Packaging & Distribution Refinements (`build.gradle`, `README.md`)**:
  - Configured `maven-publish` to selectively publish distributable extensions and exclude internal `wiremock-examples`.
  - Added Zero-Build Consumer Runner guide using published Gradle dependencies.
  - Added production-ready Alpine Dockerfile reference architecture.
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
