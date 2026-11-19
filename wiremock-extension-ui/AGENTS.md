# AGENTS.md: `wiremock-extension-ui` Module Guidelines

Scoped instructions for `wiremock-extension-ui` (Embedded Vanilla ES6 Web SPA at `/__admin/ui/`, `StubLifecycleAdminEndpoint`, and headless `WireMockConsoleProxy`).

---

## 1. Fast Feedback & 4-Tier Inner Loop

Always choose the fastest verification loop that covers your edit:

1. **Tier 0 — Dev Server Disk Hot-Reload (0s rebuild)**:
   - Run `./gradlew :wiremock-extension-bundle:runStandalone` in a background shell.
   - Edits to `.html`, `.css`, and `.js` in `src/main/resources/ui/` are served directly from disk.
2. **Tier 1 — Static Asset, ES Module Import, DOM Contract & Snapshot Gate (< 2s)**:
   ```bash
   ./gradlew :wiremock-extension-ui:test --tests "UiAssetAndContractTest" --tests "DemoSnapshotContractTest" -q --console=plain
   ```
3. **Tier 2 — In-Browser Headless ES6 Unit Tests (< 3s)**:
   - Use [`UiModuleUnitTest`](src/test/java/com/github/mattiasgreen/wiremock/ui/UiModuleUnitTest.java) for pure JS logic (`filter.js`, `charset-codec.js`, `pseudo-json.js`, `polyglot-ast.js`, `polyglot-viewer.js`, `code-editor.js`, `document-workspace.js`, `dialog.js`, `settings.js`, `stub-catalog.js`, `request-journal.js`, `tester.js`, `queues.js`):
   ```bash
   ./gradlew :wiremock-extension-ui:test --tests "UiModuleUnitTest" -q --console=plain
   ```
4. **Tier 3 — Targeted Domain Playwright E2E Test (~4–8s)**:
   - Run only the specific domain test class and method:
   ```bash
   ./gradlew :wiremock-extension-ui:test --tests "UiStubsAndLifecyclePlaywrightTest.testInitialPageLoadAndStubsListing" -q --console=plain
   ```
   - Domain suites (inheriting from `AbstractUiPlaywrightTest` or standalone):
     - `UiStubsAndLifecyclePlaywrightTest`: Stubs catalog, boolean filter, create/edit/clone, enable/disable, bulk actions.
     - `UiJournalAndTesterPlaywrightTest`: Multi-Protocol Tester, presets/history, Request Journal filtering, resolutions/triage.
     - `UiOpenApiPlaywrightTest`: OpenAPI YAML/JSON import, live proxy recording, journal snapshot, individual stub toggle, POST example synthesis.
     - `UiPayloadEditorPlaywrightTest`: Unified Document Workspace, `PolyglotViewer`, `CodeEditor` (`highlight-api` + `legacy-duallayer`), charset round-tripping, and span-exact splicing.
     - `UiDemoModePlaywrightTest` & `UiVanillaWireMockPlaywrightTest`: Explicit Demo Mode showcase (`demo-snapshot.js`) and vanilla WireMock graceful degradation.
     - `UiPerformancePlaywrightTest`: CDP tab-switching, DOM node churn, layout reflow, heap, and long-task performance profiling.
     - `UiScenariosAndStatefulPlaywrightTest`, `UiNetEmPlaywrightTest`, `UiQueuesAndTopicsPlaywrightTest`, `UiCasualTesterPlaywrightTest`, `UiChainedWorkflowPlaywrightTest`.
   - **Opt-in CDP E2E Profiler (`UiDevToolsProfiler`)**: Pass `-Dui.profile=true` to any Playwright E2E run to capture per-test V8 `.cpuprofile` files and an aggregated `profile-summary.md` under `wiremock-extension-ui/build/reports/profiles/`.
5. **Console Proxy & Demo Snapshot Loops**:
   ```bash
   ./gradlew :wiremock-extension-ui:test --tests "WireMockConsoleProxyTest" -q --console=plain
   ./gradlew :wiremock-extension-ui:generateDemoSnapshot -q --console=plain
   ```

---

## 2. Scalable Zero-Build Vanilla ES6 Invariants

