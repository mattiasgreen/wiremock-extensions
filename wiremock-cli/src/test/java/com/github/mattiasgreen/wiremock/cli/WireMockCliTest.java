package com.github.mattiasgreen.wiremock.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WireMockCliTest {

    @Test
    @DisplayName("MiniJson: correctly parses JSON objects, arrays, escapes, and numbers")
    void shouldParseJsonAccurately() {
        String json =
                """
        {
            "mappings": [
                {
                    "id": "123e4567-e89b-12d3-a456-426614174000",
                    "name": "Get Cases \\\"Special\\\"",
                    "request": {
                        "method": "GET",
                        "url": "/api/v1/cases"
                    },
                    "response": {
                        "status": 200,
                        "body": "{\\"count\\": 42}"
                    }
                }
            ],
            "total": 1,
            "active": true
        }
        """;

        Object parsed = WireMockCli.MiniJson.parse(json);
        assertThat(parsed).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        assertThat(map.get("total")).isEqualTo(1L);
        assertThat(map.get("active")).isEqualTo(Boolean.TRUE);

        @SuppressWarnings("unchecked")
        List<Object> mappings = (List<Object>) map.get("mappings");
        assertThat(mappings).hasSize(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) mappings.get(0);
        assertThat(first.get("name")).isEqualTo("Get Cases \"Special\"");

        @SuppressWarnings("unchecked")
        Map<String, Object> req = (Map<String, Object>) first.get("request");
        assertThat(req.get("method")).isEqualTo("GET");
        assertThat(req.get("url")).isEqualTo("/api/v1/cases");

        @SuppressWarnings("unchecked")
        Map<String, Object> res = (Map<String, Object>) first.get("response");
        assertThat(res.get("status")).isEqualTo(200L);
    }

    @Test
    @DisplayName("QueryEvaluator: evaluates method, path, and boolean AND/OR expressions on stubs")
    void shouldEvaluateStubExpressions() {
        Map<String, Object> stub1 = Map.of(
                "request", Map.of("method", "GET", "url", "/api/v1/cases"),
                "response", Map.of("status", 200L),
                "name", "List Cases");

        Map<String, Object> stub2 = Map.of(
                "request", Map.of("method", "POST", "url", "/api/v1/cases"),
                "response", Map.of("status", 201L),
                "name", "Create Case");

        Map<String, Object> disabledStub = Map.of(
                "request",
                Map.of("method", "GET", "url", "/api/v1/health"),
                "response",
                Map.of("status", 200L),
                "name",
                "Health Check",
                "_disabled",
                Boolean.TRUE);

        // Simple matchers
        assertThat(WireMockCli.QueryEvaluator.matchesStub("cases", stub1)).isTrue();
        assertThat(WireMockCli.QueryEvaluator.matchesStub("GET", stub1)).isTrue();
        assertThat(WireMockCli.QueryEvaluator.matchesStub("POST", stub1)).isFalse();

        // AND expressions
        assertThat(WireMockCli.QueryEvaluator.matchesStub("GET and /cases", stub1))
                .isTrue();
        assertThat(WireMockCli.QueryEvaluator.matchesStub("POST and /cases", stub1))
                .isFalse();
        assertThat(WireMockCli.QueryEvaluator.matchesStub("POST and /cases", stub2))
                .isTrue();

        // OR expressions
        assertThat(WireMockCli.QueryEvaluator.matchesStub("GET or DELETE", stub1))
                .isTrue();
        assertThat(WireMockCli.QueryEvaluator.matchesStub("PUT or DELETE", stub1))
                .isFalse();

        // NOT expressions
        assertThat(WireMockCli.QueryEvaluator.matchesStub("not POST", stub1)).isTrue();
        assertThat(WireMockCli.QueryEvaluator.matchesStub("not GET", stub1)).isFalse();

        // Status filters
        assertThat(WireMockCli.QueryEvaluator.matchesStub("status=201", stub2)).isTrue();
        assertThat(WireMockCli.QueryEvaluator.matchesStub("status=200", stub2)).isFalse();
        assertThat(WireMockCli.QueryEvaluator.matchesStub("status=disabled", disabledStub))
                .isTrue();
        assertThat(WireMockCli.QueryEvaluator.matchesStub("status=active", disabledStub))
                .isFalse();
        assertThat(WireMockCli.QueryEvaluator.matchesStub("status=active", stub1))
                .isTrue();

        // Regex path filter
        assertThat(WireMockCli.QueryEvaluator.matchesStub("path~/api/v[0-9]+/cases", stub1))
                .isTrue();
        assertThat(WireMockCli.QueryEvaluator.matchesStub("path~/orders", stub1))
                .isFalse();
    }

    @Test
    @DisplayName("ConsoleFormatter: respects NO_COLOR / disabled colors")
    void shouldFormatWithoutAnsiWhenColorsDisabled() {
        WireMockCli.ConsoleFormatter fmt = new WireMockCli.ConsoleFormatter(false);

        assertThat(fmt.bold("test")).isEqualTo("test");
        assertThat(fmt.green("ok")).isEqualTo("ok");
        assertThat(fmt.red("error")).isEqualTo("error");
        assertThat(fmt.statusBadge("200")).isEqualTo("200");
        assertThat(fmt.statusBadge("404")).isEqualTo("404");
        assertThat(fmt.methodBadge("GET")).isEqualTo("GET    ");
    }

    @Test
    @DisplayName("ConsoleFormatter: applies ANSI color codes when enabled")
    void shouldFormatWithAnsiWhenColorsEnabled() {
        WireMockCli.ConsoleFormatter fmt = new WireMockCli.ConsoleFormatter(true);

        assertThat(fmt.bold("test")).contains("\u001B[1m");
        assertThat(fmt.green("ok")).contains("\u001B[32m");
        assertThat(fmt.red("error")).contains("\u001B[31m");
        assertThat(fmt.statusBadge("200")).contains("\u001B[32m");
        assertThat(fmt.statusBadge("404")).contains("\u001B[31m");
        assertThat(fmt.methodBadge("GET")).contains("\u001B[36m");
        assertThat(fmt.methodBadge("POST")).contains("\u001B[32m");
    }
}
