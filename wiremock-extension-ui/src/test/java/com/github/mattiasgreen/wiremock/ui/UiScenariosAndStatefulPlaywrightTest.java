package com.github.mattiasgreen.wiremock.ui;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.mattiasgreen.wiremock.stateful.ast.AstJson;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * E2E Playwright tests for Scenarios DAG visualizer, Dynamic Stateful simulation stubs,
 * Target Server configuration, multi-tab identification, and graceful degradation.
 */
public class UiScenariosAndStatefulPlaywrightTest extends AbstractUiPlaywrightTest {

    @Test
    @DisplayName("Scenario 9: Scenarios DAG visualizer, state pipeline, and state overrides")
    void testScenariosDagVisualizer() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#scenarios");
        page.waitForSelector("#tab-scenarios.active .scenario-fsm-card");

        assertThat(page.locator(".scenario-name").textContent()).contains("Case-FSM");
        assertThat(page.locator(".scenario-state-pill").textContent()).contains("Started");

        assertThat(page.locator(".fsm-step-name").allTextContents()).contains("Started", "OPEN", "CLOSED");

        page.locator(".scenario-target-state-select").selectOption("OPEN");
        page.locator(".btn-set-scenario-state").click();
        page.waitForSelector(".scenario-state-pill:has-text('OPEN')");
        assertThat(page.locator(".fsm-node-card.active .fsm-node-name").textContent())
                .isEqualTo("OPEN");

        page.locator(".btn-reset-single-scenario").click();
        page.waitForSelector(".scenario-state-pill:has-text('Started')");
        assertThat(page.locator(".fsm-node-card.active .fsm-node-name").textContent())
                .isEqualTo("Started");

