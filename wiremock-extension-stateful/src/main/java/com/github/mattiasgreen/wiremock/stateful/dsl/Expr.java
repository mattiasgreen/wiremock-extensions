package com.github.mattiasgreen.wiremock.stateful.dsl;

import com.github.mattiasgreen.wiremock.stateful.ast.AstExpression;
import java.util.Objects;

public final class Expr {

    private Expr() {}

    public static FieldExprBuilder field(String path) {
        return new FieldExprBuilder(new AstExpression.FieldRef(path));
    }

    public static AstExpression literal(Object value) {
        return new AstExpression.Literal(value);
    }

    public static ListExprBuilder list(String collectionPath) {
        return new ListExprBuilder(collectionPath);
    }

    public static final class FieldExprBuilder {
        private final AstExpression expression;

        private FieldExprBuilder(AstExpression expression) {
            this.expression = Objects.requireNonNull(expression, "expression must not be null");
        }

        public AstExpression eq(Object value) {
            return new AstExpression.BinaryOp(
                    AstExpression.Operator.EQUALS, expression, new AstExpression.Literal(value));
        }

        public AstExpression notEq(Object value) {
            return new AstExpression.BinaryOp(
                    AstExpression.Operator.NOT_EQUALS, expression, new AstExpression.Literal(value));
        }

        public AstExpression greaterThan(Number value) {
            return new AstExpression.BinaryOp(
                    AstExpression.Operator.GREATER_THAN, expression, new AstExpression.Literal(value));
        }

        public AstExpression greaterThanOrEqual(Number value) {
            return new AstExpression.BinaryOp(
                    AstExpression.Operator.GREATER_THAN_OR_EQUAL, expression, new AstExpression.Literal(value));
        }

        public AstExpression lessThan(Number value) {
            return new AstExpression.BinaryOp(
                    AstExpression.Operator.LESS_THAN, expression, new AstExpression.Literal(value));
        }

        public AstExpression lessThanOrEqual(Number value) {
            return new AstExpression.BinaryOp(
                    AstExpression.Operator.LESS_THAN_OR_EQUAL, expression, new AstExpression.Literal(value));
        }

        public AstExpression toExpression() {
            return expression;
        }
    }

    public static final class ListExprBuilder {
        private final String collectionPath;

        private ListExprBuilder(String collectionPath) {
            this.collectionPath = Objects.requireNonNull(collectionPath, "collectionPath must not be null");
        }

        public AstExpression allMatch(AstExpression predicate) {
            return new AstExpression.CollectionAllMatch(collectionPath, predicate);
        }

        public AstExpression anyMatch(AstExpression predicate) {
            return new AstExpression.CollectionAnyMatch(collectionPath, predicate);
        }

        public FieldExprBuilder size() {
            return new FieldExprBuilder(new AstExpression.CollectionSize(collectionPath));
        }
    }
}
