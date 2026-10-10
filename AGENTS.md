# AGENTS.md: Developer & Coding Agent Guidelines

Guidelines, constraints, architecture, and commands for autonomous coding agents and human contributors working on `wiremock-extensions`.

---

## 1. Core Architecture & Subproject Topology

`wiremock-extensions` is a modular suite of production extensions for WireMock Standalone (JDK 21).

| Subproject | Responsibility | Primary Extension Points |
| :--- | :--- | :--- |
| **`wiremock-extension-json-logging`** | Single-line JSON traffic logging with corporate header MDC propagation (`x-correlation-id`, `x-tenant-id`). | `RequestFilterV2` (`MdcRequestFilter`), `ServeEventListener` (`JsonLoggingListener`) |
| **`wiremock-extension-otel`** | In-process OpenTelemetry metrics recording (`wiremock_requests_total`, duration histograms) + Prometheus scrape route (`/__admin/metrics/prometheus`). | `ServeEventListener` (`OtelMetricsListener`), `AdminApiExtension` (`PrometheusAdminEndpoint`) |
| **`wiremock-extension-openapi`** | OpenAPI 3.0 / 3.1 specification ingestion, schema data synthesis, and live proxy/recording routes (`/__admin/openapi/import`). | `AdminApiExtension` (`OpenApiAdminEndpoint`) |
| **`wiremock-extension-ui`** | Embedded Vanilla JS single-page web UI at `/__admin/ui/` with stub viewer, scenario DAG visualizer, request journal, and HTTP tester. | `AdminApiExtension` (`UiAdminApiEndpoint`, `StubLifecycleAdminEndpoint`) |
| **`wiremock-extension-bundle`** | Meta-jar aggregating all extensions with auto-discovery (`ExtensionFactory`) + `StandaloneDevServer` runner. | Meta-packaging & standalone JavaExec runner |
| **`wiremock-examples`** | Case Management reference implementation modeling stateful FSM scenarios, parallel collision pitfalls, and integration tests. | Reference implementation & test suite |

---

## 2. Inviolable Constraints

1. **Zero NPM / Zero JS Bundlers Constraint**:
   - `wiremock-extension-ui` is intentionally pure Vanilla JavaScript, CSS, and HTML.
   - **NEVER** introduce `package.json`, `npm`, `yarn`, `pnpm`, Webpack, Vite, Rollup, or Babel.
   - Modularization must be achieved using standard modern browser **Native ES Modules** (`import` / `export` and `<script type="module" src="app.js"></script>`).
2. **Terse, Agent-Friendly Documentation**:
   - Keep comments concise, high-density, and structured for AI coding agents.
   - Focus on state contracts, input/output types, DOM IDs/selectors, and WireMock Admin API routes. Avoid chatty narrative prose in code.
3. **Format Standards**:
   - Always run `./gradlew spotlessApply` before committing.
   - Adhere strictly to Google Java Format / Palantir standards via Spotless.
4. **Static Security & Code Quality Gate (SpotBugs + FindSecBugs)**:
   - Enforced across all subprojects on production code via `./gradlew spotbugsMain` and `./gradlew check`.
   - Builds fail (`ignoreFailures = false`) if any correctness or security issues are detected.
   - Always use explicit `Locale.ROOT` on case conversions (e.g., `toLowerCase(Locale.ROOT)`).
   - Always sanitize potential CRLF (`\r`, `\n`) characters before logging dynamic inputs.
   - Always load classpath assets using static class literals (`MyClass.class.getResourceAsStream(...)`), never dynamic `getClass()`.

---

## 3. Essential Commands & Development Workflows

### Fast Feedback & Inner Loop Guidelines
To prevent long idle wait times and maintain high velocity, always choose the tightest feedback loop applicable:
- **Never run full multi-module `./gradlew check` or all E2E tests during active code edits.**
- **Defer Spotless and SpotBugs gates** until feature completion or pre-commit / pre-push.
- Clean stale JVM background processes periodically with `./gradlew --stop` if daemon reuse slows down.

#### A. Frontend & UI Workflows (`wiremock-extension-ui`)
* **Loop 1: Dev Server Hot-Reload (0-second rebuild)**
  * Start `./gradlew :wiremock-extension-bundle:runStandalone` in background or separate shell.
  * Static web assets (`.html`, `.css`, `.js`) in `src/main/resources/ui/` are served straight from disk. Just refresh the browser.
