package com.github.mattiasgreen.wiremock.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * E2E Playwright tests for Stubs catalog, boolean filtering, lifecycle toggling,
 * project grouping, bulk actions, and stub creation/editing templates.
 */
public class UiStubsAndLifecyclePlaywrightTest extends AbstractUiPlaywrightTest {

    @Test
    @DisplayName("Scenario 1: Page load & initial stubs listing")
    void testInitialPageLoadAndStubsListing() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/");

        assertThat(page.title()).isEqualTo("WMC - " + wireMockServer.baseUrl());

        page.waitForSelector(".stub-card");
        Locator stubCards = page.locator(".stub-card");
        assertThat(stubCards.count()).isGreaterThanOrEqualTo(2);

        Locator statStubs = page.locator("#stat-stubs");
        assertThat(Integer.parseInt(statStubs.textContent().trim())).isGreaterThanOrEqualTo(2);

        Locator tabStubDetail = page.locator("#tab-stub-detail");
        assertThat(tabStubDetail.getAttribute("class")).contains("active");

        Locator swaggerLink = page.locator("a[href='../swagger-ui/']");
        assertThat(swaggerLink.isVisible()).isTrue();

        assertThat(page.getByTestId("navbar").isVisible()).isTrue();
        assertThat(page.getByTestId("stub-list").getAttribute("data-state")).isEqualTo("ready");
        assertThat(page.getByTestId("stub-card").count()).isGreaterThanOrEqualTo(2);

        page.locator("#search-box").fill("orders");
        assertThat(page.locator(".stub-card:visible").count()).isEqualTo(1);
        assertThat(page.locator(".stub-card:visible").textContent()).contains("orders");

        page.locator("#search-box").fill("non-existent-filter-query");
        assertThat(page.locator(".stub-card:visible").count()).isEqualTo(0);

        page.locator("#search-box").fill("item");
        assertThat(page.locator(".stub-card:visible").count()).isEqualTo(1);
        assertThat(page.locator(".stub-card:visible").textContent()).contains("orders");

        page.locator("#search-box").fill("orders AND item");
        assertThat(page.locator(".stub-card:visible").count()).isEqualTo(1);

        page.locator("#search-box").fill("orders AND nonexistent");
        assertThat(page.locator(".stub-card:visible").count()).isEqualTo(0);

        page.locator("#search-box").fill("orders OR nonexistent");
        assertThat(page.locator(".stub-card:visible").count()).isEqualTo(1);

        page.locator("#search-box").fill("");
        assertThat(page.locator(".stub-card:visible").count()).isGreaterThanOrEqualTo(2);

