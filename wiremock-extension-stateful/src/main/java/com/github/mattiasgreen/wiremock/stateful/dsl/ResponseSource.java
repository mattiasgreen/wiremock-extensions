package com.github.mattiasgreen.wiremock.stateful.dsl;

import com.github.mattiasgreen.wiremock.stateful.ast.AstResponseSource;

public final class ResponseSource {

    private ResponseSource() {}

    public static AstResponseSource entity() {
        return new AstResponseSource.Entity();
    }

    public static AstResponseSource lastAppendedItem(String collectionPath) {
        return new AstResponseSource.LastAppendedItem(collectionPath);
    }

    public static AstResponseSource field(String path) {
        return new AstResponseSource.SubPath(path);
    }

    public static AstResponseSource updatedItem(String collectionPath, String idField, String idParam) {
        return new AstResponseSource.UpdatedItem(collectionPath, idField, idParam);
    }

    public static AstResponseSource empty() {
        return new AstResponseSource.Empty();
    }
}
