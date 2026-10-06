package com.github.mattiasgreen.wiremock.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.admin.model.SingleStubMappingResult;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.common.Metadata;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Admin API Extension providing endpoints for stub lifecycle management:
 * enable, disable, toggle, bulk operations, and querying disabled stubs.
 *
 * Routes:
 *   POST /__admin/stubs/{id}/disable
 *   POST /__admin/stubs/{id}/enable
 *   POST /__admin/stubs/{id}/toggle
 *   GET  /__admin/stubs/disabled
 *   POST /__admin/stubs/bulk
 */
public class StubLifecycleAdminEndpoint implements AdminApiExtension {

    public static final String EXTENSION_NAME = "stub-lifecycle-admin-endpoint";
    private final DisabledStubStore disabledStubStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StubLifecycleAdminEndpoint(DisabledStubStore disabledStubStore) {
        this.disabledStubStore = Objects.requireNonNull(disabledStubStore, "disabledStubStore must not be null");
    }

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public void contributeAdminApiRoutes(Router router) {
        // Query all disabled stubs
        router.add(RequestMethod.GET, "/stubs/disabled", (admin, serveEvent, pathParams) -> {
            List<StubMapping> disabledList = new ArrayList<>(disabledStubStore.getAll());
            Map<String, Object> result = Map.of("mappings", disabledList, "meta", Map.of("total", disabledList.size()));
            return ResponseDefinitionBuilder.responseDefinition()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(Json.write(result))
                    .build();
        });

        // Disable a specific stub
        router.add(RequestMethod.POST, "/stubs/{id}/disable", (admin, serveEvent, pathParams) -> {
            UUID id;
            try {
                id = UUID.fromString(pathParams.get("id"));
            } catch (Exception e) {
                return badRequest("Invalid UUID format");
            }

            SingleStubMappingResult result = admin.getStubMapping(id);
            if (result == null || !result.isPresent()) {
                // If it's already in disabledStore, report success
                if (disabledStubStore.get(id) != null) {
                    return ok(Map.of("status", "disabled", "id", id.toString()));
                }
                return notFound("Stub not found");
            }

            StubMapping stub = result.getItem();
            disabledStubStore.put(stub);
            admin.removeStubMapping(id);

            return ok(Map.of(
                    "status", "disabled", "id", id.toString(), "name", stub.getName() != null ? stub.getName() : ""));
        });

        // Enable a specific stub
        router.add(RequestMethod.POST, "/stubs/{id}/enable", (admin, serveEvent, pathParams) -> {
            UUID id;
            try {
                id = UUID.fromString(pathParams.get("id"));
            } catch (Exception e) {
                return badRequest("Invalid UUID format");
            }

            StubMapping stub = disabledStubStore.remove(id);
            if (stub == null) {
                SingleStubMappingResult result = admin.getStubMapping(id);
                if (result != null && result.isPresent()) {
                    return ok(Map.of("status", "enabled", "id", id.toString()));
                }
                return notFound("Disabled stub not found");
            }

            admin.addStubMapping(stub);
            return ok(Map.of(
                    "status", "enabled", "id", id.toString(), "name", stub.getName() != null ? stub.getName() : ""));
        });

        // Toggle stub active <-> disabled
        router.add(RequestMethod.POST, "/stubs/{id}/toggle", (admin, serveEvent, pathParams) -> {
            UUID id;
            try {
                id = UUID.fromString(pathParams.get("id"));
            } catch (Exception e) {
                return badRequest("Invalid UUID format");
            }

            StubMapping disabledStub = disabledStubStore.remove(id);
            if (disabledStub != null) {
                admin.addStubMapping(disabledStub);
                return ok(Map.of(
                        "status",
                        "enabled",
                        "id",
                        id.toString(),
                        "name",
                        disabledStub.getName() != null ? disabledStub.getName() : ""));
            }

            SingleStubMappingResult activeResult = admin.getStubMapping(id);
            if (activeResult != null && activeResult.isPresent()) {
                StubMapping stub = activeResult.getItem();
                disabledStubStore.put(stub);
                admin.removeStubMapping(id);
                return ok(Map.of(
                        "status",
                        "disabled",
                        "id",
                        id.toString(),
                        "name",
                        stub.getName() != null ? stub.getName() : ""));
            }

            return notFound("Stub not found");
        });

        // Edit / Upsert stub mapping (supports active or disabled stubs)
        router.add(RequestMethod.PUT, "/stubs/{id}", (admin, serveEvent, pathParams) -> {
            UUID id;
            try {
                id = UUID.fromString(pathParams.get("id"));
            } catch (Exception e) {
                return badRequest("Invalid UUID format");
            }

            String body = serveEvent.getRequest().getBodyAsString();
            if (body == null || body.isBlank()) {
                return badRequest("Request body is required");
            }

            StubMapping updatedStub;
            try {
                updatedStub = Json.read(body, StubMapping.class);
            } catch (Exception e) {
                return badRequest("Failed to deserialize StubMapping: " + e.getMessage());
            }
            updatedStub.setId(id);

            // 1. If stub is in disabledStubStore, update it there
            if (disabledStubStore.get(id) != null) {
                disabledStubStore.put(updatedStub);
                return ok(updatedStub);
            }

            // 2. If stub is active in admin, update it in WireMock admin
            SingleStubMappingResult result = admin.getStubMapping(id);
            if (result != null && result.isPresent()) {
                admin.editStubMapping(updatedStub);
                return ok(updatedStub);
            }

            // 3. Fallback upsert: register in admin
            admin.addStubMapping(updatedStub);
            return ok(updatedStub);
        });

        // Delete a stub mapping by ID (removes from active or disabled store)
        router.add(RequestMethod.DELETE, "/stubs/{id}", (admin, serveEvent, pathParams) -> {
            UUID id;
            try {
                id = UUID.fromString(pathParams.get("id"));
            } catch (Exception e) {
                return badRequest("Invalid UUID format");
            }

            disabledStubStore.remove(id);
            admin.removeStubMapping(id);
            return ok(Map.of("status", "deleted", "id", id.toString()));
        });

        // Bulk operations: enable, disable, delete
        router.add(RequestMethod.POST, "/stubs/bulk", (admin, serveEvent, pathParams) -> {
            String body = serveEvent.getRequest().getBodyAsString();
            if (body == null || body.isBlank()) {
                return badRequest("Request body is required");
            }

            try {
                JsonNode root = objectMapper.readTree(body);
                String action = root.path("action").asText("");
                JsonNode idsNode = root.path("ids");
                if (!idsNode.isArray()) {
                    return badRequest("Missing or invalid 'ids' array");
                }

                int count = 0;
                for (JsonNode idNode : idsNode) {
                    try {
                        UUID id = UUID.fromString(idNode.asText());
                        switch (action.toLowerCase(Locale.ROOT)) {
                            case "disable" -> {
                                SingleStubMappingResult res = admin.getStubMapping(id);
                                if (res != null && res.isPresent()) {
                                    disabledStubStore.put(res.getItem());
                                    admin.removeStubMapping(id);
                                    count++;
                                }
                            }
                            case "enable" -> {
                                StubMapping stub = disabledStubStore.remove(id);
                                if (stub != null) {
                                    admin.addStubMapping(stub);
                                    count++;
                                }
                            }
                            case "delete" -> {
                                admin.removeStubMapping(id);
                                disabledStubStore.remove(id);
                                count++;
                            }
                            default -> {
                                return badRequest("Unsupported action: " + action);
                            }
                        }
                    } catch (IllegalArgumentException ignored) {
                    }
                }

                return ok(Map.of("action", action, "processed", count));
            } catch (IOException | IllegalArgumentException e) {
                return badRequest("Failed to parse request: " + e.getMessage());
            }
        });

        // Project Recording Snapshot: converts proxied journal traffic into DISABLED stubs grouped to project
        router.add(RequestMethod.POST, "/projects/{project}/recordings/snapshot", (admin, serveEvent, pathParams) -> {
            String projectParam = pathParams.get("project");
            String targetProject = projectParam != null ? URLDecoder.decode(projectParam, StandardCharsets.UTF_8) : "";
            boolean allProjects =
                    targetProject.isBlank() || "*".equals(targetProject) || "_all".equalsIgnoreCase(targetProject);

            List<ServeEvent> serveEvents = admin.getServeEvents().getServeEvents();
            List<StubMapping> createdStubs = new ArrayList<>();
            Set<String> seenSignatures = new HashSet<>();

            for (ServeEvent event : serveEvents) {
                if (event.getResponse() == null || event.getResponse().getStatus() <= 0) {
                    continue;
                }

                StubMapping matchedStub = event.getStubMapping();
                String stubProj = getStubProject(matchedStub);
                boolean matchesProject = allProjects || targetProject.equalsIgnoreCase(stubProj);
                boolean wasProxied = (matchedStub != null && isProxyStub(matchedStub))
                        || (event.getResponseDefinition() != null
                                && event.getResponseDefinition().getProxyBaseUrl() != null);

                if (!matchesProject || !wasProxied) {
                    continue;
                }

                String signature = event.getRequest().getMethod().getName() + ":"
                        + event.getRequest().getUrl() + ":"
                        + event.getResponse().getStatus();
                if (seenSignatures.contains(signature)) {
                    continue;
                }
                seenSignatures.add(signature);

                RequestPatternBuilder reqPattern = RequestPatternBuilder.newRequestPattern(
                        event.getRequest().getMethod(),
                        WireMock.urlEqualTo(event.getRequest().getUrl()));

                ResponseDefinitionBuilder respDef = ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(event.getResponse().getStatus());

                byte[] bodyBytes = event.getResponse().getBody();
                String bodyString = null;
                if (bodyBytes != null && bodyBytes.length > 0) {
                    if (com.github.tomakehurst.wiremock.common.Gzip.isGzipped(bodyBytes)) {
                        bodyString = com.github.tomakehurst.wiremock.common.Gzip.unGzipToString(bodyBytes);
                    } else {
                        bodyString = event.getResponse().getBodyAsString();
                    }
                }
                if (bodyString != null && !bodyString.isEmpty()) {
                    respDef.withBody(bodyString);
                }

                if (event.getResponse().getHeaders() != null) {
                    for (HttpHeader header : event.getResponse().getHeaders().all()) {
                        String name = header.key();
                        if (!name.equalsIgnoreCase("Transfer-Encoding")
                                && !name.equalsIgnoreCase("Content-Length")
                                && !name.equalsIgnoreCase("Content-Encoding")
                                && !name.equalsIgnoreCase("Connection")) {
                            respDef.withHeader(name, header.firstValue());
                        }
                    }
                }

                String assignedProject = matchedStub != null
                        ? getStubProject(matchedStub)
                        : (allProjects ? "Recorded Traffic" : targetProject);

                String stubName = String.format(
                        "[RECORDED %s %d] %s",
                        event.getRequest().getMethod().getName(),
                        event.getResponse().getStatus(),
                        event.getRequest().getUrl());

                StubMapping recordedStub = new StubMapping(reqPattern.build(), respDef.build());
                recordedStub.setId(UUID.randomUUID());
                recordedStub.setName(stubName);
                recordedStub.setPriority(5); // Higher priority than proxy (10)

                Metadata metadata = Metadata.metadata()
                        .attr("source", "recorded-proxy")
                        .attr("project", assignedProject)
                        .attr("mode", "static")
                        .attr("recordedAt", Instant.now().toString())
                        .build();
                recordedStub.setMetadata(metadata);

                // Add as DISABLED per default! Park in disabledStubStore without adding to admin
                disabledStubStore.put(recordedStub);
                createdStubs.add(recordedStub);
            }

            Map<String, Object> respBody = Map.of(
                    "project",
                    targetProject,
                    "totalRecorded",
                    createdStubs.size(),
                    "status",
                    "disabled",
                    "mappings",
                    createdStubs);
            return ok(respBody);
        });

        // Project Mode Toggle: Switches a project between "proxy" and "stubs" mode
        router.add(RequestMethod.POST, "/projects/{project}/mode", (admin, serveEvent, pathParams) -> {
            String projectParam = pathParams.get("project");
            String targetProject = projectParam != null ? URLDecoder.decode(projectParam, StandardCharsets.UTF_8) : "";
            if (targetProject.isBlank()) {
                return badRequest("Project name is required");
            }

            String body = serveEvent.getRequest().getBodyAsString();
            String requestedMode = "stubs";
            if (body != null && !body.isBlank()) {
                try {
                    JsonNode root = objectMapper.readTree(body);
                    if (root.has("mode")) {
                        requestedMode = root.get("mode").asText("stubs").toLowerCase(Locale.ROOT);
                    }
                } catch (IOException ignored) {
                }
            }

            int enabledCount = 0;
            int disabledCount = 0;

            if ("stubs".equals(requestedMode)) {
                // In "stubs" mode: disable proxy stubs, enable static/recorded stubs
                for (StubMapping stub : List.copyOf(admin.listAllStubMappings().getMappings())) {
                    if (targetProject.equalsIgnoreCase(getStubProject(stub)) && isProxyStub(stub)) {
                        disabledStubStore.put(stub);
                        admin.removeStubMapping(stub.getId());
                        disabledCount++;
                    }
                }
                for (StubMapping stub : List.copyOf(disabledStubStore.getAll())) {
                    if (targetProject.equalsIgnoreCase(getStubProject(stub)) && !isProxyStub(stub)) {
                        disabledStubStore.remove(stub.getId());
                        admin.addStubMapping(stub);
                        enabledCount++;
                    }
                }
            } else if ("proxy".equals(requestedMode)) {
                // In "proxy" mode: disable static/recorded stubs, enable proxy stubs
                for (StubMapping stub : List.copyOf(admin.listAllStubMappings().getMappings())) {
                    if (targetProject.equalsIgnoreCase(getStubProject(stub)) && !isProxyStub(stub)) {
                        disabledStubStore.put(stub);
                        admin.removeStubMapping(stub.getId());
                        disabledCount++;
                    }
                }
                for (StubMapping stub : List.copyOf(disabledStubStore.getAll())) {
                    if (targetProject.equalsIgnoreCase(getStubProject(stub)) && isProxyStub(stub)) {
                        disabledStubStore.remove(stub.getId());
                        admin.addStubMapping(stub);
                        enabledCount++;
                    }
                }
            } else {
                return badRequest("Unsupported mode: " + requestedMode + ". Use 'stubs' or 'proxy'.");
            }

            Map<String, Object> respBody = Map.of(
                    "project", targetProject,
                    "activeMode", requestedMode,
                    "stubsEnabled", enabledCount,
                    "stubsDisabled", disabledCount);
            return ok(respBody);
        });
    }

