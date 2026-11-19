package com.github.mattiasgreen.wiremock.ui;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UiAdminApiEndpointTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static WireMockServer wireMockServer;
    private static HttpClient httpClient;

    @BeforeAll
    static void startServer() {
        ClientSourceResolver resolver = new ClientSourceResolver(
                ip -> "10.20.30.40".equals(ip) ? "billing-svc.corp.internal" : "", 500L, Duration.ofMinutes(5));
        JournalFilterService filterService = new JournalFilterService(resolver);
        wireMockServer = new WireMockServer(wireMockConfig()
                .dynamicPort()
                .templatingEnabled(true)
                .extensions(new UiAdminApiEndpoint(filterService)));
        wireMockServer.start();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stopServer() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void resetServer() {
        wireMockServer.resetAll();
    }

    @Test
    @DisplayName(
            "GET /__admin/ui/api/requests?summary=true strips bodies while preserving metadata, search, and detail hydration")
    void shouldStripBodiesInSummaryModeAndHydrateFullDetailById() throws Exception {
        String largeReqBody = "{\"customerId\":\"CUST-LAZY-99\",\"blob\":\"" + "A".repeat(25_000) + "\"}";
        String largeResBody =
                "{\"status\":\"ACCEPTED\",\"echo\":\"{{request.path}}\",\"blob\":\"" + "B".repeat(25_000) + "\"}";

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/orders"))
                .withName("Create Large Order")
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withTransformers("response-template")
                        .withBody(largeResBody)));

        HttpResponse<String> trafficResp = httpClient.send(
                HttpRequest.newBuilder(URI.create(wireMockServer.baseUrl() + "/api/v1/orders"))
                        .header("Content-Type", "application/json")
                        .header("X-Forwarded-For", "10.20.30.40")
                        .POST(HttpRequest.BodyPublishers.ofString(largeReqBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(trafficResp.statusCode()).isEqualTo(201);

        // 1. Summary list request: matches body query server-side, returns slim metadata without bodies
        HttpResponse<String> summaryResp = httpClient.send(
                HttpRequest.newBuilder(URI.create(
                                wireMockServer.baseUrl() + "/__admin/ui/api/requests?summary=true&q=CUST-LAZY-99"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(summaryResp.statusCode()).isEqualTo(200);
        assertThat(summaryResp.body().length()).isLessThan(4_000);

        JsonNode summaryRoot = MAPPER.readTree(summaryResp.body());
        assertThat(summaryRoot.path("meta").path("summary").asBoolean()).isTrue();
        assertThat(summaryRoot.path("meta").path("filtered").asInt()).isEqualTo(1);
        JsonNode item = summaryRoot.path("requests").get(0);
        String eventId = item.path("id").asText();
        assertThat(eventId).isNotBlank();
        assertThat(item.path("summaryOnly").asBoolean()).isTrue();
        assertThat(item.path("wasMatched").asBoolean()).isTrue();
        assertThat(item.path("sourceIp").asText()).isEqualTo("10.20.30.40");
        assertThat(item.path("sourceHost").asText()).isEqualTo("billing-svc.corp.internal");
        assertThat(item.path("request").has("body")).isFalse();
        assertThat(item.path("request").has("bodyAsBase64")).isFalse();
        assertThat(item.path("response").has("body")).isFalse();
        assertThat(item.path("responseDefinition").has("body")).isFalse();
        assertThat(item.path("stubMapping").path("name").asText()).isEqualTo("Create Large Order");
        assertThat(item.path("stubMapping").path("response").path("isTemplated").asBoolean())
                .isTrue();

        // 2. Single-event detail endpoint returns complete request/response bodies + source enrichment
        HttpResponse<String> detailResp = httpClient.send(
                HttpRequest.newBuilder(URI.create(wireMockServer.baseUrl() + "/__admin/ui/api/requests/" + eventId))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(detailResp.statusCode()).isEqualTo(200);
        JsonNode detail = MAPPER.readTree(detailResp.body());
        assertThat(detail.path("id").asText()).isEqualTo(eventId);
        assertThat(detail.path("sourceIp").asText()).isEqualTo("10.20.30.40");
        assertThat(detail.path("sourceHost").asText()).isEqualTo("billing-svc.corp.internal");
        assertThat(detail.path("request").path("body").asText()).isEqualTo(largeReqBody);
        assertThat(detail.path("response").path("body").asText()).contains("/api/v1/orders");
        assertThat(detail.path("response").path("body").asText().length()).isGreaterThan(25_000);

        // 3. Default (non-summary) list endpoint preserves backwards-compatible full bodies
        HttpResponse<String> fullListResp = httpClient.send(
                HttpRequest.newBuilder(URI.create(wireMockServer.baseUrl() + "/__admin/ui/api/requests"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(fullListResp.statusCode()).isEqualTo(200);
        assertThat(fullListResp.body().length()).isGreaterThan(50_000);
    }

    @Test
    @DisplayName("GET /__admin/ui/api/requests/{id} returns 400 for invalid UUID and 404 for unknown UUID")
    void shouldReturn400And404ForInvalidOrMissingDetailIds() throws Exception {
        HttpResponse<String> badIdResp = httpClient.send(
                HttpRequest.newBuilder(URI.create(wireMockServer.baseUrl() + "/__admin/ui/api/requests/not-a-uuid"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(badIdResp.statusCode()).isEqualTo(400);

        HttpResponse<String> missingResp = httpClient.send(
                HttpRequest.newBuilder(
                                URI.create(wireMockServer.baseUrl() + "/__admin/ui/api/requests/" + UUID.randomUUID()))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(missingResp.statusCode()).isEqualTo(404);
    }
}
