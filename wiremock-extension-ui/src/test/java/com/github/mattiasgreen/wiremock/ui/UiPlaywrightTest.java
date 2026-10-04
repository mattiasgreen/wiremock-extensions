package com.github.mattiasgreen.wiremock.ui;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class UiPlaywrightTest {

    private WireMockServer wireMockServer;
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private final List<String> consoleLogs = Collections.synchronizedList(new ArrayList<>());
    private final List<String> consoleErrors = Collections.synchronizedList(new ArrayList<>());
    private final List<String> pageErrors = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    void startAll() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .extensions(
                        new UiAdminApiEndpoint(),
                        new StubLifecycleAdminEndpoint(new DisabledStubStore()),
                        new com.github.mattiasgreen.wiremock.openapi.OpenApiAdminEndpoint()));
        wireMockServer.start();

        wireMockServer.stubFor(get(urlEqualTo("/api/v1/users"))
                .withName("List Users API")
                .willReturn(okJson("{\"users\": [{\"id\": 1, \"name\": \"Alice\"}, {\"id\": 2, \"name\": \"Bob\"}]}")));

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/orders"))
                .withName("Create Order API")
                .withRequestBody(matchingJsonPath("$.item"))
                .willReturn(created()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"orderId\": \"ORD-99\", \"status\": \"CREATED\"}")));

        wireMockServer.stubFor(get(urlEqualTo("/favicon.ico")).willReturn(ok().withBody(new byte[0])));

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/cases"))
                .withName("Create Case")
                .inScenario("Case-FSM")
                .whenScenarioStateIs("Started")
                .willSetStateTo("OPEN")
                .willReturn(
                        created().withHeader("Content-Type", "application/json").withBody("{\"status\": \"OPEN\"}")));

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/cases/1/close"))
                .withName("Close Case")
                .inScenario("Case-FSM")
                .whenScenarioStateIs("OPEN")
                .willSetStateTo("CLOSED")
                .willReturn(okJson("{\"status\": \"CLOSED\"}")));

        playwright = Playwright.create();
        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions().setHeadless(true);
        browser = playwright.chromium().launch(launchOptions);
    }

    @AfterAll
    void stopAll() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void setUp() {
        consoleLogs.clear();
        consoleErrors.clear();
        pageErrors.clear();
        context = browser.newContext();
        page = context.newPage();

        page.onConsoleMessage(msg -> {
            String text = "[" + msg.type() + "] " + msg.text();
            consoleLogs.add(text);
            if ("error".equalsIgnoreCase(msg.type())) {
                System.err.println("BROWSER CONSOLE ERROR: " + msg.text());
                consoleErrors.add(msg.text());
            }
        });

        page.onPageError(error -> {
            System.err.println("BROWSER UNCAUGHT ERROR: " + error);
            pageErrors.add(error);
        });

        page.onResponse(response -> {
            if (response.status() >= 400
                    && !response.url().contains("favicon.ico")
                    && !response.url().contains("nonexistent")) {
                System.err.println("HTTP ERROR RESPONSE: " + response.status() + " " + response.url());
            }
        });
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    @DisplayName("Scenario 1: Page load & initial stubs listing")
    void testInitialPageLoadAndStubsListing() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/");

        assertThat(page.title()).isEqualTo("WireMock Console");

        // Wait for stubs to load asynchronously in sidebar
        page.waitForSelector(".stub-card");
        Locator stubCards = page.locator(".stub-card");
        assertThat(stubCards.count()).isGreaterThanOrEqualTo(2);

        // Check stat counters
        Locator statStubs = page.locator("#stat-stubs");
        assertThat(Integer.parseInt(statStubs.textContent().trim())).isGreaterThanOrEqualTo(2);

        // Default tab is Stub Details
        Locator tabStubDetail = page.locator("#tab-stub-detail");
        assertThat(tabStubDetail.getAttribute("class")).contains("active");

        // Verify relative neighbor link to Swagger UI
        Locator swaggerLink = page.locator("a[href='../swagger-ui/']");
        assertThat(swaggerLink.isVisible()).isTrue();

        // Verify data-testid and data-state contracts for test resilience
        assertThat(page.getByTestId("navbar").isVisible()).isTrue();
        assertThat(page.getByTestId("stub-list").getAttribute("data-state")).isEqualTo("ready");
        assertThat(page.getByTestId("stub-card").count()).isGreaterThanOrEqualTo(2);

        assertThat(pageErrors).as("Uncaught page errors on initial load").isEmpty();
    }

    @Test
    @DisplayName("Scenario 2: Stub selection and inspector details")
    void testStubSelectionAndDetails() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/");
        page.waitForSelector(".stub-card");

        // Click the /api/v1/users stub card
        page.locator(".stub-card:has-text('/api/v1/users')").click();

        // Details should become visible
        Locator detailView = page.locator("#stub-detail-view");
        assertThat(detailView.getAttribute("class")).doesNotContain("hidden");

        Locator detailUrl = page.locator("#detail-url");
        assertThat(detailUrl.textContent()).contains("/api/v1/");

        Locator jsonViewer = page.locator("#stub-json-viewer");
        assertThat(jsonViewer.textContent()).contains("request");
        assertThat(jsonViewer.locator(".json-key").count())
                .as("Highlighted JSON keys")
                .isGreaterThan(0);
        assertThat(jsonViewer.locator(".json-string").count())
                .as("Highlighted JSON strings")
                .isGreaterThan(0);
        assertThat(jsonViewer.locator(".json-punct").count())
                .as("Highlighted JSON punctuation")
                .isGreaterThan(0);

        // Verify URL hash updated with stubId
        String url = page.url();
        assertThat(url).contains("#stubs?stubId=");

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Scenario 3: HTTP Request Tester execution with dual view, permanent headers, and history")
    void testHttpRequestTesterExecution() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#tester");

        page.waitForSelector("#tab-tester.active");

        // Verify headers are permanently visible by default (not collapsed)
        Locator responseHeaders = page.locator("#tester-response-headers");
        assertThat(responseHeaders.isVisible()).isTrue();

        // Set URL to /api/v1/users
        page.locator("#tester-url").fill("/api/v1/users");

        // Click Send button
        page.locator("#btn-tester-send").click();

        // Wait for response status to show 200
        page.waitForSelector("#tester-response-status:has-text('200')");
        Locator statusEl = page.locator("#tester-response-status");
        assertThat(statusEl.textContent()).contains("200");

        Locator bodyEl = page.locator("#tester-response-body");
        assertThat(bodyEl.textContent()).contains("Alice");
        assertThat(bodyEl.locator(".json-key").count())
                .as("Response body JSON keys")
                .isGreaterThan(0);
        assertThat(bodyEl.locator(".json-string").count())
                .as("Response body JSON strings")
                .isGreaterThan(0);
        assertThat(bodyEl.locator(".json-number").count())
                .as("Response body JSON numbers")
                .isGreaterThan(0);
        assertThat(bodyEl.locator(".json-punct").count())
                .as("Response body JSON punctuation")
                .isGreaterThan(0);

        // Verify response size indicator
        Locator sizeEl = page.locator("#tester-response-size");
        assertThat(sizeEl.textContent()).contains("B");

        // Verify response headers are populated and visible
        assertThat(responseHeaders.textContent().toLowerCase()).contains("content-type");

        // Test Sent Request inspector view
        page.locator("#btn-tester-tab-request").click();
        assertThat(page.locator("#tester-sent-url").textContent()).isEqualTo("/api/v1/users");
        assertThat(page.locator("#tester-sent-method").textContent()).isEqualTo("GET");

        // Test Both (Split) inspector view
        page.locator("#btn-tester-tab-both").click();
        assertThat(page.locator("#tester-view-response").isVisible()).isTrue();
        assertThat(page.locator("#tester-view-request").isVisible()).isTrue();

        // Verify Request History recorded the execution with timestamp
        Locator historyItems = page.locator(".history-item");
        assertThat(historyItems.count()).isGreaterThanOrEqualTo(1);
        Locator firstHistoryItem = historyItems.first();
        assertThat(firstHistoryItem.textContent()).contains("GET");
        assertThat(firstHistoryItem.textContent()).contains("200");
        assertThat(firstHistoryItem.textContent()).contains("/api/v1/users");

        // Switch back to response tab
        page.locator("#btn-tester-tab-response").click();

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Scenario 8: Tester header presets, auto Content-Type, and history restore/clear")
    void testTesterPresetsAndHistoryRestore() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#tester");
        page.waitForSelector("#tab-tester.active");

        // Test Header Presets
        page.locator("#btn-preset-json").click();
        assertThat(page.locator("#tester-headers").inputValue()).contains("Content-Type: application/json");

        page.locator("#btn-preset-bearer").click();
        assertThat(page.locator("#tester-headers").inputValue()).contains("Authorization: Bearer <token>");

        // Test JSON formatting and auto Content-Type
        page.locator("#tester-body").fill("{\"name\":\"TestItem\",\"count\":5}");
        page.locator("#btn-tester-format-json").click();
        assertThat(page.locator("#tester-body").inputValue()).contains("  \"name\": \"TestItem\"");

        // Send a POST request to generate a history entry
        page.locator("#tester-method").selectOption("POST");
        page.locator("#tester-url").fill("/api/v1/orders");
        page.locator("#tester-body").fill("{\"item\": \"Widget\"}");
        page.locator("#btn-tester-send").click();

        page.waitForSelector("#tester-response-status:has-text('201')");

        // Reset form
        page.locator("#btn-tester-reset-form").click();
        assertThat(page.locator("#tester-method").inputValue()).isEqualTo("GET");
        assertThat(page.locator("#tester-url").inputValue()).isEqualTo("/api/v1/users");
        assertThat(page.locator("#tester-headers").inputValue()).isEmpty();

        // Restore from history by clicking the recorded POST history item
        page.locator(".history-item:has-text('POST')").first().click();
        assertThat(page.locator("#tester-method").inputValue()).isEqualTo("POST");
        assertThat(page.locator("#tester-url").inputValue()).isEqualTo("/api/v1/orders");
        assertThat(page.locator("#tester-response-status").textContent()).contains("201");

        // Test clearing history
        page.onceDialog(Dialog::accept);
        page.locator("#btn-tester-clear-history").click();
        assertThat(page.locator(".history-item").count()).isEqualTo(0);

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Scenario 4: Test Stub shortcut transition to HTTP Tester")
    void testTestStubShortcutTransition() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector(".stub-card");

        // Click the POST stub card
        page.locator(".stub-card:has-text('Create Order API')").click();

        // Click '⚡ Test Stub' button
        page.locator("#btn-test-stub").click();

        // Should switch to tester tab
        page.waitForSelector("#tab-tester.active");

        // Verify prefilled values
        assertThat(page.locator("#tester-method").inputValue()).isEqualTo("POST");
        assertThat(page.locator("#tester-url").inputValue()).isEqualTo("/api/v1/orders");

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Scenario 5: Request journal filtering and inline accordion details")
    void testRequestJournalFilteringAndAccordion() {
        // Send a request directly to WireMock to generate a journal entry
        page.navigate(wireMockServer.baseUrl() + "/api/v1/users");

        // Open UI journal tab
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#journal");

        page.waitForSelector("#journal-list tr");
        Locator rows = page.locator("#journal-list tr.journal-row");
        assertThat(rows.count()).isGreaterThanOrEqualTo(1);

        // Click first journal row to expand inline details
        rows.first().click();

        // Inline detail row should appear right after the clicked row
        Locator detailRow = page.locator("tr.journal-detail-row");
        assertThat(detailRow.count()).isEqualTo(1);
        assertThat(detailRow.isVisible()).isTrue();

        // Test filtering
        page.locator("#journal-search").fill("users");
        assertThat(page.locator("#journal-list tr.journal-row").count()).isGreaterThanOrEqualTo(1);

        page.locator("#journal-search").fill("nonexistent-endpoint-xyz");
        assertThat(page.locator("#journal-list tr.journal-row").count()).isEqualTo(0);

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Scenario 6: Deep linking direct navigation with query params")
    void testDeepLinkingDirectNavigation() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#tester?m=POST&p=%2Fcustom%2Fdeep-link");

        page.waitForSelector("#tab-tester.active");

        assertThat(page.locator("#tester-method").inputValue()).isEqualTo("POST");
        assertThat(page.locator("#tester-url").inputValue()).isEqualTo("/custom/deep-link");

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Scenario 7: Sidebar stub filter search box")
    void testSidebarStubSearchFiltering() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector(".stub-card");

        assertThat(page.locator(".stub-card").count()).isGreaterThanOrEqualTo(2);

        // Search for 'orders'
        page.locator("#search-box").fill("orders");
        assertThat(page.locator(".stub-card:visible").count()).isEqualTo(1);
        assertThat(page.locator(".stub-card:visible").textContent()).contains("orders");

        // Search for non-existing query
        page.locator("#search-box").fill("non-existent-filter-query");
        assertThat(page.locator(".stub-card:visible").count()).isEqualTo(0);

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Scenario 9: Scenarios DAG visualizer, state pipeline, and state overrides")
    void testScenariosDagVisualizer() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#scenarios");
        page.waitForSelector("#tab-scenarios.active .scenario-fsm-card");

        assertThat(page.locator(".scenario-name").textContent()).contains("Case-FSM");
        assertThat(page.locator(".scenario-state-pill").textContent()).contains("Started");

        // Verify pipeline steps
        assertThat(page.locator(".fsm-step-name").allTextContents()).contains("Started", "OPEN", "CLOSED");

        // Override state to OPEN
        page.locator(".scenario-target-state-select").selectOption("OPEN");
        page.locator(".btn-set-scenario-state").click();
        page.waitForSelector(".scenario-state-pill:has-text('OPEN')");
        assertThat(page.locator(".fsm-node-card.active .fsm-node-name").textContent())
                .isEqualTo("OPEN");

        // Reset state back to Started
        page.locator(".btn-reset-single-scenario").click();
        page.waitForSelector(".scenario-state-pill:has-text('Started')");
        assertThat(page.locator(".fsm-node-card.active .fsm-node-name").textContent())
                .isEqualTo("Started");

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Scenario 10: OpenAPI Import modal, YAML spec paste, and stub synthesis")
    void testOpenApiImportModalWorkflow() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector("#btn-open-openapi-modal");

        // Click Open Import Modal
        page.locator("#btn-open-openapi-modal").click();
        page.waitForSelector("#openapi-modal:not(.hidden)");

        // Verify elements visible
        assertThat(page.locator("#openapi-spec-content").isVisible()).isTrue();
        assertThat(page.locator("#btn-submit-openapi-import").isVisible()).isTrue();

        // Paste YAML spec
        String spec =
                """
                openapi: 3.0.3
                info:
                  title: Warehouse Inventory
                  version: 1.0.0
                paths:
                  /api/v1/warehouse/stock/{stockId}:
                    post:
                      summary: Create warehouse stock item
                      parameters:
                        - name: stockId
                          in: path
                          required: true
                          example: STK-882
                          schema:
                            type: string
                        - name: X-Warehouse-Region
                          in: header
                          required: true
                          example: EU-NORTH
                          schema:
                            type: string
                        - name: notify
                          in: query
                          required: true
                          example: true
                          schema:
                            type: boolean
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema:
                              type: object
                              properties:
                                warehouseId:
                                  type: string
                                  example: WH-12
                                capacity:
                                  type: integer
                                  example: 500
                      responses:
                        '201':
                          description: Stock item created
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  status:
                                    type: string
                                    example: CREATED
                                  createdId:
                                    type: string
                                    example: STK-882
                """;

        page.locator("#openapi-spec-content").fill(spec);

        // Click Generate Stubs
        page.locator("#btn-submit-openapi-import").click();

        // Feedback message should display success
        page.waitForSelector(".modal-feedback.success");
        assertThat(page.locator(".modal-feedback.success").textContent()).contains("Successfully created 1 stubs");

        // Modal should close automatically (hidden state)
        page.waitForSelector("#openapi-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));

        // The new stub card should now appear in the stubs list showing the full urlPathTemplate
        page.waitForSelector(".stub-card:has-text('/api/v1/warehouse/stock/{stockId}')");
        Locator newStubCard = page.locator(".stub-card:has-text('/api/v1/warehouse/stock/{stockId}')");
        assertThat(newStubCard.isVisible()).isTrue();
        assertThat(newStubCard.locator(".stub-card-url").textContent()).isEqualTo("/api/v1/warehouse/stock/{stockId}");

        // Click on it and inspect
        newStubCard.click();
        assertThat(page.locator("#detail-url").textContent()).isEqualTo("/api/v1/warehouse/stock/{stockId}");
        assertThat(page.locator("#stub-json-viewer").textContent()).contains("CREATED");

        // Click Test Stub
        page.locator("#btn-test-stub").click();
        page.waitForSelector("#tab-tester.active");

        // Verify HTTP Tester is pre-populated with ready-made request example:
        // 1. Method is POST
        assertThat(page.locator("#tester-method").inputValue()).isEqualTo("POST");
        // 2. Path template is substituted with example param + required query param
        assertThat(page.locator("#tester-url").inputValue()).isEqualTo("/api/v1/warehouse/stock/STK-882?notify=true");
        // 3. Required headers prefilled (Content-Type & X-Warehouse-Region)
        assertThat(page.locator("#tester-headers").inputValue()).contains("Content-Type: application/json");
        assertThat(page.locator("#tester-headers").inputValue()).contains("X-Warehouse-Region: EU-NORTH");
        // 4. Request Body prefilled with synthesized JSON payload
        assertThat(page.locator("#tester-body").inputValue()).contains("\"warehouseId\" : \"WH-12\"");
        assertThat(page.locator("#tester-body").inputValue()).contains("\"capacity\" : 500");

        // Send the prefilled request directly to WireMock!
        page.locator("#btn-tester-send").click();
        page.waitForSelector("#tester-response-status:has-text('201')");
        assertThat(page.locator("#tester-response-body").textContent()).contains("CREATED");
        assertThat(page.locator("#tester-response-body").textContent()).contains("STK-882");

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Order(13)
    @DisplayName("Should display project pills and filter stubs by project")
    void testProjectGroupingAndFiltering() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector("#stub-list .stub-card");

        // Verify project selector is present
        Locator projectSelect = page.locator("#project-filter-select");
        assertThat(projectSelect.isVisible()).isTrue();

        // Verify project pills are visible on stub cards
        Locator projectPills = page.locator(".project-pill");
        assertThat(projectPills.count()).isGreaterThan(0);

        // Verify status pills (All, Active, Disabled) are present
        Locator btnAll = page.locator("#btn-filter-all");
        Locator btnActive = page.locator("#btn-filter-active");
        Locator btnDisabled = page.locator("#btn-filter-disabled");
        assertThat(btnAll.isVisible()).isTrue();
        assertThat(btnActive.isVisible()).isTrue();
        assertThat(btnDisabled.isVisible()).isTrue();

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Order(14)
    @DisplayName("Should toggle stub between active and disabled states and affect HTTP traffic")
    void testEnableDisableStubToggle() throws Exception {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector(".stub-card:has-text('/api/v1/users')");

        Locator userCard = page.locator(".stub-card:has-text('/api/v1/users')").first();
        Locator toggleBtn = userCard.locator(".btn-card-toggle");

        // Initial traffic should be 200 OK
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(wireMockServer.baseUrl() + "/api/v1/users"))
                .GET()
                .build();
        java.net.http.HttpResponse<String> initialRes =
                client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(initialRes.statusCode()).isEqualTo(200);

        // 1. Toggle OFF via UI card toggle
        toggleBtn.click();
        page.waitForSelector(".stub-card.stub-disabled:has-text('/api/v1/users')");
        assertThat(userCard.locator(".status-badge.badge-disabled").isVisible()).isTrue();

        // 2. WireMock must now return 404 for this route
        java.net.http.HttpResponse<String> disabledRes =
                client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(disabledRes.statusCode()).isEqualTo(404);

        // 3. Toggle back ON via UI card toggle
        toggleBtn.click();
        page.waitForSelector(".stub-card:not(.stub-disabled):has-text('/api/v1/users')");

        // 4. WireMock must now return 200 again
        java.net.http.HttpResponse<String> reEnabledRes =
                client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(reEnabledRes.statusCode()).isEqualTo(200);

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Order(15)
    @DisplayName("Should import WireMock JSON bundle with custom project assignment")
    void testWireMockBundleImport() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector("#btn-open-openapi-modal");

        // Open Import Modal
        page.locator("#btn-open-openapi-modal").click();
        page.waitForSelector(
                "#openapi-modal.hidden", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.DETACHED));

        // Switch to WireMock JSON Bundle tab
        page.locator("#import-tab-bundle").click();
        page.waitForSelector("#import-view-bundle:not(.hidden)");

        // Fill bundle content and project
        String bundleJson =
                """
                {
                  "mappings": [
                    {
                      "name": "Healthcheck Ping",
                      "request": {
                        "method": "GET",
                        "url": "/api/v1/health/ping"
                      },
                      "response": {
                        "status": 200,
                        "body": "PONG"
                      }
                    }
                  ]
                }
                """;

        page.locator("#bundle-content").fill(bundleJson);
        page.locator("#bundle-target-project").fill("Ops Management");

        // Click Import
        page.locator("#btn-submit-bundle-import").click();
        page.waitForSelector("#openapi-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));

        // Verify imported stub card is displayed with the project
        page.waitForSelector(".stub-card:has-text('/api/v1/health/ping')");
        Locator pingCard = page.locator(".stub-card:has-text('/api/v1/health/ping')");
        assertThat(pingCard.isVisible()).isTrue();
        assertThat(pingCard.textContent()).contains("Ops Management");

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Order(16)
    @DisplayName("Should show bulk actions bar when stub checkboxes are selected")
    void testBulkActionsBar() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector(".stub-card-chk");

        // Initially hidden
        assertThat(page.locator("#bulk-actions-bar").isHidden()).isTrue();

        // Check first stub
        Locator firstChk = page.locator(".stub-card-chk").first();
        firstChk.check();

        // Bulk bar should now be visible
        page.waitForSelector("#bulk-actions-bar:not(.hidden)");
        assertThat(page.locator("#bulk-selected-count").textContent()).contains("1 selected");

        // Uncheck
        firstChk.uncheck();
        page.waitForSelector(
                "#bulk-actions-bar", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
        assertThat(page.locator("#bulk-actions-bar").isHidden()).isTrue();

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Order(17)
    @DisplayName("Should clone stub mapping via editor modal, save it, and verify traffic")
    void testCloneAndEditStub() throws Exception {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector(".stub-card:has-text('/api/v1/users')");

        // Select stub
        page.locator(".stub-card:has-text('/api/v1/users')").first().click();
        page.waitForSelector("#btn-clone-stub");

        // Click Duplicate
        page.locator("#btn-clone-stub").click();
        page.waitForSelector("#stub-editor-modal:not(.hidden)");
        assertThat(page.locator("#editor-modal-title").textContent()).isEqualTo("Duplicate Stub Mapping");

        // Modify URL and status
        page.locator("#editor-url").fill("/api/v1/users/cloned");
        page.locator("#editor-status").fill("201");
        page.locator("#editor-project").fill("User Clones");

        // Save
        page.locator("#btn-save-editor").click();
        page.waitForSelector(
                "#stub-editor-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));

        // Verify cloned stub appears in stubs list
        page.waitForSelector(".stub-card:has-text('/api/v1/users/cloned')");
        Locator clonedCard = page.locator(".stub-card:has-text('/api/v1/users/cloned')");
        assertThat(clonedCard.isVisible()).isTrue();
        assertThat(clonedCard.textContent()).contains("User Clones");

        // Verify live HTTP traffic returns 201 Created
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(wireMockServer.baseUrl() + "/api/v1/users/cloned"))
                .GET()
                .build();
        java.net.http.HttpResponse<String> res = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(201);

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Order(18)
    @DisplayName("Should display contextual pre-fill banner in tester and dismissible filter chip in journal")
    void testContextualTesterAndJournalWorkflow() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector(".stub-card:has-text('/api/v1/users')");

        // 1. Select stub and click Test Stub
        page.locator(".stub-card:has-text('/api/v1/users')").first().click();
        page.locator("#btn-test-stub").click();

        page.waitForSelector("#tab-tester.active");
        Locator testerBanner = page.locator("#tester-context-banner");
        assertThat(testerBanner.isVisible()).isTrue();
        assertThat(page.locator("#tester-context-title").textContent()).contains("/api/v1/users");

        // Click Clear for Ad-hoc
        page.locator("#btn-tester-clear-context").click();
        assertThat(testerBanner.isVisible()).isFalse();

        // 2. Return to Stubs and click View in Journal
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector(".stub-card:has-text('/api/v1/users')");
        page.locator(".stub-card:has-text('/api/v1/users')").first().click();
        page.locator("#btn-view-stub-in-journal").click();

        page.waitForSelector("#tab-journal.active");
        Locator journalBanner = page.locator("#journal-context-banner");
        assertThat(journalBanner.isVisible()).isTrue();
        assertThat(page.locator("#journal-filter-chip-text").textContent()).contains("/api/v1/users");

        // Click dismiss ✕ button
        page.locator("#btn-journal-clear-stub-filter").click();
        assertThat(journalBanner.isVisible()).isFalse();
        assertThat(page.locator("#journal-search").inputValue()).isEmpty();

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @Order(19)
    @DisplayName(
            "Should execute full E2E workflow: OpenAPI POST spec import -> stub synthesis with body -> tester execution with persistent sidebar -> bottom history -> journal review")
    void testOpenApiPostE2EWorkflow() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector("#btn-open-openapi-modal");

        // 1. Open OpenAPI import modal
        page.locator("#btn-open-openapi-modal").click();
        page.waitForSelector("#openapi-modal:not(.hidden)");

        String openApiYaml =
                """
                openapi: 3.0.3
                info:
                  title: Checkout Service API
                  version: 1.0.0
                paths:
                  /api/v1/checkout/cart:
                    post:
                      summary: Create checkout cart
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
                                  example: PROD-998
                                quantity:
                                  type: integer
                                  example: 3
                      responses:
                        '201':
                          description: Checkout cart created
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  cartId:
                                    type: string
                                    example: CART-771
                                  status:
                                    type: string
                                    example: CREATED
                """;

        page.locator("#openapi-spec-content").fill(openApiYaml);
        page.locator("#btn-submit-openapi-import").click();
        page.waitForSelector(".modal-feedback.success");
        page.waitForSelector("#openapi-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));

        // 2. Select synthesized POST stub in sidebar
        page.waitForSelector(".stub-card:has-text('/api/v1/checkout/cart')");
        page.locator(".stub-card:has-text('/api/v1/checkout/cart')").first().click();

        // 3. Verify stub details inspector shows POST and 201
        assertThat(page.locator("#detail-method").textContent()).isEqualTo("POST");
        assertThat(page.locator("#detail-url").textContent()).isEqualTo("/api/v1/checkout/cart");
        assertThat(page.locator("#detail-status").textContent()).isEqualTo("201");

        // 4. Click Test Stub
        page.locator("#btn-test-stub").click();
        page.waitForSelector("#tab-tester.active");

        // 5. Verify left sidebar remains visible and selected!
        assertThat(page.locator(".sidebar").isVisible()).isTrue();
        assertThat(page.locator(".stub-card:has-text('/api/v1/checkout/cart')")
                        .first()
                        .getAttribute("class"))
                .contains("selected");

        // 6. Verify HTTP Tester is pre-filled with synthesized example body
        assertThat(page.locator("#tester-method").inputValue()).isEqualTo("POST");
        assertThat(page.locator("#tester-url").inputValue()).isEqualTo("/api/v1/checkout/cart");
        assertThat(page.locator("#tester-headers").inputValue()).contains("Content-Type: application/json");
        assertThat(page.locator("#tester-body").inputValue()).contains("PROD-998");
        assertThat(page.locator("#tester-context-banner").isVisible()).isTrue();

        // 7. Send the request
        page.locator("#btn-tester-send").click();
        page.waitForSelector("#tester-response-status:has-text('201')");
        assertThat(page.locator("#tester-response-status").textContent()).contains("201");
        assertThat(page.locator("#tester-response-body").textContent()).contains("CART-771");

        // 8. Verify bottom history dock records the execution
        Locator historyCards = page.locator(".history-item");
        assertThat(historyCards.count()).isGreaterThanOrEqualTo(1);
        assertThat(historyCards.first().textContent()).contains("POST");
        assertThat(historyCards.first().textContent()).contains("201");
        assertThat(historyCards.first().textContent()).contains("/api/v1/checkout/cart");

        // 9. Navigate to Request Journal to review the logged entry
        page.locator("#nav-tab-journal").click();
        page.waitForSelector("#tab-journal.active");

        // In Request Journal, sidebar is hidden to maximize table space
        assertThat(page.locator(".sidebar").isVisible()).isFalse();

        page.waitForSelector("#journal-list tr.journal-row:has-text('/api/v1/checkout/cart')");
        Locator journalRow = page.locator("#journal-list tr.journal-row:has-text('/api/v1/checkout/cart')")
                .first();
        assertThat(journalRow.textContent()).contains("POST");
        assertThat(journalRow.textContent()).contains("201");

        // 10. Click row to inspect details
        journalRow.click();
        Locator detailRow = page.locator("tr.journal-detail-row");
        assertThat(detailRow.isVisible()).isTrue();
        assertThat(detailRow.textContent()).contains("CART-771");

        assertThat(pageErrors).isEmpty();
    }
}
