package com.github.mattiasgreen.wiremock.stateful.ast;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

public record AstInvariant(
        @JsonProperty("condition") AstExpression condition,
        @JsonProperty("failStatus") int failStatus,
        @JsonProperty("failMessage") String failMessage) {

    @JsonCreator
    public AstInvariant {
        Objects.requireNonNull(condition, "condition must not be null");
        Objects.requireNonNull(failMessage, "failMessage must not be null");
    }
}
