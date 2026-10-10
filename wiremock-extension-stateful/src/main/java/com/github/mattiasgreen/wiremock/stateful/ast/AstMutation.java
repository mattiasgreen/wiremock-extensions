package com.github.mattiasgreen.wiremock.stateful.ast;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Map;
import java.util.Objects;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AstMutation.InitEntity.class, name = "init_entity"),
    @JsonSubTypes.Type(value = AstMutation.SetField.class, name = "set_field"),
    @JsonSubTypes.Type(value = AstMutation.AppendToList.class, name = "append_to_list"),
    @JsonSubTypes.Type(value = AstMutation.UpdateListItem.class, name = "update_list_item")
})
public sealed interface AstMutation
        permits AstMutation.InitEntity, AstMutation.SetField, AstMutation.AppendToList, AstMutation.UpdateListItem {

    record InitEntity(
            @JsonProperty("idParam") String idParam, @JsonProperty("defaultFields") Map<String, Object> defaultFields)
            implements AstMutation {
        @JsonCreator
        public InitEntity {
            Objects.requireNonNull(defaultFields, "defaultFields must not be null");
        }
    }

    record SetField(@JsonProperty("path") String path, @JsonProperty("value") AstValueSource value)
            implements AstMutation {
        @JsonCreator
        public SetField {
            Objects.requireNonNull(path, "path must not be null");
            Objects.requireNonNull(value, "value must not be null");
        }
    }

    record AppendToList(
            @JsonProperty("collectionPath") String collectionPath, @JsonProperty("item") AstValueSource item)
            implements AstMutation {
        @JsonCreator
        public AppendToList {
            Objects.requireNonNull(collectionPath, "collectionPath must not be null");
            Objects.requireNonNull(item, "item must not be null");
        }
    }

    record UpdateListItem(
            @JsonProperty("collectionPath") String collectionPath,
            @JsonProperty("idField") String idField,
            @JsonProperty("idParam") String idParam,
            @JsonProperty("targetField") String targetField,
            @JsonProperty("value") AstValueSource value)
            implements AstMutation {
        @JsonCreator
        public UpdateListItem {
            Objects.requireNonNull(collectionPath, "collectionPath must not be null");
            Objects.requireNonNull(idField, "idField must not be null");
            Objects.requireNonNull(idParam, "idParam must not be null");
            Objects.requireNonNull(targetField, "targetField must not be null");
            Objects.requireNonNull(value, "value must not be null");
        }
    }
}
