package com.github.mattiasgreen.wiremock.openapi;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenApiImportResult(int totalStubsCreated, List<StubSummary> stubs, List<String> warnings) {

    public OpenApiImportResult {
        stubs = stubs != null ? List.copyOf(stubs) : List.of();
        warnings = warnings != null ? List.copyOf(warnings) : List.of();
    }

    public record StubSummary(String id, String name, String method, String url, String urlPathTemplate, int status) {}
}
