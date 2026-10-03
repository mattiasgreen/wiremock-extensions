package com.github.mattiasgreen.wiremock.ui;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.madgag.gif.fmsware.AnimatedGifEncoder;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class UiDemoGifRecordingTest {

    private WireMockServer wireMockServer;
    private Playwright playwright;
    private Browser browser;
    private Path outputDir;

    private static final int VIEWPORT_WIDTH = 1120;
    private static final int VIEWPORT_HEIGHT = 680;

    @BeforeAll
    void startAll() throws Exception {
        // Resolve docs/images directory relative to project root
        Path cwd = Paths.get("").toAbsolutePath();
        if (cwd.endsWith("wiremock-extension-ui")) {
            outputDir = cwd.getParent().resolve("docs").resolve("images");
        } else {
            outputDir = cwd.resolve("docs").resolve("images");
        }
        Files.createDirectories(outputDir);

        wireMockServer = new WireMockServer(
                WireMockConfiguration.options()
                        .dynamicPort()
                        .extensions(new UiAdminApiEndpoint())
        );
        wireMockServer.start();

        // 1. Mock Stubs
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/users"))
                .withName("List Users API")
                .willReturn(okJson("[\n  {\"id\": 101, \"name\": \"Alice Johnson\", \"role\": \"ADMIN\", \"active\": true},\n  {\"id\": 102, \"name\": \"Bob Smith\", \"role\": \"DEVELOPER\", \"active\": true},\n  {\"id\": 103, \"name\": \"Charlie Brown\", \"role\": \"OPERATOR\", \"active\": false}\n]")));

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/orders"))
                .withName("Create Order API")
                .withRequestBody(equalToJson("{\n  \"item\": \"Enterprise Subscription\",\n  \"quantity\": 2\n}"))
                .willReturn(created()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\n  \"orderId\": \"ORD-9942\",\n  \"status\": \"CREATED\",\n  \"estimatedDelivery\": \"2026-10-06\"\n}")));

        wireMockServer.stubFor(get(urlEqualTo("/api/v1/inventory/items"))
                .withName("Search Inventory")
                .willReturn(okJson("{\"category\": \"hardware\", \"count\": 142, \"inStock\": true}")));

        wireMockServer.stubFor(get(urlEqualTo("/api/v1/orders/ORD-9942/status"))
                .withName("Order Fulfillment Flow")
                .inScenario("Order-Fulfillment")
                .whenScenarioStateIs("Started")
                .willSetStateTo("PROCESSING")
                .willReturn(okJson("{\"orderId\": \"ORD-9942\", \"status\": \"PROCESSING\"}")));

        // 2. Pre-populate Request Journal by sending HTTP requests
        HttpClient httpClient = HttpClient.newHttpClient();
        String baseUrl = wireMockServer.baseUrl();

        httpClient.send(HttpRequest.newBuilder().uri(URI.create(baseUrl + "/api/v1/users")).GET().build(), HttpResponse.BodyHandlers.discarding());
        httpClient.send(HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/orders"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"item\": \"Enterprise Subscription\", \"quantity\": 2}"))
                .build(), HttpResponse.BodyHandlers.discarding());
        httpClient.send(HttpRequest.newBuilder().uri(URI.create(baseUrl + "/api/v1/orders/ORD-9942/status")).GET().build(), HttpResponse.BodyHandlers.discarding());
        httpClient.send(HttpRequest.newBuilder().uri(URI.create(baseUrl + "/api/v1/unregistered-path")).GET().build(), HttpResponse.BodyHandlers.discarding());

        // 3. Start Playwright
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    void stopAll() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (wireMockServer != null && wireMockServer.isRunning()) wireMockServer.stop();
    }

    private void recordFrame(Page page, AnimatedGifEncoder encoder, int delayMs) {
        try {
            byte[] bytes = page.screenshot();
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            encoder.setDelay(delayMs);
            encoder.addFrame(image);
        } catch (IOException e) {
            throw new RuntimeException("Failed to capture and encode frame", e);
        }
    }

    @Test
    @DisplayName("Generate demo GIF 1: Stubs & HTTP Tester workflow")
    void recordStubsAndTesterWorkflow() {
        Path gifPath = outputDir.resolve("demo-stubs-and-tester.gif");
        AnimatedGifEncoder encoder = new AnimatedGifEncoder();
        encoder.start(gifPath.toString());
        encoder.setRepeat(0); // Loop forever
        encoder.setQuality(10);

        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                .setDeviceScaleFactor(1));
        Page page = context.newPage();

        try {
            // Step 1: Open Stubs tab
            page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
            page.waitForSelector(".stub-card");
            recordFrame(page, encoder, 1200);

            // Step 2: Select users stub
            page.locator(".stub-card:has-text('/api/v1/users')").click();
            page.waitForSelector("#stub-detail-view:not(.hidden)");
            recordFrame(page, encoder, 1500);

            // Step 3: Select orders POST stub
            page.locator(".stub-card:has-text('POST')").click();
            recordFrame(page, encoder, 1500);

            // Step 4: Click 'Test Stub' shortcut
            page.locator("#btn-test-stub").click();
            page.waitForSelector("#tab-tester.active");
            recordFrame(page, encoder, 1200);

            // Step 5: Send Request in HTTP Tester
            page.locator("#btn-tester-send").click();
            page.waitForSelector("#tester-response-status:not(:has-text('...')):not(:has-text('—'))");
            System.out.println("TEST 1 RESPONSE STATUS: " + page.locator("#tester-response-status").textContent());
            recordFrame(page, encoder, 1800);

            // Step 6: View Sent Request
            page.locator("#btn-tester-tab-request").click();
            recordFrame(page, encoder, 1300);

            // Step 7: View Both (Side-by-Side split)
            page.locator("#btn-tester-tab-both").click();
            recordFrame(page, encoder, 2400);

        } finally {
            encoder.finish();
            context.close();
        }

        Assertions.assertTrue(Files.exists(gifPath) && gifPath.toFile().length() > 0, "GIF must be created");
        System.out.println("Generated GIF 1: " + gifPath.toAbsolutePath() + " (" + (gifPath.toFile().length() / 1024) + " KB)");
    }

    @Test
    @DisplayName("Generate demo GIF 2: Request Journal & Traffic Inspection")
    void recordRequestJournalWorkflow() {
        Path gifPath = outputDir.resolve("demo-request-journal.gif");
        AnimatedGifEncoder encoder = new AnimatedGifEncoder();
        encoder.start(gifPath.toString());
        encoder.setRepeat(0); // Loop forever
        encoder.setQuality(10);

        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                .setDeviceScaleFactor(1));
        Page page = context.newPage();

        try {
            // Step 1: Open Request Journal tab
            page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#journal");
            page.waitForSelector("#journal-list tr.journal-row");
            recordFrame(page, encoder, 1300);

            // Step 2: Expand first row (orders POST)
            page.locator("#journal-list tr.journal-row:has-text('orders')").first().click();
            page.waitForSelector("tr.journal-detail-row");
            recordFrame(page, encoder, 2000);

            // Step 3: Expand unmatched request (404)
            page.locator("#journal-list tr.journal-row:has-text('unregistered-path')").click();
            recordFrame(page, encoder, 1800);

            // Step 4: Search filter
            page.locator("#journal-search").fill("users");
            recordFrame(page, encoder, 1500);

            // Step 5: Clear search
            page.locator("#journal-search").fill("");
            recordFrame(page, encoder, 800);

            // Step 6: Filter unmatched only
            page.locator("#filter-unmatched-only").check();
            recordFrame(page, encoder, 1500);

            // Step 7: Uncheck filter to return to full log
            page.locator("#filter-unmatched-only").uncheck();
            recordFrame(page, encoder, 2200);

        } finally {
            encoder.finish();
            context.close();
        }

        Assertions.assertTrue(Files.exists(gifPath) && gifPath.toFile().length() > 0, "GIF must be created");
        System.out.println("Generated GIF 2: " + gifPath.toAbsolutePath() + " (" + (gifPath.toFile().length() / 1024) + " KB)");
    }

    @Test
    @DisplayName("Generate demo GIF 3: Complete UI Tour")
    void recordCompleteUiTour() {
        Path gifPath = outputDir.resolve("demo-ui-tour.gif");
        AnimatedGifEncoder encoder = new AnimatedGifEncoder();
        encoder.start(gifPath.toString());
        encoder.setRepeat(0); // Loop forever
        encoder.setQuality(10);

        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                .setDeviceScaleFactor(1));
        Page page = context.newPage();

        try {
            // 1. Stubs view
            page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
            page.waitForSelector(".stub-card");
            page.locator(".stub-card:has-text('/api/v1/users')").click();
            recordFrame(page, encoder, 1500);

            // 2. Jump to HTTP Tester via Test Stub
            page.locator("#btn-test-stub").click();
            page.waitForSelector("#tab-tester.active");
            recordFrame(page, encoder, 1100);

            // 3. Send HTTP request
            page.locator("#btn-tester-send").click();
            page.waitForSelector("#tester-response-status:has-text('200')");
            recordFrame(page, encoder, 1600);

            // 4. Switch to Request Journal
            page.locator("#nav-tab-journal").click();
            page.waitForSelector("#tab-journal.active");
            recordFrame(page, encoder, 1200);

            // 5. Expand journal row
            page.locator("#journal-list tr.journal-row").first().click();
            recordFrame(page, encoder, 1800);

            // 6. Switch to Scenarios
            page.locator("#nav-tab-scenarios").click();
            page.waitForSelector("#tab-scenarios.active");
            recordFrame(page, encoder, 2200);

        } finally {
            encoder.finish();
            context.close();
        }

        Assertions.assertTrue(Files.exists(gifPath) && gifPath.toFile().length() > 0, "GIF must be created");
        System.out.println("Generated GIF 3: " + gifPath.toAbsolutePath() + " (" + (gifPath.toFile().length() / 1024) + " KB)");
    }
}
