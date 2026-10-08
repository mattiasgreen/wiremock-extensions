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
    private final List<String> httpErrors = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    void startAll() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .extensions(
                        new UiAdminApiEndpoint(),
                        new StubLifecycleAdminEndpoint(new DisabledStubStore()),
                        new com.github.mattiasgreen.wiremock.openapi.OpenApiAdminEndpoint()));
        wireMockServer.start();
        resetDefaultStubs();

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
        httpErrors.clear();
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
                httpErrors.add(response.status() + " " + response.url());
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

        // Test collapse and expand of vertical history sidebar
        Locator historyPanel = page.locator("#tester-history-panel");
        assertThat(historyPanel.getAttribute("class")).doesNotContain("collapsed");

        page.locator("#btn-toggle-tester-history").click();
        assertThat(historyPanel.getAttribute("class")).contains("collapsed");
        assertThat(page.locator("#tester-history-collapsed-bar").isVisible()).isTrue();

        page.locator("#btn-expand-tester-history").click();
        assertThat(historyPanel.getAttribute("class")).doesNotContain("collapsed");

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
    @DisplayName("Should toggle stub between active and disabled states in detail view and affect HTTP traffic")
    void testEnableDisableStubToggle() throws Exception {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector(".stub-card:has-text('/api/v1/users')");

        Locator userCard = page.locator(".stub-card:has-text('/api/v1/users')").first();
        userCard.click();
        page.waitForSelector("#stub-detail-view:not(.hidden)");

        Locator toggleBtn = page.locator("#btn-toggle-stub");

        // Initial traffic should be 200 OK
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(wireMockServer.baseUrl() + "/api/v1/users"))
                .GET()
                .build();
        java.net.http.HttpResponse<String> initialRes =
                client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(initialRes.statusCode()).isEqualTo(200);

        // 1. Toggle OFF via detail inspector toggle
        toggleBtn.click();
        page.waitForSelector(".stub-card.stub-disabled:has-text('/api/v1/users')");
        assertThat(userCard.locator(".status-badge.badge-disabled").isVisible()).isTrue();
        assertThat(page.locator("#detail-lifecycle-state").textContent()).isEqualTo("DISABLED");
        assertThat(toggleBtn.textContent()).contains("Enable Stub");

        // 2. WireMock must now return 404 for this route
        java.net.http.HttpResponse<String> disabledRes =
                client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(disabledRes.statusCode()).isEqualTo(404);

        // 3. Toggle back ON via detail inspector toggle
        toggleBtn.click();
        page.waitForSelector(".stub-card:not(.stub-disabled):has-text('/api/v1/users')");
        assertThat(page.locator("#detail-lifecycle-state").textContent()).isEqualTo("ACTIVE");
        assertThat(toggleBtn.textContent()).contains("Disable Stub");

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
            "Should execute complete E2E scenario: clear stubs -> import OpenAPI GET & POST -> test both endpoints -> intentionally miss GET stub -> verify unmatched in journal")
    void testOpenApiPostE2EWorkflow() {
        try {
            wireMockServer.resetRequests();
            page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
            page.waitForSelector("#stubs-count-label");

            // 1. Clear all existing stubs if any exist
            if (page.locator(".stub-card").count() > 0) {
                page.locator("#chk-select-all").click();
                page.waitForSelector("#bulk-actions-bar:not(.hidden)");
                page.onceDialog(Dialog::accept);
                page.locator("#btn-bulk-delete").click();
                page.waitForSelector(".stub-list .empty-state");
                assertThat(page.locator(".stub-card").count()).isEqualTo(0);
            }

            // 2. Open OpenAPI import modal
            page.locator("#btn-open-openapi-modal").click();
            page.waitForSelector("#openapi-modal:not(.hidden)");

            String openApiYaml =
                    """
                    openapi: 3.0.3
                    info:
                      title: Store & Catalog API
                      version: 1.0.0
                    paths:
                      /api/v1/catalog/products:
                        get:
                          summary: List catalog products
                          responses:
                            '200':
                              description: Product catalog list
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
                                          example: Wireless Noise-Canceling Headphones
                                        price:
                                          type: number
                                          example: 199.99
                      /api/v1/catalog/orders:
                        post:
                          summary: Create a product order
                          requestBody:
                            required: true
                            content:
                              application/json:
                                schema:
                                  type: object
                                  required:
                                    - sku
                                    - quantity
                                    - customer
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
                              description: Order placed successfully
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
            page.locator("#btn-submit-openapi-import").click();
            page.waitForSelector(".modal-feedback.success");
            page.waitForSelector(
                    "#openapi-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));

            // 3. Verify both synthesized stubs appear in sidebar
            page.waitForSelector(".stub-card:has-text('/api/v1/catalog/orders')");
            page.waitForSelector(".stub-card:has-text('/api/v1/catalog/products')");
            assertThat(page.locator(".stub-card").count()).isEqualTo(2);

            // 4. Test POST endpoint: Select orders POST stub
            page.locator(".stub-card:has-text('/api/v1/catalog/orders')")
                    .first()
                    .click();
            assertThat(page.locator("#detail-method").textContent()).isEqualTo("POST");
            assertThat(page.locator("#detail-url").textContent()).isEqualTo("/api/v1/catalog/orders");
            assertThat(page.locator("#detail-status").textContent()).isEqualTo("201");

            // Click Test Stub
            page.locator("#btn-test-stub").click();
            page.waitForSelector("#tab-tester.active");

            // Verify left sidebar remains visible and selected
            assertThat(page.locator(".sidebar").isVisible()).isTrue();
            assertThat(page.locator(".stub-card:has-text('/api/v1/catalog/orders')")
                            .first()
                            .getAttribute("class"))
                    .contains("selected");

            // Verify HTTP Tester is pre-filled with synthesized example body
            assertThat(page.locator("#tester-method").inputValue()).isEqualTo("POST");
            assertThat(page.locator("#tester-url").inputValue()).isEqualTo("/api/v1/catalog/orders");
            assertThat(page.locator("#tester-headers").inputValue()).contains("Content-Type: application/json");
            assertThat(page.locator("#tester-body").inputValue()).contains("PROD-778");
            assertThat(page.locator("#tester-context-banner").isVisible()).isTrue();

            // Send POST request
            page.locator("#btn-tester-send").click();
            page.waitForSelector("#tester-response-status:has-text('201')");
            assertThat(page.locator("#tester-response-status").textContent()).contains("201");
            assertThat(page.locator("#tester-response-body").textContent()).contains("ORD-9912");

            // 5. Test GET endpoint: Select products GET stub in sidebar (which remains visible in tester)
            page.locator(".stub-card:has-text('/api/v1/catalog/products')")
                    .first()
                    .click();
            assertThat(page.locator("#tester-method").inputValue()).isEqualTo("GET");
            assertThat(page.locator("#tester-url").inputValue()).isEqualTo("/api/v1/catalog/products");

            // Send GET request
            page.locator("#btn-tester-send").click();
            page.waitForSelector("#tester-response-status:has-text('200')");
            assertThat(page.locator("#tester-response-status").textContent()).contains("200");
            assertThat(page.locator("#tester-response-body").textContent())
                    .contains("Wireless Noise-Canceling Headphones");

            // 6. Intentionally miss the GET stub by modifying URL
            page.locator("#tester-url").fill("/api/v1/catalog/products/missing-sku");
            page.locator("#btn-tester-send").click();
            page.waitForSelector("#tester-response-status:has-text('404')");
            assertThat(page.locator("#tester-response-status").textContent()).contains("404");

            // 7. Verify vertical history sidebar recorded all 3 operations
            Locator historyCards = page.locator(".history-item");
            assertThat(historyCards.count()).isGreaterThanOrEqualTo(3);
            assertThat(historyCards.nth(0).textContent()).contains("404");
            assertThat(historyCards.nth(1).textContent()).contains("200");
            assertThat(historyCards.nth(2).textContent()).contains("201");

            // 8. Navigate to Request Journal to review traffic and unmatched request
            page.locator("#nav-tab-journal").click();
            page.waitForSelector("#tab-journal.active");

            page.waitForSelector("#journal-list tr.journal-row:has-text('/api/v1/catalog/orders')");
            page.waitForSelector("#journal-list tr.journal-row:has-text('/api/v1/catalog/products')");
            page.waitForSelector("#journal-list tr.journal-row:has-text('missing-sku')");

            // Expand unmatched row
            Locator unmatchedRow = page.locator("#journal-list tr.journal-row:has-text('missing-sku')")
                    .first();
            assertThat(unmatchedRow.textContent()).contains("404");
            assertThat(unmatchedRow.textContent().toUpperCase()).contains("UNMATCHED");
            unmatchedRow.click();

            Locator detailRow = page.locator("tr.journal-detail-row");
            assertThat(detailRow.isVisible()).isTrue();
            assertThat(detailRow.textContent()).contains("missing-sku");

            // Toggle filter to show unmatched only
            page.locator("#filter-unmatched-only").check();
            assertThat(page.locator("#journal-list tr.journal-row:not(.journal-detail-row)")
                            .count())
                    .isEqualTo(1);

            assertThat(pageErrors).isEmpty();
        } finally {
            resetDefaultStubs();
        }
    }

    @Test
    @DisplayName("Scenario 11: OpenAPI Live Proxy Import, Testing via UI, Snapshot Recording, and Project Mode Flip")
    void testOpenApiLiveProxyAndRecordingLifecycle() {
        WireMockServer upstreamServer =
                new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstreamServer.start();

        try {
            upstreamServer.stubFor(get(urlEqualTo("/api/v1/live-catalog"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"status\":\"success\",\"upstreamData\":\"Live Catalog Item\"}")));

            page.navigate(wireMockServer.baseUrl() + "/__admin/ui/");
            assertThat(page.title()).isEqualTo("WireMock Console");

            // 1. Open Import Modal
            page.locator("#btn-open-openapi-modal").click();
            page.waitForSelector("#openapi-modal:not(.hidden)");

            // 2. Select Live Proxy & Recording mode
            page.locator("#opt-openapi-mode-proxy").click();
            assertThat(page.locator("#openapi-proxy-config").isVisible()).isTrue();

            page.locator("#openapi-proxy-url").fill(upstreamServer.baseUrl());
            page.locator("#openapi-project-name").fill("Catalog Service");

            String specYaml =
                    """
                    openapi: 3.0.3
                    info:
                      title: Catalog Service
                      version: 1.0.0
                    paths:
                      /api/v1/live-catalog:
                        get:
                          summary: Fetch live catalog
                          responses:
                            '200':
                              description: Ok
                    """;

            page.locator("#openapi-spec-content").fill(specYaml);
            page.locator("#btn-submit-openapi-import").click();

            // Wait for modal to close and stub to appear
            page.waitForSelector(
                    "#openapi-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
            page.waitForSelector("#stub-list .stub-card");

            // 3. Verify PROXY badge is rendered in stub card
            Locator proxyCard = page.locator(".stub-card")
                    .filter(new Locator.FilterOptions().setHasText("live-catalog"))
                    .first();
            assertThat(proxyCard.isVisible()).isTrue();
            assertThat(proxyCard.locator(".status-proxy").textContent()).contains("PROXY");

            // 4. Test Stub via HTTP Tester
            proxyCard.click();
            page.locator("#btn-test-stub").click();
            page.waitForSelector("#tab-tester.active");

            // Send request through Primary WireMock -> proxies to upstream
            page.locator("#btn-tester-send").click();
            page.waitForSelector("#tester-response-status:has-text('200')");
            assertThat(page.locator("#tester-response-body").textContent()).contains("Live Catalog Item");

            // 5. Select project in Project Filter
            page.locator("#nav-tab-stubs").click();
            page.waitForSelector("#tab-stub-detail.active");
            page.locator("#project-filter-select").selectOption("Catalog Service");

            // 6. Project lifecycle bar should be visible in LIVE PROXY mode
            page.waitForSelector("#project-lifecycle-bar:not(.hidden)");
            assertThat(page.locator("#project-mode-badge").textContent()).contains("LIVE PROXY");

            // 7. Snapshot live traffic (handle alert dialog)
            page.onceDialog(dialog -> dialog.accept());
            page.locator("#btn-project-snapshot").click();

            // Verify recorded stub was created in DISABLED state
            page.waitForSelector("#btn-filter-disabled");
            page.locator("#btn-filter-disabled").click();
            Locator disabledCard = page.locator(".stub-card")
                    .filter(new Locator.FilterOptions().setHasText("RECORDED"))
                    .first();
            assertThat(disabledCard.isVisible()).isTrue();
            assertThat(disabledCard.locator(".status-badge.badge-disabled").textContent())
                    .contains("DISABLED");

            // 8. Toggle project mode to STUBS MODE
            page.locator("#btn-project-mode-toggle").click();
            page.waitForSelector("#project-mode-badge:has-text('STUBS MODE')");

            // 9. Stop upstream server (simulate offline environment)
            upstreamServer.stop();

            // 10. Re-test endpoint in HTTP Tester -> Served offline from the recorded stub!
            page.locator("#nav-tab-tester").click();
            page.waitForSelector("#tab-tester.active");
            page.locator("#btn-tester-send").click();
            page.waitForSelector("#tester-response-status:has-text('200')");
            assertThat(page.locator("#tester-response-body").textContent()).contains("Live Catalog Item");

            assertThat(pageErrors).isEmpty();
        } finally {
            if (upstreamServer.isRunning()) {
                upstreamServer.stop();
            }
            resetDefaultStubs();
        }
    }

    @Test
    @DisplayName("Scenario 12: Stub lifecycle management (Edit Active, Edit Disabled, Ad-hoc Creation)")
    void testStubManagementEditAndCreateWorkflow() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/");
        page.waitForSelector("#stub-list .stub-card");

        // 1. Edit an active stub
        Locator usersCard = page.locator(".stub-card")
                .filter(new Locator.FilterOptions().setHasText("List Users API"))
                .first();
        usersCard.click();
        page.waitForSelector("#tab-stub-detail.active");
        page.locator("#btn-edit-stub").click();

        // Modal opens
        page.waitForSelector("#stub-editor-modal:not(.hidden)");
        page.locator("#editor-name").fill("Updated Users API");
        page.locator("#btn-save-editor").click();

        // Modal closes and detail updates
        page.waitForSelector(
                "#stub-editor-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
        page.waitForSelector("#detail-name:has-text('Updated Users API')");
        assertThat(page.locator("#detail-name").textContent()).contains("Updated Users API");

        // 2. Toggle stub to disabled
        page.locator("#btn-toggle-stub").click();
        page.waitForSelector("#detail-lifecycle-state:has-text('DISABLED')");

        // 3. Edit the DISABLED stub - previously this returned a 404 error!
        page.locator("#btn-edit-stub").click();
        page.waitForSelector("#stub-editor-modal:not(.hidden)");
        page.locator("#editor-name").fill("Updated Disabled Users API");
        page.locator("#btn-save-editor").click();

        // Modal closes and verified without 404
        page.waitForSelector(
                "#stub-editor-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
        page.waitForSelector("#detail-name:has-text('Updated Disabled Users API')");
        assertThat(page.locator("#detail-name").textContent()).contains("Updated Disabled Users API");

        // 4. Ad-hoc Stub Creation via ➕ New
        page.locator("#btn-create-stub").click();
        page.waitForSelector("#stub-editor-modal:not(.hidden)");
        assertThat(page.locator("#editor-modal-title").textContent()).contains("Create New Stub");

        page.locator("#editor-name").fill("Ad-hoc Custom API");
        page.locator("#editor-method").selectOption("POST");
        page.locator("#editor-url").fill("/api/v1/adhoc-test");
        page.locator("#editor-status").fill("201");
        page.locator("#editor-body").fill("{\"adhoc\": true, \"message\": \"Created successfully\"}");
        page.locator("#btn-save-editor").click();

        page.waitForSelector(
                "#stub-editor-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
        page.waitForSelector(".stub-card:has-text('Ad-hoc Custom API')");

        // 5. Test newly created stub in HTTP Tester
        Locator adhocCard = page.locator(".stub-card")
                .filter(new Locator.FilterOptions().setHasText("Ad-hoc Custom API"))
                .first();
        adhocCard.click();
        page.locator("#btn-test-stub").click();
        page.waitForSelector("#tab-tester.active");
        page.locator("#btn-tester-send").click();
        page.waitForSelector("#tester-response-status:has-text('201')");
        assertThat(page.locator("#tester-response-body").textContent()).contains("Created successfully");

        // Ensure no browser errors or 404s occurred during the operations
        assertThat(pageErrors).isEmpty();
        assertThat(consoleErrors).isEmpty();
        assertThat(httpErrors).isEmpty();
    }

    private void resetDefaultStubs() {
        wireMockServer.resetAll();
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
    }
}
