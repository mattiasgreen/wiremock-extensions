# Product & Architecture Roadmap: WireMock Extensions

Strategic vision and exploratory feature sets for future major iterations of `wiremock-extensions`.

---

## 1. OpenAPI 3.0 / 3.1 Spec Importer

*Goal: Generate complete mock suites and state machines directly from OpenAPI / Swagger specifications without manual JSON authoring.*

### Key Capabilities Under Exploration
* **Automated Stub Synthesis**:
  * Parse OpenAPI paths, operations, status codes, and `content` schemas.
  * Generate default stubs for standard responses (`200 OK`, `201 Created`, `400 Bad Request`, `404 Not Found`).
  * Populate realistic mock response bodies using `example` / `default` schema values.
* **State Machine & Scenario Inference**:
  * Infer WireMock scenario progressions from resource lifecycles (e.g., `POST /items` -> `Started` to `CREATED`, followed by `GET /items/{id}` and `DELETE /items/{id}`).
* **Spec Ingestion Interfaces**:
  * UI Drag-and-Drop: Drop `.yaml` or `.json` OpenAPI specs into the UI to auto-populate the stubs list.
  * Admin API Route: `POST /__admin/openapi/import` for automated pipeline integration.

---

## 2. In-UI Stub Authoring & Live Editing

*Goal: Transform the embedded web UI from an inspector/tester into a complete standalone WireMock control plane.*

### Key Capabilities Under Exploration
* **Visual Stub Creator / Editor Modal**:
  * Rich modal interface for creating new stubs or modifying existing mappings without touching JSON files.
  * URL Matcher Builder: visual selector for `urlEqualTo`, `urlPathMatching`, `urlPattern`.
  * Header & Query Parameter Matchers: key-value grid for headers and query parameters.
  * Response Builder: status code selector with standard HTTP semantics, headers list, and multi-mode response body editor (Raw, JSON, Binary base64).
* **Live Server Synchronization**:
  * Directly invoke WireMock Admin API routes (`POST /__admin/mappings`, `PUT /__admin/mappings/{id}`, `DELETE /__admin/mappings/{id}`).
  * Instant feedback in stub list without server reboot.

---

## 3. Bulk Stub Portfolio Management

*Goal: Seamless migration and sharing of stub collections between environments and team members.*

### Key Capabilities Under Exploration
* **One-Click Export**:
  * Export all active mappings and scenario states into a single downloadable `stubs-bundle.json`.
* **Drag-and-Drop Bulk Import**:
  * Upload stub bundles directly into the web UI with conflict resolution choices (Overwrite, Append, Skip).
* **Snapshot & Checkpoint System**:
  * Save and restore named "snapshots" of mock server state for repeatable test scenarios.

---

## 4. Live Proxy Recording & Stub Synthesis

*Goal: Record traffic from real upstream environments and synthesize production-accurate mocks.*

### Key Capabilities Under Exploration
* **Proxy Configuration from UI**:
  * Configure upstream target URL directly from the web interface.
* **Smart Recording Filters**:
  * Filter recorded traffic by URL patterns, methods, or headers.
  * Auto-extract dynamic IDs into URL regex patterns.
* **Direct Promotion**:
  * Promote any recorded serve event in the Request Journal into a permanent stub mapping with a single click.
