package com.github.mattiasgreen.wiremock.otel;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.otel.tracing.OtelTracingListener;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.Extension;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OtelMetricsExtensionFactoryTest {

    private WireMockServer serverA;
    private WireMockServer serverB;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (serverA != null && serverA.isRunning()) {
            serverA.stop();
        }
        if (serverB != null && serverB.isRunning()) {
            serverB.stop();
        }
    }

    @Test
    @DisplayName("OtelMetricsExtensionFactory creates isolated instance-scoped telemetry engines per server")
    void shouldIsolateMetricsAcrossMultipleServersInSameJvm() throws Exception {
        // Configure Server A with SPI factory
        serverA = new WireMockServer(
                WireMockConfiguration.options().dynamicPort().extensions(new OtelMetricsExtensionFactory()));
        serverA.start();

        // Configure Server B with SPI factory
        serverB = new WireMockServer(
                WireMockConfiguration.options().dynamicPort().extensions(new OtelMetricsExtensionFactory()));
        serverB.start();

        serverA.stubFor(get(urlEqualTo("/api/serverA")).willReturn(ok("A-OK")));
        serverB.stubFor(get(urlEqualTo("/api/serverB")).willReturn(ok("B-OK")));

        // Send 3 requests to Server A
        for (int i = 0; i < 3; i++) {
            HttpRequest reqA = HttpRequest.newBuilder()
                    .uri(URI.create(serverA.baseUrl() + "/api/serverA"))
                    .GET()
                    .build();
            HttpResponse<String> respA = httpClient.send(reqA, HttpResponse.BodyHandlers.ofString());
            assertThat(respA.statusCode()).isEqualTo(200);
        }

        // Send 1 request to Server B
        HttpRequest reqB = HttpRequest.newBuilder()
                .uri(URI.create(serverB.baseUrl() + "/api/serverB"))
                .GET()
                .build();
        HttpResponse<String> respB = httpClient.send(reqB, HttpResponse.BodyHandlers.ofString());
        assertThat(respB.statusCode()).isEqualTo(200);

        // Scrape Server A
        HttpResponse<String> scrapeA = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(serverA.baseUrl() + "/__admin/metrics/prometheus"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(scrapeA.statusCode()).isEqualTo(200);
        String bodyA = scrapeA.body();

        // Scrape Server B
        HttpResponse<String> scrapeB = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(serverB.baseUrl() + "/__admin/metrics/prometheus"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(scrapeB.statusCode()).isEqualTo(200);
        String bodyB = scrapeB.body();

        // Verify Server A recorded its own requests
        assertThat(bodyA).contains("wiremock_requests_total");
        assertThat(bodyA).contains("wiremock_journal_size_requests 3.0");
        assertThat(bodyA).contains("wiremock_stubs{status=\"active\"} 1.0");

        // Verify Server B recorded only its own requests without contamination
        assertThat(bodyB).contains("wiremock_requests_total");
        assertThat(bodyB).contains("wiremock_journal_size_requests 1.0");
        assertThat(bodyB).contains("wiremock_stubs{status=\"active\"} 1.0");
    }

    @Test
    @DisplayName("Factory produces expected extension list")
    void shouldProduceExpectedExtensionList() {
        OtelMetricsExtensionFactory factory = new OtelMetricsExtensionFactory();
        List<Extension> extensions = factory.create(null);
        assertThat(extensions).hasSize(3);
        assertThat(extensions.stream().map(Extension::getName))
                .containsExactlyInAnyOrder(
                        OtelMetricsListener.LISTENER_NAME,
                        PrometheusAdminEndpoint.EXTENSION_NAME,
                        OtelTracingListener.LISTENER_NAME);
    }
}
