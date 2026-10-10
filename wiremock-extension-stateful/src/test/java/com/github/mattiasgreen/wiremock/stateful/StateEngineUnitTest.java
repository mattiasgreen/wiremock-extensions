package com.github.mattiasgreen.wiremock.stateful;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.mattiasgreen.wiremock.stateful.ast.AstJson;
import com.github.mattiasgreen.wiremock.stateful.ast.AstModelDefinition;
import com.github.mattiasgreen.wiremock.stateful.dsl.*;
import com.github.mattiasgreen.wiremock.stateful.engine.StateEngine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class StateEngineUnitTest {

    @Test
    @DisplayName("Pure Java Unit Test: Case & Tasks lifecycle without HTTP overhead")
    void testCaseAndTasksLifecyclePureJava() throws Exception {
        StateEngine engine = new StateEngine();

        AstModelDefinition caseModel = SimulatorModel.forEntity("cases")
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

        engine.registerGlobalModel(caseModel);

        // 1. Create Case
        StateEngine.ExecutionResult createResult =
                engine.execute("test-corr-1", "POST", "/api/v1/cases", "{\"title\":\"Payment dispute\"}", Map.of());
        assertThat(createResult.status()).isEqualTo(201);
        JsonNode caseJson = AstJson.readTree(createResult.body());
        String caseId = caseJson.get("id").asText();
        assertThat(caseJson.get("status").asText()).isEqualTo("OPEN");

        // 2. Add Task 1
        StateEngine.ExecutionResult task1Result = engine.execute(
                "test-corr-1",
                "POST",
                "/api/v1/cases/" + caseId + "/tasks",
                "{\"title\":\"Review ledger\",\"status\":\"PENDING\"}",
                Map.of());
        assertThat(task1Result.status()).isEqualTo(201);
        JsonNode task1Json = AstJson.readTree(task1Result.body());
        String task1Id = task1Json.get("id").asText();
        assertThat(task1Id).startsWith("tsk-");

        // 3. Add Task 2
        StateEngine.ExecutionResult task2Result = engine.execute(
                "test-corr-1",
                "POST",
                "/api/v1/cases/" + caseId + "/tasks",
                "{\"title\":\"Contact customer\",\"status\":\"PENDING\"}",
                Map.of());
        assertThat(task2Result.status()).isEqualTo(201);
        JsonNode task2Json = AstJson.readTree(task2Result.body());
        String task2Id = task2Json.get("id").asText();

        // 4. List Tasks -> List has grown to 2 items!
        StateEngine.ExecutionResult listResult =
                engine.execute("test-corr-1", "GET", "/api/v1/cases/" + caseId + "/tasks", null, Map.of());
        assertThat(listResult.status()).isEqualTo(200);
        JsonNode tasksArray = AstJson.readTree(listResult.body());
        assertThat(tasksArray.isArray()).isTrue();
        assertThat(tasksArray.size()).isEqualTo(2);

        // 5. Attempt Close -> Must fail with 409 because tasks are PENDING!
        StateEngine.ExecutionResult prematureClose =
                engine.execute("test-corr-1", "POST", "/api/v1/cases/" + caseId + "/close", null, Map.of());
        assertThat(prematureClose.status()).isEqualTo(409);
        assertThat(prematureClose.body()).contains("Cannot close case with pending tasks");

        // 6. Complete Task 1
        StateEngine.ExecutionResult complete1 = engine.execute(
                "test-corr-1", "PUT", "/api/v1/cases/" + caseId + "/tasks/" + task1Id + "/complete", null, Map.of());
        assertThat(complete1.status()).isEqualTo(200);

        // Still one pending task -> Close still fails with 409
        StateEngine.ExecutionResult partialClose =
                engine.execute("test-corr-1", "POST", "/api/v1/cases/" + caseId + "/close", null, Map.of());
        assertThat(partialClose.status()).isEqualTo(409);

        // 7. Complete Task 2
        StateEngine.ExecutionResult complete2 = engine.execute(
                "test-corr-1", "PUT", "/api/v1/cases/" + caseId + "/tasks/" + task2Id + "/complete", null, Map.of());
        assertThat(complete2.status()).isEqualTo(200);

        // 8. Close Case -> Now succeeds with 200 and status CLOSED!
        StateEngine.ExecutionResult finalClose =
                engine.execute("test-corr-1", "POST", "/api/v1/cases/" + caseId + "/close", null, Map.of());
        assertThat(finalClose.status()).isEqualTo(200);
        JsonNode closedCaseJson = AstJson.readTree(finalClose.body());
        assertThat(closedCaseJson.get("status").asText()).isEqualTo("CLOSED");

        // 9. Invariant Rejection: Adding tasks to CLOSED case fails with 409!
        StateEngine.ExecutionResult lateTask = engine.execute(
                "test-corr-1", "POST", "/api/v1/cases/" + caseId + "/tasks", "{\"title\":\"Late task\"}", Map.of());
        assertThat(lateTask.status()).isEqualTo(409);
        assertThat(lateTask.body()).contains("Cannot add tasks to closed case");
    }
}
