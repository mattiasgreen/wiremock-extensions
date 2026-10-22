# Platform Bindings & Concrete Component Mappings

> **Status**: Technical Implementation Binding
> **Complements**: `docs/spec/functional-specification.md` and `docs/spec/ui-guidelines.md`
> **Audience**: Frontend Developers, Test Automation Engineers, Coding Agents

---

## 1. Concrete Identifier Mapping

| Abstract Semantic Component | Web Selector (HTML / CSS) | Desktop Swing Name (`component.setName`) | Target Capability / Role |
| :--- | :--- | :--- | :--- |
| **Server Connection URL** | `#server-url` | `"server-url"` | Input field for WireMock base URL (`http://localhost:8080`). |
| **Connect / Refresh Trigger**| `#btn-connect` | `"btn-connect"` | Trigger to refresh all datasets. |
| **Navigation Tab: Stubs** | `#nav-tab-stubs` | `"nav-tab-stubs"` / tab 0 | Switch to Stubs Catalog. |
| **Navigation Tab: Journal** | `#nav-tab-journal` | `"nav-tab-journal"` / tab 1 | Switch to Traffic Journal. |
| **Navigation Tab: Tester** | `#nav-tab-tester` | `"nav-tab-tester"` / tab 2 | Switch to HTTP Request Tester. |
| **Navigation Tab: Scenarios**| `#nav-tab-scenarios` | `"nav-tab-scenarios"` / tab 3 | Switch to FSM Scenarios Inspector. |
| **Stubs Filter Input** | `#search-box` | `"search-box"` | Case-insensitive text filter. |
| **Stubs Status Filter** | `#status-filter` | `"status-filter"` | Status filter dropdown (`All`, `Active`, `Disabled`). |
| **Stubs Table / Grid** | `#stub-table` | `"stub-table"` | Table view with columns: Status, Method, URL, Name. |
| **Stub Details Panel** | `#stub-details` | `"stub-details"` | Formatted JSON editor/viewer of selected stub mapping. |
| **Stub Toggle Action** | `#btn-toggle-stub` | `"btn-toggle-stub"` | Toggles active/disabled state without deletion. |
| **Stub Delete Action** | `#btn-delete-stub` | `"btn-delete-stub"` | Deletes selected stub mapping. |
| **Stub Test in Tester** | `#btn-test-stub` | `"btn-test-stub"` | Pre-populates tester with stub method & URL. |
| **Stub Copy cURL** | `#btn-copy-curl` | `"btn-copy-curl"` | Copies valid `curl` command to system clipboard to create this stub mapping via `POST /__admin/mappings` using multi-line JSON with `<< 'EOF'`. |
| **Stub Copy Java** | `#btn-copy-java` | `"btn-copy-java"` | Copies WireMock Java `stubFor(...)` DSL snippet to system clipboard. |
| **Stub Copy JSON** | `#btn-copy-json` | `"btn-copy-json"` | Copies raw mapping JSON to system clipboard. |
| **Stub Editor Proxy URL** | `#editor-proxy-url` | `"editor-proxy-url"` | Optional `proxyBaseUrl` upstream target input in Stub Editor modal. |
| **Journal Table** | `#journal-table` | `"journal-table"` | Table view of served requests with timestamp, method, url, status, resolution/verdict. |
| **Journal Verdict Pills** | `#btn-journal-verdict-all`, `-proxied`, `-stub`, `-stateful`, `-unmatched` | `"journal-verdict-filter"` | Filter pills for the 4 core verdicts (`🌐 PROXIED`, `📌 STUB`, `🧠 STATEFUL`, `⚠️ UNMATCHED`). |
| **Journal Auto-Refresh** | `#journal-auto-refresh` | `"journal-auto-refresh"` | Polling toggle checkbox (3-second interval). |
| **Journal Limit Input** | `#journal-limit-input` | `"journal-limit-input"` | Configurable volume limit number input (positive integer, default 100). |
| **Journal Clear Action** | `#btn-clear-journal` | `"btn-clear-journal"` | Clears recorded requests via `DELETE /__admin/requests`. |
| **Journal Resolution Badge** | `.pill-matched`, `.pill-stub`, `.pill-stubbed`, `.pill-proxied`, `.pill-stateful`, `.pill-unmatched` | `"journal-resolution"` | Verdict badge (`🌐 PROXIED`, `📌 STUB` / `TPL`, `🧠 STATEFUL`, `⚠️ UNMATCHED`). |
| **Journal Replay Action** | `.btn-journal-to-tester` | `"btn-journal-to-tester"` | Pre-populates tester with captured request data. |
| **Journal Copy cURL** | `.btn-journal-copy-curl` | `"btn-journal-copy-curl"` | Copies captured request as `curl` to system clipboard. |
| **Journal Copy Java** | `.btn-journal-copy-java` | `"btn-journal-copy-java"` | Copies captured request/response pair as WireMock Java `stubFor(...)` snippet. |
| **Journal Create Stub** | `.btn-create-stub-from-req` | `"btn-create-stub-from-req"` | Opens Stub Editor pre-populated from an `⚠️ UNMATCHED` request. |
| **Tester Method Selector** | `#tester-method` | `"tester-method"` | HTTP method dropdown (GET, POST, PUT, DELETE, PATCH). |
| **Tester URL Path** | `#tester-url` | `"tester-url"` | URL path to dispatch against WireMock. |
| **Tester Headers Editor** | `#tester-headers` | `"tester-headers"` | Multiline header editor (`Key: Value`). |
| **Tester Body Editor** | `#tester-request-body` | `"tester-request-body"` | Request payload text area. |
| **Tester Send Action** | `#tester-send-btn` | `"tester-send-btn"` | Dispatches request asynchronously. |
| **Tester Status Result** | `#tester-response-status`| `"tester-response-status"`| Displays HTTP response status and latency in ms. |
| **Tester Response Body** | `#tester-response-body` | `"tester-response-body"` | Formatted response body viewer. |
| **Scenarios Reset Action**| `#btn-reset-scenarios` | `"btn-reset-scenarios"` | Sends `POST /__admin/scenarios/reset`. |
| **Navigation Tab: NetEm** | `#nav-tab-netem` | `"nav-tab-netem"` / tab 4 | Switch to L4 TCP Network Emulator & Chaos console. |
| **NetEm Ingress Info** | `#netem-ingress-info` | `"netem-ingress-info"` | Displays active proxy name, listen port, and target port. |
| **NetEm Active Toxics Stat** | `#netem-stat-active-toxics` | `"netem-stat-active-toxics"` | Metric counter for active toxic impairments. |
| **NetEm Preset: 3G** | `#btn-preset-3g` | `"btn-preset-3g"` | Applies 3G Mobile preset (300ms latency, 64KB/s bandwidth). |
| **NetEm Reset All** | `#btn-netem-reset-all` | `"btn-netem-reset-all"` | Clears all registered toxics on the active proxy. |
| **NetEm Add Toxic Modal** | `#netem-add-toxic-modal` | `"netem-add-toxic-modal"` | Modal dialog for creating customized toxic impairments. |
| **NetEm Pattern Mode**    | `#netem-pattern-type-ascii` / `#netem-pattern-type-hex` | `"netem-pattern-type"` | Toggle between ASCII and Hex byte stream pattern modes. |
| **NetEm Toxic Pattern**   | `#netem-toxic-pattern` | `"netem-toxic-pattern"` | Stream substring or raw Hex sequence pattern filter. |
| **NetEm Toxic Client IP** | `#netem-toxic-client-ip`| `"netem-toxic-client-ip"`| Client socket IP or CIDR block filter (e.g. `10.0.0.0/8`). |
| **NetEm Journal Table**   | `#netem-journal-table` | `"netem-journal-table"` | Table view of recorded network transfers and impairments. |
| **NetEm Journal Filter**  | `#netem-journal-filter-corr` | `"netem-journal-filter-corr"` | Scope / target pattern search filter for the NetEm journal. |
| **Target Host Trigger** | `#btn-target-host` | `"btn-target-host"` | Button opening Target Server configuration modal. |
| **Target Host Label** | `#target-host-label` | `"target-host-label"` | Displays active remote target origin and proxy strategy. |
| **Target Host Modal** | `#target-host-modal` | `"target-host-modal"` | Modal dialog for remote WireMock base URL and strategy. |
| **Target Host Input** | `#input-target-host` | `"input-target-host"` | Input for remote base URL and path prefix. |
| **Strategy Proxy Radio**| `#radio-strategy-proxy` | `"radio-strategy-proxy"` | Proxy via standard gateway headers (`X-Forwarded-*`). |
| **Strategy Direct Radio**| `#radio-strategy-direct`| `"radio-strategy-direct"`| Cross-origin direct browser requests. |
| **Reset Target Trigger**| `#btn-reset-target-host`| `"btn-reset-target-host"`| Resets target to same-origin and clears query params. |
| **Capabilities Section** | `#target-capabilities-section` | `"target-capabilities-section"` | Container displaying detected extension matrix. |
| **Capabilities Status**  | `#target-capabilities-status`  | `"target-capabilities-status"`  | Active count badge (e.g. `All 5 active` or `1/5 (Degraded)`). |
| **Capabilities List**    | `#target-capabilities-list`    | `"target-capabilities-list"`    | Capability rows with `data-testid="cap-badge-{key}"`. |
| **Site Header / Tab Title** | `document.title` / `<title>` | Window / Frame Title | Browser tab display identifying the active WireMock URI: `WMC - <wiremock-uri>`. |
| **Project Lifecycle Bar** | `#project-lifecycle-bar` | `"project-lifecycle-bar"` | Active project lifecycle toolbar displayed when proxy stubs exist. |
| **Project Mode Badge** | `#project-mode-badge` | `"project-mode-badge"` | Indicates active simulation mode (`LIVE PROXY` or `STUBS MODE`). |
| **Project Mode Toggle** | `#btn-project-mode-toggle` | `"btn-project-mode-toggle"` | Toggles service/all between live proxy and recorded offline stubs. |
| **Project Snapshot** | `#btn-project-snapshot` | `"btn-project-snapshot"` | Captures live proxied traffic into disabled stubs for the project. |
| **Casual Protocol Badge** | `.http-badge.badge-CASUAL` | `"badge-casual"` | Method column pill distinguishing Casual XATMI calls (`CASUAL`). |
| **Casual Status Badge** | `.status-badge.status-casual-ok`, `.status-casual-err` | `"status-casual"` | Native Casual outcome status (`OK`, `TPESVCFAIL`, `TPENOENT`). |

