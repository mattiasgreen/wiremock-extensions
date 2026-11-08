# 15. OpenAPI Dynamic Ingestion and Proxy Layering

Date: 2026-11-07

## Status

Accepted

## Context

Integrating third-party or microservice APIs requires constructing accurate mock stubs, request schemas, and response examples. Manually authoring WireMock JSON stubs from API specifications is tedious, error-prone, and drifts out of sync as specifications evolve.

Furthermore, development teams often start with live upstream services and want to selectively override specific endpoints with mocks while letting the rest pass through (hybrid live/mock proxying). Doing this manually requires carefully orchestrating stub matchers, URLs, and priorities.

## Decision

We introduce `wiremock-extension-openapi`, an in-process OpenAPI 3.0/3.1 ingestion engine that automatically synthesizes realistic WireMock stubs and layered proxy routes:

### 1. In-Process Spec Ingestion (`OpenApiAdminEndpoint`)
- Mounts REST endpoints under `/__admin/openapi/import` accepting OpenAPI 3.0 and 3.1 specifications in either YAML or JSON format.
- Supports two primary import modes:
  - `stubs`: Synthesizes offline mocked stubs for every path and operation defined in the spec.
  - `proxy`: Generates upstream reverse-proxy routes targeting a live upstream base URL, preparing the server for selective stub overrides.

### 2. Schema-Driven Example Synthesis (`OpenApiStubGenerator`)
- Generates realistic request matchers and mock response payloads directly from OpenAPI schemas and example blocks.
- Synthesizes valid sample path parameters, query parameters, headers, and request bodies.
- Embeds rich `metadata.openapi` directly into the generated `StubMapping`, enabling the Web UI and CLI to provide 1-click "Ready-to-Fire" HTTP Tester forms.

### 3. Priority Layering for Seamless Proxy Overrides
- Generated mock stubs are assigned default priority `5`.
- Service-level catch-all proxy routes are assigned priority `10`.
- This ensures that whenever a specific endpoint stub is created or updated, WireMock automatically serves the mock stub without deleting or interrupting the fallback proxy route to the live upstream server.

## Consequences

### Positive
- **Instant Mock Server Generation**: Boot complete, functional API mocks from an OpenAPI specification file in milliseconds.
- **Zero Drift**: Re-importing updated OpenAPI specs refreshes stub mappings without requiring external code-generation tools.
- **Hybrid Proxy Workflows**: Developers seamlessly transition from a 100% live proxy to selectively mocked endpoints with zero manual priority tuning.

### Negative / Trade-offs
- Deep OpenAPI 3.1 schema features (complex `anyOf`, circular refs) require heuristic example synthesis.
- Large OpenAPI files (>10MB) require streaming parsing to avoid memory spikes.
