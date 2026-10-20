# AGENTS.md: `wiremock-extension-openapi` Module Guidelines

Scoped instructions for `wiremock-extension-openapi` (OpenAPI 3.0/3.1 YAML/JSON ingestion, realistic request/response example synthesis, and live proxy route generation).

---

## 1. Fast Verification Loops

- **Targeted Test Class (< 4s)**:
  ```bash
  ./gradlew :wiremock-extension-openapi:test --tests "OpenApiAdminEndpointTest" -q --console=plain
  ```
- **Full Module Suite (< 6s)**:
  ```bash
  ./gradlew :wiremock-extension-openapi:test -q --console=plain
  ```

---

## 2. Synthesis & Proxy Invariants

1. **OpenAPI Ingestion (`OpenApiAdminEndpoint`)**: Exposes `POST /__admin/openapi/import` accepting `specContent` (YAML/JSON), `projectName`, `baseUrl` (live proxy target), and `importMode` (`stubs` vs `proxy`).
2. **Priority Layering for Live Proxy Recording**:
   - Synthesized mock stubs default to priority `5`.
   - Service-level catch-all proxy routes (`proxyBaseUrl`) default to priority `10` so any harvested or explicit stub automatically overrides the live upstream proxy without deleting the proxy route.
3. **Ready-to-Fire Tester Metadata**: `OpenApiStubGenerator` populates `metadata.openapi` (including synthesized path parameters, query examples, and request body payloads) so the Web UI HTTP Tester can prefill valid requests in 1 click.
