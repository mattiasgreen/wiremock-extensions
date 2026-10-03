# Project Review & Architecture Roadmap: WireMock Extensions

Comprehensive review of project structure, code style, architecture, and engineering standards for the `wiremock-extensions` suite.

---

## Executive Scorecard

| Area | Current State | Target State | Priority | Status |
| :--- | :--- | :--- | :--- | :--- |
| **Java Tooling & Formatting** | No Spotless, no linter, unconfigured javac warnings, duplicated dependencies across subprojects. | Spotless (Google Java Format / Palantir), Gradle Version Catalog (`libs.versions.toml`), `-Xlint:all`, `-Werror`. | **P0** | Pending |
| **UI Modularization** | Monolithic 1,534-line `app.js` with tightly coupled state, DOM, router, and API calls. | Modular Native ES Modules (`<script type="module">`), strict 0-NPM/0-bundler constraint, terse agent-ready comments. | **P0** | Pending |
| **Agent Instructions** | No agent instructions or prompt constraints file (`AGENTS.md`). | Comprehensive `AGENTS.md` establishing conventions, architecture, test commands, and strict Vanilla JS rules. | **P0** | Pending |
| **UI Asset Serving** | Hardcoded static routes in `UiAdminApiEndpoint.java` (only `/ui/app.js` and `/ui/style.css`). | Dynamic/wildcard static asset resolution supporting subdirectories (e.g. `/ui/modules/*.js`) with dev hot-reloading and correct MIME types. | **P1** | Pending |
| **Test & Build Hygiene** | `UiDemoGifRecordingTest.java` executes on every `./gradlew test`, regenerating 4 large GIFs and bloating git. | Segregate GIF recording to a dedicated task/tag (`recordDemoGifs` / `@Tag("recording")`). | **P1** | Pending |
| **CI / CD Pipeline** | Minimal `ci.yml`; lacks Spotless check, Gradle build caching, and headless Playwright OS dependencies. | Full matrix/verification pipeline with `spotlessCheck`, headless test runner, and caching. | **P2** | Pending |
| **Extension Robustness** | `HeaderNormalizer.java` recompiles regexes on every call; `OtelMetricsRegistry.java` uses JVM-wide singleton. | Precompiled `Pattern` constants; instance-scoped metrics registry with optional shared fallback. | **P2** | Pending |

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

---

## Phased Implementation Roadmap

### Phase 1: Build Modernization & Agent Governance (Immediate Priority)

- [ ] **1.1. Create `AGENTS.md` in Repo Root**
  - [ ] Define project philosophy, strict constraints (Vanilla JS only, 0-NPM).
  - [ ] Document project module layout and responsibilities.
  - [ ] Specify key development and testing commands (`test`, `runStandalone`, `spotlessApply`, etc.).
  - [ ] Detail extension points (`AdminApiExtension`, `ServeEventListener`, `RequestFilterV2`, `ExtensionFactory`).
  - [ ] Establish coding comment style: terse, contract-focused, agent-oriented.

- [ ] **1.2. Adopt Gradle Version Catalog (`gradle/libs.versions.toml`)**
  - [ ] Centralize versions: WireMock (`3.12.1`), JUnit 5 (`5.11.4`), AssertJ (`3.27.3`), Logback (`1.5.16`), Jackson (`2.18.2`), Playwright (`1.49.0`), OpenTelemetry (`1.47.0`), Prometheus (`1.3.6`).
  - [ ] Refactor root and subproject `build.gradle` files to use version catalog aliases (`libs.wiremock`, `libs.junit.jupiter`, etc.).

- [ ] **1.3. Configure Spotless for Java & Web Assets**
  - [ ] Add `id 'com.diffplug.spotless' version '7.0.2'` (or latest stable) to root `build.gradle`.
  - [ ] Configure `spotless.java` with Google Java Format or Palantir Java Format.
  - [ ] Configure `spotless.format('misc')` for Gradle, Markdown, JSON, CSS, and HTML files (trim trailing whitespace, standard indent, end with newline).
  - [ ] Run `./gradlew spotlessApply` and verify `./gradlew spotlessCheck`.

- [ ] **1.4. Isolate GIF Recording Test**
  - [ ] Add `@Tag("recording")` to `UiDemoGifRecordingTest.java`.
  - [ ] Configure root/subproject test task to exclude `recording` tag by default during regular test runs:
    ```groovy
    tasks.named('test') {
        useJUnitPlatform {
            excludeTags 'recording'
        }
    }
    ```
  - [ ] Register a dedicated Gradle task `recordDemoGifs` for explicit generation when documentation assets need updating:
    ```groovy
    tasks.register('recordDemoGifs', Test) {
        group = 'verification'
        description = 'Generates animated demo GIFs for UI documentation via Playwright'
        useJUnitPlatform {
            includeTags 'recording'
        }
    }
    ```