1. **Zero NPM / Zero Bundlers**: Pure ES6 modules (`import` / `export`) under `src/main/resources/ui/modules/`. Never add `package.json`, Webpack, Vite, or external CDN dependencies (must remain 100% air-gapped capable).
2. **Single Source of Truth**: Author UI files **only** in `wiremock-extension-ui/src/main/resources/ui/`. Never hand-edit `docs/site/demo/` or `modules/demo-snapshot.js` (`demo-snapshot.js` is generated via `:wiremock-extension-ui:generateDemoSnapshot`; `docs/site/demo/` is gitignored and assembled by `.github/workflows/pages.yml`).
3. **Zero Marketing Assets in Classpath**: Never place `.gif` or `.png` screenshots inside `src/main/resources/ui/` (keeps the published Maven Central JAR `< 200 KB`).
4. **Container-Level Event Delegation**: Attach delegated listeners on stable parent containers (`#journal-list`, `#queues-messages-tbody`, and inside `polyglot-viewer.js`) via `e.target.closest(...)` rather than binding per-row closures inside render loops.
5. **Mandatory XSS Escaping & Non-Blocking Feedback**:
   - Wrap all dynamic strings (`url`, `name`, headers, bodies) in `escapeHtml(...)` from `modules/dom.js`.
   - Never use blocking `window.alert()` or `window.confirm()`; use `showToast(...)` (`#toast-container`), inline button feedback (`✅ Copied!`), and Promise-based `confirmDialog(...)` (`#modal-confirm`) from `modules/dialog.js`.
6. **Pure `fetch` & Explicit Lazy Demo Mode**:
   - `modules/api.js` uses pure `fetch` with zero `XMLHttpRequest` fallback and zero silent 404/network failover to demo data.
   - `modules/demo-backend.js` is dynamically imported (`import('./demo-backend.js')`) only when Demo Mode is explicitly active (`?demo=true`, `file://`, `github.io` without `?wiremock=`, or `#btn-demo-target-host`).

---

## 3. Core DOM ID Contracts

Keep these DOM selector contracts intact across `index.html`, `modules/dom.js`, and Playwright tests:
- **Navigation & Global Chrome**: `#nav-tab-stubs`, `#nav-tab-journal`, `#nav-tab-tester`, `#nav-tab-scenarios`, `#nav-tab-netem`, `#nav-tab-queues`, `#btn-target-host`, `#target-host-label`, `#btn-open-settings`, `#demo-mode-badge`, `#toast-container`, `#modal-confirm`.
- **Stubs, Lifecycle & Bulk Actions**: `#search-box`, `#btn-filter-all`, `#btn-filter-active`, `#btn-filter-disabled`, `#chk-select-all`, `#stub-list`, `#bulk-actions-bar`, `#btn-create-stub`, `#btn-open-openapi-modal`, `#btn-test-stub`, `#btn-edit-stub`, `#btn-toggle-stub`, `#btn-view-stub-in-journal`, `#btn-stub-copy-menu`, `#stub-copy-menu` (`#btn-copy-curl`, `#btn-copy-java`, `#btn-copy-json`), `#btn-clone-stub`, `#btn-delete-stub`, `#stub-json-viewer`, `#stub-viewer-mode-toggle`, `#btn-stub-maximize`.
- **Document Workspace & Code Editor**: `#modal-document-workspace`, `#workspace-title`, `#workspace-desc-pill`, `#workspace-dirty-badge`, `#workspace-template-select`, `#btn-workspace-open-openapi`, `#workspace-view-mode-toggle`, `#btn-workspace-edit-root`, `#workspace-editor-subheader`, `#workspace-breadcrumbs`, `#workspace-lint-pill`, `#btn-workspace-format`, `#btn-workspace-minify`, `#workspace-wire-style-select`, `#workspace-engine-toggle` (`#btn-editor-engine-beta`, `#btn-editor-engine-legacy`), `#workspace-viewer-pane`, `#workspace-editor-pane`, `#workspace-code-editor-host`, `#workspace-validation-banner`, `#btn-workspace-force-apply`, `#btn-workspace-cancel`, `#btn-workspace-save`, `#btn-workspace-cancel-section`, `#btn-workspace-apply-section`.
- **Settings & Confirmation Dialogs**: `#modal-settings`, `#settings-tab-target`, `#settings-tab-headers`, `#settings-tab-exclusions`, `#settings-tab-sync`, `#settings-panel-target`, `#settings-panel-headers`, `#settings-panel-exclusions`, `#settings-panel-sync`, `#input-target-host`, `#radio-strategy-proxy`, `#radio-strategy-direct`, `#target-capabilities-section`, `#target-capabilities-status`, `#target-capabilities-list`, `#btn-save-target-host`, `#btn-reset-target-host`, `#btn-demo-target-host`, `#local-headers-tbody`, `#btn-add-local-header-row`, `#settings-excluded-paths`, `#btn-save-local-headers`, `#confirm-modal-title`, `#confirm-modal-message`, `#btn-confirm-accept`, `#btn-confirm-secondary`, `#btn-confirm-cancel`.
- **Journal**: `#journal-table`, `#journal-list`, `#journal-search`, `#filter-unmatched-only`, `#journal-auto-refresh`, `#journal-limit-input`, `#btn-journal-snapshot-filtered`, `#btn-refresh-journal`, `#btn-reset-journal`.
- **Multi-Protocol Tester**: `#tester-proto-picker` (`#btn-tester-proto-http`, `#btn-tester-proto-kafka`, `#btn-tester-proto-amqp`, `#btn-tester-proto-casual`), `#tester-quick-templates`, `#tester-bar-http` (`#tester-method`, `#tester-url`), `#tester-bar-kafka`, `#tester-bar-amqp`, `#tester-bar-casual`, `#btn-tester-send`, `#tester-headers`, `#tester-local-headers-container`, `#tester-local-headers-toggle`, `#btn-manage-local-headers`, `#tester-local-headers-chips`, `#tester-request-viewer`, `#btn-tester-edit-workspace`, `#btn-tester-share-link`, `#tester-response-body`, `#btn-tester-response-maximize`, `#tester-history-panel`.
- **Scenarios, NetEm, Queues & Import**: `#scenarios-container`, `#btn-refresh-scenarios`, `#btn-reset-scenarios`, `#netem-toxics-list`, `#netem-add-toxic-modal`, `#netem-journal-table`, `#sidebar-queues`, `#queues-destination-list`, `#queues-messages-tbody`, `#modal-queues-brokers`, `#openapi-modal`, `#openapi-spec-viewer`, `#btn-openapi-edit-workspace`, `#bundle-content-viewer`, `#btn-bundle-edit-workspace`.