    private boolean isProxyStub(StubMapping stub) {
        if (stub == null) return false;
        if (stub.getResponse() != null
                && stub.getResponse().getProxyBaseUrl() != null
                && !stub.getResponse().getProxyBaseUrl().isBlank()) {
            return true;
        }
        if (stub.getMetadata() != null
                && "proxy".equalsIgnoreCase(stub.getMetadata().getString("mode"))) {
            return true;
        }
        return false;
    }

    private String getStubProject(StubMapping stub) {
        if (stub == null || stub.getMetadata() == null) return "Ungrouped";
        String project = stub.getMetadata().getString("project");
        return (project != null && !project.isBlank()) ? project : "Ungrouped";
    }

    private static com.github.tomakehurst.wiremock.http.ResponseDefinition ok(Object body) {
        return ResponseDefinitionBuilder.responseDefinition()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(Json.write(body))
                .build();
    }

    private static com.github.tomakehurst.wiremock.http.ResponseDefinition badRequest(String message) {
        return ResponseDefinitionBuilder.responseDefinition()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody(Json.write(Map.of("error", message)))
                .build();
    }

    private static com.github.tomakehurst.wiremock.http.ResponseDefinition notFound(String message) {
        return ResponseDefinitionBuilder.responseDefinition()
                .withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody(Json.write(Map.of("error", message)))
                .build();
    }
}
