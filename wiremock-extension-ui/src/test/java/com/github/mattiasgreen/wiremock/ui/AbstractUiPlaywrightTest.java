package com.github.mattiasgreen.wiremock.ui;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import com.github.mattiasgreen.wiremock.stateful.ast.AstModelDefinition;
import com.github.mattiasgreen.wiremock.stateful.dsl.*;
import com.github.mattiasgreen.wiremock.stateful.engine.StateEngine;
import com.github.mattiasgreen.wiremock.stateful.extension.StatefulAdminEndpoint;
import com.github.mattiasgreen.wiremock.stateful.extension.StatefulResponseTransformer;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.microsoft.playwright.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;

/**
 * Shared Playwright + WireMock test harness reusing a single headless Chromium browser,
 * BrowserContext, Page, and WireMockServer across all UI E2E domain test suites.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractUiPlaywrightTest {

    protected static WireMockServer wireMockServer;
    protected static StateEngine stateEngine;
    protected static DisabledStubStore disabledStubStore;
    protected static Playwright playwright;
    protected static Browser browser;
    protected static BrowserContext context;
    protected static Page page;

    protected static final List<String> consoleLogs = Collections.synchronizedList(new ArrayList<>());
    protected static final List<String> consoleErrors = Collections.synchronizedList(new ArrayList<>());
    protected static final List<String> pageErrors = Collections.synchronizedList(new ArrayList<>());
    protected static final List<String> httpErrors = Collections.synchronizedList(new ArrayList<>());

    private static boolean initialized = false;

    static synchronized Browser getSharedBrowser() {
        if (browser == null) {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    if (context != null) context.close();
                } catch (Exception ignored) {
                }
                try {
                    if (browser != null) browser.close();
                } catch (Exception ignored) {
                }
                try {
                    if (playwright != null) playwright.close();
                } catch (Exception ignored) {
                }
                try {
                    if (wireMockServer != null && wireMockServer.isRunning()) wireMockServer.stop();
                } catch (Exception ignored) {
                }
            }));
        }
        return browser;
    }

    @BeforeAll
    synchronized void ensureSharedEnvironmentStarted() {
        if (initialized) {
            return;
        }
        stateEngine = new StateEngine();
        disabledStubStore = new DisabledStubStore();
        wireMockServer = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .extensions(
                        new UiAdminApiEndpoint(),
                        new StubLifecycleAdminEndpoint(disabledStubStore),
                        new com.github.mattiasgreen.wiremock.openapi.OpenApiAdminEndpoint(),
                        new StatefulAdminEndpoint(stateEngine),
                        new StatefulResponseTransformer(stateEngine),
                        new com.github.mattiasgreen.wiremock.netem.admin.TcpNetEmAdminEndpoint(),
                        new com.github.mattiasgreen.wiremock.casual.CasualAdminEndpoint()));
        wireMockServer.start();
        resetDefaultStubs();

        browser = getSharedBrowser();
        initialized = true;
    }

    @BeforeEach
    void setUpEach() {
        consoleLogs.clear();
        consoleErrors.clear();
        pageErrors.clear();
        httpErrors.clear();

        context = browser.newContext(
                new Browser.NewContextOptions().setPermissions(List.of("clipboard-read", "clipboard-write")));
        page = context.newPage();

        page.onConsoleMessage(msg -> {
            String text = "[" + msg.type() + "] " + msg.text();
            consoleLogs.add(text);
            if ("error".equalsIgnoreCase(msg.type()) && !msg.text().contains("404 (Not Found)")) {
                System.err.println("BROWSER CONSOLE ERROR: " + msg.text());
                consoleErrors.add(msg.text());
            }
        });

        page.onPageError(error -> {
            System.err.println("BROWSER UNCAUGHT ERROR: " + error);
            pageErrors.add(error);
        });

        page.onResponse(response -> {
            if (response.status() >= 400
                    && !response.url().contains("favicon.ico")
                    && !response.url().contains("nonexistent")
                    && !response.url().contains("/__admin/tcp-netem/")
                    && !response.url().contains("/__admin/metrics/")
                    && !response.url().contains("/__admin/stateful/")
                    && !response.url().contains("/__admin/stubs/disabled")) {
                System.err.println("HTTP ERROR RESPONSE: " + response.status() + " " + response.url());
                httpErrors.add(response.status() + " " + response.url());
            }
        });
    }

    @AfterEach
    void tearDownEach() {
        if (context != null) {
            context.close();
            context = null;
        }
        resetDefaultStubs();
    }

    protected static void resetDefaultStubs() {
        if (stateEngine != null) {
            stateEngine.clearAll();
        }
        if (disabledStubStore != null) {
            disabledStubStore.clear();
        }
        wireMockServer.resetAll();
        wireMockServer.stubFor(
                get(urlEqualTo("/__admin/metrics/prometheus")).willReturn(ok("# HELP wiremock_requests_total\n")));
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/users"))
                .withName("List Users API")
                .willReturn(okJson("{\"users\": [{\"id\": 1, \"name\": \"Alice\"}, {\"id\": 2, \"name\": \"Bob\"}]}")));

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/orders"))
                .withName("Create Order API")
                .withRequestBody(matchingJsonPath("$.item"))
                .willReturn(created()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"orderId\": \"ORD-99\", \"status\": \"CREATED\"}")));

        wireMockServer.stubFor(get(urlEqualTo("/favicon.ico")).willReturn(ok().withBody(new byte[0])));

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/cases"))
                .withName("Create Case")
                .inScenario("Case-FSM")
                .whenScenarioStateIs("Started")
                .willSetStateTo("OPEN")
                .willReturn(
                        created().withHeader("Content-Type", "application/json").withBody("{\"status\": \"OPEN\"}")));

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/cases/1/close"))
                .withName("Close Case")
                .inScenario("Case-FSM")
                .whenScenarioStateIs("OPEN")
                .willSetStateTo("CLOSED")
                .willReturn(okJson("{\"status\": \"CLOSED\"}")));
    }

    protected AstModelDefinition createCasesModel() {
        return SimulatorModel.forEntity("cases")
                .idPathParam("caseId")
                .idPrefix("case-")
                .onPost("/api/v1/cases")
                .initialState(Map.of("status", "OPEN", "tasks", List.of()))
                .respondWith(201, ResponseSource.entity())
                .onGet("/api/v1/cases/{caseId}")
                .respondWith(200, ResponseSource.entity())
                .onPost("/api/v1/cases/{caseId}/tasks")
                .mutate(Action.appendToList("tasks", Source.requestBodyWithGeneratedId("id", "task-")))
                .respondWith(201, ResponseSource.entity())
                .onPut("/api/v1/cases/{caseId}/close")
                .require(
                        Expr.list("tasks").allMatch(Expr.field("status").notEq("PENDING")),
                        409,
                        "Cannot close case: pending tasks exist")
                .mutate(Action.setField("status", "CLOSED"))
                .respondWith(200, ResponseSource.entity())
                .build();
    }

    // =========================================================================
    // UI Domain Driver Seam: High-level operations over raw DOM selectors/sleeps
    // =========================================================================

    protected void openUi() {
        openUi("");
    }

    protected void openUi(String pathOrHash) {
        String target = wireMockServer.baseUrl() + "/__admin/ui/" + (pathOrHash != null ? pathOrHash : "");
        page.navigate(target);
        page.waitForSelector("#stub-list");
    }

    protected void switchTab(String tabId) {
        String navSelector = "#nav-" + tabId.replace("tab-", "tab-");
        if (!navSelector.startsWith("#nav-tab-")) {
            navSelector = "#nav-tab-" + tabId.replace("tab-", "");
        }
        page.locator(navSelector).click();
        page.waitForSelector("#" + tabId + ".active");
    }

    protected void filterStubs(String query) {
        page.locator("#search-box").fill(query);
    }

    protected void selectStubCard(String textMatch) {
        page.locator(".stub-card:has-text('" + textMatch + "')").first().click();
    }

    protected void selectProject(String projectName) {
        page.locator("#project-filter-select").selectOption(projectName);
    }

    protected void selectStatusFilter(String status) {
        page.locator("#btn-filter-" + status).click();
    }

    protected void openJournalTab() {
        switchTab("tab-journal");
        page.waitForSelector("#journal-table");
    }

    protected void filterJournal(String query) {
        page.locator("#journal-search").fill(query);
    }
}
