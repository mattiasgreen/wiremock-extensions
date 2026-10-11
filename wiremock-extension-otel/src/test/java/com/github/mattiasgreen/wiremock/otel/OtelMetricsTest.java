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
        assertThat(prometheusText).contains("http_request_method=\"GET\"");
        assertThat(prometheusText).contains("http_request_method=\"POST\"");
        assertThat(prometheusText).contains("wiremock_matched=\"true\"");
        assertThat(prometheusText).contains("wiremock_request_duration_ms");
        assertThat(prometheusText).contains("wiremock_requests_unmatched_total");

        HttpRequest aliasReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/metrics"))
                .GET()
                .build();
        HttpResponse<String> aliasResp = httpClient.send(aliasReq, HttpResponse.BodyHandlers.ofString());
        assertThat(aliasResp.statusCode()).isEqualTo(200);
        assertThat(aliasResp.body()).contains("wiremock_requests_total");
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
