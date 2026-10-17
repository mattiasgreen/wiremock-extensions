package com.github.mattiasgreen.wiremock.ui;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.microsoft.playwright.Dialog;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * E2E Playwright tests for OpenAPI 3.x YAML/JSON import, request example synthesis,
 * live proxy route generation, traffic snapshotting, and project mode switching.
 */
public class UiOpenApiPlaywrightTest extends AbstractUiPlaywrightTest {

    @Test
    @DisplayName("Scenario 10: OpenAPI Import modal, YAML spec paste, and stub synthesis")
    void testOpenApiImportModalWorkflow() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector("#btn-open-openapi-modal");

        page.locator("#btn-open-openapi-modal").click();
        page.waitForSelector("#openapi-modal:not(.hidden)");

        assertThat(page.locator("#openapi-spec-content").isVisible()).isTrue();
        assertThat(page.locator("#btn-submit-openapi-import").isVisible()).isTrue();

        String spec =
                """
                openapi: 3.0.3
                info:
                  title: Warehouse Inventory
                  version: 1.0.0
                paths:
                  /api/v1/warehouse/stock/{stockId}:
                    post:
                      summary: Create warehouse stock item
                      parameters:
                        - name: stockId
                          in: path
                          required: true
                          example: STK-882
                          schema:
                            type: string
                        - name: X-Warehouse-Region
                          in: header
                          required: true
                          example: EU-NORTH
                          schema:
                            type: string
                        - name: notify
                          in: query
                          required: true
                          example: true
                          schema:
                            type: boolean
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema:
                              type: object
                              properties:
                                warehouseId:
                                  type: string
                                  example: WH-12
                                capacity:
                                  type: integer
                                  example: 500
                      responses:
                        '201':
                          description: Stock item created
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  status:
                                    type: string
                                    example: CREATED
                                  createdId:
                                    type: string
                                    example: STK-882
                """;

        page.locator("#openapi-spec-content").fill(spec);
        page.locator("#btn-submit-openapi-import").click();

        page.waitForSelector(".modal-feedback.success");
        assertThat(page.locator(".modal-feedback.success").textContent()).contains("Successfully created 1 stubs");

