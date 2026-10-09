package com.github.mattiasgreen.wiremock.stateful.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.mattiasgreen.wiremock.stateful.ast.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class StateEngine {

    public record ExecutionResult(int status, String body, Map<String, String> headers) {}

    private final SessionStateStore stateStore = new SessionStateStore();
    private final Map<String, List<CompiledRule>> rulesByCorrelation = new ConcurrentHashMap<>();
    private final List<CompiledRule> globalRules = new ArrayList<>();
    private final AtomicLong idGenerator = new AtomicLong(100);

    private record CompiledRule(AstModelDefinition model, AstRuleDefinition rule, RouteMatcher matcher) {}

    public void registerGlobalModel(AstModelDefinition model) {
        for (AstRuleDefinition rule : model.rules()) {
            globalRules.add(new CompiledRule(
                    model,
                    rule,
                    new RouteMatcher(rule.route().method(), rule.route().pathPattern())));
        }
    }

    public void registerSessionModel(String correlationId, AstModelDefinition model) {
        List<CompiledRule> compiled = rulesByCorrelation.computeIfAbsent(correlationId, k -> new ArrayList<>());
        for (AstRuleDefinition rule : model.rules()) {
            compiled.add(new CompiledRule(
                    model,
                    rule,
                    new RouteMatcher(rule.route().method(), rule.route().pathPattern())));
        }
    }

    public void clearSession(String correlationId) {
        rulesByCorrelation.remove(correlationId);
        stateStore.clearSession(correlationId);
    }

    public void clearAll() {
        rulesByCorrelation.clear();
        globalRules.clear();
        stateStore.clearAll();
    }

    public SessionStateStore getStateStore() {
        return stateStore;
    }

    public ExecutionResult execute(
            String correlationId, String method, String path, String requestBodyJson, Map<String, String> headers) {

        String effectiveCorrelation = (correlationId != null && !correlationId.isBlank()) ? correlationId : "DEFAULT";
        List<CompiledRule> sessionRules = rulesByCorrelation.get(effectiveCorrelation);

        List<CompiledRule> candidateRules = new ArrayList<>();
        if (sessionRules != null) {
            candidateRules.addAll(sessionRules);
        }
        candidateRules.addAll(globalRules);

        for (CompiledRule candidate : candidateRules) {
            Optional<Map<String, String>> matchResult = candidate.matcher().match(method, path);
            if (matchResult.isPresent()) {
                Map<String, String> pathParams = new HashMap<>(matchResult.get());
                return handleRuleExecution(
                        effectiveCorrelation, candidate.model(), candidate.rule(), pathParams, requestBodyJson);
            }
        }

        return null; // No rule matched
    }

    private ExecutionResult handleRuleExecution(
            String correlationId,
            AstModelDefinition model,
            AstRuleDefinition rule,
            Map<String, String> pathParams,
            String requestBodyJson) {

        String entityType = model.entity();
        String idParam = model.idPathParam();
        String entityId = pathParams.get(idParam);

        JsonNode requestBody = null;
        if (requestBodyJson != null && !requestBodyJson.isBlank()) {
            try {
                requestBody = AstJson.readTree(requestBodyJson);
            } catch (JsonProcessingException e) {
                return new ExecutionResult(
                        400, "{\"error\":\"Invalid JSON request body\"}", Map.of("Content-Type", "application/json"));
            }
        }

        boolean isCreateRule = rule.route().method().equalsIgnoreCase("POST") && !pathParams.containsKey(idParam);
        if (isCreateRule && entityId == null) {
            entityId = model.idPrefix() + idGenerator.incrementAndGet();
            pathParams.put(idParam, entityId);
        }

        ObjectNode entity = null;
        if (entityId != null) {
            Optional<ObjectNode> existing = stateStore.getEntity(correlationId, entityType, entityId);
            if (existing.isPresent()) {
                entity = existing.get();
            } else if (isCreateRule) {
                entity = AstJson.createObjectNode();
                entity.put("id", entityId);
                stateStore.putEntity(correlationId, entityType, entityId, entity);
            } else {
                return new ExecutionResult(
                        404,
                        "{\"error\":\"" + entityType + " not found with id: " + entityId + "\"}",
                        Map.of("Content-Type", "application/json"));
            }
        }

        // 1. Evaluate Invariants
        for (AstInvariant inv : rule.invariants()) {
            boolean satisfied = AstEvaluator.evaluate(inv.condition(), entity, pathParams);
            if (!satisfied) {
                String errorJson = "{\"status\":" + inv.failStatus() + ",\"error\":\"" + inv.failMessage() + "\"}";
                return new ExecutionResult(inv.failStatus(), errorJson, Map.of("Content-Type", "application/json"));
            }
        }

        // 2. Apply Mutations
        JsonNode lastAffectedItem = null;
        for (AstMutation mutation : rule.mutations()) {
            AstMutator.MutationResult res = AstMutator.apply(mutation, entity, requestBody, pathParams, idGenerator);
            entity = res.entity();
            if (res.affectedItem() != null) {
                lastAffectedItem = res.affectedItem();
            }
        }

        if (entityId != null && entity != null) {
            stateStore.putEntity(correlationId, entityType, entityId, entity);
        }

        // 3. Synthesize Response
        AstResponse responseSpec = rule.response();
        int status = responseSpec.status();
        String responseBody = formatResponseBody(responseSpec.source(), entity, lastAffectedItem);

        Map<String, String> responseHeaders = new HashMap<>(responseSpec.headers());
        if (!responseHeaders.containsKey("Content-Type") && !responseBody.isEmpty()) {
            responseHeaders.put("Content-Type", "application/json");
        }

        return new ExecutionResult(status, responseBody, Collections.unmodifiableMap(responseHeaders));
    }

    private String formatResponseBody(AstResponseSource source, ObjectNode entity, JsonNode affectedItem) {
        if (source instanceof AstResponseSource.Entity) {
            return entity != null ? entity.toString() : "{}";
        }
        if (source instanceof AstResponseSource.LastAppendedItem) {
            return affectedItem != null ? affectedItem.toString() : "{}";
        }
        if (source instanceof AstResponseSource.UpdatedItem) {
            return affectedItem != null ? affectedItem.toString() : "{}";
        }
        if (source instanceof AstResponseSource.SubPath subPath) {
            if (entity != null && entity.has(subPath.path())) {
                return entity.get(subPath.path()).toString();
            }
            return "[]";
        }
        if (source instanceof AstResponseSource.Empty) {
            return "";
        }
        return entity != null ? entity.toString() : "{}";
    }
}
