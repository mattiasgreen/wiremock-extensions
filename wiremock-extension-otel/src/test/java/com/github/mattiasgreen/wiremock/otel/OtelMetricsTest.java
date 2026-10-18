package com.github.mattiasgreen.wiremock.otel;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class OtelMetricsTest {

    private WireMockServer server;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .extensions(new OtelMetricsListener(), new PrometheusAdminEndpoint()));
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
    void testPrometheusEndpointReflectsRequestCountersAndDuration() throws Exception {
        server.stubFor(get(urlEqualTo("/api/v1/ping")).withName("Ping Stub").willReturn(ok("pong")));

        server.stubFor(
                post(urlEqualTo("/api/v1/ping")).withName("Post Ping Stub").willReturn(ok("post-pong")));

        for (int i = 0; i < 3; i++) {
            HttpRequest getReq = HttpRequest.newBuilder()
                    .uri(URI.create(server.baseUrl() + "/api/v1/ping"))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(getReq, HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(200);
        }

        for (int i = 0; i < 2; i++) {
            HttpRequest postReq = HttpRequest.newBuilder()
                    .uri(URI.create(server.baseUrl() + "/api/v1/ping"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            HttpResponse<String> resp = httpClient.send(postReq, HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(200);
        }

        HttpRequest missingReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/api/v1/does-not-exist"))
                .GET()
                .build();
        HttpResponse<String> missingResp = httpClient.send(missingReq, HttpResponse.BodyHandlers.ofString());
        assertThat(missingResp.statusCode()).isEqualTo(404);

        HttpRequest metricsReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/metrics/prometheus"))
                .GET()
                .build();

        HttpResponse<String> metricsResp = httpClient.send(metricsReq, HttpResponse.BodyHandlers.ofString());
        assertThat(metricsResp.statusCode()).isEqualTo(200);
        assertThat(metricsResp.headers().firstValue("Content-Type")).isPresent();
        assertThat(metricsResp.headers().firstValue("Content-Type").get()).contains("text/plain");

        String prometheusText = metricsResp.body();
        assertThat(prometheusText).isNotEmpty();

        assertThat(prometheusText).contains("wiremock_requests_total");
        assertThat(prometheusText).contains("protocol=\"http\"");
        assertThat(prometheusText).contains("traffic_type=\"stubbed\"");
        assertThat(prometheusText).contains("http_request_method=\"GET\"");
        assertThat(prometheusText).contains("http_request_method=\"POST\"");
        assertThat(prometheusText).contains("wiremock_matched=\"true\"");
        assertThat(prometheusText).contains("wiremock_request_duration_ms");
        assertThat(prometheusText).contains("wiremock_requests_unmatched_total");
        assertThat(prometheusText).contains("traffic_type=\"unmatched\"");

        HttpRequest aliasReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/metrics"))
                .GET()
                .build();
        HttpResponse<String> aliasResp = httpClient.send(aliasReq, HttpResponse.BodyHandlers.ofString());
        assertThat(aliasResp.statusCode()).isEqualTo(200);
        assertThat(aliasResp.body()).contains("wiremock_requests_total");
    }

    @Test
    void testStubbedVsProxiedAndFaultMetrics() throws Exception {
        WireMockServer upstream =
                new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstream.start();
        try {
            upstream.stubFor(get("/proxied/hello").willReturn(ok("remote-hello-body")));

            server.stubFor(get("/local/hello").withName("Local Hello").willReturn(ok("local-hello-body")));
            server.stubFor(get("/proxied/hello")
                    .withName("Proxied Hello")
                    .willReturn(aResponse().proxiedFrom(upstream.baseUrl())));
            server.stubFor(get("/fault/hello")
                    .withName("Fault Hello")
                    .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.EMPTY_RESPONSE)));

            // 1. Local Stubbed request
            HttpRequest localReq = HttpRequest.newBuilder()
                    .uri(URI.create(server.baseUrl() + "/local/hello"))
                    .GET()
                    .build();
            HttpResponse<String> localResp = httpClient.send(localReq, HttpResponse.BodyHandlers.ofString());
            assertThat(localResp.statusCode()).isEqualTo(200);
            assertThat(localResp.body()).isEqualTo("local-hello-body");

            // 2. Proxied request
            HttpRequest proxyReq = HttpRequest.newBuilder()
                    .uri(URI.create(server.baseUrl() + "/proxied/hello"))
                    .GET()
                    .build();
            HttpResponse<String> proxyResp = httpClient.send(proxyReq, HttpResponse.BodyHandlers.ofString());
            assertThat(proxyResp.statusCode()).isEqualTo(200);
            assertThat(proxyResp.body()).isEqualTo("remote-hello-body");

            // 3. Fault request
            HttpRequest faultReq = HttpRequest.newBuilder()
                    .uri(URI.create(server.baseUrl() + "/fault/hello"))
                    .GET()
                    .build();
            try {
                httpClient.send(faultReq, HttpResponse.BodyHandlers.ofString());
            } catch (Exception ignored) {
                // Expected connection reset or empty response
            }

            // 4. Scrape metrics
            HttpRequest metricsReq = HttpRequest.newBuilder()
                    .uri(URI.create(server.baseUrl() + "/__admin/metrics/prometheus"))
                    .GET()
                    .build();
            HttpResponse<String> metricsResp = httpClient.send(metricsReq, HttpResponse.BodyHandlers.ofString());
            assertThat(metricsResp.statusCode()).isEqualTo(200);
            String body = metricsResp.body();

            // Verify Stubbed metric
            assertThat(body).contains("traffic_type=\"stubbed\"");
            assertThat(body).contains("wiremock_stub_name=\"Local Hello\"");

            // Verify Proxied metric
            assertThat(body).contains("traffic_type=\"proxied\"");
            assertThat(body).contains("wiremock_stub_name=\"Proxied Hello\"");
            assertThat(body).contains("target_upstream=\"" + upstream.baseUrl() + "\"");
            assertThat(body).contains("wiremock_upstream_duration_ms");

            // Verify Fault metric
            assertThat(body).contains("traffic_type=\"fault\"");
            assertThat(body).contains("wiremock_stub_name=\"Fault Hello\"");
        } finally {
            upstream.stop();
        }
    }

    @Test
    void testCasualProtocolMetrics() throws Exception {
        server.stubFor(post(urlEqualTo("/__casual/services/order_service"))
                .withName("Casual Order Stub")
                .willReturn(okJson("{\"status\":\"COMMITTED\"}").withHeader("X-Casual-Error-State", "OK")));

        HttpRequest casualReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__casual/services/order_service"))
                .header("X-Casual-Service", "order_service")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"amount\":100}"))
                .build();

        HttpResponse<String> resp = httpClient.send(casualReq, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);

        HttpRequest metricsReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/metrics/prometheus"))
                .GET()
                .build();
        HttpResponse<String> metricsResp = httpClient.send(metricsReq, HttpResponse.BodyHandlers.ofString());
        assertThat(metricsResp.statusCode()).isEqualTo(200);
        String body = metricsResp.body();

        assertThat(body).contains("protocol=\"casual\"");
        assertThat(body).contains("rpc_service=\"order_service\"");
        assertThat(body).contains("casual_error_state=\"OK\"");
        assertThat(body).contains("traffic_type=\"stubbed\"");
    }

    @Test
    void testRuntimeHealthGauges() throws Exception {
        HttpRequest metricsReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/metrics/prometheus"))
                .GET()
                .build();
        HttpResponse<String> metricsResp = httpClient.send(metricsReq, HttpResponse.BodyHandlers.ofString());
        assertThat(metricsResp.statusCode()).isEqualTo(200);
        String body = metricsResp.body();

        assertThat(body).contains("wiremock_journal_size_requests");
        assertThat(body).contains("wiremock_journal_capacity");
        assertThat(body).contains("wiremock_stubs_total{status=\"active\"}");
    }

    @Test
    void testPrometheusEndpointIncludesNetEmMetricsWhenActive() throws Exception {
        com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmRegistry.getInstance()
                .closeAll();
        com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmProxy proxy =
                com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmRegistry.getInstance()
                        .register(new com.github.mattiasgreen.wiremock.netem.model.NetEmProxyConfig(
                                "otel-test-proxy", 0, "127.0.0.1", server.port(), true));

        try {
            // Send request via NetEm proxy
            server.stubFor(get(urlEqualTo("/api/netem")).willReturn(ok("netem-ok")));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + proxy.getPort() + "/api/netem"))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(200);

            // Scrape Prometheus
            HttpRequest metricsReq = HttpRequest.newBuilder()
                    .uri(URI.create(server.baseUrl() + "/__admin/metrics/prometheus"))
                    .GET()
                    .build();
            HttpResponse<String> metricsResp = httpClient.send(metricsReq, HttpResponse.BodyHandlers.ofString());
            assertThat(metricsResp.statusCode()).isEqualTo(200);

            String body = metricsResp.body();
            assertThat(body).contains("wiremock_netem_connections_active{proxy=\"otel-test-proxy\"}");
            assertThat(body)
                    .contains(
                            "wiremock_netem_transfers_total{proxy=\"otel-test-proxy\",outcome=\"normal\",toxic=\"none\"}");
            assertThat(body)
                    .contains(
                            "wiremock_netem_bytes_transferred_total{proxy=\"otel-test-proxy\",direction=\"upstream\",outcome=\"normal\"}");
        } finally {
            com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmRegistry.getInstance()
                    .closeAll();
        }
    }
}
