# Agentic Workflow Efficiency Review & Implementation Plan

This document captures the critical review of `wiremock-extensions` across `main` and active feature branches (`wiremock_ui_ux_audit`, `feat/casual-protocol-wiremock`, `roadmap`), followed by the concrete execution plan for benchmarking and optimizing our inner/outer loops, quality gateways, token budget, and hierarchical agent customizations.

---

## Part 1: Architectural Trajectory & Critical Review

### 1.1 Where We Are Heading (Main + Parallel Feature Branches)
- **`main` (`7ecf9ff`)**: Includes 9 subprojects (`json-logging`, `otel`, `openapi`, `stateful`, `tcp-netem`, `casual`, `ui`, `bundle`, `examples`), in-browser headless ES module unit tests (`UiModuleUnitTest`), high-volume journal lazy rendering, deep boolean search, and the in-process Netty XATMI/Casual gateway (`wiremock-extension-casual`) with a 3-node Quarkus 2PC/MitM integration test suite (`quarkusTest`).
- **`wiremock_ui_ux_audit` (`fae6d07` - incoming)**: Introduces the Progressive Simulation Lifecycle (Ad-hoc $\rightarrow$ Traffic Harvesting $\rightarrow$ SDK Synthesis), 4-verdict journal taxonomy (`STUB`, `PROXIED`, `UNMATCHED`, `CHAOS`), `syncDemoSite` asset deduplication, and architectural guardrails for scaling the zero-build Vanilla ES6 UI beyond 8,000 LOC.
- **Roadmap Horizon (`TODO.md`)**: Automated WebM video tours via GitHub Release assets, AsyncAPI/WebSockets (`wiremock-extension-asyncapi`), Webhook Outbox (`wiremock-extension-webhooks`), and OpenAPI schema validation.

### 1.2 Key Bottlenecks Identified
1. **Single Monolithic Root `AGENTS.md`**: Loaded unconditionally on every turn, missing `openapi` and `casual` from the architecture table, and mixing global rules with UI-specific DOM selectors and UX checklists. Zero module-scoped `AGENTS.md` files, zero `.agents/skills/` runbooks, and zero `.agents/hooks.json` guardrails.
2. **Token Sinks**:
   - `UiPlaywrightTest.java` is ~78 KB (~1,630 lines on `main`, ~1,732 lines on `wiremock_ui_ux_audit`), consuming ~20,000 tokens whenever viewed in full.
   - `CHANGELOG.md` (~25 KB) is managed by `release-please` and should never be read or hand-edited by coding agents.
   - Verbose Gradle console output adds 1,000–4,000 tokens per command without `-q --console=plain`.
3. **Inner/Outer Loop Speed**:
   - `gradle.properties` lacks `org.gradle.parallel=true`, `org.gradle.caching=true`, `org.gradle.vfs.watch=true`, and tuned `jvmargs`.
   - `UiPlaywrightTest` recreates `BrowserContext` and `Page` before every single test method and launches separate Chromium browsers across test classes.
   - `.github/workflows/ci.yml` does not cache `~/.cache/ms-playwright`, re-downloading ~150 MB of Chromium on every CI run.
4. **Release & Quality Gate Gaps**:
   - `nmcpAggregation` in root `build.gradle` uses a hardcoded subproject list that currently omits `wiremock-extension-casual`.
   - `UiAssetAndContractTest` checks HTTP serving of assets, but does not statically validate ES6 `import` specifier resolution, absence of blocking `alert()` calls, or `modules/dom.js` selector alignment with `index.html`.

---

## Part 2: Execution & Benchmarking Plan

### Workstream 1: Inner & Outer Loop Benchmarking and Build/Test Optimization

#### Step 1.1: Baseline Benchmark Matrix (Before Changes)
Measure wall-clock time (seconds) and output line volume across 6 representative developer & agent loops:

| Loop ID | Loop Category | Command |
| :--- | :--- | :--- |
| **L1-Backend** | Backend Inner Loop (Warm Daemon) | `./gradlew :wiremock-extension-openapi:test --tests "OpenApiAdminEndpointTest" --rerun-tasks` |
| **L1-Casual** | Casual TCP Inner Loop (Warm Daemon) | `./gradlew :wiremock-extension-casual:test --tests "CasualWireMockGatewayTest" --rerun-tasks` |
| **L1-UI-Unit** | UI JS Unit Inner Loop (`UiModuleUnitTest`) | `./gradlew :wiremock-extension-ui:test --tests "UiModuleUnitTest" --rerun-tasks` |
| **L2-UI-E2E-Single** | UI E2E Single Scenario (`UiPlaywrightTest`) | `./gradlew :wiremock-extension-ui:test --tests "*PlaywrightTest.testInitialPageLoadAndStubsListing" --rerun-tasks` |
| **L3-UI-Suite** | Full UI Test Suite (`:wiremock-extension-ui:test`) | `./gradlew :wiremock-extension-ui:test --rerun-tasks` |
| **Outer-Gate** | Full Pre-PR Outer Gate (`spotlessCheck` + `check`) | `./gradlew spotlessCheck check --rerun-tasks` |
| **Outer-Gate-Cached** | Incremental / Cached Outer Gate | `./gradlew spotlessCheck check` |

