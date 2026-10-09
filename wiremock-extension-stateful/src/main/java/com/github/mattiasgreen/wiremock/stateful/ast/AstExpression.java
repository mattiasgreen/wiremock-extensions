package com.github.mattiasgreen.wiremock.stateful.ast;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Objects;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AstExpression.FieldRef.class, name = "field_ref"),
    @JsonSubTypes.Type(value = AstExpression.Literal.class, name = "literal"),
    @JsonSubTypes.Type(value = AstExpression.BinaryOp.class, name = "binary_op"),
    @JsonSubTypes.Type(value = AstExpression.CollectionSize.class, name = "collection_size"),
    @JsonSubTypes.Type(value = AstExpression.CollectionAllMatch.class, name = "collection_all_match"),
    @JsonSubTypes.Type(value = AstExpression.CollectionAnyMatch.class, name = "collection_any_match")
})
public sealed interface AstExpression
        permits AstExpression.FieldRef,
                AstExpression.Literal,
                AstExpression.BinaryOp,
                AstExpression.CollectionSize,
                AstExpression.CollectionAllMatch,
                AstExpression.CollectionAnyMatch {

    enum Operator {
        EQUALS,
        NOT_EQUALS,
        GREATER_THAN,
        GREATER_THAN_OR_EQUAL,
        LESS_THAN,
        LESS_THAN_OR_EQUAL
    }

    record FieldRef(@JsonProperty("path") String path) implements AstExpression {
        @JsonCreator
        public FieldRef {
            Objects.requireNonNull(path, "path must not be null");
        }
    }

    record Literal(@JsonProperty("value") Object value) implements AstExpression {
        @JsonCreator
        public Literal {
            // value can be null, string, number, or boolean
        }
    }

    record BinaryOp(
            @JsonProperty("operator") Operator operator,
            @JsonProperty("left") AstExpression left,
            @JsonProperty("right") AstExpression right)
            implements AstExpression {
        @JsonCreator
        public BinaryOp {
            Objects.requireNonNull(operator, "operator must not be null");
            Objects.requireNonNull(left, "left expression must not be null");
            Objects.requireNonNull(right, "right expression must not be null");
        }
    }

    record CollectionSize(@JsonProperty("path") String path) implements AstExpression {
        @JsonCreator
        public CollectionSize {
            Objects.requireNonNull(path, "path must not be null");
        }
    }

    record CollectionAllMatch(
            @JsonProperty("collectionPath") String collectionPath, @JsonProperty("predicate") AstExpression predicate)
            implements AstExpression {
        @JsonCreator
        public CollectionAllMatch {
            Objects.requireNonNull(collectionPath, "collectionPath must not be null");
            Objects.requireNonNull(predicate, "predicate must not be null");
        }
    }

    record CollectionAnyMatch(
            @JsonProperty("collectionPath") String collectionPath, @JsonProperty("predicate") AstExpression predicate)
            implements AstExpression {
        @JsonCreator
        public CollectionAnyMatch {
            Objects.requireNonNull(collectionPath, "collectionPath must not be null");
            Objects.requireNonNull(predicate, "predicate must not be null");
        }
    }
}
