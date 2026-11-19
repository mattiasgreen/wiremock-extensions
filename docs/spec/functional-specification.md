# Functional Specification & Core Acceptance Stories

> **Status**: Platform-Agnostic Canonical Specification
> **Audience**: Product Designers, Software Architects, Autonomous Coding Agents
> **Portability Target**: Independent of implementation language (Java, Rust, Go, Python) and presentation layer (Web SPA, Swing Desktop, SwiftUI, Terminal TUI).

> ### 🎯 Core Mission & Progressive Simulation Value Proposition
>
> **Empower teams testing complex distributed systems** (combining modern HTTP REST, legacy Casual/Tuxedo RPCs, raw TCP, and async messaging) to run the **exact same integration suite** across three progressive fidelity stages: **fully live**, **hybrid**, and **100% offline simulation**—with zero test code changes.
>
> ```mermaid
> flowchart LR
>     A["Stage 1: Live WireMock Proxy<br/>(HTTP REST + Casual XATMI / 2PC)"] -->|"1-Click Session Snapshot"| B["Stage 2: Hybrid & Offline Simulation<br/>(Zero Upstream Dependencies, Native Stubs + Scenarios)"]
>     B -->|"Deterministic Codegen + AI Synthesis"| C["Stage 3: Domain Simulation SDK<br/>(Type-Safe Java 21 Records, Builders & Replayable Scenarios)"]
> ```

---

## 1. Domain Model & Invariants

```
┌────────────────────────────────────────────────────────────────────────┐
│                          WireMock Ecosystem                            │
├─────────────────┬───────────────────┬──────────────────────────────────┤
│ Stub Mappings   │ Request Journal   │ Native Scenarios (FSM)           │
│ - Matcher Spec  │ - ServeEvents     │ - Scenario State Machine         │
│ - Response Spec │ - Match Verdicts  │ - Correlation-Scoped Names       │
│ - Priority Tier │ - Latency & Diffs │ - Transition Guards & Reset      │
└─────────────────┴───────────────────┴──────────────────────────────────┘
```

### 1.1 Entities & State Contracts

#### A. Stub Mapping (`Stub`)
- **Identity**: Globally unique UUID string (`id`).
- **Name**: Optional human-readable descriptor (`name`).
- **Status**: Native priority-tier lifecycle state: `ACTIVE` (`priority < 100`, defaulting to `5` for local stubs, `10` for live proxies, and `50` for optional `offline-guard` catch-alls) or `PARKED` (`priority >= 100`, preserving `metadata.previousPriority` for reversible mode flips).
- **Request Matcher**: Predicate rules matching HTTP method, absolute URL path / regex, query parameters, headers, and request body patterns.
- **Response Definition**: HTTP status code (default `200`), response headers dictionary, and payload (static JSON/text or dynamic template).
- **Metadata**: Key-value metadata tags (e.g. project name, author, synthesized from OpenAPI flag).

#### B. Request Journal Entry (`ServeEvent`)
- **Identity**: Globally unique UUID string (`id`).
- **Timestamp**: High-resolution ISO-8601 or millisecond epoch of request arrival.
- **Incoming Request**: HTTP method, complete URL path with query string, complete headers (including `X-Correlation-Id`, `X-Forwarded-For`, `X-Real-IP`), resolved caller source IP (`sourceIp`), cached async reverse-DNS hostname (`sourceHost`), and captured body payload.
- **Traffic Verdict**: Categorized into one of three mutually exclusive execution outcomes:
  - **`PROXIED`**: `wasMatched == true` and served via a `proxyBaseUrl` route (forwarded to a live upstream service such as an ERP System, CRM System, or Billing Platform).
  - **`STUB`**: `wasMatched == true` and served locally from a static or Handlebars-templated (`response-template`) WireMock mapping with zero upstream network calls.
  - **`UNMATCHED`**: `wasMatched == false` (HTTP 404); captures the nearest stub candidate and distance score for diagnostic diffing.
- **Response Definition**: Served status code, headers, and body.
- **Timing**: Serve duration / latency in milliseconds.

#### C. Finite State Machine Scenario (`Scenario`)
- **Identity**: Unique scenario name string (`name`, optionally scoped with an entity or correlation ID such as `CaseLifecycle-<caseId>` for parallel test isolation).
- **Current State**: Active state identifier (defaults to initial state `"Started"`).
- **Possible States**: Enumerated set of valid state string identifiers.
- **Transitions**: Governed by stub executions configured with `whenScenarioStateIs` and `willSetStateTo`.

