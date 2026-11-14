package com.github.mattiasgreen.wiremock.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Admin API Extension providing priority-tier stub lifecycle extensions:
 * capability status probe, project proxy/stubs mode switching, and serve event snapshot conversions.
 *
 * <p>Individual stub parking/activation and bulk updates use WireMock's native {@code /__admin/mappings/{id}} API.
 * Acts as a thin HTTP adapter mapping Admin API routes to {@link StubLifecycleService}.
 */
public class StubLifecycleAdminEndpoint implements AdminApiExtension {

    public static final String EXTENSION_NAME = "stub-lifecycle-admin-endpoint";
    private final StubLifecycleService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StubLifecycleAdminEndpoint(StubLifecycleService service) {
        this.service = Objects.requireNonNull(service, "service must not be null");
    }

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public void contributeAdminApiRoutes(Router router) {
        // Capability health probe used by the Web UI to detect lifecycle extension availability
        router.add(
                RequestMethod.GET, "/lifecycle/status", (admin, serveEvent, pathParams) -> ok(Map.of("status", "UP")));

        // Project Recording Snapshot: converts proxied journal traffic into parked (priority 100) stubs grouped to
        // project
        router.add(RequestMethod.POST, "/projects/{project}/recordings/snapshot", (admin, serveEvent, pathParams) -> {
            String projectParam = pathParams.get("project");
            String targetProject = projectParam != null ? URLDecoder.decode(projectParam, StandardCharsets.UTF_8) : "";

            RecordedStubConverter.SnapshotOptions options =
                    parseSnapshotOptions(serveEvent.getRequest().getBodyAsString());
            StubLifecycleService.SnapshotCommand cmd =
                    StubLifecycleService.SnapshotCommand.forProject(targetProject, options);
            List<StubMapping> createdStubs = service.withAdmin(admin).snapshotRecordings(cmd);

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

            Optional<StubMapping> stub =
                    service.withAdmin(admin).generateSnapshotTemplate(targetId, targetProject, options);
            if (stub.isEmpty()) return notFound("Recorded request not found in journal for id: " + targetId);

            return ok(stub.get());
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

            StubLifecycleService.SnapshotCommand cmd = StubLifecycleService.SnapshotCommand.forRequests(
                    targetIds, targetProject, activate, onlyProxied, options);
            List<StubMapping> createdStubs = service.withAdmin(admin).snapshotRecordings(cmd);

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
                StubLifecycleService.ProjectModeResult res =
                        service.withAdmin(admin).switchProjectMode(targetProject, requestedMode);
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
