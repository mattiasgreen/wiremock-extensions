package com.github.mattiasgreen.wiremock.ui;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Generates visual screenshots of the Web SPA.
 * These images enable autonomous AI agents and human designers to visually inspect
 * layout, hierarchy, contrast, and cognitive ergonomics via multimodal image analysis.
 */
@Tag("ui-snapshot")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class UiSnapshotCaptureTest {

    private WireMockServer wireMockServer;
    private DisabledStubStore disabledStubStore;
    private Browser browser;
    private Path webOutputDir;

    private static final int WEB_WIDTH = 1280;
    private static final int WEB_HEIGHT = 800;

    @BeforeAll
    void startAll() throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        Path baseReports = root.endsWith("wiremock-extension-ui")
                ? root.resolve("build/reports/screenshots")
                : root.resolve("wiremock-extension-ui/build/reports/screenshots");

        webOutputDir = baseReports.resolve("web");
        Files.createDirectories(webOutputDir);

        disabledStubStore = new DisabledStubStore();
        wireMockServer = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .extensions(new UiAdminApiEndpoint(), new StubLifecycleAdminEndpoint(disabledStubStore)));
        wireMockServer.start();

        seedSampleData();

        browser = AbstractUiPlaywrightTest.getSharedBrowser();
    }

    @org.junit.jupiter.api.AfterAll
    void stopAll() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    private void seedSampleData() throws Exception {
        // Active stubs
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/users"))
                .withName("List Users")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\": 1, \"name\": \"Alice\", \"role\": \"ADMIN\"}]")));

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/orders"))
                .withName("Create Order")
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"orderId\": \"ORD-9942\", \"status\": \"CONFIRMED\"}")));

        // Scenario stub
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/orders/ORD-9942/status"))
                .withName("Order Status Progression")
                .inScenario("OrderLifecycle")
                .whenScenarioStateIs("Started")
                .willSetStateTo("PROCESSING")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\": \"PROCESSING\"}")));

        // Trigger traffic for Request Journal
        HttpClient httpClient = HttpClient.newHttpClient();
        String base = wireMockServer.baseUrl();

        httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(base + "/api/v1/users"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(base + "/api/v1/orders"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"item\": \"Widget\", \"quantity\": 3}"))
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        // Unmatched request
        httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(base + "/api/v1/missing-route"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
    }

    // ─── Web SPA Snapshots ──────────────────────────────────────────────────

    @Test
    @DisplayName("Capture Web SPA views")
    void captureWebViews() throws IOException {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(WEB_WIDTH, WEB_HEIGHT)
                .setDeviceScaleFactor(1));
        Page page = context.newPage();
        try {
            // 1. Stubs View
            page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
            page.waitForSelector(".stub-card");
            page.waitForTimeout(500);
            page.screenshot(new Page.ScreenshotOptions().setPath(webOutputDir.resolve("01-stubs-explorer.png")));

            // 2. Request Journal View
            page.locator("#nav-tab-journal").click();
            page.waitForSelector("#tab-journal.active");
            page.waitForSelector("#journal-table");
            page.waitForTimeout(500);
            page.screenshot(new Page.ScreenshotOptions().setPath(webOutputDir.resolve("02-request-journal.png")));

            // 3. HTTP Tester View
            page.locator("#nav-tab-tester").click();
            page.waitForSelector("#tab-tester.active");
            page.waitForTimeout(500);
            page.screenshot(new Page.ScreenshotOptions().setPath(webOutputDir.resolve("03-http-tester.png")));

            // 4. Scenarios View
            page.locator("#nav-tab-scenarios").click();
            page.waitForSelector("#tab-scenarios.active");
            page.waitForTimeout(500);
            page.screenshot(new Page.ScreenshotOptions().setPath(webOutputDir.resolve("04-scenarios-view.png")));

            // 5. Target Server Modal View
            page.locator("#btn-target-host").click();
            page.waitForSelector("#target-host-modal:not(.hidden)");
            page.waitForTimeout(300);
            page.screenshot(new Page.ScreenshotOptions().setPath(webOutputDir.resolve("05-target-host-modal.png")));
            page.locator("#btn-close-target-host").click();
            page.waitForSelector(
                    "#target-host-modal",
                    new Page.WaitForSelectorOptions()
                            .setState(com.microsoft.playwright.options.WaitForSelectorState.HIDDEN));

            // 6. HTTP Tester with Truncated Payload Preview chip
            String innerXml =
                    "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><account>ACC-7788</account><balance>9400.50</balance></soap:Body></soap:Envelope>";
            String middleJson = com.github.tomakehurst.wiremock.common.Json.write(
                    java.util.Map.of("coreBankingXml", innerXml, "service", "cbs-mock"));
            String rootJson = com.github.tomakehurst.wiremock.common.Json.write(
                    java.util.Map.of("transactionId", "TXN-9012", "payload", middleJson));

            wireMockServer.stubFor(get(urlEqualTo("/api/v1/nested-banking"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(rootJson)));

            page.locator("#nav-tab-tester").click();
            page.waitForSelector("#tab-tester.active");
            page.locator("#tester-url").fill("/api/v1/nested-banking");
            page.locator("#btn-tester-send").click();
            page.waitForSelector("#tester-response-status:has-text('200')");
            page.waitForSelector("#tester-response-body .truncated-payload-preview");
            page.waitForTimeout(300);
            page.screenshot(
                    new Page.ScreenshotOptions().setPath(webOutputDir.resolve("06-nested-payload-preview.png")));

            // 7. Stacked Nested Payload Lens Modal View
            page.locator("#tester-response-body .btn-lens-open-pill").first().click();
            page.waitForSelector("#modal-payload-lens:not(.hidden)");
            page.waitForTimeout(300);
            page.screenshot(
                    new Page.ScreenshotOptions().setPath(webOutputDir.resolve("07-nested-payload-lens-modal.png")));
            page.locator("#btn-close-payload-lens").click();
            page.waitForSelector(
                    "#modal-payload-lens",
                    new Page.WaitForSelectorOptions()
                            .setState(com.microsoft.playwright.options.WaitForSelectorState.HIDDEN));

            Assertions.assertTrue(Files.exists(webOutputDir.resolve("01-stubs-explorer.png")));
            Assertions.assertTrue(Files.exists(webOutputDir.resolve("02-request-journal.png")));
            Assertions.assertTrue(Files.exists(webOutputDir.resolve("03-http-tester.png")));
            Assertions.assertTrue(Files.exists(webOutputDir.resolve("04-scenarios-view.png")));
            Assertions.assertTrue(Files.exists(webOutputDir.resolve("05-target-host-modal.png")));
            Assertions.assertTrue(Files.exists(webOutputDir.resolve("06-nested-payload-preview.png")));
            Assertions.assertTrue(Files.exists(webOutputDir.resolve("07-nested-payload-lens-modal.png")));
        } finally {
            context.close();
        }
    }
}
