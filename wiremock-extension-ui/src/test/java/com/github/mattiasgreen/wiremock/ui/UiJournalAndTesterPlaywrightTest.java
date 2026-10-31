package com.github.mattiasgreen.wiremock.ui;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * E2E Playwright tests for HTTP Request Tester, presets/history, Request Journal filtering,
 * lazy progressive rendering, resolution pills, and deep-linking.
 */
public class UiJournalAndTesterPlaywrightTest extends AbstractUiPlaywrightTest {

    @Test
    @DisplayName("Scenario 3: HTTP Request Tester execution with dual view, permanent headers, and history")
    void testHttpRequestTesterExecution() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#tester");

        page.waitForSelector("#tab-tester.active");

        Locator responseHeaders = page.locator("#tester-response-headers");
        assertThat(responseHeaders.isVisible()).isTrue();

        page.locator("#tester-url").fill("/api/v1/users");
        page.locator("#btn-tester-send").click();

        page.waitForSelector("#tester-response-status:has-text('200')");
        Locator statusEl = page.locator("#tester-response-status");
        assertThat(statusEl.textContent()).contains("200");

        Locator bodyEl = page.locator("#tester-response-body");
        assertThat(bodyEl.textContent()).contains("Alice");
        assertThat(bodyEl.locator(".json-key").count())
                .as("Response body JSON keys")
                .isGreaterThan(0);
        assertThat(bodyEl.locator(".json-string").count())
                .as("Response body JSON strings")
                .isGreaterThan(0);
        assertThat(bodyEl.locator(".json-number").count())
                .as("Response body JSON numbers")
                .isGreaterThan(0);
        assertThat(bodyEl.locator(".json-punct").count())
                .as("Response body JSON punctuation")
                .isGreaterThan(0);

        Locator sizeEl = page.locator("#tester-response-size");
        assertThat(sizeEl.textContent()).contains("B");

        assertThat(responseHeaders.textContent().toLowerCase()).contains("content-type");

        page.locator("#btn-tester-tab-request").click();
        assertThat(page.locator("#tester-sent-url").textContent()).isEqualTo("/api/v1/users");
        assertThat(page.locator("#tester-sent-method").textContent()).isEqualTo("GET");

        page.locator("#btn-tester-tab-both").click();
        assertThat(page.locator("#tester-view-response").isVisible()).isTrue();
        assertThat(page.locator("#tester-view-request").isVisible()).isTrue();

        Locator historyItems = page.locator(".history-item");
        assertThat(historyItems.count()).isGreaterThanOrEqualTo(1);
        Locator firstHistoryItem = historyItems.first();
        assertThat(firstHistoryItem.textContent()).contains("GET");
        assertThat(firstHistoryItem.textContent()).contains("200");
        assertThat(firstHistoryItem.textContent()).contains("/api/v1/users");

        page.locator("#btn-tester-tab-response").click();

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Scenario 8: Tester header presets, auto Content-Type, and history restore/clear")
    void testTesterPresetsAndHistoryRestore() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#tester");
        page.waitForSelector("#tab-tester.active");

        page.locator("#btn-preset-json").click();
        assertThat(page.locator("#tester-headers").inputValue()).contains("Content-Type: application/json");

        page.locator("#btn-preset-bearer").click();
        assertThat(page.locator("#tester-headers").inputValue()).contains("Authorization: Bearer <token>");

        page.locator("#tester-body").fill("{\"name\":\"TestItem\",\"count\":5}");
        page.locator("#btn-tester-format-json").click();
        assertThat(page.locator("#tester-body").inputValue()).contains("  \"name\": \"TestItem\"");

        page.locator("#tester-method").selectOption("POST");
        page.locator("#tester-url").fill("/api/v1/orders");
        page.locator("#tester-body").fill("{\"item\": \"Widget\"}");
        page.locator("#btn-tester-send").click();

        page.waitForSelector("#tester-response-status:has-text('201')");

