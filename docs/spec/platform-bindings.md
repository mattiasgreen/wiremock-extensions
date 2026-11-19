# Platform Bindings & Concrete Component Mappings

> **Status**: Technical Implementation Binding
> **Complements**: `docs/spec/functional-specification.md` and `docs/spec/ui-guidelines.md`
> **Audience**: Frontend Developers, Test Automation Engineers, Coding Agents

---

## 1. Concrete Identifier Mapping

| Abstract Semantic Component | Web Selector (HTML / CSS) | Desktop Swing Name (`component.setName`) | Target Capability / Role |
| :--- | :--- | :--- | :--- |
| **Navigation Tab: Stubs** | `#nav-tab-stubs` | `"nav-tab-stubs"` / tab 0 | Switch to Stubs Catalog. |
| **Navigation Tab: Journal** | `#nav-tab-journal` | `"nav-tab-journal"` / tab 1 | Switch to Traffic Journal. |
| **Navigation Tab: Tester** | `#nav-tab-tester` | `"nav-tab-tester"` / tab 2 | Switch to Unified Multi-Protocol Tester (`http`, `kafka`, `amqp`, `casual`). |
| **Navigation Tab: Scenarios**| `#nav-tab-scenarios` | `"nav-tab-scenarios"` / tab 3 | Switch to FSM Scenarios Inspector. |
| **Navigation Tab: NetEm** | `#nav-tab-netem` | `"nav-tab-netem"` / tab 4 | Switch to L4 TCP Network Emulator & Chaos console (visible even when `.cap-disabled`). |
| **Navigation Tab: Queues** | `#nav-tab-queues` | `"nav-tab-queues"` / tab 5 | Switch to Async Destinations & Queues explorer (`metadata.async`; visible on vanilla WireMock). |
| **Stubs Filter Input** | `#search-box` | `"search-box"` | Case-insensitive free-text and boolean (`AND`, `OR`) filter. |
| **Stubs Status Filter Pills** | `#btn-filter-all`, `#btn-filter-active`, `#btn-filter-disabled` | `"status-filter"` | Lifecycle filter pills (`All`, `Active`, `Disabled` / parked). |
| **Stubs Catalog List** | `#stub-list` (`.stub-card`) | `"stub-list"` | Scrollable catalog of `.stub-card` mappings with selection checkboxes (`.stub-card-chk`). |
| **Bulk Actions Bar** | `#bulk-actions-bar` (`#btn-bulk-enable`, `-disable`, `-export`, `-delete`) | `"bulk-actions-bar"` | Scoped strictly to visible selected stubs (`getVisibleSelectedStubIds`). |
| **Stub Details Panel** | `#stub-detail-view`, `#stub-json-viewer` | `"stub-detail-view"` | Polyglot Tree Viewer (`Rich` / `Raw` via `#stub-viewer-mode-toggle`) of selected stub mapping. |
| **Stub Primary Actions** | `#btn-test-stub`, `#btn-edit-stub`, `#btn-toggle-stub`, `#btn-view-stub-in-journal` | `"stub-primary-actions"` | Test in Tester, Edit in Document Workspace, toggle active/parked tier, or filter Journal by stub. |
| **Stub Copy Dropdown Menu** | `#btn-stub-copy-menu`, `#stub-copy-menu` (`#btn-copy-curl`, `#btn-copy-java`, `#btn-copy-json`) | `"stub-copy-menu"` | Copies `POST /__admin/mappings` cURL heredoc (`<< 'EOF'`), Java `stubFor(...)` DSL, or raw JSON. |
| **Stub Duplicate & Delete Buttons** | `#btn-clone-stub`, `#btn-delete-stub` | `"btn-clone-stub"`, `"btn-delete-stub"` | Inline Stub Detail toolbar buttons: duplicates stub in Document Workspace or permanently deletes with confirmation dialog. |
| **Journal Table** | `#journal-table`, `#journal-list` | `"journal-table"` | Table view of served requests with timestamp, caller source, protocol, operation, target/url, status, resolution/verdict, duration. |
| **Journal Source Cell** | `td.journal-source-cell` | `"journal-source-cell"` | Displays resolved caller short hostname (or IP fallback when DNS fails) as a clickable source filter chip (`.btn-journal-filter-source`). |
| **Journal Source Detail** | `div.journal-source-detail` | `"journal-source-detail"` | Expanded `Source Caller` section showing resolved FQDN and effective source IP. |
| **Journal Verdict Pills** | `#btn-journal-verdict-all`, `-proxied`, `-stub`, `-unmatched` | `"journal-verdict-filter"` | Filter pills for the 3 core verdicts (`🌐 PROXIED`, `📌 STUB`, `⚠️ UNMATCHED`). |
| **Journal Protocol Pills** | `#btn-journal-proto-all`, `-http`, `-kafka`, `-amqp`, `-casual` | `"journal-protocol-filter"` | Filter pills for captured protocol traffic. |
| **Journal Auto-Refresh** | `#journal-auto-refresh` | `"journal-auto-refresh"` | Polling toggle checkbox (3-second interval). |
| **Journal Limit Input** | `#journal-limit-input` | `"journal-limit-input"` | Configurable volume limit number input (positive integer, default 100). |
| **Journal Clear Action** | `#btn-reset-journal` | `"btn-reset-journal"` | Clears recorded requests via `DELETE /__admin/requests` after `#modal-confirm`. |
| **Journal Resolution Badge** | `.pill-matched`, `.pill-stub`, `.pill-stubbed`, `.pill-proxied`, `.pill-bridged`, `.pill-unmatched`, `.pill-proxy-error` | `"journal-resolution"` | Verdict badge (`🌐 PROXIED`, `🔀 BRIDGED`, `📌 STUB` / `TPL`, `⚠️ UNMATCHED`, `⚠️ PROXY_ERROR`). |
| **Journal Replay Action** | `.btn-journal-to-tester` | `"btn-journal-to-tester"` | Pre-populates Multi-Protocol Tester with captured request data. |
| **Journal Copy cURL** | `.btn-journal-copy-curl` | `"btn-journal-copy-curl"` | Copies captured request as `curl` to system clipboard. |
| **Journal Copy Java** | `.btn-journal-copy-java` | `"btn-journal-copy-java"` | Copies captured request/response pair as WireMock Java `stubFor(...)` snippet. |
| **Journal Create Stub** | `.btn-create-stub-from-req` | `"btn-create-stub-from-req"` | Opens `#modal-document-workspace` pre-populated from an `⚠️ UNMATCHED` request. |
| **Tester Protocol Picker** | `#tester-proto-picker` (`#btn-tester-proto-http`, `-kafka`, `-amqp`, `-casual`) | `"tester-proto-picker"` | Switches active `PROTOCOL_ADAPTERS` transport in the Unified Multi-Protocol Tester. |
| **Tester Quick Templates** | `#tester-quick-templates` | `"tester-quick-templates"` | Loads curated HTTP, Kafka, AMQP, or Casual request templates. |
| **Tester HTTP Address Bar** | `#tester-bar-http` (`#tester-method`, `#tester-url`) | `"tester-bar-http"` | HTTP method selector and target path input. |
| **Tester Async / Casual Bars** | `#tester-bar-kafka`, `#tester-bar-amqp`, `#tester-bar-casual` | `"tester-proto-bars"` | Protocol-specific destination inputs (topic/key/partition, queue/corr/replyTo, host/port/service/buffer). |
| **Tester Headers Editor** | `#tester-headers` | `"tester-headers"` | Shared multiline header editor (`Key: Value`) maintaining isolated per-protocol drafts. |
| **Tester Payload Viewer** | `#tester-request-viewer`, `#btn-tester-edit-workspace` | `"tester-request-viewer"` | `PolyglotViewer` preview of request body + trigger to edit in `#modal-document-workspace`. |
| **Tester Send Action** | `#btn-tester-send` | `"btn-tester-send"` | Single shared send button adapting label/behavior to active protocol adapter. |
| **Tester Share Link** | `#btn-tester-share-link` | `"btn-tester-share-link"` | Encodes active protocol & parameters into clipboard share URL on click only. |
| **Tester Status Result** | `#tester-response-status`| `"tester-response-status"`| Displays HTTP/Casual response status and latency (`#tester-response-time`). |
| **Tester Response Body** | `#tester-response-body`, `#btn-tester-response-maximize` | `"tester-response-body"` | `PolyglotViewer` / Hex Dump / Raw response inspector with Workspace drill-down. |
| **Tester Persistent Headers Toggle** | `#tester-local-headers-toggle` | `"tester-local-headers-toggle"` | Checkbox enabling/disabling browser local persistent headers injection (HTTP only). |
| **Manage Persistent Headers Action** | `#btn-manage-local-headers`, `#btn-open-settings` | `"btn-manage-local-headers"` | Opens `#modal-settings` directly on the `Persistent Headers` tab (`#settings-tab-headers`). |
| **Persistent Headers Count** | `#tester-local-headers-count` | `"tester-local-headers-count"` | Badge showing total number of active configured local persistent headers. |
| **Persistent Headers Chips** | `#tester-local-headers-chips` | `"tester-local-headers-chips"` | Container displaying active persistent headers chips and `(overridden)` hints. |
| **Tester Tab: Sent Request** | `#btn-tester-tab-request` | `"btn-tester-tab-request"` | Tab displaying the final resolved outgoing request, headers, and `PolyglotViewer` body. |
| **Tester Tab: Response Body** | `#btn-tester-tab-response` | `"btn-tester-tab-response"` | Tab displaying the received response body and headers. |
| **Tester Sent Headers View** | `#tester-sent-headers` | `"tester-sent-headers"` | Inspection `<pre>` element containing final resolved outgoing headers. |
| **Modal: Document Workspace** | `#modal-document-workspace` | `"modal-document-workspace"` | Fullscreen viewer-first workbench (`DOCUMENT_VIEWER` <-> `SECTION_EDITOR`) for Stubs, Tester, OpenAPI, Bundles, and Queues. |
| **Workspace Mode & Panes** | `#workspace-viewer-pane`, `#workspace-editor-pane`, `#workspace-code-editor-host` | `"workspace-panes"` | Non-destructive `PolyglotViewer` tree (`#workspace-viewer-pane`) vs. focused `CodeEditor` host (`#workspace-code-editor-host`). |
| **Workspace Editor Controls** | `#btn-workspace-edit-root`, `.btn-workspace-edit-section`, `#btn-workspace-apply-section`, `#btn-workspace-cancel-section`, `#btn-workspace-save` | `"workspace-actions"` | Enter root/section editor, splice span-exact edits back to viewer, or commit root changes. |
| **Workspace Formatting & Wire Style** | `#workspace-breadcrumbs`, `#btn-workspace-format`, `#btn-workspace-minify`, `#workspace-wire-style-select`, `#workspace-validation-banner`, `#btn-workspace-force-apply` | `"workspace-toolbar"` | Ancestor breadcrumb trail, prettify/compact actions, `auto`/`compact`/`multiline` wire re-encoding, and validation diagnostics. |
| **CodeEditor Engine Toggle** | `#workspace-engine-toggle` (`#btn-editor-engine-beta`, `#btn-editor-engine-legacy`) | `"workspace-engine-toggle"` | Hot-swaps between default `highlight-api` (`<pre contenteditable="plaintext-only">` + `CSS.highlights`) and `legacy-duallayer` (`<textarea>`). |
| **Polyglot Tree Card Controls** | `.nested-payload-block`, `.btn-nested-toggle`, `.btn-nested-copy`, `.btn-nested-open-workspace`, `.btn-workspace-edit-section` | `"polyglot-tree"` | Foldable nested payload cards with single-delegated toggle, copy, and workspace/edit actions (never disabled Edit buttons). |
| **Root Document Dirty Indicator** | `#workspace-dirty-badge` | `"workspace-dirty-badge"` | Prominent `.viewport-dirty-badge` indicating uncommitted in-memory edits awaiting root Save/Discard. |
| **Top-Level Viewer Badges** | `#stub-top-pill`, `#tester-top-pill`, `#tester-request-top-pill`, `#workspace-desc-pill` | `"top-descriptor-pill"` | Format/charset/size badges (`XML · ISO-8859-1 · 0.2 KB`, `FIELDED · JSON · ISO-8859-1 · 0.4 KB`). |
| **Scenarios Inspector & Reset** | `#scenarios-container`, `#btn-refresh-scenarios`, `#btn-reset-scenarios` | `"scenarios-container"` | Live FSM DAG pipeline cards and `POST /__admin/scenarios/reset` action. |
| **NetEm Ingress & Stats** | `#netem-ingress-info`, `#netem-stat-active-toxics` | `"netem-ingress-info"` | Displays active proxy name/ports and active toxic impairment count. |
| **NetEm Preset & Reset** | `#btn-preset-3g`, `#btn-netem-reset-all` | `"btn-preset-3g"` | Applies 3G Mobile preset (300ms latency, 64KB/s) or clears all registered toxics. |
| **NetEm Add Toxic Modal** | `#netem-add-toxic-modal` | `"netem-add-toxic-modal"` | Modal dialog for creating customized toxic impairments. |
| **NetEm Pattern Mode** | `#btn-pattern-ascii`, `#btn-pattern-hex` | `"netem-pattern-mode"` | Toggle between ASCII and Hex byte stream pattern modes. |
| **NetEm Toxic Pattern & IP** | `#netem-toxic-pattern`, `#netem-toxic-client-ip` | `"netem-toxic-pattern"` | Stream substring/Hex sequence filter and client socket IP/CIDR block filter. |
| **NetEm Journal Table & Filter** | `#netem-journal-table`, `#netem-journal-filter-corr` | `"netem-journal-table"` | Table view of recorded network transfers and scope/target filter input. |
| **Modal: Unified Settings** | `#modal-settings` | `"modal-settings"` | 4-tab modal (`#settings-tab-target`, `#settings-tab-headers`, `#settings-tab-exclusions`, `#settings-tab-sync`). |
| **Settings Tabs & Panels** | `#settings-panel-target`, `#settings-panel-headers`, `#settings-panel-exclusions`, `#settings-panel-sync` | `"settings-panels"` | Target Host + Capabilities, Persistent Headers (`#local-headers-tbody`, `#btn-add-local-header-row`), Snapshot Exclusions (`#settings-excluded-paths`), Clipboard Sync (`#btn-settings-copy-config`, `#settings-import-json`). |
| **Target Host Trigger & Label** | `#btn-target-host`, `#target-host-label`, `#demo-mode-badge` | `"btn-target-host"` | Opens `#modal-settings` (`target` tab) and displays active remote target origin/strategy or Demo Mode badge. |
| **Target Host Controls** | `#input-target-host`, `#radio-strategy-proxy`, `#radio-strategy-direct`, `#btn-save-target-host`, `#btn-reset-target-host`, `#btn-demo-target-host` | `"target-host-controls"` | Remote WireMock URL, proxy vs. direct strategy, Connect, Reset to same-origin, or explicit Demo Mode activation. |
| **Capabilities Matrix** | `#target-capabilities-section`, `#target-capabilities-status`, `#target-capabilities-list` (`[data-testid="cap-badge-{key}"]`) | `"target-capabilities-section"` | Extension capability matrix (`core`, `lifecycle`, `netem`, `metrics`, `casual`, `async`, `journal`). |
| **Modal: Confirmation Dialog** | `#modal-confirm` (`#confirm-modal-title`, `#confirm-modal-message`, `#btn-confirm-accept`, `#btn-confirm-secondary`, `#btn-confirm-cancel`) | `"modal-confirm"` | Promise-based confirmation dialog (`confirmDialog`) replacing native `window.confirm()`. |
| **Toast Notifications** | `#toast-container` (`.toast-item`, `.toast-message`, `.toast-close`) | `"toast-container"` | Non-blocking notification stack (`showToast`) replacing native `window.alert()`. |
| **Site Header / Tab Title** | `document.title` / `<title>` | Window / Frame Title | Browser tab display identifying the active WireMock URI: `WMC - <wiremock-uri>`. |
| **Casual Protocol & Status Badges** | `.http-badge.badge-CASUAL`, `.stub-status-pill.status-proxy`, `.card-value.status-proxy` | `"badge-casual"` | Distinguishes Casual XATMI calls (`CASUAL` / `OK` / `TPESVCFAIL` / `TPENOENT`) and proxy routes (`PROXY`). |

