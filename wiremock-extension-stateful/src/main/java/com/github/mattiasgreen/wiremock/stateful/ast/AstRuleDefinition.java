package com.github.mattiasgreen.wiremock.stateful.ast;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

public record AstRuleDefinition(
        @JsonProperty("route") AstRoute route,
        @JsonProperty("invariants") List<AstInvariant> invariants,
        @JsonProperty("mutations") List<AstMutation> mutations,
        @JsonProperty("response") AstResponse response) {

    @JsonCreator
    public AstRuleDefinition {
        Objects.requireNonNull(route, "route must not be null");
        if (invariants == null) {
            invariants = List.of();
        }
        if (mutations == null) {
            mutations = List.of();
        }
        Objects.requireNonNull(response, "response must not be null");
    }
}
