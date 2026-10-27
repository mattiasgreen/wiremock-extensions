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
import java.util.List;
import java.util.Map;
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

    @Test
    @DisplayName("Should snapshot live proxied journal traffic into DISABLED stubs and toggle project mode")
    void shouldSnapshotProxiedTrafficAndToggleProjectMode() throws Exception {
        WireMockServer upstreamServer =
                new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstreamServer.start();

        try {
            // Upstream live service returns real data
            upstreamServer.stubFor(WireMock.get(urlEqualTo("/api/inventory/123"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"stock\":42,\"origin\":\"live-upstream\"}")));

            // Configure Primary server with a proxy stub for project "Warehouse"
            UUID proxyStubId = UUID.randomUUID();
            server.stubFor(WireMock.get(urlEqualTo("/api/inventory/123"))
                    .withId(proxyStubId)
                    .withName("[PROXY GET] /api/inventory/123")
                    .willReturn(aResponse().proxiedFrom(upstreamServer.baseUrl()))
                    .withMetadata(com.github.tomakehurst.wiremock.common.Metadata.metadata()
                            .attr("source", "openapi")
                            .attr("mode", "proxy")
                            .attr("project", "Warehouse")));

            // Client invokes primary WireMock
            HttpResponse<String> liveRes = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(server.baseUrl() + "/api/inventory/123"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(liveRes.statusCode()).isEqualTo(200);
            assertThat(liveRes.body()).contains("live-upstream");

            // Snapshot recorded traffic for project "Warehouse"
            HttpResponse<String> snapRes = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(server.baseUrl() + "/__admin/projects/Warehouse/recordings/snapshot"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(snapRes.statusCode()).isEqualTo(200);
            JsonNode snapJson = objectMapper.readTree(snapRes.body());
            assertThat(snapJson.get("totalRecorded").asInt()).isEqualTo(1);
            assertThat(snapJson.get("status").asText()).isEqualTo("disabled");

            // Verify the recorded stub was created in DISABLED store per default
            assertThat(disabledStubStore.size()).isEqualTo(1);
            com.github.tomakehurst.wiremock.stubbing.StubMapping recordedStub =
                    disabledStubStore.getAll().iterator().next();
            assertThat(recordedStub.getResponse().getStatus()).isEqualTo(200);
            assertThat(recordedStub.getResponse().getBody()).contains("live-upstream");
            assertThat(recordedStub.getMetadata().getString("project")).isEqualTo("Warehouse");
            assertThat(recordedStub.getMetadata().getString("source")).isEqualTo("recorded-proxy");

            // Now switch project "Warehouse" to STUBS mode!
            HttpResponse<String> modeRes = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(server.baseUrl() + "/__admin/projects/Warehouse/mode"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"stubs\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(modeRes.statusCode()).isEqualTo(200);
            JsonNode modeJson = objectMapper.readTree(modeRes.body());
            assertThat(modeJson.get("activeMode").asText()).isEqualTo("stubs");
            assertThat(modeJson.get("stubsEnabled").asInt()).isEqualTo(1);
            assertThat(modeJson.get("stubsDisabled").asInt()).isEqualTo(1);

            // Pull the plug on upstream (simulate offline testing)
            upstreamServer.stop();

            // Client requests again from Primary WireMock -> served 100% offline from the recorded stub!
            HttpResponse<String> offlineRes = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(server.baseUrl() + "/api/inventory/123"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(offlineRes.statusCode()).isEqualTo(200);
            assertThat(offlineRes.body()).contains("live-upstream");

        } finally {
            if (upstreamServer.isRunning()) {
                upstreamServer.stop();
            }
        }
    }

    @Test
    @DisplayName("Should snapshot only filtered request IDs to disabled stub store or active stubs")
    void shouldSnapshotFilteredRecordings() throws Exception {
        WireMockServer upstreamServer =
                new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstreamServer.start();

        try {
            upstreamServer.stubFor(WireMock.get(urlEqualTo("/api/customers/42"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"id\":42,\"name\":\"Alice\"}")));
            upstreamServer.stubFor(WireMock.get(urlEqualTo("/api/invoices/99"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"id\":99,\"amount\":150.0}")));

            // Configure proxy stub on server
            server.stubFor(
                    WireMock.any(urlMatching("/api/.*")).willReturn(aResponse().proxiedFrom(upstreamServer.baseUrl())));

            // Execute both requests through server
            httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(server.baseUrl() + "/api/customers/42"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(server.baseUrl() + "/api/invoices/99"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            // Fetch request IDs from server serve events
            HttpResponse<String> journalRes = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(server.baseUrl() + "/__admin/requests"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode journalJson = objectMapper.readTree(journalRes.body());
            String customerRequestId = null;
            String invoiceRequestId = null;
            for (JsonNode reqNode : journalJson.get("requests")) {
                String url = reqNode.get("request").get("url").asText();
                if ("/api/customers/42".equals(url)) {
                    customerRequestId = reqNode.get("id").asText();
                } else if ("/api/invoices/99".equals(url)) {
                    invoiceRequestId = reqNode.get("id").asText();
                }
            }
            assertThat(customerRequestId).isNotNull();
            assertThat(invoiceRequestId).isNotNull();

            // 1. Snapshot ONLY customer request into disabled store with project "CustomerDomain"
            String payload1 = objectMapper.writeValueAsString(
                    Map.of("requestIds", List.of(customerRequestId), "project", "CustomerDomain", "activate", false));
            HttpResponse<String> snapRes1 = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(server.baseUrl() + "/__admin/stubs/snapshot-filtered"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload1))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(snapRes1.statusCode()).isEqualTo(200);
            JsonNode snapJson1 = objectMapper.readTree(snapRes1.body());
            assertThat(snapJson1.get("totalRecorded").asInt()).isEqualTo(1);
            assertThat(snapJson1.get("status").asText()).isEqualTo("disabled");
            assertThat(snapJson1.get("project").asText()).isEqualTo("CustomerDomain");

            assertThat(disabledStubStore.size()).isEqualTo(1);
            com.github.tomakehurst.wiremock.stubbing.StubMapping disabledStub =
                    disabledStubStore.getAll().iterator().next();
            assertThat(disabledStub.getRequest().getUrl()).isEqualTo("/api/customers/42");
            assertThat(disabledStub.getResponse().getBody()).contains("Alice");
            assertThat(disabledStub.getMetadata().getString("project")).isEqualTo("CustomerDomain");

            // 2. Snapshot invoice request directly to ACTIVE mappings
            String payload2 = objectMapper.writeValueAsString(
                    Map.of("requestIds", List.of(invoiceRequestId), "project", "BillingDomain", "activate", true));
            HttpResponse<String> snapRes2 = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(server.baseUrl() + "/__admin/stubs/snapshot-filtered"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(payload2))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(snapRes2.statusCode()).isEqualTo(200);
            JsonNode snapJson2 = objectMapper.readTree(snapRes2.body());
            assertThat(snapJson2.get("totalRecorded").asInt()).isEqualTo(1);
            assertThat(snapJson2.get("status").asText()).isEqualTo("active");
            assertThat(snapJson2.get("project").asText()).isEqualTo("BillingDomain");

            // Active stubs should now include invoice stub
            boolean foundInvoiceStub = server.listAllStubMappings().getMappings().stream()
                    .anyMatch(s -> "/api/invoices/99".equals(s.getRequest().getUrl()));
            assertThat(foundInvoiceStub).isTrue();

        } finally {
            if (upstreamServer.isRunning()) {
                upstreamServer.stop();
            }
        }
    }

    @Test
    @DisplayName(
            "Should generate snapshot stub template via POST /stubs/snapshot-template with nested JSON and excluded fields")
    void shouldGenerateSnapshotStubTemplateWithNestedJsonAndExcludedFields() throws Exception {
        WireMockServer upstream =
                new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstream.start();

        try {
            upstream.stubFor(WireMock.post(urlEqualTo("/api/billing/charge"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"status\":\"CHARGED\",\"invoiceId\":\"INV-12345\"}")));

            server.stubFor(WireMock.post(urlMatching("/api/billing/.*"))
                    .atPriority(10)
                    .withMetadata(Map.of("project", "Billing", "mode", "proxy"))
                    .willReturn(aResponse().proxiedFrom(upstream.baseUrl())));

            // Send request with nested Base64 JSON and volatile fields
            String nestedJson = "{\"caseId\":\"CASE-999\",\"amount\":299.0}";
            String encodedNested = java.util.Base64.getEncoder()
                    .encodeToString(nestedJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String reqBody = String.format(
                    java.util.Locale.ROOT,
                    "{\"customerId\":\"CUST-42\",\"CORRELATION_ID\":\"TX-123\",\"REQUEST_TIMESTAMP\":1729000000,\"FML_ARCHIVE_JSON\":\"%s\"}",
                    encodedNested);

            HttpResponse<String> chargeRes = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(server.baseUrl() + "/api/billing/charge"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(reqBody))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(chargeRes.statusCode()).isEqualTo(200);

            List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> events = server.getAllServeEvents();
            assertThat(events).hasSize(1);
            String requestId = events.get(0).getId().toString();

            // Call /__admin/stubs/snapshot-template
            String templateReq = objectMapper.writeValueAsString(Map.of("requestId", requestId, "project", "Billing"));

            HttpResponse<String> templateRes = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(server.baseUrl() + "/__admin/stubs/snapshot-template"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(templateReq))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(templateRes.statusCode()).isEqualTo(200);
            JsonNode stubNode = objectMapper.readTree(templateRes.body());

            assertThat(stubNode.get("name").asText()).contains("[RECORDED POST 200]");
            assertThat(stubNode.get("request").get("url").asText()).isEqualTo("/api/billing/charge");

            // Verify body patterns contain business fields and exclude transient fields
            JsonNode bodyPatterns = stubNode.get("request").get("bodyPatterns");
            assertThat(bodyPatterns.isArray()).isTrue();
            assertThat(bodyPatterns.size()).isGreaterThanOrEqualTo(2);

            String bodyPatternsStr = bodyPatterns.toString();
            assertThat(bodyPatternsStr).contains("$.customerId");
            assertThat(bodyPatternsStr).contains("$.FML_ARCHIVE_JSON.caseId");
            assertThat(bodyPatternsStr).doesNotContain("CORRELATION_ID");
            assertThat(bodyPatternsStr).doesNotContain("REQUEST_TIMESTAMP");

            // Verify metadata
            assertThat(stubNode.get("metadata").get("mode").asText()).isEqualTo("static");
            assertThat(stubNode.get("metadata").get("source").asText()).isEqualTo("recorded-proxy");
            assertThat(stubNode.get("metadata").get("project").asText()).isEqualTo("Billing");

            // Verify response body is formatted
            assertThat(stubNode.get("response").get("body").asText()).contains("\"invoiceId\" : \"INV-12345\"");
        } finally {
            upstream.stop();
        }
    }
}
