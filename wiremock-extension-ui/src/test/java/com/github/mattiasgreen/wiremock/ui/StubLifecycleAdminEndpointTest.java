package com.github.mattiasgreen.wiremock.ui;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StubLifecycleAdminEndpointTest {

    private WireMockServer server;
    private HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private DisabledStubStore disabledStubStore;

    @BeforeEach
    void setUp() {
        disabledStubStore = new DisabledStubStore();
        server = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .extensions(new UiAdminApiEndpoint(), new StubLifecycleAdminEndpoint(disabledStubStore)));
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
    @DisplayName("Should disable active stub, isolate traffic, and enable it back")
    void shouldDisableAndEnableStub() throws Exception {
        UUID stubId = UUID.randomUUID();
        server.stubFor(WireMock.get(urlEqualTo("/api/orders/99"))
                .withId(stubId)
                .withName("Order 99")
                .willReturn(aResponse().withStatus(200).withBody("{\"order\":99}")));

        // 1. Verify stub is active and serves traffic
        HttpResponse<String> liveRes = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(server.baseUrl() + "/api/orders/99"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(liveRes.statusCode()).isEqualTo(200);
        assertThat(liveRes.body()).contains("{\"order\":99}");

        // 2. Disable stub via POST /__admin/stubs/{id}/disable
        HttpResponse<String> disableRes = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(server.baseUrl() + "/__admin/stubs/" + stubId + "/disable"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(disableRes.statusCode()).isEqualTo(200);

        JsonNode disableJson = objectMapper.readTree(disableRes.body());
        assertThat(disableJson.get("status").asText()).isEqualTo("disabled");
        assertThat(disableJson.get("id").asText()).isEqualTo(stubId.toString());

        // 3. Verify stub is no longer matched by WireMock (returns 404)
        HttpResponse<String> disabledTrafficRes = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(server.baseUrl() + "/api/orders/99"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(disabledTrafficRes.statusCode()).isEqualTo(404);

        // 4. Verify stub appears in /__admin/stubs/disabled
        HttpResponse<String> listDisabledRes = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(server.baseUrl() + "/__admin/stubs/disabled"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(listDisabledRes.statusCode()).isEqualTo(200);
        JsonNode disabledListJson = objectMapper.readTree(listDisabledRes.body());
        assertThat(disabledListJson.get("mappings").isArray()).isTrue();
        assertThat(disabledListJson.get("mappings")).hasSize(1);
        assertThat(disabledListJson.get("mappings").get(0).get("id").asText()).isEqualTo(stubId.toString());

        // 5. Re-enable stub via POST /__admin/stubs/{id}/enable
        HttpResponse<String> enableRes = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(server.baseUrl() + "/__admin/stubs/" + stubId + "/enable"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(enableRes.statusCode()).isEqualTo(200);
        JsonNode enableJson = objectMapper.readTree(enableRes.body());
        assertThat(enableJson.get("status").asText()).isEqualTo("enabled");

        // 6. Verify traffic is matched again
        HttpResponse<String> reEnabledTrafficRes = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(server.baseUrl() + "/api/orders/99"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(reEnabledTrafficRes.statusCode()).isEqualTo(200);
        assertThat(reEnabledTrafficRes.body()).contains("{\"order\":99}");
    }

    @Test
    @DisplayName("Should toggle stub and perform bulk disable/delete")
    void shouldToggleAndBulkProcessStubs() throws Exception {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        server.stubFor(WireMock.get(urlEqualTo("/test/1")).withId(id1).willReturn(ok()));
        server.stubFor(WireMock.get(urlEqualTo("/test/2")).withId(id2).willReturn(ok()));

        // Toggle id1 (active -> disabled)
        HttpResponse<String> toggle1 = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(server.baseUrl() + "/__admin/stubs/" + id1 + "/toggle"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(toggle1.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(toggle1.body()).get("status").asText()).isEqualTo("disabled");

        // Bulk disable id2
        String bulkDisableBody = "{\"action\":\"disable\",\"ids\":[\"" + id2 + "\"]}";
        HttpResponse<String> bulkRes = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(server.baseUrl() + "/__admin/stubs/bulk"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(bulkDisableBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(bulkRes.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(bulkRes.body()).get("processed").asInt())
                .isEqualTo(1);

        // Verify both are now disabled
        assertThat(disabledStubStore.size()).isEqualTo(2);

        // Bulk delete both
        String bulkDeleteBody = "{\"action\":\"delete\",\"ids\":[\"" + id1 + "\",\"" + id2 + "\"]}";
        HttpResponse<String> deleteRes = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(server.baseUrl() + "/__admin/stubs/bulk"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(bulkDeleteBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(deleteRes.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(deleteRes.body()).get("processed").asInt())
                .isEqualTo(2);
        assertThat(disabledStubStore.size()).isEqualTo(0);
    }
}
