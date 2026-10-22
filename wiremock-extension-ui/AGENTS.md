# AGENTS.md: `wiremock-extension-ui` Module Guidelines

Scoped instructions for `wiremock-extension-ui` (Embedded Vanilla ES6 Web SPA at `/__admin/ui/`, `StubLifecycleAdminEndpoint`, and headless `WireMockConsoleProxy`).

---

## 1. Fast Feedback & 4-Tier Inner Loop

Always choose the fastest verification loop that covers your edit:

1. **Tier 0 — Dev Server Disk Hot-Reload (0s rebuild)**:
   - Run `./gradlew :wiremock-extension-bundle:runStandalone` in a background shell.
   - Edits to `.html`, `.css`, and `.js` in `src/main/resources/ui/` are served directly from disk.
2. **Tier 1 — Static Asset, ES Module Import & DOM Contract Gate (< 2s)**:
   ```bash
   ./gradlew :wiremock-extension-ui:test --tests "UiAssetAndContractTest" -q --console=plain
   ```
3. **Tier 2 — In-Browser Headless ES6 Unit Tests (< 3s)**:
   - Use [`UiModuleUnitTest`](src/test/java/com/github/mattiasgreen/wiremock/ui/UiModuleUnitTest.java) for pure JS logic (`filter.js`, query parsing, cURL/stubFor synthesis, state helpers):
   ```bash
   ./gradlew :wiremock-extension-ui:test --tests "UiModuleUnitTest" -q --console=plain
   ```
4. **Tier 3 — Targeted Domain Playwright E2E Test (~4–8s)**:
   - Run only the specific domain test class and method:
   ```bash
   ./gradlew :wiremock-extension-ui:test --tests "UiStubsAndLifecyclePlaywrightTest.testInitialPageLoadAndStubsListing" -q --console=plain
   ```
   - Domain suites (inheriting from `AbstractUiPlaywrightTest`):
     - `UiStubsAndLifecyclePlaywrightTest`: Stubs catalog, boolean filter, create/edit/clone, enable/disable, bulk actions, projects.
     - `UiJournalAndTesterPlaywrightTest`: HTTP Tester, presets/history, Request Journal filtering, resolutions/triage.
     - `UiOpenApiPlaywrightTest`: OpenAPI YAML/JSON import, live proxy recording, project mode flip, POST example synthesis.
     - `UiScenariosAndStatefulPlaywrightTest`: Scenarios DAG visualizer, dynamic stateful stubs, target host modal, vanilla WireMock graceful degradation.
5. **Console Proxy Loop (< 4s)**:
   ```bash
   ./gradlew :wiremock-extension-ui:test --tests "WireMockConsoleProxyTest" -q --console=plain
   ```

---

## 2. Scalable Zero-Build Vanilla ES6 Invariants

1. **Zero NPM / Zero Bundlers**: Pure ES6 modules (`import` / `export`) under `src/main/resources/ui/modules/`. Never add `package.json`, Webpack, Vite, or external CDN dependencies (must remain 100% air-gapped capable).
2. **Single Source of Truth**: Author UI files **only** in `wiremock-extension-ui/src/main/resources/ui/`. Never hand-edit `docs/site/demo/`; run `./gradlew :wiremock-extension-ui:syncDemoSite` when syncing the static demo.
3. **Zero Marketing Assets in Classpath**: Never place `.gif` or `.png` screenshots inside `src/main/resources/ui/` (keeps the published Maven Central JAR `< 200 KB`).
4. **Container-Level Event Delegation**: Attach delegated listeners on stable parent containers (`#stub-list`, `#journal-list`) via `e.target.closest('[data-action]')` rather than binding per-row closures inside render loops.
5. **Mandatory XSS Escaping & Non-Blocking Feedback**:
   - Wrap all dynamic strings (`url`, `name`, headers, bodies) in `escapeHtml(...)` from `modules/dom.js`.
   - Never use blocking `alert()` for confirmations; use inline button feedback (`✅ Copied!`) or toast notifications.

---

## 3. Core DOM ID Contracts

Keep these DOM selector contracts intact across `index.html`, `modules/dom.js`, and Playwright tests:
- **Navigation**: `#nav-tab-stubs`, `#nav-tab-journal`, `#nav-tab-tester`, `#nav-tab-scenarios`, `#nav-tab-netem`.
- **Stubs**: `#search-box`, `#stub-list`, `#btn-test-stub`, `#btn-copy-curl`, `#btn-copy-json`.
- **Journal**: `#journal-table`, `#journal-list`, `#journal-search`, `#filter-unmatched-only`, `#journal-auto-refresh`.
- **Tester**: `#tester-method`, `#tester-url`, `#btn-tester-send`, `#tester-response-body`.
- **Scenarios**: `#scenarios-container`, `#btn-refresh-scenarios`, `#btn-reset-scenarios`.

> **UX & Visual Changes**: Activate the `ux-audit-and-snapshots` skill whenever adding or modifying UI views or workflows.
