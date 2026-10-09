package com.github.mattiasgreen.wiremock.stateful.dsl;

import com.github.mattiasgreen.wiremock.stateful.ast.AstValueSource;

public final class Source {

    private Source() {}

    public static AstValueSource requestBody() {
        return new AstValueSource.RequestBody();
    }

    public static AstValueSource requestBodyWithGeneratedId(String idField, String idPrefix) {
        return new AstValueSource.RequestBodyWithGeneratedId(idField, idPrefix);
    }

    public static AstValueSource literal(Object value) {
        return new AstValueSource.LiteralValue(value);
    }

    public static AstValueSource pathParam(String paramName) {
        return new AstValueSource.PathParam(paramName);
    }
}
