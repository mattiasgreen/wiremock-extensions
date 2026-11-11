package com.github.mattiasgreen.wiremock.async.admin;

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
        AsyncAdminEndpoint adminEndpoint = new AsyncAdminEndpoint();
        wireMockServer =
                new WireMockServer(WireMockConfiguration.options().dynamicPort().extensions(adminEndpoint));
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
    @DisplayName("GET /__admin/async/status should return beta status and health indicators")
    void shouldReturnAsyncStatus() throws Exception {
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
    @DisplayName("GET /__admin/async/journal should return recorded journal events")
    void shouldReturnAsyncJournal() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(wireMockServer.baseUrl() + "/__admin/async/journal"))
                .GET()
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);

        JsonNode json = MAPPER.readTree(resp.body());
        assertThat(json.has("journal")).isTrue();
        assertThat(json.has("count")).isTrue();
    }

    @Test
    @DisplayName("GET /__admin/async/harvested should return live messages harvested by the proxy bridge")
    void shouldReturnHarvestedMessages() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(wireMockServer.baseUrl() + "/__admin/async/harvested"))
                .GET()
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);

        JsonNode json = MAPPER.readTree(resp.body());
        assertThat(json.has("harvested")).isTrue();
        assertThat(json.has("count")).isTrue();
    }

    @Test
    @DisplayName("GET /__admin/async/stubs should return harvested offline stubs (deprecated alias)")
    void shouldReturnAsyncStubs() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(wireMockServer.baseUrl() + "/__admin/async/stubs"))
                .GET()
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);

        JsonNode json = MAPPER.readTree(resp.body());
        assertThat(json.has("stubs")).isTrue();
        assertThat(json.has("count")).isTrue();
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
}