---

## 2. Core Functional Capabilities

1. **Service-Scoped Exploration & Gradual Hybrid Simulation**: Search by text and boolean expressions (`AND`, `OR`) across URL, method, name, headers, body patterns, and metadata. Group stubs and proxy routes by remote service (`metadata.project`, e.g., `erp-system`, `crm-service`, `billing-platform`). Enable harvested stubs and park proxy routes via native priority tiers (`priority < 100` vs. `priority >= 100`) so manual testers and runtime-parameterized BDD/JUnit suites can run fully integrated, hybrid, or fully simulated without modifying test code.
2. **Lifecycle Manipulation**: Toggle active/parked state (`priority` tiers on `/__admin/mappings/{id}`) without destroying mappings. Add new stubs or catch-all proxy routes from templates. Duplicate existing stubs. Delete stubs with user assurance.
3. **Ad-Hoc Request Replay (Multi-Protocol Tester)**: Execute arbitrary HTTP, Casual, Kafka, or AMQP requests against the mock server. Preset rapid headers (`Content-Type: application/json`, `Authorization: Bearer <token>`, `X-Correlation-Id`). Record and navigate execution history.
4. **Traffic Observability, Harvesting & Triage (Request Journal)**: Live stream of served traffic clearly badged as `PROXIED`, `STUB`, or `UNMATCHED`. Server-side isolation of unmatched traffic (`?unmatched=true`), configurable journal fetch limit, and progressive/lazy DOM rendering. Snapshot `PROXIED` responses into offline service stubs (`/__admin/lifecycle/snapshots`, `/__admin/lifecycle/snapshot-template`); promote `UNMATCHED` 404 requests directly into the Unified Document Workspace (`Create Stub from Request`); replay any entry in the Multi-Protocol Tester; export as cURL or Java `stubFor()` DSL.
5. **Native Scenario Inspection**: Live visual inspection of FSM scenario states and DAG transitions via `/__admin/scenarios`, with 1-click state reset.
6. **Contract Ingestion & SDK Bootstrapping**: Ingest OpenAPI 3.0 / 3.1 definitions (as synthetic stubs or live proxies) and combine contracts with harvested Journal traffic to bootstrap typed Java `TestDataBuilder`s and domain-level simulation SDKs.

---

## 3. Compact E2E User Stories (Canonical Acceptance Flows)

These stories define the **inviolable acceptance criteria** for any client frontend (Web, Desktop, CLI, Mobile).

### Story 1: Explore and Filter Stubs (Deep Free-Text & Boolean Expressions)
- **Given** registered stubs: `[GET /api/cases, ACTIVE (priority 5)]`, `[POST /api/cases, ACTIVE, header: X-Correlation-Id: CORR-1234]`, and `[GET /health, PARKED (priority 100)]`.
- **When** the user searches for text `"cases"`.
- **Then** only `/api/cases` stubs are visible.
- **When** the user filters by correlation ID or header matcher `"CORR-1234"`.
- **Then** only the `POST /api/cases` stub with that correlation ID is displayed.
- **When** the user filters using boolean expressions `"cases AND CORR-1234"` or `"health OR CORR-1234"`.
- **Then** the catalog evaluates the boolean predicate across both shallow and deep attributes (headers, body patterns, metadata).
- **When** the user filters by status `"Disabled"` (parked).
- **Then** only `/health` is visible.
- **When** the user clears the search filter and selects status `"All"`.
- **Then** all three stubs are visible.

### Story 2: Stub Lifecycle Toggle & Deletion
- **Given** an active stub `[GET /api/cases]` (`priority 5`).
- **When** the user toggles the stub to parked (`priority >= 100`).
- **Then** the stub's priority tier updates to parked (`priority >= 100`) without deletion, yielding precedence to active stubs or an `offline-guard` (`priority 50`).
- **When** the user toggles the stub back to active.
- **Then** its original active priority tier is restored (`priority < 100`) and it matches incoming traffic at top precedence.
- **When** the user deletes the stub with confirmation.
- **Then** the stub is permanently removed from the catalog.

### Story 3: Traffic Journal Replay to Tester
- **Given** an incoming request `POST /api/cases` with body `{"title":"Urgent Bug"}` has been served and logged.
- **When** the user inspects that journal entry and triggers "Send in Tester".
- **Then** the Multi-Protocol Tester view activates immediately.
- **And** method is set to `POST`, URL to `/api/cases`, and body contains `{"title":"Urgent Bug"}`.
- **When** the user modifies the title to `"Resolved Bug"` and triggers "Send Request".
- **Then** the request is dispatched, response status is recorded, and entry is appended to Tester History.

