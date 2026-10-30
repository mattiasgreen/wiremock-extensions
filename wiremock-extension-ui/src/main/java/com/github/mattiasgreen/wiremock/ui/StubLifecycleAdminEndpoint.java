package com.github.mattiasgreen.wiremock.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Admin API Extension providing endpoints for stub lifecycle management:
 * enable, disable, toggle, bulk operations, snapshots, and querying disabled stubs.
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
            return ok(result);
        });

        // Disable a specific stub
        router.add(RequestMethod.POST, "/stubs/{id}/disable", (admin, serveEvent, pathParams) -> {
            UUID id = parseUuid(pathParams.get("id"));
            if (id == null) return badRequest("Invalid UUID format");

            DisabledStubStore.TransitionResult res = disabledStubStore.disable(admin, id);
            if (!res.found()) return notFound("Stub not found");
            return ok(Map.of("status", res.status(), "id", res.id().toString(), "name", res.name()));
        });

        // Enable a specific stub
        router.add(RequestMethod.POST, "/stubs/{id}/enable", (admin, serveEvent, pathParams) -> {
            UUID id = parseUuid(pathParams.get("id"));
            if (id == null) return badRequest("Invalid UUID format");

            DisabledStubStore.TransitionResult res = disabledStubStore.enable(admin, id);
            if (!res.found()) return notFound("Disabled stub not found");
            return ok(Map.of("status", res.status(), "id", res.id().toString(), "name", res.name()));
        });

        // Toggle stub active <-> disabled
        router.add(RequestMethod.POST, "/stubs/{id}/toggle", (admin, serveEvent, pathParams) -> {
            UUID id = parseUuid(pathParams.get("id"));
            if (id == null) return badRequest("Invalid UUID format");

            DisabledStubStore.TransitionResult res = disabledStubStore.toggle(admin, id);
            if (!res.found()) return notFound("Stub not found");
            return ok(Map.of("status", res.status(), "id", res.id().toString(), "name", res.name()));
        });

        // Edit / Upsert stub mapping
        router.add(RequestMethod.PUT, "/stubs/{id}", (admin, serveEvent, pathParams) -> {
            UUID id = parseUuid(pathParams.get("id"));
            if (id == null) return badRequest("Invalid UUID format");

            String body = serveEvent.getRequest().getBodyAsString();
            if (body == null || body.isBlank()) return badRequest("Request body is required");

            StubMapping updatedStub;
            try {
                updatedStub = Json.read(body, StubMapping.class);
            } catch (Exception e) {
                return badRequest("Failed to deserialize StubMapping: " + e.getMessage());
            }

            return ok(disabledStubStore.upsert(admin, id, updatedStub));
        });

        // Delete a stub mapping by ID
        router.add(RequestMethod.DELETE, "/stubs/{id}", (admin, serveEvent, pathParams) -> {
            UUID id = parseUuid(pathParams.get("id"));
            if (id == null) return badRequest("Invalid UUID format");

            disabledStubStore.delete(admin, id);
            return ok(Map.of("status", "deleted", "id", id.toString()));
        });

        // Bulk operations: enable, disable, delete
        router.add(RequestMethod.POST, "/stubs/bulk", (admin, serveEvent, pathParams) -> {
            String body = serveEvent.getRequest().getBodyAsString();
            if (body == null || body.isBlank()) return badRequest("Request body is required");

            try {
                JsonNode root = objectMapper.readTree(body);
                String action = root.path("action").asText("");
                JsonNode idsNode = root.path("ids");
                if (!idsNode.isArray()) return badRequest("Missing or invalid 'ids' array");

                List<UUID> ids = new ArrayList<>();
                for (JsonNode idNode : idsNode) {
                    try {
                        ids.add(UUID.fromString(idNode.asText()));
                    } catch (IllegalArgumentException ignored) {
                    }
                }

                int count = disabledStubStore.bulkApply(admin, action, ids);
                return ok(Map.of("action", action, "processed", count));
            } catch (IllegalArgumentException e) {
                return badRequest(e.getMessage());
            } catch (IOException e) {
                return badRequest("Failed to parse request: " + e.getMessage());
            }
        });

        // Project Recording Snapshot: converts proxied journal traffic into DISABLED stubs grouped to project
        router.add(RequestMethod.POST, "/projects/{project}/recordings/snapshot", (admin, serveEvent, pathParams) -> {
            String projectParam = pathParams.get("project");
            String targetProject = projectParam != null ? URLDecoder.decode(projectParam, StandardCharsets.UTF_8) : "";
            boolean allProjects =
                    targetProject.isBlank() || "*".equals(targetProject) || "_all".equalsIgnoreCase(targetProject);

            RecordedStubConverter.SnapshotOptions options =
                    parseSnapshotOptions(serveEvent.getRequest().getBodyAsString());
            List<ServeEvent> serveEvents = admin.getServeEvents().getServeEvents();
            List<StubMapping> createdStubs = disabledStubStore.snapshotServeEvents(
                    admin, serveEvents, null, targetProject, allProjects, true, true, false, options);

            return ok(Map.of(
                    "project",
                    targetProject,
                    "totalRecorded",
                    createdStubs.size(),
                    "status",
                    "disabled",
                    "mappings",
                    createdStubs));
        });

        // Generate Snapshot Stub Template: converts a single ServeEvent into an editable StubMapping definition
        router.add(RequestMethod.POST, "/stubs/snapshot-template", (admin, serveEvent, pathParams) -> {
            String body = serveEvent.getRequest().getBodyAsString();
            if (body == null || body.isBlank()) return badRequest("Missing request body with requestId");

            String targetId = null;
            String targetProject = "Recorded Traffic";
            RecordedStubConverter.SnapshotOptions options = RecordedStubConverter.SnapshotOptions.defaults();

            try {
                JsonNode root = objectMapper.readTree(body);
                if (root.has("requestId")) targetId = root.get("requestId").asText();
                if (root.has("project") && !root.get("project").asText().isBlank()) {
                    String p = root.get("project").asText();
                    if (!"*".equals(p) && !"_all".equalsIgnoreCase(p)) targetProject = p;
                }
                options = parseSnapshotOptions(root);
            } catch (IOException e) {
                return badRequest("Failed to parse request JSON: " + e.getMessage());
            }

            if (targetId == null || targetId.isBlank()) return badRequest("requestId is required");

            ServeEvent foundEvent = null;
            for (ServeEvent event : admin.getServeEvents().getServeEvents()) {
                if (targetId.equals(event.getId().toString())) {
                    foundEvent = event;
                    break;
                }
            }

            if (foundEvent == null) return notFound("Recorded request not found in journal for id: " + targetId);

            StubMapping stub = RecordedStubConverter.convert(foundEvent, targetProject, options);
            if (stub == null) return badRequest("Failed to convert recorded event into stub mapping (empty response)");

            return ok(stub);
        });

        // Filtered Request Snapshot: converts specific ServeEvents into stubs
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
            List<StubMapping> createdStubs = disabledStubStore.snapshotServeEvents(
                    admin, serveEvents, targetIds, targetProject, false, false, onlyProxied, activate, options);

            return ok(Map.of(
                    "project",
                    targetProject,
                    "totalRecorded",
                    createdStubs.size(),
                    "status",
                    activate ? "active" : "disabled",
                    "mappings",
                    createdStubs));
        });

        // Project Mode Toggle: Switches a project (or _all) between "proxy" and "stubs" mode
        router.add(RequestMethod.POST, "/projects/{project}/mode", (admin, serveEvent, pathParams) -> {
            String projectParam = pathParams.get("project");
            String targetProject = projectParam != null ? URLDecoder.decode(projectParam, StandardCharsets.UTF_8) : "";
            if (targetProject.isBlank()) return badRequest("Project name is required");

            String requestedMode = "stubs";
            String body = serveEvent.getRequest().getBodyAsString();
            if (body != null && !body.isBlank()) {
                try {
                    JsonNode root = objectMapper.readTree(body);
                    if (root.has("mode")) {
                        requestedMode = root.get("mode").asText("stubs").toLowerCase(Locale.ROOT);
                    }
                } catch (IOException ignored) {
                }
            }

            try {
                DisabledStubStore.ProjectModeResult res =
                        disabledStubStore.switchProjectMode(admin, targetProject, requestedMode);
                return ok(Map.of(
                        "project", res.project(),
                        "activeMode", res.activeMode(),
                        "stubsEnabled", res.stubsEnabled(),
                        "stubsDisabled", res.stubsDisabled()));
            } catch (IllegalArgumentException e) {
                return badRequest(e.getMessage());
            }
        });
    }

    private static UUID parseUuid(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private RecordedStubConverter.SnapshotOptions parseSnapshotOptions(String body) {
        if (body == null || body.isBlank()) {
            return RecordedStubConverter.SnapshotOptions.defaults();
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            return parseSnapshotOptions(root);
        } catch (IOException ignored) {
            return RecordedStubConverter.SnapshotOptions.defaults();
        }
    }

    private RecordedStubConverter.SnapshotOptions parseSnapshotOptions(JsonNode root) {
        if (root == null) return RecordedStubConverter.SnapshotOptions.defaults();
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
