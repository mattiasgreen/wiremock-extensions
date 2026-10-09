package com.github.mattiasgreen.wiremock.stateful.engine;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class SessionStateStore {

    // correlationId -> entityType -> entityId -> ObjectNode
    private final Map<String, Map<String, Map<String, ObjectNode>>> store = new ConcurrentHashMap<>();

    public Optional<ObjectNode> getEntity(String correlationId, String entityType, String entityId) {
        Map<String, Map<String, ObjectNode>> entities = store.get(correlationId);
        if (entities == null) {
            return Optional.empty();
        }
        Map<String, ObjectNode> records = entities.get(entityType);
        if (records == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(records.get(entityId));
    }

    public void putEntity(String correlationId, String entityType, String entityId, ObjectNode entity) {
        store.computeIfAbsent(correlationId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(entityType, k -> new ConcurrentHashMap<>())
                .put(entityId, entity);
    }

    public void removeEntity(String correlationId, String entityType, String entityId) {
        Map<String, Map<String, ObjectNode>> entities = store.get(correlationId);
        if (entities != null) {
            Map<String, ObjectNode> records = entities.get(entityType);
            if (records != null) {
                records.remove(entityId);
            }
        }
    }

    public void clearSession(String correlationId) {
        store.remove(correlationId);
    }

    public void clearAll() {
        store.clear();
    }

    public int activeSessionsCount() {
        return store.size();
    }
}
