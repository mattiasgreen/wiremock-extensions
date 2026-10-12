# Functional Specification & Core Acceptance Stories

> **Status**: Platform-Agnostic Canonical Specification  
> **Audience**: Product Designers, Software Architects, Autonomous Coding Agents  
> **Portability Target**: Independent of implementation language (Java, Rust, Go, Python) and presentation layer (Web SPA, Swing Desktop, SwiftUI, Terminal TUI).

---

## 1. Domain Model & Invariants

```
┌────────────────────────────────────────────────────────────────────────┐
│                          WireMock Ecosystem                            │
├─────────────────┬───────────────────┬──────────────────────────────────┤
│ Stub Mappings   │ Request Journal   │ Stateful Simulations (FSM / Ast) │
│ - Matcher Spec  │ - ServeEvents     │ - Scenario State Machine         │
│ - Response Spec │ - Match Verdicts  │ - Dynamic Entity Graph           │
│ - Lifecycle     │ - Latency & Diffs │ - Mutation Actions & Guards      │
└─────────────────┴───────────────────┴──────────────────────────────────┘
```

### 1.1 Entities & State Contracts

#### A. Stub Mapping (`Stub`)
- **Identity**: Globally unique UUID string (`id`).
- **Name**: Optional human-readable descriptor (`name`).
- **Status**: Lifecycle state: `ACTIVE` or `DISABLED` (disabled stubs remain registered in the engine but are bypass-inhibited from request matching).
- **Request Matcher**: Predicate rules matching HTTP method, absolute URL path / regex, query parameters, headers, and request body patterns.
- **Response Definition**: HTTP status code (default `200`), response headers dictionary, and payload (static JSON/text or dynamic template).
- **Metadata**: Key-value metadata tags (e.g. project name, author, synthesized from OpenAPI flag).

#### B. Request Journal Entry (`ServeEvent`)
- **Identity**: Globally unique UUID string (`id`).
- **Timestamp**: High-resolution ISO-8601 or millisecond epoch of request arrival.
- **Incoming Request**: HTTP method, complete URL path with query string, complete headers, client IP, and captured body payload.
- **Match Verdict**: Boolean `wasMatched`. When `false`, captures the nearest stub candidate and distance score.
- **Response Definition**: Served status code, headers, and body.
- **Timing**: Serve duration / latency in milliseconds.

#### C. Finite State Machine Scenario (`Scenario`)
- **Identity**: Unique scenario name string (`name`).
- **Current State**: Active state identifier (defaults to initial state `"Started"`).
- **Possible States**: Enumerated set of valid state string identifiers.
- **Transitions**: Governed by stub executions configured with `whenScenarioStateIs` and `willSetStateTo`.

#### D. Dynamic State Simulator (`SimulatorModel`)
- **Entity Collection**: Plural entity identifier (e.g., `cases`, `orders`).
- **Identity Scheme**: Path parameter token and generated ID prefix.
- **Live Entity State Graph**: In-memory JSON dictionary representing current state of created instances.
- **Mutations & Guards**: State transitions governed by expressions (e.g., `tasks.allMatch(status != PENDING)`).

---

## 2. Core Functional Capabilities

1. **Stub Exploration & Discovery**: Search by text across URL, method, and name. Filter by lifecycle status (`All`, `Active`, `Disabled`). Sortable by any metadata attribute.
2. **Lifecycle Manipulation**: Toggle active/disabled state without destroying mappings. Add new stubs from templates. Duplicate existing stubs. Delete stubs with user assurance.
3. **Ad-Hoc Request Replay (HTTP Tester)**: Execute arbitrary HTTP requests against the mock server. Preset rapid headers (`Content-Type: application/json`, `Authorization: Bearer <token>`). Record and navigate execution history.
4. **Traffic Observability & Diffs (Request Journal)**: Live stream of served traffic. Clear visual distinction between matched (`200`-`3xx`) and unmatched (`404`) requests. 1-click promotion of captured requests into the HTTP Tester or export as cURL. Deep comparison between actual incoming traffic and stub definitions.
5. **Stateful Scenario Inspection & Reset**: Live visual inspection of FSM scenario states. Instant reset of all scenarios to `"Started"`.
6. **Contract Ingestion**: Ingest OpenAPI 3.0 / 3.1 definitions to synthesize realistic stubs and state models automatically.

---

## 3. Compact E2E User Stories (Canonical Acceptance Flows)

