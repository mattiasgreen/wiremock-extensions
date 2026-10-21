package com.github.mattiasgreen.wiremock.cli;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.netem.admin.TcpNetEmAdminEndpoint;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WireMockCliIntegrationTest {

    private WireMockServer wireMockServer;
    private ByteArrayOutputStream outCapture;
    private ByteArrayOutputStream errCapture;
    private PrintStream out;
    private PrintStream err;

    @BeforeEach
    void setUp() {
        outCapture = new ByteArrayOutputStream();
        errCapture = new ByteArrayOutputStream();
        out = new PrintStream(outCapture, true, StandardCharsets.UTF_8);
        err = new PrintStream(errCapture, true, StandardCharsets.UTF_8);

        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();

        // Register default sample stubs
        wireMockServer.stubFor(
                get(urlEqualTo("/api/v1/cases")).withName("List Cases").willReturn(okJson("{\"cases\": []}")));

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/cases"))
                .withName("Create Case")
                .willReturn(created().withBody("{\"id\": 101}")));

        wireMockServer.stubFor(
                get(urlEqualTo("/api/v1/orders")).withName("List Orders").willReturn(okJson("{\"orders\": []}")));
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    @Test
    @DisplayName("wm info: probes running server and reports core capabilities")
    void shouldProbeServerInfo() {
        int code = WireMockCli.run(new String[] {"info", "-s", wireMockServer.baseUrl(), "--no-color"}, out, err);

        assertThat(code).isEqualTo(0);
        String output = outCapture.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("WireMock Instance Probe", "Core Capabilities", "Stubs Catalog", "Request Journal");
    }

    @Test
    @DisplayName("wm stubs: lists all stubs and applies AND/OR filters correctly")
    void shouldListAndFilterStubs() {
        // 1. List all stubs
        int code = WireMockCli.run(new String[] {"stubs", "-s", wireMockServer.baseUrl(), "--no-color"}, out, err);

        assertThat(code).isEqualTo(0);
        String allOutput = outCapture.toString(StandardCharsets.UTF_8);
        assertThat(allOutput).contains("/api/v1/cases", "/api/v1/orders", "List Cases", "Create Case");
        assertThat(allOutput).contains("Showing 3 of 3 stubs");

        // 2. Filter with AND: "GET and /cases"
        outCapture.reset();
        int codeFilter = WireMockCli.run(
                new String[] {"stubs", "GET and /cases", "-s", wireMockServer.baseUrl(), "--no-color"}, out, err);

        assertThat(codeFilter).isEqualTo(0);
        String filterOutput = outCapture.toString(StandardCharsets.UTF_8);
        assertThat(filterOutput).contains("List Cases");
        assertThat(filterOutput).doesNotContain("Create Case");
        assertThat(filterOutput).doesNotContain("List Orders");
        assertThat(filterOutput).contains("Showing 1 of 3 stubs");

        // 3. Export as raw JSON
        outCapture.reset();
        int codeJson =
                WireMockCli.run(new String[] {"stubs", "POST", "--json", "-s", wireMockServer.baseUrl()}, out, err);

        assertThat(codeJson).isEqualTo(0);
        String jsonOutput = outCapture.toString(StandardCharsets.UTF_8);
        assertThat(jsonOutput).startsWith("[").contains("\"Create Case\"", "\"method\": \"POST\"");
    }

    @Test
    @DisplayName("wm tail: records served traffic, timestamps, latency, and near-miss warnings")
    void shouldTailRequestJournal() throws Exception {
        HttpClient http = HttpClient.newHttpClient();

        // Fire 2 matching requests and 1 unmatched 404
        http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(wireMockServer.baseUrl() + "/api/v1/cases"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(wireMockServer.baseUrl() + "/api/v1/cases"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        http.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(wireMockServer.baseUrl() + "/api/v1/casses-typo"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        // 1. Run standard tail
        int code = WireMockCli.run(
                new String[] {"tail", "-n", "10", "-s", wireMockServer.baseUrl(), "--no-color"}, out, err);

        assertThat(code).isEqualTo(0);
        String output = outCapture.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("/api/v1/cases", "200", "201");
        assertThat(output).contains("/api/v1/casses-typo", "404", "UNMATCHED");

        // 2. Run tail with unmatched filter (-u)
        outCapture.reset();
        int codeUnmatched =
                WireMockCli.run(new String[] {"tail", "-u", "-s", wireMockServer.baseUrl(), "--no-color"}, out, err);

        assertThat(codeUnmatched).isEqualTo(0);
        String unmatchedOutput = outCapture.toString(StandardCharsets.UTF_8);
        assertThat(unmatchedOutput).contains("/api/v1/casses-typo");
        assertThat(unmatchedOutput).doesNotContain("200", "201");
    }

    @Test
    @DisplayName("wm toxics: degrades gracefully when tcp-netem extension is not installed")
    void shouldDegradeGracefullyOnVanillaServer() {
        int code = WireMockCli.run(new String[] {"toxics", "-s", wireMockServer.baseUrl(), "--no-color"}, out, err);

        assertThat(code).isEqualTo(0);
        String output = outCapture.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("wiremock-extension-tcp-netem is not installed");
    }

    @Test
    @DisplayName("wm toxics: inspects active proxies and toxics when extension is present")
    void shouldInspectToxicsWhenExtensionPresent() throws Exception {
        WireMockServer netemServer = new WireMockServer(
                WireMockConfiguration.options().dynamicPort().extensions(new TcpNetEmAdminEndpoint()));
        netemServer.start();

        try {
            com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmRegistry.getInstance()
                    .register(new com.github.mattiasgreen.wiremock.netem.model.NetEmProxyConfig(
                            "wiremock-tcp-ingress", 0, "127.0.0.1", netemServer.port(), true));

            int code = WireMockCli.run(new String[] {"toxics", "-s", netemServer.baseUrl(), "--no-color"}, out, err);

            assertThat(code).isEqualTo(0);
            String output = outCapture.toString(StandardCharsets.UTF_8);
            assertThat(output).contains("Proxy:", "wiremock-tcp-ingress");
        } finally {
            com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmRegistry.getInstance()
                    .remove("wiremock-tcp-ingress");
            netemServer.stop();
        }
    }

    @Test
    @DisplayName("wm without running server: reports actionable connection error instead of unknown error")
    void shouldReportActionableConnectionErrorWhenServerUnreachable() {
        int unusedPort = 59999;
        String unreachableUrl = "http://localhost:" + unusedPort;

        int code = WireMockCli.run(new String[] {"info", "-s", unreachableUrl, "--no-color"}, out, err);

        assertThat(code).isEqualTo(1);
        String errOutput = errCapture.toString(StandardCharsets.UTF_8);
        assertThat(errOutput).contains("Cannot connect to WireMock server at http://localhost:" + unusedPort);
        assertThat(errOutput).contains("--server");
        assertThat(errOutput).contains("WIREMOCK_URL");
        assertThat(errOutput).doesNotContain("Unknown error");
    }
}
