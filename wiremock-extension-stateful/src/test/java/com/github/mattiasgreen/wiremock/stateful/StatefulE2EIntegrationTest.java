package com.github.mattiasgreen.wiremock.stateful;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.mattiasgreen.wiremock.stateful.ast.AstJson;
import com.github.mattiasgreen.wiremock.stateful.ast.AstModelDefinition;
import com.github.mattiasgreen.wiremock.stateful.client.RemoteStateClient;
import com.github.mattiasgreen.wiremock.stateful.dsl.*;
import com.github.mattiasgreen.wiremock.stateful.extension.StatefulExtensionFactory;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class StatefulE2EIntegrationTest {

    private WireMockServer wireMockServer;
    private RemoteStateClient stateClient;
    private HttpClient httpClient;
    private AstModelDefinition caseModel;

    @BeforeAll
    void startServer() {
        wireMockServer = new WireMockServer(
                WireMockConfiguration.options().dynamicPort().extensions(new StatefulExtensionFactory()));
        wireMockServer.start();

        stateClient = new RemoteStateClient(wireMockServer.baseUrl());
        httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        // Define Case Management DSL model
        caseModel = SimulatorModel.forEntity("cases")
                .idFromPath("/api/v1/cases/{caseId}")
                .onPost("/api/v1/cases")
                .initialState(Map.of("status", "OPEN", "tasks", List.of()))
                .respondWith(201, ResponseSource.entity())
                .onPost("/api/v1/cases/{caseId}/tasks")
                .require(Expr.field("status").eq("OPEN"), 409, "Cannot add tasks to closed case")
                .mutate(Action.appendToList("tasks", Source.requestBodyWithGeneratedId("id", "tsk-")))
                .respondWith(201, ResponseSource.lastAppendedItem("tasks"))
                .onGet("/api/v1/cases/{caseId}/tasks")
                .respondWith(200, ResponseSource.field("tasks"))
                .onPut("/api/v1/cases/{caseId}/tasks/{taskId}/complete")
                .mutate(Action.updateListItem("tasks", "id", "taskId", "status", "COMPLETED"))
                .respondWith(200, ResponseSource.updatedItem("tasks", "id", "taskId"))
                .onPost("/api/v1/cases/{caseId}/close")
                .require(Expr.field("status").eq("OPEN"), 400, "Case is already closed")
                .require(
                        Expr.list("tasks").allMatch(Expr.field("status").eq("COMPLETED")),
                        409,
                        "Cannot close case with pending tasks")
                .mutate(Action.setField("status", "CLOSED"))
                .respondWith(200, ResponseSource.entity())
                .build();
    }

    @AfterAll
    void stopServer() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void resetWireMock() {
        wireMockServer.resetAll();
    }

    @Test
    @DisplayName("Remote E2E: Push AST over HTTP Admin API, verify full stateful lifecycle & journal")
    void testRemoteE2ELifecycle() throws Exception {
        String correlationId = "corr-lifecycle-1";

        // 1. Remote Test System instruments WireMock with the AST model
        stateClient.pushModel(correlationId, caseModel);

        // 2. Client makes HTTP call: Create Case
        HttpResponse<String> createResp =
                sendPost("/api/v1/cases", "{\"title\":\"Invoice reconciliation\"}", correlationId);
        assertThat(createResp.statusCode()).isEqualTo(201);
        JsonNode caseJson = AstJson.readTree(createResp.body());
        String caseId = caseJson.get("id").asText();
        assertThat(caseJson.get("status").asText()).isEqualTo("OPEN");

        // 3. Client adds tasks
        HttpResponse<String> task1Resp = sendPost(
                "/api/v1/cases/" + caseId + "/tasks",
                "{\"title\":\"Check bank feed\",\"status\":\"PENDING\"}",
                correlationId);
        assertThat(task1Resp.statusCode()).isEqualTo(201);
        JsonNode task1 = AstJson.readTree(task1Resp.body());
        String task1Id = task1.get("id").asText();

        HttpResponse<String> task2Resp = sendPost(
                "/api/v1/cases/" + caseId + "/tasks",
                "{\"title\":\"Verify receipt\",\"status\":\"PENDING\"}",
                correlationId);
        assertThat(task2Resp.statusCode()).isEqualTo(201);
        JsonNode task2 = AstJson.readTree(task2Resp.body());
        String task2Id = task2.get("id").asText();

        // 4. List Tasks -> List has dynamically grown to 2 items!
        HttpResponse<String> listResp = sendGet("/api/v1/cases/" + caseId + "/tasks", correlationId);
        assertThat(listResp.statusCode()).isEqualTo(200);
        JsonNode tasksArray = AstJson.readTree(listResp.body());
        assertThat(tasksArray.size()).isEqualTo(2);

        // 5. Invariant enforcement: premature close returns 409 Conflict
        HttpResponse<String> badClose = sendPost("/api/v1/cases/" + caseId + "/close", "", correlationId);
        assertThat(badClose.statusCode()).isEqualTo(409);
        assertThat(badClose.body()).contains("Cannot close case with pending tasks");

        // 6. Complete both tasks
        HttpResponse<String> comp1 =
                sendPut("/api/v1/cases/" + caseId + "/tasks/" + task1Id + "/complete", "", correlationId);
        assertThat(comp1.statusCode()).isEqualTo(200);
        HttpResponse<String> comp2 =
                sendPut("/api/v1/cases/" + caseId + "/tasks/" + task2Id + "/complete", "", correlationId);
        assertThat(comp2.statusCode()).isEqualTo(200);

        // 7. Close Case succeeds
        HttpResponse<String> goodClose = sendPost("/api/v1/cases/" + caseId + "/close", "", correlationId);
        assertThat(goodClose.statusCode()).isEqualTo(200);
        JsonNode closedCase = AstJson.readTree(goodClose.body());
        assertThat(closedCase.get("status").asText()).isEqualTo("CLOSED");

        // 8. WireMock Journal verification still works seamlessly!
        wireMockServer.verify(
                1,
                postRequestedFor(urlEqualTo("/api/v1/cases")).withHeader("x-correlation-id", equalTo(correlationId)));
        wireMockServer.verify(
                2,
                postRequestedFor(urlPathMatching("/api/v1/cases/[^/]+/tasks"))
                        .withHeader("x-correlation-id", equalTo(correlationId)));
        wireMockServer.verify(
                2,
                postRequestedFor(urlPathMatching("/api/v1/cases/[^/]+/close"))
                        .withHeader("x-correlation-id", equalTo(correlationId)));

        // 9. Inspect live entity state via Admin API
        String liveState = stateClient.getEntity(correlationId, "cases", caseId);
        assertThat(liveState).contains("\"status\":\"CLOSED\"");

        // 10. Clean up session
        stateClient.clearSession(correlationId);
        assertThat(stateClient.getEntity(correlationId, "cases", caseId)).isNull();
    }

    @Test
    @DisplayName("Parallel Collision Solved: Two test runs with different correlation IDs never collide")
    void testParallelCorrelationIsolation() throws Exception {
        String corrA = "test-scenario-A";
        String corrB = "test-scenario-B";

        stateClient.pushModel(corrA, caseModel);
        stateClient.pushModel(corrB, caseModel);

        // Scenario A creates a case
        HttpResponse<String> respA = sendPost("/api/v1/cases", "{\"title\":\"Case for A\"}", corrA);
        String caseAId = AstJson.readTree(respA.body()).get("id").asText();

        // Scenario B creates a case
        HttpResponse<String> respB = sendPost("/api/v1/cases", "{\"title\":\"Case for B\"}", corrB);
        String caseBId = AstJson.readTree(respB.body()).get("id").asText();

        // Both are OPEN. Now Scenario A closes its case!
        HttpResponse<String> closeA = sendPost("/api/v1/cases/" + caseAId + "/close", "", corrA);
        assertThat(closeA.statusCode()).isEqualTo(200);

        // Scenario A cannot add tasks anymore (409)
        HttpResponse<String> taskA = sendPost("/api/v1/cases/" + caseAId + "/tasks", "{\"title\":\"Late A\"}", corrA);
        assertThat(taskA.statusCode()).isEqualTo(409);

        // Scenario B's case is STILL OPEN and completely unaffected by Scenario A!
        HttpResponse<String> taskB = sendPost(
                "/api/v1/cases/" + caseBId + "/tasks", "{\"title\":\"Task B1\",\"status\":\"PENDING\"}", corrB);
        assertThat(taskB.statusCode()).isEqualTo(201);

        HttpResponse<String> listB = sendGet("/api/v1/cases/" + caseBId + "/tasks", corrB);
        assertThat(listB.statusCode()).isEqualTo(200);
        assertThat(AstJson.readTree(listB.body()).size()).isEqualTo(1);
    }

    private HttpResponse<String> sendPost(String path, String body, String correlationId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(wireMockServer.baseUrl() + path))
                .header("Content-Type", "application/json")
                .header("x-correlation-id", correlationId)
                .POST(HttpRequest.BodyPublishers.ofString(body != null ? body : ""))
                .timeout(Duration.ofSeconds(5))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendPut(String path, String body, String correlationId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(wireMockServer.baseUrl() + path))
                .header("Content-Type", "application/json")
                .header("x-correlation-id", correlationId)
                .PUT(HttpRequest.BodyPublishers.ofString(body != null ? body : ""))
                .timeout(Duration.ofSeconds(5))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendGet(String path, String correlationId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(wireMockServer.baseUrl() + path))
                .header("x-correlation-id", correlationId)
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
