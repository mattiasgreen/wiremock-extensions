# UI Architecture, Workflows & Interaction Contracts

> **Status**: Canonical UI Architecture & Workflow Playbook
> **Complements**: [`docs/spec/functional-specification.md`](functional-specification.md) and [`docs/spec/platform-bindings.md`](platform-bindings.md)
> **Audience**: Product Engineers, Frontend Developers, Autonomous Coding Agents
> **Applicability**: Web SPA, Native Desktop (Swing), Terminal TUI, Mobile Apps

---

## 1. Core Product Mission & Mental Model

`wiremock-extension-ui` is **not** a generic API design clone. It is the **Interactive Workbench, Traffic Harvester, and Diagnostic Console** for the `wiremock-extensions` progressive simulation suite.

### 1.1 The Enterprise Problem We Solve
Enterprise systems under test (e.g., an Insurance or Banking checkout application) typically depend on 10+ downstream services (**ERP System**, **CRM System**, **Billing Platform**, **Risk Engine**, **Legacy ESB**, **Document Archive**). Full end-to-end integration environments suffer from frequent maintenance windows, drifted test data across systems, and an inability to deterministically trigger edge cases or transport faults.

### 1.2 The 5-Stage Progressive Simulation Maturity Ladder
The UI must guide and support teams as they migrate remote dependencies—**gradually, service by service**—from live integration to hermetic, domain-driven simulation:

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                      The 5-Stage Simulation Maturity Ladder                             │
│                                                                                         │
│  Stage 1: Intercept & Record (Live Proxy Mode)                                          │
│    SUT points all outbound calls to WireMock. Proxy routes (grouped by Remote Service)  │
│    forward traffic to live ERP/CRM/Billing and record everything as [🌐 PROXIED].       │
│                                          │                                              │
│                                          ▼                                              │
│  Stage 2: Harvest & Go Offline (Static Stubs Baseline)                                  │
│    Extract recorded Journal traffic into per-service stub collections.                  │
│    Flip peripheral services from LIVE PROXY ──► STUBS MODE while complex core           │
│    services remain proxied (Hybrid Simulation).                                         │
│                                          │                                              │
│                                          ▼                                              │
│  Stage 3: Parameterize Simple Services (Templated Stubs)                                │
│    Adjust static stubs with WireMock Handlebars templating ({{request.pathSegments}},   │
│    {{jsonPath}}) and request matchers for lightweight dynamic variation.                │
│                                          │                                              │
│                                          ▼                                              │
│  Stage 4: Simulate Stateful Behavior & Faults (L4 NetEm + L7 + Stateful AST)            │
│    Simulate ERP business rule exceptions, stateful entity lifecycles ([🧠 STATEFUL]),   │
│    and L4 TCP outages/throttling—all isolated per parallel test via X-Correlation-Id.   │
│                                          │                                              │
│                                          ▼                                              │
│  Stage 5: Bootstrap the Producer Simulation SDK (TestDataBuilders + BDD/JUnit DSL)      │
│    Combine OpenAPI/AsyncAPI contracts + recorded payloads to synthesize typed DTOs,     │
│    fluent TestDataBuilders (.isVip(), .isBlockedDueToRisk()), and domain-level          │
│    simulation SDKs so the exact same BDD test runs integrated, hybrid, or simulated.    │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Canonical Taxonomy & Traffic Verdicts

Ambiguous labels create cognitive friction. Across all views (Stubs Catalog, Request Journal, HTTP Tester, Scenarios), the UI must use this exact, terse vocabulary:

### 2.1 Request Journal & Stub Verdict Badges

Every served request in the Request Journal—and every rule in the Stubs Catalog—belongs to one of **four mutually exclusive verdicts**:

| Badge | CSS / Token | Tooltip (`title`) | Runtime Meaning |
| :--- | :--- | :--- | :--- |
| **`🌐 PROXIED`** | `#3B82F6` (Sapphire Blue) | *"Forwarded over the network to the live upstream service via proxyBaseUrl."* | WireMock matched a proxy route and talked to the live remote system (e.g., ERP or CRM). |
| **`📌 STUB`** *(or `STUB · TPL`)* | `#10B981` (Emerald Green) | *"Served locally by a WireMock stub mapping (static or Handlebars template). Zero upstream network calls."* | WireMock answered in-process from a declarative stub. Append `· TPL` when `response-template` transformer is active. |
| **`🧠 STATEFUL`** | `#8B5CF6` (Violet / Indigo) | *"Served locally by the in-memory Stateful AST engine with entity state mutations and invariant guards."* | Handled by `wiremock-extension-stateful` (replaces the ambiguous `"DYNAMIC"` label). |
| **`⚠️ UNMATCHED`** | `#F59E0B` (Amber) / `#EF4444` (Red) | *"No matching stub, stateful rule, or proxy route found (HTTP 404)."* | WireMock did not know how to handle the request; requires a new stub or proxy route. |

