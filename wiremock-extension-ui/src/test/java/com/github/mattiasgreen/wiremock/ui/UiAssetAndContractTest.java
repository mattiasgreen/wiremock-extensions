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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class UiAssetAndContractTest {

    private static final Pattern ES_IMPORT_PATTERN =
            Pattern.compile("(?:import|export)\\s+[^'\"]*?from\\s+['\"](\\.[^'\"]+)['\"]");
    private static final Pattern DOM_ID_LOOKUP_PATTERN = Pattern.compile("getElementById\\(['\"]([^'\"]+)['\"]\\)");

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
        assertThat(html).contains("WireMock Console");
        assertThat(html).contains("id=\"stub-list\"");
        assertThat(html).contains("id=\"search-box\"");
        assertThat(html).contains("id=\"journal-table\"");
        assertThat(html).contains("id=\"tab-tester\"");
        assertThat(html).contains("id=\"journal-search\"");
        assertThat(html).contains("id=\"journal-auto-refresh\"");
        assertThat(html).contains("id=\"journal-limit-input\"");
        assertThat(html).contains("id=\"filter-unmatched-only\"");
        assertThat(html).contains("id=\"journal-filtered-count\"");
        assertThat(html).contains("id=\"btn-test-stub\"");
        assertThat(html).contains("id=\"btn-copy-curl\"");
        assertThat(html).contains("id=\"btn-tester-send\"");
        assertThat(html).contains("id=\"btn-tester-share-link\"");
        assertThat(html).contains("id=\"nav-tab-netem\"");
        assertThat(html).contains("id=\"tab-netem\"");
        assertThat(html).contains("id=\"netem-journal-table\"");
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

        HttpRequest netemJsReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/ui/modules/netem.js"))
                .GET()
                .build();
        HttpResponse<String> netemJsResp = httpClient.send(netemJsReq, HttpResponse.BodyHandlers.ofString());
        assertThat(netemJsResp.statusCode()).isEqualTo(200);
        assertThat(netemJsResp.headers().firstValue("Content-Type").get()).contains("application/javascript");
        assertThat(netemJsResp.body()).contains("initNetEmTab");
        assertThat(netemJsResp.body()).contains("applyPreset");

        HttpRequest missingReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/ui/nonexistent.xyz"))
                .GET()
                .build();
        HttpResponse<String> missingResp = httpClient.send(missingReq, HttpResponse.BodyHandlers.ofString());
        assertThat(missingResp.statusCode()).isEqualTo(404);

        HttpRequest traversalReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/ui/modules/nonexistent.js"))
                .GET()
                .build();
        HttpResponse<String> traversalResp = httpClient.send(traversalReq, HttpResponse.BodyHandlers.ofString());
        assertThat(traversalResp.statusCode()).isEqualTo(404);
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

        // Unmatched request test
        httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(server.baseUrl() + "/non-existent"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        HttpRequest requestsUnmatchedFilterReq = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/requests?unmatched=true"))
                .GET()
                .build();
        HttpResponse<String> filterResp =
                httpClient.send(requestsUnmatchedFilterReq, HttpResponse.BodyHandlers.ofString());
        JsonNode filterJson = objectMapper.readTree(filterResp.body());
        assertThat(filterJson.has("requests")).isTrue();
        assertThat(filterJson.get("requests").size()).isEqualTo(1);
        assertThat(filterJson.get("requests").get(0).get("wasMatched").asBoolean())
                .isFalse();

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

    @Test
    void testStaticEsModuleImportsAndDomSelectorContracts() throws Exception {
        Path uiRoot = Path.of("src/main/resources/ui");
        assertThat(Files.isDirectory(uiRoot)).isTrue();

        String indexHtml = Files.readString(uiRoot.resolve("index.html"), StandardCharsets.UTF_8);
        // Air-gapped check: no external CDN scripts or stylesheets
        assertThat(indexHtml).doesNotContain("<script src=\"http");
        assertThat(indexHtml).doesNotContain("<link rel=\"stylesheet\" href=\"http");

        // Verify all relative ES6 module imports resolve on disk
        try (Stream<Path> paths = Files.walk(uiRoot)) {
            List<Path> jsFiles = paths.filter(p -> p.toString().endsWith(".js")).toList();
            assertThat(jsFiles).isNotEmpty();

            for (Path jsFile : jsFiles) {
                String content = Files.readString(jsFile, StandardCharsets.UTF_8);
                Matcher importMatcher = ES_IMPORT_PATTERN.matcher(content);
                while (importMatcher.find()) {
                    String importTarget = importMatcher.group(1);
                    Path resolved = jsFile.getParent().resolve(importTarget).normalize();
                    assertThat(Files.isRegularFile(resolved))
                            .as("ES6 import '%s' in %s must resolve to an existing file", importTarget, jsFile)
                            .isTrue();
                }
            }
        }

        // Verify all static getElementById(...) lookups in dom.js exist in index.html
        Path domJs = uiRoot.resolve("modules/dom.js");
        if (Files.isRegularFile(domJs)) {
            String domJsContent = Files.readString(domJs, StandardCharsets.UTF_8);
            Matcher idMatcher = DOM_ID_LOOKUP_PATTERN.matcher(domJsContent);
            int checkedDomIds = 0;
            while (idMatcher.find()) {
                String domId = idMatcher.group(1);
                assertThat(indexHtml)
                        .as("DOM element id='%s' referenced in modules/dom.js must exist in index.html", domId)
                        .contains("id=\"" + domId + "\"");
                checkedDomIds++;
            }
            assertThat(checkedDomIds).isGreaterThan(10);
        }

        // Collect all declared IDs in index.html and dynamic template IDs in JS modules
        Set<String> declaredIds = new HashSet<>();
        Matcher indexIdMatcher =
                Pattern.compile("id=[\"']([a-zA-Z0-9_-]+)[\"']").matcher(indexHtml);
        while (indexIdMatcher.find()) {
            declaredIds.add(indexIdMatcher.group(1));
        }

        try (Stream<Path> paths = Files.walk(uiRoot)) {
            List<Path> jsFiles = paths.filter(p -> p.toString().endsWith(".js")).toList();
            for (Path jsFile : jsFiles) {
                String content = Files.readString(jsFile, StandardCharsets.UTF_8);
                Matcher templateIdMatcher =
                        Pattern.compile("id=['\"]([a-zA-Z0-9_-]+)['\"]").matcher(content);
                while (templateIdMatcher.find()) {
                    declaredIds.add(templateIdMatcher.group(1));
                }
                Matcher idAssignmentMatcher = Pattern.compile("\\.id\\s*=\\s*['\"]([a-zA-Z0-9_-]+)['\"]")
                        .matcher(content);
                while (idAssignmentMatcher.find()) {
                    declaredIds.add(idAssignmentMatcher.group(1));
                }
            }
        }

        // Verify all getElementById(...) lookups across all JS modules resolve to a declared ID
        int checkedIds = 0;
        try (Stream<Path> paths = Files.walk(uiRoot)) {
            List<Path> jsFiles = paths.filter(p -> p.toString().endsWith(".js")).toList();
            for (Path jsFile : jsFiles) {
                String content = Files.readString(jsFile, StandardCharsets.UTF_8);
                Matcher idMatcher = DOM_ID_LOOKUP_PATTERN.matcher(content);
                while (idMatcher.find()) {
                    String domId = idMatcher.group(1);
                    assertThat(declaredIds)
                            .as(
                                    "DOM element id='%s' referenced in %s must be declared in index.html or JS templates",
                                    domId, jsFile.getFileName())
                            .contains(domId);
                    checkedIds++;
                }
            }
        }
        assertThat(checkedIds).isGreaterThan(50);
    }
}
