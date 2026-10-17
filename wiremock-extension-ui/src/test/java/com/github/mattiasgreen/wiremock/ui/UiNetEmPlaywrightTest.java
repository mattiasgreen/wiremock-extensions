package com.github.mattiasgreen.wiremock.ui;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.netem.admin.TcpNetEmAdminEndpoint;
import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmProxy;
import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmRegistry;
import com.github.mattiasgreen.wiremock.netem.model.NetEmProxyConfig;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class UiNetEmPlaywrightTest {

    private WireMockServer wireMockServer;
    private TcpNetEmProxy netEmProxy;
    private BrowserContext context;
    private Page page;
    private final List<String> pageErrors = Collections.synchronizedList(new ArrayList<>());
    private final HttpClient httpClient =
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @BeforeAll
    void startAll() throws Exception {
        TcpNetEmRegistry.getInstance().resetAll();

        wireMockServer = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .extensions(new UiAdminApiEndpoint(), new TcpNetEmAdminEndpoint()));
        wireMockServer.start();

        wireMockServer.stubFor(
                get(urlEqualTo("/api/test-netem")).willReturn(okJson("{\"status\":\"ok\",\"engine\":\"wiremock\"}")));

        netEmProxy = TcpNetEmRegistry.getInstance()
                .register(new NetEmProxyConfig("wiremock-netem", 0, "127.0.0.1", wireMockServer.port(), true));

        Browser browser = AbstractUiPlaywrightTest.getSharedBrowser();
        context = browser.newContext();
        page = context.newPage();
        page.onDialog(dialog -> dialog.accept());
        page.onPageError(error -> {
            System.err.println("BROWSER UNCAUGHT ERROR: " + error);
            pageErrors.add(error);
        });
    }

    @AfterAll
    void stopAll() throws Exception {
        if (context != null) {
            context.close();
        }
        TcpNetEmRegistry.getInstance().remove("wiremock-netem");
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void setUp() {
        pageErrors.clear();
    }

    @AfterEach
    void tearDown() {
        netEmProxy.clearToxics();
        netEmProxy.getJournal().clear();
    }

    @Test
    @DisplayName("Verify NetEm Tab navigation, ingress badge, and initial stats")
    void testNetEmTabNavigationAndIngressInfo() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/");

        // Click NetEm Navigation Tab
        Locator navNetEm = page.locator("#nav-tab-netem");
        assertThat(navNetEm.isVisible()).isTrue();
        navNetEm.click();

        page.waitForSelector("#tab-netem.active");
        page.waitForFunction(
                "() => document.getElementById('netem-ingress-info') && document.getElementById('netem-ingress-info').textContent.includes('wiremock-netem')");
        Locator ingressInfo = page.locator("#netem-ingress-info");
        assertThat(ingressInfo.textContent()).contains("wiremock-netem");
        assertThat(ingressInfo.textContent()).contains(String.valueOf(wireMockServer.port()));

        // Empty state is rendered when no toxics are active
        Locator toxicsList = page.locator("#netem-toxics-list");
        assertThat(toxicsList.textContent()).contains("No network impairments active");

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Verify Preset application, toxic listing, and reset all")
    void testPresetAndToxicManagement() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#netem");
        page.waitForSelector("#tab-netem.active");
        page.waitForFunction(
                "() => document.getElementById('netem-ingress-info') && document.getElementById('netem-ingress-info').textContent.includes('wiremock-netem')");

        // Click 3G preset button
        Locator preset3g = page.locator("#btn-preset-3g");
        preset3g.click();

        // Toxic cards should now appear
        page.waitForSelector(".netem-toxic-card");
        Locator toxicCards = page.locator(".netem-toxic-card");
        assertThat(toxicCards.count()).isGreaterThanOrEqualTo(1);

        // Stat counter for active toxics should match
        Locator statActiveToxics = page.locator("#netem-stat-active-toxics");
        assertThat(Integer.parseInt(statActiveToxics.textContent().trim())).isGreaterThanOrEqualTo(1);

        // Click Reset All button
        Locator resetBtn = page.locator("#btn-netem-reset-all");
        resetBtn.click();

        // After reset, list displays empty state
        page.waitForSelector("#netem-toxics-list .empty-state");
        assertThat(page.locator("#netem-toxics-list").textContent()).contains("No network impairments active");
        assertThat(Integer.parseInt(statActiveToxics.textContent().trim())).isEqualTo(0);

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Verify Custom Toxic creation via modal and deletion")
    void testAddCustomToxicModalAndDeletion() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#netem");
        page.waitForSelector("#tab-netem.active");
        page.waitForFunction(
                "() => document.getElementById('netem-ingress-info') && document.getElementById('netem-ingress-info').textContent.includes('wiremock-netem')");

        // Open Add Toxic modal
        page.locator("#btn-netem-add-toxic").click();
        page.waitForSelector("#netem-add-toxic-modal:not(.hidden)");

        // Fill modal fields
        page.locator("#netem-toxic-name").fill("custom-latency-test");
        page.locator("#netem-toxic-type").selectOption("latency");
        page.locator("#attr-latency").fill("350");
        page.locator("#attr-jitter").fill("25");

        // Submit toxic
        page.locator("#btn-netem-submit-toxic").click();
        page.waitForFunction(
                "() => document.getElementById('netem-add-toxic-modal') && document.getElementById('netem-add-toxic-modal').classList.contains('hidden')");

        // Card should be present
        page.waitForSelector(".netem-toxic-card[data-toxic-name='custom-latency-test']");
        Locator card = page.locator(".netem-toxic-card[data-toxic-name='custom-latency-test']");
        assertThat(card.isVisible()).isTrue();
        assertThat(card.textContent()).contains("LATENCY");
        assertThat(card.textContent()).contains("350");

        // Delete this toxic
        card.locator(".btn-delete-toxic").click();
        page.waitForSelector("#netem-toxics-list .empty-state");
        assertThat(page.locator("#netem-toxics-list").textContent()).contains("No network impairments active");

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Verify NetEm Journal records traffic and filtering works")
    void testNetEmJournalRecordingAndFiltering() throws Exception {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#netem");
        page.waitForSelector("#tab-netem.active");
        page.waitForFunction(
                "() => document.getElementById('netem-ingress-info') && document.getElementById('netem-ingress-info').textContent.includes('wiremock-netem')");

        // Make an HTTP request through NetEm Proxy port with correlation ID
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + netEmProxy.getPort() + "/api/test-netem"))
                .header("X-Correlation-Id", "playwright-test-123")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("wiremock");
        Thread.sleep(200);

        // Refresh journal
        page.locator("#btn-netem-refresh").click();
        page.waitForSelector("#netem-journal-tbody tr:not(.empty-state-row)");

        Locator journalRows = page.locator("#netem-journal-tbody tr");
        assertThat(journalRows.count()).isGreaterThanOrEqualTo(1);
        assertThat(page.locator("#netem-journal-tbody").textContent()).contains("playwright-test-123");
        assertThat(page.locator("#netem-journal-tbody").textContent()).contains("Normal");

        // Filter by correlation id
        page.locator("#netem-journal-filter-corr").fill("playwright-test-123");
        page.waitForTimeout(300);
        assertThat(page.locator("#netem-journal-tbody").textContent()).contains("playwright-test-123");

        page.locator("#netem-journal-filter-corr").fill("non-existent-correlation-id");
        page.waitForTimeout(300);
        assertThat(page.locator("#netem-journal-tbody").textContent())
                .contains("No matching network events recorded in NetEm journal.");

        // Clear filter
        page.locator("#netem-journal-filter-corr").fill("");
        page.waitForTimeout(300);

        // Click Clear Journal button
        page.locator("#btn-netem-clear-journal").click();
        page.waitForSelector("#netem-journal-tbody .empty-state-cell");
        assertThat(page.locator("#netem-journal-tbody").textContent())
                .contains("No matching network events recorded in NetEm journal.");

        assertThat(pageErrors).isEmpty();
    }
}
