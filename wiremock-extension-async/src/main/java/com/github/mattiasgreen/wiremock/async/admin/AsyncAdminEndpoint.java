package com.github.mattiasgreen.wiremock.async.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mattiasgreen.wiremock.async.connection.AsyncBrokerRegistry;
import com.github.mattiasgreen.wiremock.async.destination.AsyncDestination;
import com.github.mattiasgreen.wiremock.async.engine.AsyncEngine;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.Admin;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * AdminApiExtension contributing Admin API routes for asynchronous messaging status,
 * in-process Kafka wire sink management, and synthetic message publishing.
 *
 * <p><b>Note:</b> This is Beta functionality subject to evolution.
 */
public class AsyncAdminEndpoint implements AdminApiExtension {

    public static final String EXTENSION_NAME = "async-admin-endpoint";
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private final AsyncEngine engine;
    private final AsyncBrokerRegistry brokerRegistry;

    public AsyncAdminEndpoint() {
        this(AsyncEngine.getInstance(), AsyncBrokerRegistry.getInstance());
    }

    public AsyncAdminEndpoint(AsyncEngine engine) {
        this(engine, AsyncBrokerRegistry.getInstance());
    }

    public AsyncAdminEndpoint(AsyncEngine engine, AsyncBrokerRegistry brokerRegistry) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.brokerRegistry = Objects.requireNonNull(brokerRegistry, "brokerRegistry must not be null");
    }

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public void contributeAdminApiRoutes(Router router) {
        // GET /__admin/async/status
        router.add(RequestMethod.GET, "/async/status", (admin, serveEvent, pathParams) -> {
            Map<String, Object> resp = new HashMap<>();
            resp.put("status", "UP");
            resp.put("beta", true);
            resp.put("kafkaSinkRunning", engine.isKafkaSinkRunning());
            resp.put("kafkaSinkPort", engine.getKafkaSinkPort());
            resp.put("messagesConsumed", engine.getMessagesConsumed());
            resp.put("messagesProduced", engine.getMessagesProduced());
            resp.put("messagesProxied", engine.getMessagesProxied());
            resp.put("journalCount", buildAsyncJournalSummary(admin).size());
            resp.put("harvestedCount", engine.getHarvestedStubs().size());

            try {
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(MAPPER.writeValueAsString(resp))
                        .build();
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(500)
                        .build();
            }
        });

        // GET /__admin/async/journal
        router.add(RequestMethod.GET, "/async/journal", (admin, serveEvent, pathParams) -> {
            try {
                List<String> journal = buildAsyncJournalSummary(admin);
                Map<String, Object> resp = Map.of("count", journal.size(), "journal", journal);
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(MAPPER.writeValueAsString(resp))
                        .build();
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(500)
                        .build();
            }
        });

        // GET /__admin/async/harvested - Returns live wire messages harvested by the proxy bridge
        router.add(RequestMethod.GET, "/async/harvested", (admin, serveEvent, pathParams) -> {
            try {
                Map<String, Object> resp = Map.of(
                        "count", engine.getHarvestedStubs().size(),
                        "harvested", engine.getHarvestedStubs());
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(MAPPER.writeValueAsString(resp))
                        .build();
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(500)
                        .build();
            }
        });

        // GET /__admin/async/stubs (Deprecated alias for /async/harvested; registered stubs live under
        // /__admin/mappings)
        router.add(RequestMethod.GET, "/async/stubs", (admin, serveEvent, pathParams) -> {
            try {
                Map<String, Object> resp = Map.of(
                        "count", engine.getHarvestedStubs().size(),
                        "stubs", engine.getHarvestedStubs());
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(MAPPER.writeValueAsString(resp))
                        .build();
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(500)
                        .build();
            }
        });

        // POST /__admin/async/publish
        router.add(RequestMethod.POST, "/async/publish", (admin, serveEvent, pathParams) -> {
            try {
                String reqBody = serveEvent.getRequest().getBodyAsString();
                JsonNode json = MAPPER.readTree(reqBody);
                AsyncMessage message = parseAsyncMessage(json, "kafka", "");

                engine.publish(message.destination(), message);

                Map<String, Object> resp = Map.of(
                        "published", true,
                        "id", message.id(),
                        "destination", message.destination(),
                        "protocol", message.protocol());

                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(MAPPER.writeValueAsString(resp))
                        .build();
            } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"" + e.getMessage() + "\"}")
                        .build();
            }
        });

        // POST /__admin/async/sinks/kafka
        router.add(RequestMethod.POST, "/async/sinks/kafka", (admin, serveEvent, pathParams) -> {
            try {
                String reqBody = serveEvent.getRequest().getBodyAsString();
                JsonNode json = MAPPER.readTree(reqBody);
                String action = json.has("action") ? json.get("action").asText() : "start";
                int port = json.has("port") ? json.get("port").asInt() : 0;

                if ("stop".equalsIgnoreCase(action)) {
                    engine.stopKafkaSink();
                } else {
                    engine.startKafkaSink(port, null);
                }

                Map<String, Object> resp = Map.of(
                        "kafkaSinkRunning", engine.isKafkaSinkRunning(),
                        "kafkaSinkPort", engine.getKafkaSinkPort());

                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(MAPPER.writeValueAsString(resp))
                        .build();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Kafka sink operation interrupted\"}")
                        .build();
            } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"" + e.getMessage() + "\"}")
                        .build();
            }
        });

        // GET /__admin/async/connections
        router.add(RequestMethod.GET, "/async/connections", (admin, serveEvent, pathParams) -> {
            try {
                List<Map<String, Object>> list = brokerRegistry.listSanitized();
                Map<String, Object> resp = Map.of("count", list.size(), "connections", list);
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(MAPPER.writeValueAsString(resp))
                        .build();
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(500)
                        .build();
            }
        });

        // POST /__admin/async/connections
        router.add(RequestMethod.POST, "/async/connections", (admin, serveEvent, pathParams) -> {
            try {
                String reqBody = serveEvent.getRequest().getBodyAsString();
                JsonNode node = MAPPER.readTree(reqBody);
                List<com.github.mattiasgreen.wiremock.async.connection.BrokerConnection> registered =
                        brokerRegistry.registerFromJson(node);
                if (registered.isEmpty()) {
                    return ResponseDefinitionBuilder.responseDefinition()
                            .withStatus(400)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"error\":\"No valid connection objects found (id is required)\"}")
                            .build();
                }

                List<Map<String, Object>> sanitizedList = new ArrayList<>();
                for (var c : registered) {
                    sanitizedList.add(brokerRegistry.exportSanitized(c.id()));
                }

                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("count", sanitizedList.size());
                resp.put("connections", sanitizedList);

                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(MAPPER.writeValueAsString(resp))
                        .build();
            } catch (java.io.IOException | IllegalArgumentException e) {
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"" + e.getMessage() + "\"}")
                        .build();
            }
        });

        // DELETE /__admin/async/connections/{id}
        router.add(RequestMethod.DELETE, "/async/connections/{id}", (admin, serveEvent, pathParams) -> {
            String id = pathParams.get("id");
            boolean removed = brokerRegistry.remove(id);
            if (removed) {
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(204)
                        .build();
            } else {
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Connection not found: " + id + "\"}")
                        .build();
            }
        });

        // GET /__admin/async/destinations
        router.add(RequestMethod.GET, "/async/destinations", (admin, serveEvent, pathParams) -> {
            try {
                List<AsyncDestination> destinations = engine.listAllDestinations();
                Map<String, Object> resp = Map.of("count", destinations.size(), "destinations", destinations);
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(MAPPER.writeValueAsString(resp))
                        .build();
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(500)
                        .build();
            }
        });

        // GET /__admin/async/destinations/{protocol}/{name}/messages
        router.add(
                RequestMethod.GET,
                "/async/destinations/{protocol}/{name}/messages",
                (admin, serveEvent, pathParams) -> {
                    try {
                        String protocol = pathParams.get("protocol");
                        String name = pathParams.get("name");
                        List<AsyncMessage> messages = engine.peekMessages(protocol, name, 50);
                        Map<String, Object> resp = Map.of(
                                "protocol", protocol,
                                "destination", name,
                                "count", messages.size(),
                                "messages", messages);
                        return ResponseDefinitionBuilder.responseDefinition()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(MAPPER.writeValueAsString(resp))
                                .build();
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        return ResponseDefinitionBuilder.responseDefinition()
                                .withStatus(500)
                                .build();
                    }
                });

        // DELETE /__admin/async/destinations/{protocol}/{name}/messages/{id}
        router.add(
                RequestMethod.DELETE,
                "/async/destinations/{protocol}/{name}/messages/{id}",
                (admin, serveEvent, pathParams) -> {
                    String protocol = pathParams.get("protocol");
                    String name = pathParams.get("name");
                    String id = pathParams.get("id");
                    boolean deleted = engine.deleteMessage(protocol, name, id);
                    if (deleted) {
                        return ResponseDefinitionBuilder.responseDefinition()
                                .withStatus(204)
                                .build();
                    } else {
                        return ResponseDefinitionBuilder.responseDefinition()
                                .withStatus(404)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"error\":\"Message not found: " + id + "\"}")
                                .build();
                    }
                });

        // DELETE /__admin/async/destinations/{protocol}/{name}
        router.add(RequestMethod.DELETE, "/async/destinations/{protocol}/{name}", (admin, serveEvent, pathParams) -> {
            String protocol = pathParams.get("protocol");
            String name = pathParams.get("name");
            engine.purgeDestination(protocol, name);
            return ResponseDefinitionBuilder.responseDefinition()
                    .withStatus(204)
                    .build();
        });

        // POST /__admin/async/destinations/{protocol}/{name}/messages
        router.add(
                RequestMethod.POST,
                "/async/destinations/{protocol}/{name}/messages",
                (admin, serveEvent, pathParams) -> {
                    try {
                        String protocol = pathParams.get("protocol");
                        String name = pathParams.get("name");
                        String reqBody = serveEvent.getRequest().getBodyAsString();
                        JsonNode json = MAPPER.readTree(reqBody);
                        AsyncMessage msg = parseAsyncMessage(json, protocol, name);

                        boolean published = engine.publishMessage(protocol, name, msg);
                        if (published) {
                            return ResponseDefinitionBuilder.responseDefinition()
                                    .withStatus(201)
                                    .withHeader("Content-Type", "application/json")
                                    .withBody(MAPPER.writeValueAsString(msg))
                                    .build();
                        } else {
                            return ResponseDefinitionBuilder.responseDefinition()
                                    .withStatus(400)
                                    .withHeader("Content-Type", "application/json")
                                    .withBody("{\"error\":\"Failed to publish message to " + protocol + "/" + name
                                            + "\"}")
                                    .build();
                        }
                    } catch (java.io.IOException | IllegalArgumentException e) {
                        return ResponseDefinitionBuilder.responseDefinition()
                                .withStatus(400)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"error\":\"" + e.getMessage() + "\"}")
                                .build();
                    }
                });
    }

    private static AsyncMessage parseAsyncMessage(JsonNode json, String defaultProtocol, String defaultDestination) {
        String id = json.hasNonNull("id")
                ? json.get("id").asText()
                : UUID.randomUUID().toString();
        String destination = json.hasNonNull("destination") && !defaultDestination.isEmpty()
                ? defaultDestination
                : (json.hasNonNull("destination") ? json.get("destination").asText() : defaultDestination);
        String protocol = json.hasNonNull("protocol") && defaultDestination.isEmpty()
                ? json.get("protocol").asText()
                : defaultProtocol;
        String key = json.hasNonNull("key") ? json.get("key").asText() : "";
        String payload = json.hasNonNull("payload") ? json.get("payload").asText() : "";
        String correlationId =
                json.hasNonNull("correlationId") ? json.get("correlationId").asText() : "";
        String replyTo = json.hasNonNull("replyTo") ? json.get("replyTo").asText() : "";

        Map<String, String> headers = new HashMap<>();
        if (json.has("headers") && json.get("headers").isObject()) {
            json.get("headers")
                    .fields()
                    .forEachRemaining(entry ->
                            headers.put(entry.getKey(), entry.getValue().asText()));
        }

        return AsyncMessage.builder()
                .id(id)
                .protocol(protocol)
                .destination(destination)
                .key(key)
                .correlationId(correlationId)
                .replyTo(replyTo)
                .payload(payload)
                .headers(headers)
                .timestamp(Instant.now())
                .build();
    }

    private static List<String> buildAsyncJournalSummary(Admin admin) {
        if (admin == null
                || admin.getServeEvents() == null
                || admin.getServeEvents().getServeEvents() == null) {
            return List.of();
        }
        List<ServeEvent> events = admin.getServeEvents().getServeEvents();
        List<String> summary = new ArrayList<>();
        for (ServeEvent se : events) {
            if (!isAsyncServeEvent(se)) {
                continue;
            }
            String method = se.getRequest().getMethod() != null
                    ? se.getRequest().getMethod().getName()
                    : "ASYNC";
            String url = se.getRequest().getUrl() != null ? se.getRequest().getUrl() : "";
            int status = 200;
            if (se.getResponseDefinition() != null) {
                status = se.getResponseDefinition().getStatus();
            } else if (se.getResponse() != null) {
                status = se.getResponse().getStatus();
            }
            summary.add(method + ":" + status + ":" + url);
        }
        return Collections.unmodifiableList(summary);
    }

    private static boolean isAsyncServeEvent(ServeEvent se) {
        if (se == null || se.getRequest() == null) {
            return false;
        }
        String url = se.getRequest().getUrl();
        if (url != null && url.startsWith("/__async/")) {
            return true;
        }
        return se.getRequest().getHeaders() != null
                && (se.getRequest()
                                .getHeaders()
                                .getHeader("X-WireMock-Async-Protocol")
                                .isPresent()
                        || se.getRequest()
                                .getHeaders()
                                .getHeader("X-Async-Destination")
                                .isPresent());
    }
}