> **UX & Visual Changes**: Activate the `ux-audit-and-snapshots` skill whenever adding or modifying UI views or workflows.

---

## 4. Deepened Architecture Topology

1. **Frontend Modules (`src/main/resources/ui/modules/`)**:
   - **Polyglot AST, Viewer & Code Editor Stack**:
     - `modules/charset-codec.js`: Charset normalization/detection (`UTF-8`, `ISO-8859-1`, `WINDOWS-1252`, `KOI8-R`, `US-ASCII`) and Base64 codec.
     - `modules/pseudo-json.js`: Hardened Java `Map.toString()` (`{k=v}`) recursive-descent parser & formatter preserving KV notation.
     - `modules/highlighter.js`: Syntax format detection, validation, HTML highlighters, formatters, XML minifier, and character-offset tokenizer (`tokenizeOffsets`).
     - `modules/polyglot-ast.js`: Exact `[start, end]` span-tracking AST discovery (`discoverEmbeddedPayloads`, `buildPolyglotSegments`) and nested splice engine (`applyNestedPathEdit`, `spliceEmbeddedPayloadEdit`), preserving compact vs. multi-line wire style without duplicate-sibling or placeholder collisions.
     - `modules/polyglot-viewer.js`: Foldable read-only/editable tree renderer (`mountPolyglotViewer`, `renderPolyglotTreeHtml`) with container-level click delegation and zero disabled Edit buttons.
     - `modules/code-editor.js`: Hot-swappable editor adapter (`mountCodeEditor`) defaulting in-memory to `<pre contenteditable="plaintext-only">` + browser-native `CSS.highlights` (`highlight-api`), with instant fallback to the dual-layer `<textarea>` engine (`legacy-duallayer`).
     - `modules/document-workspace.js`: Fullscreen `#modal-document-workspace` controller orchestrating viewer-first `DOCUMENT_VIEWER` <-> focused `SECTION_EDITOR` transitions, breadcrumbs, and unsaved-change state.
   - **Stubs, Lifecycle, Journal, Tester & Settings**:
     - `modules/stub-catalog.js` & `modules/stubs.js`: Canonical Stub Catalog partitioning native WireMock mappings into active (`priority < 100`), `offline-guard` (`priority 50`, `metadata.role === "offline-guard"`), and parked (`priority >= 100`) tiers, `#btn-stub-copy-menu`, inline Duplicate/Delete actions, and `PolyglotViewer` detail rendering.
     - `modules/lifecycle.js` & `modules/bulk-actions.js`: Native priority-tier stub parking (`/__admin/mappings/{id}`), Document Workspace entry points (`openStubEditor`, `openStubEditorFromRequest`), and `getVisibleSelectedStubIds`-scoped bulk actions.
     - `modules/journal.js` & `modules/request-journal.js`: Request journal table, source caller IP/host filtering, `PolyglotViewer` drawers, lazy 50-row rendering, and `initJournalTab`.
     - `modules/tester.js` & `modules/dispatcher.js`: `PROTOCOL_ADAPTERS`-driven Multi-Protocol Tester (`http`, `kafka`, `amqp`, `casual`) sharing `#tester-headers` (with per-protocol drafts), `#btn-tester-send`, `PolyglotViewer` + Document Workspace, on-click `#btn-tester-share-link`, and execution history.
     - `modules/dialog.js`: Non-blocking `#toast-container` (`showToast`) and Promise-based `#modal-confirm` (`confirmDialog`).
     - `modules/settings.js`: Unified 4-tab `#modal-settings` controller (Target Host, Persistent Headers, Snapshot Exclusions, Clipboard Config Sync) and `updateTargetHostDisplay`.
     - `modules/api.js`, `modules/demo-backend.js` & `modules/demo-snapshot.js`: Pure `fetch` client dynamically importing `demo-backend.js` over live-generated `DEMO_SNAPSHOT` only on explicit Demo Mode.
     - `modules/queues.js`, `modules/scenarios.js`, `modules/netem.js`, `modules/openapi.js`, `modules/import-export.js`, `modules/capabilities.js`, `modules/app-loader.js`, `modules/router.js`, `modules/state.js`, `modules/dom.js`, `modules/filter.js`, `modules/base64.js`: Feature workspaces (keeping `#nav-tab-netem` and `#nav-tab-queues` visible on vanilla WireMock), capability probing, and core state/filter helpers.
     - `app.js`: Slim composition root registering route handlers, invoking module initializers, and exposing unit-test exports.