### 2.2 Service (`Project`) Grouping & Hybrid Mode
* What WireMock metadata stores as `metadata.project` represents an **Upstream / Remote Service** (e.g., `erp-system`, `crm-service`, `billing-platform`, `notification-gateway`).
* Each service operates independently in **`LIVE PROXY`** or **`STUBS MODE`**, allowing manual testers in the UI (and automated JUnit/Cucumber runners via runtime parameters) to mix live and simulated services seamlessly.

---

## 3. Core End-to-End UI Workflows (The 30-Second Contracts)

Every UI screen and button placement must optimize these four end-to-end flows:

### Workflow A: Live Integration Harvesting (`PROXIED` $\rightarrow$ `STUB`)
1. **Bootstrap Proxy**: User imports an OpenAPI spec in **Live Proxy Mode** *or* creates a quick catch-all/prefix **Proxy Route** (`➕ New` $\rightarrow$ Proxy Template, e.g., `/erp/api/.*` $\rightarrow$ `https://erp-test.internal`) for a remote service when no OpenAPI spec exists.
2. **Observe Live Traffic**: User runs manual click-testing or a BDD test. Calls appear in the Request Journal badged as **`🌐 PROXIED`** with the target upstream origin visible.
3. **Harvest**:
   * *Single Call*: User clicks **`📸 Freeze as Stub`** on a `PROXIED` journal entry to save that authentic response as a `DISABLED` stub under that service.
   * *Whole Service*: User selects the service in the Stubs sidebar and clicks **`📸 Snapshot`** in the Service Lifecycle Bar to capture all deduplicated `PROXIED` calls into `DISABLED` stubs.
4. **Go Offline**: User clicks **`🟣 Switch to Stubs`**. Proxy routes for that service are disabled, harvested static stubs are enabled, and subsequent test runs execute offline (`📌 STUB`).

### Workflow B: Unmatched Request Triage (`UNMATCHED 404` $\rightarrow$ Working Stub)
1. **Identify**: User filters Request Journal by **`⚠️ Unmatched`** (or spots the amber/red `UNMATCHED` pill).
2. **Diagnose**: Expanding the row displays the incoming request alongside **Near-Miss Diagnostic Feedback** (closest stub candidate and which matcher failed: URL, Method, Header, or Body).
3. **Synthesize Stub (Never "Freeze" a 404!)**:
   * On an `UNMATCHED` row, `📸 Freeze Stub` must **not** blindly save the 404 error as a stub.
   * Instead, clicking **`➕ Create Stub from Request`** opens the **Stub Editor Modal** pre-populated with the captured request's `method`, `url`, `headers`, and `bodyPatterns`, with the response defaulted to `200 OK` for immediate editing and activation.
4. **Verify**: Clicking **`🚀 Replay in Tester`** loads the exact captured request into the HTTP Tester to confirm the new stub responds with `200 OK`.

### Workflow C: Matched Stub & Correlation-ID Session Debugging (`Journal` $\leftrightarrow$ `Stub` $\leftrightarrow$ `Tester`)
1. **Bidirectional Navigation**:
   * From a **Stub** in the Stubs tab: Clicking **`📜 View in Journal`** switches to the Request Journal filtered to that stub's URL/ID.
   * From a **`📌 STUB`** or **`🧠 STATEFUL`** entry in the Request Journal: Clicking the matched stub name (**`🔗 Inspect Stub`**) jumps directly to the Stubs tab with that mapping selected.
2. **Correlation-ID Lens (`X-Correlation-Id`)**:
   * Parallel JUnit/Cucumber tests isolate state and chaos using `X-Correlation-Id`.
   * Whenever a request in the Journal carries `X-Correlation-Id`, display it as a clickable chip so the engineer can filter the entire Journal (and NetEm transfer log) to that single test execution's trace in one click.

