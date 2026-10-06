package com.github.mattiasgreen.wiremock.openapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.DateSchema;
import io.swagger.v3.oas.models.media.DateTimeSchema;
import io.swagger.v3.oas.models.media.EmailSchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.UUIDSchema;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Synthesizes sample JSON data from OpenAPI MediaType and Schema definitions
 * following the Priority 1-3 hierarchy:
 * 1. Priority 1: Explicit example / examples defined on MediaType or Schema.
 * 2. Priority 2: Default values defined in Schema properties.
 * 3. Priority 3: Synthetic generation based on types and formats.
 */
public class SchemaDataSynthesizer {

    private final ObjectMapper objectMapper;
    private final OpenApiStubOptions options;

    public SchemaDataSynthesizer(OpenApiStubOptions options) {
        this.options = options;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Synthesizes a response body string (typically JSON) for a given MediaType definition.
     */
    public String synthesizeResponseBody(MediaType mediaType, OpenAPI openAPI) {
        if (mediaType == null) {
            return null;
        }

        // Priority 1: Direct MediaType example or examples
        if (mediaType.getExample() != null) {
            return serializeObject(mediaType.getExample());
        }
        if (mediaType.getExamples() != null && !mediaType.getExamples().isEmpty()) {
            for (var ex : mediaType.getExamples().values()) {
                if (ex != null && ex.getValue() != null) {
                    return serializeObject(ex.getValue());
                }
            }
        }

        Schema<?> schema = mediaType.getSchema();
        if (schema == null) {
            return null;
        }

        Object sampleData = synthesizeSchemaValue(schema, openAPI, new HashSet<>(), 0);
        if (sampleData == null) {
            return null;
        }

        return serializeObject(sampleData);
    }

    /**
     * Synthesizes a request body string for an OpenAPI RequestBody definition.
     */
    public String synthesizeRequestBody(io.swagger.v3.oas.models.parameters.RequestBody requestBody, OpenAPI openAPI) {
        if (requestBody == null
                || requestBody.getContent() == null
                || requestBody.getContent().isEmpty()) {
            return null;
        }

        // Priority to application/json or fallback to first available
        MediaType mediaType = requestBody.getContent().get("application/json");
        if (mediaType == null) {
            mediaType = requestBody.getContent().values().iterator().next();
        }

        return synthesizeResponseBody(mediaType, openAPI);
    }

    /**
     * Synthesizes a single parameter value (for path or query parameter replacement).
     */
    public String synthesizeParameterValue(io.swagger.v3.oas.models.parameters.Parameter param, OpenAPI openAPI) {
        if (param == null) {
            return "";
        }
        if (param.getExample() != null) {
            return String.valueOf(param.getExample());
        }
        if (param.getExamples() != null && !param.getExamples().isEmpty()) {
            for (var ex : param.getExamples().values()) {
                if (ex != null && ex.getValue() != null) {
                    return String.valueOf(ex.getValue());
                }
            }
        }
        if (param.getSchema() != null) {
            Schema<?> schema = resolveSchema(param.getSchema(), openAPI);
            if (schema.getExample() != null) {
                return String.valueOf(schema.getExample());
            }
            if (options.useDefaultValues() && schema.getDefault() != null) {
                return String.valueOf(schema.getDefault());
            }
            Object sample = generatePrimitiveSample(schema);
            if (sample != null) {
                return String.valueOf(sample);
            }
        }
        return param.getName() != null ? "sample-" + param.getName() : "sample";
    }

    @SuppressWarnings("rawtypes")
    private Object synthesizeSchemaValue(Schema<?> rawSchema, OpenAPI openAPI, Set<String> visitedRefs, int depth) {
        if (rawSchema == null || depth > 10) {
            return null;
        }

        Schema<?> schema = resolveSchema(rawSchema, openAPI);

        // Priority 1: Schema example
        if (schema.getExample() != null) {
            return schema.getExample();
        }

        // Priority 2: Schema default
        if (options.useDefaultValues() && schema.getDefault() != null) {
            return schema.getDefault();
        }

        if (!options.useSyntheticExamples()) {
            return null;
        }

        // Enum values take precedence for synthetic generation
        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
            return schema.getEnum().getFirst();
        }

        // Priority 3: Type and format-aware synthetic generation
        if (schema instanceof ArraySchema arraySchema) {
            Schema<?> itemsSchema = arraySchema.getItems();
            List<Object> items = new ArrayList<>();
            if (itemsSchema != null) {
                Object item = synthesizeSchemaValue(itemsSchema, openAPI, visitedRefs, depth + 1);
                if (item != null) {
                    items.add(item);
                }
            }
            return items;
        }

        if (schema instanceof ObjectSchema
                || (schema.getProperties() != null && !schema.getProperties().isEmpty())
                || "object".equals(schema.getType())) {
            Map<String, Object> obj = new LinkedHashMap<>();
            if (schema.getProperties() != null) {
                @SuppressWarnings("rawtypes")
                Map<String, Schema> props = schema.getProperties();
                for (Map.Entry<String, Schema> entry : props.entrySet()) {
                    String propName = entry.getKey();
                    Schema<?> propSchema = entry.getValue();

                    // Prevent infinite recursion in cyclical schemas
                    if (propSchema != null && propSchema.get$ref() != null) {
                        String ref = propSchema.get$ref();
                        if (visitedRefs.contains(ref)) {
                            obj.put(propName, null);
                            continue;
                        }
                        visitedRefs.add(ref);
                        obj.put(propName, synthesizeSchemaValue(propSchema, openAPI, visitedRefs, depth + 1));
                        visitedRefs.remove(ref);
                    } else {
                        obj.put(propName, synthesizeSchemaValue(propSchema, openAPI, visitedRefs, depth + 1));
                    }
                }
            }
            return obj;
        }

        return generatePrimitiveSample(schema);
    }

