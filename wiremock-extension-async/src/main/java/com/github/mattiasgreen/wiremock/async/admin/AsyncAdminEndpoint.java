package com.github.mattiasgreen.wiremock.async.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mattiasgreen.wiremock.async.engine.AsyncEngine;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import java.time.Instant;
import java.util.HashMap;
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
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AsyncEngine engine;

    public AsyncAdminEndpoint() {
        this(AsyncEngine.getInstance());
    }

    public AsyncAdminEndpoint(AsyncEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
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

        // GET /__admin/async/stubs
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
    }
}