### Story 4: Diagnostic Diff on Unmatched Requests
- **Given** a client dispatched `POST /api/case` (typo: singular instead of plural `/api/cases`).
- **When** the user inspects the journal entry.
- **Then** the entry displays an unmistakable `UNMATCHED (404)` badge.
- **And** the inspector displays the exact incoming path, headers, and payload alongside diagnostic matcher feedback.
- **When** the user triggers "Copy as cURL".
- **Then** the system clipboard contains an exact, reproducible `curl` command.

### Story 5: FSM Scenario State Management
- **Given** a scenario `"CaseLifecycle"` in state `"Started"`.
- **When** a sequence of requests transitions the state to `"CLOSED"`.
- **Then** the Scenarios view reflects current state `"CLOSED"`.
- **When** the user triggers "Reset Scenarios".
- **Then** all scenarios immediately return to `"Started"`.

### Story 6: Specification Ingestion & Synthesis
- **Given** an OpenAPI 3.0 specification file containing endpoint `/api/v1/customers`.
- **When** the user imports the specification via OpenAPI Import.
- **Then** new stub mappings are synthesized with mock schemas.
- **And** the Stubs catalog refreshes, displaying the synthesized endpoints with OpenAPI metadata badges.

### Story 7: L4 TCP Network Impairment (NetEm) & Fault Injection
- **Given** WireMock is running with Dual-Ingress NetEm proxy listening on port `8081` forwarding to core port `8080`.
- **When** the user navigates to the NetEm Console tab and clicks preset "3G Mobile".
- **Then** `latency` (300ms $\pm$ 50ms) and `bandwidth` (64 KB/s) toxics are registered on the proxy.
- **And** the Active Toxics counter displays `2`.
- **When** a client dispatches TCP traffic or HTTP requests through port `8081` matching a stream pattern (ASCII string, raw Hex byte sequence for binary protocols like Casual, or client IP/CIDR).
- **Then** the request experiences 300ms latency and throttled bandwidth, while traffic without matching pattern/IP or requests sent directly to port `8080` complete at normal line speed with zero blast radius.
- **And** the NetEm Circular Event Journal logs both impaired and normal transfers, filterable by scope / stream pattern.

### Story 8: Remote WireMock Target & Forwarded Proxy Strategy
- **Given** the Web Console is accessed at `http://localhost:8090/__admin/ui/`.
- **When** the user clicks the target status button (`#btn-target-host`) to open the Target Host tab in Settings (`#modal-settings`) and configures target `https://gateway.corp:8443/test-envs/env1/wiremock` with strategy `proxy`.
- **Then** the browser URL updates to include `?wiremock=https%3A%2F%2Fgateway.corp%3A8443%2Ftest-envs%2Fenv1%2Fwiremock`.
- **And** the navbar indicator displays `Target: https://gateway.corp:8443/test-envs/env1/wiremock (proxied)`.
- **When** the browser refreshes stubs or dispatches test requests.
- **Then** requests remain relative to the console origin while injecting `X-Forwarded-Host`, `X-Forwarded-Proto`, and `X-Forwarded-Prefix` headers to avoid browser CORS restrictions.
- **When** the user copies a cURL command from any stub mapping or the Multi-Protocol Tester.
- **Then** the copied command uses the authentic remote upstream base URL (`https://gateway.corp:8443/test-envs/env1/wiremock`) without proxy routing headers.

