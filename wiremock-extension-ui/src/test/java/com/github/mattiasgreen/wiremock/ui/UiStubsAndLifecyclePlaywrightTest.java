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

        // Verify Copy dropdown and inline Duplicate/Delete buttons expose expected actions cleanly
        assertThat(page.locator("#btn-clone-stub").isVisible()).isTrue();
        assertThat(page.locator("#btn-delete-stub").isVisible()).isTrue();
        openStubCopyMenu();
        assertThat(page.locator("#btn-copy-curl").isVisible()).isTrue();
        assertThat(page.locator("#btn-copy-java").isVisible()).isTrue();
        assertThat(page.locator("#btn-copy-json").isVisible()).isTrue();

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Should display project pills and filter stubs by project")
    void testProjectGroupingAndFiltering() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector("#stub-list .stub-card");

        Locator projectPills = page.locator(".project-pill");
        assertThat(projectPills.count()).isGreaterThan(0);

        Locator btnAll = page.locator("#btn-filter-all");
        Locator btnActive = page.locator("#btn-filter-active");
        Locator btnDisabled = page.locator("#btn-filter-disabled");
        assertThat(btnAll.isVisible()).isTrue();
        assertThat(btnActive.isVisible()).isTrue();
        assertThat(btnDisabled.isVisible()).isTrue();

        page.locator("#search-box").fill("api-users");
        page.waitForSelector("#stub-list .stub-card");
        assertThat(page.locator("#stub-list .stub-card").count()).isGreaterThan(0);
        page.locator("#search-box").fill("");

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Should toggle stub between active and parked priority tiers and yield to offline-guard")
    void testEnableDisableStubToggle() throws Exception {
        wireMockServer.stubFor(com.github.tomakehurst.wiremock.client.WireMock.any(
                        com.github.tomakehurst.wiremock.client.WireMock.urlMatching("/api/.*"))
                .atPriority(StubLifecycleService.PRIORITY_OFFLINE_GUARD)
                .withMetadata(java.util.Map.of("role", "offline-guard"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                        .withStatus(501)
                        .withBody("{\"error\":\"offline-guard\"}")));

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
        assertThat(disabledRes.statusCode()).isEqualTo(501);

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
        openOpenApiModal();

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

        page.locator("#btn-bundle-edit-workspace").click();
        waitForStubEditorOpen();
        if (page.locator("#workspace-editor-pane").isHidden()) {
            page.locator("#btn-workspace-edit-root").click();
            page.waitForSelector("#workspace-editor-pane:not(.hidden)");
        }
        setCodeEditorContent(bundleJson);
        saveStubWorkspaceAndWaitClosed();
        assertThat(page.locator("#bundle-content-viewer").textContent()).contains("/api/v1/health/ping");

        page.locator("#bundle-target-project").fill("Ops Management");

        page.locator("#btn-submit-bundle-import").click();
        waitForOpenApiModalClosed();

        page.waitForSelector(".stub-card:has-text('/api/v1/health/ping')");
        Locator pingCard = page.locator(".stub-card:has-text('/api/v1/health/ping')");
        assertThat(pingCard.isVisible()).isTrue();
        assertThat(pingCard.textContent()).contains("Ops Management");

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Should scope bulk delete to currently visible filtered stubs and confirm via #modal-confirm")
    void testBulkActionsBarAndVisibleOnlyBulkDelete() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector(".stub-card-chk");

        assertThat(page.locator("#bulk-actions-bar").isHidden()).isTrue();

        // Select both specific target stubs (/api/v1/users and /api/v1/orders)
        Locator usersChk = page.locator(".stub-card:has-text('/api/v1/users') .stub-card-chk")
                .first();
        Locator ordersChk = page.locator(".stub-card:has-text('/api/v1/orders') .stub-card-chk")
                .first();
        usersChk.check();
        ordersChk.check();

        page.waitForSelector("#bulk-actions-bar:not(.hidden)");
        assertThat(page.locator("#bulk-selected-count").textContent()).contains("2 selected");

        // Filter to only "orders" -> bulk toolbar scopes to visible match ("1 selected")
        page.locator("#search-box").fill("orders");
        page.waitForSelector("#bulk-selected-count:has-text('1 selected')");

        // Delete visible selection via #modal-confirm -> hides non-matching /api/v1/users from deletion!
        page.locator("#btn-bulk-delete").click();
        acceptConfirmModal();

        page.locator("#search-box").fill("");
        page.waitForSelector(".stub-card:has-text('/api/v1/users')");
        assertThat(page.locator(".stub-card:has-text('/api/v1/orders')").count())
                .isEqualTo(0);
        assertThat(page.locator(".stub-card:has-text('/api/v1/users')").count()).isGreaterThanOrEqualTo(1);

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName("Should clone stub mapping via Document Workspace, save it, and verify traffic")
    void testCloneAndEditStub() throws Exception {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector(".stub-card:has-text('/api/v1/users')");

        page.locator(".stub-card:has-text('/api/v1/users')").first().click();
        page.waitForSelector("#btn-clone-stub");

        page.locator("#btn-clone-stub").click();
        waitForStubEditorOpen();
        assertThat(page.locator("#workspace-title").textContent()).isEqualTo("Duplicate Stub Mapping");

        page.locator("#btn-workspace-edit-root").click();
        page.waitForSelector("#workspace-editor-pane:not(.hidden)");
        String currentJson = getCodeEditorContent();
        String updatedJson = currentJson
                .replace("/api/v1/users", "/api/v1/users/cloned")
                .replace("\"status\": 200", "\"status\": 201")
                .replace(
                        "\"name\": \"List Users API (Copy)\"",
                        "\"name\": \"List Users API (Copy)\",\n  \"metadata\": { \"project\": \"User Clones\" }");
        setCodeEditorContent(updatedJson);

        saveStubWorkspaceAndWaitClosed();

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
    @DisplayName("Scenario 12: Stub lifecycle management via Document Workspace (Edit Active, Edit Disabled, Create)")
    void testStubManagementEditAndCreateWorkflow() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/");
        page.waitForSelector("#stub-list .stub-card");

        Locator usersCard = page.locator(".stub-card")
                .filter(new Locator.FilterOptions().setHasText("List Users API"))
                .first();
        usersCard.click();
        page.waitForSelector("#tab-stub-detail.active");
        page.locator("#btn-edit-stub").click();

        waitForStubEditorOpen();
        page.locator("#btn-workspace-edit-root").click();
        page.waitForSelector("#workspace-editor-pane:not(.hidden)");
        String activeJson = getCodeEditorContent();
        setCodeEditorContent(activeJson.replace("List Users API", "Updated Users API"));
        saveStubWorkspaceAndWaitClosed();
        page.waitForSelector("#detail-name:has-text('Updated Users API')");
        assertThat(page.locator("#detail-name").textContent()).contains("Updated Users API");

        page.locator("#btn-toggle-stub").click();
        page.waitForSelector("#detail-lifecycle-state:has-text('DISABLED')");

        page.locator("#btn-edit-stub").click();
        waitForStubEditorOpen();
        page.locator("#btn-workspace-edit-root").click();
        page.waitForSelector("#workspace-editor-pane:not(.hidden)");
        String disabledJson = getCodeEditorContent();
        setCodeEditorContent(disabledJson.replace("Updated Users API", "Updated Disabled Users API"));
        saveStubWorkspaceAndWaitClosed();
        page.waitForSelector("#detail-name:has-text('Updated Disabled Users API')");
        assertThat(page.locator("#detail-name").textContent()).contains("Updated Disabled Users API");

        openNewStubModal();
        assertThat(page.locator("#workspace-title").textContent()).contains("Create New Stub");

        String adhocJson =
                """
                {
                  "name": "Ad-hoc Custom API",
                  "priority": 5,
                  "request": {
                    "method": "POST",
                    "url": "/api/v1/adhoc-test"
                  },
                  "response": {
                    "status": 201,
                    "headers": {
                      "Content-Type": "application/json"
                    },
                    "body": "{\\"adhoc\\": true, \\"message\\": \\"Created successfully\\"}"
                  }
                }
                """;
        editWorkspaceRootJson(adhocJson);
        saveStubWorkspaceAndWaitClosed();
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

        openNewStubModal();

        page.waitForSelector("#workspace-template-group:not(.hidden)");
        assertThat(page.locator("#workspace-template-select").inputValue()).isEqualTo("uri-get");
        assertThat(page.locator("#workspace-viewer-pane").innerText()).contains("/api/v1/items");

        page.locator("#workspace-template-select").selectOption("body-contains");
        assertThat(page.locator("#workspace-viewer-pane").innerText()).contains("/api/v1/messages");
        assertThat(page.locator("#workspace-viewer-pane").innerText()).contains("string X");

        saveStubWorkspaceAndWaitClosed();

        page.waitForSelector(".stub-card:has-text('Submit Message (Body Contains)')");
        Locator card = page.locator(".stub-card")
                .filter(new Locator.FilterOptions().setHasText("Submit Message (Body Contains)"))
                .first();
        card.click();

        page.locator("#btn-test-stub").click();
        page.waitForSelector("#tab-tester.active");
        assertThat(page.locator("#tester-method").inputValue()).isEqualTo("POST");
        assertThat(page.locator("#tester-url").inputValue()).contains("/api/v1/messages");
        assertThat(page.locator("#tester-request-viewer").innerText()).contains("string X");

        page.locator("#btn-tester-send").click();
        page.waitForSelector("#tester-response-status:has-text('200')");
        assertThat(page.locator("#tester-response-body").textContent()).contains("body contains string X");

        openStubsTab();
        openNewStubModal();
        page.locator("#btn-workspace-open-openapi").click();
        page.waitForSelector(
                "#modal-document-workspace", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
        page.waitForSelector("#openapi-modal:not(.hidden)");
        page.locator("#btn-cancel-openapi").click();
        waitForOpenApiModalClosed();

        assertThat(pageErrors).isEmpty();
        assertThat(consoleErrors).isEmpty();
        assertThat(httpErrors).isEmpty();
    }

    @Test
    @DisplayName("Scenario 31: Document Workspace nested section round-trip editing and re-escaping")
    void testStubEditorNestedPayloadLensRoundTrip() throws Exception {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/");
        page.waitForSelector("#stub-list");

        openNewStubModal();

        String initialXml =
                "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><account>OLD-ACC</account></soap:Body></soap:Envelope>";
        String rootBodyJson =
                "{\"status\": \"PENDING\", \"soapPayload\": \"" + initialXml.replace("\"", "\\\"") + "\"}";
        String stubJson =
                """
                {
                  "name": "Lens Roundtrip Stub",
                  "request": { "method": "GET", "url": "/api/v1/roundtrip-test" },
                  "response": { "status": 200, "body": %s }
                }
                """
                        .formatted("\"" + rootBodyJson.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");

        editWorkspaceRootJson(stubJson);

        // Viewer discovers nested response.body -> soapPayload XML
        Locator sectionBtns = page.locator("#workspace-viewer-pane .btn-workspace-edit-section");
        assertThat(sectionBtns.count()).isGreaterThanOrEqualTo(2);
        sectionBtns.last().click();
        page.waitForSelector("#workspace-editor-pane:not(.hidden)");

        assertThat(getCodeEditorContent()).contains("OLD-ACC");

        String updatedXml =
                "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body><account>NEW-ACC-9999</account></soap:Body></soap:Envelope>";
        setCodeEditorContent(updatedXml);

        page.locator("#btn-workspace-apply-section").click();
        page.waitForSelector("#workspace-viewer-pane:not(.hidden)");
        assertThat(page.locator("#workspace-viewer-pane").innerText()).contains("NEW-ACC-9999");

        saveStubWorkspaceAndWaitClosed();

        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(wireMockServer.baseUrl() + "/api/v1/roundtrip-test"))
                .GET()
                .build();
        java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("NEW-ACC-9999");
        assertThat(resp.body()).contains("<soap:Envelope");
        assertThat(resp.body()).contains("xmlns:soap=\\\"http");

        assertThat(pageErrors).isEmpty();
        assertThat(consoleErrors).isEmpty();
    }
}