---

## 2. WireMock Admin API Protocol Bindings

| Capability | HTTP Method | Route | Payload |
| :--- | :--- | :--- | :--- |
| **List Stubs (Active & Parked)** | `GET` | `/__admin/mappings` | None (priority `>= 100` denotes parked stubs; `5` active stub; `10` active proxy; `50` optional `offline-guard` catch-all) |
| **Create Stub** | `POST` | `/__admin/mappings` | Stub JSON definition |
| **Update / Park / Activate Stub** | `PUT` | `/__admin/mappings/{id}` | Stub JSON definition (sets `priority` and `metadata.previousPriority`) |
| **Probe Lifecycle Service** | `GET` | `/__admin/lifecycle/status` | None (`{"status":"UP"}`) |
| **Delete Stub** | `DELETE` | `/__admin/mappings/{id}` | None |
| **Snapshot Traffic** | `POST` | `/__admin/lifecycle/snapshots` | Filtered / project-scoped snapshot specification |
| **Synthesize Template Stub** | `POST` | `/__admin/lifecycle/snapshot-template` | Serve-event template synthesis payload |
| **Fetch Journal** | `GET` | `/__admin/ui/api/requests?q={query}&limit={limit}&summary=true` (fallback `/__admin/requests`) | Server-side enriched journal summary entries (`sourceIp`, `sourceHost`, verdict, headers, timing) without heavy bodies; supports boolean search (`src:`, `ip:`, `host:`). |
| **Fetch Journal Detail** | `GET` | `/__admin/ui/api/requests/{id}` (fallback `/__admin/requests/{id}`) | Full `ServeEvent` with request/response bodies and caller source enrichment, lazy-fetched on row expand or action. |
| **Clear Journal** | `DELETE` | `/__admin/requests` | None |
| **Fetch Scenarios** | `GET` | `/__admin/scenarios` | None |
| **Reset Scenarios** | `POST` | `/__admin/scenarios/reset` | None |
| **Ingest OpenAPI** | `POST` | `/__admin/openapi/import` | OpenAPI YAML/JSON spec and synthesis/proxy options |
| **Inspect OpenAPI** | `POST` | `/__admin/openapi/inspect` | OpenAPI YAML/JSON spec metadata inspection (`{"spec": "..."}`) |
| **Import Mappings Bundle** | `POST` | `/__admin/mappings/import` | Bulk WireMock JSON bundle import |
| **List NetEm Proxies** | `GET` | `/__admin/tcp-netem/proxies` | None |
| **Add NetEm Toxic** | `POST` | `/__admin/tcp-netem/proxies/{name}/toxics` | Toxic JSON definition |
| **Delete NetEm Toxic** | `DELETE` | `/__admin/tcp-netem/proxies/{name}/toxics/{toxic}` | None |
| **Apply NetEm Preset** | `POST` | `/__admin/tcp-netem/proxies/{name}/presets/{preset}` | None |
| **Fetch NetEm Journal**| `GET` | `/__admin/tcp-netem/journal` | Optional `?limit`, `?scope`, `?impaired` query params |
| **Clear NetEm Journal**| `DELETE` | `/__admin/tcp-netem/journal` | None |
| **Reset All NetEm** | `POST` | `/__admin/tcp-netem/reset` | None |
| **Probe Otel Metrics** | `GET` | `/__admin/metrics/prometheus` | None |
| **Runtime Log Levels** | `GET`/`PUT`/`POST` | `/__admin/logging/levels` | Plain-text Logback logger level query/update (`logger=LEVEL`) |
| **Reset Log Levels** | `POST` | `/__admin/logging/reset` | Restores configured startup Logback levels |
| **Probe UI Version** | `GET` | `/__admin/ui/version` | Version, branch, and commit metadata JSON |
| **Probe / Update Casual Gateway** | `GET`/`POST`/`PUT` | `/__admin/casual/config` | Casual gateway port, upstream, and status |
| **Reset Casual Config** | `POST` | `/__admin/casual/reset` | Resets dynamic Casual gateway configuration |
| **Execute Casual Call** | `POST` | `/__admin/casual/call` | Service name, bufferType, payload, headers, optional host/port |
| **Discover Casual Services** | `POST` | `/__admin/casual/discover` | Queries remote/local Casual domain services and queues |
| **Probe Async Messaging** | `GET` | `/__admin/async/status` | Async engine status, Kafka sink port, broker count |
| **Start / Configure Kafka Sink** | `POST` | `/__admin/async/sinks/kafka` | Starts or reconfigures the in-process Netty Kafka wire sink |
| **Publish / Inspect Async** | `GET`/`POST`/`DELETE` | `/__admin/async/{publish,destinations,connections}` | Async queue publishing, destination inspection/purge, broker connections |
| **Probe Compressed Journal** | `GET` | `/__admin/journal/stats` | Zstd journal compression and JVM memory metrics |
| **Compact / Rebalance Journal** | `POST` | `/__admin/journal/{compact,rebalance}` | Triggers dictionary compaction or cluster rebalancing |
| **Search Compressed Journal** | `GET` | `/__admin/journal/search?query={q}` | Indexed full-text search across compressed journal entries |