### Workflow D: Bridging From UI to Code (`TestDataBuilder` & SDK Extraction)
1. **Level 1 (Immediate Code Export)**: From any Stub or Journal entry, developers can copy not just `📋 Copy cURL` and `📋 Copy JSON`, but **`☕ Copy Java stubFor()`** (and DTO / `TestDataBuilder` snippets) to paste directly into JUnit/Cucumber tests.
2. **Level 2 (SDK Bootstrapping)**: Exporting a service's harvested stubs alongside its OpenAPI contract feeds into the Simulation SDK generator (`Model DTOs` + semantic `TestDataBuilder`s + runtime-parameterized `*ServiceMock`).

---

## 4. Layout, Action Budget & Sensory Feedback Rules

### 4.1 Action Budget (Preventing "Button Soup")
* **Toolbar Ceiling**: A panel header or card header may contain **at most 1 Primary CTA** (solid accent fill) and **at most 2–3 compact Secondary buttons**. Secondary utility actions (`Import`, `Export`, `Refresh`) should use compact icon+label buttons with clear visual grouping.
* **Contextual Action Placement**: Actions that mutate or export a specific item (`⚡ Test`, `✏️ Edit`, `🧬 Clone`, `⏸️ Disable`, `🗑️ Delete`) belong inside the **Detail Inspector header** of the selected item, never duplicated across every row of a dense list.
* **Persistent vs. Full-Width Workspace**:
  * `Stubs` and `HTTP Tester` share the persistent left `#sidebar-stubs` catalog so developers can click through stubs and immediately fire requests in the Tester.
  * `Request Journal`, `Scenarios`, and `TCP NetEm` collapse `#sidebar-stubs` to claim 100% horizontal viewport width for wide tables and DAG visualizations.

### 4.2 Non-Blocking Sensory Feedback
* **No Blocking `alert()` for Routine Confirmations**: Never use native browser `alert()` for success confirmations (e.g., after taking a traffic snapshot). Use inline button state transitions (`📸 Snapshot` $\rightarrow$ `✅ Captured 4 Stubs!`) or a non-blocking toast notification. Reserve `confirm()` strictly for destructive operations (`Delete Stub`, `Clear Journal`, `Reset Scenarios`).
* **Clipboard Assurance**: Every copy button must flash `✅ Copied!` for 1500ms before restoring its label.

---

## 5. Scalable Zero-Build Vanilla ES6 Architecture

To scale `wiremock-extension-ui` to 8,000+ lines of code (adding Matcher Builders, Live Stateful Session Inspectors, AsyncAPI Event Streams, and SDK Codegen Previews) **without** introducing `npm`, Webpack, Vite, or React, all frontend code must follow these architectural invariants:

### 5.1 Native ES Module Boundaries (`src/main/resources/ui/modules/`)
* Keep modules strictly single-responsibility and domain-scoped:
  * **Core Infrastructure**: `state.js` (reactive state + URL sync), `dom.js` (cached static selectors + HTML escaping), `api.js` (fetch wrapper + proxy header injection), `router.js` (hash deep-linking), `capabilities.js` (remote feature probing & graceful degradation).
  * **Feature Verticals**: `stubs.js`, `lifecycle.js`, `projects.js`, `journal.js`, `tester.js`, `scenarios.js`, `dynamic-stubs.js`, `netem.js`, `openapi.js`, `import-export.js`, `bulk-actions.js`.
* **Single Source of Truth for Static Demo**: `wiremock-extension-ui/src/main/resources/ui/` is the **only** directory where UI files are authored. Never commit duplicated copies of UI scripts into `docs/site/demo/` (use the Gradle `syncDemoSite` task or CI artifact assembly).
* **Zero Marketing Assets in Classpath**: Never store documentation GIFs, pitch decks, or Playwright reference PNGs under `src/main/resources/ui/`. Keep them in `docs/` so the production JAR remains `< 200 KB`.

### 5.2 DOM Rendering & Event Delegation
* **Container-Level Event Delegation**: Instead of attaching hundreds of individual `.addEventListener()` callbacks inside `.forEach()` loops after every `innerHTML` render (which leaks closures and slows down large catalogs), attach a single delegated click/change listener on the stable parent container (e.g., `#stub-list`, `#journal-list`) using `e.target.closest('[data-action]')`.
* **XSS-Safe Template Literals**: Always wrap dynamic user/network strings (`url`, `name`, header values, query params) in `escapeHtml(...)` from `dom.js` before interpolating into HTML strings.
* **Two-Way Form $\leftrightarrow$ JSON Synchronization**: For complex editors (like the Stub Editor & Matcher Builder), treat the standard WireMock JSON mapping object as the canonical data model; form rows read from and mutate that JSON structure directly rather than maintaining parallel disconnected form state.
