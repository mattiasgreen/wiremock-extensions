package com.github.mattiasgreen.wiremock.ui;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.casual.CasualCallResult;
import com.github.mattiasgreen.wiremock.casual.CasualClient;
import com.github.mattiasgreen.wiremock.casual.CasualRegistry;
import com.github.mattiasgreen.wiremock.casual.CasualWireMockGateway;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.microsoft.playwright.Locator;
import java.util.Map;
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
                .wireMockServer(wireMockServer)
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

        // Verify response headers
        Locator responseHeaders = page.locator("#casual-response-headers");
        assertThat(responseHeaders.isVisible()).isTrue();
        assertThat(responseHeaders.textContent()).contains("Content-Type: application/json");
        assertThat(responseHeaders.textContent()).contains("X-Casual-Error-State: OK");
        assertThat(page.locator("#casual-response-header-count").textContent()).matches("\\d+");

        // Verify Sent Request tab
        page.locator("#btn-casual-tab-request").click();
        assertThat(page.locator("#casual-view-request").isVisible()).isTrue();
        assertThat(page.locator("#casual-view-response").isVisible()).isFalse();
        assertThat(page.locator("#casual-sent-headers").textContent()).contains("X-Casual-Service: echoService");
        assertThat(page.locator("#casual-sent-body").textContent())
                .contains("\"testMessage\": \"Hello from Playwright\"");

        // Verify Both tab
        page.locator("#btn-casual-tab-both").click();
        assertThat(page.locator("#casual-view-request").isVisible()).isTrue();
        assertThat(page.locator("#casual-view-response").isVisible()).isTrue();
        assertThat(page.locator("#casual-inspector-content").getAttribute("class"))
                .contains("both-mode");

        // Switch back to Response tab
        page.locator("#btn-casual-tab-response").click();
        assertThat(page.locator("#casual-view-response").isVisible()).isTrue();
        assertThat(page.locator("#casual-view-request").isVisible()).isFalse();
        assertThat(page.locator("#casual-inspector-content").getAttribute("class"))
                .doesNotContain("both-mode");

        // Test Preset badges
        page.locator("#btn-casual-preset-corr").click();
        assertThat(page.locator("#casual-request-headers").inputValue()).contains("X-Correlation-Id:");
        page.locator("#btn-casual-preset-tx").click();
        assertThat(page.locator("#casual-request-headers").inputValue())
                .contains("X-Casual-Transaction-State: TX_ACTIVE");

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

    @Test
    @DisplayName(
            "Should replay Casual request from Journal into Casual Tester, execute tpcall upstream, and verify resulting journal entry")
    void testCasualJournalReplayFlow() throws Exception {
        WireMockServer upstreamServer = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamServer.start();

        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamServer)
                .build();
        upstreamGateway.start();

        try {
            upstreamServer.stubFor(post(urlEqualTo("/__casual/services/upstreamEcho"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"upstream\": true, \"received\": \"proxyOK\"}")));

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/upstreamEcho"))
                    .withMetadata(Map.of(
                            "mode", "proxy",
                            "protocol", "casual",
                            "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            // 1. Execute initial proxy call over TCP to gateway
            try (CasualClient client = new CasualClient("127.0.0.1", casualGateway.getPort())) {
                CasualCallResult initialResult = client.call("upstreamEcho", "{\"clientCall\": 1}");
                assertThat(initialResult.isSuccess()).isTrue();
                assertThat(initialResult.payload()).contains("\"upstream\": true");
            }

            // 2. Open Journal in UI and verify recorded request and response
            page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#journal");
            page.waitForSelector("#tab-journal.active");
            page.waitForSelector(".journal-row");

            Locator journalRows = page.locator(".journal-row");
            assertThat(journalRows.count()).isGreaterThanOrEqualTo(1);
            Locator firstRow = journalRows.first();
            assertThat(firstRow.textContent()).contains("CASUAL");
            assertThat(firstRow.textContent()).contains("upstreamEcho");

            // Expand row
            firstRow.click();
            page.waitForSelector(".journal-detail-row");
            Locator detailContent = page.locator(".journal-detail-content");
            assertThat(detailContent.textContent()).contains("Casual Request: tpcall(\"upstreamEcho\")");
            assertThat(detailContent.textContent()).contains("\"clientCall\": 1");
            assertThat(detailContent.textContent()).contains("\"upstream\": true");

            // 3. Click 'Replay in Casual Tester' button
            Locator replayBtn = page.locator("[data-testid='btn-journal-to-tester']");
            assertThat(replayBtn.isVisible()).isTrue();
            assertThat(replayBtn.textContent()).contains("Replay in Casual Tester");
            replayBtn.click();

            // 4. Verify tab switched to Casual Tester with pre-filled service and payload
            page.waitForSelector("#tab-casual.active");
            assertThat(page.locator("#casual-service-name").inputValue()).isEqualTo("upstreamEcho");
            assertThat(page.locator("#casual-request-payload").inputValue()).contains("\"clientCall\": 1");
            assertThat(page.locator("#casual-request-headers").inputValue()).contains("X-Casual-Service: upstreamEcho");

            // 5. Send replayed request from Casual Tester
            page.locator("#btn-casual-send").click();

            // 6. Verify response in Casual Tester is received from upstream
            page.waitForSelector("#casual-response-status:has-text('OK')");
            assertThat(page.locator("#casual-response-status").textContent()).contains("OK (0)");
            Locator responseBody = page.locator("#casual-response-body");
            assertThat(responseBody.textContent()).contains("\"upstream\": true");
            assertThat(responseBody.textContent()).contains("\"received\": \"proxyOK\"");
            Locator responseHeaders = page.locator("#casual-response-headers");
            assertThat(responseHeaders.isVisible()).isTrue();
            assertThat(responseHeaders.textContent()).contains("X-Casual-Error-State: OK");

            // 7. Switch back to Journal and verify new entry recorded
            page.locator("#nav-tab-journal").click();
            page.waitForSelector("#tab-journal.active");
            page.locator("#btn-refresh-journal").click();
            page.waitForSelector(".journal-row:nth-child(2)");
            Locator updatedRows = page.locator(".journal-row");
            assertThat(updatedRows.count()).isGreaterThanOrEqualTo(2);
        } finally {
            upstreamGateway.close();
            upstreamServer.stop();
        }
    }
}
