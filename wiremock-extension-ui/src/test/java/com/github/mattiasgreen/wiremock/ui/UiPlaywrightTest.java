package com.github.mattiasgreen.wiremock.ui;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

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
        wireMockServer = new WireMockServer(
                WireMockConfiguration.options()
                        .dynamicPort()
                        .extensions(new UiAdminApiEndpoint())
        );
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

        wireMockServer.stubFor(get(urlEqualTo("/favicon.ico"))
                .willReturn(ok().withBody(new byte[0])));

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
            if (response.status() >= 400 && !response.url().contains("favicon.ico") && !response.url().contains("nonexistent")) {
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

        assertThat(page.title()).isEqualTo("WireMock Stub Viewer");

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
        page.locator(".stub-card:has-text('POST')").click();

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
}
