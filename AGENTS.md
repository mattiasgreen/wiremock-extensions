# AGENTS.md: Developer & Coding Agent Guidelines

Guidelines, constraints, architecture, and commands for autonomous coding agents and human contributors working on `wiremock-extensions`.

---

## 1. Core Architecture & Subproject Topology

`wiremock-extensions` is a modular suite of production extensions for WireMock Standalone (JDK 21).

| Subproject | Responsibility | Primary Extension Points |
| :--- | :--- | :--- |
| **`wiremock-extension-json-logging`** | Single-line JSON traffic logging with corporate header MDC propagation (`x-correlation-id`, `x-tenant-id`). | `RequestFilterV2` (`MdcRequestFilter`), `ServeEventListener` (`JsonLoggingListener`) |
| **`wiremock-extension-otel`** | In-process OpenTelemetry metrics recording (`wiremock_requests_total`, duration histograms) + Prometheus scrape route (`/__admin/metrics/prometheus`). | `ServeEventListener` (`OtelMetricsListener`), `AdminApiExtension` (`PrometheusAdminEndpoint`) |
| **`wiremock-extension-ui`** | Embedded Vanilla JS single-page web UI at `/__admin/ui/` with stub viewer, scenario DAG visualizer, request journal, and HTTP tester. | `AdminApiExtension` (`UiAdminApiEndpoint`) |
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

---

## 3. Essential Commands & Development Workflows

* **Run all tests** (GIF recording excluded by default for speed):
  ```bash
  ./gradlew test
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
* **Check formatting compliance**:
  ```bash
  ./gradlew spotlessCheck
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
