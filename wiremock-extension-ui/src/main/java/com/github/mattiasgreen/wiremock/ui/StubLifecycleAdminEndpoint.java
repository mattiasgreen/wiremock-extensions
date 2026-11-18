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
import java.util.*;

/**
 * Admin API Extension providing priority-tier stub lifecycle extensions:
 * capability status probe and serve event snapshot conversions.
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

        // Unified Recording Snapshot: converts journal traffic (by requestIds or project scope) into stubs
        router.add(RequestMethod.POST, "/lifecycle/snapshots", (admin, serveEvent, pathParams) -> {
            String body = serveEvent.getRequest().getBodyAsString();
            if (body == null || body.isBlank()) {
                StubLifecycleService.SnapshotCommand cmd = StubLifecycleService.SnapshotCommand.forProject(
                        "_all", RecordedStubConverter.SnapshotOptions.defaults());
                List<StubMapping> createdStubs = service.withAdmin(admin).snapshotRecordings(cmd);
                return ok(Map.of(
                        "project",
                        "_all",
                        "totalRecorded",
                        createdStubs.size(),
                        "status",
                        "disabled",
                        "mappings",
                        createdStubs));
            }

            try {
                JsonNode root = objectMapper.readTree(body);
                boolean activate = root.has("activate") && root.get("activate").asBoolean(false);
                RecordedStubConverter.SnapshotOptions options = parseSnapshotOptions(root);

                String targetProject;
                StubLifecycleService.SnapshotCommand cmd;
                if (root.hasNonNull("requestIds") && root.get("requestIds").isArray()) {
                    Set<String> targetIds = new HashSet<>();
                    for (JsonNode idNode : root.get("requestIds")) {
                        targetIds.add(idNode.asText());
                    }
                    targetProject = "Recorded Traffic";
                    if (root.hasNonNull("project")
                            && !root.get("project").asText().isBlank()) {
                        String p = root.get("project").asText().trim();
                        if (!"*".equals(p) && !"_all".equalsIgnoreCase(p)) {
                            targetProject = p;
                        }
                    }
                    boolean onlyProxied =
                            !root.has("onlyProxied") || root.get("onlyProxied").asBoolean(true);
                    cmd = StubLifecycleService.SnapshotCommand.forRequests(
                            targetIds, targetProject, activate, onlyProxied, options);
                } else {
                    String rawProject =
                            root.hasNonNull("project") ? root.get("project").asText("") : "";
                    targetProject = rawProject.isBlank() ? "_all" : rawProject.trim();
                    cmd = StubLifecycleService.SnapshotCommand.forProject(targetProject, activate, options);
                }

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
            } catch (IOException e) {
                return badRequest("Failed to parse request JSON: " + e.getMessage());
            }
        });

        // Generate Snapshot Stub Template: converts a single ServeEvent into an editable StubMapping definition
        router.add(RequestMethod.POST, "/lifecycle/snapshot-template", (admin, serveEvent, pathParams) -> {
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
