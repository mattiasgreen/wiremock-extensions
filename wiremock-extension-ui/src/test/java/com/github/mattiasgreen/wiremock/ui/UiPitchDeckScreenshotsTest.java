package com.github.mattiasgreen.wiremock.ui;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import com.github.mattiasgreen.wiremock.stateful.ast.AstModelDefinition;
import com.github.mattiasgreen.wiremock.stateful.dsl.*;
import com.github.mattiasgreen.wiremock.stateful.engine.StateEngine;
import com.github.mattiasgreen.wiremock.stateful.extension.StatefulAdminEndpoint;
import com.github.mattiasgreen.wiremock.stateful.extension.StatefulResponseTransformer;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.microsoft.playwright.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;

@Tag("pitch-deck")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class UiPitchDeckScreenshotsTest {

    private WireMockServer wireMockServer;
    private StateEngine stateEngine;
    private Playwright playwright;
    private Browser browser;
    private Path outputDir;

    private static final int VIEWPORT_WIDTH = 1280;
    private static final int VIEWPORT_HEIGHT = 740;

    @BeforeAll
    void startAll() throws Exception {
        Path cwd = Paths.get("").toAbsolutePath();
        if (cwd.endsWith("wiremock-extension-ui")) {
            outputDir = cwd.resolve("src/main/resources/ui/pitch/images");
        } else {
            outputDir = cwd.resolve("wiremock-extension-ui/src/main/resources/ui/pitch/images");
        }
        Files.createDirectories(outputDir);

        stateEngine = new StateEngine();
        wireMockServer = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .extensions(
                        new UiAdminApiEndpoint(),
                        new StubLifecycleAdminEndpoint(new DisabledStubStore()),
                        new com.github.mattiasgreen.wiremock.openapi.OpenApiAdminEndpoint(),
                        new StatefulAdminEndpoint(stateEngine),
                        new StatefulResponseTransformer(stateEngine)));
        wireMockServer.start();

        setupInitialStubs();

        // Pre-populate Request Journal
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

    private void setupInitialStubs() {
        wireMockServer.stubFor(
                get(urlEqualTo("/api/v1/users"))
                        .withMetadata(Map.of("project", "Core API"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "[{\"id\": 1, \"name\": \"Alice\", \"role\": \"ADMIN\"}, {\"id\": 2, \"name\": \"Bob\", \"role\": \"DEVELOPER\"}]")));

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/orders"))
                .withMetadata(Map.of("project", "Order Service"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"orderId\": \"ORD-9942\", \"status\": \"CONFIRMED\"}")));

        wireMockServer.stubFor(get(urlEqualTo("/api/v1/orders/ORD-9942/status"))
                .inScenario("Order Flow")
                .whenScenarioStateIs("Started")
                .willSetStateTo("Awaiting Fulfillment")
                .withMetadata(Map.of("project", "Order Service"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"orderId\": \"ORD-9942\", \"status\": \"AWAITING_FULFILLMENT\"}")));

        // Register dynamic state model
        stateEngine.registerGlobalModel(createCasesModel());
    }

    private AstModelDefinition createCasesModel() {
        return SimulatorModel.forEntity("cases")
                .idPathParam("caseId")
                .idPrefix("case-")
                .onPost("/api/v1/cases")
                .initialState(Map.of("status", "OPEN", "tasks", List.of()))
                .respondWith(201, ResponseSource.entity())
                .onGet("/api/v1/cases/{caseId}")
                .respondWith(200, ResponseSource.entity())
                .onPost("/api/v1/cases/{caseId}/tasks")
                .mutate(Action.appendToList("tasks", Source.requestBodyWithGeneratedId("id", "task-")))
                .respondWith(201, ResponseSource.entity())
                .onPut("/api/v1/cases/{caseId}/close")
                .require(
                        Expr.list("tasks").allMatch(Expr.field("status").notEq("PENDING")),
                        409,
                        "Cannot close case: pending tasks exist")
                .mutate(Action.setField("status", "CLOSED"))
                .respondWith(200, ResponseSource.entity())
                .build();
    }

    @Test
    @DisplayName("Capture pitch deck screenshot 1: Stubs Explorer")
    void captureStubsExplorer() throws IOException {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                .setDeviceScaleFactor(1));
        Page page = context.newPage();
        try {
            page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
            applyZoom(page);
            page.waitForSelector(".stub-card");
            page.locator(".stub-card:has-text('/api/v1/users')").click();
            page.waitForSelector("#stub-detail-view:not(.hidden)");
            page.waitForTimeout(600);

            Path target = outputDir.resolve("screenshot-stubs-explorer.png");
            page.screenshot(new Page.ScreenshotOptions().setPath(target));
            Assertions.assertTrue(Files.exists(target));
        } finally {
            context.close();
        }
    }

    @Test
    @DisplayName("Capture pitch deck screenshot 2: OpenAPI Importer Modal")
    void captureOpenApiModal() throws IOException {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                .setDeviceScaleFactor(1));
        Page page = context.newPage();
        try {
            page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
            applyZoom(page);
            page.waitForSelector(".stub-card");
            page.locator("#btn-open-openapi-modal").click();
            page.waitForSelector("#openapi-modal:not(.hidden)");
            page.waitForTimeout(400);

            Path target = outputDir.resolve("screenshot-openapi-modal.png");
            page.screenshot(new Page.ScreenshotOptions().setPath(target));
            Assertions.assertTrue(Files.exists(target));
        } finally {
            context.close();
        }
    }

    @Test
    @DisplayName("Capture pitch deck screenshot 3: HTTP Tester")
    void captureHttpTester() throws IOException {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                .setDeviceScaleFactor(1));
        Page page = context.newPage();
        try {
            page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#tester");
            applyZoom(page);
            page.waitForSelector("#btn-tester-send");
            page.locator("#tester-url").fill("/api/v1/users");
            page.locator("#btn-tester-send").click();
            page.waitForSelector("#tester-response-status:has-text('200')");
            page.waitForTimeout(500);

            Path target = outputDir.resolve("screenshot-http-tester.png");
            page.screenshot(new Page.ScreenshotOptions().setPath(target));
            Assertions.assertTrue(Files.exists(target));
        } finally {
            context.close();
        }
    }

    @Test
    @DisplayName("Capture pitch deck screenshot 4: Scenarios DAG Visualizer")
    void captureScenariosDag() throws IOException {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                .setDeviceScaleFactor(1));
        Page page = context.newPage();
        try {
            page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#scenarios");
            applyZoom(page);
            page.waitForSelector("#scenarios-container");
            page.waitForTimeout(600);

            Path target = outputDir.resolve("screenshot-scenarios-dag.png");
            page.screenshot(new Page.ScreenshotOptions().setPath(target));
            Assertions.assertTrue(Files.exists(target));
        } finally {
            context.close();
        }
    }

    @Test
    @DisplayName("Capture pitch deck screenshot 5: Request Journal")
    void captureRequestJournal() throws IOException {
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                .setDeviceScaleFactor(1));
        Page page = context.newPage();
        try {
            page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#journal");
            applyZoom(page);
            page.waitForSelector("#journal-table");
            Locator firstRow =
                    page.locator("#journal-list tr[data-testid='journal-row']").first();
            firstRow.click();
            page.waitForSelector("tr[data-testid='journal-detail-row']:not(.hidden)");
            page.waitForTimeout(500);

            Path target = outputDir.resolve("screenshot-request-journal.png");
            page.screenshot(new Page.ScreenshotOptions().setPath(target));
            Assertions.assertTrue(Files.exists(target));
        } finally {
            context.close();
        }
    }
}
