package com.github.mattiasgreen.wiremock.ui;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.madgag.gif.fmsware.AnimatedGifEncoder;
import com.microsoft.playwright.*;
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
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;

@Tag("recording")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class UiDemoGifRecordingTest {

    private WireMockServer wireMockServer;
    private Playwright playwright;
    private Browser browser;
    private Path outputDir;

    private static final int VIEWPORT_WIDTH = 1280;
    private static final int VIEWPORT_HEIGHT = 740;

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

        wireMockServer = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .extensions(
                        new UiAdminApiEndpoint(),
                        new StubLifecycleAdminEndpoint(new DisabledStubStore()),
                        new com.github.mattiasgreen.wiremock.openapi.OpenApiAdminEndpoint()));
        wireMockServer.start();

        setupInitialStubs();

        // 2. Pre-populate Request Journal by sending HTTP requests
        HttpClient httpClient = HttpClient.newHttpClient();
        String baseUrl = wireMockServer.baseUrl();

        httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/v1/users"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/v1/orders"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"item\": \"Enterprise Subscription\", \"quantity\": 2}"))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/v1/orders/ORD-9942/status"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/v1/unregistered-path"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        // 3. Start Playwright
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    private void applyZoom(Page page) {
        page.evaluate("() => { if (document.body) document.body.style.zoom = '0.90'; }");
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
            applyZoom(page);
            page.waitForSelector(".stub-card");
            recordFrame(page, encoder, 1200);

            // Step 2: Select users stub
            page.locator(".stub-card:has-text('/api/v1/users')").click();
            page.waitForSelector("#stub-detail-view:not(.hidden)");
            recordFrame(page, encoder, 1500);

            // Step 3: Select orders POST stub
            page.locator(".stub-card:has-text('Create Order API')").click();
            recordFrame(page, encoder, 1500);

            // Step 4: Click 'Test Stub' shortcut
            page.locator("#btn-test-stub").click();
            page.waitForSelector("#tab-tester.active");
            recordFrame(page, encoder, 1200);

            // Step 5: Send Request in HTTP Tester
            page.locator("#btn-tester-send").click();
            page.waitForSelector("#tester-response-status:not(:has-text('...')):not(:has-text('—'))");
            System.out.println("TEST 1 RESPONSE STATUS: "
                    + page.locator("#tester-response-status").textContent());
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
        System.out.println("Generated GIF 1: " + gifPath.toAbsolutePath() + " ("
                + (gifPath.toFile().length() / 1024) + " KB)");
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
            applyZoom(page);
            page.waitForSelector("#journal-list tr.journal-row");
            recordFrame(page, encoder, 1300);

            // Step 2: Expand first row (orders POST)
            page.locator("#journal-list tr.journal-row:has-text('orders')")
                    .first()
                    .click();
            page.waitForSelector("tr.journal-detail-row");
            recordFrame(page, encoder, 2000);

            // Step 3: Expand unmatched request (404)
            page.locator("#journal-list tr.journal-row:has-text('unregistered-path')")
                    .click();
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
        System.out.println("Generated GIF 2: " + gifPath.toAbsolutePath() + " ("
                + (gifPath.toFile().length() / 1024) + " KB)");
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
            applyZoom(page);
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

            // 6. Switch to Scenarios (DAG visualizer)
            page.locator("#nav-tab-scenarios").click();
            page.waitForSelector("#tab-scenarios.active .scenario-fsm-card");
            recordFrame(page, encoder, 2400);

        } finally {
            encoder.finish();
            context.close();
        }

        Assertions.assertTrue(Files.exists(gifPath) && gifPath.toFile().length() > 0, "GIF must be created");
        System.out.println("Generated GIF 3: " + gifPath.toAbsolutePath() + " ("
                + (gifPath.toFile().length() / 1024) + " KB)");
    }

    @Test
    @DisplayName("Generate demo GIF 4: Scenarios DAG & State Machine Visualizer")
    void recordScenariosDagWorkflow() {
        Path gifPath = outputDir.resolve("demo-scenarios-dag.gif");
        AnimatedGifEncoder encoder = new AnimatedGifEncoder();
        encoder.start(gifPath.toString());
        encoder.setRepeat(0); // Loop forever
        encoder.setQuality(10);

        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                .setDeviceScaleFactor(1));
        Page page = context.newPage();

        try {
            // Step 1: Open Scenarios tab directly
            page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#scenarios");
            applyZoom(page);
            page.waitForSelector(".scenario-fsm-card");
            recordFrame(page, encoder, 1500);

            // Step 2: Show the pipeline flow
            page.waitForSelector(".fsm-pipeline-step");
            recordFrame(page, encoder, 1500);

            // Step 3: Change state via dropdown override
            page.locator(".scenario-target-state-select").selectOption("SHIPPED");
            recordFrame(page, encoder, 1200);

            page.locator(".btn-set-scenario-state").click();
            page.waitForSelector(".fsm-node-card.active:has-text('SHIPPED')");
            recordFrame(page, encoder, 2000);

            // Step 4: Click Reset scenario button to return to Started
            page.locator(".btn-reset-single-scenario").click();
            page.waitForSelector(".fsm-node-card.active:has-text('Started')");
            recordFrame(page, encoder, 2200);

        } finally {
            encoder.finish();
            context.close();
        }

        Assertions.assertTrue(Files.exists(gifPath) && gifPath.toFile().length() > 0, "GIF must be created");
        System.out.println("Generated GIF 4: " + gifPath.toAbsolutePath() + " ("
                + (gifPath.toFile().length() / 1024) + " KB)");
    }

    @Test
    @DisplayName("Generate demo GIF 5: OpenAPI 3.0/3.1 Spec Import & Stub Synthesis")
    void recordOpenApiImportWorkflow() {
        Path gifPath = outputDir.resolve("demo-openapi-import.gif");
        AnimatedGifEncoder encoder = new AnimatedGifEncoder();
        encoder.start(gifPath.toString());
        encoder.setRepeat(0); // Loop forever
        encoder.setQuality(10);

        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                .setDeviceScaleFactor(1));
        Page page = context.newPage();

        try {
            wireMockServer.resetRequests();

            // Step 1: Open Stubs tab and display current initial state
            page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
            applyZoom(page);
            page.waitForSelector(".stub-card");
            recordFrame(page, encoder, 1200);

            // Step 2: Clear all existing stubs via select-all and bulk delete
            page.locator("#chk-select-all").click();
            page.waitForSelector("#bulk-actions-bar:not(.hidden)");
            recordFrame(page, encoder, 1100);

            page.onceDialog(Dialog::accept);
            page.locator("#btn-bulk-delete").click();
            page.waitForSelector(".stub-list .empty-state");
            recordFrame(page, encoder, 1400);

            // Step 3: Open OpenAPI Import modal
            page.locator("#btn-open-openapi-modal").click();
            page.waitForSelector("#openapi-modal:not(.hidden)");
            recordFrame(page, encoder, 1100);

            // Step 4: Populate sample OpenAPI YAML specification with both GET & POST endpoints
            String openApiYaml =
                    """
                    openapi: 3.0.3
                    info:
                      title: Store Catalog API
                      version: 1.0.0
                    paths:
                      /api/v1/catalog/products:
                        get:
                          summary: List catalog products
                          responses:
                            '200':
                              description: Product catalog
                              content:
                                application/json:
                                  schema:
                                    type: array
                                    items:
                                      type: object
                                      properties:
                                        sku:
                                          type: string
                                          example: PROD-778
                                        name:
                                          type: string
                                          example: Wireless Headphones
                                        price:
                                          type: number
                                          example: 199.99
                      /api/v1/catalog/orders:
                        post:
                          summary: Place an order
                          requestBody:
                            required: true
                            content:
                              application/json:
                                schema:
                                  type: object
                                  required:
                                    - sku
                                    - quantity
                                  properties:
                                    sku:
                                      type: string
                                      example: PROD-778
                                    quantity:
                                      type: integer
                                      example: 2
                                    customer:
                                      type: string
                                      example: alex@example.com
                          responses:
                            '201':
                              description: Order confirmed
                              content:
                                application/json:
                                  schema:
                                    type: object
                                    properties:
                                      orderId:
                                        type: string
                                        example: ORD-9912
                                      status:
                                        type: string
                                        example: CONFIRMED
                    """;

            page.locator("#openapi-spec-content").fill(openApiYaml);
            recordFrame(page, encoder, 1800);

            // Step 5: Generate Stubs from OpenAPI spec
            page.locator("#btn-submit-openapi-import").click();
            page.waitForSelector(".modal-feedback.success");
            recordFrame(page, encoder, 1400);

            // Step 6: Modal closes automatically, synthesized GET & POST stubs appear in sidebar
            page.waitForSelector(
                    "#openapi-modal",
                    new Page.WaitForSelectorOptions()
                            .setState(com.microsoft.playwright.options.WaitForSelectorState.HIDDEN));
            page.waitForSelector(".stub-card:has-text('/api/v1/catalog/orders')");
            page.waitForSelector(".stub-card:has-text('/api/v1/catalog/products')");
            recordFrame(page, encoder, 1500);

            // Step 7: Select POST stub to inspect mapping & jump to HTTP Tester
            page.locator(".stub-card:has-text('/api/v1/catalog/orders')")
                    .first()
                    .click();
            page.waitForSelector("#stub-detail-view:not(.hidden)");
            recordFrame(page, encoder, 1400);

            page.locator("#btn-test-stub").click();
            page.waitForSelector("#tab-tester.active");
            recordFrame(page, encoder, 1600);

            // Step 8: Send POST request with synthesized example request body
            page.locator("#btn-tester-send").click();
            page.waitForSelector("#tester-response-status:has-text('201')");
            recordFrame(page, encoder, 2000);

            // Step 9: In HTTP Tester, select GET stub from persistent sidebar to pre-fill GET endpoint
            page.locator(".stub-card:has-text('/api/v1/catalog/products')")
                    .first()
                    .click();
            recordFrame(page, encoder, 1500);

            // Step 10: Send GET request to verify 200 response with synthesized catalog items
            page.locator("#btn-tester-send").click();
            page.waitForSelector("#tester-response-status:has-text('200')");
            recordFrame(page, encoder, 2000);

            // Step 11: Modify GET URL to intentionally miss the stub (trigger 404 unmatched)
            page.locator("#tester-url").fill("/api/v1/catalog/products/missing-sku");
            recordFrame(page, encoder, 1300);

            page.locator("#btn-tester-send").click();
            page.waitForSelector("#tester-response-status:has-text('404')");
            recordFrame(page, encoder, 2000);

            // Step 12: Navigate to Request Journal to review traffic history
            page.locator("#nav-tab-journal").click();
            page.waitForSelector("#tab-journal.active");
            page.waitForSelector("#journal-list tr.journal-row:has-text('missing-sku')");
            recordFrame(page, encoder, 2000);

            // Step 13: Expand unmatched request row to inspect matching diagnostics
            page.locator("#journal-list tr.journal-row:has-text('missing-sku')")
                    .first()
                    .click();
            page.waitForSelector("tr.journal-detail-row");
            recordFrame(page, encoder, 2400);

            // Step 14: Filter to unmatched requests only
            page.locator("#filter-unmatched-only").check();
            recordFrame(page, encoder, 2200);

        } finally {
            setupInitialStubs();
            encoder.finish();
            context.close();
        }

        Assertions.assertTrue(Files.exists(gifPath) && gifPath.toFile().length() > 0, "GIF must be created");
        System.out.println("Generated GIF 5: " + gifPath.toAbsolutePath() + " ("
                + (gifPath.toFile().length() / 1024) + " KB)");
    }

    private void setupInitialStubs() {
        wireMockServer.resetAll();
        wireMockServer.stubFor(
                get(urlEqualTo("/api/v1/users"))
                        .withName("List Users API")
                        .willReturn(
                                okJson(
                                        "[\n  {\"id\": 101, \"name\": \"Alice Johnson\", \"role\": \"ADMIN\", \"active\": true},\n  {\"id\": 102, \"name\": \"Bob Smith\", \"role\": \"DEVELOPER\", \"active\": true},\n  {\"id\": 103, \"name\": \"Charlie Brown\", \"role\": \"OPERATOR\", \"active\": false}\n]")));

        wireMockServer.stubFor(
                post(urlEqualTo("/api/v1/orders"))
                        .withName("Create Order API")
                        .withRequestBody(
                                equalToJson("{\n  \"item\": \"Enterprise Subscription\",\n  \"quantity\": 2\n}"))
                        .willReturn(
                                created()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\n  \"orderId\": \"ORD-9942\",\n  \"status\": \"CREATED\",\n  \"estimatedDelivery\": \"2026-10-06\"\n}")));

        wireMockServer.stubFor(get(urlEqualTo("/api/v1/inventory/items"))
                .withName("Search Inventory")
                .willReturn(okJson("{\"category\": \"hardware\", \"count\": 142, \"inStock\": true}")));

        // Order Fulfillment Scenario Stubs
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/orders/ORD-9942/status"))
                .withName("Order Status (Initial)")
                .inScenario("Order-Fulfillment")
                .whenScenarioStateIs("Started")
                .willReturn(okJson("{\"orderId\": \"ORD-9942\", \"status\": \"PENDING\"}")));

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/orders/ORD-9942/process"))
                .withName("Start Processing Order")
                .inScenario("Order-Fulfillment")
                .whenScenarioStateIs("Started")
                .willSetStateTo("PROCESSING")
                .willReturn(okJson("{\"orderId\": \"ORD-9942\", \"status\": \"PROCESSING\"}")));

        wireMockServer.stubFor(get(urlEqualTo("/api/v1/orders/ORD-9942/status"))
                .withName("Order Status (Processing)")
                .inScenario("Order-Fulfillment")
                .whenScenarioStateIs("PROCESSING")
                .willReturn(okJson("{\"orderId\": \"ORD-9942\", \"status\": \"PROCESSING\"}")));

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/orders/ORD-9942/ship"))
                .withName("Ship Order")
                .inScenario("Order-Fulfillment")
                .whenScenarioStateIs("PROCESSING")
                .willSetStateTo("SHIPPED")
                .willReturn(okJson("{\"orderId\": \"ORD-9942\", \"status\": \"SHIPPED\"}")));

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/orders/ORD-9942/deliver"))
                .withName("Deliver Order")
                .inScenario("Order-Fulfillment")
                .whenScenarioStateIs("SHIPPED")
                .willSetStateTo("DELIVERED")
                .willReturn(okJson("{\"orderId\": \"ORD-9942\", \"status\": \"DELIVERED\"}")));

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/orders/ORD-9942/ship"))
                .withName("Reject Shipping Delivered Order")
                .inScenario("Order-Fulfillment")
                .whenScenarioStateIs("DELIVERED")
                .willReturn(status(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"Order already delivered\"}")));
    }
}