        page.locator("#btn-tester-reset-form").click();
        assertThat(page.locator("#tester-method").inputValue()).isEqualTo("GET");
        assertThat(page.locator("#tester-url").inputValue()).isEqualTo("/api/v1/users");
        assertThat(page.locator("#tester-headers").inputValue()).isEmpty();

        page.locator(".history-item:has-text('POST')").first().click();
        assertThat(page.locator("#tester-method").inputValue()).isEqualTo("POST");
        assertThat(page.locator("#tester-url").inputValue()).isEqualTo("/api/v1/orders");
        assertThat(page.locator("#tester-response-status").textContent()).contains("201");

        Locator historyPanel = page.locator("#tester-history-panel");
        assertThat(historyPanel.getAttribute("class")).doesNotContain("collapsed");

        page.locator("#btn-toggle-tester-history").click();
        assertThat(historyPanel.getAttribute("class")).contains("collapsed");
        assertThat(page.locator("#tester-history-collapsed-bar").isVisible()).isTrue();

        page.locator("#btn-expand-tester-history").click();
        assertThat(historyPanel.getAttribute("class")).doesNotContain("collapsed");

        page.onceDialog(Dialog::accept);
        page.locator("#btn-tester-clear-history").click();
        assertThat(page.locator(".history-item").count()).isEqualTo(0);

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Scenario 5: Request journal filtering and inline accordion details")
    void testRequestJournalFilteringAndAccordion() {
        page.navigate(wireMockServer.baseUrl() + "/api/v1/users");
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#journal");

        page.waitForSelector("#journal-list tr");
        Locator rows = page.locator("#journal-list tr.journal-row");
        assertThat(rows.count()).isGreaterThanOrEqualTo(1);

        rows.first().click();

        Locator detailRow = page.locator("tr.journal-detail-row");
        assertThat(detailRow.count()).isEqualTo(1);
        assertThat(detailRow.isVisible()).isTrue();

        page.locator("#journal-search").fill("users");
        page.locator("#journal-search").press("Enter");
        page.waitForTimeout(100);
        assertThat(page.locator("#journal-list tr.journal-row").count()).isGreaterThanOrEqualTo(1);

        page.locator("#journal-search").fill("nonexistent-endpoint-xyz");
        page.locator("#journal-search").press("Enter");
        page.waitForTimeout(100);
        assertThat(page.locator("#journal-list tr.journal-row").count()).isEqualTo(0);

        page.locator("#journal-search").fill("users AND 200");
        page.locator("#journal-search").press("Enter");
        page.waitForTimeout(100);
        assertThat(page.locator("#journal-list tr.journal-row").count()).isGreaterThanOrEqualTo(1);

        page.locator("#journal-search").fill("users AND 500");
        page.locator("#journal-search").press("Enter");
        page.waitForTimeout(100);
        assertThat(page.locator("#journal-list tr.journal-row").count()).isEqualTo(0);

        page.locator("#journal-search").fill("users OR nonexistent");
        page.locator("#journal-search").press("Enter");
        page.waitForTimeout(100);
        assertThat(page.locator("#journal-list tr.journal-row").count()).isGreaterThanOrEqualTo(1);

        page.locator("#journal-search").fill("");
        page.locator("#journal-search").press("Enter");
        page.waitForTimeout(100);
        assertThat(page.locator("#journal-list tr.journal-row").count()).isGreaterThanOrEqualTo(1);

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
    @DisplayName("Should display contextual pre-fill banner in tester and dismissible filter chip in journal")
    void testContextualTesterAndJournalWorkflow() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector(".stub-card:has-text('/api/v1/users')");

        page.locator(".stub-card:has-text('/api/v1/users')").first().click();
        page.locator("#btn-test-stub").click();

        page.waitForSelector("#tab-tester.active");
        Locator testerBanner = page.locator("#tester-context-banner");
        assertThat(testerBanner.isVisible()).isTrue();
        assertThat(page.locator("#tester-context-title").textContent()).contains("/api/v1/users");
        assertThat(page.locator("#tester-method").inputValue()).isEqualTo("GET");
        assertThat(page.locator("#tester-url").inputValue()).isEqualTo("/api/v1/users");

        page.locator("#btn-tester-clear-context").click();
        assertThat(testerBanner.isVisible()).isFalse();

        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector(".stub-card:has-text('/api/v1/users')");
        page.locator(".stub-card:has-text('/api/v1/users')").first().click();
        page.locator("#btn-view-stub-in-journal").click();

        page.waitForSelector("#tab-journal.active");
        Locator journalBanner = page.locator("#journal-context-banner");
        assertThat(journalBanner.isVisible()).isTrue();
        assertThat(page.locator("#journal-filter-chip-text").textContent()).contains("/api/v1/users");

        page.locator("#btn-journal-clear-stub-filter").click();
        assertThat(journalBanner.isVisible()).isFalse();
        assertThat(page.locator("#journal-search").inputValue()).isEmpty();

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Verify Request Journal displays Resolution column with STUBBED, PROXIED, and UNMATCHED pills")
    void testJournalResolutionPills() throws Exception {
        WireMockServer upstreamServer =
                new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstreamServer.start();
        try {
            wireMockServer.resetRequests();

            upstreamServer.stubFor(get(urlEqualTo("/api/v1/remote-service"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"source\":\"upstream\"}")));
            wireMockServer.stubFor(get(urlEqualTo("/api/v1/remote-service"))
                    .willReturn(aResponse().proxiedFrom(upstreamServer.baseUrl())));

            HttpClient client = HttpClient.newHttpClient();
            client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(wireMockServer.baseUrl() + "/api/v1/users"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(wireMockServer.baseUrl() + "/api/v1/remote-service"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(wireMockServer.baseUrl() + "/api/v1/nonexistent-404-endpoint"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());

            page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#journal");
            page.waitForSelector("#tab-journal.active");

            assertThat(page.locator("#journal-table thead").textContent()).contains("Resolution");
            assertThat(page.locator("#journal-table thead").textContent()).doesNotContain("Matched");

            page.waitForSelector("#journal-list tr.journal-row:has-text('/api/v1/users')");
            page.waitForSelector("#journal-list tr.journal-row:has-text('/api/v1/remote-service')");
            page.waitForSelector("#journal-list tr.journal-row:has-text('/api/v1/nonexistent-404-endpoint')");

            Locator stubbedRow = page.locator("#journal-list tr.journal-row:has-text('/api/v1/users')")
                    .first();
            assertThat(stubbedRow.locator(".pill-stubbed").textContent()).contains("STUB");

            Locator proxiedRow = page.locator("#journal-list tr.journal-row:has-text('/api/v1/remote-service')")
                    .first();
            assertThat(proxiedRow.locator(".pill-proxied").textContent()).contains("PROXIED");

            Locator unmatchedRow = page.locator(
                            "#journal-list tr.journal-row:has-text('/api/v1/nonexistent-404-endpoint')")
                    .first();
            assertThat(unmatchedRow.locator(".pill-unmatched").textContent()).contains("UNMATCHED");

            proxiedRow.click();
            page.waitForSelector("tr.journal-detail-row");
            assertThat(page.locator("tr.journal-detail-row").textContent()).contains(upstreamServer.baseUrl());

            page.locator("#journal-search").fill("proxied");
            assertThat(page.locator("#journal-list tr.journal-row:not(.journal-detail-row)")
                            .count())
                    .isEqualTo(1);
            assertThat(page.locator("#journal-list").textContent()).contains("/api/v1/remote-service");

            page.locator("#journal-search").fill("stubbed");
            assertThat(page.locator("#journal-list tr.journal-row:not(.journal-detail-row)")
                            .count())
                    .isEqualTo(1);
            assertThat(page.locator("#journal-list").textContent()).contains("/api/v1/users");

            assertThat(pageErrors).isEmpty();
        } finally {
            if (upstreamServer.isRunning()) {
                upstreamServer.stop();
            }
            resetDefaultStubs();
        }
    }

    @Test
    @DisplayName(
            "Scenario 28: Request Journal lazy progressive rendering, custom limit input, and server-side unmatched filter")
    void testRequestJournalPaginationAndUnmatchedFilter() throws Exception {
        wireMockServer.resetRequests();

        HttpClient client = HttpClient.newHttpClient();
        for (int i = 0; i < 55; i++) {
            client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(wireMockServer.baseUrl() + "/api/v1/users"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
        }
        client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(wireMockServer.baseUrl() + "/missing-resource-alpha"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(wireMockServer.baseUrl() + "/missing-resource-beta"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/");
        page.waitForSelector("#stub-list");

        page.locator("#nav-tab-journal").click();
        page.waitForSelector("#tab-journal.active");
        page.waitForSelector("#journal-table");

        page.waitForSelector("#journal-filtered-count:has-text('Showing 50 of 57')");
        assertThat(page.locator("#journal-filtered-count").textContent()).contains("Showing 50 of 57");
        assertThat(page.locator("#journal-list tr.journal-row").count()).isEqualTo(50);

        page.evaluate(
                "() => { const c = document.querySelector('#tab-journal .table-container'); if (c) { c.scrollTop ="
                        + " c.scrollHeight; c.dispatchEvent(new Event('scroll')); } }");
        page.waitForSelector("#journal-filtered-count:has-text('Showing 57 of 57')");
        assertThat(page.locator("#journal-filtered-count").textContent()).contains("Showing 57 of 57");
        assertThat(page.locator("#journal-list tr.journal-row").count()).isEqualTo(57);

        page.locator("#journal-limit-input").fill("15");
        page.locator("#journal-limit-input").press("Enter");
        page.waitForSelector("#journal-filtered-count:has-text('Showing 15 of 15')");
        assertThat(page.locator("#journal-list tr.journal-row").count()).isEqualTo(15);

        page.locator("#filter-unmatched-only").check();
        page.waitForSelector("#journal-filtered-count:has-text('Showing 2 of 2')");
        assertThat(page.locator("#journal-list tr.journal-row").count()).isEqualTo(2);
        assertThat(page.locator("#journal-list").textContent()).contains("missing-resource-alpha");
        assertThat(page.locator("#journal-list").textContent()).contains("missing-resource-beta");

        page.locator("#journal-search").fill("alpha");
        page.waitForSelector("#journal-filtered-count:has-text('Showing 1 of 1')");
        assertThat(page.locator("#journal-list tr.journal-row").count()).isEqualTo(1);
        assertThat(page.locator("#journal-list").textContent()).contains("missing-resource-alpha");

        page.locator("#journal-search").fill("");
        page.locator("#filter-unmatched-only").uncheck();
        page.locator("#journal-limit-input").fill("100");
        page.locator("#journal-limit-input").press("Enter");
        page.waitForSelector("#journal-list tr.journal-row:has-text('/api/v1/users')");
        page.waitForSelector("#journal-filtered-count:has-text('Showing 50 of 57')");
        assertThat(page.locator("#journal-list tr.journal-row").count()).isEqualTo(50);

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName(
            "Scenario 30: Journal 4-verdict taxonomy (PROXIED, STUB, UNMATCHED), Copy Java stubFor(), and Create Stub from Unmatched Request")
    void testJournalVerdictTaxonomyAndTriageWorkflow() {
        // Spin up a simulated upstream ERP server so proxyBaseUrl requests succeed
        WireMockServer upstreamErp =
                new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstreamErp.start();
        try {
            upstreamErp.stubFor(get(urlEqualTo("/api/v1/erp/invoices"))
                    .willReturn(okJson("{\"invoices\":[{\"id\":\"INV-100\",\"amount\":2500}]}")));

            wireMockServer.stubFor(any(urlMatching("/api/v1/erp/.*"))
                    .withName("Live Service Proxy (ERP System)")
                    .atPriority(10)
                    .withMetadata(Map.of("project", "ERP System", "mode", "proxy"))
                    .willReturn(aResponse().proxiedFrom(upstreamErp.baseUrl())));

            // 1. Verify Copy Java on Stub Detail
            page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
            page.waitForSelector(".stub-card");
            page.locator(".stub-card", new Page.LocatorOptions().setHasText("/api/v1/users"))
                    .click();
            page.locator("#btn-copy-java").click();
            String javaSnippet = (String) page.evaluate("() => navigator.clipboard.readText()");
            assertThat(javaSnippet).contains("stubFor(get(urlEqualTo(\"/api/v1/users\"))");
            assertThat(javaSnippet).contains(".willReturn(aResponse()");

            // 2. Dispatch 3 requests: STUB (/api/v1/users), PROXIED (/api/v1/erp/invoices), UNMATCHED
            // (/api/v1/missing-endpoint)
            page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#tester");
            page.locator("#tester-method").selectOption("GET");
            page.locator("#tester-url").fill("/api/v1/users");
            page.locator("#btn-tester-send").click();
            page.waitForSelector("#tester-response-status:has-text('200')");

            page.locator("#tester-url").fill("/api/v1/erp/invoices");
            page.locator("#btn-tester-send").click();
            page.waitForSelector("#tester-response-status:has-text('200')");
            assertThat(page.locator("#tester-response-body").textContent()).contains("INV-100");

            page.locator("#tester-url").fill("/api/v1/missing-endpoint");
            page.locator("#btn-tester-send").click();
            page.waitForSelector("#tester-response-status:has-text('404')");

            // 3. Inspect Journal Verdicts
            page.locator("#nav-tab-journal").click();
            page.waitForSelector("#journal-table");

            assertThat(page.locator("#journal-list tr[data-verdict='unmatched']")
                            .first()
                            .textContent())
                    .contains("UNMATCHED");
            assertThat(page.locator("#journal-list tr[data-verdict='proxied']")
                            .first()
                            .textContent())
                    .contains("PROXIED");
            assertThat(page.locator("#journal-list tr[data-verdict='stub']")
                            .first()
                            .textContent())
                    .contains("STUB");

            // 4. Filter by 🌐 Proxied verdict pill
            page.locator("#btn-journal-verdict-proxied").click();
            assertThat(page.locator("#journal-list tr[data-testid='journal-row']")
                            .count())
                    .isEqualTo(1);
            assertThat(page.locator("#journal-list tr[data-testid='journal-row']")
                            .first()
                            .textContent())
                    .contains("/api/v1/erp/invoices");

            // 5. Filter by ⚠️ Unmatched verdict pill and expand row
            page.locator("#btn-journal-verdict-unmatched").click();
            page.waitForSelector("#journal-list tr[data-verdict='unmatched']");
            Locator unmatchedRow =
                    page.locator("#journal-list tr[data-verdict='unmatched']").first();
            assertThat(unmatchedRow.textContent()).contains("/api/v1/missing-endpoint");
            unmatchedRow.click();
            page.waitForSelector("tr[data-testid='journal-detail-row']");

            // Ensure Snapshot Stub is NOT offered on an unmatched 404 request, and Create Stub IS offered
            assertThat(page.locator("tr[data-testid='journal-detail-row'] .btn-snapshot-req")
                            .count())
                    .isEqualTo(0);
            Locator createStubBtn =
                    page.locator("tr[data-testid='journal-detail-row'] [data-testid='btn-create-stub-from-req']");
            assertThat(createStubBtn.isVisible()).isTrue();

            // Click Create Stub from Request -> verify Stub Editor modal opens pre-filled
            createStubBtn.click();
            page.waitForSelector("#stub-editor-modal:not(.hidden)");
            assertThat(page.locator("#editor-url").inputValue()).isEqualTo("/api/v1/missing-endpoint");
            assertThat(page.locator("#editor-status").inputValue()).isEqualTo("200");
            page.locator("#btn-cancel-editor").click();
        } finally {
            upstreamErp.stop();
        }
    }

    @Test
    @DisplayName("Scenario 11: Request Journal datetime filtering and 1-click snapshot of filtered proxied requests")
    void testJournalTimeRangeAndFilteredStubSnapshot() throws Exception {
        WireMockServer upstreamOrders =
                new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstreamOrders.start();

        try {
            upstreamOrders.stubFor(get(urlEqualTo("/api/v1/orders/101"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"orderId\":101,\"status\":\"NEW\"}")));
            upstreamOrders.stubFor(get(urlEqualTo("/api/v1/orders/102"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"orderId\":102,\"status\":\"SHIPPED\"}")));

            wireMockServer.stubFor(get(urlMatching("/api/v1/orders/.*"))
                    .willReturn(aResponse().proxiedFrom(upstreamOrders.baseUrl())));

            // Execute proxied traffic
            HttpClient httpClient = HttpClient.newHttpClient();
            httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(wireMockServer.baseUrl() + "/api/v1/orders/101"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(wireMockServer.baseUrl() + "/api/v1/orders/102"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#journal");
            page.waitForSelector("#journal-table");

            // Verify time range selector exists
            Locator timeRangeSelect = page.locator("#journal-time-range");
            assertThat(timeRangeSelect.isVisible()).isTrue();
            assertThat(timeRangeSelect.inputValue()).isEqualTo("all");

            // Verify snapshot button exists
            Locator snapshotBtn = page.locator("#btn-journal-snapshot-filtered");
            assertThat(snapshotBtn.isVisible()).isTrue();

            // Filter journal by "101"
            page.locator("#journal-search").fill("101");
            page.locator("#journal-search").press("Enter");

            page.waitForSelector("#journal-list tr[data-testid='journal-row']");
            assertThat(page.locator("#journal-list tr[data-testid='journal-row']")
                            .count())
                    .isEqualTo(1);
            assertThat(page.locator("#journal-proxied-count").textContent()).isEqualTo("1");
            assertThat(snapshotBtn.isEnabled()).isTrue();

            // Click snapshot button
            snapshotBtn.click();
            page.waitForSelector("#btn-journal-snapshot-filtered:has-text('Snapshotted')");

            // Verify in disabled stub store
            assertThat(disabledStubStore.getAll().stream().anyMatch(s -> "/api/v1/orders/101"
                            .equals(s.getRequest().getUrl())))
                    .isTrue();

        } finally {
            upstreamOrders.stop();
        }
    }

    @Test
    @DisplayName(
            "Scenario 12: Tester persistent corporate headers in browser localStorage, visual chips, macro expansion, and precedence")
    void testTesterPersistentLocalHeaders() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#tester");
        page.waitForSelector("#tab-tester.active");

        // Verify local headers container is visible
        Locator container = page.locator("#tester-local-headers-container");
        assertThat(container.isVisible()).isTrue();

        // Initially 2 default corporate headers configured
        Locator countEl = page.locator("#tester-local-headers-count");
        assertThat(countEl.textContent().trim()).isEqualTo("2");

        // Open Manage Local Headers modal
        page.locator("#btn-manage-local-headers").click();
        page.waitForSelector("#modal-local-headers:not(.hidden)");

        // Table body
        Locator tbody = page.locator("#local-headers-tbody");
        Locator rows = tbody.locator(".local-header-row");
        // Ensure we have at least 3 rows
        while (rows.count() < 3) {
            page.locator("#btn-add-local-header-row").click();
        }

        // Fill row 0: X-Tenant-Id: corp-dev-tenant
        rows.nth(0).locator(".header-row-name").fill("X-Tenant-Id");
        rows.nth(0).locator(".header-row-value").fill("corp-dev-tenant");

        // Fill row 1: X-Correlation-Id: {{uuid}}
        rows.nth(1).locator(".header-row-name").fill("X-Correlation-Id");
        rows.nth(1).locator(".header-row-value").fill("{{uuid}}");

        // Fill row 2: X-User-Role: Developer
        rows.nth(2).locator(".header-row-name").fill("X-User-Role");
        rows.nth(2).locator(".header-row-value").fill("Developer");

        // Save
        page.locator("#btn-save-local-headers").click();
        page.waitForFunction(
                "() => document.getElementById('modal-local-headers') && document.getElementById('modal-local-headers').classList.contains('hidden')");

        // Verify count and chips
        assertThat(countEl.textContent().trim()).isEqualTo("3");
        Locator chips = page.locator("#tester-local-headers-chips .local-header-chip");
        assertThat(chips.count()).isEqualTo(3);
        assertThat(page.locator("#tester-local-headers-chips").textContent()).contains("X-Tenant-Id");
        assertThat(page.locator("#tester-local-headers-chips").textContent()).contains("corp-dev-tenant");
        assertThat(page.locator("#tester-local-headers-chips").textContent()).contains("X-Correlation-Id");
        assertThat(page.locator("#tester-local-headers-chips").textContent()).contains("{{uuid}}");
        assertThat(page.locator("#tester-local-headers-chips").textContent()).contains("X-User-Role");

        // Send request
        page.locator("#tester-url").fill("/api/v1/users");
        page.locator("#btn-tester-send").click();
        page.waitForSelector("#tester-response-status:has-text('200')");

        // Inspect Sent Request pane
        page.locator("#btn-tester-tab-request").click();
        Locator sentHeaders = page.locator("#tester-sent-headers");
        assertThat(sentHeaders.textContent()).contains("X-Tenant-Id: corp-dev-tenant");
        assertThat(sentHeaders.textContent()).contains("X-User-Role: Developer");
        // Verify {{uuid}} was resolved to a valid UUID
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("X-Correlation-Id:\\s+([0-9a-fA-F-]+)")
                .matcher(sentHeaders.textContent());
        assertThat(m.find()).as("Resolved UUID in sent headers").isTrue();
        assertThat(m.group(1)).matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

        // Now test override: type into textarea #tester-headers: "X-Tenant-Id: overridden-tenant"
        page.locator("#tester-headers").fill("X-Tenant-Id: overridden-tenant");
        // Chip should now show overridden state
        Locator tenantChip = page.locator(".local-header-chip[data-header-name='X-Tenant-Id']");
        assertThat(tenantChip.getAttribute("class")).contains("overridden");
        assertThat(tenantChip.textContent()).contains("(overridden)");

        // Send request again with override
        page.locator("#btn-tester-send").click();
        page.waitForSelector("#tester-response-status:has-text('200')");
        assertThat(page.locator("#tester-sent-headers").textContent()).contains("X-Tenant-Id: overridden-tenant");
        assertThat(page.locator("#tester-sent-headers").textContent()).doesNotContain("X-Tenant-Id: corp-dev-tenant");

        // Test toggle persistent headers off
        page.locator("#tester-local-headers-toggle").uncheck();
        assertThat(page.locator("#tester-local-headers-chips").textContent()).contains("Persistent headers paused");

        // Clear textarea and send request: no persistent headers should be sent
        page.locator("#tester-headers").fill("");
        page.locator("#btn-tester-send").click();
        page.waitForSelector("#tester-response-status:has-text('200')");
        assertThat(page.locator("#tester-sent-headers").textContent()).doesNotContain("X-User-Role");
        assertThat(page.locator("#tester-sent-headers").textContent()).doesNotContain("X-Tenant-Id");

        // Re-enable toggle
        page.locator("#tester-local-headers-toggle").check();
        assertThat(page.locator("#tester-local-headers-chips .local-header-chip")
                        .count())
                .isEqualTo(3);
    }
}