* **Loop 2: Targeted Playwright Verification (~10–18s)**
  * Filter to only the single test method being authored or debugged:
    ```bash
    ./gradlew :wiremock-extension-ui:test --tests "UiPlaywrightTest.testSpecificMethodName"
    ```
* **Loop 3: Subproject Formatting (Fast)**
  * Format only web/UI assets without running full repo formatting:
    ```bash
    ./gradlew :wiremock-extension-ui:spotlessApply
    ```

#### B. Backend / Java Workflows (`json-logging`, `otel`, `openapi`, etc.)
* **Loop 1: Targeted Class or Method Test (< 5s)**
  * ```bash
    ./gradlew :wiremock-extension-openapi:test --tests "OpenApiSpecParserTest.shouldParseSimpleSpec"
    ```
* **Loop 2: Module-Level Test Suite (< 15s)**
  * ```bash
    ./gradlew :wiremock-extension-openapi:test
    ```
* **Loop 3: Module-Level Static Analysis Gate**
  * ```bash
    ./gradlew :wiremock-extension-openapi:spotbugsMain
    ```

#### C. Outer Loop Gate (Pre-Commit & Pre-PR)
**MANDATORY**: The final outer check MUST ALWAYS be run after the inner loop is green, before committing or creating a PR:
```bash
./gradlew spotlessApply
./gradlew check
```
Only proceed to `git commit` once both inner loop and outer gate are 100% green.

### Essential Task Reference
* **Run all verification checks** (Tests, Spotless, and SpotBugs):
  ```bash
  ./gradlew check
  ```
* **Run standalone development server with sample stubs**:
  ```bash
  ./gradlew :wiremock-extension-bundle:runStandalone
  # Accessible at:
  #   UI:         http://localhost:8080/__admin/ui/
  #   Metrics:    http://localhost:8080/__admin/metrics/prometheus
  #   Admin API:  http://localhost:8080/__admin/mappings
  ```
* **Run UI integration tests (Playwright)**:
  ```bash
  ./gradlew :wiremock-extension-ui:test
  ```
* **Format codebase (Java & web assets)**:
  ```bash
  ./gradlew spotlessApply
  ```
* **Regenerate documentation demo GIFs** (only when UI visual changes are made):
  ```bash
  ./gradlew :wiremock-extension-ui:recordDemoGifs
  ```

---

## 4. Extension Implementation Conventions

### WireMock Extension SPI Auto-Discovery
Each extension must be registered in its module's `META-INF/services/com.github.tomakehurst.wiremock.extension.ExtensionFactory`.
- Example factory:
  ```java
  public class UiExtensionFactory implements ExtensionFactory {
      @Override
      public List<Extension> create(ExtensionContext extensionContext) {
          return List.of(new UiAdminApiEndpoint());
      }
  }
  ```

### Static Asset Serving & Dev Hot-Reload
- `UiAdminApiEndpoint` implements `AdminApiExtension` and maps routes under `/__admin/ui/...`.
- In local development workspaces, static files are hot-reloaded directly from the source directory (`wiremock-extension-ui/src/main/resources/ui/`) without needing recompilation or JAR repackaging.
- In production / standalone JAR execution, assets fall back to the classpath (`getClass().getResourceAsStream(...)`).

### UI DOM & Test Selectors
- Automated tests (`UiPlaywrightTest` and `UiAssetAndContractTest`) bind to explicit element IDs:
  - Navigation tabs: `#nav-tab-stubs`, `#nav-tab-journal`, `#nav-tab-tester`, `#nav-tab-scenarios`.
  - Stubs tab: `#search-box`, `#stub-list`, `#btn-test-stub`, `#btn-copy-curl`, `#btn-copy-json`.
  - Journal tab: `#journal-table`, `#journal-search`, `#journal-auto-refresh`.
  - Tester tab: `#tester-method`, `#tester-url`, `#tester-send-btn`, `#tester-response-body`.
  - Scenarios tab: `#scenarios-container`, `#btn-refresh-scenarios`, `#btn-reset-scenarios`.
- When updating UI code or modularizing components, ensure all existing ID contracts remain intact.
