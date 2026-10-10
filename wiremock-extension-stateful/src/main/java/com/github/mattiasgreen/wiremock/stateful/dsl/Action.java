package com.github.mattiasgreen.wiremock.stateful.dsl;

import com.github.mattiasgreen.wiremock.stateful.ast.AstMutation;
import com.github.mattiasgreen.wiremock.stateful.ast.AstValueSource;
import java.util.Map;

public final class Action {

    private Action() {}

    public static AstMutation initEntity(String idParam, Map<String, Object> defaultFields) {
        return new AstMutation.InitEntity(idParam, defaultFields);
    }

    public static AstMutation setField(String path, AstValueSource value) {
        return new AstMutation.SetField(path, value);
    }

    public static AstMutation setField(String path, Object literalValue) {
        return new AstMutation.SetField(path, Source.literal(literalValue));
    }

    public static AstMutation appendToList(String collectionPath, AstValueSource item) {
        return new AstMutation.AppendToList(collectionPath, item);
    }

    public static AstMutation updateListItem(
            String collectionPath, String idField, String idParam, String targetField, AstValueSource value) {
        return new AstMutation.UpdateListItem(collectionPath, idField, idParam, targetField, value);
    }

    public static AstMutation updateListItem(
            String collectionPath, String idField, String idParam, String targetField, Object literalValue) {
        return new AstMutation.UpdateListItem(
                collectionPath, idField, idParam, targetField, Source.literal(literalValue));
    }
}
