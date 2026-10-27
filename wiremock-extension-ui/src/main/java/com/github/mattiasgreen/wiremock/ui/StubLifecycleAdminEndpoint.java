package com.github.mattiasgreen.wiremock.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.admin.model.SingleStubMappingResult;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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

            RecordedStubConverter.SnapshotOptions options = RecordedStubConverter.SnapshotOptions.defaults();
            String body = serveEvent.getRequest().getBodyAsString();
            if (body != null && !body.isBlank()) {
                try {
                    JsonNode root = objectMapper.readTree(body);
                    options = parseSnapshotOptions(root);
                } catch (IOException ignored) {
                }
            }

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

                String bodyHash = event.getRequest().getBody() != null
                        ? Integer.toHexString(Arrays.hashCode(event.getRequest().getBody()))
                        : "";
                String signature = event.getRequest().getMethod().getName() + ":"
                        + event.getRequest().getUrl() + ":"
                        + event.getResponse().getStatus() + ":"
                        + bodyHash;
                if (seenSignatures.contains(signature)) {
                    continue;
                }
                seenSignatures.add(signature);

                StubMapping recordedStub =
                        RecordedStubConverter.convert(event, allProjects ? "Recorded Traffic" : targetProject, options);
                if (recordedStub != null) {
                    disabledStubStore.put(recordedStub);
                    createdStubs.add(recordedStub);
                }
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

        // Generate Snapshot Stub Template: converts a single ServeEvent into an editable StubMapping definition
        router.add(RequestMethod.POST, "/stubs/snapshot-template", (admin, serveEvent, pathParams) -> {
            String body = serveEvent.getRequest().getBodyAsString();
            if (body == null || body.isBlank()) {
                return badRequest("Missing request body with requestId");
            }

            String targetId = null;
            String targetProject = "Recorded Traffic";
            RecordedStubConverter.SnapshotOptions options = RecordedStubConverter.SnapshotOptions.defaults();

            try {
                JsonNode root = objectMapper.readTree(body);
                if (root.has("requestId")) {
                    targetId = root.get("requestId").asText();
                }
                if (root.has("project") && !root.get("project").asText().isBlank()) {
                    String p = root.get("project").asText();
                    if (!"*".equals(p) && !"_all".equalsIgnoreCase(p)) {
                        targetProject = p;
                    }
                }
                options = parseSnapshotOptions(root);
            } catch (IOException e) {
                return badRequest("Failed to parse request JSON: " + e.getMessage());
            }

            if (targetId == null || targetId.isBlank()) {
                return badRequest("requestId is required");
            }

            ServeEvent foundEvent = null;
            for (ServeEvent event : admin.getServeEvents().getServeEvents()) {
                if (targetId.equals(event.getId().toString())) {
                    foundEvent = event;
                    break;
                }
            }

            if (foundEvent == null) {
                return notFound("Recorded request not found in journal for id: " + targetId);
            }

            StubMapping stub = RecordedStubConverter.convert(foundEvent, targetProject, options);
            if (stub == null) {
                return badRequest("Failed to convert recorded event into stub mapping (empty response)");
            }

            return ResponseDefinitionBuilder.responseDefinition()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(Json.write(stub))
                    .build();
        });

        // Filtered Request Snapshot: converts specific ServeEvents (by requestIds or all proxied events) into stubs
        router.add(RequestMethod.POST, "/stubs/snapshot-filtered", (admin, serveEvent, pathParams) -> {
            String body = serveEvent.getRequest().getBodyAsString();
            Set<String> targetIds = new HashSet<>();
            String targetProject = "Recorded Traffic";
            boolean activate = false;
            boolean onlyProxied = true;
            RecordedStubConverter.SnapshotOptions options = RecordedStubConverter.SnapshotOptions.defaults();

            if (body != null && !body.isBlank()) {
                try {
                    JsonNode root = objectMapper.readTree(body);
                    if (root.has("requestIds") && root.get("requestIds").isArray()) {
                        for (JsonNode idNode : root.get("requestIds")) {
                            targetIds.add(idNode.asText());
                        }
                    }
                    if (root.has("project") && !root.get("project").asText().isBlank()) {
                        String p = root.get("project").asText();
                        if (!"*".equals(p) && !"_all".equalsIgnoreCase(p)) {
                            targetProject = p;
                        }
                    }
                    if (root.has("activate")) {
                        activate = root.get("activate").asBoolean(false);
                    }
                    if (root.has("onlyProxied")) {
                        onlyProxied = root.get("onlyProxied").asBoolean(true);
                    }
                    options = parseSnapshotOptions(root);
                } catch (IOException e) {
                    return badRequest("Failed to parse request JSON: " + e.getMessage());
                }
            }

            List<ServeEvent> serveEvents = admin.getServeEvents().getServeEvents();
            List<StubMapping> createdStubs = new ArrayList<>();
            Set<String> seenSignatures = new HashSet<>();

            for (ServeEvent event : serveEvents) {
                if (event.getResponse() == null || event.getResponse().getStatus() <= 0) {
                    continue;
                }

                if (!targetIds.isEmpty() && !targetIds.contains(event.getId().toString())) {
                    continue;
                }

                StubMapping matchedStub = event.getStubMapping();
                boolean wasProxied = (matchedStub != null && isProxyStub(matchedStub))
                        || (event.getResponseDefinition() != null
                                && event.getResponseDefinition().getProxyBaseUrl() != null);

                if (onlyProxied && !wasProxied) {
                    continue;
                }

                String bodyHash = event.getRequest().getBody() != null
                        ? Integer.toHexString(Arrays.hashCode(event.getRequest().getBody()))
                        : "";
                String signature = event.getRequest().getMethod().getName() + ":"
                        + event.getRequest().getUrl() + ":"
                        + event.getResponse().getStatus() + ":"
                        + bodyHash;
                if (seenSignatures.contains(signature)) {
                    continue;
                }
                seenSignatures.add(signature);

                StubMapping recordedStub = RecordedStubConverter.convert(event, targetProject, options);
                if (recordedStub != null) {
                    if (activate) {
                        admin.addStubMapping(recordedStub);
                    } else {
                        disabledStubStore.put(recordedStub);
                    }
                    createdStubs.add(recordedStub);
                }
            }

            Map<String, Object> respBody = Map.of(
                    "project",
                    targetProject,
                    "totalRecorded",
                    createdStubs.size(),
                    "status",
                    activate ? "active" : "disabled",
                    "mappings",
                    createdStubs);
            return ok(respBody);
        });

        // Project Mode Toggle: Switches a project (or _all) between "proxy" and "stubs" mode
        router.add(RequestMethod.POST, "/projects/{project}/mode", (admin, serveEvent, pathParams) -> {
            String projectParam = pathParams.get("project");
            String targetProject = projectParam != null ? URLDecoder.decode(projectParam, StandardCharsets.UTF_8) : "";
            if (targetProject.isBlank()) {
                return badRequest("Project name is required");
            }
            boolean allProjects = "*".equals(targetProject) || "_all".equalsIgnoreCase(targetProject);

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
                    if ((allProjects || targetProject.equalsIgnoreCase(getStubProject(stub))) && isProxyStub(stub)) {
                        disabledStubStore.put(stub);
                        admin.removeStubMapping(stub.getId());
                        disabledCount++;
                    }
                }
                for (StubMapping stub : List.copyOf(disabledStubStore.getAll())) {
                    if ((allProjects || targetProject.equalsIgnoreCase(getStubProject(stub))) && !isProxyStub(stub)) {
                        disabledStubStore.remove(stub.getId());
                        admin.addStubMapping(stub);
                        enabledCount++;
                    }
                }
            } else if ("proxy".equals(requestedMode)) {
                // In "proxy" mode: disable static/recorded stubs, enable proxy stubs
                for (StubMapping stub : List.copyOf(admin.listAllStubMappings().getMappings())) {
                    if ((allProjects || targetProject.equalsIgnoreCase(getStubProject(stub))) && !isProxyStub(stub)) {
                        disabledStubStore.put(stub);
                        admin.removeStubMapping(stub.getId());
                        disabledCount++;
                    }
                }
                for (StubMapping stub : List.copyOf(disabledStubStore.getAll())) {
                    if ((allProjects || targetProject.equalsIgnoreCase(getStubProject(stub))) && isProxyStub(stub)) {
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

    private RecordedStubConverter.SnapshotOptions parseSnapshotOptions(JsonNode root) {
        if (root == null) {
            return RecordedStubConverter.SnapshotOptions.defaults();
        }
        Set<String> excluded = new HashSet<>(RecordedStubConverter.DEFAULT_EXCLUDED_JSON_PATHS);
        if (root.has("excludedJsonPaths") && root.get("excludedJsonPaths").isArray()) {
            for (JsonNode n : root.get("excludedJsonPaths")) {
                if (n.isTextual() && !n.asText().isBlank()) {
                    excluded.add(n.asText().trim());
                }
            }
        }
        boolean useEqualToJson =
                root.has("useEqualToJson") && root.get("useEqualToJson").asBoolean(false);
        return new RecordedStubConverter.SnapshotOptions(excluded, useEqualToJson);
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
