package com.github.mattiasgreen.wiremock.examples;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mattiasgreen.wiremock.async.engine.AsyncEngine;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import com.github.mattiasgreen.wiremock.journal.store.CompressedStores;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.SingleRootFileSource;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration test demonstrating cross-extension synergy across the entire suite:
 * <ul>
 *   <li>Async Engine & Event Triggers ({@code wiremock-extension-async})</li>
 *   <li>Zstd Dictionary Request Journal ({@code wiremock-extension-journal})</li>
 *   <li>OpenTelemetry Prometheus Metrics & Semantic Traffic Classification ({@code wiremock-extension-otel})</li>
 *   <li>Embedded Web UI Console ({@code wiremock-extension-ui})</li>
 * </ul>
 */
class CrossExtensionSynergyIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WireMockServer server;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        AsyncEngine.getInstance().reset();
        WireMockConfiguration options = WireMockConfiguration.options()
                .dynamicPort()
                .withStores(new CompressedStores(new SingleRootFileSource(".")))
                .extensionScanningEnabled(true);
        server = new WireMockServer(options);
        server.start();

        httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterEach
    void tearDown() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
        AsyncEngine.getInstance().reset();
    }

    @Test
    @DisplayName("Should demonstrate cross-extension synergy: Async triggers + Zstd compression + OTEL metrics + UI")
    void shouldDemonstrateCrossExtensionSynergy() throws Exception {
        // 1. Verify Async Engine Admin Endpoint is online and active
        HttpRequest asyncStatusReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/async/status"))
                .GET()
                .build();
        HttpResponse<String> asyncStatusResp = httpClient.send(asyncStatusReq, HttpResponse.BodyHandlers.ofString());
        assertThat(asyncStatusResp.statusCode()).isEqualTo(200);
        JsonNode asyncJson = MAPPER.readTree(asyncStatusResp.body());
        assertThat(asyncJson.get("status").asText()).isEqualTo("UP");
        assertThat(asyncJson.get("beta").asBoolean()).isTrue();

        // 2. Configure a stub mapping with Async trigger metadata
        BlockingQueue<AsyncMessage> asyncQueue = new LinkedBlockingQueue<>();
        AsyncEngine.getInstance().registerPublisher("notifications.broadcast", (dest, msg) -> asyncQueue.offer(msg));

        server.stubFor(
                post(urlEqualTo("/api/v1/orders"))
                        .willReturn(aResponse()
                                .withStatus(201)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"orderId\":\"ORD-999\",\"status\":\"PENDING\"}"))
                        .withMetadata(
                                Map.of(
                                        "async",
                                        Map.of(
                                                "produce",
                                                List.of(
                                                        Map.of(
                                                                "destination", "notifications.broadcast",
                                                                "protocol", "kafka",
                                                                "key", "{{jsonPath response.body '$.orderId'}}",
                                                                "payload",
                                                                        "{\"orderId\":\"{{jsonPath response.body '$.orderId'}}\",\"event\":\"ORDER_PLACED\"}"))))));

        // 3. Dispatch HTTP request to trigger the order stub and async publishing
        HttpRequest orderReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/api/v1/orders"))
                .header("Content-Type", "application/json")
                .header("X-Correlation-Id", "corr-test-12345")
                .POST(HttpRequest.BodyPublishers.ofString("{\"customerId\":\"CUST-1\"}"))
                .build();
        HttpResponse<String> orderResp = httpClient.send(orderReq, HttpResponse.BodyHandlers.ofString());
        assertThat(orderResp.statusCode()).isEqualTo(201);

        // 4. Verify Async Engine produced message asynchronously via virtual thread
        AsyncMessage producedMessage = asyncQueue.poll(5, TimeUnit.SECONDS);
        assertThat(producedMessage).isNotNull();
        assertThat(producedMessage.destination()).isEqualTo("notifications.broadcast");
        assertThat(producedMessage.protocol()).isEqualTo("kafka");
        assertThat(producedMessage.key()).isEqualTo("ORD-999");
        assertThat(producedMessage.payload()).isEqualTo("{\"orderId\":\"ORD-999\",\"event\":\"ORDER_PLACED\"}");

        // 5. Send an async ingress event to test OTEL traffic classification for async protocol
        server.stubFor(post(urlEqualTo("/__async/kafka/notifications.broadcast"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ack\":true}")));

        HttpRequest asyncIngressReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__async/kafka/notifications.broadcast"))
                .header("X-Async-Channel", "notifications.broadcast")
                .header("X-Async-Protocol", "kafka")
                .POST(HttpRequest.BodyPublishers.ofString("{\"event\":\"ACK\"}"))
                .build();
        HttpResponse<String> asyncIngressResp = httpClient.send(asyncIngressReq, HttpResponse.BodyHandlers.ofString());
        assertThat(asyncIngressResp.statusCode()).isEqualTo(200);

        // 6. Verify Zstd Request Journal stats endpoint reports recorded events
        HttpRequest journalStatsReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/journal/stats"))
                .GET()
                .build();
        HttpResponse<String> journalStatsResp = httpClient.send(journalStatsReq, HttpResponse.BodyHandlers.ofString());
        assertThat(journalStatsResp.statusCode()).isEqualTo(200);
        JsonNode journalJson = MAPPER.readTree(journalStatsResp.body());
        assertThat(journalJson.get("totalEvents").asLong()).isGreaterThanOrEqualTo(2L);

        // 7. Verify Prometheus metrics scrape endpoint reflects async traffic classification
        HttpRequest prometheusReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/metrics/prometheus"))
                .GET()
                .build();
        HttpResponse<String> prometheusResp = httpClient.send(prometheusReq, HttpResponse.BodyHandlers.ofString());
        assertThat(prometheusResp.statusCode()).isEqualTo(200);
        String promBody = prometheusResp.body();
        assertThat(promBody).contains("wiremock_requests_total");
        assertThat(promBody).contains("rpc_system=\"kafka\"");
        assertThat(promBody).contains("rpc_service=\"notifications.broadcast\"");

        // 8. Verify UI endpoint serves the console
        HttpRequest uiReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/ui/"))
                .GET()
                .build();
        HttpResponse<String> uiResp = httpClient.send(uiReq, HttpResponse.BodyHandlers.ofString());
        assertThat(uiResp.statusCode()).isEqualTo(200);
        assertThat(uiResp.body()).contains("WireMock Console");
    }
}
