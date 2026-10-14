package com.github.mattiasgreen.wiremock.ui;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WireMockConsoleProxyTest {

    private WireMockServer defaultWireMock;
    private WireMockConsoleProxy proxy;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        defaultWireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        defaultWireMock.start();

        defaultWireMock.stubFor(
                get(urlEqualTo("/api/cases")).willReturn(okJson("{\"cases\": [{\"id\": 101, \"status\": \"OPEN\"}]}")));

        proxy = new WireMockConsoleProxy(defaultWireMock.baseUrl(), 0);
    }

    @AfterEach
    void tearDown() {
        if (proxy != null) {
            proxy.stop();
        }
        if (defaultWireMock != null && defaultWireMock.isRunning()) {
            defaultWireMock.stop();
        }
    }

    @Test
    @DisplayName("Root URL redirects (302) to /__admin/ui/")
    void shouldRedirectRootToWebUi() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + proxy.getLocalPort() + "/"))
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(302);
        assertThat(resp.headers().firstValue("Location").orElse("")).contains("/__admin/ui/");
    }

    @Test
    @DisplayName("Serves Web UI static assets directly with CORS headers")
    void shouldServeWebUiStaticAssets() throws Exception {
        // 1. HTML index
        HttpRequest htmlReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + proxy.getLocalPort() + "/__admin/ui/"))
                .GET()
                .build();
        HttpResponse<String> htmlResp = client.send(htmlReq, HttpResponse.BodyHandlers.ofString());
        assertThat(htmlResp.statusCode()).isEqualTo(200);
        assertThat(htmlResp.body()).contains("<!DOCTYPE html>");
        assertThat(htmlResp.body()).contains("WireMock Console");
        assertThat(htmlResp.headers().firstValue("Access-Control-Allow-Origin").orElse(""))
                .contains("*");

        // 2. CSS stylesheet
        HttpRequest cssReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + proxy.getLocalPort() + "/__admin/ui/style.css"))
                .GET()
                .build();
        HttpResponse<String> cssResp = client.send(cssReq, HttpResponse.BodyHandlers.ofString());
        assertThat(cssResp.statusCode()).isEqualTo(200);
        assertThat(cssResp.headers().firstValue("Content-Type").orElse("")).contains("text/css");

        // 3. JS module
        HttpRequest jsReq = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + proxy.getLocalPort() + "/__admin/ui/app.js"))
                .GET()
                .build();
        HttpResponse<String> jsResp = client.send(jsReq, HttpResponse.BodyHandlers.ofString());
        assertThat(jsResp.statusCode()).isEqualTo(200);
        assertThat(jsResp.headers().firstValue("Content-Type").orElse("")).contains("javascript");
    }

    @Test
    @DisplayName("Transparently proxies /__admin/mappings to default upstream target")
    void shouldProxyAdminMappingsToDefaultTarget() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + proxy.getLocalPort() + "/__admin/mappings"))
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("/api/cases");
        assertThat(resp.headers().firstValue("Access-Control-Allow-Origin").orElse(""))
                .contains("*");
    }

    @Test
    @DisplayName("Routes dynamically per-request using X-Forwarded-Host and X-Forwarded-Proto")
    void shouldRouteDynamicallyViaForwardedHeaders() throws Exception {
        WireMockServer secondary =
                new WireMockServer(WireMockConfiguration.options().dynamicPort());
        secondary.start();
        try {
            secondary.stubFor(
                    get(urlEqualTo("/api/staging/users")).willReturn(okJson("{\"users\": [\"Alice\", \"Bob\"]}")));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + proxy.getLocalPort() + "/api/staging/users"))
                    .header("X-Forwarded-Host", "localhost:" + secondary.port())
                    .header("X-Forwarded-Proto", "http")
                    .GET()
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.body()).contains("Alice");
            assertThat(resp.headers().firstValue("Access-Control-Allow-Origin").orElse(""))
                    .contains("*");
        } finally {
            secondary.stop();
        }
    }

    @Test
    @DisplayName("Supports remote base path prefix via X-Forwarded-Prefix")
    void shouldSupportForwardedPrefix() throws Exception {
        WireMockServer ingressServer =
                new WireMockServer(WireMockConfiguration.options().dynamicPort());
        ingressServer.start();
        try {
            // Remote WireMock is mounted behind a gateway at /test-envs/env123/wiremock
            ingressServer.stubFor(get(urlEqualTo("/test-envs/env123/wiremock/__admin/mappings"))
                    .willReturn(okJson("{\"mappings\": [{\"name\": \"Ingress Stub\"}]}")));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + proxy.getLocalPort() + "/__admin/mappings"))
                    .header("X-Forwarded-Host", "localhost:" + ingressServer.port())
                    .header("X-Forwarded-Proto", "http")
                    .header("X-Forwarded-Prefix", "/test-envs/env123/wiremock")
                    .GET()
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            assertThat(resp.statusCode()).isEqualTo(200);
            assertThat(resp.body()).contains("Ingress Stub");
            assertThat(resp.headers().firstValue("Access-Control-Allow-Origin").orElse(""))
                    .contains("*");
        } finally {
            ingressServer.stop();
        }
    }

    @Test
    @DisplayName("Serves version info endpoint at /__admin/ui/version with CORS headers")
    void shouldServeVersionInfo() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + proxy.getLocalPort() + "/__admin/ui/version"))
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("version");
        assertThat(resp.headers().firstValue("Access-Control-Allow-Origin").orElse(""))
                .contains("*");
    }

    @Test
    @DisplayName("Outputs complete local URI with target url and proxy mode pre-built")
    void shouldGenerateUiUrlWithPrebuiltTargetAndProxyStrategy() {
        String urlWithParams = proxy.getLocalUiUrl();
        assertThat(urlWithParams).startsWith("http://localhost:" + proxy.getLocalPort() + "/__admin/ui/?wiremock=");
        assertThat(urlWithParams).contains("&strategy=proxy");
        assertThat(urlWithParams)
                .contains(java.net.URLEncoder.encode(defaultWireMock.baseUrl(), StandardCharsets.UTF_8));

        String rawUrl = proxy.getLocalUiUrl(false);
        assertThat(rawUrl).isEqualTo("http://localhost:" + proxy.getLocalPort() + "/__admin/ui/");
    }
}
