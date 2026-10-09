package com.github.mattiasgreen.wiremock.stateful.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.mattiasgreen.wiremock.stateful.ast.AstExpression;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;

public final class AstEvaluator {

    public static boolean evaluate(AstExpression expression, JsonNode context, Map<String, String> pathParams) {
        Object val = evaluateExpression(expression, context, pathParams);
        if (val instanceof Boolean b) {
            return b;
        }
        return val != null;
    }

    public static Object evaluateExpression(
            AstExpression expression, JsonNode context, Map<String, String> pathParams) {
        if (expression instanceof AstExpression.FieldRef fieldRef) {
            return extractValue(context, fieldRef.path());
        } else if (expression instanceof AstExpression.Literal literal) {
            return literal.value();
        } else if (expression instanceof AstExpression.CollectionSize collectionSize) {
            JsonNode node = extractJsonNode(context, collectionSize.path());
            if (node != null && node.isArray()) {
                return node.size();
            }
            return 0;
        } else if (expression instanceof AstExpression.BinaryOp binaryOp) {
            Object left = evaluateExpression(binaryOp.left(), context, pathParams);
            Object right = evaluateExpression(binaryOp.right(), context, pathParams);
            return compare(binaryOp.operator(), left, right);
        } else if (expression instanceof AstExpression.CollectionAllMatch allMatch) {
            JsonNode node = extractJsonNode(context, allMatch.collectionPath());
            if (node == null || !node.isArray() || node.isEmpty()) {
                return true;
            }
            ArrayNode arrayNode = (ArrayNode) node;
            for (Iterator<JsonNode> it = arrayNode.elements(); it.hasNext(); ) {
                JsonNode element = it.next();
                if (!evaluate(allMatch.predicate(), element, pathParams)) {
                    return false;
                }
            }
            return true;
        } else if (expression instanceof AstExpression.CollectionAnyMatch anyMatch) {
            JsonNode node = extractJsonNode(context, anyMatch.collectionPath());
            if (node == null || !node.isArray() || node.isEmpty()) {
                return false;
            }
            ArrayNode arrayNode = (ArrayNode) node;
            for (Iterator<JsonNode> it = arrayNode.elements(); it.hasNext(); ) {
                JsonNode element = it.next();
                if (evaluate(anyMatch.predicate(), element, pathParams)) {
                    return true;
                }
            }
            return false;
        }

        throw new UnsupportedOperationException(
                "Unknown expression type: " + expression.getClass().getName());
    }

    private static JsonNode extractJsonNode(JsonNode context, String path) {
        if (context == null || path == null) {
            return null;
        }
        String[] parts = path.split("\\.");
        JsonNode current = context;
        for (String part : parts) {
            if (part.equals("item") || part.equals("entity")) {
                continue;
            }
            if (current == null || !current.has(part)) {
                return null;
            }
            current = current.get(part);
        }
        return current;
    }

    private static Object extractValue(JsonNode context, String path) {
        JsonNode node = extractJsonNode(context, path);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isIntegralNumber()) {
            return node.asLong();
        }
        if (node.isFloatingPointNumber()) {
            return node.asDouble();
        }
        return node.asText();
    }

    private static boolean compare(AstExpression.Operator operator, Object left, Object right) {
        if (operator == AstExpression.Operator.EQUALS) {
            return Objects.equals(normalize(left), normalize(right));
        }
        if (operator == AstExpression.Operator.NOT_EQUALS) {
            return !Objects.equals(normalize(left), normalize(right));
        }

        if (left instanceof Number numLeft && right instanceof Number numRight) {
            double dLeft = numLeft.doubleValue();
            double dRight = numRight.doubleValue();
            return switch (operator) {
                case GREATER_THAN -> dLeft > dRight;
                case GREATER_THAN_OR_EQUAL -> dLeft >= dRight;
                case LESS_THAN -> dLeft < dRight;
                case LESS_THAN_OR_EQUAL -> dLeft <= dRight;
                default -> false;
            };
        }

        return false;
    }

    private static Object normalize(Object val) {
        if (val instanceof Number n) {
            return n.doubleValue();
        }
        return val;
    }
}
