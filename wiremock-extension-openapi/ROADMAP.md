# Subproject Roadmap: wiremock-extension-openapi

Vision, scope, and staged execution plan for OpenAPI 3.0 / 3.1 support in `wiremock-extensions`.

---

## 1. Scope & Priorities

### In Scope (Current Iteration: Core / Priorities 1-3)
1. **Specification Parsing & Dereferencing**:
   - Ingest OpenAPI 3.0 and 3.1 specifications in both YAML and JSON formats.
   - Dereference `$ref` references (local and components).
2. **Path & Parameter Matchers**:
   - Support `urlPathTemplate` for endpoints with path parameters (e.g., `/pets/{id}`).
   - Support exact path (`urlPathEqualTo`) for static endpoints.
   - Generate query parameter matchers for required query params.
   - Generate header matchers for required headers.
3. **Response & Mock Synthesis (Priorities 1-3)**:
   - **Priority 1 (Examples)**: Extract explicit `example` or `examples` defined on media types or schemas.
   - **Priority 2 (Defaults)**: Extract `default` values defined in schema properties.
   - **Priority 3 (Synthetic Generation)**: Deterministic mock values based on primitive schema types (`string`, `integer`, `number`, `boolean`, `array`, `object`) and formats (`uuid`, `date-time`, `date`, `email`, `uri`, `ipv4`).
4. **WireMock Admin API & Embedded UI**:
   - `POST /__admin/openapi/import`: Import endpoint returning created stubs count and stub details.
   - Embedded Web UI modal in `wiremock-extension-ui` with file upload / drag-and-drop.

---

## 2. Deferred / Future Enhancements (Post-Core Roadmap)

### State Machine & Scenario Inference
- **Automatic REST Lifecycle Inference**:
  - Infer WireMock Scenario states across CRUD verbs on common resource paths (e.g. `POST /items` -> `STARTED` to `CREATED`, followed by `GET /items/{id}` and `DELETE /items/{id}`).
- **OpenAPI Vendor Extensions (`x-wiremock-*`)**:
  - `x-wiremock-scenario`: Explicitly declare a WireMock scenario name.
  - `x-wiremock-required-state`: State required for this stub to trigger.
  - `x-wiremock-new-state`: New state transitioned to after response.
  - `x-wiremock-priority`: Custom stub priority override.
- **WireMock Response Templating Dynamic Injection**:
  - Inject Handlebars expressions (`{{request.path.[0]}}`, `{{now}}`, `{{randomValue type='UUID'}}`) directly into synthesized responses.
- **Faker & Mock Extensions**:
  - Support `x-faker` (e.g., `x-faker: internet.email`, `x-faker: commerce.productName`) for ultra-realistic data generation.
