package com.github.mattiasgreen.wiremock.casual;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CasualAdminEndpointTest {

    private WireMockServer wireMockServer;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;
    private String adminBaseUrl;

    @BeforeEach
    void setUp() {
        CasualRegistry.getInstance().reset();

        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort().extensions(new CasualExtensionFactory()));
        wireMockServer.start();

        adminBaseUrl = "http://127.0.0.1:" + wireMockServer.port() + "/__admin";
        httpClient =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        CasualRegistry.getInstance().reset();
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void shouldReturnDefaultConfigOnGet() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(adminBaseUrl + "/casual/config"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/json");

        JsonNode root = objectMapper.readTree(response.body());
        assertThat(root.get("enabled").asBoolean()).isTrue();
        assertThat(root.get("port").asInt()).isEqualTo(7772);
        assertThat(root.get("upstreamHost").isNull()).isTrue();
        assertThat(root.get("upstreamPort").asInt()).isEqualTo(0);
        assertThat(root.get("upstreamEnabled").asBoolean()).isFalse();
        assertThat(root.get("recordStubs").asBoolean()).isFalse();
        assertThat(root.get("running").asBoolean()).isFalse();
    }

    @Test
    void shouldUpdateConfigAndGatewayViaPost() throws Exception {
        // Register mock/real gateway in registry to verify dynamic update
        CasualWireMockGateway gateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockBaseUrl("http://127.0.0.1:" + wireMockServer.port())
                .build();
        CasualRegistry.getInstance().setGateway(gateway);

        String updateJson =
                """
                {
                    "upstreamHost": "upstream.casual.local",
                    "upstreamPort": 7780,
                    "recordStubs": true
                }
                """;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(adminBaseUrl + "/casual/config"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(updateJson))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode root = objectMapper.readTree(response.body());
        assertThat(root.get("upstreamHost").asText()).isEqualTo("upstream.casual.local");
        assertThat(root.get("upstreamPort").asInt()).isEqualTo(7780);
        assertThat(root.get("upstreamEnabled").asBoolean()).isTrue();
        assertThat(root.get("recordStubs").asBoolean()).isTrue();

        // Verify gateway was updated dynamically
        assertThat(gateway.getUpstreamHost()).isEqualTo("upstream.casual.local");
        assertThat(gateway.getUpstreamPort()).isEqualTo(7780);
        assertThat(gateway.isUpstreamEnabled()).isTrue();
        assertThat(gateway.isRecordStubs()).isTrue();
    }

    @Test
    void shouldResetConfigViaPost() throws Exception {
        CasualConfiguration custom = new CasualConfiguration();
        custom.setUpstreamHost("temp.host");
        custom.setUpstreamPort(9999);
        custom.setRecordStubs(true);
        CasualRegistry.getInstance().updateConfig(custom);

        HttpRequest resetReq = HttpRequest.newBuilder()
                .uri(URI.create(adminBaseUrl + "/casual/reset"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = httpClient.send(resetReq, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode root = objectMapper.readTree(response.body());
        assertThat(root.get("upstreamHost").isNull()).isTrue();
        assertThat(root.get("upstreamPort").asInt()).isEqualTo(0);
        assertThat(root.get("recordStubs").asBoolean()).isFalse();
    }
}
