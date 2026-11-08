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
}