---

| **Casual Service Dispatch** | `POST` | `/__casual/services/{serviceName}` | Service request payload (JSON or Base64) |
| **Casual Service Stubs** | `POST` | `/__admin/mappings` | WireMock stub matching `POST /__casual/services/{name}` |

---

## 3. Terminal CLI Platform Bindings (`wiremock-cli` / `wm`)

| Abstract Semantic Action / View | CLI Command / Invocation | Description |
| :--- | :--- | :--- |
| **Probe Instance Capabilities** | `wm info [-s <url>]` | Probes core WireMock version and extension matrix (`tcp-netem`, `otel`, `ui`). |
| **List & Search Stubs** | `wm stubs [expr] [--json]` | Displays formatted stubs catalog or JSON. Supports boolean expressions (`"GET and /cases"`, `"status=disabled"`). |
| **Stream Request Journal** | `wm tail [expr] [-f] [-u] [-n <N>]` | Live streams incoming requests with caller source (`IP`/`host`), status badges, latency, positional boolean filter expressions (including `src:`, `ip:`, `host:`), and near-miss diagnostics on 404s. |
| **Inspect Network Toxics** | `wm toxics [--json]` | Inspects active L4 TCP proxies and toxics; outputs clean fallback notice if `tcp-netem` is not installed. |
| **Launch Console Proxy** | `wm proxy [target] [port]` | Launches headless console reverse proxy (`WireMockConsoleProxy` from `wiremock-extension-ui`) and web UI server at `http://localhost:19840/__admin/ui/`. |
