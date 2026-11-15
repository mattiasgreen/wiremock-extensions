package com.github.mattiasgreen.wiremock.ui;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mattiasgreen.wiremock.support.IndexedBodyLookup;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.Timing;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.ContentTypeHeader;
import com.github.tomakehurst.wiremock.http.Cookie;
import com.github.tomakehurst.wiremock.http.FormParameter;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.LoggedResponse;
import com.github.tomakehurst.wiremock.http.QueryParameter;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    @DisplayName(
            "Uses IndexedBodyLookup before scanning bodies and skips body decompression on definitive index hit/miss")
    void shouldUseIndexedBodyLookupAndSkipBodyScanWhenDefinitive() {
        AtomicInteger responseBodyReads = new AtomicInteger();
        AtomicInteger indexLookups = new AtomicInteger();

        UUID eventId = UUID.randomUUID();
        LoggedRequest request = LoggedRequest.createFrom(new Request() {
            @Override
            public String getUrl() {
                return "/api/orders/indexed-test";
            }

            @Override
            public String getAbsoluteUrl() {
                return "http://localhost:8080/api/orders/indexed-test";
            }

            @Override
            public RequestMethod getMethod() {
                return RequestMethod.POST;
            }

            @Override
            public String getScheme() {
                return "http";
            }

            @Override
            public String getHost() {
                return "localhost";
            }

            @Override
            public int getPort() {
                return 8080;
            }

            @Override
            public String getClientIp() {
                return "127.0.0.1";
            }

            @Override
            public String getHeader(String key) {
                return "X-Tenant-Header".equalsIgnoreCase(key) ? "acme-corp" : null;
            }

            @Override
            public HttpHeader header(String key) {
                return new HttpHeader(key, getHeader(key));
            }

            @Override
            public ContentTypeHeader contentTypeHeader() {
                return new ContentTypeHeader("application/json");
            }

            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders(new HttpHeader("X-Tenant-Header", "acme-corp"));
            }

            @Override
            public boolean containsHeader(String key) {
                return "X-Tenant-Header".equalsIgnoreCase(key);
            }

            @Override
            public Set<String> getAllHeaderKeys() {
                return Set.of("X-Tenant-Header");
            }

            @Override
            public Map<String, Cookie> getCookies() {
                return Collections.emptyMap();
            }

            @Override
            public QueryParameter queryParameter(String key) {
                return QueryParameter.absent(key);
            }

            @Override
            public FormParameter formParameter(String key) {
                return FormParameter.absent(key);
            }

            @Override
            public Map<String, FormParameter> formParameters() {
                return Collections.emptyMap();
            }

            @Override
            public byte[] getBody() {
                return new byte[0];
            }

            @Override
            public String getBodyAsString() {
                return "";
            }

            @Override
            public String getBodyAsBase64() {
                return "";
            }

            @Override
            public boolean isBrowserProxyRequest() {
                return false;
            }

            @Override
            public Optional<Request> getOriginalRequest() {
                return Optional.empty();
            }

            @Override
            public String getProtocol() {
                return "HTTP/1.1";
            }

            @Override
            public com.github.tomakehurst.wiremock.common.url.PathParams getPathParameters() {
                return com.github.tomakehurst.wiremock.common.url.PathParams.empty();
            }

            @Override
            public boolean isMultipart() {
                return false;
            }

            @Override
            public Collection<Part> getParts() {
                return Collections.emptyList();
            }

            @Override
            public Part getPart(String name) {
                return null;
            }
        });

        LoggedResponse countingResponse =
                new LoggedResponse(
                        200,
                        new HttpHeaders(new HttpHeader("X-Resp-Tag", "billing-tag")),
                        java.util.Base64.getEncoder()
                                .encodeToString("{\"note\":\"miss-in-index-but-in-body fallback-body-token\"}"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        null,
                        "") {
                    @Override
                    public String getBodyAsString() {
                        responseBodyReads.incrementAndGet();
                        return super.getBodyAsString();
                    }
                };

        ServeEvent event = new ServeEvent(
                eventId, request, null, ok().build(), countingResponse, true, Timing.UNTIMED, new LinkedList<>());

        IndexedBodyLookup lookup = (id, token) -> {
            assertThat(id).isEqualTo(eventId);
            indexLookups.incrementAndGet();
            if ("indexed-hit".equals(token)) {
                return Optional.of(true);
            }
            if ("miss-in-index-but-in-body".equals(token)) {
                return Optional.of(false);
            }
            return Optional.empty();
        };

        JournalFilterService service = new JournalFilterService(
                new ClientSourceResolver(ip -> "", 16, java.time.Duration.ofMinutes(1)), lookup);

        // 1. Header matches BEFORE indexed lookup and BEFORE body scan
        JournalFilterService.JournalFilterResult headerResult =
                service.filter(List.of(event), "acme-corp", "all", false, null, 10);
        assertThat(headerResult.filtered()).isEqualTo(1);
        assertThat(indexLookups.get()).isZero();
        assertThat(responseBodyReads.get()).isZero();

        // 2. Indexed definitive TRUE skips body scan
        JournalFilterService.JournalFilterResult hitResult =
                service.filter(List.of(event), "indexed-hit", "all", false, null, 10);
        assertThat(hitResult.filtered()).isEqualTo(1);
        assertThat(indexLookups.get()).isEqualTo(1);
        assertThat(responseBodyReads.get()).isZero();

        // 3. Indexed definitive FALSE skips body scan (even when token exists in body)
        JournalFilterService.JournalFilterResult missResult =
                service.filter(List.of(event), "miss-in-index-but-in-body", "all", false, null, 10);
        assertThat(missResult.filtered()).isZero();
        assertThat(indexLookups.get()).isEqualTo(2);
        assertThat(responseBodyReads.get()).isZero();

        // 4. Indexed Optional.empty() falls back to body scan
        JournalFilterService.JournalFilterResult fallbackResult =
                service.filter(List.of(event), "fallback-body-token", "all", false, null, 10);
        assertThat(fallbackResult.filtered()).isEqualTo(1);
        assertThat(indexLookups.get()).isEqualTo(3);
        assertThat(responseBodyReads.get()).isEqualTo(1);
    }
}