    private Object generatePrimitiveSample(Schema<?> schema) {
        String type = schema.getType() != null ? schema.getType().toLowerCase(Locale.ROOT) : "";
        String format = schema.getFormat() != null ? schema.getFormat().toLowerCase(Locale.ROOT) : "";

        if (schema instanceof UUIDSchema || "uuid".equals(format)) {
            return "3fa85f64-5717-4562-b3fc-2c963f66afa6";
        }
        if (schema instanceof DateTimeSchema || "date-time".equals(format)) {
            return "2026-10-04T12:00:00Z";
        }
        if (schema instanceof DateSchema || "date".equals(format)) {
            return "2026-10-04";
        }
        if (schema instanceof EmailSchema || "email".equals(format)) {
            return "user@example.com";
        }
        if ("uri".equals(format)) {
            return "https://example.com/resource";
        }
        if ("ipv4".equals(format)) {
            return "192.168.1.1";
        }

        if (schema instanceof IntegerSchema || "integer".equals(type)) {
            return 1;
        }
        if (schema instanceof NumberSchema || "number".equals(type)) {
            return 9.99;
        }
        if (schema instanceof BooleanSchema || "boolean".equals(type)) {
            return true;
        }
        if (schema instanceof StringSchema || "string".equals(type)) {
            return schema.getName() != null ? schema.getName() : "string";
        }

        return "sample";
    }

    @SuppressWarnings("rawtypes")
    private Schema<?> resolveSchema(Schema<?> schema, OpenAPI openAPI) {
        if (schema.get$ref() != null && openAPI != null && openAPI.getComponents() != null) {
            String ref = schema.get$ref();
            String prefix = "#/components/schemas/";
            if (ref.startsWith(prefix)) {
                String schemaName = ref.substring(prefix.length());
                Schema resolved = openAPI.getComponents().getSchemas().get(schemaName);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        return schema;
    }

    private String serializeObject(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof String str) {
            // If already valid JSON string, return as is
            String trimmed = str.trim();
            if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
                    || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
                return trimmed;
            }
            return "\"" + str + "\"";
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return String.valueOf(obj);
        }
    }
}
