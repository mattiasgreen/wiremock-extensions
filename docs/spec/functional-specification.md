# Functional Specification & Core Acceptance Stories

> **Status**: Platform-Agnostic Canonical Specification
> **Audience**: Product Designers, Software Architects, Autonomous Coding Agents
> **Portability Target**: Independent of implementation language (Java, Rust, Go, Python) and presentation layer (Web SPA, Swing Desktop, SwiftUI, Terminal TUI).

> ### 🎯 Core Mission & Progressive Simulation Value Proposition
>
> **Empower teams testing complex distributed systems** (combining modern HTTP REST, legacy Casual/Tuxedo RPCs, raw TCP, and stateful databases) to run the **exact same integration suite** across three progressive fidelity stages: **fully live**, **hybrid**, and **100% offline simulation**—with zero test code changes.
>
> ```mermaid
> flowchart LR
>     A["Stage 1: Live WireMock Proxy<br/>(HTTP REST + Casual XATMI / 2PC)"] -->|"1-Click Session Snapshot"| B["Stage 2: Hybrid & Offline Simulation<br/>(Zero Upstream Dependencies, Stubs + Stateful AST)"]
>     B -->|"Deterministic Codegen + AI Synthesis"| C["Stage 3: Domain Simulation SDK<br/>(Type-Safe Java 21 Records, Builders & Replayable Scenarios)"]
> ```

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
- **Incoming Request**: HTTP method, complete URL path with query string, complete headers (including `X-Correlation-Id`), client IP, and captured body payload.
- **Traffic Verdict**: Categorized into one of four mutually exclusive execution outcomes:
  - **`PROXIED`**: `wasMatched == true` and served via a `proxyBaseUrl` route (forwarded to a live upstream service such as an ERP System, CRM System, or Billing Platform).
  - **`STUB`**: `wasMatched == true` and served locally from a static or Handlebars-templated (`response-template`) WireMock mapping with zero upstream network calls.
  - **`STATEFUL`**: Served in-process by the `wiremock-extension-stateful` AST engine with entity mutations and invariant guard evaluation.
  - **`UNMATCHED`**: `wasMatched == false` (HTTP 404); captures the nearest stub candidate and distance score for diagnostic diffing.
- **Response Definition**: Served status code, headers, and body.
- **Timing**: Serve duration / latency in milliseconds.

#### C. Finite State Machine Scenario (`Scenario`)
- **Identity**: Unique scenario name string (`name`).
- **Current State**: Active state identifier (defaults to initial state `"Started"`).
- **Possible States**: Enumerated set of valid state string identifiers.
- **Transitions**: Governed by stub executions configured with `whenScenarioStateIs` and `willSetStateTo`.

#### D. Stateful Entity Simulator (`SimulatorModel`)
- **Entity Collection**: Plural entity identifier (e.g., `cases`, `orders`, `customers`).
- **Identity Scheme**: Path parameter token and generated ID prefix, isolated per parallel test via `X-Correlation-Id`.
- **Live Entity State Graph**: In-memory JSON dictionary representing current state of created instances.
- **Mutations & Guards**: State transitions governed by expressions (e.g., `tasks.allMatch(status != PENDING)`).

---

## 2. Core Functional Capabilities

1. **Service-Scoped Exploration & Gradual Hybrid Simulation**: Search by text and boolean expressions (`AND`, `OR`) across URL, method, name, headers, body patterns, and metadata. Group stubs and proxy routes by remote service (`metadata.project`, e.g., `erp-system`, `crm-service`, `billing-platform`). Switch individual services between `LIVE PROXY` and `STUBS MODE` so manual testers and runtime-parameterized BDD/JUnit suites can run fully integrated, hybrid, or fully simulated without modifying test code.
2. **Lifecycle Manipulation**: Toggle active/disabled state without destroying mappings. Add new stubs or catch-all proxy routes from templates. Duplicate existing stubs. Delete stubs with user assurance.
3. **Ad-Hoc Request Replay (HTTP Tester)**: Execute arbitrary HTTP requests against the mock server. Preset rapid headers (`Content-Type: application/json`, `Authorization: Bearer <token>`, `X-Correlation-Id`). Record and navigate execution history.
4. **Traffic Observability, Harvesting & Triage (Request Journal)**: Live stream of served traffic clearly badged as `PROXIED`, `STUB`, `STATEFUL`, or `UNMATCHED`. Server-side isolation of unmatched traffic (`?unmatched=true`), configurable journal fetch limit, and progressive/lazy DOM rendering. Freeze `PROXIED` responses into offline service stubs; promote `UNMATCHED` 404 requests directly into the Stub Editor (`Create Stub from Request`); replay any entry in the HTTP Tester; export as cURL or Java `stubFor()` DSL.
5. **Stateful Scenario & Entity Inspection**: Live visual inspection of FSM scenario states and `STATEFUL` AST entity graphs per `X-Correlation-Id`, with 1-click state reset.
6. **Contract Ingestion & SDK Bootstrapping**: Ingest OpenAPI 3.0 / 3.1 definitions (as synthetic stubs or live proxies) and combine contracts with harvested Journal traffic to bootstrap typed Java `TestDataBuilder`s and domain-level simulation SDKs.

---

## 3. Compact E2E User Stories (Canonical Acceptance Flows)

These stories define the **inviolable acceptance criteria** for any client frontend (Web, Desktop, CLI, Mobile).

### Story 1: Explore and Filter Stubs (Deep Free-Text & Boolean Expressions)
- **Given** registered stubs: `[GET /api/cases, ACTIVE]`, `[POST /api/cases, ACTIVE, header: X-Correlation-Id: CORR-1234]`, and `[GET /health, DISABLED]`.
- **When** the user searches for text `"cases"`.
- **Then** only `/api/cases` stubs are visible.
- **When** the user filters by correlation ID or header matcher `"CORR-1234"`.
- **Then** only the `POST /api/cases` stub with that correlation ID is displayed.
- **When** the user filters using boolean expressions `"cases AND CORR-1234"` or `"health OR CORR-1234"`.
- **Then** the catalog evaluates the boolean predicate across both shallow and deep attributes (headers, body patterns, metadata).
- **When** the user filters by status `"Disabled"`.
- **Then** only `/health` is visible.
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
- **When** the scenario runs in `LIVE PROXY` mode against live Server B.
- **Then** the customer applies for a product, receives an offer, approves purchase, and is billed via Casual 2PC transaction (`tpcall("billing/charge")` within global `Xid` with `prepare` and `commit`).
- **And** the Unified Request Journal captures all 7 downstream calls as `🌐 PROXIED`, displaying protocol badge `CASUAL` and status `OK` for Casual XATMI calls without conflating with HTTP `POST` or `200`.
- **When** the operator snapshots live recordings via the Console UI (`#btn-project-snapshot`) and toggles mode to `STUBS MODE` (`#btn-project-mode-toggle`).
- **Then** recorded stubs are generated in priority over proxy routes, with Casual stubs tagged as `[RECORDED CASUAL OK]` and protocol `casual`.
- **When** Upstream Server B is shut down completely and the customer scenario is replayed.
- **Then** the entire customer application, offer, approval, Casual 2PC billing, and sales order flow completes successfully offline from recorded stubs.
- **And** the Unified Request Journal verifies all 7 calls were served locally with resolution `📌 STUB`, while Upstream Server B received zero traffic.
