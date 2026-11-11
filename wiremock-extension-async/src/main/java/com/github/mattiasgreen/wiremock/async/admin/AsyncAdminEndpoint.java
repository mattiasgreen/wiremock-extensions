package com.github.mattiasgreen.wiremock.async.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mattiasgreen.wiremock.async.connection.AsyncBrokerRegistry;
import com.github.mattiasgreen.wiremock.async.connection.BrokerConnection;
import com.github.mattiasgreen.wiremock.async.destination.AsyncDestination;
import com.github.mattiasgreen.wiremock.async.destination.AsyncDestinationMessage;
import com.github.mattiasgreen.wiremock.async.destination.AsyncDestinationService;
import com.github.mattiasgreen.wiremock.async.engine.AsyncEngine;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import java.time.Instant;
import java.util.HashMap;
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
    private final AsyncDestinationService destinationService;

    public AsyncAdminEndpoint() {
        this(AsyncEngine.getInstance(), AsyncBrokerRegistry.getInstance(), AsyncDestinationService.getInstance());
    }

    public AsyncAdminEndpoint(AsyncEngine engine) {
        this(engine, AsyncBrokerRegistry.getInstance(), AsyncDestinationService.getInstance());
    }

    public AsyncAdminEndpoint(AsyncEngine engine, AsyncBrokerRegistry brokerRegistry) {
        this(engine, brokerRegistry, AsyncDestinationService.getInstance());
    }

    public AsyncAdminEndpoint(
            AsyncEngine engine, AsyncBrokerRegistry brokerRegistry, AsyncDestinationService destinationService) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.brokerRegistry = Objects.requireNonNull(brokerRegistry, "brokerRegistry must not be null");
        this.destinationService = Objects.requireNonNull(destinationService, "destinationService must not be null");
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
            resp.put("journalCount", engine.getJournal().size());
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
                Map<String, Object> resp = Map.of(
                        "count", engine.getJournal().size(),
                        "journal", engine.getJournal());
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

                String destination =
                        json.has("destination") ? json.get("destination").asText() : "";
                String protocol = json.has("protocol") ? json.get("protocol").asText() : "kafka";
                String key = json.has("key") ? json.get("key").asText() : "";
                String payload = json.has("payload") ? json.get("payload").asText() : "";
                String correlationId =
                        json.has("correlationId") ? json.get("correlationId").asText() : "";
                String replyTo = json.has("replyTo") ? json.get("replyTo").asText() : "";

                Map<String, String> headers = new HashMap<>();
                if (json.has("headers") && json.get("headers").isObject()) {
                    json.get("headers")
                            .fields()
                            .forEachRemaining(entry ->
                                    headers.put(entry.getKey(), entry.getValue().asText()));
                }

                String msgId = UUID.randomUUID().toString();
                AsyncMessage message = AsyncMessage.builder()
                        .id(msgId)
                        .correlationId(correlationId)
                        .replyTo(replyTo)
                        .destination(destination)
                        .protocol(protocol)
                        .key(key)
                        .payload(payload)
                        .headers(headers)
                        .timestamp(Instant.now())
                        .build();

                engine.publish(destination, message);

                Map<String, Object> resp = Map.of(
                        "published", true,
                        "id", msgId,
                        "destination", destination,
                        "protocol", protocol);

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
                String id = node.hasNonNull("id") ? node.get("id").asText().trim() : "";
                if (id.isEmpty()) {
                    return ResponseDefinitionBuilder.responseDefinition()
                            .withStatus(400)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"error\":\"id is required\"}")
                            .build();
                }

                String protocol = node.hasNonNull("protocol")
                        ? node.get("protocol").asText().toLowerCase(java.util.Locale.ROOT)
                        : "kafka";
                String name = node.hasNonNull("name") ? node.get("name").asText() : id;
                String endpoint =
                        node.hasNonNull("endpoint") ? node.get("endpoint").asText() : "";

                Map<String, String> properties = new HashMap<>();
                if (node.hasNonNull("properties") && node.get("properties").isObject()) {
                    node.get("properties")
                            .fields()
                            .forEachRemaining(entry -> properties.put(
                                    entry.getKey(), entry.getValue().asText()));
                }

                BrokerConnection connection = new BrokerConnection(id, protocol, name, endpoint, properties);
                brokerRegistry.register(connection);

                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(MAPPER.writeValueAsString(brokerRegistry.exportSanitized(id)))
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
                List<AsyncDestination> destinations = destinationService.listAllDestinations();
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
                        List<AsyncDestinationMessage> messages = destinationService.peekMessages(protocol, name, 50);
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
                    boolean deleted = destinationService.deleteMessage(protocol, name, id);
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
            destinationService.purgeDestination(protocol, name);
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

                        String key = json.has("key") ? json.get("key").asText() : "";
                        String payload =
                                json.has("payload") ? json.get("payload").asText() : "";
                        String correlationId = json.has("correlationId")
                                ? json.get("correlationId").asText()
                                : "";
                        String replyTo =
                                json.has("replyTo") ? json.get("replyTo").asText() : "";

                        Map<String, String> headers = new HashMap<>();
                        if (json.has("headers") && json.get("headers").isObject()) {
                            json.get("headers")
                                    .fields()
                                    .forEachRemaining(entry -> headers.put(
                                            entry.getKey(), entry.getValue().asText()));
                        }

                        String id = json.has("id")
                                ? json.get("id").asText()
                                : UUID.randomUUID().toString();
                        AsyncDestinationMessage msg = new AsyncDestinationMessage(
                                id,
                                protocol,
                                name,
                                key,
                                correlationId,
                                replyTo,
                                Instant.now(),
                                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                                headers,
                                payload,
                                null,
                                null);

                        boolean published = destinationService.publishMessage(protocol, name, msg);
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
}
