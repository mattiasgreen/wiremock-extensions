package com.github.mattiasgreen.wiremock.examples.casemanagement;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import com.github.tomakehurst.wiremock.WireMockServer;

/**
 * Reusable stub definitions modeling the Case Management System state machine.
 */
public class CaseLifecycleStubs {

    /**
     * Sets up a shared scenario using out-of-the-box WireMock scenario mechanics.
     * Demonstrates state progression: Started -> OPEN -> CLOSED -> REOPENED.
     */
    public static void setupSharedCaseScenario(WireMockServer wireMock, String scenarioName) {
        // --- 1. Creation: Started -> OPEN ---
        wireMock.stubFor(post(urlEqualTo("/api/v1/cases"))
                .withName("Create Case")
                .inScenario(scenarioName)
                .whenScenarioStateIs("Started")
                .willSetStateTo("OPEN")
                .willReturn(created()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\": \"case-100\", \"title\": \"Support Ticket\", \"status\": \"OPEN\"}")));

        // --- 2. In State: OPEN ---
        wireMock.stubFor(get(urlPathMatching("/api/v1/cases/[^/]+"))
                .withName("Get Case (Open)")
                .inScenario(scenarioName)
                .whenScenarioStateIs("OPEN")
                .willReturn(okJson("{\"id\": \"case-100\", \"title\": \"Support Ticket\", \"status\": \"OPEN\"}")));

        wireMock.stubFor(post(urlPathMatching("/api/v1/cases/[^/]+/comments"))
                .withName("Add Comment (Open)")
                .inScenario(scenarioName)
                .whenScenarioStateIs("OPEN")
                .willReturn(created()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\": \"cmt-1\", \"caseId\": \"case-100\", \"text\": \"First comment\"}")));

        wireMock.stubFor(
                post(urlPathMatching("/api/v1/cases/[^/]+/tasks"))
                        .withName("Add Task (Open)")
                        .inScenario(scenarioName)
                        .whenScenarioStateIs("OPEN")
                        .willReturn(
                                created()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"id\": \"tsk-1\", \"caseId\": \"case-100\", \"description\": \"Investigate logs\", \"completed\": false}")));

        // Transition: OPEN -> CLOSED
        wireMock.stubFor(post(urlPathMatching("/api/v1/cases/[^/]+/close"))
                .withName("Close Case")
                .inScenario(scenarioName)
                .whenScenarioStateIs("OPEN")
                .willSetStateTo("CLOSED")
                .willReturn(okJson("{\"id\": \"case-100\", \"status\": \"CLOSED\"}")));

        // --- 3. In State: CLOSED ---
        wireMock.stubFor(get(urlPathMatching("/api/v1/cases/[^/]+"))
                .withName("Get Case (Closed)")
                .inScenario(scenarioName)
                .whenScenarioStateIs("CLOSED")
                .willReturn(okJson("{\"id\": \"case-100\", \"title\": \"Support Ticket\", \"status\": \"CLOSED\"}")));

        // Rejection Invariants in CLOSED
        wireMock.stubFor(post(urlPathMatching("/api/v1/cases/[^/]+/comments"))
                .withName("Reject Comment on Closed Case")
                .inScenario(scenarioName)
                .whenScenarioStateIs("CLOSED")
                .willReturn(status(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"CONFLICT\", \"message\": \"Cannot add comments to closed case\"}")));

        wireMock.stubFor(post(urlPathMatching("/api/v1/cases/[^/]+/tasks"))
                .withName("Reject Task on Closed Case")
                .inScenario(scenarioName)
                .whenScenarioStateIs("CLOSED")
                .willReturn(status(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"CONFLICT\", \"message\": \"Cannot add tasks to closed case\"}")));

        wireMock.stubFor(post(urlPathMatching("/api/v1/cases/[^/]+/close"))
                .withName("Reject Closing Already Closed Case")
                .inScenario(scenarioName)
                .whenScenarioStateIs("CLOSED")
                .willReturn(status(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"BAD_REQUEST\", \"message\": \"Case is already closed\"}")));

        // Transition: CLOSED -> REOPENED
        wireMock.stubFor(post(urlPathMatching("/api/v1/cases/[^/]+/reopen"))
                .withName("Reopen Case")
                .inScenario(scenarioName)
                .whenScenarioStateIs("CLOSED")
                .willSetStateTo("REOPENED")
                .willReturn(okJson("{\"id\": \"case-100\", \"status\": \"REOPENED\"}")));

        // --- 4. In State: REOPENED ---
        wireMock.stubFor(get(urlPathMatching("/api/v1/cases/[^/]+"))
                .withName("Get Case (Reopened)")
                .inScenario(scenarioName)
                .whenScenarioStateIs("REOPENED")
                .willReturn(okJson("{\"id\": \"case-100\", \"title\": \"Support Ticket\", \"status\": \"REOPENED\"}")));

        wireMock.stubFor(
                post(urlPathMatching("/api/v1/cases/[^/]+/comments"))
                        .withName("Add Comment (Reopened)")
                        .inScenario(scenarioName)
                        .whenScenarioStateIs("REOPENED")
                        .willReturn(
                                created()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "{\"id\": \"cmt-2\", \"caseId\": \"case-100\", \"text\": \"Reopened investigation\"}")));

        wireMock.stubFor(post(urlPathMatching("/api/v1/cases/[^/]+/close"))
                .withName("Close Reopened Case")
                .inScenario(scenarioName)
                .whenScenarioStateIs("REOPENED")
                .willSetStateTo("CLOSED")
                .willReturn(okJson("{\"id\": \"case-100\", \"status\": \"CLOSED\"}")));
    }

    /**
     * Best Practice: Scopes the scenario name by unique entity ID (caseId).
     * Prevents cross-test and cross-entity scenario collision in parallel tests.
     */
    public static void setupIsolatedCaseScenario(WireMockServer wireMock, String caseId, String title) {
        String scenarioName = "CaseLifecycle-" + caseId;

        // Creation / initial stub
        wireMock.stubFor(get(urlEqualTo("/api/v1/cases/" + caseId))
                .withName("Get Isolated Case " + caseId + " (Open)")
                .inScenario(scenarioName)
                .whenScenarioStateIs("Started")
                .willReturn(
                        okJson("{\"id\": \"" + caseId + "\", \"title\": \"" + title + "\", \"status\": \"OPEN\"}")));

        wireMock.stubFor(post(urlEqualTo("/api/v1/cases/" + caseId + "/comments"))
                .withName("Add Comment to Isolated Case " + caseId)
                .inScenario(scenarioName)
                .whenScenarioStateIs("Started")
                .willReturn(created()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\": \"cmt-" + caseId + "\", \"caseId\": \"" + caseId
                                + "\", \"text\": \"Comment for " + caseId + "\"}")));

        // Transition: Started (Open) -> CLOSED
        wireMock.stubFor(post(urlEqualTo("/api/v1/cases/" + caseId + "/close"))
                .withName("Close Isolated Case " + caseId)
                .inScenario(scenarioName)
                .whenScenarioStateIs("Started")
                .willSetStateTo("CLOSED")
                .willReturn(okJson("{\"id\": \"" + caseId + "\", \"status\": \"CLOSED\"}")));

        // Rejection in CLOSED
        wireMock.stubFor(post(urlEqualTo("/api/v1/cases/" + caseId + "/comments"))
                .withName("Reject Comment on Isolated Closed Case " + caseId)
                .inScenario(scenarioName)
                .whenScenarioStateIs("CLOSED")
                .willReturn(status(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"CONFLICT\", \"message\": \"Cannot add comments to closed case "
                                + caseId + "\"}")));
    }
}