2. **Backend & Static Asset Services (`src/main/java/.../ui/`)**:
   - `StubLifecycleService` & `StubLifecycleAdminEndpoint`: Stateless priority-tier lifecycle coordinator exposing `/__admin/lifecycle/{status,snapshots,snapshot-template}` (individual and bulk enable/disable use WireMock's native `/__admin/mappings/{id}`).
   - `UiAssetLoader` & `UiAdminApiEndpoint`: Serves static console assets, `/__admin/ui/version`, and enriched `/__admin/ui/api/requests` (`ClientSourceResolver`, `JournalFilterService`, `JournalQueryMatcher`).
   - `WireMockConsoleProxy`: Self-contained zero-dependency JDK 21 reverse proxy & static asset server for JEP 330 / `wm proxy` / `:wiremock-extension-ui:runProxy`.

3. **Test Harness & Seams (`src/test/java/.../ui/`)**:
   - `AbstractUiPlaywrightTest` & `UiDevToolsProfiler`: Shared headless Chromium instance, `UiDriver` domain seam, and opt-in CDP profiler (`-Dui.profile=true` -> `wiremock-extension-ui/build/reports/profiles/*.cpuprofile` + `profile-summary.md`).
   - `UiPerformancePlaywrightTest`: Dedicated CDP performance test asserting bounded DOM churn, layout reflows, and long tasks across SPA tab transitions.
   - `UiAssetAndContractTest`: Verifies static asset paths, relative ES6 imports (no circular imports involving `stubs.js`), and DOM ID contracts.
   - `UiModuleUnitTest`: In-browser headless unit test suite verifying ES6 modules directly.
   - `DemoSnapshotGenerator` & `DemoSnapshotContractTest`: Boots an in-process `WireMockServer` to generate `modules/demo-snapshot.js` (`:wiremock-extension-ui:generateDemoSnapshot`) and verifies schema fidelity.
   - `WireMockConsoleProxyTest`: Verifies standalone reverse proxy routing, CORS headers, and static asset serving.
