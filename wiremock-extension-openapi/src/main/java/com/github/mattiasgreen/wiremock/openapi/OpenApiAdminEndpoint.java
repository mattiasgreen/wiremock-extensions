package com.github.mattiasgreen.wiremock.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mattiasgreen.wiremock.openapi.analysis.OperationStubAnalysis;
import com.github.mattiasgreen.wiremock.openapi.analysis.RecordedStubAnalysisReport;
import com.github.mattiasgreen.wiremock.openapi.analysis.RecordedStubAnalyzer;
import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
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
                                            opts.get("generationMode").asText().toUpperCase(java.util.Locale.ROOT)));
                                } catch (IllegalArgumentException ignored) {
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
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                // Not a wrapped JSON, treat whole body as raw YAML or JSON spec
            }

            try {
                OpenApiStubGenerator generator = new OpenApiStubGenerator(options);
                List<StubMapping> generatedStubs = generator.generateStubs(specContent);

                List<OpenApiImportResult.StubSummary> summaries = new ArrayList<>();
                for (StubMapping stub : generatedStubs) {
                    admin.addStubMapping(stub);
                    int status =
                            (stub.getResponse() != null && stub.getResponse().getStatus() > 0)
                                    ? stub.getResponse().getStatus()
                                    : 200;
                    summaries.add(new OpenApiImportResult.StubSummary(
                            stub.getId() != null ? stub.getId().toString() : null,
                            stub.getName(),
                            stub.getRequest().getMethod().getName(),
                            stub.getRequest().getUrl(),
                            stub.getRequest().getUrlPathTemplate(),
                            status));
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

        router.add(RequestMethod.POST, "/openapi/analyze-recorded-stubs", (admin, serveEvent, pathParams) -> {
            String requestBody = serveEvent.getRequest().getBodyAsString();
            if (requestBody == null || requestBody.isBlank()) {
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Request body cannot be empty\"}")
                        .build();
            }

            String specContent = null;
            String project = null;
            boolean generalizeAndRegister = false;

            try {
                if (requestBody.trim().startsWith("{")) {
                    JsonNode node = objectMapper.readTree(requestBody);
                    if (node.has("spec") && node.get("spec").isTextual()) {
                        specContent = node.get("spec").asText();
                    }
                    if (node.has("project") && node.get("project").isTextual()) {
                        project = node.get("project").asText();
                    }
                    if (node.has("generalizeAndRegister")
                            && node.get("generalizeAndRegister").isBoolean()) {
                        generalizeAndRegister =
                                node.get("generalizeAndRegister").asBoolean();
                    }
                } else {
                    specContent = requestBody;
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                specContent = requestBody;
            }

            if (specContent == null || specContent.isBlank()) {
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"OpenAPI specification content is required\"}")
                        .build();
            }

            try {
                List<StubMapping> allStubs = admin.listAllStubMappings().getMappings();
                final String targetProject = project;
                List<StubMapping> candidateStubs = allStubs.stream()
                        .filter(s -> {
                            if (targetProject != null && !targetProject.isBlank()) {
                                if (s.getMetadata() != null && s.getMetadata().containsKey("project")) {
                                    return targetProject.equalsIgnoreCase(
                                            s.getMetadata().getString("project"));
                                }
                            }
                            return (s.getName() != null && s.getName().startsWith("[RECORDED"))
                                    || (s.getMetadata() != null
                                            && s.getMetadata().containsKey("recorded"));
                        })
                        .toList();

                if (candidateStubs.isEmpty()) {
                    candidateStubs = allStubs.stream()
                            .filter(s -> s.getName() == null || !s.getName().startsWith("[GENERALIZED"))
                            .toList();
                }

                RecordedStubAnalyzer analyzer = new RecordedStubAnalyzer();
                RecordedStubAnalysisReport report = analyzer.analyze(specContent, candidateStubs);

                List<String> registeredStubIds = new ArrayList<>();
                if (generalizeAndRegister) {
                    for (OperationStubAnalysis op : report.operations()) {
                        for (UUID stubId : op.matchedStubIds()) {
                            StubMapping rawStub = candidateStubs.stream()
                                    .filter(s -> stubId.equals(s.getId()))
                                    .findFirst()
                                    .orElse(null);
                            if (rawStub != null) {
                                StubMapping generalized = analyzer.generalizeStub(rawStub, op);
                                admin.addStubMapping(generalized);
                                registeredStubIds.add(generalized.getId().toString());
                                break;
                            }
                        }
                    }
                }

                Map<String, Object> responsePayload = new LinkedHashMap<>();
                responsePayload.put("report", report);
                responsePayload.put("candidateStubsAnalyzed", candidateStubs.size());
                responsePayload.put("generalizeAndRegister", generalizeAndRegister);
                responsePayload.put("registeredStubIds", registeredStubIds);

                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(Json.write(responsePayload))
                        .build();
            } catch (RuntimeException e) {
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":" + Json.write(e.getMessage()) + "}")
                        .build();
            }
        });
    }
}