These stories define the **inviolable acceptance criteria** for any client frontend (Web, Desktop, CLI, Mobile).

### Story 1: Explore and Filter Stubs
- **Given** registered stubs: `[GET /api/cases, ACTIVE]`, `[POST /api/cases, ACTIVE]`, and `[GET /health, DISABLED]`.
- **When** the user searches for text `"cases"`.
- **Then** only `/api/cases` stubs are visible.
- **When** the user filters by status `"Disabled"`.
- **Then** the list is empty (no disabled stubs match `"cases"`).
- **When** the user clears the search filter and selects status `"All"`.
- **Then** all three stubs are visible.

### Story 2: Stub Lifecycle Toggle & Deletion
- **Given** an active stub `[GET /api/cases]`.
- **When** the user toggles the stub to disabled.
- **Then** the stub's status immediately updates to `DISABLED`.
- **And** requests matching `GET /api/cases` return unmatched (`404`).
- **When** the user toggles the stub back to active.
- **Then** the stub returns `200 OK` on incoming traffic.
- **When** the user deletes the stub with confirmation.
- **Then** the stub is permanently removed from the catalog.

### Story 3: Traffic Journal Replay to Tester
- **Given** an incoming request `POST /api/cases` with body `{"title":"Urgent Bug"}` has been served and logged.
- **When** the user inspects that journal entry and triggers "Send in Tester".
- **Then** the HTTP Tester view activates immediately.
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
- **When** a client dispatches HTTP requests through port `8081` with header `X-Correlation-Id: test-chaos-42`.
- **Then** the request experiences 300ms latency and throttled bandwidth, while traffic without the matching header or requests sent directly to port `8080` complete at normal line speed with zero blast radius.
- **And** the NetEm Circular Event Journal logs both impaired and normal transfers, filterable by correlation ID.

### Story 8: Remote WireMock Target & Forwarded Proxy Strategy
- **Given** the Web Console is accessed at `http://localhost:8090/__admin/ui/`.
- **When** the user clicks the target status button (`#btn-target-host`) and configures target `https://gateway.corp:8443/test-envs/env1/wiremock` with strategy `proxy`.
- **Then** the browser URL updates to include `?wiremock=https%3A%2F%2Fgateway.corp%3A8443%2Ftest-envs%2Fenv1%2Fwiremock`.
- **And** the navbar indicator displays `Target: https://gateway.corp:8443/test-envs/env1/wiremock (proxied)`.
- **When** the browser refreshes stubs or dispatches test requests.
- **Then** requests remain relative to the console origin while injecting `X-Forwarded-Host`, `X-Forwarded-Proto`, and `X-Forwarded-Prefix` headers to avoid browser CORS restrictions.
- **When** the user copies a cURL command from any stub mapping or the HTTP Tester.
- **Then** the copied command uses the authentic remote upstream base URL (`https://gateway.corp:8443/test-envs/env1/wiremock`) without proxy routing headers.

### Story 9: Remote WireMock Capability Detection & Graceful Degradation
- **Given** the Web Console is pointing to a remote WireMock instance (e.g. standard vanilla WireMock lacking custom extensions).
- **When** the console loads or connects to the remote target.
- **Then** the console probes endpoint availability for Core (`/__admin/mappings`), Stub Lifecycle (`/__admin/stubs/disabled`), Stateful Simulation (`/__admin/stateful/models`), TCP NetEm (`/__admin/tcp-netem/proxies`), and Metrics (`/__admin/metrics/prometheus`).
- **And** features backed by missing extensions gracefully degrade:
  - TCP NetEm tab (`#nav-tab-netem`) is hidden.
  - Metrics link (`#link-metrics`) is hidden.
  - Dynamic stub controls (`#btn-create-dynamic-stub`, `#btn-filter-dynamic`) are hidden.
  - Stub pause/lifecycle controls (`#btn-filter-disabled`, `#btn-toggle-stub`, bulk disable) are hidden.
  - Core WireMock features (Stubs Catalog, Request Journal, HTTP Tester, Scenarios) remain 100% operational.
- **When** the user opens the Target Server modal (`#btn-target-host`).
- **Then** the modal displays the Detected Server Capabilities matrix (`#target-capabilities-section`) showing the active ratio (e.g. `1/5 active (Degraded)` or `All 5 extensions active`) and clear `Active` / `Not Installed` status badges for each capability.


