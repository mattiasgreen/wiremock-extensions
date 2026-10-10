package com.github.mattiasgreen.wiremock.stateful.ast;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.Objects;

public record AstResponse(
        @JsonProperty("status") int status,
        @JsonProperty("source") AstResponseSource source,
        @JsonProperty("headers") Map<String, String> headers) {

    @JsonCreator
    public AstResponse {
        Objects.requireNonNull(source, "source must not be null");
        if (headers == null) {
            headers = Map.of();
        }
    }
}
