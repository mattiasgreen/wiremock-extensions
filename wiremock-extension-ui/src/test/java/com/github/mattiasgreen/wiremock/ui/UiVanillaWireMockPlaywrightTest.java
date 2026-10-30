package com.github.mattiasgreen.wiremock.ui;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
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
 * Verifies that WireMock Console Web UI functions reliably against a completely vanilla
 * WireMock Standalone instance (zero custom extensions installed), with graceful degradation
 * and informative tooltips for unavailable extension features.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UiVanillaWireMockPlaywrightTest {

    private WireMockServer vanillaWireMock;
    private WireMockConsoleProxy proxy;
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;

    private final List<String> pageErrors = Collections.synchronizedList(new ArrayList<>());
    private final List<String> consoleErrors = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    void setUpAll() {
        // 1. Start completely vanilla WireMock (zero extensions)
        vanillaWireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        vanillaWireMock.start();

        vanillaWireMock.stubFor(
                get(urlEqualTo("/api/hello")).willReturn(okJson("{\"message\": \"Hello from Vanilla WireMock\"}")));

        // 2. Start WireMockConsoleProxy pointing to the vanilla instance
        proxy = new WireMockConsoleProxy(vanillaWireMock.baseUrl(), 0);

        // 3. Reuse shared headless browser
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
        if (proxy != null) {
            proxy.stop();
        }
        if (vanillaWireMock != null && vanillaWireMock.isRunning()) {
            vanillaWireMock.stop();
        }
    }

    @Test
    @DisplayName(
            "Vanilla WireMock: Core features work (Stubs, Journal, Tester) and missing extensions gracefully degrade with tooltips")
    void shouldGracefullyDegradeOnVanillaWireMock() throws Exception {
        // Navigate to UI through proxy
        page.navigate(proxy.getLocalUiUrl());
        page.waitForSelector("#stub-list .stub-card");

        // 1. Verify Stubs Catalog renders active stub from /__admin/mappings
        Locator stubCards = page.locator("#stub-list .stub-card");
        assertThat(stubCards.count()).isGreaterThanOrEqualTo(1);
        assertThat(stubCards.first().textContent()).contains("/api/hello");

        // Click the stub card to open detail pane
        stubCards.first().click();
        page.waitForSelector("#stub-detail-view:not(.hidden)");

        // 2. Verify Lifecycle controls degrade gracefully with tooltips
        Locator btnToggle = page.locator("#btn-toggle-stub");
        assertThat(btnToggle.isDisabled()).isTrue();
        assertThat(btnToggle.getAttribute("class")).contains("cap-disabled");
        assertThat(btnToggle.getAttribute("title")).contains("requires wiremock-extension-ui lifecycle endpoint");

        Locator filterDisabled = page.locator("#btn-filter-disabled");
        assertThat(filterDisabled.isDisabled()).isTrue();
        assertThat(filterDisabled.getAttribute("class")).contains("cap-disabled");
        assertThat(filterDisabled.getAttribute("title")).contains("requires wiremock-extension-ui lifecycle endpoint");

        // 3. Verify Stateful controls degrade gracefully
        Locator filterDynamic = page.locator("#btn-filter-dynamic");
        assertThat(filterDynamic.isDisabled()).isTrue();
        assertThat(filterDynamic.getAttribute("class")).contains("cap-disabled");
        assertThat(filterDynamic.getAttribute("title")).contains("requires wiremock-extension-stateful");

        // 4. Verify Target Host capability matrix reports 1/5 active (Core only)
        page.locator("#btn-target-host").click();
        page.waitForSelector("#target-host-modal:not(.hidden)");
        page.waitForSelector(".capability-row");

        Locator capRows = page.locator(".capability-row");
        assertThat(capRows.count()).isGreaterThanOrEqualTo(5);

        Locator coreBadge = page.locator("[data-testid='cap-badge-core']");
        assertThat(coreBadge.textContent()).isEqualTo("Active");

        Locator lifecycleBadge = page.locator("[data-testid='cap-badge-lifecycle']");
        assertThat(lifecycleBadge.textContent()).isEqualTo("Not Installed");

        Locator statefulBadge = page.locator("[data-testid='cap-badge-stateful']");
        assertThat(statefulBadge.textContent()).isEqualTo("Not Installed");

        Locator netemBadge = page.locator("[data-testid='cap-badge-netem']");
        assertThat(netemBadge.textContent()).isEqualTo("Not Installed");

        page.locator("#btn-close-target-host").click();
        assertThat(page.locator("#target-host-modal").isHidden()).isTrue();

        // 5. Verify HTTP Tester executes against vanilla WireMock
        page.locator("#nav-tab-tester").click();
        page.waitForSelector("#tab-tester.active");

        page.locator("#tester-url").fill("/api/hello");
        page.locator("#btn-tester-send").click();
        page.waitForSelector("#tester-response-status:has-text('200')");
        assertThat(page.locator("#tester-response-body").textContent()).contains("Hello from Vanilla WireMock");

        // 6. Verify Request Journal works seamlessly via fallback to /__admin/requests
        page.locator("#nav-tab-journal").click();
        page.waitForSelector("#tab-journal.active");
        page.locator("#btn-refresh-journal").click();
        page.waitForSelector("#journal-list tr.journal-row");

        Locator journalRows = page.locator("#journal-list tr.journal-row");
        assertThat(journalRows.count()).isGreaterThanOrEqualTo(1);
        assertThat(journalRows.first().textContent()).contains("/api/hello");

        // Expand journal row and verify snapshot button is disabled with tooltip
        journalRows.first().click();
        page.waitForSelector(".journal-detail-row");

        Locator snapshotBtn = page.locator(".btn-snapshot-req");
        assertThat(snapshotBtn.isDisabled()).isTrue();
        assertThat(snapshotBtn.getAttribute("class")).contains("cap-disabled");
        assertThat(snapshotBtn.getAttribute("title")).contains("requires wiremock-extension-ui lifecycle endpoint");

        // 7. Verify zero unhandled JavaScript errors occurred throughout the entire flow
        assertThat(pageErrors).isEmpty();
    }
}
