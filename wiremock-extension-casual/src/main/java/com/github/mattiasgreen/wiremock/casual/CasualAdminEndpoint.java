package com.github.mattiasgreen.wiremock.casual;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.laz.casual.network.protocol.messages.domain.CasualDomainDiscoveryReplyMessage;
import se.laz.casual.network.protocol.messages.domain.Service;

/**
 * Admin API Extension providing REST control plane endpoints under {@code /__admin/casual/config}
 * for querying and updating Casual protocol gateway configuration at runtime.
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
        router.add(
                RequestMethod.POST,
                "/casual/call",
                (admin, serveEvent, pathParams) ->
                        executeCasualCall(serveEvent.getRequest().getBodyAsString()));
        router.add(
                RequestMethod.POST,
                "/casual/discover",
                (admin, serveEvent, pathParams) ->
                        executeCasualDiscover(serveEvent.getRequest().getBodyAsString()));
    }

    private ResponseDefinition executeCasualCall(String body) {
        if (body == null || body.isBlank()) {
            return errorResponse(400, "Request body must not be empty");
        }

        try {
            JsonNode root = objectMapper.readTree(body);
            String service =
                    root.hasNonNull("service") ? root.get("service").asText().trim() : "";
            if (service.isBlank()) {
                return errorResponse(400, "Missing required 'service' field in Casual call request");
            }

            String payload = root.hasNonNull("payload") ? root.get("payload").asText() : "{}";
            String bufferType =
                    root.hasNonNull("bufferType") ? root.get("bufferType").asText() : "JSON";
            String encoding = root.hasNonNull("payloadEncoding")
                    ? root.get("payloadEncoding").asText().toLowerCase(java.util.Locale.ROOT)
                    : "";

            if ("hex".equals(encoding) && !payload.isBlank()) {
                byte[] hexBytes = CasualBufferUtils.fromHex(payload);
                payload = java.util.Base64.getEncoder().encodeToString(hexBytes);
                if (bufferType.equalsIgnoreCase("JSON")) {
                    bufferType = "X_OCTET";
                }
            }

            String host = root.hasNonNull("host") && !root.get("host").asText().isBlank()
                    ? root.get("host").asText().trim()
                    : "127.0.0.1";

            int port = root.hasNonNull("port") ? root.get("port").asInt() : 0;
            if (port <= 0
                    || (registry.getGateway().isPresent()
                            && port == CasualConfiguration.DEFAULT_PORT
                            && (host.equals("127.0.0.1") || host.equalsIgnoreCase("localhost")))) {
                port = registry.getGateway()
                        .map(CasualWireMockGateway::getPort)
                        .orElse(port > 0 ? port : CasualClient.DEFAULT_PORT);
            }

            int timeout = root.hasNonNull("timeoutSeconds")
                    ? root.get("timeoutSeconds").asInt()
                    : CasualClient.DEFAULT_TIMEOUT_SECONDS;
            if (timeout <= 0) {
                timeout = CasualClient.DEFAULT_TIMEOUT_SECONDS;
            }

            try (CasualClient client = new CasualClient(host, port, timeout)) {
                CasualCallResult result = client.call(service, bufferType, payload);

                Map<String, Object> respMap = new LinkedHashMap<>();
                respMap.put("service", result.serviceName());
                respMap.put("errorState", result.errorState().name());
                respMap.put("errorCode", result.errorState().getValue());
                respMap.put("userCode", result.userCode());
                respMap.put(
                        "execution",
                        result.execution() != null ? result.execution().toString() : "");
                respMap.put("bufferType", result.bufferType());
                respMap.put("payload", result.payload());
                respMap.put("durationMs", result.durationMs());
                respMap.put("success", result.isSuccess());
                respMap.put("isBinary", !result.isJson());
                respMap.put("sizeBytes", result.getBytes().length);
                if (!result.isJson()) {
                    respMap.put("payloadHex", result.toHexDump(256));
                }

                Map<String, String> responseHeaders = new LinkedHashMap<>();
                responseHeaders.put(
                        "Content-Type",
                        result.isJson()
                                ? "application/json"
                                : (CasualBufferUtils.isCStringType(result.bufferType())
                                        ? "text/plain; charset=UTF-8"
                                        : "application/octet-stream"));
                responseHeaders.put("X-Casual-Error-State", result.errorState().name());
                responseHeaders.put("X-Casual-Buffer-Type", result.bufferType());
                if (result.userCode() != 0) {
                    responseHeaders.put("X-Casual-User-Error", String.valueOf(result.userCode()));
                }
                if (result.execution() != null) {
                    responseHeaders.put("X-Casual-Execution", result.execution().toString());
                }
                respMap.put("headers", responseHeaders);

                Map<String, String> requestHeaders = new LinkedHashMap<>();
                requestHeaders.put(
                        "Content-Type",
                        CasualBufferUtils.isJsonType(bufferType)
                                ? "application/json"
                                : (CasualBufferUtils.isCStringType(bufferType)
                                        ? "text/plain; charset=UTF-8"
                                        : "application/octet-stream"));
                requestHeaders.put("X-Casual-Service", service);
                requestHeaders.put("X-Casual-Buffer-Type", bufferType);
                if (root.has("headers") && root.get("headers").isObject()) {
                    root.get("headers").fields().forEachRemaining(entry -> {
                        requestHeaders.put(entry.getKey(), entry.getValue().asText());
                    });
                }
                respMap.put("requestHeaders", requestHeaders);

                return jsonResponse(200, objectMapper.writeValueAsString(respMap));
            } catch (Exception e) {
                String safeMsg = e.getMessage() != null
                        ? e.getMessage().replace('\r', ' ').replace('\n', ' ')
                        : "Unknown error";
                LOG.warn("Failed executing Casual service call via Admin API: {}", safeMsg);

                Map<String, Object> errMap = new LinkedHashMap<>();
                errMap.put("service", service);
                errMap.put("errorState", "TPESYSTEM");
                errMap.put("errorCode", -1);
                errMap.put("userCode", -1);
                errMap.put("execution", "");
                errMap.put("bufferType", bufferType != null ? bufferType : "JSON");
                errMap.put("payload", "Casual call failed: " + safeMsg);
                errMap.put("durationMs", 0);
                errMap.put("success", false);

                return jsonResponse(200, objectMapper.writeValueAsString(errMap));
            }
        } catch (JsonProcessingException e) {
            return errorResponse(400, "Invalid JSON request: " + e.getMessage());
        }
    }

    private ResponseDefinition executeCasualDiscover(String body) {
        try {
            String host = "127.0.0.1";
            int port = 0;
            int timeout = CasualClient.DEFAULT_TIMEOUT_SECONDS;
            List<String> services = new ArrayList<>();

            if (body != null && !body.isBlank()) {
                JsonNode root = objectMapper.readTree(body);
                if (root.hasNonNull("host") && !root.get("host").asText().isBlank()) {
                    host = root.get("host").asText().trim();
                }
                if (root.hasNonNull("port")) {
                    port = root.get("port").asInt();
                }
                if (root.hasNonNull("timeoutSeconds")) {
                    timeout = root.get("timeoutSeconds").asInt();
                }
                if (root.has("services") && root.get("services").isArray()) {
                    for (JsonNode item : root.get("services")) {
                        services.add(item.asText());
                    }
                }
            }

            if (port <= 0
                    || (registry.getGateway().isPresent()
                            && port == CasualConfiguration.DEFAULT_PORT
                            && (host.equals("127.0.0.1") || host.equalsIgnoreCase("localhost")))) {
                port = registry.getGateway()
                        .map(CasualWireMockGateway::getPort)
                        .orElse(port > 0 ? port : CasualClient.DEFAULT_PORT);
            }
            if (timeout <= 0) {
                timeout = CasualClient.DEFAULT_TIMEOUT_SECONDS;
            }

            try (CasualClient client = new CasualClient(host, port, timeout)) {
                long startNanos = System.nanoTime();
                CasualDomainDiscoveryReplyMessage reply = client.discover(services);
                long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

                Map<String, Object> respMap = new LinkedHashMap<>();
                respMap.put("domainName", reply.getDomainName());
                respMap.put(
                        "domainId",
                        reply.getDomainId() != null ? reply.getDomainId().toString() : "");
                respMap.put(
                        "services",
                        reply.getServices() != null
                                ? reply.getServices().stream()
                                        .map(Service::getName)
                                        .toList()
                                : List.of());
                respMap.put("durationMs", durationMs);

                return jsonResponse(200, objectMapper.writeValueAsString(respMap));
            } catch (Exception e) {
                String safeMsg = e.getMessage() != null
                        ? e.getMessage().replace('\r', ' ').replace('\n', ' ')
                        : "Unknown error";
                return errorResponse(500, "Discovery failed: " + safeMsg);
            }
        } catch (JsonProcessingException e) {
            return errorResponse(400, "Invalid JSON request: " + e.getMessage());
        }
    }

    private ResponseDefinition getConfigJson() {
        try {
            CasualConfiguration config = registry.getConfig();
            Map<String, Object> map = config.toMap();
            map.put(
                    "running",
                    registry.getGateway().map(CasualWireMockGateway::isRunning).orElse(false));
            registry.getGateway().ifPresent(gw -> map.put("port", gw.getPort()));
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
        return ResponseDefinitionBuilder.responseDefinition()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"" + message.replace("\"", "\\\"") + "\"}")
                .build();
    }
}
