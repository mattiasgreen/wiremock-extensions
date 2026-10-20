package com.github.mattiasgreen.wiremock.ui;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.netem.admin.TcpNetEmAdminEndpoint;
import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmProxy;
import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmRegistry;
import com.github.mattiasgreen.wiremock.netem.model.NetEmProxyConfig;
import com.github.mattiasgreen.wiremock.netem.model.NetEmStreamDirection;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicConfig;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicType;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private Path webOutputDir;
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

        Path root = Paths.get("").toAbsolutePath();
        Path baseReports = root.endsWith("wiremock-extension-ui")
                ? root.resolve("build/reports/screenshots")
                : root.resolve("wiremock-extension-ui/build/reports/screenshots");
        webOutputDir = baseReports.resolve("web");
        Files.createDirectories(webOutputDir);

        netEmProxy = TcpNetEmRegistry.getInstance()
                .register(new NetEmProxyConfig("wiremock-netem", 0, "127.0.0.1", wireMockServer.port(), true));

        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    void stopAll() throws Exception {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
        TcpNetEmRegistry.getInstance().remove("wiremock-netem");
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void setUp() {
        pageErrors.clear();
        context = browser.newContext();
        page = context.newPage();
        page.onDialog(dialog -> dialog.accept());
        page.onPageError(error -> {
            System.err.println("BROWSER UNCAUGHT ERROR: " + error);
            pageErrors.add(error);
        });
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
        netEmProxy.clearToxics();
        netEmProxy.getJournal().clear();
    }

    @Test
    @DisplayName("JavaScript Unit Testing: Verify pure conversion functions in browser context")
    void testNetEmJsUnitFunctions() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#netem");
        page.waitForSelector("#tab-netem.active");

        // 1. asciiToHex unit tests
        String hex = (String)
                page.evaluate(
                        "async () => { const { asciiToHex } = await import('./modules/netem.js'); return asciiToHex('order'); }");
        assertThat(hex).isEqualTo("6f 72 64 65 72");

        // 2. hexToAscii unit tests
        String ascii = (String)
                page.evaluate(
                        "async () => { const { hexToAscii } = await import('./modules/netem.js'); return hexToAscii('6f 72 64 65 72'); }");
        assertThat(ascii).isEqualTo("order");

        // 3. Round-trip conversion test
        String roundtrip = (String)
                page.evaluate(
                        "async () => { const { asciiToHex, hexToAscii } = await import('./modules/netem.js'); return hexToAscii(asciiToHex('chaos-token-99')); }");
        assertThat(roundtrip).isEqualTo("chaos-token-99");

        // 4. isValidHex unit tests
        Boolean validHex = (Boolean)
                page.evaluate(
                        "async () => { const { isValidHex } = await import('./modules/netem.js'); return isValidHex('ca500001'); }");
        Boolean invalidHex = (Boolean)
                page.evaluate(
                        "async () => { const { isValidHex } = await import('./modules/netem.js'); return isValidHex('ca500'); }");
        assertThat(validHex).isTrue();
        assertThat(invalidHex).isFalse();

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Smoke Test: Bidirectional ASCII <-> Hex auto-conversion in Toxic Modal")
    void testNetEmAsciiAndHexAutoConversion() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#netem");
        page.waitForSelector("#tab-netem.active");

        // Open Add Toxic modal
        page.locator("#btn-netem-add-toxic").click();
        page.waitForSelector("#netem-add-toxic-modal:not(.hidden)");

        // Type ASCII pattern
        page.locator("#netem-toxic-pattern").fill("order-service");

        // Toggle to HEX
        page.locator("#btn-pattern-hex").click();
        page.waitForFunction("() => document.getElementById('btn-pattern-hex').classList.contains('active')");

        String hexValue = page.locator("#netem-toxic-pattern").inputValue();
        assertThat(hexValue).isEqualTo("6f 72 64 65 72 2d 73 65 72 76 69 63 65");
        assertThat(page.locator("#lbl-toxic-pattern").textContent()).contains("Hex / Raw Bytes");

        // Toggle back to ASCII
        page.locator("#btn-pattern-ascii").click();
        page.waitForFunction("() => document.getElementById('btn-pattern-ascii').classList.contains('active')");

        String asciiValue = page.locator("#netem-toxic-pattern").inputValue();
        assertThat(asciiValue).isEqualTo("order-service");
        assertThat(page.locator("#lbl-toxic-pattern").textContent()).contains("ASCII");

        page.locator("#btn-netem-cancel-toxic").click();
        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Verify Preset injection and Reset All in UI")
    void testPresetsAndResetAll() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#netem");
        page.waitForSelector("#tab-netem.active");
        page.waitForFunction(
                "() => document.getElementById('netem-ingress-info') && document.getElementById('netem-ingress-info').textContent.includes('wiremock-netem')");

        // 1. Initial State: No toxics active
        Locator toxicsList = page.locator("#netem-toxics-list");
        assertThat(toxicsList.textContent()).contains("No network impairments active");

        Locator statActiveToxics = page.locator("#netem-stat-active-toxics");
        assertThat(Integer.parseInt(statActiveToxics.textContent().trim())).isEqualTo(0);

        // 2. Click 3G Mobile Preset
        page.locator("#btn-preset-3g").click();
        page.waitForSelector(".netem-toxic-card");

        Locator cards = page.locator(".netem-toxic-card");
        assertThat(cards.count()).isGreaterThanOrEqualTo(2);
        assertThat(Integer.parseInt(statActiveToxics.textContent().trim())).isGreaterThanOrEqualTo(2);

        // 3. Click Reset All Toxics
        page.locator("#btn-netem-reset-all").click();
        page.waitForSelector("#netem-toxics-list .empty-state");
        assertThat(page.locator("#netem-toxics-list").textContent()).contains("No network impairments active");
        assertThat(Integer.parseInt(statActiveToxics.textContent().trim())).isEqualTo(0);

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Verify Custom Toxic creation with Pattern & Client IP and deletion")
    void testAddCustomToxicWithPatternAndClientIp() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#netem");
        page.waitForSelector("#tab-netem.active");
        page.waitForFunction(
                "() => document.getElementById('netem-ingress-info') && document.getElementById('netem-ingress-info').textContent.includes('wiremock-netem')");

        // Open Add Toxic modal
        page.locator("#btn-netem-add-toxic").click();
        page.waitForSelector("#netem-add-toxic-modal:not(.hidden)");

        // Fill modal fields
        page.locator("#netem-toxic-name").fill("custom-pattern-test");
        page.locator("#netem-toxic-type").selectOption("latency");
        page.locator("#netem-toxic-client-ip").fill("127.0.0.1");
        page.locator("#netem-toxic-pattern").fill("order-scope-42");
        page.locator("#attr-latency").fill("350");
        page.locator("#attr-jitter").fill("25");

        page.screenshot(new Page.ScreenshotOptions().setPath(webOutputDir.resolve("07-netem-add-toxic-modal.png")));

        // Submit toxic
        page.locator("#btn-netem-submit-toxic").click();
        page.waitForFunction(
                "() => document.getElementById('netem-add-toxic-modal') && document.getElementById('netem-add-toxic-modal').classList.contains('hidden')");

        // Card should be present with Scoped and IP badges
        page.waitForSelector(".netem-toxic-card[data-toxic-name='custom-pattern-test']");
        Locator card = page.locator(".netem-toxic-card[data-toxic-name='custom-pattern-test']");
        assertThat(card.isVisible()).isTrue();
        assertThat(card.textContent()).contains("LATENCY");
        assertThat(card.textContent()).contains("Scoped: order-scope-42");
        assertThat(card.textContent()).contains("IP: 127.0.0.1");

        page.screenshot(new Page.ScreenshotOptions().setPath(webOutputDir.resolve("06-netem-console.png")));

        // Delete this toxic
        card.locator(".btn-delete-toxic").click();
        page.waitForSelector("#netem-toxics-list .empty-state");
        assertThat(page.locator("#netem-toxics-list").textContent()).contains("No network impairments active");

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Verify NetEm Journal records traffic and filtering by scope works")
    void testNetEmJournalRecordingAndFiltering() throws Exception {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#netem");
        page.waitForSelector("#tab-netem.active");
        page.waitForFunction(
                "() => document.getElementById('netem-ingress-info') && document.getElementById('netem-ingress-info').textContent.includes('wiremock-netem')");

        // Add toxic with pattern
        netEmProxy.addToxic(NetEmToxicConfig.builder("playwright-latency", NetEmToxicType.LATENCY).stream(
                        NetEmStreamDirection.DOWNSTREAM)
                .pattern("playwright-test-123")
                .attribute("latency", 20L)
                .build());

        // Make an HTTP request through NetEm Proxy port with target pattern
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + netEmProxy.getPort() + "/api/test-netem"))
                .header("X-Scope", "playwright-test-123")
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

        // Filter by scope
        page.locator("#netem-journal-filter-corr").fill("playwright-test-123");
        page.waitForTimeout(300);
        assertThat(page.locator("#netem-journal-tbody").textContent()).contains("playwright-test-123");

        page.locator("#netem-journal-filter-corr").fill("non-existent-scope");
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
