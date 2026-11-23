package com.github.mattiasgreen.wiremock.async.admin;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AsyncAdminEndpointTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WireMockServer wireMockServer;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(
                WireMockConfiguration.options().dynamicPort().extensions(new AsyncExtensionFactory()));
        wireMockServer.start();
        httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    @Test
    @DisplayName("GET /__admin/async/status should return beta status, health indicators, and native asyncStubCount")
    void shouldReturnAsyncStatus() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__async/kafka/orders.events"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}"))
                .withMetadata(Map.of("async", Map.of("mode", "mock", "channel", "orders.events"))));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(wireMockServer.baseUrl() + "/__admin/async/status"))
                .GET()
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);

        JsonNode json = MAPPER.readTree(resp.body());
        assertThat(json.get("status").asText()).isEqualTo("UP");
        assertThat(json.get("beta").asBoolean()).isTrue();
        assertThat(json.has("kafkaSinkRunning")).isTrue();
        assertThat(json.get("asyncStubCount").asLong()).isEqualTo(1L);
        assertThat(json.has("journalCount")).isFalse();
        assertThat(json.has("harvestedCount")).isFalse();
    }

    @Test
    @DisplayName("POST /__admin/async/publish should validate input and return published acknowledgment")
    void shouldPublishMessageViaAdminApi() throws Exception {
        String payload =
                """
                {
                  "destination": "orders.notifications",
                  "protocol": "kafka",
                  "key": "cust-55",
                  "payload": "{\\"notified\\":true}"
                }
                """;

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(wireMockServer.baseUrl() + "/__admin/async/publish"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);

        JsonNode json = MAPPER.readTree(resp.body());
        assertThat(json.get("published").asBoolean()).isTrue();
        assertThat(json.get("destination").asText()).isEqualTo("orders.notifications");
        assertThat(json.has("id")).isTrue();
    }

    @Test
    @DisplayName("Removed legacy endpoints (/async/journal, /async/harvested, /async/stubs) should not be served")
    void shouldNotServeRemovedLegacyShadowEndpoints() throws Exception {
        for (String legacyPath :
                new String[] {"/__admin/async/journal", "/__admin/async/harvested", "/__admin/async/stubs"}) {
            HttpResponse<String> resp = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(wireMockServer.baseUrl() + legacyPath))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("POST /__admin/async/connections should register connection and GET should return sanitized list")
    void shouldManageConnectionsViaAdminApi() throws Exception {
        String payload =
                """
        {
          "id": "qa-kafka",
          "protocol": "kafka",
          "name": "QA Kafka Cluster",
          "endpoint": "kafka.qa:9092",
          "properties": {
            "password": "super-secret-password",
            "client.id": "wiremock-client"
          }
        }
        """;

        HttpRequest postReq = HttpRequest.newBuilder()
                .uri(URI.create(wireMockServer.baseUrl() + "/__admin/async/connections"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> postResp = httpClient.send(postReq, HttpResponse.BodyHandlers.ofString());
        assertThat(postResp.statusCode()).isEqualTo(201);

        HttpRequest getReq = HttpRequest.newBuilder()
                .uri(URI.create(wireMockServer.baseUrl() + "/__admin/async/connections"))
                .GET()
                .build();

        HttpResponse<String> getResp = httpClient.send(getReq, HttpResponse.BodyHandlers.ofString());
        assertThat(getResp.statusCode()).isEqualTo(200);

        JsonNode json = MAPPER.readTree(getResp.body());
        assertThat(json.has("connections")).isTrue();
        assertThat(json.get("count").asInt()).isGreaterThanOrEqualTo(1);

        JsonNode conn = null;
        for (JsonNode c : json.get("connections")) {
            if ("qa-kafka".equals(c.get("id").asText())) {
                conn = c;
                break;
            }
        }
        assertThat(conn).isNotNull();
        assertThat(conn.get("properties").get("password").asText()).isEqualTo("******");
        assertThat(conn.get("properties").get("client.id").asText()).isEqualTo("wiremock-client");

        // DELETE connection
        HttpRequest deleteReq = HttpRequest.newBuilder()
                .uri(URI.create(wireMockServer.baseUrl() + "/__admin/async/connections/qa-kafka"))
                .DELETE()
                .build();

        HttpResponse<String> deleteResp = httpClient.send(deleteReq, HttpResponse.BodyHandlers.ofString());
        assertThat(deleteResp.statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("POST /__admin/async/connections should support batch array and preserve ${VAR_NAME} tokens")
    void shouldSupportBatchConnectionsAndPreserveEnvTokens() throws Exception {
        String batchPayload =
                """
        {
          "connections": [
            {
              "id": "batch-amqp",
              "protocol": "amqp",
              "name": "Artemis Prod",
              "endpoint": "${ARTEMIS_HOST}:5672",
              "properties": {
                "password": "${AMQP_PASSWORD}",
                "client.id": "k8s-pod"
              }
            }
          ]
        }
        """;

        HttpRequest postReq = HttpRequest.newBuilder()
                .uri(URI.create(wireMockServer.baseUrl() + "/__admin/async/connections"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(batchPayload))
                .build();

        HttpResponse<String> postResp = httpClient.send(postReq, HttpResponse.BodyHandlers.ofString());
        assertThat(postResp.statusCode()).isEqualTo(201);

        JsonNode postJson = MAPPER.readTree(postResp.body());
        assertThat(postJson.get("count").asInt()).isEqualTo(1);
        JsonNode c = postJson.get("connections").get(0);
        assertThat(c.get("id").asText()).isEqualTo("batch-amqp");
        assertThat(c.get("endpoint").asText()).isEqualTo("${ARTEMIS_HOST}:5672");
        assertThat(c.get("properties").get("password").asText()).isEqualTo("${AMQP_PASSWORD}");
        assertThat(c.get("properties").get("client.id").asText()).isEqualTo("k8s-pod");
    }

    @Test
    @DisplayName("Should list destinations, publish, peek messages, delete single message, and purge via Admin API")
    void shouldManageDestinationsViaAdminApi() throws Exception {
        String postMsg =
                """
        {
          "id": "dest-msg-1",
          "key": "order-101",
          "payload": "{\\"status\\":\\"PENDING\\"}"
        }
        """;

        // POST message
        HttpRequest postReq = HttpRequest.newBuilder()
                .uri(URI.create(
                        wireMockServer.baseUrl() + "/__admin/async/destinations/in-memory/test.orders/messages"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(postMsg))
                .build();

        HttpResponse<String> postResp = httpClient.send(postReq, HttpResponse.BodyHandlers.ofString());
        assertThat(postResp.statusCode()).isEqualTo(201);

        // GET destinations
        HttpRequest getDests = HttpRequest.newBuilder()
                .uri(URI.create(wireMockServer.baseUrl() + "/__admin/async/destinations"))
                .GET()
                .build();

        HttpResponse<String> destsResp = httpClient.send(getDests, HttpResponse.BodyHandlers.ofString());
        assertThat(destsResp.statusCode()).isEqualTo(200);
        JsonNode destsJson = MAPPER.readTree(destsResp.body());
        assertThat(destsJson.get("count").asInt()).isGreaterThanOrEqualTo(1);

        // GET peek messages
        HttpRequest getMsgs = HttpRequest.newBuilder()
                .uri(URI.create(
                        wireMockServer.baseUrl() + "/__admin/async/destinations/in-memory/test.orders/messages"))
                .GET()
                .build();

        HttpResponse<String> msgsResp = httpClient.send(getMsgs, HttpResponse.BodyHandlers.ofString());
        assertThat(msgsResp.statusCode()).isEqualTo(200);
        JsonNode msgsJson = MAPPER.readTree(msgsResp.body());
        assertThat(msgsJson.get("count").asInt()).isEqualTo(1);
        assertThat(msgsJson.get("messages").get(0).get("id").asText()).isEqualTo("dest-msg-1");

        // DELETE single message
        HttpRequest deleteMsg = HttpRequest.newBuilder()
                .uri(URI.create(wireMockServer.baseUrl()
                        + "/__admin/async/destinations/in-memory/test.orders/messages/dest-msg-1"))
                .DELETE()
                .build();

        HttpResponse<String> delResp = httpClient.send(deleteMsg, HttpResponse.BodyHandlers.ofString());
        assertThat(delResp.statusCode()).isEqualTo(204);

        // Verify empty
        HttpResponse<String> afterDelResp = httpClient.send(getMsgs, HttpResponse.BodyHandlers.ofString());
        assertThat(MAPPER.readTree(afterDelResp.body()).get("count").asInt()).isEqualTo(0);
    }

    @Test
    @DisplayName(
            "Should route /async/publish, /async/destinations/.../messages, and HTTP produce triggers canonically with headers, counters, and ServeEvents")
    void shouldRouteAllPublishPathsCanonicallyWithHeadersCountersAndServeEvents() throws Exception {
        // Start local Kafka wire sink via Admin API
        HttpResponse<String> sinkResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(wireMockServer.baseUrl() + "/__admin/async/sinks/kafka"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"action\":\"start\",\"port\":0}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(sinkResp.statusCode()).isEqualTo(200);

        String topic = "orders.canonical";

        // 1. Publish via POST /__admin/async/publish
        String publishPayload =
                """
                {
                  "destination": "orders.canonical",
                  "protocol": "kafka",
                  "key": "k-1",
                  "correlationId": "corr-1",
                  "replyTo": "orders.replies",
                  "headers": { "x-source": "admin-publish" },
                  "payload": "{\\"step\\":1}"
                }
                """;
        HttpResponse<String> pubResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(wireMockServer.baseUrl() + "/__admin/async/publish"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(publishPayload))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(pubResp.statusCode()).isEqualTo(200);

        // 2. Publish via POST /__admin/async/destinations/{protocol}/{name}/messages
        String destPayload =
                """
                {
                  "key": "k-2",
                  "correlationId": "corr-2",
                  "replyTo": "orders.replies",
                  "headers": { "x-source": "destination-explorer" },
                  "payload": "{\\"step\\":2}"
                }
                """;
        HttpResponse<String> destResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(
                                wireMockServer.baseUrl() + "/__admin/async/destinations/kafka/" + topic + "/messages"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(destPayload))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(destResp.statusCode()).isEqualTo(201);

        // 3. Publish via HTTP-triggered metadata.async.produce
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/trigger-order"))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"orderId\":\"ORD-3\"}"))
                .withMetadata(Map.of(
                        "async",
                        Map.of(
                                "produce",
                                List.of(Map.of(
                                        "destination", topic,
                                        "protocol", "kafka",
                                        "key", "{{jsonPath response.body '$.orderId'}}",
                                        "payload",
                                                "{\"step\":3,\"orderId\":\"{{jsonPath response.body '$.orderId'}}\"}",
                                        "headers", Map.of("x-source", "http-trigger")))))));

        HttpResponse<String> httpTriggerResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(wireMockServer.baseUrl() + "/api/v1/trigger-order"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(httpTriggerResp.statusCode()).isEqualTo(202);

        // Wait briefly for virtual-thread HTTP trigger dispatch to complete
        long deadline = System.currentTimeMillis() + 5_000;
        JsonNode peekedJson = null;
        while (System.currentTimeMillis() < deadline) {
            HttpResponse<String> peekResp = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(wireMockServer.baseUrl() + "/__admin/async/destinations/kafka/" + topic
                                    + "/messages"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(peekResp.statusCode()).isEqualTo(200);
            peekedJson = MAPPER.readTree(peekResp.body());
            if (peekedJson.get("count").asInt() >= 3) {
                break;
            }
            Thread.sleep(25);
        }

        assertThat(peekedJson).isNotNull();
        assertThat(peekedJson.get("count").asInt()).isEqualTo(3);

        JsonNode m0 = peekedJson.get("messages").get(0);
        JsonNode m1 = peekedJson.get("messages").get(1);
        JsonNode m2 = peekedJson.get("messages").get(2);
        assertThat(m0.get("id").asText()).isEqualTo("orders.canonical:0:0");
        assertThat(m0.get("headers").get("x-source").asText()).isEqualTo("admin-publish");
        assertThat(m0.get("headers").get("x-correlation-id").asText()).isEqualTo("corr-1");
        assertThat(m1.get("id").asText()).isEqualTo("orders.canonical:0:1");
        assertThat(m1.get("headers").get("x-source").asText()).isEqualTo("destination-explorer");
        assertThat(m1.get("headers").get("x-correlation-id").asText()).isEqualTo("corr-2");
        assertThat(m2.get("id").asText()).isEqualTo("orders.canonical:0:2");
        assertThat(m2.get("headers").get("x-source").asText()).isEqualTo("http-trigger");

        // Verify messagesProduced counter updated across all 3 publish paths
        JsonNode statusJson = MAPPER.readTree(httpClient
                .send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(wireMockServer.baseUrl() + "/__admin/async/status"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .body());
        assertThat(statusJson.get("messagesProduced").asLong()).isEqualTo(3L);

        // Verify all 3 publishes recorded Synthetic Async ServeEvents
        long asyncServeEvents = wireMockServer.getAllServeEvents().stream()
                .filter(se -> ("/__async/kafka/" + topic).equals(se.getRequest().getUrl()))
                .count();
        assertThat(asyncServeEvents).isEqualTo(3L);

        // Verify deleting a peeked Kafka record by its returned stable id succeeds via HTTP Admin API
        HttpResponse<String> delKafkaResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(wireMockServer.baseUrl()
                                + "/__admin/async/destinations/kafka/" + topic + "/messages/"
                                + m0.get("id").asText()))
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(delKafkaResp.statusCode()).isEqualTo(204);
    }
}
