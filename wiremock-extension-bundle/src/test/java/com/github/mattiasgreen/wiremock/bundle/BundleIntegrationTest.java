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

        // 2. Test Prometheus Metrics route (allow brief async serve event processing)
        String metricsBody = "";
        for (int i = 0; i < 30; i++) {
            HttpRequest metricsReq = HttpRequest.newBuilder()
                    .uri(URI.create(server.baseUrl() + "/__admin/metrics/prometheus"))
                    .GET()
                    .build();
            HttpResponse<String> metricsResp = httpClient.send(metricsReq, HttpResponse.BodyHandlers.ofString());
            assertThat(metricsResp.statusCode()).isEqualTo(200);
            metricsBody = metricsResp.body();
            if (metricsBody.contains("wiremock_requests_total")) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(metricsBody).contains("wiremock_requests_total");

        // 3. Test UI route
        HttpRequest uiReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/ui"))
                .GET()
                .build();
        HttpResponse<String> uiResp = httpClient.send(uiReq, HttpResponse.BodyHandlers.ofString());
        assertThat(uiResp.statusCode()).isEqualTo(200);
        assertThat(uiResp.body()).contains("WireMock Console");

        // 4. Test Runtime Logback Levels route (GET, PUT, and reset via SPI auto-discovery)
        HttpRequest logLevelsReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/logging/levels"))
                .GET()
                .build();
        HttpResponse<String> logLevelsResp = httpClient.send(logLevelsReq, HttpResponse.BodyHandlers.ofString());
        assertThat(logLevelsResp.statusCode()).isEqualTo(200);
        assertThat(logLevelsResp.body()).startsWith("ROOT=");

        HttpRequest updateLevelsReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/logging/levels"))
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString("com.github.mattiasgreen=TRACE,se.laz.casual=TRACE"))
                .build();
        HttpResponse<String> updateLevelsResp = httpClient.send(updateLevelsReq, HttpResponse.BodyHandlers.ofString());
        assertThat(updateLevelsResp.statusCode()).isEqualTo(200);
        assertThat(updateLevelsResp.body())
                .contains("com.github.mattiasgreen=TRACE")
                .contains("se.laz.casual=TRACE");

        HttpRequest resetLevelsReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/logging/reset"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resetLevelsResp = httpClient.send(resetLevelsReq, HttpResponse.BodyHandlers.ofString());
        assertThat(resetLevelsResp.statusCode()).isEqualTo(200);
        assertThat(resetLevelsResp.body())
                .doesNotContain("com.github.mattiasgreen=TRACE")
                .doesNotContain("se.laz.casual=TRACE");
    }
}
