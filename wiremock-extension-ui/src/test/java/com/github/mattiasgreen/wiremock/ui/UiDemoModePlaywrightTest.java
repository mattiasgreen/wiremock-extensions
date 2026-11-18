package com.github.mattiasgreen.wiremock.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * End-to-end Playwright tests verifying the in-browser static showcase engine (Demo Mode).
 * Verifies that the console operates completely in-browser on static hosting (such as GitHub Pages)
 * with preloaded stubs, rich request journal, scenario state progression, and interactive HTTP Tester execution.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UiDemoModePlaywrightTest {

    private WireMockServer assetServer;
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;

    private final List<String> pageErrors = Collections.synchronizedList(new ArrayList<>());
    private final List<String> consoleErrors = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    void setUpAll() {
        // Start asset server to host UI static resources
        assetServer =
                new WireMockServer(WireMockConfiguration.options().dynamicPort().extensions(new UiAdminApiEndpoint()));
        assetServer.start();

        browser = AbstractUiPlaywrightTest.getSharedBrowser();
        context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1280, 800));
        page = context.newPage();

        page.onPageError(pageErrors::add);
        page.onConsoleMessage(msg -> {
            if ("error".equalsIgnoreCase(msg.type())) {
                consoleErrors.add(msg.text());
            }
        });
    }

    @AfterAll
    void tearAll() {
        if (context != null) {
            context.close();
        }
        if (assetServer != null && assetServer.isRunning()) {
            assetServer.stop();
        }
    }

    @Test
    @DisplayName(
            "Demo Mode: In-browser showcase loads preloaded stubs, journal, scenarios, and supports interactive tester execution")
    void shouldRunCompletelyInBrowserDemoMode() {
        // Navigate with ?demo=true to activate static showcase mode
        page.navigate(assetServer.baseUrl() + "/__admin/ui/?demo=true");
        page.waitForSelector("#stub-list .stub-card");

        // 1. Verify Demo indicator badge and Target Host display
        Locator demoBadge = page.locator("#demo-mode-badge");
        assertThat(demoBadge.isVisible()).isTrue();
        assertThat(demoBadge.textContent()).contains("Demo Mode");

        Locator targetHostLabel = page.locator("#target-host-label");
        assertThat(targetHostLabel.textContent()).contains("Demo Mode");

        Locator targetDot = page.locator("#target-host-dot");
        assertThat(targetDot.getAttribute("class")).contains("demo");

        // 2. Verify Preloaded Stubs
        Locator stubCards = page.locator("#stub-list .stub-card");
        assertThat(stubCards.count()).isGreaterThanOrEqualTo(5);

        // Find Create Order polyglot stub and Async Kafka stub from DEMO_SNAPSHOT
        Locator orderStub = page.locator("#stub-list .stub-card:has-text('Create Order (Nested Polyglot Payload)')");
        assertThat(orderStub.count()).isGreaterThanOrEqualTo(1);

        Locator kafkaStub = page.locator("#stub-list .stub-card:has-text('Kafka Events Sink')");
        assertThat(kafkaStub.count()).isGreaterThanOrEqualTo(1);

        // Click Order stub to view details
        orderStub.first().click();
        page.waitForSelector("#stub-detail-view:not(.hidden)");
        assertThat(page.locator("#detail-url").textContent()).contains("/api/v1/orders");

        // 3. Verify Filter Pills and Disabled Stubs (DEMO_SNAPSHOT has 1 parked stub)
        Locator btnFilterDisabled = page.locator("#btn-filter-disabled");
        assertThat(btnFilterDisabled.textContent()).contains("Disabled (1)");
        btnFilterDisabled.click();
        Locator disabledCards = page.locator("#stub-list .stub-card");
        assertThat(disabledCards.count()).isEqualTo(1);

        // Switch back to All
        page.locator("#btn-filter-all").click();

        // Filter by Async Messaging via search box
        page.locator("#search-box").fill("Async Messaging");
        Locator asyncFilteredCards = page.locator("#stub-list .stub-card");
        assertThat(asyncFilteredCards.count()).isGreaterThanOrEqualTo(2);
        page.locator("#search-box").fill("");

        // 4. Verify Request Journal Tab
        page.locator("#nav-tab-journal").click();
        page.waitForSelector("#journal-list tr[data-testid='journal-row']");

        Locator journalRows = page.locator("#journal-list tr[data-testid='journal-row']");
        int initialJournalCount = journalRows.count();
        assertThat(initialJournalCount).isGreaterThanOrEqualTo(6);

        // Verify preloaded protocol badges in Demo Journal
        assertThat(page.locator("#journal-list .protocol-badge.protocol-casual").count())
                .isGreaterThanOrEqualTo(1);
        assertThat(page.locator("#journal-list .protocol-badge.protocol-http").count())
                .isGreaterThanOrEqualTo(1);

        // Test Protocol Filter Pills
        page.locator("#btn-journal-proto-casual").click();
        assertThat(page.locator("#journal-list tr[data-testid='journal-row']").count())
                .isGreaterThanOrEqualTo(1);
        assertThat(page.locator("#journal-list .protocol-badge.protocol-http").count())
                .isEqualTo(0);
        page.locator("#btn-journal-proto-all").click();
        assertThat(page.locator("#journal-list tr[data-testid='journal-row']").count())
                .isEqualTo(initialJournalCount);

        // Expand nested Create Order request row to inspect polyglot cards & Snapshot Stub button
        Locator orderRow = page.locator(
                        "#journal-list tr[data-testid='journal-row']:has-text('POST'):has-text('/api/v1/orders')")
                .first();
        orderRow.click();
        page.waitForSelector("tr[data-testid='journal-detail-row']");
        assertThat(page.locator("tr[data-testid='journal-detail-row'] .btn-snapshot-req")
                        .count())
                .isGreaterThanOrEqualTo(1);
        Locator nestedCards = page.locator("tr[data-testid='journal-detail-row'] .nested-payload-block");
        assertThat(nestedCards.count()).isGreaterThanOrEqualTo(1);

        // 5. Test Interactive HTTP Tester execution in Demo Mode via Document Workspace
        page.locator("#nav-tab-tester").click();
        page.waitForSelector("#tester-url");

        page.locator("#tester-method").selectOption("POST");
        page.locator("#tester-url").fill("/api/v1/orders");
        page.locator("#tester-headers").fill("Content-Type: application/json");

        page.locator("#btn-tester-edit-workspace").click();
        page.waitForSelector("#modal-document-workspace:not(.hidden)");
        if (page.locator("#workspace-editor-pane").isHidden()) {
            page.locator("#btn-workspace-edit-root").click();
            page.waitForSelector("#workspace-editor-pane:not(.hidden)");
        }
        String orderReqJson = "{\n  \"orderId\": \"ORD-9942\",\n  \"totalAmount\": 1250\n}";
        Locator editorInput = page.locator("#workspace-code-editor-host [data-testid='code-editor-input']");
        if ("TEXTAREA".equals(editorInput.evaluate("el => el.tagName"))) {
            editorInput.fill(orderReqJson);
        } else {
            editorInput.evaluate(
                    "(el, val) => { el.textContent = val; el.dispatchEvent(new Event('input', { bubbles: true })); }",
                    orderReqJson);
        }
        page.locator("#btn-workspace-apply-section").click();
        page.waitForSelector("#workspace-viewer-pane:not(.hidden)");
        page.locator("#btn-workspace-save").click();
        page.waitForSelector(
                "#modal-document-workspace",
                new com.microsoft.playwright.Page.WaitForSelectorOptions()
                        .setState(com.microsoft.playwright.options.WaitForSelectorState.HIDDEN));

        // Click Send
        page.locator("#btn-tester-send").click();

        // Verify response rendered with status 201 and response body
        page.waitForSelector("#tester-response-status:has-text('201')");
        assertThat(page.locator("#tester-response-status").textContent()).contains("201");
        assertThat(page.locator("#tester-response-body").textContent()).contains("ORD-9942");
        assertThat(page.locator("#tester-response-body").textContent()).contains("CREATED");

        // Switch to Journal and verify newly executed request was logged
        page.locator("#nav-tab-journal").click();
        page.waitForSelector("#journal-list tr[data-testid='journal-row']");
        Locator updatedRows = page.locator("#journal-list tr[data-testid='journal-row']");
        assertThat(updatedRows.count()).isGreaterThan(initialJournalCount);

        // 6. Verify Scenarios Tab
        page.locator("#nav-tab-scenarios").click();
        page.waitForSelector("#tab-scenarios.active .scenario-fsm-card");
        Locator scenarioCards = page.locator("#tab-scenarios.active .scenario-fsm-card");
        assertThat(scenarioCards.count()).isGreaterThanOrEqualTo(1);
        assertThat(page.locator(".scenario-name").first().textContent()).contains("OrderFulfillment");

        // Ensure zero unexpected browser console errors occurred
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Settings Modal: #btn-demo-target-host lazily loads Demo Mode on demand")
    void shouldActivateDemoModeFromSettingsModalTargetHostButton() {
        page.navigate(assetServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector("#btn-target-host");

        // Initially live (demo badge hidden)
        assertThat(page.locator("#demo-mode-badge").isHidden()).isTrue();

        // Open merged Settings modal -> Target tab
        page.locator("#btn-target-host").click();
        page.waitForSelector("#modal-settings:not(.hidden)");
        page.waitForSelector("#settings-panel-target:not(.hidden)");

        // Click Activate Demo Mode -> lazy imports demo-backend.js and seeds catalog
        page.locator("#btn-demo-target-host").click();
        page.waitForSelector(
                "#modal-settings",
                new com.microsoft.playwright.Page.WaitForSelectorOptions()
                        .setState(com.microsoft.playwright.options.WaitForSelectorState.HIDDEN));
        page.waitForSelector("#demo-mode-badge:not(.hidden)");
        page.waitForSelector("#stub-list .stub-card:has-text('Create Order (Nested Polyglot Payload)')");

        assertThat(pageErrors).isEmpty();
    }
}
