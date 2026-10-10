package com.github.mattiasgreen.wiremock.stateful.dsl;

import com.github.mattiasgreen.wiremock.stateful.ast.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SimulatorModel {

    private SimulatorModel() {}

    public static Builder forEntity(String entity) {
        return new Builder(entity);
    }

    public static final class Builder {
        private final String entity;
        private String idPathParam = "id";
        private String idPrefix;
        private final List<AstRuleDefinition> rules = new ArrayList<>();

        private Builder(String entity) {
            this.entity = Objects.requireNonNull(entity, "entity must not be null");
            this.idPrefix = entity + "-";
        }

        public Builder idPathParam(String paramName) {
            this.idPathParam = Objects.requireNonNull(paramName, "idPathParam must not be null");
            return this;
        }

        public Builder idPrefix(String prefix) {
            this.idPrefix = Objects.requireNonNull(prefix, "idPrefix must not be null");
            return this;
        }

        public Builder idFromPath(String pathPatternWithParam) {
            Pattern pattern = Pattern.compile("\\{([^}]+)\\}");
            Matcher matcher = pattern.matcher(pathPatternWithParam);
            if (matcher.find()) {
                this.idPathParam = matcher.group(1);
            }
            return this;
        }

        public RuleBuilder onPost(String pathPattern) {
            return new RuleBuilder(this, "POST", pathPattern);
        }

        public RuleBuilder onGet(String pathPattern) {
            return new RuleBuilder(this, "GET", pathPattern);
        }

        public RuleBuilder onPut(String pathPattern) {
            return new RuleBuilder(this, "PUT", pathPattern);
        }

        public RuleBuilder onDelete(String pathPattern) {
            return new RuleBuilder(this, "DELETE", pathPattern);
        }

        void addRule(AstRuleDefinition rule) {
            this.rules.add(rule);
        }

        public AstModelDefinition build() {
            return new AstModelDefinition(entity, idPathParam, idPrefix, List.copyOf(rules));
        }
    }

    public static final class RuleBuilder {
        private final Builder parent;
        private final String method;
        private final String pathPattern;
        private final List<AstInvariant> invariants = new ArrayList<>();
        private final List<AstMutation> mutations = new ArrayList<>();
        private AstResponse response;

        RuleBuilder(Builder parent, String method, String pathPattern) {
            this.parent = Objects.requireNonNull(parent, "parent must not be null");
            this.method = Objects.requireNonNull(method, "method must not be null");
            this.pathPattern = Objects.requireNonNull(pathPattern, "pathPattern must not be null");
        }

        public RuleBuilder require(AstExpression condition, int failStatus, String failMessage) {
            this.invariants.add(new AstInvariant(condition, failStatus, failMessage));
            return this;
        }

        public RuleBuilder mutate(AstMutation mutation) {
            this.mutations.add(mutation);
            return this;
        }

        public RuleBuilder initialState(Map<String, Object> defaultFields) {
            this.mutations.add(new AstMutation.InitEntity(parent.idPathParam, defaultFields));
            return this;
        }

        public RuleBuilder respondWith(int status, AstResponseSource source) {
            return respondWith(status, source, Map.of());
        }

        public RuleBuilder respondWith(int status, AstResponseSource source, Map<String, String> headers) {
            this.response = new AstResponse(status, source, headers);
            return this;
        }

        private void commitRule() {
            AstResponse res =
                    response != null ? response : new AstResponse(200, new AstResponseSource.Entity(), Map.of());
            parent.addRule(new AstRuleDefinition(
                    new AstRoute(method, pathPattern), List.copyOf(invariants), List.copyOf(mutations), res));
        }

        public RuleBuilder onPost(String nextPathPattern) {
            commitRule();
            return parent.onPost(nextPathPattern);
        }

        public RuleBuilder onGet(String nextPathPattern) {
            commitRule();
            return parent.onGet(nextPathPattern);
        }

        public RuleBuilder onPut(String nextPathPattern) {
            commitRule();
            return parent.onPut(nextPathPattern);
        }

        public RuleBuilder onDelete(String nextPathPattern) {
            commitRule();
            return parent.onDelete(nextPathPattern);
        }

        public AstModelDefinition build() {
            commitRule();
            return parent.build();
        }
    }
}