        page.waitForSelector("#openapi-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));

        page.waitForSelector(".stub-card:has-text('/api/v1/warehouse/stock/{stockId}')");
        Locator newStubCard = page.locator(".stub-card:has-text('/api/v1/warehouse/stock/{stockId}')");
        assertThat(newStubCard.isVisible()).isTrue();
        assertThat(newStubCard.locator(".stub-card-url").textContent()).isEqualTo("/api/v1/warehouse/stock/{stockId}");

        newStubCard.click();
        assertThat(page.locator("#detail-url").textContent()).isEqualTo("/api/v1/warehouse/stock/{stockId}");
        assertThat(page.locator("#stub-json-viewer").textContent()).contains("CREATED");

        page.locator("#btn-test-stub").click();
        page.waitForSelector("#tab-tester.active");

        assertThat(page.locator("#tester-method").inputValue()).isEqualTo("POST");
        assertThat(page.locator("#tester-url").inputValue()).isEqualTo("/api/v1/warehouse/stock/STK-882?notify=true");
        assertThat(page.locator("#tester-headers").inputValue()).contains("Content-Type: application/json");
        assertThat(page.locator("#tester-headers").inputValue()).contains("X-Warehouse-Region: EU-NORTH");
        assertThat(page.locator("#tester-body").inputValue()).contains("\"warehouseId\" : \"WH-12\"");
        assertThat(page.locator("#tester-body").inputValue()).contains("\"capacity\" : 500");

        page.locator("#btn-tester-send").click();
        page.waitForSelector("#tester-response-status:has-text('201')");
        assertThat(page.locator("#tester-response-body").textContent()).contains("CREATED");
        assertThat(page.locator("#tester-response-body").textContent()).contains("STK-882");

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName(
            "Should execute complete E2E scenario: clear stubs -> import OpenAPI GET & POST -> test both endpoints -> intentionally miss GET stub -> verify unmatched in journal")
    void testOpenApiPostE2EWorkflow() {
        try {
            wireMockServer.resetRequests();
            page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
            page.waitForSelector("#stub-list[data-state='ready']");

            if (page.locator(".stub-card").count() > 0) {
                page.locator("#chk-select-all").click();
                page.waitForSelector("#bulk-actions-bar:not(.hidden)");
                page.onceDialog(Dialog::accept);
                page.locator("#btn-bulk-delete").click();
                page.waitForSelector(".stub-list .empty-state");
                assertThat(page.locator(".stub-card").count()).isEqualTo(0);
            }

            page.locator("#btn-open-openapi-modal").click();
            page.waitForSelector("#openapi-modal:not(.hidden)");

            String openApiYaml =
                    """
                    openapi: 3.0.3
                    info:
                      title: Store & Catalog API
                      version: 1.0.0
                    paths:
                      /api/v1/catalog/products:
                        get:
                          summary: List catalog products
                          responses:
                            '200':
                              description: Product catalog list
                              content:
                                application/json:
                                  schema:
                                    type: array
                                    items:
                                      type: object
                                      properties:
                                        sku:
                                          type: string
                                          example: PROD-778
                                        name:
                                          type: string
                                          example: Wireless Noise-Canceling Headphones
                                        price:
                                          type: number
                                          example: 199.99
                      /api/v1/catalog/orders:
                        post:
                          summary: Create a product order
                          requestBody:
                            required: true
                            content:
                              application/json:
                                schema:
                                  type: object
                                  required:
                                    - sku
                                    - quantity
                                    - customer
                                  properties:
                                    sku:
                                      type: string
                                      example: PROD-778
                                    quantity:
                                      type: integer
                                      example: 2
                                    customer:
                                      type: string
                                      example: alex@example.com
                          responses:
                            '201':
                              description: Order placed successfully
                              content:
                                application/json:
                                  schema:
                                    type: object
                                    properties:
                                      orderId:
                                        type: string
                                        example: ORD-9912
                                      status:
                                        type: string
                                        example: CONFIRMED
                    """;

            page.locator("#openapi-spec-content").fill(openApiYaml);
            page.locator("#btn-submit-openapi-import").click();
            page.waitForSelector(".modal-feedback.success");
            page.waitForSelector(
                    "#openapi-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));

            page.waitForSelector(".stub-card:has-text('/api/v1/catalog/orders')");
            page.waitForSelector(".stub-card:has-text('/api/v1/catalog/products')");
            assertThat(page.locator(".stub-card").count()).isEqualTo(2);

            page.locator(".stub-card:has-text('/api/v1/catalog/orders')")
                    .first()
                    .click();
            assertThat(page.locator("#detail-method").textContent()).isEqualTo("POST");
            assertThat(page.locator("#detail-url").textContent()).isEqualTo("/api/v1/catalog/orders");
            assertThat(page.locator("#detail-status").textContent()).isEqualTo("201");

            page.locator("#btn-test-stub").click();
            page.waitForSelector("#tab-tester.active");

            assertThat(page.locator(".sidebar").isVisible()).isTrue();
            assertThat(page.locator(".stub-card:has-text('/api/v1/catalog/orders')")
                            .first()
                            .getAttribute("class"))
                    .contains("selected");

            assertThat(page.locator("#tester-method").inputValue()).isEqualTo("POST");
            assertThat(page.locator("#tester-url").inputValue()).isEqualTo("/api/v1/catalog/orders");
            assertThat(page.locator("#tester-headers").inputValue()).contains("Content-Type: application/json");
            assertThat(page.locator("#tester-body").inputValue()).contains("PROD-778");
            assertThat(page.locator("#tester-context-banner").isVisible()).isTrue();

            page.locator("#btn-tester-send").click();
            page.waitForSelector("#tester-response-status:has-text('201')");
            assertThat(page.locator("#tester-response-status").textContent()).contains("201");
            assertThat(page.locator("#tester-response-body").textContent()).contains("ORD-9912");

            page.locator(".stub-card:has-text('/api/v1/catalog/products')")
                    .first()
                    .click();
            assertThat(page.locator("#tester-method").inputValue()).isEqualTo("GET");
            assertThat(page.locator("#tester-url").inputValue()).isEqualTo("/api/v1/catalog/products");

            page.locator("#btn-tester-send").click();
            page.waitForSelector("#tester-response-status:has-text('200')");
            assertThat(page.locator("#tester-response-status").textContent()).contains("200");
            assertThat(page.locator("#tester-response-body").textContent())
                    .contains("Wireless Noise-Canceling Headphones");

            page.locator("#tester-url").fill("/api/v1/catalog/products/missing-sku");
            page.locator("#btn-tester-send").click();
            page.waitForSelector("#tester-response-status:has-text('404')");
            assertThat(page.locator("#tester-response-status").textContent()).contains("404");

            Locator historyCards = page.locator(".history-item");
            assertThat(historyCards.count()).isGreaterThanOrEqualTo(3);
            assertThat(historyCards.nth(0).textContent()).contains("404");
            assertThat(historyCards.nth(1).textContent()).contains("200");
            assertThat(historyCards.nth(2).textContent()).contains("201");

            page.locator("#nav-tab-journal").click();
            page.waitForSelector("#tab-journal.active");

            page.waitForSelector("#journal-list tr.journal-row:has-text('/api/v1/catalog/orders')");
            page.waitForSelector("#journal-list tr.journal-row:has-text('/api/v1/catalog/products')");
            page.waitForSelector("#journal-list tr.journal-row:has-text('missing-sku')");

            Locator ordersRow = page.locator("#journal-list tr.journal-row:has-text('/api/v1/catalog/orders')")
                    .first();
            assertThat(ordersRow.textContent()).contains("STUBBED");

            Locator unmatchedRow = page.locator("#journal-list tr.journal-row:has-text('missing-sku')")
                    .first();
            assertThat(unmatchedRow.textContent()).contains("404");
            assertThat(unmatchedRow.textContent().toUpperCase()).contains("UNMATCHED");
            unmatchedRow.click();

            Locator detailRow = page.locator("tr.journal-detail-row");
            assertThat(detailRow.isVisible()).isTrue();
            assertThat(detailRow.textContent()).contains("missing-sku");

            page.locator("#filter-unmatched-only").check();
            assertThat(page.locator("#journal-list tr.journal-row:not(.journal-detail-row)")
                            .count())
                    .isEqualTo(1);

            assertThat(pageErrors).isEmpty();
        } finally {
            resetDefaultStubs();
        }
    }

    @Test
    @DisplayName("Scenario 11: OpenAPI Live Proxy Import, Testing via UI, Snapshot Recording, and Project Mode Flip")
    void testOpenApiLiveProxyAndRecordingLifecycle() {
        WireMockServer upstreamServer =
                new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstreamServer.start();

        try {
            upstreamServer.stubFor(get(urlEqualTo("/api/v1/live-catalog"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"status\":\"success\",\"upstreamData\":\"Live Catalog Item\"}")));

            page.navigate(wireMockServer.baseUrl() + "/__admin/ui/");
            assertThat(page.title()).isEqualTo("WMC - " + wireMockServer.baseUrl());

            page.locator("#btn-open-openapi-modal").click();
            page.waitForSelector("#openapi-modal:not(.hidden)");

            page.locator("#opt-openapi-mode-proxy").click();
            assertThat(page.locator("#openapi-proxy-config").isVisible()).isTrue();

            page.locator("#openapi-proxy-url").fill(upstreamServer.baseUrl());
            page.locator("#openapi-project-name").fill("Catalog Service");

            String specYaml =
                    """
                    openapi: 3.0.3
                    info:
                      title: Catalog Service
                      version: 1.0.0
                    paths:
                      /api/v1/live-catalog:
                        get:
                          summary: Fetch live catalog
                          responses:
                            '200':
                              description: Ok
                    """;

            page.locator("#openapi-spec-content").fill(specYaml);
            page.locator("#btn-submit-openapi-import").click();

            page.waitForSelector(
                    "#openapi-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
            page.waitForSelector("#stub-list .stub-card");

            Locator proxyCard = page.locator(".stub-card")
                    .filter(new Locator.FilterOptions().setHasText("live-catalog"))
                    .first();
            assertThat(proxyCard.isVisible()).isTrue();
            assertThat(proxyCard.locator(".status-proxy").textContent()).contains("PROXY");

            proxyCard.click();
            page.locator("#btn-test-stub").click();
            page.waitForSelector("#tab-tester.active");

            page.locator("#btn-tester-send").click();
            page.waitForSelector("#tester-response-status:has-text('200')");
            assertThat(page.locator("#tester-response-body").textContent()).contains("Live Catalog Item");

            page.locator("#nav-tab-stubs").click();
            page.waitForSelector("#tab-stub-detail.active");
            page.locator("#project-filter-select").selectOption("Catalog Service");

            page.waitForSelector("#project-lifecycle-bar:not(.hidden)");
            assertThat(page.locator("#project-mode-badge").textContent()).contains("LIVE PROXY");

            page.onceDialog(dialog -> dialog.accept());
            page.locator("#btn-project-snapshot").click();

            page.waitForSelector("#btn-filter-disabled");
            page.locator("#btn-filter-disabled").click();
            Locator disabledCard = page.locator(".stub-card")
                    .filter(new Locator.FilterOptions().setHasText("RECORDED"))
                    .first();
            assertThat(disabledCard.isVisible()).isTrue();
            assertThat(disabledCard.locator(".status-badge.badge-disabled").textContent())
                    .contains("DISABLED");

            page.locator("#btn-project-mode-toggle").click();
            page.waitForSelector("#project-mode-badge:has-text('STUBS MODE')");

            upstreamServer.stop();

            page.locator("#nav-tab-tester").click();
            page.waitForSelector("#tab-tester.active");
            page.locator("#btn-tester-send").click();
            page.waitForSelector("#tester-response-status:has-text('200')");
            assertThat(page.locator("#tester-response-body").textContent()).contains("Live Catalog Item");

            assertThat(pageErrors).isEmpty();
        } finally {
            if (upstreamServer.isRunning()) {
                upstreamServer.stop();
            }
            resetDefaultStubs();
        }
    }
}