---

## 2. WireMock Admin API Protocol Bindings

| Capability | HTTP Method | Route | Payload |
| :--- | :--- | :--- | :--- |
| **List Active Stubs** | `GET` | `/__admin/mappings` | None |
| **List Disabled Stubs**| `GET` | `/__admin/stubs/disabled` | None |
| **Create Stub** | `POST` | `/__admin/mappings` | Stub JSON definition |
| **Update Stub** | `PUT` | `/__admin/mappings/{id}` | Stub JSON definition |
| **Toggle Stub State** | `POST` | `/__admin/stubs/{id}/toggle` | None |
| **Delete Stub** | `DELETE` | `/__admin/mappings/{id}` | None |
| **Snapshot Project Traffic** | `POST` | `/__admin/projects/{project}/recordings/snapshot` | None |
| **Toggle Project Mode** | `POST` | `/__admin/projects/{project}/mode` | `{"mode": "stubs" \| "proxy"}` |
| **Fetch Journal** | `GET` | `/__admin/requests?limit={limit}` (or `?unmatched=true`) | None |
| **Clear Journal** | `DELETE` | `/__admin/requests` | None |
| **Fetch Scenarios** | `GET` | `/__admin/scenarios` | None |
| **Reset Scenarios** | `POST` | `/__admin/scenarios/reset` | None |
| **Ingest OpenAPI** | `POST` | `/__admin/openapi/import` | OpenAPI YAML/JSON or file path |
| **List NetEm Proxies** | `GET` | `/__admin/tcp-netem/proxies` | None |
| **Add NetEm Toxic** | `POST` | `/__admin/tcp-netem/proxies/{name}/toxics` | Toxic JSON definition |
| **Delete NetEm Toxic** | `DELETE` | `/__admin/tcp-netem/proxies/{name}/toxics/{toxic}` | None |
| **Apply NetEm Preset** | `POST` | `/__admin/tcp-netem/proxies/{name}/presets/{preset}` | None |
| **Fetch NetEm Journal**| `GET` | `/__admin/tcp-netem/proxies/{name}/journal` | None |
| **Clear NetEm Journal**| `DELETE` | `/__admin/tcp-netem/proxies/{name}/journal` | None |
| **Reset All NetEm** | `POST` | `/__admin/tcp-netem/reset` | None |
| **Probe Stateful API** | `GET` | `/__admin/stateful/models` | None |
| **Probe Otel Metrics** | `GET` | `/__admin/metrics/prometheus` | None |

