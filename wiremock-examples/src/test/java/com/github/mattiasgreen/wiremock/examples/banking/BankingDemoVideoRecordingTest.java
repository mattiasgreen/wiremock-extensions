package com.github.mattiasgreen.wiremock.examples.banking;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Video;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Headless Playwright WebM video tour recorder for the 3-node Banking Progressive Simulation.
 * <p>
 * Demonstrates the full lifecycle across Customer Portal and WireMock Console:
 * <ul>
 *   <li>Frame 0: WireMock Console in LIVE PROXY mode with HTTP + Casual XATMI routes</li>
 *   <li>Run 1: Customer Portal submits application, approves offer, and completes Casual 2PC billing</li>
 *   <li>WireMock Console: Inspecting the 7 calls in the Unified Journal (CASUAL + OK status)</li>
 *   <li>Snapshotting recordings and flipping to STUBS MODE</li>
 *   <li>Simulating upstream outage (Server B shut down)</li>
 *   <li>Run 2: Customer Portal completes application &amp; 2PC billing 100% offline from stubs</li>
 *   <li>WireMock Console: Unified Journal verifies all 7 calls served locally with STUB resolution</li>
 * </ul>
 */
@Tag("recording")
class BankingDemoVideoRecordingTest {

    private static final int VIEWPORT_WIDTH = 1280;
    private static final int VIEWPORT_HEIGHT = 800;
    private static final int DEMO_STEP_PAUSE_MS = 1400;

    private BankingSimulationHarness harness;
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Path tempVideoDir;
    private Path finalVideoPath;

    @BeforeEach
    void setUp() throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        Path baseOutputDir = root.endsWith("wiremock-examples")
                ? root.resolve("build/videos/banking")
                : root.resolve("wiremock-examples/build/videos/banking");
        tempVideoDir = baseOutputDir.resolve("raw");
        Files.createDirectories(tempVideoDir);
        finalVideoPath = baseOutputDir.resolve("banking-progressive-simulation-tour.webm");

