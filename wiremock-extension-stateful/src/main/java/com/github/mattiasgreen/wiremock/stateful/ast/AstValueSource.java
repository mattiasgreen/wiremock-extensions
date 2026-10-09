package com.github.mattiasgreen.wiremock.stateful.ast;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Objects;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = AstValueSource.RequestBody.class, name = "request_body"),
    @JsonSubTypes.Type(value = AstValueSource.RequestBodyWithGeneratedId.class, name = "request_body_with_id"),
    @JsonSubTypes.Type(value = AstValueSource.LiteralValue.class, name = "literal_value"),
    @JsonSubTypes.Type(value = AstValueSource.PathParam.class, name = "path_param")
})
public sealed interface AstValueSource
        permits AstValueSource.RequestBody,
                AstValueSource.RequestBodyWithGeneratedId,
                AstValueSource.LiteralValue,
                AstValueSource.PathParam {

    record RequestBody() implements AstValueSource {
        @JsonCreator
        public RequestBody {}
    }

    record RequestBodyWithGeneratedId(
            @JsonProperty("idField") String idField, @JsonProperty("idPrefix") String idPrefix)
            implements AstValueSource {
        @JsonCreator
        public RequestBodyWithGeneratedId {
            Objects.requireNonNull(idField, "idField must not be null");
        }
    }

    record LiteralValue(@JsonProperty("value") Object value) implements AstValueSource {
        @JsonCreator
        public LiteralValue {}
    }

    record PathParam(@JsonProperty("paramName") String paramName) implements AstValueSource {
        @JsonCreator
        public PathParam {
            Objects.requireNonNull(paramName, "paramName must not be null");
        }
    }
}
