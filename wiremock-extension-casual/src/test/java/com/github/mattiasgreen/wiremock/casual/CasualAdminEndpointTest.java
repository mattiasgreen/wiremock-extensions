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
        httpClient = HttpClient.newHttpClient();
        objectMapper = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
        CasualRegistry.getInstance().reset();
    }

    @Test
    void shouldReturnConfigJsonWithDefaultValues() throws Exception {
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
        assertThat(root.get("running").asBoolean()).isFalse();
    }

    @Test
    void shouldUpdateConfigAndGatewayViaPost() throws Exception {
        CasualWireMockGateway gateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(wireMockServer)
                .build();
        CasualRegistry.getInstance().setGateway(gateway);

        String updateJson =
                """
                {
                    "enabled": false,
                    "port": 8888
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
        assertThat(root.get("enabled").asBoolean()).isFalse();
        assertThat(root.get("port").asInt()).isEqualTo(8888);
    }

    @Test
    void shouldResetConfigViaPost() throws Exception {
        CasualConfiguration custom = new CasualConfiguration();
        custom.setEnabled(false);
        custom.setPort(9999);
        CasualRegistry.getInstance().updateConfig(custom);

        HttpRequest resetReq = HttpRequest.newBuilder()
                .uri(URI.create(adminBaseUrl + "/casual/reset"))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> response = httpClient.send(resetReq, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode root = objectMapper.readTree(response.body());
        assertThat(root.get("enabled").asBoolean()).isTrue();
        assertThat(root.get("port").asInt()).isEqualTo(7772);
    }

    @Test
    void shouldExecuteCasualCallViaAdminApi() throws Exception {
        com.github.tomakehurst.wiremock.client.WireMock wireMock =
                new com.github.tomakehurst.wiremock.client.WireMock(wireMockServer.port());
        wireMock.register(com.github.tomakehurst.wiremock.client.WireMock.post(
                        com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/__casual/services/adminCallTest"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"adminCalled\": true, \"id\": 999}")));

        CasualWireMockGateway gateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockBaseUrl("http://127.0.0.1:" + wireMockServer.port())
                .build();
        gateway.start();
        CasualRegistry.getInstance().setGateway(gateway);

        try {
            String callReqJson =
                    """
                    {
                        "service": "adminCallTest",
                        "payload": "{\\"test\\": 1}",
                        "port": %d
                    }
                    """
                            .formatted(gateway.getPort());

            HttpRequest callReq = HttpRequest.newBuilder()
                    .uri(URI.create(adminBaseUrl + "/casual/call"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(callReqJson))
                    .build();

            HttpResponse<String> response = httpClient.send(callReq, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode root = objectMapper.readTree(response.body());
            assertThat(root.get("success").asBoolean()).isTrue();
            assertThat(root.get("service").asText()).isEqualTo("adminCallTest");
            assertThat(root.get("errorState").asText()).isEqualTo("OK");
            assertThat(root.get("payload").asText()).contains("\"adminCalled\": true");
            assertThat(root.has("headers")).isTrue();
            assertThat(root.get("headers").get("Content-Type").asText()).isEqualTo("application/json");
            assertThat(root.get("headers").get("X-Casual-Error-State").asText()).isEqualTo("OK");
            assertThat(root.has("requestHeaders")).isTrue();
            assertThat(root.get("requestHeaders").get("X-Casual-Service").asText())
                    .isEqualTo("adminCallTest");

            // Also test discover
            String discoverReqJson =
                    """
                    {
                        "services": ["adminCallTest"],
                        "port": %d
                    }
                    """
                            .formatted(gateway.getPort());

            HttpRequest discReq = HttpRequest.newBuilder()
                    .uri(URI.create(adminBaseUrl + "/casual/discover"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(discoverReqJson))
                    .build();

            HttpResponse<String> discResp = httpClient.send(discReq, HttpResponse.BodyHandlers.ofString());
            assertThat(discResp.statusCode()).isEqualTo(200);
            JsonNode discRoot = objectMapper.readTree(discResp.body());
            assertThat(discRoot.get("domainName").asText()).isEqualTo("wiremock-casual-domain");
            assertThat(discRoot.get("services").toString()).contains("adminCallTest");
        } finally {
            gateway.close();
        }
    }
}
