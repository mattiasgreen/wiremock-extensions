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

        // Find Banking KYC stub
        Locator kycStub = page.locator("#stub-list .stub-card:has-text('Banking KYC Verification')");
        assertThat(kycStub.count()).isGreaterThanOrEqualTo(1);

        // Click KYC stub to view details
        kycStub.first().click();
        page.waitForSelector("#stub-detail-view:not(.hidden)");
        assertThat(page.locator("#detail-url").textContent()).contains("/api/v1/kyc/verify");

        // 3. Verify Filter Pills and Disabled Stubs
        Locator btnFilterDisabled = page.locator("#btn-filter-disabled");
        assertThat(btnFilterDisabled.textContent()).contains("Disabled (2)");
        btnFilterDisabled.click();
        Locator disabledCards = page.locator("#stub-list .stub-card");
        assertThat(disabledCards.count()).isEqualTo(2);

        // Switch back to All
        page.locator("#btn-filter-all").click();

        // 4. Verify Request Journal Tab
        page.locator("#nav-tab-journal").click();
        page.waitForSelector("#journal-list tr[data-testid='journal-row']");

        Locator journalRows = page.locator("#journal-list tr[data-testid='journal-row']");
        int initialJournalCount = journalRows.count();
        assertThat(initialJournalCount).isGreaterThanOrEqualTo(4);

        // Expand KYC request row to inspect preview chips
        Locator kycRow = page.locator("#journal-list tr[data-testid='journal-row']:has-text('/api/v1/kyc/verify')");
        assertThat(kycRow.count()).isGreaterThanOrEqualTo(1);
        kycRow.first().click();

        // Verify detail row expands with nested payload preview chip
        page.waitForSelector("tr[data-testid='journal-detail-row']");
        Locator previewChip = page.locator("tr[data-testid='journal-detail-row'] .truncated-payload-preview");
        assertThat(previewChip.count()).isGreaterThanOrEqualTo(1);

        // 5. Test Interactive HTTP Tester execution in Demo Mode
        page.locator("#nav-tab-tester").click();
        page.waitForSelector("#tester-url");

        // Populate Tester with Banking KYC request
        page.locator("#tester-method").selectOption("POST");
        page.locator("#tester-url").fill("/api/v1/kyc/verify");
        page.locator("#tester-headers").fill("Content-Type: application/json");
        page.locator("#tester-body").fill("{\n  \"caseId\": \"KYC-9042\",\n  \"applicant\": \"Demo Test\"\n}");

        // Click Send
        page.locator("#btn-tester-send").click();

        // Verify response rendered with status 200 and response body
        page.waitForSelector("#tester-response-status:has-text('200')");
        assertThat(page.locator("#tester-response-status").textContent()).contains("200");
        assertThat(page.locator("#tester-response-body").textContent()).contains("KYC-9042");
        assertThat(page.locator("#tester-response-body").textContent()).contains("APPROVED");

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
        assertThat(page.locator(".scenario-name").first().textContent()).contains("OrderLifecycle");

        // Ensure zero unexpected browser console errors occurred
        assertThat(pageErrors).isEmpty();
    }
}
