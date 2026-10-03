package com.github.mattiasgreen.wiremock.ui;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class UiAssetAndContractTest {

    private WireMockServer server;
    private HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        server =
                new WireMockServer(WireMockConfiguration.options().dynamicPort().extensions(new UiAdminApiEndpoint()));
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
    void testHtmlAssetServedCorrectly() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/ui"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).isPresent();
        assertThat(response.headers().firstValue("Content-Type").get()).contains("text/html");

        String html = response.body();
        assertThat(html).contains("WireMock Stub Viewer");
        assertThat(html).contains("id=\"stub-list\"");
        assertThat(html).contains("id=\"search-box\"");
        assertThat(html).contains("id=\"journal-table\"");
        assertThat(html).contains("id=\"tab-tester\"");
        assertThat(html).contains("id=\"journal-search\"");
        assertThat(html).contains("id=\"journal-auto-refresh\"");
        assertThat(html).contains("id=\"btn-test-stub\"");
        assertThat(html).contains("id=\"btn-copy-curl\"");
        assertThat(html).contains("id=\"btn-tester-send\"");
        assertThat(html).contains("id=\"btn-tester-share-link\"");
    }

    @Test
    void testJsAndCssAssetsServedCorrectly() throws Exception {
        HttpRequest jsReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/ui/app.js"))
                .GET()
                .build();
        HttpResponse<String> jsResp = httpClient.send(jsReq, HttpResponse.BodyHandlers.ofString());
        assertThat(jsResp.statusCode()).isEqualTo(200);
        assertThat(jsResp.headers().firstValue("Content-Type").get()).contains("application/javascript");
        assertThat(jsResp.body()).contains("loadMappings");
        assertThat(jsResp.body()).contains("executeTesterRequest");
        assertThat(jsResp.body()).contains("highlightJson");
        assertThat(jsResp.body()).contains("generateCurl");
        assertThat(jsResp.body()).contains("parseHash");
        assertThat(jsResp.headers().firstValue("Cache-Control")).isPresent();

        HttpRequest cssReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/ui/style.css"))
                .GET()
                .build();
        HttpResponse<String> cssResp = httpClient.send(cssReq, HttpResponse.BodyHandlers.ofString());
        assertThat(cssResp.statusCode()).isEqualTo(200);
        assertThat(cssResp.headers().firstValue("Content-Type").get()).contains("text/css");
        assertThat(cssResp.body()).contains(":root");
        assertThat(cssResp.body()).contains(".json-key");
        assertThat(cssResp.body()).contains(".tester-container");

        HttpRequest missingReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/ui/nonexistent.xyz"))
                .GET()
                .build();
        HttpResponse<String> missingResp = httpClient.send(missingReq, HttpResponse.BodyHandlers.ofString());
        assertThat(missingResp.statusCode()).isEqualTo(404);
    }

    @Test
    void testAdminApiContractCompatibility() throws Exception {
        server.stubFor(get(urlEqualTo("/api/v1/contract-test"))
                .withName("Contract Test Stub")
                .willReturn(ok("OK")));

        HttpRequest mappingsReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/mappings"))
                .GET()
                .build();
        HttpResponse<String> mappingsResp = httpClient.send(mappingsReq, HttpResponse.BodyHandlers.ofString());
        assertThat(mappingsResp.statusCode()).isEqualTo(200);
        JsonNode mappingsJson = objectMapper.readTree(mappingsResp.body());
        assertThat(mappingsJson.has("mappings")).isTrue();
        assertThat(mappingsJson.get("mappings").isArray()).isTrue();

        HttpRequest requestsReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/requests"))
                .GET()
                .build();
        HttpResponse<String> requestsResp = httpClient.send(requestsReq, HttpResponse.BodyHandlers.ofString());
        assertThat(requestsResp.statusCode()).isEqualTo(200);
        JsonNode requestsJson = objectMapper.readTree(requestsResp.body());
        assertThat(requestsJson.has("requests")).isTrue();

        HttpRequest scenariosReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/scenarios"))
                .GET()
                .build();
        HttpResponse<String> scenariosResp = httpClient.send(scenariosReq, HttpResponse.BodyHandlers.ofString());
        assertThat(scenariosResp.statusCode()).isEqualTo(200);
        JsonNode scenariosJson = objectMapper.readTree(scenariosResp.body());
        assertThat(scenariosJson.has("scenarios")).isTrue();

        server.stubFor(get(urlEqualTo("/api/v1/sc-test"))
                .inScenario("TestScenario")
                .whenScenarioStateIs("Started")
                .willSetStateTo("STEP_1")
                .willReturn(ok("OK")));

        HttpRequest putStateReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/scenarios/TestScenario/state"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"state\": \"STEP_1\"}"))
                .build();
        HttpResponse<String> putResp = httpClient.send(putStateReq, HttpResponse.BodyHandlers.ofString());
        assertThat(putResp.statusCode()).isEqualTo(200);
    }
}
