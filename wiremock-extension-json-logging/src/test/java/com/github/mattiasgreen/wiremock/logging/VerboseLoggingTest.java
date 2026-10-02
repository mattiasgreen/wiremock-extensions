package com.github.mattiasgreen.wiremock.logging;

import com.github.mattiasgreen.wiremock.logging.testutil.TestLogAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

public class VerboseLoggingTest {

    private WireMockServer server;
    private HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TestLogAppender.clear();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
        System.clearProperty("wiremock.verbose.json");
    }

    @Test
    void testVerboseModeIncludesRequestAndResponseBody() throws Exception {
        System.setProperty("wiremock.verbose.json", "true");

        server = new WireMockServer(
                WireMockConfiguration.options()
                        .dynamicPort()
                        .extensions(new JsonLoggingListener(true))
        );
        server.start();

        server.stubFor(post(urlEqualTo("/api/v1/orders"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"orderId\":\"ord-555\",\"status\":\"CREATED\"}")));

        String requestPayload = "{\"item\":\"Widget\",\"quantity\":3}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/api/v1/orders"))
                .header("Content-Type", "application/json")
                .header("X-Custom-Client", "JUnitTest")
                .POST(HttpRequest.BodyPublishers.ofString(requestPayload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(201);

        JsonNode logJson = awaitTrafficLogJson();
        assertThat(logJson).isNotNull();

        assertThat(logJson.get("request").has("body")).isTrue();
        assertThat(logJson.get("request").get("body").asText()).isEqualTo(requestPayload);

        assertThat(logJson.get("response").has("body")).isTrue();
        assertThat(logJson.get("response").get("body").asText()).contains("ord-555");
    }

    @Test
    void testNonVerboseModeExcludesBodies() throws Exception {
        System.setProperty("wiremock.verbose.json", "false");

        server = new WireMockServer(
                WireMockConfiguration.options()
                        .dynamicPort()
                        .extensions(new JsonLoggingListener(false))
        );
        server.start();

        server.stubFor(post(urlEqualTo("/api/v1/orders"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withBody("{\"status\":\"OK\"}")));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/api/v1/orders"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"silent\":\"payload\"}"))
                .build();

        httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        JsonNode logJson = awaitTrafficLogJson();
        assertThat(logJson).isNotNull();

        assertThat(logJson.get("request").has("body")).isFalse();
        assertThat(logJson.get("response").has("body")).isFalse();
    }

    private JsonNode awaitTrafficLogJson() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode found = TestLogAppender.getEvents().stream()
                    .filter(e -> "wiremock.traffic".equals(e.getLoggerName()))
                    .map(e -> {
                        try {
                            return objectMapper.readTree(e.getFormattedMessage());
                        } catch (Exception ex) {
                            return null;
                        }
                    })
                    .filter(node -> node != null && node.has("event"))
                    .reduce((first, second) -> second)
                    .orElse(null);

            if (found != null) {
                return found;
            }
            Thread.sleep(30);
        }
        return null;
    }
}