### Story 9: Remote WireMock Capability Detection & Graceful Degradation
- **Given** the Web Console is pointing to a remote WireMock instance (e.g. standard vanilla WireMock lacking custom extensions).
- **When** the console loads or connects to the remote target.
- **Then** the console probes endpoint availability for Core (`/__admin/mappings`), Stub Lifecycle (`/__admin/lifecycle/status`), TCP NetEm (`/__admin/tcp-netem/proxies`), Metrics (`/__admin/metrics/prometheus`), Casual Gateway (`/__admin/casual/config`), Async Messaging (`/__admin/async/status`), and Compressed Journal (`/__admin/journal/stats`).
- **And** extension tabs remain visible in the navigation bar for discoverability while unavailable controls gracefully degrade with visual hints (`.cap-disabled`) and descriptive tooltips:
  - TCP NetEm tab (`#nav-tab-netem`) is styled as `.cap-disabled` with a tooltip indicating missing `wiremock-extension-tcp-netem` (and `#tab-netem` displays `#netem-cap-missing-banner`).
  - Async Queues & Topics tab (`#nav-tab-queues`) remains visible in the navigation bar and `wiremock-extension-async` is reported as `Not Installed` in the capabilities matrix when `/__admin/async/status` is absent.
  - Casual protocol dispatch (`#btn-tester-send` when `proto=casual`) is styled as `.cap-disabled` with `#casual-cap-missing-banner` when `wiremock-extension-casual` is absent.
  - Metrics link (`#link-metrics`) is styled as `.cap-disabled`.
  - Recording snapshot controls (`#btn-journal-snapshot-filtered`, `.btn-snapshot-req`) are styled as `.cap-disabled` when `/__admin/lifecycle/status` is unavailable, while native priority-tier stub parking (`#btn-filter-disabled`, `#btn-toggle-stub`, bulk enable/disable via `/__admin/mappings/{id}`) remains enabled even on vanilla WireMock.
  - Core WireMock features (Stubs Catalog, Request Journal, HTTP Tester, Scenarios) remain 100% operational with zero console exceptions.
- **When** the user opens Settings on the Target Host tab (`#btn-target-host` -> `#modal-settings`).
- **Then** the modal displays the Detected Server Capabilities matrix (`#target-capabilities-section`) showing active capability counts (e.g. `1/7 active (Degraded)` vs `All 7 extensions active`) and clear `Active` / `Not Installed` status badges (`data-testid="cap-badge-{key}"`) for each capability.

### Story 10: High-Volume Request Journal Lazy Rendering, Custom Limit, and Server-Side Unmatched Filtering
- **Given** WireMock has recorded thousands of events in the Request Journal, with isolated unmatched requests buried among high-volume traffic.
- **When** the user checks "Show unmatched only" (`#filter-unmatched-only`).
- **Then** the journal fetches directly from the server using `?unmatched=true`, immediately surfacing all unmatched requests regardless of total journal depth.
- **When** the user enters a custom positive integer in the limit field (`#journal-limit-input`, default `100`).
- **Then** the journal fetches up to the specified volume of recent events from WireMock.
- **When** the number of displayed events exceeds the initial lazy viewport batch (50 items).
- **Then** the counter shows `Showing 50 of <total>`.
- **When** the user scrolls down the table container.
- **Then** additional rows from in-memory JSON are lazily appended to the DOM, preserving fast frame rates without pagination UI complexity.

### Story 11: Multi-Instance Tab Identification (Site Header & Tab Name)
- **Given** multiple WireMock Console instances or target environments are open in separate browser tabs.
- **When** the console loads at origin `http://localhost:8080/__admin/ui/`.
- **Then** the browser tab title (site header title) is dynamically set to `WMC - http://localhost:8080`.
- **When** the user configures a remote target server `https://gateway.corp:8443/test-envs/env1/wiremock`.
- **Then** the browser tab title immediately updates to `WMC - https://gateway.corp:8443/test-envs/env1/wiremock`.
- **When** the user resets the target back to same-origin.
- **Then** the browser tab title reverts to `WMC - http://localhost:8080`.

### Story 12: Multi-Protocol Progressive Simulation (HTTP REST + Casual XATMI/2PC)
- **Given** a 3-node enterprise topology: Customer Portal & Product System SUT (Node 1), WireMock MITM Gateway (Node 2), and Upstream Backend Server B (Node 3) hosting HTTP Case Management, HTTP CRM, and Casual 2PC Billing.
- **When** the scenario runs through active live proxy routes forwarding to Server B.
- **Then** the customer applies for a product, receives an offer, approves purchase, and is billed via Casual 2PC transaction (`tpcall("billing/charge")` within global `Xid` with `prepare` and `commit`).
- **And** the Unified Request Journal captures all 7 downstream calls as `🌐 PROXIED`, displaying protocol badge `CASUAL` and status `OK` for Casual XATMI calls without conflating with HTTP `POST` or `200`.
- **When** the operator snapshots live recordings from the Request Journal (`#btn-journal-snapshot-filtered`), enables the recorded stubs, and parks the proxy stubs via the Stubs sidebar (`#bulk-actions-bar` / `#btn-toggle-stub`).
- **Then** recorded stubs are generated in priority over proxy routes, with Casual stubs tagged as `[RECORDED CASUAL OK]` and protocol `casual`.
- **When** Upstream Server B is shut down completely and the customer scenario is replayed.
- **Then** the entire customer application, offer, approval, Casual 2PC billing, and sales order flow completes successfully offline from recorded stubs.
- **And** the Unified Request Journal verifies all 7 calls were served locally with resolution `📌 STUB`, while Upstream Server B received zero traffic.

