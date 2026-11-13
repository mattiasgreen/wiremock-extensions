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
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JournalFilterServiceTest {

    private WireMockServer server;
    private HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    void startServer() {
        server =
                new WireMockServer(WireMockConfiguration.options().dynamicPort().extensions(new UiAdminApiEndpoint()));
        server.start();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterAll
    void stopServer() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
    }

    @BeforeEach
    void resetServer() {
        server.resetAll();
    }

    @Test
    @DisplayName("Boolean evaluateExpression: correctly handles AND, OR, and whitespace implicit AND")
    void testBooleanQueryEvaluator() {
        assertThat(JournalQueryMatcher.evaluateExpression(
                        "foo AND bar", token -> token.equals("foo") || token.equals("bar")))
                .isTrue();
        assertThat(JournalQueryMatcher.evaluateExpression("foo AND bar", token -> token.equals("foo")))
                .isFalse();
        assertThat(JournalQueryMatcher.evaluateExpression("foo OR bar", token -> token.equals("bar")))
                .isTrue();
        assertThat(JournalQueryMatcher.evaluateExpression("foo OR bar", token -> token.equals("baz")))
                .isFalse();
        assertThat(JournalQueryMatcher.evaluateExpression(
                        "foo bar", token -> token.equals("foo") || token.equals("bar")))
                .isTrue();
        assertThat(JournalQueryMatcher.evaluateExpression("A AND B OR C", token -> token.equals("c")))
                .isTrue();
    }

    @Test
    @DisplayName("GET /__admin/ui/api/requests: filters by boolean query and correlation ID")
    void testServerSideJournalFiltering() throws Exception {
        // Setup stubs
        server.stubFor(get(urlEqualTo("/api/cases"))
                .withHeader("X-Correlation-Id", equalTo("BANK-TX-1001"))
                .willReturn(okJson("{\"caseId\": \"CASE-1001\", \"status\": \"NEW\"}")));

        server.stubFor(post(urlEqualTo("/api/cases/1001/offer"))
                .withHeader("X-Correlation-Id", equalTo("BANK-TX-1001"))
                .willReturn(okJson("{\"status\": \"OFFER_READY\"}")));

        server.stubFor(get(urlEqualTo("/api/crm/customers/42"))
                .withHeader("X-Correlation-Id", equalTo("CRM-TRACE-99"))
                .willReturn(okJson("{\"customerId\": 42, \"name\": \"Alice\"}")));

        // Execute requests
        executeGet("/api/cases", "BANK-TX-1001");
        executePost("/api/cases/1001/offer", "BANK-TX-1001", "{\"amount\": 350}");
        executeGet("/api/crm/customers/42", "CRM-TRACE-99");
        executeGet("/api/unmatched/route", "ORPHAN-TRACE"); // 404

        // 1. Fetch all requests via /ui/api/requests
        JsonNode allRes = queryJournal("");
        assertThat(allRes.path("meta").path("total").asInt()).isEqualTo(4);
        assertThat(allRes.path("meta").path("filtered").asInt()).isEqualTo(4);
        assertThat(allRes.path("requests")).hasSize(4);

        // 2. Query with correlation ID
        JsonNode corrRes = queryJournal("q=BANK-TX-1001");
        assertThat(corrRes.path("meta").path("filtered").asInt()).isEqualTo(2);
        assertThat(corrRes.path("requests")).hasSize(2);

        // 3. Query with AND boolean operator
        JsonNode andRes = queryJournal("q=POST AND offer");
        assertThat(andRes.path("meta").path("filtered").asInt()).isEqualTo(1);
        assertThat(andRes.path("requests").get(0).path("request").path("url").asText())
                .isEqualTo("/api/cases/1001/offer");

        // 4. Query with OR boolean operator
        JsonNode orRes = queryJournal("q=crm OR unmatched");
        assertThat(orRes.path("meta").path("filtered").asInt()).isEqualTo(2);

        // 5. Query by verdict: unmatched
        JsonNode unmatchedRes = queryJournal("verdict=unmatched");
        assertThat(unmatchedRes.path("meta").path("filtered").asInt()).isEqualTo(1);
        assertThat(unmatchedRes.path("requests").get(0).path("wasMatched").asBoolean())
                .isFalse();

        // 6. Query by verdict: stub
        JsonNode stubRes = queryJournal("verdict=stub");
        assertThat(stubRes.path("meta").path("filtered").asInt()).isEqualTo(3);

        // 7. Limit truncation
        JsonNode limitRes = queryJournal("limit=2");
        assertThat(limitRes.path("meta").path("total").asInt()).isEqualTo(4);
        assertThat(limitRes.path("meta").path("filtered").asInt()).isEqualTo(4);
        assertThat(limitRes.path("requests")).hasSize(2);
    }

    private void executeGet(String path, String correlationId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + path))
                .header("X-Correlation-Id", correlationId)
                .GET()
                .build();
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void executePost(String path, String correlationId, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + path))
                .header("X-Correlation-Id", correlationId)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode queryJournal(String queryString) throws Exception {
        String encodedQuery = queryString.replace(" ", "%20");
        String uri = server.baseUrl() + "/__admin/ui/api/requests" + (encodedQuery.isBlank() ? "" : "?" + encodedQuery);
        HttpRequest request =
                HttpRequest.newBuilder().uri(URI.create(uri)).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    @Test
    @DisplayName("GET /__admin/ui/api/requests: filters by datetime range (since and until)")
    void testDatetimeRangeFiltering() throws Exception {
        Instant t0 = Instant.now().minusSeconds(10);
        executeGet("/api/cases", "BANK-TX-1001");
        Instant t1 = Instant.now().plusMillis(50);
        Thread.sleep(100);
        executeGet("/api/crm/customers/42", "CRM-TRACE-99");
        Instant t2 = Instant.now().plusSeconds(10);

        // Filter since t1 (only the second request)
        JsonNode sinceRes = queryJournal("since=" + t1.toEpochMilli());
        assertThat(sinceRes.path("meta").path("filtered").asInt()).isEqualTo(1);
        assertThat(sinceRes.path("requests").get(0).path("request").path("url").asText())
                .contains("/api/crm/customers/42");

        // Filter until t1 (only the first request)
        JsonNode untilRes = queryJournal("until=" + t1.toEpochMilli());
        assertThat(untilRes.path("meta").path("filtered").asInt()).isEqualTo(1);
        assertThat(untilRes.path("requests").get(0).path("request").path("url").asText())
                .contains("/api/cases");

        // Filter ISO string format
        JsonNode isoRes = queryJournal("since=" + t0.toString() + "&until=" + t2.toString());
        assertThat(isoRes.path("meta").path("filtered").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("Enriches sourceIp/sourceHost and filters by ip:, host:, src:, and boolean operators")
    void shouldEnrichAndFilterBySourceIpAndHostname() throws Exception {
        ClientSourceResolver mockResolver = new ClientSourceResolver(
                ip -> switch (ip) {
                    case "10.20.30.40" -> "billing-api-01.corp.internal";
                    case "10.20.30.41" -> "checkout-web-02.corp.internal";
                    default -> "";
                },
                200,
                java.time.Duration.ofMinutes(5));
        WireMockServer customServer = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .extensions(new UiAdminApiEndpoint(new JournalFilterService(mockResolver))));
        customServer.start();
        try {
            customServer.stubFor(get("/api/ping").willReturn(ok("pong")));
            String base = customServer.baseUrl();

            httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(base + "/api/ping?client=billing"))
                            .header("X-Forwarded-For", "10.20.30.40, 10.0.0.1")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(base + "/api/ping?client=checkout"))
                            .header("X-Forwarded-For", "10.20.30.41")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());

            // Verify enrichment fields on unfiltered request list
            HttpResponse<String> allResp = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(base + "/__admin/ui/api/requests"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(allResp.statusCode()).isEqualTo(200);
            JsonNode allJson = objectMapper.readTree(allResp.body());
            assertThat(allJson.path("requests")).hasSize(2);
            assertThat(allJson.path("requests").get(0).path("sourceIp").asText())
                    .isEqualTo("10.20.30.41");
            assertThat(allJson.path("requests").get(0).path("sourceHost").asText())
                    .isEqualTo("checkout-web-02.corp.internal");
            assertThat(allJson.path("requests").get(1).path("sourceIp").asText())
                    .isEqualTo("10.20.30.40");
            assertThat(allJson.path("requests").get(1).path("sourceHost").asText())
                    .isEqualTo("billing-api-01.corp.internal");

            // Filter by host:, ip:, src:, and boolean AND/OR
            JsonNode hostRes = queryCustomServer(customServer, "q=" + encode("host:billing-api-01"));
            assertThat(hostRes.path("meta").path("filtered").asInt()).isEqualTo(1);
            assertThat(hostRes.path("requests").get(0).path("sourceIp").asText())
                    .isEqualTo("10.20.30.40");

            JsonNode ipRes = queryCustomServer(customServer, "q=" + encode("ip:10.20.30.41"));
            assertThat(ipRes.path("meta").path("filtered").asInt()).isEqualTo(1);
            assertThat(ipRes.path("requests").get(0).path("sourceHost").asText())
                    .isEqualTo("checkout-web-02.corp.internal");

            JsonNode srcOrRes =
                    queryCustomServer(customServer, "q=" + encode("src:10.20.30.40 OR src:checkout-web-02"));
            assertThat(srcOrRes.path("meta").path("filtered").asInt()).isEqualTo(2);

            JsonNode andMissRes =
                    queryCustomServer(customServer, "q=" + encode("host:billing-api-01 AND ip:10.20.30.41"));
            assertThat(andMissRes.path("meta").path("filtered").asInt()).isZero();
        } finally {
            customServer.stop();
        }
    }

    private JsonNode queryCustomServer(WireMockServer target, String queryParams) throws Exception {
        HttpResponse<String> response = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(target.baseUrl() + "/__admin/ui/api/requests?" + queryParams))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return objectMapper.readTree(response.body());
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }
}
