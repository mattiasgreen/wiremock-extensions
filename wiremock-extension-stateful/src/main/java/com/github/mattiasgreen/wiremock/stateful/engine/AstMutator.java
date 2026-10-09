package com.github.mattiasgreen.wiremock.stateful.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.mattiasgreen.wiremock.stateful.ast.AstJson;
import com.github.mattiasgreen.wiremock.stateful.ast.AstMutation;
import com.github.mattiasgreen.wiremock.stateful.ast.AstValueSource;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public final class AstMutator {

    public record MutationResult(ObjectNode entity, JsonNode affectedItem) {}

    public static MutationResult apply(
            AstMutation mutation,
            ObjectNode entity,
            JsonNode requestBody,
            Map<String, String> pathParams,
            AtomicLong idGenerator) {

        if (mutation instanceof AstMutation.InitEntity init) {
            String entityId = pathParams.get(init.idParam());
            if (entityId != null) {
                entity.put("id", entityId);
            }
            init.defaultFields().forEach((k, v) -> entity.set(k, AstJson.valueToTree(v)));
            return new MutationResult(entity, entity);
        }

        if (mutation instanceof AstMutation.SetField setField) {
            JsonNode valNode = resolveValueSource(setField.value(), requestBody, pathParams, idGenerator);
            entity.set(setField.path(), valNode);
            return new MutationResult(entity, entity);
        }

        if (mutation instanceof AstMutation.AppendToList append) {
            ArrayNode listNode;
            if (entity.has(append.collectionPath())
                    && entity.get(append.collectionPath()).isArray()) {
                listNode = (ArrayNode) entity.get(append.collectionPath());
            } else {
                listNode = entity.putArray(append.collectionPath());
            }

            JsonNode itemNode = resolveValueSource(append.item(), requestBody, pathParams, idGenerator);
            listNode.add(itemNode);
            return new MutationResult(entity, itemNode);
        }

        if (mutation instanceof AstMutation.UpdateListItem update) {
            String expectedId = pathParams.get(update.idParam());
            if (entity.has(update.collectionPath())
                    && entity.get(update.collectionPath()).isArray()) {
                ArrayNode array = (ArrayNode) entity.get(update.collectionPath());
                for (Iterator<JsonNode> it = array.elements(); it.hasNext(); ) {
                    JsonNode element = it.next();
                    if (element.isObject()
                            && element.has(update.idField())
                            && element.get(update.idField()).asText().equals(expectedId)) {
                        ObjectNode objElement = (ObjectNode) element;
                        JsonNode valNode = resolveValueSource(update.value(), requestBody, pathParams, idGenerator);
                        objElement.set(update.targetField(), valNode);
                        return new MutationResult(entity, objElement);
                    }
                }
            }
            return new MutationResult(entity, null);
        }

        throw new UnsupportedOperationException(
                "Unknown mutation type: " + mutation.getClass().getName());
    }

    private static JsonNode resolveValueSource(
            AstValueSource source, JsonNode requestBody, Map<String, String> pathParams, AtomicLong idGenerator) {

        if (source instanceof AstValueSource.RequestBody) {
            return requestBody != null ? requestBody.deepCopy() : AstJson.createObjectNode();
        }

        if (source instanceof AstValueSource.RequestBodyWithGeneratedId withId) {
            ObjectNode node;
            if (requestBody != null && requestBody.isObject()) {
                node = ((ObjectNode) requestBody).deepCopy();
            } else {
                node = AstJson.createObjectNode();
            }
            if (!node.has(withId.idField())) {
                String generatedId = withId.idPrefix() + idGenerator.incrementAndGet();
                node.put(withId.idField(), generatedId);
            }
            return node;
        }

        if (source instanceof AstValueSource.LiteralValue literal) {
            return AstJson.valueToTree(literal.value());
        }

        if (source instanceof AstValueSource.PathParam pathParam) {
            String val = pathParams.get(pathParam.paramName());
            return AstJson.valueToTree(val);
        }

        throw new UnsupportedOperationException(
                "Unknown value source: " + source.getClass().getName());
    }
}