### Story 13: Distributed Session Recording & Compound Correlation Patterns ("Snowball Harvesting") *(Planned / Not Yet Implemented)*
- **Status**: Future design concept; no session-harvesting Admin API or UI causal-tree cluster view is currently implemented.
- **Intended Flow**: Start a compound recording session matching `X-Correlation-Id`, URL patterns, or entity IDs; dynamically expand captured IDs (e.g. `caseId`, `invoiceId`) discovered in upstream responses to correlate transitive HTTP and Casual 2PC calls; inspect the transitive call tree in the Request Journal; and export a portable simulation bundle for offline CI replay.

### Story 14: In-Process Casual (XATMI) Domain Gateway, Discovery Aggregation, and Fast-Fail Proxying
- **Given** WireMock is running with the `wiremock-extension-casual` gateway listening on a raw TCP port, and active WireMock proxy stubs specifying `targetBaseUrl: casual://<host>:<port>`.
- **When** an XATMI client initiates a domain connect handshake (`DOMAIN_CONNECT_REQUEST`).
- **Then** the gateway dynamically negotiates the highest mutually supported protocol version using upstream `casual-network` decoders, supporting modern WildFly / JBoss and Quarkus Narayana XA clients without requiring an external JEE or C++ domain broker, and transparently normalizes protocol versions across decoupled downstream and upstream legs (preventing legacy Protocol 1.2 clients from encountering Protocol 1.3+ framing exceptions).
- **When** the client issues a service discovery request (`DOMAIN_DISCOVERY_REQUEST`).
- **Then** the gateway queries upstreams from active proxy stubs and aggregates them with local WireMock mock stubs (`/__casual/services/*`), returning a unified service list.
- **When** a service call (`SERVICE_CALL_REQUEST`) arrives for a stubbed service.
- **Then** the gateway evaluates WireMock stubs in-memory; if matching an offline mock, it respects `X-Casual-Error-State`, `X-Casual-User-Supplied-Error`, and `X-Casual-Transaction-State`, responding with `SERVICE_CALL_REPLY`.
- **When** an inbound call matches a Casual proxy stub (`targetBaseUrl: casual://...`).
- **Then** the gateway forwards the request natively over TCP socket-to-socket to the designated upstream domain, enlists the connection for multi-upstream 2PC transaction stickiness (`Xid`), records human-readable request/response events into WireMock's Request Journal (enabling native WireMock snapshot recording), and fails fast with actionable diagnostic hints if the upstream call exceeds the timeout (default 4s).
- **When** an unsupported frame or connection drop occurs.
- **Then** the gateway fails fast and closes the TCP channel immediately (`ctx.close()`) to avoid client-side hangs.

### Story 15: Persistent Local Browser Headers in Multi-Protocol Tester
- **Given** an engineer is testing services requiring mandatory corporate headers (`X-Correlation-Id`, `X-Tenant-Id`, `Authorization`).
- **When** the user configures local persistent headers via Console Settings (`#modal-settings` -> `Persistent Headers` tab, opened from `#btn-manage-local-headers` or `#btn-open-settings`) and enables `#tester-local-headers-toggle`.
- **Then** the headers are stored exclusively in browser `localStorage` and never transmitted to or stored in server-side stub files.
- **And** when HTTP protocol is active in the Multi-Protocol Tester, the headers render visibly in `#tester-local-headers-chips` displaying key-value pairs (supporting dynamic macros `{{uuid}}`, `{{now}}`, `{{timestamp}}`), while switching protocols (`http`, `kafka`, `amqp`, `casual`) preserves independent per-protocol drafts in the shared `#tester-headers` editor.
- **And** if the user specifies an identical header key in `#tester-headers`, the local header chip shows an `(overridden)` visual state and the per-request header takes precedence.
- **When** the user dispatches the request or clicks "Copy cURL".
- **Then** the resolved headers (combining enabled local persistent headers and per-request headers) are sent and included in the cURL command and Sent Request inspector.

