# wiremock-extension-openapi

OpenAPI 3.0 & 3.1 specification importer and stub synthesizer for WireMock Standalone.

## Capabilities

* **Automated Stub Generation**:
  * Parses OpenAPI 3.0 and 3.1 specifications (both YAML and JSON formats).
  * Automatically dereferences external and internal component `$ref` schemas.
  * Uses WireMock 3.x `urlPathTemplate` pattern matching for endpoints with path parameters (e.g. `/users/{id}`).
  * Synthesizes matchers for required query parameters and headers.
* **Live Transparent Proxy & Traffic Recording**:
  * Import OpenAPI specs directly as live dynamic proxy stubs (`proxyBaseUrl` with Priority 10) targeted to an upstream service URL or server from the spec (`servers:` block auto-discovery).
  * Auto-propagate headers with optional supplemental client proxy headers.
  * Tag and group proxy stubs cleanly by project.
  * Real-time spec inspection route (`POST /__admin/openapi/inspect`) extracting servers, title, and endpoints before import.
* **Format-Aware Response Data Synthesizer**:
  * **Priority 1 (Examples)**: Uses explicit `example` or `examples` definitions from media types and schemas.
  * **Priority 2 (Defaults)**: Uses `default` values defined on schema properties.
  * **Priority 3 (Synthetic Generation)**: Generates type and format-aware sample data (`uuid`, `date-time`, `date`, `email`, `uri`, `ipv4`, enums).
* **WireMock Admin API Ingestion**:
  * Exposes `POST /__admin/openapi/import` route accepting raw YAML/JSON or wrapped JSON with generation options (`generationMode`, `proxyBaseUrl`, `targetProject`, `additionalProxyHeaders`, `includeOnlySuccessResponses`, `matchRequiredQueryParams`, `matchRequiredHeaders`).
  * Exposes `POST /__admin/openapi/inspect` route to preview spec metadata and available server URLs.
* **Web UI Integration**:
  * Directly embedded in `wiremock-extension-ui` with drag-and-drop dropzone, live proxy toggle, server picker, and project tagging.

## Quick Demo

![OpenAPI Spec Importer Workflow](../docs/images/demo-openapi-import.gif)

## Admin API Usage

### Import Raw YAML Specification
```bash
curl -X POST http://localhost:8080/__admin/openapi/import \
  -H "Content-Type: application/x-yaml" \
  --data-binary @petstore.yaml
```

### Import with Fine-Grained Options
```bash
curl -X POST http://localhost:8080/__admin/openapi/import \
  -H "Content-Type: application/json" \
  -d '{
    "spec": "openapi: 3.0.3\n...",
    "options": {
      "includeOnlySuccessResponses": true,
      "matchRequiredQueryParams": true,
      "matchRequiredHeaders": true
    }
  }'
```

## Programmatic Java Usage

```java
OpenApiStubGenerator generator = new OpenApiStubGenerator(OpenApiStubOptions.defaults());
List<StubMapping> stubs = generator.generateStubs(specYamlContent);

for (StubMapping stub : stubs) {
    wireMockServer.addStubMapping(stub);
}
```
