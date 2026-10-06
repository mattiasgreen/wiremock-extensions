package com.github.mattiasgreen.wiremock.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Admin API Extension providing endpoints to ingest OpenAPI specifications
 * and register generated WireMock stubs dynamically.
 *
 * Route: POST /__admin/openapi/import
 */
public class OpenApiAdminEndpoint implements AdminApiExtension {

    public static final String EXTENSION_NAME = "openapi-admin-endpoint";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public void contributeAdminApiRoutes(Router router) {
        router.add(RequestMethod.POST, "/openapi/inspect", (admin, serveEvent, pathParams) -> {
            String requestBody = serveEvent.getRequest().getBodyAsString();
            if (requestBody == null || requestBody.isBlank()) {
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Request body cannot be empty\"}")
                        .build();
            }

            String specContent = requestBody;
            try {
                if (requestBody.trim().startsWith("{")) {
                    JsonNode node = objectMapper.readTree(requestBody);
                    if (node.has("spec") && node.get("spec").isTextual()) {
                        specContent = node.get("spec").asText();
                    }
                }
            } catch (Exception ignored) {
            }

            try {
                OpenApiSpecInspector inspector = new OpenApiSpecInspector();
                OpenApiSpecInfo info = inspector.inspect(specContent);
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(Json.write(info))
                        .build();
            } catch (Exception e) {
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":" + Json.write(e.getMessage()) + "}")
                        .build();
            }
        });

        router.add(RequestMethod.POST, "/openapi/import", (admin, serveEvent, pathParams) -> {
            String requestBody = serveEvent.getRequest().getBodyAsString();
            if (requestBody == null || requestBody.isBlank()) {
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Request body cannot be empty\"}")
                        .build();
            }

            String specContent = requestBody;
            OpenApiStubOptions options = OpenApiStubOptions.defaults();

            // Check if body is a JSON wrapper { "spec": "...", "options": { ... } }
            try {
                if (requestBody.trim().startsWith("{")) {
                    JsonNode node = objectMapper.readTree(requestBody);
                    if (node.has("spec") && node.get("spec").isTextual()) {
                        specContent = node.get("spec").asText();
                        if (node.has("options") && node.get("options").isObject()) {
                            JsonNode opts = node.get("options");
                            OpenApiStubOptions.Builder builder = OpenApiStubOptions.builder();
                            if (opts.has("includeOnlySuccessResponses")) {
                                builder.includeOnlySuccessResponses(
                                        opts.get("includeOnlySuccessResponses").asBoolean());
                            }
                            if (opts.has("matchRequiredQueryParams")) {
                                builder.matchRequiredQueryParams(
                                        opts.get("matchRequiredQueryParams").asBoolean());
                            }
                            if (opts.has("matchRequiredHeaders")) {
                                builder.matchRequiredHeaders(
                                        opts.get("matchRequiredHeaders").asBoolean());
                            }
                            if (opts.has("useDefaultValues")) {
                                builder.useDefaultValues(
                                        opts.get("useDefaultValues").asBoolean());
                            }
                            if (opts.has("useSyntheticExamples")) {
                                builder.useSyntheticExamples(
                                        opts.get("useSyntheticExamples").asBoolean());
                            }
                            if (opts.has("generationMode")) {
                                try {
                                    builder.generationMode(GenerationMode.valueOf(
                                            opts.get("generationMode").asText().toUpperCase()));
                                } catch (Exception ignored) {
                                }
                            }
                            if (opts.has("proxyBaseUrl")
                                    && !opts.get("proxyBaseUrl").isNull()) {
                                builder.proxyBaseUrl(opts.get("proxyBaseUrl").asText());
                            }
                            if (opts.has("targetProject")
                                    && !opts.get("targetProject").isNull()) {
                                builder.targetProject(opts.get("targetProject").asText());
                            }
                            if (opts.has("additionalProxyHeaders")
                                    && opts.get("additionalProxyHeaders").isObject()) {
                                java.util.Iterator<Map.Entry<String, JsonNode>> fields =
                                        opts.get("additionalProxyHeaders").fields();
                                while (fields.hasNext()) {
                                    Map.Entry<String, JsonNode> entry = fields.next();
                                    builder.additionalProxyHeader(
                                            entry.getKey(), entry.getValue().asText());
                                }
                            }
                            options = builder.build();
                        }
                    }
                }
            } catch (Exception ignored) {
                // Not a wrapped JSON, treat whole body as raw YAML or JSON spec
            }

            try {
                OpenApiStubGenerator generator = new OpenApiStubGenerator(options);
                List<StubMapping> generatedStubs = generator.generateStubs(specContent);

                List<OpenApiImportResult.StubSummary> summaries = new ArrayList<>();
                for (StubMapping stub : generatedStubs) {
                    admin.addStubMapping(stub);
                    int status = stub.getResponse().getStatus();
                    Integer statusVal = (status > 0) ? status : null;
                    summaries.add(new OpenApiImportResult.StubSummary(
                            stub.getId() != null ? stub.getId().toString() : null,
                            stub.getName(),
                            stub.getRequest().getMethod().getName(),
                            stub.getRequest().getUrl(),
                            stub.getRequest().getUrlPathTemplate(),
                            statusVal));
                }

                OpenApiImportResult result = new OpenApiImportResult(summaries.size(), summaries, List.of());
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(Json.write(result))
                        .build();

            } catch (Exception e) {
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":" + Json.write(e.getMessage()) + "}")
                        .build();
            }
        });
    }
}
