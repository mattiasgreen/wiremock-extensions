# AGENTS.md: Developer & Coding Agent Guidelines

Global guidelines, architecture topology, and workflows for autonomous coding agents and contributors working on `wiremock-extensions`. Module-specific instructions live in each subproject's `AGENTS.md`, and detailed procedural runbooks live in `.agents/skills/`.

---

## 1. Core Architecture & Subproject Topology

`wiremock-extensions` is a modular suite of production extensions for WireMock Standalone (JDK 21).

| Subproject | Responsibility | Primary Extension Points | Scoped Guide |
| :--- | :--- | :--- | :--- |
| **`wiremock-extension-json-logging`** | Single-line JSON traffic logging with corporate header MDC propagation (`x-correlation-id`, `x-tenant-id`). | `RequestFilterV2` (`MdcRequestFilter`), `ServeEventListener` (`JsonLoggingListener`) | Root rules |
| **`wiremock-extension-otel`** | In-process OpenTelemetry metrics & W3C trace spans + Prometheus scrape route (`/__admin/metrics/prometheus`). | `ServeEventListener` (`OtelMetricsListener`), `AdminApiExtension` (`PrometheusAdminEndpoint`) | Root rules |
| **`wiremock-extension-openapi`** | OpenAPI 3.0/3.1 YAML/JSON ingestion, request/response example synthesis, and live proxy route generation. | `AdminApiExtension` (`OpenApiAdminEndpoint`) | [`openapi/AGENTS.md`](wiremock-extension-openapi/AGENTS.md) |
| **`wiremock-extension-stateful`** | In-memory dynamic stateful API simulation with AST evaluation and multi-tenant isolation. | `AdminApiExtension` (`StatefulAdminEndpoint`), `ResponseTransformerV2` | [`stateful/AGENTS.md`](wiremock-extension-stateful/AGENTS.md) |
| **`wiremock-extension-tcp-netem`** | In-process Java 21 virtual-thread L4 TCP network emulation, Toxiproxy v2 REST API, and L7 chaos interceptor. | `AdminApiExtension` (`TcpNetEmAdminEndpoint`), `ServeEventListener` (`NetEmStubListener`) | [`tcp-netem/AGENTS.md`](wiremock-extension-tcp-netem/AGENTS.md) |
| **`wiremock-extension-casual`** | In-process Netty XATMI / Casual protocol TCP gateway, L7 proxy recording, and 2PC/XA transaction participant/forwarding. | `CasualWireMockGateway`, `se.laz.casual.*` standalone client/server | [`casual/AGENTS.md`](wiremock-extension-casual/AGENTS.md) |
| **`wiremock-extension-ui`** | Embedded Vanilla ES6 Web SPA at `/__admin/ui/`, stub lifecycle store, and headless `WireMockConsoleProxy`. | `AdminApiExtension` (`UiAdminApiEndpoint`, `StubLifecycleAdminEndpoint`) | [`ui/AGENTS.md`](wiremock-extension-ui/AGENTS.md) |
| **`wiremock-extension-bundle`** | Meta-jar aggregating extensions with SPI auto-discovery (`META-INF/services/...ExtensionFactory`) + `StandaloneDevServer`. | Meta-packaging & standalone JavaExec runner | Root rules |
| **`wiremock-examples`** | Case Management reference implementation modeling stateful FSM scenarios and parallel isolation tests. | Reference implementation & integration test suite | Root rules |

---

## 2. Inviolable Constraints

1. **Zero NPM / Zero JS Bundlers (`wiremock-extension-ui`)**:
   - Pure Vanilla ES6 modules (`import` / `export`), HTML, and CSS. **NEVER** introduce `package.json`, `npm`, `yarn`, `pnpm`, Webpack, Vite, or external CDN links. See [`wiremock-extension-ui/AGENTS.md`](wiremock-extension-ui/AGENTS.md).
2. **Terse Documentation & Token Hygiene**:
   - Keep comments concise and focused on contracts, input/output types, DOM IDs, and Admin API routes.
   - **NEVER view or hand-edit `CHANGELOG.md`** (managed automatically by `release-please`).
   - Always pass `-q --console=plain` to `./gradlew` commands during inner-loop work to minimize console log token bloat.
3. **Formatting & Static Security Gate (Spotless + SpotBugs + FindSecBugs)**:
   - Enforced across all subprojects via `./gradlew spotlessApply` and `./gradlew spotbugsMain` (`ignoreFailures = false`).
   - Always use explicit `Locale.ROOT` on case conversions (`toLowerCase(Locale.ROOT)`).
   - Always sanitize potential CRLF (`\r`, `\n`) characters before logging dynamic inputs.
   - Always load classpath resources via static class literals (`MyClass.class.getResourceAsStream(...)`), never `getClass()`.
4. **Strict Zero-Credential-Inspection & No-Circumvention Policy**:
   - **NEVER** extract, dump, or query credentials, tokens, passwords, or secrets (`git credential fill`, OS keychains, `~/.ssh`, `~/.git-credentials`, `~/.netrc`, `~/.aws`, `~/.config/gh`, or environment secret dumps).
   - If a remote resource returns `401` or `403`, reproduce locally or ask the user for the log snippet.

---

## 3. Fast Inner-Loop vs. Outer-Loop Workflow

- **Never run full multi-module `./gradlew check` or unfiltered E2E suites during active code edits.**
- **Inner Loop (Sub-5s to 10s)**:
  - Backend / Java / Casual: `./gradlew :<module>:test --tests "ClassName.methodName" -q --console=plain`
  - UI Static Contract & ES6 Unit Tests: `./gradlew :wiremock-extension-ui:test --tests "UiAssetAndContractTest" --tests "UiModuleUnitTest" -q --console=plain`
  - UI Targeted Playwright Scenario: `./gradlew :wiremock-extension-ui:test --tests "Ui*PlaywrightTest.testMethodName" -q --console=plain`
  - Console Proxy: `./gradlew :wiremock-extension-ui:test --tests "WireMockConsoleProxyTest.methodName" -q --console=plain`
- **Outer Loop Gate (Pre-Commit & Pre-PR — Mandatory)**:
  ```bash
  ./gradlew spotlessApply check -q --console=plain
  ```

---

## 4. Subagent Delegation & On-Demand Skills

- **Token-Smart Subagent Usage**:
  - Delegate broad codebase searches or inspection of large test/HTML files (>30 KB) to the `research` subagent (`Model: "flash"`) so raw file dumps do not bloat the main planner context.
  - Use concurrent `self` subagents for independent parallel tasks (e.g., updating `docs/spec/` documentation while running module verification tests).
- **On-Demand Workspace Skills (`.agents/skills/`)**:
  - **`ux-audit-and-snapshots`**: Activate when designing, modifying, or reviewing UI features (5-Phase Senior Loop, `captureUiSnapshots`, multimodal inspection, and 5 Lenses UX review).
  - **`pr-and-release-prep`**: Activate before committing, creating a PR, or checking `release-please` / Maven Central readiness.
  - **`demo-video-recording`**: Activate when recording animated GIFs, pitch deck screenshots, or Playwright WebM video tours.
  - **`casual-upstream-sync`**: Activate when modifying `se.laz.casual.*` protocol classes in `wiremock-extension-casual`.
