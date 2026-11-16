package com.github.mattiasgreen.wiremock.ui;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * E2E Playwright tests for Scenarios DAG visualizer, Target Server configuration,
 * and multi-tab identification.
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
    void testConfigurableWireMockTargetHost() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/");
        page.waitForSelector("#target-host-label");
        assertThat(page.locator("#target-host-label").textContent()).isEqualTo("Target: (same-origin)");
        assertThat(page.title()).isEqualTo("WMC - " + wireMockServer.baseUrl());

        page.locator("#btn-target-host").click();
        page.waitForSelector("#modal-settings:not(.hidden)");
        page.waitForSelector("#settings-panel-target:not(.hidden)");

        String targetUrl = wireMockServer.baseUrl();
        page.locator("#input-target-host").fill(targetUrl);
        page.locator("#btn-save-target-host").click();

        page.waitForSelector(
                "#modal-settings", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
        assertThat(page.locator("#target-host-label").textContent()).contains(targetUrl);
        assertThat(page.title()).isEqualTo("WMC - " + targetUrl);
        assertThat(page.url()).contains("wiremock=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8));

        page.waitForSelector(".stub-card");
        page.locator(".stub-card").first().click();
        openStubCopyMenu();
        page.locator("#btn-copy-curl").click();
        assertThat(page.locator("#btn-copy-curl").textContent()).contains("Copied");

        page.locator("#btn-target-host").click();
        page.waitForSelector("#modal-settings:not(.hidden)");
        page.locator("#btn-reset-target-host").click();
        page.waitForSelector(
                "#modal-settings", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
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
        page.waitForSelector("#modal-settings:not(.hidden)");
        String remoteUrl2 = "http://mock-env2.corp.internal:9090";
        page.locator("#input-target-host").fill(remoteUrl2);
        page.locator("#btn-save-target-host").click();
        page.waitForSelector(
                "#modal-settings", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
        assertThat(page.title()).isEqualTo("WMC - " + remoteUrl2);

        page.locator("#btn-target-host").click();
        page.waitForSelector("#modal-settings:not(.hidden)");
        page.locator("#btn-reset-target-host").click();
        page.waitForSelector(
                "#modal-settings", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));
        assertThat(page.title()).isEqualTo("WMC - " + wireMockServer.baseUrl());

        assertThat(pageErrors).isEmpty();
        assertThat(consoleErrors).isEmpty();
    }

    @Test
    @DisplayName(
            "Scenario 26: Settings modal Target tab configures base URL, strategy, updates browser query params and cURL")
    void testTargetHostModalAndStrategyConfiguration() {
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/");
        page.waitForSelector("#stub-list");

        assertThat(page.locator("#target-host-label").textContent()).contains("(same-origin)");

        page.locator("#btn-target-host").click();
        page.waitForSelector("#modal-settings:not(.hidden)");

        page.locator("#input-target-host").fill("https://gateway.corp:8443/test-envs/env1/wiremock");
        page.locator("#radio-strategy-proxy").check();
        page.locator("#btn-save-target-host").click();

        page.waitForSelector(
                "#modal-settings", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));

        assertThat(page.locator("#target-host-label").textContent())
                .contains("https://gateway.corp:8443/test-envs/env1/wiremock (proxied)");
        assertThat(page.url()).contains("wiremock=https%3A%2F%2Fgateway.corp%3A8443%2Ftest-envs%2Fenv1%2Fwiremock");

        page.locator(".stub-card").first().click();
        openStubCopyMenu();
        page.locator("#btn-copy-curl").click();
        String copiedCurl = (String) page.evaluate("() => navigator.clipboard.readText()");
        assertThat(copiedCurl).contains("https://gateway.corp:8443/test-envs/env1/wiremock");

        page.locator("#btn-target-host").click();
        page.waitForSelector("#modal-settings:not(.hidden)");
        page.locator("#radio-strategy-direct").check();
        page.locator("#btn-save-target-host").click();
        page.waitForSelector(
                "#modal-settings", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));

        assertThat(page.locator("#target-host-label").textContent())
                .contains("https://gateway.corp:8443/test-envs/env1/wiremock (direct)");
        assertThat(page.url()).contains("strategy=direct");

        page.locator("#btn-target-host").click();
        page.waitForSelector("#modal-settings:not(.hidden)");
        page.locator("#btn-reset-target-host").click();
        page.waitForSelector(
                "#modal-settings", new Page.WaitForSelectorOptions().setState(WaitForSelectorState.HIDDEN));

        page.waitForSelector("#target-host-label:has-text('(same-origin)')");
        assertThat(page.locator("#target-host-label").textContent()).contains("(same-origin)");
        assertThat(page.url()).doesNotContain("wiremock=");
        assertThat(page.url()).doesNotContain("strategy=");
    }
}
