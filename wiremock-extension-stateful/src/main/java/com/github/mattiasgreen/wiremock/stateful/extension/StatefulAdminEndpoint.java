package com.github.mattiasgreen.wiremock.stateful.extension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.mattiasgreen.wiremock.stateful.ast.AstJson;
import com.github.mattiasgreen.wiremock.stateful.ast.AstModelDefinition;
import com.github.mattiasgreen.wiremock.stateful.engine.StateEngine;
import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public class StatefulAdminEndpoint implements AdminApiExtension {

    public static final String EXTENSION_NAME = "stateful-admin-endpoint";
    public static final String CORRELATION_HEADER = "x-correlation-id";

    private final StateEngine stateEngine;

    public StatefulAdminEndpoint(StateEngine stateEngine) {
        this.stateEngine = Objects.requireNonNull(stateEngine, "stateEngine must not be null");
    }

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public void contributeAdminApiRoutes(Router router) {
        router.add(RequestMethod.POST, "/stateful/rules", (admin, serveEvent, pathParams) -> {
            String requestBody = serveEvent.getRequest().getBodyAsString();
            if (requestBody == null || requestBody.isBlank()) {
                return badRequest("Request body cannot be empty");
            }

            String correlationId = null;
            if (serveEvent.getRequest().containsHeader(CORRELATION_HEADER)) {
                correlationId =
                        serveEvent.getRequest().header(CORRELATION_HEADER).firstValue();
            }

            try {
                // If the payload wraps { "correlationId": "...", "model": { ... } }
                JsonNode rootNode = AstJson.readTree(requestBody);
                AstModelDefinition model;
                if (rootNode.has("model") && rootNode.get("model").isObject()) {
                    if (correlationId == null && rootNode.has("correlationId")) {
                        correlationId = rootNode.get("correlationId").asText();
                    }
                    model = AstJson.treeToValue(rootNode.get("model"), AstModelDefinition.class);
                } else {
                    model = AstJson.fromJson(requestBody, AstModelDefinition.class);
                }

                if (correlationId == null || correlationId.isBlank()) {
                    correlationId = "DEFAULT";
                }

                stateEngine.registerSessionModel(correlationId, model);

                String responseJson = String.format(
                        Locale.ROOT,
                        "{\"status\":\"REGISTERED\",\"correlationId\":\"%s\",\"entity\":\"%s\",\"rulesCount\":%d}",
                        correlationId,
                        model.entity(),
                        model.rules().size());

                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseJson)
                        .build();
            } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
                return badRequest("Failed to register stateful model: " + e.getMessage());
            }
        });

        router.add(RequestMethod.GET, "/stateful/models", (admin, serveEvent, pathParams) -> {
            String json = AstJson.toJson(stateEngine.getAllModels());
            return ResponseDefinitionBuilder.responseDefinition()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(json)
                    .build();
        });

        router.add(RequestMethod.DELETE, "/stateful/sessions/{correlationId}", (admin, serveEvent, pathParams) -> {
            String correlationId = pathParams.get("correlationId");
            stateEngine.clearSession(correlationId);
            return ResponseDefinitionBuilder.responseDefinition()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"status\":\"CLEARED\",\"correlationId\":\"" + correlationId + "\"}")
                    .build();
        });

        router.add(RequestMethod.DELETE, "/stateful/sessions", (admin, serveEvent, pathParams) -> {
            stateEngine.clearAll();
            return ResponseDefinitionBuilder.responseDefinition()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"status\":\"CLEARED_ALL\"}")
                    .build();
        });

        router.add(
                RequestMethod.GET,
                "/stateful/entities/{correlationId}/{entityType}/{entityId}",
                (admin, serveEvent, pathParams) -> {
                    String correlationId = pathParams.get("correlationId");
                    String entityType = pathParams.get("entityType");
                    String entityId = pathParams.get("entityId");

                    Optional<ObjectNode> entity =
                            stateEngine.getStateStore().getEntity(correlationId, entityType, entityId);
                    if (entity.isEmpty()) {
                        return ResponseDefinitionBuilder.responseDefinition()
                                .withStatus(404)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"error\":\"Entity not found\"}")
                                .build();
                    }

                    return ResponseDefinitionBuilder.responseDefinition()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(entity.get().toString())
                            .build();
                });
    }

    private ResponseDefinition badRequest(String message) {
        return ResponseDefinitionBuilder.responseDefinition()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"" + message.replace("\"", "\\\"") + "\"}")
                .build();
    }
}