### Story 16: Nested Polyglot Payload Unnesting, Charset Detection & Unified Document Workspace Round-Tripping
- **Given** an API request, response, stub mapping, OpenAPI spec, or queue message contains nested or encoded structures (escaped JSON inside JSON, XML inside JSON, JSON/KV inside XML CDATA or entities, Java `Map.toString()` `{k=v}` KV notation, or Base64-encoded payloads in `UTF-8`, `ISO-8859-1`, `WINDOWS-1252`, `KOI8-R`, or `US-ASCII`).
- **When** inspecting traffic or mappings across Stub Detail (`#stub-json-viewer`), Request Journal, Multi-Protocol Tester (`http`, `casual`, `kafka`, `amqp`), Queues, OpenAPI, or Bundles.
- **Then** the Polyglot AST engine automatically detects syntax (`json`, `xml`, `kv`, `text`) and character encoding (XML prolog `encoding` -> `X-Casual-Charset` -> `Content-Type` charset -> `UTF-8`), rendering concise descriptor badges (`JSON · 0.4 KB`, `XML · ISO-8859-1 · 0.2 KB`, `BASE64 · XML · KOI8-R · 0.3 KB`, `FIELDED · JSON · ISO-8859-1 · 0.4 KB`) and foldable nested tree cards (`PolyglotViewer`) with `Rich` / `Raw` syntax toggles, `📋 Copy`, and `⛶ Open in Workspace` (emitting zero disabled Edit buttons on read-only views).
- **When** the user triggers `New`, `Edit`, `Duplicate`, or `Edit / Workspace` on an editable document (Stubs, Tester Request Payload, OpenAPI, Bundles, or Queue Messages).
- **Then** the Unified Fullscreen Document Workspace (`#modal-document-workspace`) opens in the pretty-printed **Document Viewer** first (unless opened directly into editing for quick-paste/re-publish flows); clicking `✏️ Edit Document` or `✏️ Edit Section` transitions into the focused **Section Editor** (`code-editor.js`, defaulting in-memory to `<pre contenteditable="plaintext-only">` + `CSS.highlights` with hot-swappable fallback to the legacy dual-layer `<textarea>` engine) with breadcrumb trail navigation and wire-style preservation (`Auto`, `Compact`, `Pretty`).
- **When** the user modifies the root document or a deeply nested leaf payload and clicks `✓ Apply Section`.
- **Then** the AST splice engine executes **exact `[start, end]` character-span substitution** up the ancestor chain back to the Root Document—avoiding duplicate-sibling `.replace()` collisions, `hasOwnProperty` root-key overwrites, or placeholder collisions while preserving compact vs. multi-line wire style and surrounding whitespace/XML tags—marking the document dirty (`#workspace-dirty-badge`) until the user commits via `💾 Save` / `💾 Save Mapping` or discards via `#modal-confirm`.

### Story 17: Request Journal Caller Source Identification & Filtering

> *As an engineer operating a centralized WireMock instance,*
> *I want the Request Journal, API, and CLI to display and filter by each caller's effective source IP and reverse-DNS hostname,*
> *So that I can immediately tell which microservice, gateway, or test runner sent each request without stalling on slow DNS.*

- **Given** incoming HTTP requests (optionally behind proxies setting `X-Forwarded-For` or `X-Real-IP`), Casual TCP gateway calls forwarding remote TCP peer IPs, or Console Proxy traffic.
- **When** requests are queried via `/__admin/ui/api/requests`, rendered in the Web UI Request Journal, or streamed via `wm tail`.
- **Then** the server extracts the effective source IP (`X-Forwarded-For` first hop -> `X-Real-IP` -> socket `clientIp`), resolves reverse-DNS hostnames asynchronously on virtual threads with a bounded batch wait (~50ms) and 15-minute positive/negative TTL cache (fast-pathing loopback to `localhost`), renders a scannable `Source` column (`shortHost` + compact IP) and expandable `Source Caller` detail panel, and supports substring/boolean search (`src:`, `ip:`, `host:`) plus 1-click source chip filtering.

### Story 18: Explicit Lazy In-Browser Demo Mode from Live-Generated Snapshot
- **Given** the Web Console is opened without a live WireMock backend (or for interactive product showcase).
- **When** Demo Mode is explicitly activated via `?demo=true`, `file://`, `github.io` (without `?wiremock=`), or clicking `✨ Demo Mode` (`#btn-demo-target-host`) in Settings—never via silent 404 or network-error failover.
- **Then** the API client dynamically imports `demo-backend.js` and `demo-snapshot.js` (generated from an authentic in-process `WireMockServer` by `DemoSnapshotGenerator`), delegating stub filtering and journal matching to the canonical `StubCatalog` and `filter.js` modules for full offline parity.