---

| **Casual Service Dispatch** | `POST` | `/__casual/services/{serviceName}` | Service request payload (JSON or Base64) |
| **Casual Service Stubs** | `POST` | `/__admin/mappings` | WireMock stub matching `POST /__casual/services/{name}` |

---

## 3. Terminal CLI Platform Bindings (`wiremock-cli` / `wm`)

| Abstract Semantic Action / View | CLI Command / Invocation | Description |
| :--- | :--- | :--- |
| **Probe Instance Capabilities** | `wm info [-s <url>]` | Probes core WireMock version and extension matrix (`tcp-netem`, `stateful`, `otel`, `ui`). |
| **List & Search Stubs** | `wm stubs [expr] [--json]` | Displays formatted stubs catalog or JSON. Supports boolean expressions (`"GET and /cases"`, `"status=disabled"`). |
| **Stream Request Journal** | `wm tail [-f] [-u] [-n <N>]` | Live streams incoming requests with status badges, latency, and near-miss diagnostics on 404s. |
| **Inspect Network Toxics** | `wm toxics [--json]` | Inspects active L4 TCP proxies and toxics; outputs clean fallback notice if `tcp-netem` is not installed. |
| **Launch Console Proxy** | `wm proxy [target] [port]` | Launches headless console reverse proxy and web UI server at `http://localhost:19840/__admin/ui/`. |
