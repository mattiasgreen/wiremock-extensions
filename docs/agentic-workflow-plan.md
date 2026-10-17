# Agentic Workflow Efficiency Review, Plan & Benchmark Results

This document captures the critical review of `wiremock-extensions` across `main` and active feature branches (`wiremock_ui_ux_audit`, `feat/casual-protocol-wiremock`, `roadmap`), the execution plan, and the empirical before/after benchmark results.

---

## Part 1: Architectural Trajectory & Critical Review

### 1.1 Where We Are Heading (Main + Parallel Feature Branches)
- **`main` (`7ecf9ff`)**: Includes 9 subprojects (`json-logging`, `otel`, `openapi`, `stateful`, `tcp-netem`, `casual`, `ui`, `bundle`, `examples`), in-browser headless ES module unit tests (`UiModuleUnitTest`), high-volume journal lazy rendering, deep boolean search, and the in-process Netty XATMI/Casual gateway (`wiremock-extension-casual`) with a 3-node Quarkus 2PC/MitM integration test suite (`quarkusTest`).
- **`wiremock_ui_ux_audit` (`fae6d07` - incoming)**: Introduces the Progressive Simulation Lifecycle (Ad-hoc $\rightarrow$ Traffic Harvesting $\rightarrow$ SDK Synthesis), 4-verdict journal taxonomy (`STUB`, `PROXIED`, `UNMATCHED`, `CHAOS`), `syncDemoSite` asset deduplication, and architectural guardrails for scaling the zero-build Vanilla ES6 UI beyond 8,000 LOC.
- **Roadmap Horizon (`TODO.md`)**: Automated WebM video tours via GitHub Release assets, AsyncAPI/WebSockets (`wiremock-extension-asyncapi`), Webhook Outbox (`wiremock-extension-webhooks`), and OpenAPI schema validation.

### 1.2 Root-Cause Bottlenecks Discovered During Baseline Benchmarking
1. **95 MB of Hidden Logback `DEBUG` Output per Test Suite Run**:
   - Only `wiremock-extension-json-logging` had a `src/test/resources/logback-test.xml`. All 8 other modules defaulted Logback to `<root level="DEBUG">`, causing Jetty and Netty to format and stream **305,582 lines (66.96 MB)** of `DEBUG` logs during `UiPlaywrightTest` alone (~95 MB of XML + ~95 MB of HTML reports = **~190 MB of disk I/O per run**).
2. **`UiSnapshotCaptureTest` (`@Tag("ui-snapshot")`) Running Inside Standard `./gradlew test`**:
   - Root `build.gradle` only excluded `'recording', 'pitch-deck'`, so every `./gradlew test` run also launched an extra Playwright browser and captured 8 full-page PNG screenshots.
3. **Flaky Race Condition & Missing Store Reset in `UiPlaywrightTest`**:
   - `DisabledStubStore.clear()` was never called in `resetDefaultStubs()`, and `testOpenApiPostE2EWorkflow` waited on `#stubs-count-label` (present statically in `index.html`) instead of `#stub-list[data-state='ready']`, causing intermittent `expected: 2, but was: 8` failures.
4. **Stale DOM ID Lookup in `modules/dom.js`**:
   - Our new static contract check in `UiAssetAndContractTest` (`testStaticEsModuleImportsAndDomSelectorContracts`, running in `62ms`) immediately caught a stale `document.getElementById('btn-refresh')` reference in `modules/dom.js` (`btn-refresh-stubs` in `index.html`).
5. **Monolithic Root `AGENTS.md` & 94 KB `UiPlaywrightTest.java`**:
   - Root `AGENTS.md` (212 lines) was loaded unconditionally on every turn without module-scoped `AGENTS.md` files or on-demand `.agents/skills/` runbooks.
   - `UiPlaywrightTest.java` had grown to **93.7 KB (1,890 lines)** (~22,000 tokens per full file read).

---

## Part 2: Implemented Optimizations & Customizations

1. **Gradle Parallelism, Build Cache, C1 Test JIT & Default `WARN` Test Logging**:
   - Enabled `org.gradle.parallel=true`, `org.gradle.caching=true`, `org.gradle.vfs.watch=true`, and `-Xmx2048m -XX:+UseParallelGC` in [`gradle.properties`](../gradle.properties).
   - Added [`config/logback-test.xml`](../config/logback-test.xml) (`<root level="WARN"/>`) wired automatically in [`build.gradle`](../build.gradle) for subprojects without a custom `logback-test.xml`, plus `-XX:TieredStopAtLevel=1` on `Test` tasks and `'ui-snapshot'` tag exclusion.
   - Replaced the hardcoded `nmcpAggregation` dependency list in [`build.gradle`](../build.gradle) with dynamic discovery across all published subprojects (`subprojects.findAll { it.name != 'wiremock-examples' }`).