#### Step 1.2: Performance, Token & Quality Gateway Improvements
1. **Tune `gradle.properties`**:
   - Enable `org.gradle.parallel=true`, `org.gradle.caching=true`, `org.gradle.vfs.watch=true`, and `org.gradle.jvmargs=-Xmx2048m -XX:+UseParallelGC -Dfile.encoding=UTF-8`.
2. **Optimize Root `build.gradle` & Dynamic `nmcpAggregation`**:
   - Auto-wire `nmcpAggregation` across all published subprojects (`subprojects.matching { it.name != 'wiremock-examples' }`) so new modules like `wiremock-extension-casual` are never missed during Maven Central publishing.
   - Add fast C1 JIT compilation (`-XX:TieredStopAtLevel=1`) for short-lived test JVMs and disable redundant SpotBugs XML report serialization when HTML is already enabled.
3. **Split Monolithic `UiPlaywrightTest.java` & Reuse Shared Chromium Context**:
   - Create `AbstractUiPlaywrightTest.java` holding a JVM-shared headless Chromium `Browser` and reusable `BrowserContext`/`Page` (resetting state cleanly in `@BeforeEach` instead of destroying/recreating the browser context 24 times).
   - Split the 24 E2E scenarios into 4 domain-focused test classes (~15–20 KB each, cutting token cost per file view by ~75%):
     - `UiStubsAndLifecyclePlaywrightTest.java`
     - `UiJournalAndTesterPlaywrightTest.java`
     - `UiOpenApiPlaywrightTest.java`
     - `UiScenariosAndStatefulPlaywrightTest.java`
4. **Strengthen Frontend Static Gate in `UiAssetAndContractTest.java` (< 2s)**:
   - Verify all relative ES6 `import ... from './...'` paths in `src/main/resources/ui/**/*.js` resolve to existing files.
   - Verify all `document.getElementById('...')` selectors in `modules/dom.js` exist in `index.html`.
   - Deduplicate `docs/site/demo/` via a `syncDemoSite` task in `wiremock-extension-ui/build.gradle` (aligned with `wiremock_ui_ux_audit`).
5. **Cache Playwright Browsers in `.github/workflows/ci.yml`**:
   - Cache `~/.cache/ms-playwright` keyed by `gradle/libs.versions.toml` so CI avoids downloading Chromium on every build.

#### Step 1.3: Post-Optimization Benchmark & Delta Evaluation
Re-run the 7 benchmark loops and record wall-clock speedups, cache hit behavior, and token footprint reductions.

---

### Workstream 2: Hierarchical `AGENTS.md`, `.agents/skills/`, & `.agents/hooks.json`

#### 2.1 Target Customization Tree
```text
wiremock-extensions/
├── AGENTS.md                                      # Lean global rules (~95 lines, all 9 modules)
├── .agents/
│   ├── hooks.json                                 # PreToolUse guardrails (credential block)
│   ├── scripts/
│   │   └── pre-command-guard.ps1                  # Fast command security guard
│   └── skills/
│       ├── ux-audit-and-snapshots/
│       │   └── SKILL.md                           # 5-Phase Senior UX Loop, visual snapshots, 5 Lenses
│       ├── pr-and-release-prep/
│       │   └── SKILL.md                           # Pre-PR gate, release-please rules, PR markdown template
│       ├── demo-video-recording/
│       │   └── SKILL.md                           # Playwright GIF/WebM recording & GitHub Release assets
│       └── casual-upstream-sync/
│           └── SKILL.md                           # se.laz.casual.* isolation & upstream extraction runbook
├── wiremock-extension-ui/
│   └── AGENTS.md                                  # Scoped UI rules (4-tier inner loop, ES6 modules, DOM IDs)
├── wiremock-extension-casual/
│   └── AGENTS.md                                  # Scoped Casual/XATMI rules (Netty ByteBuf, 2PC, quarkusTest)
├── wiremock-extension-tcp-netem/
│   └── AGENTS.md                                  # Scoped NetEm rules (Virtual threads, Toxiproxy v2, dual-ingress)
├── wiremock-extension-stateful/
│   └── AGENTS.md                                  # Scoped Stateful AST & multi-tenant X-Correlation-Id rules
└── wiremock-extension-openapi/
    └── AGENTS.md                                  # Scoped OpenAPI 3.x ingestion, example synthesis, proxy rules
```