- [ ] **1.5. Enable Strict Compiler Flags**
  - [ ] Configure Java compilation in `subprojects`:
    ```groovy
    tasks.withType(JavaCompile).configureEach {
        options.compilerArgs += ['-Xlint:all', '-Xlint:-processing', '-Werror', '-parameters']
        options.encoding = 'UTF-8'
    }
    ```

---

### Phase 2: UI Asset Serving & Native ES-Module Decomposition

- [ ] **2.1. Upgrade `UiAdminApiEndpoint.java` for Dynamic Modular Asset Serving**
  - [ ] Replace static hardcoded routes (`/ui/app.js`, `/ui/style.css`) with dynamic static file resolution supporting subpaths (e.g. `/ui/modules/{fileName}`).
  - [ ] Support correct MIME types (`application/javascript; charset=utf-8`, `text/css; charset=utf-8`, `text/html; charset=utf-8`).
  - [ ] Maintain the dual-resolution strategy:
    1. Dev filesystem resolution (`DEV_ROOT_CANDIDATES`) for instant hot-reload during development.
    2. Production classpath fallback (`getClass().getResourceAsStream(...)`) for standalone JAR execution.
  - [ ] Update `UiAssetAndContractTest.java` to test modular subpath asset delivery.

- [ ] **2.2. Decompose Monolithic `app.js` into Native ES Modules**
  - Target directory: `wiremock-extension-ui/src/main/resources/ui/modules/`
  - [ ] `modules/state.js`: Global application state, subscription listeners, and state mutation dispatchers.
  - [ ] `modules/api.js`: WireMock Admin API client (`/mappings`, `/requests`, `/scenarios`, reset endpoints).
  - [ ] `modules/router.js`: Hash-based tab routing (`#stubs`, `#journal`, `#tester`, `#scenarios`), query parameter serialization/deserialization.
  - [ ] `modules/dom.js`: Cached DOM element queries, helper utilities, and safe HTML/escaping primitives.
  - [ ] `modules/stubs.js`: Stub list filtering, search, selection, detail card rendering, and cURL generation.
  - [ ] `modules/journal.js`: Request journal polling, table rendering, diff modal, and auto-refresh controls.
  - [ ] `modules/tester.js`: HTTP request runner, dynamic headers key-value editor, execution, and response viewer.
  - [ ] `modules/scenarios.js`: Scenario state badges, manual transition controls, and SVG/DAG graph visualizer.
  - [ ] `modules/highlighter.js`: Terse JSON syntax highlighter token generator.
  - [ ] `app.js`: Main entry point initializing modules, registering event handlers, and bootstrapping the app.
  - [ ] `index.html`: Update script tag to `<script type="module" src="/__admin/ui/app.js"></script>`.

- [ ] **2.3. Add Terse, Agent-Friendly Comments across UI Modules**
  - [ ] Annotate state structures, DOM ID contracts, and API routes with compact JSDoc blocks.
  - [ ] Clearly document test selector dependencies (e.g., `#btn-test-stub`, `#search-box`, `#tester-url`).

- [ ] **2.4. Verify Playwright and Integration Test Suites**
  - [ ] Run full Playwright test suite (`UiPlaywrightTest`) to confirm 100% parity and zero regressions in browser execution.

---

### Phase 3: Extension Hardening & CI/CD Pipeline

- [ ] **3.1. Performance & Robustness Optimizations**
  - [ ] In `HeaderNormalizer.java`: Precompile `Pattern` constants for header normalization regexes instead of invoking `String.replaceAll` on every incoming request.
  - [ ] In `OtelMetricsRegistry.java`: Support instance-based registration and lifecycle teardown alongside the singleton fallback to improve multi-server test isolation.
  - [ ] In `MdcRequestFilter.java` & `JsonLoggingListener.java`: Ensure MDC cleanup guarantees even when unhandled exceptions occur in filter pipelines.

- [ ] **3.2. Upgrade GitHub Actions (`.github/workflows/ci.yml`)**
  - [ ] Add `spotlessCheck` step to fail fast on formatting violations.
  - [ ] Configure Gradle build cache and dependency verification.
  - [ ] Ensure headless Playwright Linux dependencies are installed or cached for CI runs.
  - [ ] Ensure `test` task runs efficiently with recording tests excluded.
