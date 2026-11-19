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

1. **OpenAPI Ingestion (`OpenApiAdminEndpoint`)**:
   - `POST /__admin/openapi/import`: Accepts either a raw YAML/JSON OpenAPI specification body or a JSON wrapper `{ "spec": "<yaml-or-json>", "options": { "generationMode": "SYNTHETIC" | "PROXY", "proxyBaseUrl": "...", "targetProject": "...", "includeOnlySuccessResponses": true, "matchRequiredQueryParams": false, "matchRequiredHeaders": false, "useDefaultValues": true, "useSyntheticExamples": true, "additionalProxyHeaders": { ... } } }`.
   - `POST /__admin/openapi/inspect`: Accepts either a raw OpenAPI spec or `{ "spec": "<yaml-or-json>" }` and returns `OpenApiSpecInfo` metadata without registering stubs.
2. **Priority Layering for Live Proxy Recording**:
   - Synthesized mock stubs default to priority `5`.
   - Service-level catch-all proxy routes (`proxyBaseUrl`) default to priority `10` so any harvested or explicit stub automatically overrides the live upstream proxy without deleting the proxy route.
3. **Ready-to-Fire Tester Metadata**: `OpenApiStubGenerator` populates `metadata.openapi` (including synthesized path parameters, query examples, and request body payloads) so the Web UI HTTP Tester can prefill valid requests in 1 click.
