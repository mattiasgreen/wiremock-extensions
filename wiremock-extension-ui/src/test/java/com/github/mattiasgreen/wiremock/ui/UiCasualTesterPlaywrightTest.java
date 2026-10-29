package com.github.mattiasgreen.wiremock.ui;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.casual.CasualRegistry;
import com.github.mattiasgreen.wiremock.casual.CasualWireMockGateway;
import com.microsoft.playwright.Locator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class UiCasualTesterPlaywrightTest extends AbstractUiPlaywrightTest {

    private static CasualWireMockGateway casualGateway;

    @BeforeAll
    void startCasualGateway() throws Exception {
        casualGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockBaseUrl(wireMockServer.baseUrl())
                .build();
        casualGateway.start();
        CasualRegistry.getInstance().setGateway(casualGateway);
    }

    @AfterAll
    void stopCasualGateway() {
        if (casualGateway != null) {
            casualGateway.close();
        }
        CasualRegistry.getInstance().reset();
    }

    @Test
    @DisplayName("Should execute Casual service call (tpcall), inspect response, and verify history in UI")
    void testCasualTesterExecution() {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/echoService"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"echoed\": true, \"service\": \"echoService\"}")));

        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#casual");
        page.waitForSelector("#tab-casual.active");

        // Verify navigation tab
        Locator navTab = page.locator("#nav-tab-casual");
        assertThat(navTab.isVisible()).isTrue();
        assertThat(navTab.textContent()).contains("Casual Tester");

        // Verify target port prefilled
        page.locator("#casual-target-port").fill(String.valueOf(casualGateway.getPort()));
        page.locator("#casual-service-name").fill("echoService");
        page.locator("#casual-request-payload").fill("{\"testMessage\": \"Hello from Playwright\"}");

        // Test Format JSON
        page.locator("#btn-casual-format-json").click();
        assertThat(page.locator("#casual-request-payload").inputValue())
                .contains("  \"testMessage\": \"Hello from Playwright\"");

        // Execute tpcall
        page.locator("#btn-casual-send").click();

        page.waitForSelector("#casual-response-status:has-text('OK')");
        Locator statusEl = page.locator("#casual-response-status");
        assertThat(statusEl.textContent()).contains("OK (0)");

        Locator bodyEl = page.locator("#casual-response-body");
        assertThat(bodyEl.textContent()).contains("\"echoed\": true");
        assertThat(bodyEl.textContent()).contains("\"service\": \"echoService\"");

        Locator timeEl = page.locator("#casual-response-time");
        assertThat(timeEl.textContent()).contains("ms");

        // Verify history item recorded
        Locator historyItems = page.locator("#casual-history-list .history-item");
        assertThat(historyItems.count()).isGreaterThanOrEqualTo(1);
        Locator firstHistory = historyItems.first();
        assertThat(firstHistory.textContent()).contains("echoService");
        assertThat(firstHistory.textContent()).contains("OK");

        // Test unmatched service call
        page.locator("#casual-service-name").fill("unstubbedService");
        page.locator("#btn-casual-send").click();

        page.waitForSelector("#casual-response-status:has-text('TPENOENT')");
        assertThat(page.locator("#casual-response-status").textContent()).contains("TPENOENT (6)");

        // Verify history contains 2 items
        assertThat(page.locator("#casual-history-list .history-item").count()).isGreaterThanOrEqualTo(2);

        // Click first history item to restore echoService
        page.locator("#casual-history-list .history-item").nth(1).click();
        assertThat(page.locator("#casual-service-name").inputValue()).isEqualTo("echoService");

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Should execute binary Casual service call, display hex dump, and toggle views in UI")
    void testCasualTesterBinaryExecution() {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/binaryService"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/octet-stream")
                        .withHeader("X-Casual-Buffer-Type", "X_OCTET")
                        .withBody(new byte[] {0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x21})));

        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#casual");
        page.waitForSelector("#tab-casual.active");

        page.locator("#casual-target-port").fill(String.valueOf(casualGateway.getPort()));
        page.locator("#casual-service-name").fill("binaryService");
        page.locator("#casual-buffer-type").selectOption("X_OCTET");

        page.locator("#casual-payload-encoding").selectOption("hex");
        page.locator("#casual-request-payload").fill("01 02 03 04");

        page.locator("#btn-casual-send").click();

        page.waitForSelector("#casual-response-status:has-text('OK')");
        assertThat(page.locator("#casual-response-status").textContent()).contains("OK (0)");

        assertThat(page.locator("#casual-response-code").textContent()).contains("6 B");

        Locator downloadBtn = page.locator("#btn-casual-download-binary");
        assertThat(downloadBtn.isVisible()).isTrue();

        Locator bodyEl = page.locator("#casual-response-body");
        assertThat(bodyEl.textContent()).contains("48 65 6C 6C 6F 21");
        assertThat(bodyEl.textContent()).contains("|Hello!|");

        page.locator("#btn-casual-view-raw").click();
        assertThat(bodyEl.textContent()).contains("SGVsbG8h");

        page.locator("#btn-casual-view-hex").click();
        assertThat(bodyEl.textContent()).contains("48 65 6C 6C 6F 21");

        assertThat(pageErrors).isEmpty();
    }
}
