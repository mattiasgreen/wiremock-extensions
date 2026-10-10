package com.github.mattiasgreen.wiremock.stateful.ast;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

public record AstRoute(@JsonProperty("method") String method, @JsonProperty("pathPattern") String pathPattern) {

    @JsonCreator
    public AstRoute {
        Objects.requireNonNull(method, "method must not be null");
        Objects.requireNonNull(pathPattern, "pathPattern must not be null");
    }
}
