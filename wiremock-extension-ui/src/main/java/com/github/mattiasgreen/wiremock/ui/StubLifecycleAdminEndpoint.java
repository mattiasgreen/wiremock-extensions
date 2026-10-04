package com.github.mattiasgreen.wiremock.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.admin.model.SingleStubMappingResult;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
                        switch (action.toLowerCase()) {
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
                    } catch (Exception ignored) {
                    }
                }

                return ok(Map.of("action", action, "processed", count));
            } catch (Exception e) {
                return badRequest("Failed to parse request: " + e.getMessage());
            }
        });
    }

    private static com.github.tomakehurst.wiremock.http.ResponseDefinition ok(Map<String, ?> body) {
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
