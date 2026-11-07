# 16. Zero-NPM Embedded Vanilla ES6 UI Architecture

Date: 2026-11-07

## Status

Accepted

## Context

WireMock Standalone is historically headless, managed either via curl/HTTP REST Admin APIs or file-based JSON mappings. Developers, testers, and teams running local or containerized WireMock instances lack an intuitive, visual administrative console to inspect stubs, triage unmatched requests, test endpoints, visualize scenarios, and manage simulation states.

Modern web applications often adopt complex JavaScript ecosystems: `npm`, Node.js, Webpack/Vite bundlers, Babel transpilers, and large frameworks (React, Vue, Angular). In a Java extension library packaged for Maven Central and embedded directly into standalone JVM processes, introducing Node.js and npm builds introduces severe drawbacks:
1. Massive JAR bloat (`node_modules` transitive bloat).
2. Air-gapped network failure modes in enterprise environments requiring external CDN or registry downloads.
3. Slow, brittle CI pipelines requiring multi-runtime (JDK + Node) toolchain synchronization.
4. Security vulnerabilities (daily CVE churn across npm dependencies).

## Decision

We introduce `wiremock-extension-ui`, an embedded Single-Page Application (SPA) served directly from the WireMock process at `/__admin/ui/`, adhering to a strict **Zero-NPM / Zero-Bundler / Pure Vanilla ES6** architectural standard:

### 1. Pure Native Vanilla ES6 Modules
- All UI logic is authored as native ES6 modules (`import` / `export`) under `src/main/resources/ui/modules/`.
- Browsers load ES modules natively via `<script type="module" src="app.js">`.
- Zero Node.js, zero `package.json`, zero bundlers, and zero external CDNs.
- 100% self-contained and air-gapped capable; runs completely offline without internet connectivity.

### 2. High-Density Ergonomic Dashboard
- Organized into clear functional domains:
  - **Stubs Catalog**: Real-time search, Boolean filter evaluation, lifecycle enable/disable, project segmentation, and direct JSON editing.
  - **Request Journal**: Real-time request log, unmatched request triage, diff inspection, cURL / Java `stubFor()` generation, and 1-click stub snapshotting.
  - **HTTP & Casual Testers**: Built-in request execution console with syntax-highlighted Dual-Layer editors, format detection, and history presets.
  - **OpenAPI Manager**: YAML/JSON spec ingestion and live reverse-proxy recording.
  - **Scenarios Visualizer**: Interactive DAG graph showing state transitions and FSM states.
  - **Dynamic State Simulation**: In-memory state inspection and AST rule evaluation.
  - **TCP NetEm Chaos**: L4 transport and L7 traffic impairment control.

### 3. Lightweight Maven Packaging (< 200 KB)
- The entire embedded UI (HTML, CSS, JS, SVG icons) compiles into a lightweight JAR of less than 200 KB.
- Marketing media, documentation screenshots, and demo GIF assets are strictly isolated outside the JAR classpath.

### 4. Headless `WireMockConsoleProxy`
- Provides an optional zero-dependency reverse-proxy console (`java WireMockConsoleProxy.java` or `wm proxy`) that runs standalone, bridging any remote WireMock instance to the local UI.

### 5. Multi-Tiered Verification Loop
- **Tier 0**: Zero-rebuild live disk hot-reloading for rapid UI development.
- **Tier 1**: Static asset contract & import path validation (`UiAssetAndContractTest`).
- **Tier 2**: In-browser headless ES6 module unit tests via Nashorn/Playwright (`UiModuleUnitTest`).
- **Tier 3**: End-to-end domain Playwright test suites (`Ui*PlaywrightTest`).

## Consequences

### Positive
- **Instant Developer Startup**: Works anywhere Java 21 runs without requiring Node.js, npm, or build scripts.
- **Extreme Maintainability & Zero Dependency Rot**: Immune to npm supply-chain attacks, build tool deprecations, and Node version mismatches.
- **Near-Zero Footprint**: Tiny artifact footprint (< 200 KB) adds negligible overhead to standalone WireMock distributions.

### Negative / Trade-offs
- UI features must use native Web APIs and custom lightweight components (e.g., custom Dual-Layer syntax highlighter and DAG visualizer) rather than off-the-shelf React/npm widgets.
- Cross-browser compatibility relies on modern evergreen browser standards (ES2020+).
