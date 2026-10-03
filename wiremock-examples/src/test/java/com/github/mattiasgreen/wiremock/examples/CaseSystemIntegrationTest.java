package com.github.mattiasgreen.wiremock.examples;

import com.github.mattiasgreen.wiremock.examples.casemanagement.CaseClient;
import com.github.mattiasgreen.wiremock.examples.casemanagement.CaseLifecycleStubs;
import com.github.mattiasgreen.wiremock.examples.casemanagement.model.*;
import com.github.mattiasgreen.wiremock.ui.UiAdminApiEndpoint;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CaseSystemIntegrationTest {

    private WireMockServer wireMockServer;
    private CaseClient caseClient;
    private Playwright playwright;
    private Browser browser;

    @BeforeAll
    void startAll() {
        wireMockServer = new WireMockServer(
                WireMockConfiguration.options()
                        .dynamicPort()
                        .extensions(new UiAdminApiEndpoint())
        );
        wireMockServer.start();

        caseClient = new CaseClient(wireMockServer.baseUrl());
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    void stopAll() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (wireMockServer != null && wireMockServer.isRunning()) wireMockServer.stop();
    }

    @BeforeEach
    void resetWireMock() {
        wireMockServer.resetAll();
    }

    @Test
    @DisplayName("Single Case: Full lifecycle transitions and state rejection invariants")
    void testSingleCaseLifecycleAndInvariants() {
        String scenarioName = "CaseLifecycle";
        CaseLifecycleStubs.setupSharedCaseScenario(wireMockServer, scenarioName);

        // 1. Create Case: Transitions Started -> OPEN
        CaseDto createdCase = caseClient.createCase("Customer billing error");
        assertThat(createdCase.getId()).isEqualTo("case-100");
        assertThat(createdCase.getStatus()).isEqualTo("OPEN");

        // 2. Perform operations permitted in OPEN state
        CommentDto comment1 = caseClient.addComment("case-100", "Checking ledger transaction");
        assertThat(comment1.getId()).isEqualTo("cmt-1");

        TaskDto task1 = caseClient.addTask("case-100", "Reconcile Stripe balance");
        assertThat(task1.getId()).isEqualTo("tsk-1");

        // 3. Close Case: Transitions OPEN -> CLOSED
        CaseDto closedCase = caseClient.closeCase("case-100");
        assertThat(closedCase.getStatus()).isEqualTo("CLOSED");

        // 4. Invariant Rejection: Adding comments to closed case must be rejected with 409 Conflict
        assertThatThrownBy(() -> caseClient.addComment("case-100", "Late comment"))
                .isInstanceOf(CaseConflictException.class)
                .satisfies(ex -> {
                    CaseConflictException cce = (CaseConflictException) ex;
                    assertThat(cce.getStatusCode()).isEqualTo(409);
                    assertThat(cce.getErrorPayload()).contains("Cannot add comments to closed case");
                });

        // 5. Invariant Rejection: Adding tasks to closed case must be rejected with 409 Conflict
        assertThatThrownBy(() -> caseClient.addTask("case-100", "Late task"))
                .isInstanceOf(CaseConflictException.class)
                .satisfies(ex -> {
                    CaseConflictException cce = (CaseConflictException) ex;
                    assertThat(cce.getStatusCode()).isEqualTo(409);
                    assertThat(cce.getErrorPayload()).contains("Cannot add tasks to closed case");
                });

        // 6. Invariant Rejection: Closing already closed case must be rejected with 400 Bad Request
        assertThatThrownBy(() -> caseClient.closeCase("case-100"))
                .isInstanceOf(CaseConflictException.class)
                .satisfies(ex -> {
                    CaseConflictException cce = (CaseConflictException) ex;
                    assertThat(cce.getStatusCode()).isEqualTo(400);
                    assertThat(cce.getErrorPayload()).contains("Case is already closed");
                });

        // 7. Reopen Case: Transitions CLOSED -> REOPENED
        CaseDto reopenedCase = caseClient.reopenCase("case-100");
        assertThat(reopenedCase.getStatus()).isEqualTo("REOPENED");

        // 8. Permitted in REOPENED: comments work again
        CommentDto comment2 = caseClient.addComment("case-100", "Client provided updated bank statement");
        assertThat(comment2.getId()).isEqualTo("cmt-2");

        // 9. Verify Request Journal
        wireMockServer.verify(1, postRequestedFor(urlEqualTo("/api/v1/cases")));
        wireMockServer.verify(3, postRequestedFor(urlPathMatching("/api/v1/cases/[^/]+/comments")));
        wireMockServer.verify(2, postRequestedFor(urlPathMatching("/api/v1/cases/[^/]+/tasks")));
        wireMockServer.verify(2, postRequestedFor(urlPathMatching("/api/v1/cases/[^/]+/close")));
        wireMockServer.verify(1, postRequestedFor(urlPathMatching("/api/v1/cases/[^/]+/reopen")));
    }

    @Test
    @DisplayName("Parallel Cases Conundrum: Demonstrates global scenario state collision pitfall")
    void testParallelCasesDemonstratingWireMockGlobalScenarioLimitation() {
        String sharedScenario = "SharedGlobalCaseScenario";
        CaseLifecycleStubs.setupSharedCaseScenario(wireMockServer, sharedScenario);

        // Client starts managing Case A and Case B
        CaseDto caseA = caseClient.createCase("Ticket A");
        assertThat(caseA.getStatus()).isEqualTo("OPEN");

        // Both cases are open. Now user completes and closes Case A:
        caseClient.closeCase("case-A");

        // Now user tries to add a comment to Case B.
        // In the real world, Case B is OPEN. But in WireMock's naive shared scenario,
        // the singleton scenario transitioned to CLOSED. Case B's request fails unexpectedly!
        assertThatThrownBy(() -> caseClient.addComment("case-B", "Comment on ticket B"))
                .isInstanceOf(CaseConflictException.class)
                .as("Demonstrates WireMock pain point: Case B fails because Case A shifted the global scenario state")
                .satisfies(ex -> assertThat(((CaseConflictException) ex).getStatusCode()).isEqualTo(409));
    }

    @Test
    @DisplayName("Parallel Cases Solution: Best practice isolated scenarios per entity ID")
    void testParallelCasesBestPracticeWithParameterizedScenarios() {
        // Setup isolated scenario state machines per case ID
        CaseLifecycleStubs.setupIsolatedCaseScenario(wireMockServer, "case-alpha", "Ticket Alpha");
        CaseLifecycleStubs.setupIsolatedCaseScenario(wireMockServer, "case-beta", "Ticket Beta");

        // User works on both cases concurrently
        CaseDto caseAlpha = caseClient.getCase("case-alpha");
        CaseDto caseBeta = caseClient.getCase("case-beta");
        assertThat(caseAlpha.getStatus()).isEqualTo("OPEN");
        assertThat(caseBeta.getStatus()).isEqualTo("OPEN");

        // Close Case Alpha
        caseClient.closeCase("case-alpha");

        // Case Beta can STILL receive comments because its scenario state is isolated!
        CommentDto commentBeta = caseClient.addComment("case-beta", "Still active comment on beta");
        assertThat(commentBeta.getId()).isEqualTo("cmt-case-beta");

        // But Case Alpha rejects comments because it is closed!
        assertThatThrownBy(() -> caseClient.addComment("case-alpha", "Late comment on alpha"))
                .isInstanceOf(CaseConflictException.class)
                .satisfies(ex -> assertThat(((CaseConflictException) ex).getStatusCode()).isEqualTo(409));
    }

    @Test
    @DisplayName("Playwright UI: Verify Scenario DAG visualizer and real-time state synchronization")
    void testScenarioDagVisualizerWithPlaywright() {
        String scenarioName = "VisualCaseLifecycle";
        CaseLifecycleStubs.setupSharedCaseScenario(wireMockServer, scenarioName);

        BrowserContext context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1280, 740));
        Page page = context.newPage();

        try {
            // Open Scenarios tab in WireMock UI
            page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#scenarios");
            page.waitForSelector(".scenario-fsm-card");

            // Verify Scenario Card renders
            assertThat(page.locator(".scenario-name").textContent()).contains("VisualCaseLifecycle");
            assertThat(page.locator(".scenario-state-pill").textContent()).contains("Started");

            // Verify DAG Pipeline flow shows the complete lifecycle progression
            assertThat(page.locator(".fsm-step-name").allTextContents())
                    .contains("Started", "OPEN", "CLOSED", "REOPENED");

            // Client creates a case via HTTP
            caseClient.createCase("Ticket via API");

            // Refresh scenarios in UI
            page.locator("#btn-refresh-scenarios").click();
            page.waitForSelector(".scenario-state-pill:has-text('OPEN')");
            assertThat(page.locator(".fsm-node-card.active .fsm-node-name").textContent()).isEqualTo("OPEN");

            // Client closes the case via HTTP
            caseClient.closeCase("case-100");

            // Refresh scenarios in UI
            page.locator("#btn-refresh-scenarios").click();
            page.waitForSelector(".scenario-state-pill:has-text('CLOSED')");
            assertThat(page.locator(".fsm-node-card.active .fsm-node-name").textContent()).isEqualTo("CLOSED");

        } finally {
            context.close();
        }
    }
}
