package com.github.mattiasgreen.wiremock.bundle;

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

public class BundleIntegrationTest {

    private WireMockServer server;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        server =
                new WireMockServer(WireMockConfiguration.options().dynamicPort().extensionScanningEnabled(true));
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
    void testBundleLoadsAllExtensionsTogether() throws Exception {
        server.stubFor(get(urlEqualTo("/api/v1/bundle-test")).willReturn(okJson("{\"status\":\"bundle-ok\"}")));

        // 1. Test Stub with MDC Header
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/api/v1/bundle-test"))
                .header("X-Correlation-Id", "bundle-12345")
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);

        // 2. Test Prometheus Metrics route
        HttpRequest metricsReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/metrics/prometheus"))
                .GET()
                .build();
        HttpResponse<String> metricsResp = httpClient.send(metricsReq, HttpResponse.BodyHandlers.ofString());
        assertThat(metricsResp.statusCode()).isEqualTo(200);
        assertThat(metricsResp.body()).contains("wiremock_requests_total");

        // 3. Test UI route
        HttpRequest uiReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/ui"))
                .GET()
                .build();
        HttpResponse<String> uiResp = httpClient.send(uiReq, HttpResponse.BodyHandlers.ofString());
        assertThat(uiResp.statusCode()).isEqualTo(200);
        assertThat(uiResp.body()).contains("WireMock Stub Viewer");
    }
}
