package com.github.mattiasgreen.wiremock.examples.banking;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dual-browser-tab Playwright E2E test and visual snapshot recorder for the 3-node
 * Banking Product Application &amp; Purchase progressive simulation scenario:
 * <ul>
 *   <li>Tab 1 ({@code portalPage}): Customer Portal UI on Node 1 ({@link ProductPortalServer})</li>
 *   <li>Tab 2 ({@code consolePage}): Embedded WireMock Console UI on Node 2 ({@code /__admin/ui/})</li>
 * </ul>
 */
class BankingProgressiveSimulationPlaywrightTest {

    private static final int VIEWPORT_WIDTH = 1280;
    private static final int VIEWPORT_HEIGHT = 800;

    private BankingSimulationHarness harness;
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Path screenshotDir;

    @BeforeEach
    void setUp() throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        Path baseReports = root.endsWith("wiremock-examples")
                ? root.resolve("build/reports/screenshots/banking")
                : root.resolve("wiremock-examples/build/reports/screenshots/banking");
        screenshotDir = baseReports;
        Files.createDirectories(screenshotDir);

        harness = new BankingSimulationHarness();

        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        context = browser.newContext(new Browser.NewContextOptions().setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT));
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("Full E2E Playwright Journey: Customer Portal UI + WireMock Console UI (LIVE PROXY -> STUBS MODE)")
    void shouldDrivePortalAndConsoleUiFromLiveProxyToOfflineStubsMode() {
        Page portalPage = context.newPage();
        Page consolePage = context.newPage();

        // 1. Open WireMock Console UI (Tab 2) and verify initial LIVE PROXY mode across All Projects
        consolePage.navigate(harness.getWireMockServer().baseUrl() + "/__admin/ui/");
        consolePage.waitForSelector("#stub-list .stub-card");
        consolePage.waitForSelector("#project-lifecycle-bar:not(.hidden)");
        assertThat(consolePage.locator("#project-mode-badge").textContent()).contains("LIVE PROXY");

        // Verify the Casual proxy stub card renders CASUAL badge and PROXY status badge
        Locator casualProxyCard = consolePage
                .locator(".stub-card")
                .filter(new Locator.FilterOptions().setHasText("/__casual/services/.*"))
                .first();
        assertThat(casualProxyCard.isVisible()).isTrue();
        assertThat(casualProxyCard.locator(".http-badge.badge-CASUAL").textContent())
                .isEqualTo("CASUAL");
        assertThat(casualProxyCard.locator(".stub-status-pill.status-proxy").textContent())
                .contains("PROXY");
        casualProxyCard.click();
        captureScreenshot(consolePage, "01-console-initial-live-proxy-stubs.png");

        // 2. Open Customer Portal UI (Tab 1) and run Step 1-6 (Submit Application) in LIVE PROXY mode
        portalPage.navigate(harness.getProductPortal().getPortalUrl());
        portalPage.waitForSelector("#btn-submit-application");
        assertThat(portalPage.locator(".brand").textContent()).contains("Nordic Retail Bank");

        portalPage.locator("#btn-submit-application").click();
        portalPage.waitForSelector("#offer-panel:not(.hidden)");

        assertThat(portalPage.locator("#offer-case-id").textContent()).isEqualTo("CASE-1001");
        assertThat(portalPage.locator("#offer-kyc-status").textContent()).isEqualTo("VERIFIED");
        assertThat(portalPage.locator("#offer-eligibility").textContent()).isEqualTo("ELIGIBLE");
        assertThat(portalPage.locator("#offer-case-status").textContent()).isEqualTo("OFFER_READY");
        captureScreenshot(portalPage, "02-portal-offer-ready.png");

        // 3. Approve Purchase Order (Step 7-10: Casual 2PC billing + Case completion + CRM sales update)
        portalPage.locator("#btn-approve-purchase").click();
        portalPage.waitForSelector("#purchase-confirmation:not(.hidden)");

        assertThat(portalPage.locator("#conf-invoice-id").textContent()).isEqualTo("INV-9001");
        assertThat(portalPage.locator("#conf-xa-status").textContent()).isEqualTo("XA_OK");
        assertThat(portalPage.locator("#conf-case-status").textContent()).isEqualTo("PURCHASED");
        assertThat(portalPage.locator("#conf-sales-id").textContent()).isEqualTo("SALE-5001");
        captureScreenshot(portalPage, "03-portal-purchase-confirmed-live.png");

        assertThat(harness.getUpstreamBackend().getCommittedXids()).hasSize(1);

        // 4. Switch to WireMock Console UI (Tab 2) -> Inspect Unified Request Journal (🌐 PROXIED)
        consolePage.locator("#nav-tab-journal").click();
        consolePage.waitForSelector("#tab-journal.active");
        consolePage.locator("#btn-refresh-journal").click();
        consolePage.waitForFunction("() => document.querySelectorAll('#journal-list tr.journal-row').length === 7");

        Locator journalRows = consolePage.locator("#journal-list tr.journal-row");
        assertThat(journalRows.count()).isEqualTo(7);
        for (int i = 0; i < 7; i++) {
            Locator row = journalRows.nth(i);
            assertThat(row.getAttribute("data-verdict")).isEqualTo("proxied");
            assertThat(row.locator("td.pill-proxied").textContent()).contains("PROXIED");
        }

        // Verify Casual row in Unified Journal shows CASUAL protocol badge and OK status (not HTTP 200)
        Locator casualJournalRow = consolePage
                .locator("#journal-list tr.journal-row")
                .filter(new Locator.FilterOptions().setHasText("/__casual/services/billing/charge"))
                .first();
        assertThat(casualJournalRow.isVisible()).isTrue();
        assertThat(casualJournalRow.getAttribute("data-protocol")).isEqualTo("casual");
        assertThat(casualJournalRow.locator(".http-badge.badge-CASUAL").textContent())
                .isEqualTo("CASUAL");
        assertThat(casualJournalRow.locator("td").nth(4).textContent()).isEqualTo("OK");

        // Expand Casual journal row to inspect payload
        casualJournalRow.click();
        captureScreenshot(consolePage, "04-console-journal-proxied-unified.png");

        // 5. Switch to Stubs Tab -> Snapshot Recordings & Flip to STUBS MODE
        consolePage.locator("#nav-tab-stubs").click();
        consolePage.waitForSelector("#tab-stub-detail.active");
        consolePage.waitForSelector("#project-lifecycle-bar:not(.hidden)");

        consolePage.onceDialog(Dialog::accept);
        consolePage.locator("#btn-project-snapshot").click();

        // Wait for snapshot to complete by checking disabled filter count
        consolePage.waitForFunction("() => document.getElementById('btn-filter-disabled')?.textContent.includes('7')");

        // Flip All Projects from LIVE PROXY -> STUBS MODE
        consolePage.locator("#btn-project-mode-toggle").click();
        consolePage.waitForSelector("#project-mode-badge:has-text('STUBS MODE')");
        consolePage.waitForSelector(".stub-card:has-text('RECORDED')");

        // Verify recorded Casual stub card displays [RECORDED CASUAL OK], CASUAL badge, and OK status pill
        Locator recordedCasualStub = consolePage
                .locator(".stub-card")
                .filter(new Locator.FilterOptions().setHasText("[RECORDED CASUAL OK]"))
                .first();
        assertThat(recordedCasualStub.isVisible()).isTrue();
        assertThat(recordedCasualStub.locator(".http-badge.badge-CASUAL").textContent())
                .isEqualTo("CASUAL");
        assertThat(recordedCasualStub.locator(".stub-status-pill").textContent())
                .isEqualTo("OK");
        recordedCasualStub.click();
        captureScreenshot(consolePage, "05-console-stubs-mode-recorded.png");

        // Clear Request Journal before Run 2
        consolePage.locator("#nav-tab-journal").click();
        consolePage.waitForSelector("#tab-journal.active");
        consolePage.onceDialog(Dialog::accept);
        consolePage.locator("#btn-reset-journal").click();
        consolePage.waitForFunction("() => document.querySelectorAll('#journal-list tr.journal-row').length === 0");

        // 6. Stop Upstream Server B completely -> Replay full 10-step scenario in Customer Portal (Tab 1)
        harness.stopUpstreamBackend();

        portalPage.locator("#btn-reset-portal").click();
        portalPage.waitForFunction("() => document.getElementById('offer-panel').classList.contains('hidden')");

        portalPage.locator("#btn-submit-application").click();
        portalPage.waitForSelector("#offer-panel:not(.hidden)");
        assertThat(portalPage.locator("#offer-case-id").textContent()).isEqualTo("CASE-1001");
        assertThat(portalPage.locator("#offer-kyc-status").textContent()).isEqualTo("VERIFIED");
        assertThat(portalPage.locator("#offer-eligibility").textContent()).isEqualTo("ELIGIBLE");
        assertThat(portalPage.locator("#offer-case-status").textContent()).isEqualTo("OFFER_READY");

        portalPage.locator("#btn-approve-purchase").click();
        portalPage.waitForSelector("#purchase-confirmation:not(.hidden)");
        assertThat(portalPage.locator("#conf-invoice-id").textContent()).isEqualTo("INV-9001");
        assertThat(portalPage.locator("#conf-xa-status").textContent()).isEqualTo("XA_OK");
        assertThat(portalPage.locator("#conf-case-status").textContent()).isEqualTo("PURCHASED");
        assertThat(portalPage.locator("#conf-sales-id").textContent()).isEqualTo("SALE-5001");
        captureScreenshot(portalPage, "06-portal-purchase-confirmed-stubbed.png");

        // Verify Upstream Server B was never touched during Run 2
        assertThat(harness.getUpstreamBackend().getCommittedXids()).hasSize(1);

        // 7. Switch to WireMock Console UI (Tab 2) -> Verify all 7 calls in Run 2 are 📌 STUB
        consolePage.locator("#btn-refresh-journal").click();
        consolePage.waitForFunction("() => document.querySelectorAll('#journal-list tr.journal-row').length === 7");

        Locator stubbedJournalRows = consolePage.locator("#journal-list tr.journal-row");
        assertThat(stubbedJournalRows.count()).isEqualTo(7);
        for (int i = 0; i < 7; i++) {
            Locator row = stubbedJournalRows.nth(i);
            assertThat(row.getAttribute("data-verdict")).isEqualTo("stub");
            assertThat(row.locator("td.pill-stub").textContent()).contains("STUB");
        }

        Locator stubbedCasualRow = consolePage
                .locator("#journal-list tr.journal-row")
                .filter(new Locator.FilterOptions().setHasText("/__casual/services/billing/charge"))
                .first();
        assertThat(stubbedCasualRow.isVisible()).isTrue();
        assertThat(stubbedCasualRow.locator(".http-badge.badge-CASUAL").textContent())
                .isEqualTo("CASUAL");
        assertThat(stubbedCasualRow.locator("td").nth(4).textContent()).isEqualTo("OK");
        assertThat(stubbedCasualRow.locator("td.pill-stub").textContent()).contains("STUB");

        stubbedCasualRow.click();
        captureScreenshot(consolePage, "07-console-journal-stubbed-unified.png");
    }

    private void captureScreenshot(Page page, String filename) {
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(screenshotDir.resolve(filename))
                .setFullPage(false));
    }
}