2. **Playwright Suite Decomposition & Shared Chromium Browser (`AbstractUiPlaywrightTest`)**:
   - Split the 93.7 KB `UiPlaywrightTest.java` into [`AbstractUiPlaywrightTest`](../wiremock-extension-ui/src/test/java/com/github/mattiasgreen/wiremock/ui/AbstractUiPlaywrightTest.java) (8.3 KB) and 4 domain suites (`17.6 KB`–`19.9 KB` each):
     - [`UiStubsAndLifecyclePlaywrightTest`](../wiremock-extension-ui/src/test/java/com/github/mattiasgreen/wiremock/ui/UiStubsAndLifecyclePlaywrightTest.java)
     - [`UiJournalAndTesterPlaywrightTest`](../wiremock-extension-ui/src/test/java/com/github/mattiasgreen/wiremock/ui/UiJournalAndTesterPlaywrightTest.java)
     - [`UiOpenApiPlaywrightTest`](../wiremock-extension-ui/src/test/java/com/github/mattiasgreen/wiremock/ui/UiOpenApiPlaywrightTest.java)
     - [`UiScenariosAndStatefulPlaywrightTest`](../wiremock-extension-ui/src/test/java/com/github/mattiasgreen/wiremock/ui/UiScenariosAndStatefulPlaywrightTest.java)
   - Shared a single JVM-wide Chromium `Browser` instance across all 4 domain suites, `UiModuleUnitTest`, and `UiNetEmPlaywrightTest`.
3. **Static ES6 Module & DOM ID Contract Gate (< 0.3s)**:
   - Added `testStaticEsModuleImportsAndDomSelectorContracts()` to [`UiAssetAndContractTest`](../wiremock-extension-ui/src/test/java/com/github/mattiasgreen/wiremock/ui/UiAssetAndContractTest.java) verifying that all relative ES6 `import` specifiers resolve on disk, no external CDN scripts exist, and all `getElementById` calls in `modules/dom.js` match `index.html`.
4. **Hierarchical `AGENTS.md`, On-Demand `.agents/skills/`, & `.agents/hooks.json`**:
   - Slimmed root [`AGENTS.md`](../AGENTS.md) to 72 lines (~950 tokens, covering all 9 modules).
   - Created scoped `AGENTS.md` guides in `wiremock-extension-ui`, `wiremock-extension-casual`, `wiremock-extension-tcp-netem`, `wiremock-extension-stateful`, and `wiremock-extension-openapi`.
   - Extracted 4 progressive-disclosure workspace skills under `.agents/skills/`: `ux-audit-and-snapshots`, `pr-and-release-prep`, `demo-video-recording`, and `casual-upstream-sync`.
   - Added configurable `PreToolUse` credential guard hook in [`.agents/hooks.json`](../.agents/hooks.json) and [`.agents/scripts/pre-command-guard.ps1`](../.agents/scripts/pre-command-guard.ps1).

---

## Part 3: Empirical Before vs. After Benchmark Evaluation

### 3.1 Inner & Outer Loop Wall-Clock Speedups

| Loop ID | Loop Description | Baseline (Before) | Optimized (After) | Improvement |
| :--- | :--- | :--- | :--- | :--- |
| **L1-Backend-Warm** | Single backend test class (`OpenApiAdminEndpointTest --rerun`) | `17.63s` (20 lines) | **`6.41s`** (0 lines) | **2.75x faster (-63.6%)** |
| **L1-Casual-Warm** | Single Casual TCP gateway test (`CasualWireMockGatewayTest --rerun`) | `13.59s` (19 lines) | **`9.68s`** (0 lines) | **1.40x faster (-28.8%)** |
| **L1-UI-Contract** | UI static ES6 import + DOM ID + HTTP asset contract (`UiAssetAndContractTest`) | `N/A` (3 server restarts) | **`6.39s`–`10.03s`** (`0.26s` in-JVM) | **Catches JS/DOM bugs in < 0.3s in-JVM** |
| **L1-UI-Unit-Warm** | Headless ES6 module unit harness (`UiModuleUnitTest --rerun`) | `19.78s` (31 lines) | **`12.21s`** (0 lines) | **1.62x faster (-38.3%)** |
| **L2-UI-E2E-Warm** | Single Playwright E2E scenario (`--tests *.testInitialPageLoadAndStubsListing --rerun`) | `15.61s` (31 lines) | **`12.04s`** (0 lines) | **1.30x faster (-22.9%)** |
| **L3-UI-Suite** | Full UI test suite (`:wiremock-extension-ui:test --rerun`, 40+ tests) | `100.70s` (Failed: race) | **`47.91s`** (100% pass) | **2.10x faster (-52.4%)** |
| **Outer-Gate-Full** | Full non-cached multi-module gate (`spotlessCheck check --rerun-tasks`) | `200.28s` (1,662 lines) | **`122.89s`** (0 lines) | **1.63x faster (-77.4s saved)** |
| **Outer-Gate-Incr** | Cached / incremental pre-PR gate (`spotlessCheck check`) | `3.61s` (161 lines) | **`2.36s`** (0 lines) | **1.53x faster (-34.6%)** |

### 3.2 Token Budget & Disk I/O Footprint Reductions

| Metric | Baseline (Before) | Optimized (After) | Reduction |
| :--- | :--- | :--- | :--- |
| **Always-On Root `AGENTS.md` Size** | 212 lines (~2,600 tokens) | **72 lines (~950 tokens)** | **-63% always-on rule tokens** |
| **Largest Playwright Test File Size** | `93.7 KB` (`1,890` lines, ~22,000 tokens) | **`17.6 KB`–`19.9 KB`** (~4,500 tokens/domain suite) | **-79% tokens per test file read** |
| **Total JUnit XML Report Footprint (All Modules)** | `~95,000 KB` (`95.0 MB`,incl. `66.96 MB` in UI) | **`54.51 KB` (`0.053 MB`)** | **-99.94% (~1,750x smaller)** |
| **Outer-Gate Console Output Lines** | `1,662 lines` (~12,000 tokens) | **`0 lines`** (with `-q --console=plain` on success) | **-100% console noise on pass** |