        harness = new BankingSimulationHarness();

        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
                .setRecordVideoDir(tempVideoDir)
                .setRecordVideoSize(VIEWPORT_WIDTH, VIEWPORT_HEIGHT));
    }

    @AfterEach
    void tearDown() {
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
    @DisplayName("Record Headless WebM Video Tour: Banking Progressive Simulation (Live Proxy -> Offline Stubs)")
    void recordBankingProgressiveSimulationVideoTour() throws IOException {
        Page page = context.newPage();

        // SCENE 1: Frame 0 inside WireMock Console UI (Live Proxy Mode)
        page.navigate(harness.getWireMockServer().baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector("#stub-list .stub-card");
        page.waitForSelector("#project-lifecycle-bar:not(.hidden)");
        assertThat(page.locator("#project-mode-badge").textContent()).contains("LIVE PROXY");

        // Highlight the Casual proxy route stub card
        Locator casualProxyCard = page.locator(".stub-card")
                .filter(new Locator.FilterOptions().setHasText("/__casual/services/.*"))
                .first();
        assertThat(casualProxyCard.isVisible()).isTrue();
        casualProxyCard.click();
        page.waitForTimeout(DEMO_STEP_PAUSE_MS);

        // SCENE 2: Switch to Customer Portal -> Submit Loan Application
        page.navigate(harness.getProductPortal().getPortalUrl());
        page.waitForSelector("#btn-submit-application");
        page.waitForTimeout(DEMO_STEP_PAUSE_MS);

        page.locator("#btn-submit-application").click();
        page.waitForSelector("#offer-panel:not(.hidden)");
        assertThat(page.locator("#offer-case-id").textContent()).isEqualTo("CASE-1001");
        assertThat(page.locator("#offer-kyc-status").textContent()).isEqualTo("VERIFIED");
        assertThat(page.locator("#offer-eligibility").textContent()).isEqualTo("ELIGIBLE");
        assertThat(page.locator("#offer-case-status").textContent()).isEqualTo("OFFER_READY");
        page.waitForTimeout(DEMO_STEP_PAUSE_MS);

        // SCENE 3: Approve Purchase Order & Trigger Casual 2PC Billing
        page.locator("#btn-approve-purchase").click();
        page.waitForSelector("#purchase-confirmation:not(.hidden)");
        assertThat(page.locator("#conf-invoice-id").textContent()).isEqualTo("INV-9001");
        assertThat(page.locator("#conf-xa-status").textContent()).isEqualTo("XA_OK");
        assertThat(page.locator("#conf-case-status").textContent()).isEqualTo("PURCHASED");
        assertThat(page.locator("#conf-sales-id").textContent()).isEqualTo("SALE-5001");
        page.waitForTimeout(DEMO_STEP_PAUSE_MS + 400);

        // SCENE 4: Switch to WireMock Console UI -> Inspect Unified Request Journal (7 Proxied Calls)
        page.navigate(harness.getWireMockServer().baseUrl() + "/__admin/ui/#journal");
        page.waitForSelector("#tab-journal.active");
        page.locator("#btn-refresh-journal").click();
        page.waitForFunction("() => document.querySelectorAll('#journal-list tr.journal-row').length === 7");
        page.waitForTimeout(DEMO_STEP_PAUSE_MS);

        // Expand Casual journal row with protocol-native CASUAL badge and OK status
        Locator casualJournalRow = page.locator("#journal-list tr.journal-row")
                .filter(new Locator.FilterOptions().setHasText("/__casual/services/billing/charge"))
                .first();
        assertThat(casualJournalRow.isVisible()).isTrue();
        assertThat(casualJournalRow.getAttribute("data-protocol")).isEqualTo("casual");
        assertThat(casualJournalRow.locator(".http-badge.badge-CASUAL").textContent())
                .isEqualTo("CASUAL");
        assertThat(casualJournalRow.locator("td").nth(4).textContent()).isEqualTo("OK");
        casualJournalRow.click();
        page.waitForTimeout(DEMO_STEP_PAUSE_MS + 600);

        // SCENE 5: Switch to Stubs View -> Snapshot Recordings & Flip to STUBS MODE
        page.locator("#nav-tab-stubs").click();
        page.waitForSelector("#tab-stub-detail.active");
        page.waitForSelector("#project-lifecycle-bar:not(.hidden)");
        page.waitForTimeout(800);

        page.onceDialog(Dialog::accept);
        page.locator("#btn-project-snapshot").click();
        page.waitForFunction("() => document.getElementById('btn-filter-disabled')?.textContent.includes('7')");
        page.waitForTimeout(DEMO_STEP_PAUSE_MS);

        page.locator("#btn-project-mode-toggle").click();
        page.waitForSelector("#project-mode-badge:has-text('STUBS MODE')");
        page.waitForSelector(".stub-card:has-text('RECORDED')");

        // Inspect recorded Casual stub card
        Locator recordedCasualStub = page.locator(".stub-card")
                .filter(new Locator.FilterOptions().setHasText("[RECORDED CASUAL OK]"))
                .first();
        assertThat(recordedCasualStub.isVisible()).isTrue();
        recordedCasualStub.click();
        page.waitForTimeout(DEMO_STEP_PAUSE_MS);

        // Reset journal before offline run
        page.locator("#nav-tab-journal").click();
        page.waitForSelector("#tab-journal.active");
        page.onceDialog(Dialog::accept);
        page.locator("#btn-reset-journal").click();
        page.waitForFunction("() => document.querySelectorAll('#journal-list tr.journal-row').length === 0");
        page.waitForTimeout(800);

        // SCENE 6: Simulate Upstream Outage -> Stop Backend Server B
        harness.stopUpstreamBackend();

        // SCENE 7: Customer Portal -> Replay 10-step Customer Journey 100% Offline
        page.navigate(harness.getProductPortal().getPortalUrl());
        page.waitForSelector("#btn-submit-application");
        page.waitForTimeout(DEMO_STEP_PAUSE_MS);

        page.locator("#btn-submit-application").click();
        page.waitForSelector("#offer-panel:not(.hidden)");
        assertThat(page.locator("#offer-case-id").textContent()).isEqualTo("CASE-1001");
        page.waitForTimeout(DEMO_STEP_PAUSE_MS);

        page.locator("#btn-approve-purchase").click();
        page.waitForSelector("#purchase-confirmation:not(.hidden)");
        assertThat(page.locator("#conf-invoice-id").textContent()).isEqualTo("INV-9001");
        assertThat(page.locator("#conf-xa-status").textContent()).isEqualTo("XA_OK");
        assertThat(page.locator("#conf-case-status").textContent()).isEqualTo("PURCHASED");
        page.waitForTimeout(DEMO_STEP_PAUSE_MS);

        // SCENE 8: WireMock Console -> Verify All 7 Calls Served Locally from Stubs
        page.navigate(harness.getWireMockServer().baseUrl() + "/__admin/ui/#journal");
        page.waitForSelector("#tab-journal.active");
        page.locator("#btn-refresh-journal").click();
        page.waitForFunction("() => document.querySelectorAll('#journal-list tr.journal-row').length === 7");

        Locator stubbedJournalRows = page.locator("#journal-list tr.journal-row");
        assertThat(stubbedJournalRows.count()).isEqualTo(7);
        for (int i = 0; i < 7; i++) {
            Locator row = stubbedJournalRows.nth(i);
            assertThat(row.getAttribute("data-verdict")).isEqualTo("stub");
            assertThat(row.locator("td.pill-stub").textContent()).contains("STUB");
        }

        Locator stubbedCasualRow = page.locator("#journal-list tr.journal-row")
                .filter(new Locator.FilterOptions().setHasText("/__casual/services/billing/charge"))
                .first();
        assertThat(stubbedCasualRow.isVisible()).isTrue();
        stubbedCasualRow.click();
        page.waitForTimeout(DEMO_STEP_PAUSE_MS + 1000);

        // Finalize Video Recording
        Video video = page.video();
        Path rawVideoPath = video != null ? video.path() : null;
        context.close();
        context = null;

        if (rawVideoPath != null && Files.exists(rawVideoPath)) {
            Files.move(rawVideoPath, finalVideoPath, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(tempVideoDir);
            assertThat(Files.exists(finalVideoPath)).isTrue();
            assertThat(Files.size(finalVideoPath)).isGreaterThan(10_000L);
        }
    }
}
