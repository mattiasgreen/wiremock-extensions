package com.github.mattiasgreen.wiremock.stateful.ast;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Objects;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AstResponseSource.Entity.class, name = "entity"),
    @JsonSubTypes.Type(value = AstResponseSource.LastAppendedItem.class, name = "last_appended_item"),
    @JsonSubTypes.Type(value = AstResponseSource.SubPath.class, name = "sub_path"),
    @JsonSubTypes.Type(value = AstResponseSource.UpdatedItem.class, name = "updated_item"),
    @JsonSubTypes.Type(value = AstResponseSource.Empty.class, name = "empty")
})
public sealed interface AstResponseSource
        permits AstResponseSource.Entity,
                AstResponseSource.LastAppendedItem,
                AstResponseSource.SubPath,
                AstResponseSource.UpdatedItem,
                AstResponseSource.Empty {

    record Entity() implements AstResponseSource {
        @JsonCreator
        public Entity {}
    }

    record LastAppendedItem(@JsonProperty("collectionPath") String collectionPath) implements AstResponseSource {
        @JsonCreator
        public LastAppendedItem {
            Objects.requireNonNull(collectionPath, "collectionPath must not be null");
        }
    }

    record SubPath(@JsonProperty("path") String path) implements AstResponseSource {
        @JsonCreator
        public SubPath {
            Objects.requireNonNull(path, "path must not be null");
        }
    }

    record UpdatedItem(
            @JsonProperty("collectionPath") String collectionPath,
            @JsonProperty("idField") String idField,
            @JsonProperty("idParam") String idParam)
            implements AstResponseSource {
        @JsonCreator
        public UpdatedItem {
            Objects.requireNonNull(collectionPath, "collectionPath must not be null");
            Objects.requireNonNull(idField, "idField must not be null");
            Objects.requireNonNull(idParam, "idParam must not be null");
        }
    }

    record Empty() implements AstResponseSource {
        @JsonCreator
        public Empty {}
    }
}
