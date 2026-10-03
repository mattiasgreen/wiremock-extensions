package com.github.mattiasgreen.wiremock.logging;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mattiasgreen.wiremock.logging.mdc.MdcRequestFilter;
import com.github.mattiasgreen.wiremock.logging.testutil.TestLogAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

public class MdcAndJsonLoggingTest {

    private WireMockServer server;
    private HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TestLogAppender.clear();
        MDC.clear();

        server = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .extensions(new MdcRequestFilter(), new JsonLoggingListener()));
        server.start();

        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
        MDC.clear();
    }

    @Test
    void testMdcHeadersExtractedAndTaggedInJsonLog() throws Exception {
        server.stubFor(get(urlEqualTo("/api/v1/resource"))
                .withName("Test Resource Stub")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"OK\"}")));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/api/v1/resource"))
                .header("X-Correlation-Id", "corr-test-999")
                .header("X-Tenant-Id", "tenant-alpha")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode logJson = awaitTrafficLogJson();
        assertThat(logJson).isNotNull();

        assertThat(logJson.get("event").asText()).isEqualTo("wiremock_request_served");
        assertThat(logJson.get("matched").asBoolean()).isTrue();
        assertThat(logJson.get("stub_name").asText()).isEqualTo("Test Resource Stub");
        assertThat(logJson.get("response").get("status").asInt()).isEqualTo(200);

        assertThat(logJson.has("x_correlation_id")).isTrue();
        assertThat(logJson.get("x_correlation_id").asText()).isEqualTo("corr-test-999");

        assertThat(logJson.has("x_tenant_id")).isTrue();
        assertThat(logJson.get("x_tenant_id").asText()).isEqualTo("tenant-alpha");
    }

    @Test
    void testMdcCleanedUpAfterRequestCompletes() throws Exception {
        server.stubFor(get(urlEqualTo("/api/v1/clean")).willReturn(ok()));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/api/v1/clean"))
                .header("X-Correlation-Id", "leak-check-456")
                .GET()
                .build();

        httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(MDC.get("x_correlation_id")).isNull();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void testUnmatchedRequestStructuredJson() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/unmatched/endpoint"))
                .header("X-Correlation-Id", "unmatched-987")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(404);

        JsonNode logJson = awaitTrafficLogJson();
        assertThat(logJson).isNotNull();

        assertThat(logJson.get("matched").asBoolean()).isFalse();
        assertThat(logJson.get("response").get("status").asInt()).isEqualTo(404);
        assertThat(logJson.get("x_correlation_id").asText()).isEqualTo("unmatched-987");
        assertThat(logJson.has("unmatched_reason")).isTrue();
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
