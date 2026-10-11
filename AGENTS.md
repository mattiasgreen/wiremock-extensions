# AGENTS.md: Developer & Coding Agent Guidelines

Guidelines, constraints, architecture, and commands for autonomous coding agents and human contributors working on `wiremock-extensions`.

---

## 1. Core Architecture & Subproject Topology

`wiremock-extensions` is a modular suite of production extensions for WireMock Standalone (JDK 21).

| Subproject | Responsibility | Primary Extension Points |
| :--- | :--- | :--- |
| **`wiremock-extension-json-logging`** | Single-line JSON traffic logging with corporate header MDC propagation (`x-correlation-id`, `x-tenant-id`). | `RequestFilterV2` (`MdcRequestFilter`), `ServeEventListener` (`JsonLoggingListener`) |
| **`wiremock-extension-otel`** | In-process OpenTelemetry metrics recording (`wiremock_requests_total`, duration histograms) + Prometheus scrape route (`/__admin/metrics/prometheus`). | `ServeEventListener` (`OtelMetricsListener`), `AdminApiExtension` (`PrometheusAdminEndpoint`) |
| **`wiremock-extension-stateful`** | In-memory dynamic stateful API simulation with AST evaluation and multi-tenant isolation. | `AdminApiExtension` (`StatefulAdminApiEndpoint`), `RequestFilterV2` |
| **`wiremock-extension-tcp-netem`** | In-process L4 TCP network emulation & Toxiproxy-compatible fault injection with dual-ingress architecture. | `AdminApiExtension` (`TcpNetEmAdminEndpoint`), `ServeEventListener` (`NetEmStubListener`) |
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
5. **Strict Zero-Credential-Inspection & No-Circumvention Policy**:
   - **NEVER** attempt to extract, dump, or query credentials, tokens, passwords, or secrets from the host system or environment.
   - Specifically forbidden:
     - Querying credential helpers (e.g., `git credential fill`, OS credential managers, keychains, secret services).
     - Inspecting private user files (`~/.ssh`, `~/.git-credentials`, `~/.netrc`, `~/.aws`, `~/.config/gh`, etc.).
     - Dumping environment variables that may contain API keys or auth tokens.
   - **Respect Access Boundaries**: If a remote resource, API, or CI log returns `401 Unauthorized` or `403 Forbidden`, do **NOT** attempt credential harvesting or circumvention. Instead, reproduce the issue locally or explicitly ask the user for the relevant log snippet or details.

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

#### B. Headless Standalone Proxy Workflows (`WireMockConsoleProxy.java`)
* **Loop 1: Instant Script Run (0-second compilation)**
  * Run the uncompiled `.java` file directly via JDK 21 JEP 330:
    ```bash
    java WireMockConsoleProxy.java
    ```
  * Or via Gradle: `./gradlew :wiremock-extension-ui:runProxy`
* **Loop 2: Targeted Console Proxy Verification (< 5s)**
  * Filter to a single test method:
    ```bash
    ./gradlew :wiremock-extension-ui:test --tests "WireMockConsoleProxyTest.shouldRouteDynamicallyViaForwardedHeaders"
    ```
* **Loop 3: Full Console Proxy Suite (< 10s)**
  * ```bash
    ./gradlew :wiremock-extension-ui:test --tests "WireMockConsoleProxyTest"
    ```

#### C. Backend / Java Workflows (`json-logging`, `otel`, `openapi`, etc.)
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

#### D. Visual Snapshot Capture & Inspection Workflow
* **Loop 1: Generate Visual Snapshots (Web SPA)**
  * ```bash
    ./gradlew :wiremock-extension-ui:captureUiSnapshots
    ```
  * Generates high-resolution PNGs in:
    * `wiremock-extension-ui/build/reports/screenshots/web/`
* **Loop 2: Agent Visual Inspection**
  * Autonomous agents **must inspect the generated images** using `view_file` to visually verify layout balance, text truncation, visual hierarchy, and color contrast.

#### E. Outer Loop Gate (Pre-Commit & Pre-PR)
**MANDATORY**: The final outer check MUST ALWAYS be run after the inner loop is green, before committing or creating a PR:
```bash
./gradlew spotlessApply
./gradlew check
```
Only proceed to `git commit` once both inner loop and outer gate are 100% green.

