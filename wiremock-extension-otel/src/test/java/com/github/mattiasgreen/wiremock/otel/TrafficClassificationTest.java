package com.github.mattiasgreen.wiremock.otel;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TrafficClassificationTest {

    private WireMockServer server;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
    }

    @Test
    @DisplayName("Null ServeEvent produces safe default unmatched classification")
    void shouldClassifyNullServeEventSafely() {
        TrafficClassification tc = TrafficClassification.from(null);
        assertThat(tc.protocol()).isEqualTo("http");
        assertThat(tc.trafficType()).isEqualTo("unmatched");
        assertThat(tc.method()).isEqualTo("UNKNOWN");
        assertThat(tc.matched()).isFalse();
        assertThat(tc.durationMs()).isEqualTo(0.0);
        assertThat(tc.urlPath()).isEqualTo("/");
    }

    @Test
    @DisplayName("Classify standard HTTP matched stub")
    void shouldClassifyStandardHttpMatchedStub() throws Exception {
        server.stubFor(get(urlPathEqualTo("/api/v1/users"))
                .withName("User Listing Stub")
                .inScenario("UserFlow")
                .whenScenarioStateIs("Started")
                .willReturn(okJson("{\"users\":[]}")));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/api/v1/users?page=2"))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);

        List<ServeEvent> events = server.getAllServeEvents();
        assertThat(events).hasSize(1);

        TrafficClassification tc = TrafficClassification.from(events.get(0));

        assertThat(tc.protocol()).isEqualTo("http");
        assertThat(tc.trafficType()).isEqualTo("stubbed");
        assertThat(tc.method()).isEqualTo("GET");
        assertThat(tc.statusCode()).isEqualTo(200);
        assertThat(tc.matched()).isTrue();
        assertThat(tc.stubName()).isEqualTo("User Listing Stub");
        assertThat(tc.scenarioName()).isEqualTo("UserFlow");
        assertThat(tc.scenarioState()).isEqualTo("Started");
        assertThat(tc.urlPath()).isEqualTo("/api/v1/users");
        assertThat(tc.urlQuery()).isEqualTo("page=2");
        assertThat(tc.durationMs()).isGreaterThanOrEqualTo(0.0);
        assertThat(tc.endTimestamp()).isAfterOrEqualTo(tc.startTimestamp());
    }

    @Test
    @DisplayName("Classify Casual RPC service via URL path and headers")
    void shouldClassifyCasualRpcService() throws Exception {
        server.stubFor(post(urlEqualTo("/__casual/services/order_service"))
                .willReturn(okJson("{\"status\":\"COMMITTED\"}").withHeader("X-Casual-Error-State", "OK")));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__casual/services/order_service"))
                .header("X-Casual-Service", "order_service")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"amount\":100}"))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);

        List<ServeEvent> events = server.getAllServeEvents();
        assertThat(events).hasSize(1);

        TrafficClassification tc = TrafficClassification.from(events.get(0));

        assertThat(tc.protocol()).isEqualTo("casual");
        assertThat(tc.rpcSystem()).isEqualTo("casual");
        assertThat(tc.rpcService()).isEqualTo("order_service");
        assertThat(tc.rpcStatus()).isEqualTo("OK");
        assertThat(tc.trafficType()).isEqualTo("stubbed");
    }

    @Test
    @DisplayName("Classify gRPC protocol via Content-Type header on unmatched request")
    void shouldClassifyGrpcProtocol() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/com.example.OrderService/GetOrder"))
                .header("Content-Type", "application/grpc")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(404);

        List<ServeEvent> events = server.getAllServeEvents();
        assertThat(events).hasSize(1);

        TrafficClassification tc = TrafficClassification.from(events.get(0));

        assertThat(tc.protocol()).isEqualTo("grpc");
        assertThat(tc.trafficType()).isEqualTo("unmatched");
        assertThat(tc.statusCode()).isEqualTo(404);
        assertThat(tc.matched()).isFalse();
    }

    @Test
    @DisplayName("Classify Proxied and Fault responses")
    void shouldClassifyProxiedAndFaultResponses() throws Exception {
        WireMockServer upstream =
                new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstream.start();
        try {
            upstream.stubFor(get("/proxy/data").willReturn(ok("upstream-payload")));
            server.stubFor(get("/proxy/data").willReturn(aResponse().proxiedFrom(upstream.baseUrl())));
            server.stubFor(get("/fault/data").willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

            // Proxied
            HttpRequest proxyReq = HttpRequest.newBuilder()
                    .uri(URI.create(server.baseUrl() + "/proxy/data"))
                    .GET()
                    .build();
            HttpResponse<String> proxyResp = httpClient.send(proxyReq, HttpResponse.BodyHandlers.ofString());
            assertThat(proxyResp.statusCode()).isEqualTo(200);

            // Fault
            HttpRequest faultReq = HttpRequest.newBuilder()
                    .uri(URI.create(server.baseUrl() + "/fault/data"))
                    .GET()
                    .build();
            try {
                httpClient.send(faultReq, HttpResponse.BodyHandlers.ofString());
            } catch (Exception ignored) {
            }

            List<ServeEvent> events = server.getAllServeEvents();
            assertThat(events).hasSize(2);

            TrafficClassification faultTc = TrafficClassification.from(events.get(0));
            assertThat(faultTc.trafficType()).isEqualTo("fault");

            TrafficClassification proxyTc = TrafficClassification.from(events.get(1));
            assertThat(proxyTc.trafficType()).isEqualTo("proxied");
            assertThat(proxyTc.targetUpstream()).isEqualTo(upstream.baseUrl());
        } finally {
            upstream.stop();
        }
    }
}
