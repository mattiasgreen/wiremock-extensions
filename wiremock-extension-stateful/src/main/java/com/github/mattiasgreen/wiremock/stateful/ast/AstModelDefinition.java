package com.github.mattiasgreen.wiremock.stateful.ast;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;

public record AstModelDefinition(
        @JsonProperty("entity") String entity,
        @JsonProperty("idPathParam") String idPathParam,
        @JsonProperty("idPrefix") String idPrefix,
        @JsonProperty("rules") List<AstRuleDefinition> rules) {

    @JsonCreator
    public AstModelDefinition {
        Objects.requireNonNull(entity, "entity must not be null");
        Objects.requireNonNull(idPathParam, "idPathParam must not be null");
        if (idPrefix == null) {
            idPrefix = entity + "-";
        }
        if (rules == null) {
            rules = List.of();
        }
    }
}