#### F. Pull Request (PR) Preparation Protocol
Before submitting or presenting a PR to the user, coding agents must complete the following checklist:
1. **Abstract Spec Alignment**: Verify that the feature is documented in `docs/spec/functional-specification.md` and mapped in `docs/spec/platform-bindings.md`.
2. **Senior Self-Review Reflection**: Include a brief self-critique using the **5 Lenses of Senior UX Review** in the PR summary.
3. **Documentation Review**: Verify that all relevant documentation (`README.md`, module-specific `README.md`, architectural notes) has been updated.
4. **UI Demo Assets Check**: Ensure demo GIFs (`UiDemoGifRecordingTest`) and visual snapshots (`UiSnapshotCaptureTest`) have been updated.
5. **Copy-Pasteable PR Markdown**: Output clean, complete, copy-pasteable markdown for the PR body (Summary, Key Features, Verification Checklist).
6. **Direct PR Creation URL**: Clickable link to create the PR on GitHub (`https://github.com/mattiasgreen/wiremock-extensions/compare/main...<feature-branch>?expand=1`).

---

## 4. Autonomous Agentic Iteration Workflow & Senior Review Protocol

Autonomous agents must not act like junior developers who randomly drop buttons into panels and stop at deterministic green tests. Every feature iteration must follow this 5-phase discipline:

```
┌────────────────────────────────────────────────────────────────────────┐
│                   Senior Agentic Development Loop                      │
│                                                                        │
│  [1. Abstract Spec] ──► [2. Platform Bindings] ──► [3. Implementation] │
│                                                            │           │
│  [5. Senior Reflection] ◄── [4. Visual Snapshot Inspection] ◄──┘       │
└────────────────────────────────────────────────────────────────────────┘
```

### Phase 1: Abstract Product & Functional Spec Alignment
- **Never start in concrete UI code (HTML/Swing/divs)**: First place the capability in [`docs/spec/functional-specification.md`](docs/spec/functional-specification.md).
- Write or update the **Compact E2E User Story** (Given/When/Then format). The story must remain valid whether the backend is Java or Rust, and whether the frontend is Web, Swing, or iOS.

### Phase 2: Platform Mapping & Implementation
- Bind the abstract concepts to concrete components in [`docs/spec/platform-bindings.md`](docs/spec/platform-bindings.md) (DOM IDs for Web, `component.setName(...)` for Swing).
- Implement the functionality following the zero-dependency rule and subproject constraints.

### Phase 3: Automated E2E Verification
- Implement deterministic E2E verification tests (`UiPlaywrightTest` for Web, `WireMockConsoleProxyTest` for Proxy) directly validating the compact user stories.

### Phase 4: Visual Capture & Multimodal Inspection
- Execute `./gradlew :wiremock-extension-ui:captureUiSnapshots`.
- Call `view_file` on the resulting PNG images in `build/reports/screenshots/`.
- Critically evaluate the actual rendered pixels: is text clipped? Is visual weight balanced? Are buttons awkwardly floating?

### Phase 5: Senior UX & Cognitive Self-Review (The 5 Lenses)
Before declaring any task done or presenting to the user, the agent must evaluate the solution through the **5 Lenses of Senior Product Engineering**:
1. **CRUD & State Lifecycle Completeness (The Law of Symmetry)**:
   - Does this entity have creation, editing, deletion, toggling, search, and sorting?
   - Never implement half a lifecycle (e.g. adding a filter without a clear button, or adding an item without deletion).
2. **The Negative Space (Empty, Error, and Boundary States)**:
   - What happens when 0 items exist? Is there a helpful call-to-action?
   - What happens if the server is offline or returns an error?
3. **Cognitive Ergonomics & Visual Hierarchy**:
   - **Hick's Law**: Group actions logically; do not create unorganized button soups. Distinguish Primary (Send/Save), Secondary (Copy/Export), and Destructive (Delete/Clear) actions.
   - **Fitts's Law**: Place actions directly adjacent to the data they affect.
   - **Immediate Sensory Feedback**: Always provide instant confirmation (e.g. `"Copied to clipboard!"` toast, spinners during requests).
4. **Persona Ergonomics (The 30-Second Rule)**:
   - Can an integration debugger trace an unmatched 404 and replay it in the tester in under 30 seconds?
5. **Platform Idioms & Power-User Accelerators**:
   - Keyboard shortcuts (`Enter` to send, `Escape` to dismiss, `Ctrl+F` to search).
   - Clipboard integration and focus management.

**Mandatory Rule**: Every major feature summary from the agent must include a concise **Senior Self-Review Reflection** addressing these lenses.

---

## 5. Extension Implementation Conventions

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
