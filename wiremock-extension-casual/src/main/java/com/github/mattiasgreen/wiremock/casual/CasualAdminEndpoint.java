package com.github.mattiasgreen.wiremock.casual;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Admin API Extension providing REST control plane endpoints under {@code /__admin/casual/config}
 * for querying and updating Casual protocol gateway configuration, upstream targets, and recording state at runtime.
 */
public class CasualAdminEndpoint implements AdminApiExtension {

    public static final String EXTENSION_NAME = "casual-admin-endpoint";
    private static final Logger LOG = LoggerFactory.getLogger(CasualAdminEndpoint.class);

    private final CasualRegistry registry;
    private final ObjectMapper objectMapper;

    public CasualAdminEndpoint() {
        this(CasualRegistry.getInstance());
    }

    public CasualAdminEndpoint(CasualRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public void contributeAdminApiRoutes(Router router) {
        router.add(RequestMethod.GET, "/casual/config", (admin, serveEvent, pathParams) -> getConfigJson());
        router.add(
                RequestMethod.POST,
                "/casual/config",
                (admin, serveEvent, pathParams) ->
                        updateConfig(serveEvent.getRequest().getBodyAsString()));
        router.add(
                RequestMethod.PUT,
                "/casual/config",
                (admin, serveEvent, pathParams) ->
                        updateConfig(serveEvent.getRequest().getBodyAsString()));
        router.add(RequestMethod.POST, "/casual/reset", (admin, serveEvent, pathParams) -> resetConfig());
    }

    private ResponseDefinition getConfigJson() {
        try {
            CasualConfiguration config = registry.getConfig();
            Map<String, Object> map = config.toMap();
            map.put(
                    "running",
                    registry.getGateway().map(CasualWireMockGateway::isRunning).orElse(false));
            String json = objectMapper.writeValueAsString(map);
            return jsonResponse(200, json);
        } catch (JsonProcessingException e) {
            LOG.error("Failed to serialize Casual configuration", e);
            return errorResponse(500, "Failed to read Casual configuration: " + e.getMessage());
        }
    }

    private ResponseDefinition updateConfig(String body) {
        if (body == null || body.isBlank()) {
            return errorResponse(400, "Request body must not be empty");
        }

        try {
            CasualConfiguration update = objectMapper.readValue(body, CasualConfiguration.class);
            CasualConfiguration updated = registry.updateConfig(update);
            Map<String, Object> map = updated.toMap();
            map.put(
                    "running",
                    registry.getGateway().map(CasualWireMockGateway::isRunning).orElse(false));
            String json = objectMapper.writeValueAsString(map);
            return jsonResponse(200, json);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            String sanitizedMsg =
                    e.getMessage() != null ? e.getMessage().replace('\r', '_').replace('\n', '_') : "unknown";
            LOG.warn("Failed to parse or apply Casual configuration update: {}", sanitizedMsg);
            return errorResponse(400, "Invalid Casual configuration: " + sanitizedMsg);
        }
    }

    private ResponseDefinition resetConfig() {
        registry.reset();
        return getConfigJson();
    }

    private ResponseDefinition jsonResponse(int status, String body) {
        return ResponseDefinitionBuilder.responseDefinition()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body)
                .build();
    }

    private ResponseDefinition errorResponse(int status, String message) {
        String sanitized =
                message != null ? message.replace('\r', ' ').replace('\n', ' ').replace("\"", "\\\"") : "";
        return ResponseDefinitionBuilder.responseDefinition()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": \"" + sanitized + "\"}")
                .build();
    }
}