        assertThat(pageErrors).as("Uncaught page errors on initial load").isEmpty();
    }

    @Test
    @DisplayName("Scenario 2: Stub selection and inspector details")
    void testStubSelectionAndDetails() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/");
        page.waitForSelector(".stub-card");

        page.locator(".stub-card:has-text('/api/v1/users')").click();

        Locator detailView = page.locator("#stub-detail-view");
        assertThat(detailView.getAttribute("class")).doesNotContain("hidden");

        Locator detailUrl = page.locator("#detail-url");
        assertThat(detailUrl.textContent()).contains("/api/v1/");

        Locator jsonViewer = page.locator("#stub-json-viewer");
        assertThat(jsonViewer.textContent()).contains("request");
        assertThat(jsonViewer.locator(".json-key").count())
                .as("Highlighted JSON keys")
                .isGreaterThan(0);
        assertThat(jsonViewer.locator(".json-string").count())
                .as("Highlighted JSON strings")
                .isGreaterThan(0);
        assertThat(jsonViewer.locator(".json-punct").count())
                .as("Highlighted JSON punctuation")
                .isGreaterThan(0);

        String url = page.url();
        assertThat(url).contains("#stubs?stubId=");

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Should display project pills and filter stubs by project")
    void testProjectGroupingAndFiltering() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector("#stub-list .stub-card");

        Locator projectSelect = page.locator("#project-filter-select");
        assertThat(projectSelect.isVisible()).isTrue();

        Locator projectPills = page.locator(".project-pill");
        assertThat(projectPills.count()).isGreaterThan(0);

        Locator btnAll = page.locator("#btn-filter-all");
        Locator btnActive = page.locator("#btn-filter-active");
        Locator btnDisabled = page.locator("#btn-filter-disabled");
        assertThat(btnAll.isVisible()).isTrue();
        assertThat(btnActive.isVisible()).isTrue();
        assertThat(btnDisabled.isVisible()).isTrue();

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Should toggle stub between active and disabled states in detail view and affect HTTP traffic")
    void testEnableDisableStubToggle() throws Exception {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector(".stub-card:has-text('/api/v1/users')");

        Locator userCard = page.locator(".stub-card:has-text('/api/v1/users')").first();
        userCard.click();
        page.waitForSelector("#stub-detail-view:not(.hidden)");

        Locator toggleBtn = page.locator("#btn-toggle-stub");

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(wireMockServer.baseUrl() + "/api/v1/users"))
                .GET()
                .build();
        HttpResponse<String> initialRes = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(initialRes.statusCode()).isEqualTo(200);

        toggleBtn.click();
        page.waitForSelector(".stub-card.stub-disabled:has-text('/api/v1/users')");
        assertThat(userCard.locator(".status-badge.badge-disabled").isVisible()).isTrue();
        assertThat(page.locator("#detail-lifecycle-state").textContent()).isEqualTo("DISABLED");
        assertThat(toggleBtn.textContent()).contains("Enable Stub");

        HttpResponse<String> disabledRes = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(disabledRes.statusCode()).isEqualTo(404);

        toggleBtn.click();
        page.waitForSelector(".stub-card:not(.stub-disabled):has-text('/api/v1/users')");
        assertThat(page.locator("#detail-lifecycle-state").textContent()).isEqualTo("ACTIVE");
        assertThat(toggleBtn.textContent()).contains("Disable Stub");

        HttpResponse<String> reEnabledRes = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(reEnabledRes.statusCode()).isEqualTo(200);

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Should import WireMock JSON bundle with custom project assignment")
    void testWireMockBundleImport() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector("#btn-open-openapi-modal");

        page.locator("#btn-open-openapi-modal").click();
        page.waitForSelector(
                "#openapi-modal.hidden", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.DETACHED));

        page.locator("#import-tab-bundle").click();
        page.waitForSelector("#import-view-bundle:not(.hidden)");

        String bundleJson =
                """
                {
                  "mappings": [
                    {
                      "name": "Healthcheck Ping",
                      "request": {
                        "method": "GET",
                        "url": "/api/v1/health/ping"
                      },
                      "response": {
                        "status": 200,
                        "body": "PONG"
                      }
                    }
                  ]
                }
                """;

        page.locator("#bundle-content").fill(bundleJson);
        page.locator("#bundle-target-project").fill("Ops Management");

        page.locator("#btn-submit-bundle-import").click();
        page.waitForSelector("#openapi-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));

        page.waitForSelector(".stub-card:has-text('/api/v1/health/ping')");
        Locator pingCard = page.locator(".stub-card:has-text('/api/v1/health/ping')");
        assertThat(pingCard.isVisible()).isTrue();
        assertThat(pingCard.textContent()).contains("Ops Management");

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Should show bulk actions bar when stub checkboxes are selected")
    void testBulkActionsBar() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector(".stub-card-chk");

        assertThat(page.locator("#bulk-actions-bar").isHidden()).isTrue();

        Locator firstChk = page.locator(".stub-card-chk").first();
        firstChk.check();

        page.waitForSelector("#bulk-actions-bar:not(.hidden)");
        assertThat(page.locator("#bulk-selected-count").textContent()).contains("1 selected");

        firstChk.uncheck();
        page.waitForSelector(
                "#bulk-actions-bar", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
        assertThat(page.locator("#bulk-actions-bar").isHidden()).isTrue();

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Should clone stub mapping via editor modal, save it, and verify traffic")
    void testCloneAndEditStub() throws Exception {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector(".stub-card:has-text('/api/v1/users')");

        page.locator(".stub-card:has-text('/api/v1/users')").first().click();
        page.waitForSelector("#btn-clone-stub");

        page.locator("#btn-clone-stub").click();
        page.waitForSelector("#stub-editor-modal:not(.hidden)");
        assertThat(page.locator("#editor-modal-title").textContent()).isEqualTo("Duplicate Stub Mapping");

        page.locator("#editor-url").fill("/api/v1/users/cloned");
        page.locator("#editor-status").fill("201");
        page.locator("#editor-project").fill("User Clones");

        page.locator("#btn-save-editor").click();
        page.waitForSelector(
                "#stub-editor-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));

        page.waitForSelector(".stub-card:has-text('/api/v1/users/cloned')");
        Locator clonedCard = page.locator(".stub-card:has-text('/api/v1/users/cloned')");
        assertThat(clonedCard.isVisible()).isTrue();
        assertThat(clonedCard.textContent()).contains("User Clones");

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(wireMockServer.baseUrl() + "/api/v1/users/cloned"))
                .GET()
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(201);

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Scenario 12: Stub lifecycle management (Edit Active, Edit Disabled, Ad-hoc Creation)")
    void testStubManagementEditAndCreateWorkflow() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/");
        page.waitForSelector("#stub-list .stub-card");

        Locator usersCard = page.locator(".stub-card")
                .filter(new Locator.FilterOptions().setHasText("List Users API"))
                .first();
        usersCard.click();
        page.waitForSelector("#tab-stub-detail.active");
        page.locator("#btn-edit-stub").click();

        page.waitForSelector("#stub-editor-modal:not(.hidden)");
        page.locator("#editor-name").fill("Updated Users API");
        page.locator("#btn-save-editor").click();

        page.waitForSelector(
                "#stub-editor-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
        page.waitForSelector("#detail-name:has-text('Updated Users API')");
        assertThat(page.locator("#detail-name").textContent()).contains("Updated Users API");

        page.locator("#btn-toggle-stub").click();
        page.waitForSelector("#detail-lifecycle-state:has-text('DISABLED')");

        page.locator("#btn-edit-stub").click();
        page.waitForSelector("#stub-editor-modal:not(.hidden)");
        page.locator("#editor-name").fill("Updated Disabled Users API");
        page.locator("#btn-save-editor").click();

        page.waitForSelector(
                "#stub-editor-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
        page.waitForSelector("#detail-name:has-text('Updated Disabled Users API')");
        assertThat(page.locator("#detail-name").textContent()).contains("Updated Disabled Users API");

        page.locator("#btn-create-stub").click();
        page.waitForSelector("#stub-editor-modal:not(.hidden)");
        assertThat(page.locator("#editor-modal-title").textContent()).contains("Create New Stub");

        page.locator("#editor-name").fill("Ad-hoc Custom API");
        page.locator("#editor-method").selectOption("POST");
        page.locator("#editor-url").fill("/api/v1/adhoc-test");
        page.locator("#editor-status").fill("201");
        page.locator("#editor-body").fill("{\"adhoc\": true, \"message\": \"Created successfully\"}");
        page.locator("#btn-save-editor").click();

        page.waitForSelector(
                "#stub-editor-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
        page.waitForSelector(".stub-card:has-text('Ad-hoc Custom API')");

        Locator adhocCard = page.locator(".stub-card")
                .filter(new Locator.FilterOptions().setHasText("Ad-hoc Custom API"))
                .first();
        adhocCard.click();
        page.locator("#btn-test-stub").click();
        page.waitForSelector("#tab-tester.active");
        page.locator("#btn-tester-send").click();
        page.waitForSelector("#tester-response-status:has-text('201')");
        assertThat(page.locator("#tester-response-body").textContent()).contains("Created successfully");

        assertThat(pageErrors).isEmpty();
        assertThat(consoleErrors).isEmpty();
        assertThat(httpErrors).isEmpty();
    }

    @Test
    void testStubCreationTemplatesAndRequestBodyMatching() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector("#stub-list .stub-card");

        page.locator("#btn-create-stub").click();
        page.waitForSelector("#stub-editor-modal:not(.hidden)");

        page.waitForSelector("#editor-openapi-banner:not(.hidden)");
        assertThat(page.locator("#editor-openapi-banner").textContent())
                .contains("The primary way is to point to an OpenAPI spec");

        page.waitForSelector("#editor-template-group:not(.hidden)");
        assertThat(page.locator("#editor-template-select").inputValue()).isEqualTo("uri-get");
        assertThat(page.locator("#editor-url").inputValue()).isEqualTo("/api/v1/items");

        page.locator("#editor-template-select").selectOption("body-contains");
        assertThat(page.locator("#editor-method").inputValue()).isEqualTo("POST");
        assertThat(page.locator("#editor-url").inputValue()).isEqualTo("/api/v1/messages");
        assertThat(page.locator("#editor-body-match-type").inputValue()).isEqualTo("contains");
        page.waitForSelector("#editor-body-match-container:not(.hidden)");
        assertThat(page.locator("#editor-body-match-pattern").inputValue()).isEqualTo("string X");

        page.locator("#btn-save-editor").click();
        page.waitForSelector(
                "#stub-editor-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));

        page.waitForSelector(".stub-card:has-text('Submit Message (Body Contains)')");
        Locator card = page.locator(".stub-card")
                .filter(new Locator.FilterOptions().setHasText("Submit Message (Body Contains)"))
                .first();
        card.click();

        page.locator("#btn-test-stub").click();
        page.waitForSelector("#tab-tester.active");
        assertThat(page.locator("#tester-method").inputValue()).isEqualTo("POST");
        assertThat(page.locator("#tester-url").inputValue()).contains("/api/v1/messages");
        assertThat(page.locator("#tester-body").inputValue()).contains("string X");

        page.locator("#btn-tester-send").click();
        page.waitForSelector("#tester-response-status:has-text('200')");
        assertThat(page.locator("#tester-response-body").textContent()).contains("body contains string X");

        page.locator("#nav-tab-stubs").click();
        page.waitForSelector("#tab-stub-detail.active");
        page.locator("#btn-create-stub").click();
        page.waitForSelector("#stub-editor-modal:not(.hidden)");
        page.locator("#btn-editor-open-openapi").click();
        page.waitForSelector(
                "#stub-editor-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
        page.waitForSelector("#openapi-modal:not(.hidden)");
        page.locator("#btn-cancel-openapi").click();
        page.waitForSelector("#openapi-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));

        assertThat(pageErrors).isEmpty();
        assertThat(consoleErrors).isEmpty();
        assertThat(httpErrors).isEmpty();
    }
}