        assertThat(pageErrors).isEmpty();
    }

    @Test
    @DisplayName(
            "Scenario 22: Unified stubs view displays dynamic simulation badges, route coverage, and invariant cards")
    void testUnifiedStubsViewDisplaysDynamicBadgesAndCoverage() {
        stateEngine.registerGlobalModel(createCasesModel());

        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector(".stub-card.stub-dynamic");

        Locator dynamicCards = page.locator(".stub-card.stub-dynamic");
        assertThat(dynamicCards.count()).isEqualTo(4);
        assertThat(dynamicCards.first().locator(".badge-dynamic").textContent()).contains("STATEFUL");

        page.locator("#btn-filter-dynamic").click();
        page.waitForTimeout(200);
        assertThat(page.locator(".stub-card").count()).isEqualTo(4);

        Locator closeCaseCard =
                page.locator(".stub-card", new Page.LocatorOptions().setHasText("PUT /api/v1/cases/{caseId}/close"));
        closeCaseCard.click();
        page.waitForSelector("#detail-stateful-section:not(.hidden)");

        assertThat(page.locator("#stateful-entity-label").textContent()).isEqualTo("cases");
        assertThat(page.locator("#stateful-route-scope").textContent()).contains("/api/v1/cases/*/close");
        assertThat(page.locator("#stateful-invariants-count").textContent()).isEqualTo("1");
        assertThat(page.locator("#stateful-invariants-list").textContent())
                .contains("Cannot close case: pending tasks exist");
        assertThat(page.locator("#stateful-mutations-count").textContent()).isEqualTo("1");

        page.locator("#btn-filter-all").click();

        assertThat(pageErrors).isEmpty();
        assertThat(consoleErrors).isEmpty();
    }

    @Test
    @DisplayName(
            "Scenario 23: Dynamic stub jump to HTTP tester with path parameter replacement and execution journal tracing")
    void testDynamicStubToTesterToJournalWorkflow() {
        stateEngine.registerGlobalModel(createCasesModel());
        ObjectNode caseEntity = AstJson.createObjectNode();
        caseEntity.put("id", "case-100");
        caseEntity.put("status", "OPEN");
        caseEntity.putArray("tasks");
        stateEngine.getStateStore().putEntity("test-scenario-1", "cases", "case-100", caseEntity);

        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector(".stub-card.stub-dynamic");

        Locator taskCard =
                page.locator(".stub-card", new Page.LocatorOptions().setHasText("POST /api/v1/cases/{caseId}/tasks"));
        taskCard.click();

        page.locator("#btn-test-stub").click();

        page.waitForSelector("#tab-tester.active");
        assertThat(page.locator("#tester-method").inputValue()).isEqualTo("POST");
        assertThat(page.locator("#tester-url").inputValue()).isEqualTo("/api/v1/cases/case-100/tasks");
        assertThat(page.locator("#tester-headers").inputValue()).contains("X-Correlation-Id: test-scenario-1");
        assertThat(page.locator("#tester-body").inputValue()).contains("Verification Task");

        page.locator("#btn-tester-send").click();
        page.waitForSelector("#tester-response-status:not(:has-text('...'))");
        assertThat(page.locator("#tester-response-status").textContent()).contains("201");

        page.locator("#nav-tab-journal").click();
        page.waitForSelector("#journal-table");

        Locator journalRows = page.locator("#journal-list tr[data-testid='journal-row']");
        assertThat(journalRows.first().locator(".pill-stateful").textContent()).contains("STATEFUL");

        journalRows.first().click();
        page.waitForSelector("tr[data-testid='journal-detail-row']:not(.hidden)");

        Locator statefulCard = page.locator("div[data-testid='journal-stateful-card']");
        assertThat(statefulCard.isVisible()).isTrue();
        assertThat(statefulCard.textContent()).contains("cases");
        assertThat(statefulCard.textContent()).contains("PASSED");

        assertThat(pageErrors).isEmpty();
        assertThat(consoleErrors).isEmpty();
    }

    @Test
    @DisplayName("Scenario 24: Bootstrap new dynamic stub from template modal")
    void testCreateDynamicStubFromTemplateModal() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector("#stub-list");

        page.locator("#btn-create-dynamic-stub").click();
        page.waitForSelector("#dynamic-stub-modal:not(.hidden)");

        page.locator("#dynamic-model-json").fill("{ malformed json syntax ]");
        final boolean[] dialogHandled = {false};
        page.onceDialog(dialog -> {
            assertThat(dialog.message()).contains("Invalid JSON");
            dialogHandled[0] = true;
            dialog.accept();
        });
        page.locator("#btn-save-dynamic").click();
        page.waitForTimeout(100);
        assertThat(dialogHandled[0]).isTrue();
        assertThat(page.locator("#dynamic-stub-modal").isVisible()).isTrue();

        page.locator("#dynamic-template-select").selectOption("order-fulfillment");
        assertThat(page.locator("#dynamic-entity-name").inputValue()).isEqualTo("orders");
        assertThat(page.locator("#dynamic-model-json").inputValue()).contains("cannot ship empty order");

        page.locator("#btn-save-dynamic").click();
        page.waitForSelector(
                "#dynamic-stub-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));

        page.waitForSelector(".stub-card:has-text('ORDERS')");
        Locator orderCards = page.locator(".stub-card:has-text('ORDERS')");
        assertThat(orderCards.count()).isGreaterThanOrEqualTo(1);

        assertThat(pageErrors).isEmpty();
        assertThat(consoleErrors).isEmpty();
    }

    @Test
    @DisplayName("Scenario 25: Stateful invariant violation returns 409 and traces in Request Journal")
    void testDynamicStubInvariantViolationInJournal() {
        stateEngine.registerGlobalModel(createCasesModel());

        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#tester");
        page.locator("#tester-method").selectOption("POST");
        page.locator("#tester-url").fill("/api/v1/cases");
        page.locator("#tester-headers").fill("X-Correlation-Id: inv-session-1\nContent-Type: application/json");
        page.locator("#tester-body").fill("{\"title\":\"Case with tasks\"}");
        page.locator("#btn-tester-send").click();
        page.waitForSelector("#tester-response-status:has-text('201')");

        page.locator("#tester-url").fill("/api/v1/cases/case-101/tasks");
        page.locator("#tester-body").fill("{\"title\":\"Pending task\",\"status\":\"PENDING\"}");
        page.locator("#btn-tester-send").click();
        page.waitForSelector("#tester-response-status:has-text('201')");

        page.locator("#tester-method").selectOption("PUT");
        page.locator("#tester-url").fill("/api/v1/cases/case-101/close");
        page.locator("#tester-body").fill("{}");
        page.locator("#btn-tester-send").click();
        page.waitForSelector("#tester-response-status:has-text('409')");
        assertThat(page.locator("#tester-response-body").textContent())
                .contains("Cannot close case: pending tasks exist");

        page.locator("#nav-tab-journal").click();
        page.waitForSelector("#journal-table");

        Locator firstRow =
                page.locator("#journal-list tr[data-testid='journal-row']").first();
        firstRow.click();
        page.waitForSelector("tr[data-testid='journal-detail-row']:not(.hidden)");

        Locator statefulCard = page.locator("div[data-testid='journal-stateful-card']");
        assertThat(statefulCard.isVisible()).isTrue();
        assertThat(statefulCard.textContent()).contains("VIOLATED");
        assertThat(statefulCard.textContent()).contains("Cannot close case: pending tasks exist");

        assertThat(pageErrors).isEmpty();
        assertThat(consoleErrors.stream().filter(e -> !e.contains("409")).toList())
                .isEmpty();
    }

    @Test
    void testConfigurableWireMockTargetHost() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/");
        page.waitForSelector("#target-host-label");
        assertThat(page.locator("#target-host-label").textContent()).isEqualTo("Target: (same-origin)");
        assertThat(page.title()).isEqualTo("WMC - " + wireMockServer.baseUrl());

        page.locator("#btn-target-host").click();
        page.waitForSelector("#target-host-modal:not(.hidden)");

        String targetUrl = wireMockServer.baseUrl();
        page.locator("#input-target-host").fill(targetUrl);
        page.locator("#btn-save-target-host").click();

        page.waitForSelector(
                "#target-host-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
        assertThat(page.locator("#target-host-label").textContent()).contains(targetUrl);
        assertThat(page.title()).isEqualTo("WMC - " + targetUrl);
        assertThat(page.url()).contains("wiremock=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8));

        page.waitForSelector(".stub-card");
        page.locator(".stub-card").first().click();
        page.locator("#btn-copy-curl").click();
        assertThat(page.locator("#btn-copy-curl").textContent()).contains("Copied");

        page.locator("#btn-target-host").click();
        page.waitForSelector("#target-host-modal:not(.hidden)");
        page.locator("#btn-reset-target-host").click();
        page.waitForSelector(
                "#target-host-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
        assertThat(page.locator("#target-host-label").textContent()).isEqualTo("Target: (same-origin)");
        assertThat(page.title()).isEqualTo("WMC - " + wireMockServer.baseUrl());
        assertThat(page.url()).doesNotContain("wiremock=");

        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/?wiremock="
                + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8) + "#stubs");
        page.waitForSelector(".stub-card");
        assertThat(page.locator("#target-host-label").textContent()).contains(targetUrl);
        assertThat(page.title()).isEqualTo("WMC - " + targetUrl);

        assertThat(pageErrors).isEmpty();
        assertThat(consoleErrors).isEmpty();
        assertThat(httpErrors).isEmpty();
    }

    @Test
    @DisplayName("Story 10: Multi-Instance Tab Identification (Site Header & Tab Name)")
    void testMultiTabSiteHeaderAndTabName() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/");
        page.waitForSelector("#stub-list");
        assertThat(page.title()).isEqualTo("WMC - " + wireMockServer.baseUrl());

        String remoteUrl = "https://mock-env1.corp.internal:8443/wiremock";
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/?wiremock="
                + URLEncoder.encode(remoteUrl, StandardCharsets.UTF_8));
        page.waitForSelector("#target-host-label");
        assertThat(page.title()).isEqualTo("WMC - " + remoteUrl);

        page.locator("#btn-target-host").click();
        page.waitForSelector("#target-host-modal:not(.hidden)");
        String remoteUrl2 = "http://mock-env2.corp.internal:9090";
        page.locator("#input-target-host").fill(remoteUrl2);
        page.locator("#btn-save-target-host").click();
        page.waitForSelector(
                "#target-host-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
        assertThat(page.title()).isEqualTo("WMC - " + remoteUrl2);

        page.locator("#btn-target-host").click();
        page.waitForSelector("#target-host-modal:not(.hidden)");
        page.locator("#btn-reset-target-host").click();
        page.waitForSelector(
                "#target-host-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
        assertThat(page.title()).isEqualTo("WMC - " + wireMockServer.baseUrl());

        assertThat(pageErrors).isEmpty();
        assertThat(consoleErrors).isEmpty();
    }

    @Test
    @DisplayName(
            "Scenario 26: Target server modal configures base URL, strategy, updates browser query params and cURL")
    void testTargetHostModalAndStrategyConfiguration() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/");
        page.waitForSelector("#stub-list");

        assertThat(page.locator("#target-host-label").textContent()).contains("(same-origin)");

        page.locator("#btn-target-host").click();
        page.waitForSelector("#target-host-modal:not(.hidden)");

        page.locator("#input-target-host").fill("https://gateway.corp:8443/test-envs/env1/wiremock");
        page.locator("#radio-strategy-proxy").check();
        page.locator("#btn-save-target-host").click();

        page.waitForSelector(
                "#target-host-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));

        assertThat(page.locator("#target-host-label").textContent())
                .contains("https://gateway.corp:8443/test-envs/env1/wiremock (proxied)");
        assertThat(page.url()).contains("wiremock=https%3A%2F%2Fgateway.corp%3A8443%2Ftest-envs%2Fenv1%2Fwiremock");

        page.locator(".stub-card").first().click();
        page.locator("#btn-copy-curl").click();
        String copiedCurl = (String) page.evaluate("() => navigator.clipboard.readText()");
        assertThat(copiedCurl).contains("https://gateway.corp:8443/test-envs/env1/wiremock");

        page.locator("#btn-target-host").click();
        page.waitForSelector("#target-host-modal:not(.hidden)");
        page.locator("#radio-strategy-direct").check();
        page.locator("#btn-save-target-host").click();
        page.waitForSelector(
                "#target-host-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));

        assertThat(page.locator("#target-host-label").textContent())
                .contains("https://gateway.corp:8443/test-envs/env1/wiremock (direct)");
        assertThat(page.url()).contains("strategy=direct");

        page.locator("#btn-target-host").click();
        page.waitForSelector("#target-host-modal:not(.hidden)");
        page.locator("#btn-reset-target-host").click();
        page.waitForSelector(
                "#target-host-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));

        assertThat(page.locator("#target-host-label").textContent()).contains("(same-origin)");
        assertThat(page.url()).doesNotContain("wiremock=");
        assertThat(page.url()).doesNotContain("strategy=");
    }

    @Test
    @DisplayName("Scenario 27: Graceful degradation on remote WireMock lacking custom extensions")
    void testGracefulDegradationOnVanillaWireMock() {
        WireMockServer vanillaServer =
                new WireMockServer(WireMockConfiguration.options().dynamicPort());
        vanillaServer.start();
        WireMockConsoleProxy proxy = new WireMockConsoleProxy(wireMockServer.baseUrl(), 0);
        try {
            vanillaServer.stubFor(get(urlEqualTo("/api/v1/health"))
                    .withName("Health Check")
                    .willReturn(okJson("{\"status\":\"UP\"}")));

            page.navigate("http://localhost:" + proxy.getLocalPort() + "/__admin/ui/?wiremock="
                    + URLEncoder.encode(vanillaServer.baseUrl(), StandardCharsets.UTF_8)
                    + "&strategy=proxy#stubs");

            page.waitForSelector(".stub-card");
            assertThat(page.locator(".stub-card").count()).isEqualTo(1);
            assertThat(page.locator(".stub-card").first().textContent()).contains("/api/v1/health");

            assertThat(page.locator("#nav-tab-netem").getAttribute("class")).contains("hidden");
            assertThat(page.locator("#link-metrics").getAttribute("class")).contains("hidden");
            assertThat(page.locator("#btn-create-dynamic-stub").getAttribute("class"))
                    .contains("hidden");
            assertThat(page.locator("#btn-filter-dynamic").getAttribute("class"))
                    .contains("hidden");
            assertThat(page.locator("#btn-filter-disabled").getAttribute("class"))
                    .contains("hidden");

            page.locator(".stub-card").first().click();
            assertThat(page.locator("#btn-toggle-stub").getAttribute("class")).contains("hidden");
            assertThat(page.locator("#detail-lifecycle-state").textContent()).isEqualTo("ACTIVE");

            page.locator("#btn-target-host").click();
            page.waitForSelector("#target-host-modal:not(.hidden)");

            page.waitForSelector("#target-capabilities-status");
            assertThat(page.locator("#target-capabilities-status").textContent())
                    .contains("1/5 active (Degraded)");

            assertThat(page.locator("[data-testid='cap-badge-core']").textContent())
                    .isEqualTo("Active");
            assertThat(page.locator("[data-testid='cap-badge-lifecycle']").textContent())
                    .isEqualTo("Not Installed");
            assertThat(page.locator("[data-testid='cap-badge-stateful']").textContent())
                    .isEqualTo("Not Installed");
            assertThat(page.locator("[data-testid='cap-badge-netem']").textContent())
                    .isEqualTo("Not Installed");
            assertThat(page.locator("[data-testid='cap-badge-metrics']").textContent())
                    .isEqualTo("Not Installed");

            page.locator("#btn-close-target-host").click();
            page.waitForSelector(
                    "#target-host-modal", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));

            page.locator("#btn-test-stub").click();
            page.waitForSelector("#tab-tester.active");
            page.locator("#btn-tester-send").click();
            page.waitForSelector("#tester-response-status:has-text('200')");
            assertThat(page.locator("#tester-response-body").textContent()).contains("\"status\": \"UP\"");
        } finally {
            proxy.stop();
            vanillaServer.stop();
        }
    }
}
