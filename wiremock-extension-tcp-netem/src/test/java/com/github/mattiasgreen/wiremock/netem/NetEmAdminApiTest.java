package com.github.mattiasgreen.wiremock.netem;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mattiasgreen.wiremock.netem.admin.TcpNetEmExtensionFactory;
import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmRegistry;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NetEmAdminApiTest {

    private WireMockServer wireMockServer;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;
    private String adminBaseUrl;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort().extensions(new TcpNetEmExtensionFactory()));
        wireMockServer.start();

        adminBaseUrl = "http://127.0.0.1:" + wireMockServer.port() + "/__admin";
        httpClient =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        objectMapper = new ObjectMapper();

        TcpNetEmRegistry.getInstance().closeAll();
    }

    @AfterEach
    void tearDown() {
        TcpNetEmRegistry.getInstance().closeAll();
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void shouldCreateInspectAndManipulateProxiesViaDedicatedApi() throws Exception {
        // 1. Create proxy
        String createJson =
                """
            {
                "name": "service-alpha",
                "listenPort": 0,
                "targetHost": "127.0.0.1",
                "targetPort": %d,
                "enabled": true
            }
            """
                        .formatted(wireMockServer.port());

        HttpResponse<String> createResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(adminBaseUrl + "/tcp-netem/proxies"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(createJson))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(createResp.statusCode()).isEqualTo(201);
        JsonNode createNode = objectMapper.readTree(createResp.body());
        assertThat(createNode.get("name").asText()).isEqualTo("service-alpha");
        assertThat(createNode.get("listenPort").asInt()).isGreaterThan(0);

        // 2. Add Toxic
        String toxicJson =
                """
            {
                "name": "latency-down",
                "type": "latency",
                "stream": "downstream",
                "toxicity": 1.0,
                "attributes": {
                    "latency": 120,
                    "jitter": 10
                }
            }
            """;

        HttpResponse<String> toxicResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(adminBaseUrl + "/tcp-netem/proxies/service-alpha/toxics"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(toxicJson))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(toxicResp.statusCode()).isEqualTo(200);

        // 3. Inspect proxy details
        HttpResponse<String> getResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(adminBaseUrl + "/tcp-netem/proxies/service-alpha"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(getResp.statusCode()).isEqualTo(200);
        JsonNode getNode = objectMapper.readTree(getResp.body());
        assertThat(getNode.get("toxics")).hasSize(1);
        assertThat(getNode.get("toxics").get(0).get("name").asText()).isEqualTo("latency-down");

        // 4. Apply preset
        HttpResponse<String> presetResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(adminBaseUrl + "/tcp-netem/proxies/service-alpha/presets/3G_MOBILE"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(presetResp.statusCode()).isEqualTo(200);
        JsonNode presetNode = objectMapper.readTree(presetResp.body());
        assertThat(presetNode.get("toxics")).hasSize(2);

        // 5. Reset all
        HttpResponse<String> resetResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(adminBaseUrl + "/tcp-netem/reset"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resetResp.statusCode()).isEqualTo(200);

        HttpResponse<String> afterReset = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(adminBaseUrl + "/tcp-netem/proxies/service-alpha"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        JsonNode afterResetNode = objectMapper.readTree(afterReset.body());
        assertThat(afterResetNode.get("toxics")).isEmpty();
    }

    @Test
    void shouldSupportToxiproxyV2ApiCompatibility() throws Exception {
        // 1. Create proxy with Toxiproxy JSON contract
        String createJson =
                """
            {
                "name": "toxiproxy-alpha",
                "listen": "127.0.0.1:0",
                "upstream": "127.0.0.1:%d",
                "enabled": true
            }
            """
                        .formatted(wireMockServer.port());

        HttpResponse<String> createResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(adminBaseUrl + "/proxies"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(createJson))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(createResp.statusCode()).isEqualTo(201);

        // 2. List proxies via GET /__admin/proxies (returns map of proxies)
        HttpResponse<String> listResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(adminBaseUrl + "/proxies"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(listResp.statusCode()).isEqualTo(200);
        JsonNode mapNode = objectMapper.readTree(listResp.body());
        assertThat(mapNode.has("toxiproxy-alpha")).isTrue();
        assertThat(mapNode.get("toxiproxy-alpha").get("upstream").asText())
                .isEqualTo("127.0.0.1:" + wireMockServer.port());

        // 3. Reset via POST /__admin/reset
        HttpResponse<String> resetResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(adminBaseUrl + "/proxies/reset"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resetResp.statusCode()).isEqualTo(200);
    }
}
