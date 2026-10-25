package com.github.mattiasgreen.wiremock.openapi.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.DateSchema;
import io.swagger.v3.oas.models.media.DateTimeSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic analyzer correlating raw recorded WireMock StubMappings against an
 * OpenAPI 3.0 / 3.1 specification. Identifies operations, parameter echoes, temporal
 * fields, baseline defaults, and distinguishing traits across recordings.
 */
public class RecordedStubAnalyzer {

    private static final Pattern PATH_PARAM_REGEX = Pattern.compile("\\{([^}]+)}");
    private static final Pattern ISO_DATE_REGEX = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}(T.*)?$");

    private final ObjectMapper objectMapper;

    public RecordedStubAnalyzer() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Analyzes a collection of recorded WireMock stubs against an OpenAPI specification string.
     *
     * @param openApiSpecContent raw OpenAPI specification (YAML or JSON)
     * @param recordedStubs collection of recorded StubMappings
     * @return structured analysis report
     */
    public RecordedStubAnalysisReport analyze(String openApiSpecContent, List<StubMapping> recordedStubs) {
        Objects.requireNonNull(openApiSpecContent, "openApiSpecContent must not be null");
        Objects.requireNonNull(recordedStubs, "recordedStubs must not be null");

        ParseOptions parseOptions = new ParseOptions();
        parseOptions.setResolve(true);

        SwaggerParseResult parseResult = new OpenAPIParser().readContents(openApiSpecContent, null, parseOptions);
        OpenAPI openAPI = parseResult.getOpenAPI();
        if (openAPI == null) {
            String errorMsg = parseResult.getMessages() != null
                            && !parseResult.getMessages().isEmpty()
                    ? String.join(", ", parseResult.getMessages())
                    : "Failed to parse OpenAPI spec";
            throw new IllegalArgumentException(errorMsg);
        }

        String specTitle = openAPI.getInfo() != null && openAPI.getInfo().getTitle() != null
                ? openAPI.getInfo().getTitle()
                : "OpenAPI Specification";
        String specVersion = openAPI.getInfo() != null && openAPI.getInfo().getVersion() != null
                ? openAPI.getInfo().getVersion()
                : "1.0.0";

        Map<String, PathItem> paths = openAPI.getPaths() != null ? openAPI.getPaths() : Collections.emptyMap();

        Map<OperationKey, List<MatchedStubContext>> groupedStubs = new LinkedHashMap<>();
        List<String> unmatchedUrls = new ArrayList<>();

        for (StubMapping stub : recordedStubs) {
            String rawUrl = extractStubUrl(stub);
            String method = stub.getRequest().getMethod().getName().toUpperCase(Locale.ROOT);

            MatchedStubContext context = matchStub(method, rawUrl, paths, stub, openAPI);
            if (context != null) {
                OperationKey key = new OperationKey(context.httpMethod(), context.openApiPathTemplate());
                groupedStubs.computeIfAbsent(key, k -> new ArrayList<>()).add(context);
            } else {
                unmatchedUrls.add(method + " " + rawUrl);
            }
        }

        List<OperationStubAnalysis> analyses = new ArrayList<>();
        for (Map.Entry<OperationKey, List<MatchedStubContext>> entry : groupedStubs.entrySet()) {
            List<MatchedStubContext> contexts = entry.getValue();
            MatchedStubContext first = contexts.getFirst();

            List<UUID> stubIds = contexts.stream().map(c -> c.stub().getId()).toList();

            // Aggregate parameter echoes (union across recordings)
            Map<String, ParameterEcho> uniqueEchoes = new LinkedHashMap<>();
            for (MatchedStubContext ctx : contexts) {
                for (ParameterEcho echo : ctx.parameterEchoes()) {
                    uniqueEchoes.putIfAbsent(echo.responseJsonPointer(), echo);
                }
            }

            // Aggregate temporal fields
            Map<String, TemporalFieldInfo> uniqueTemporal = new LinkedHashMap<>();
            for (MatchedStubContext ctx : contexts) {
                for (TemporalFieldInfo t : ctx.temporalFields()) {
                    uniqueTemporal.putIfAbsent(t.jsonPointer(), t);
                }
            }

            // Diff payload fields across recordings for this operation
            Map<String, Object> sharedBaselines = new LinkedHashMap<>();
            Map<String, List<Object>> distinguishingTraits = new LinkedHashMap<>();
            computeFieldVariations(contexts, uniqueEchoes.keySet(), sharedBaselines, distinguishingTraits);

            analyses.add(new OperationStubAnalysis(
                    first.httpMethod(),
                    first.openApiPathTemplate(),
                    first.operationId(),
                    first.responseSchemaName(),
                    stubIds,
                    new ArrayList<>(uniqueEchoes.values()),
                    new ArrayList<>(uniqueTemporal.values()),
                    sharedBaselines,
                    distinguishingTraits));
        }

        return new RecordedStubAnalysisReport(specTitle, specVersion, analyses, unmatchedUrls);
    }

    /**
     * Converts a brittle recorded stub into a generalized WireMock StubMapping with path templates,
     * relaxed query matchers, and Handlebars response templates for echoed values.
     *
     * @param rawStub original recorded StubMapping
     * @param analysis operation analysis for this stub
     * @return portable generalized StubMapping
     */
    public StubMapping generalizeStub(StubMapping rawStub, OperationStubAnalysis analysis) {
        Objects.requireNonNull(rawStub, "rawStub must not be null");
        Objects.requireNonNull(analysis, "analysis must not be null");

        RequestPatternBuilder reqPattern = RequestPatternBuilder.newRequestPattern(
                RequestMethod.fromString(analysis.httpMethod()),
                WireMock.urlPathTemplate(analysis.openApiPathTemplate()));

        // Relax query parameters in the template
        String rawUrl = extractStubUrl(rawStub);
        Map<String, String> queryParams = extractQueryParams(rawUrl);
        for (String qName : queryParams.keySet()) {
            reqPattern.withQueryParam(qName, WireMock.matching(".+"));
        }

        // Header allow-listing: keep Content-Type, Accept, X-Correlation-Id
        if (rawStub.getRequest().getHeaders() != null) {
            for (Map.Entry<String, com.github.tomakehurst.wiremock.matching.MultiValuePattern> entry :
                    rawStub.getRequest().getHeaders().entrySet()) {
                String headerName = entry.getKey();
                if (headerName.equalsIgnoreCase("Content-Type")
                        || headerName.equalsIgnoreCase("Accept")
                        || headerName.equalsIgnoreCase("X-Correlation-Id")) {
                    reqPattern.withHeader(headerName, entry.getValue());
                }
            }
        }

        ResponseDefinitionBuilder respDef = ResponseDefinitionBuilder.responseDefinition()
                .withStatus(rawStub.getResponse().getStatus());

        String rawBody = rawStub.getResponse().getBody();
        if (rawBody != null && !rawBody.isBlank()) {
            String generalizedBody = templatizeBody(rawBody, analysis.parameterEchoes());
            respDef.withBody(generalizedBody);
        }

        // Copy semantic response headers
        if (rawStub.getResponse().getHeaders() != null) {
            for (HttpHeader header : rawStub.getResponse().getHeaders().all()) {
                String name = header.key();
                if (name.equalsIgnoreCase("Content-Type") || name.equalsIgnoreCase("X-Correlation-Id")) {
                    respDef.withHeader(name, header.firstValue());
                }
            }
        }

        respDef.withTransformers("response-template");

        StubMapping generalized = new StubMapping(reqPattern.build(), respDef.build());
        generalized.setId(rawStub.getId() != null ? rawStub.getId() : UUID.randomUUID());
        generalized.setName(String.format(
                Locale.ROOT, "[GENERALIZED %s] %s", analysis.httpMethod(), analysis.openApiPathTemplate()));
        return generalized;
    }

    private String templatizeBody(String jsonBody, List<ParameterEcho> echoes) {
        try {
            JsonNode root = objectMapper.readTree(jsonBody);
            if (!(root instanceof ObjectNode objNode)) {
                return jsonBody;
            }

            for (ParameterEcho echo : echoes) {
                String pointer = echo.responseJsonPointer();
                String fieldName = pointer.startsWith("/") ? pointer.substring(1) : pointer;
                if (objNode.has(fieldName)) {
                    String templateValue = "path".equalsIgnoreCase(echo.parameterLocation())
                            ? String.format(Locale.ROOT, "{{request.path.%s}}", echo.parameterName())
                            : String.format(Locale.ROOT, "{{request.query.%s.[0]}}", echo.parameterName());
                    objNode.put(fieldName, templateValue);
                }
            }
            return objectMapper.writeValueAsString(objNode);
        } catch (JsonProcessingException e) {
            return jsonBody;
        }
    }

    private void computeFieldVariations(
            List<MatchedStubContext> contexts,
            Set<String> echoedPointers,
            Map<String, Object> sharedBaselines,
            Map<String, List<Object>> distinguishingTraits) {

        if (contexts.isEmpty()) {
            return;
        }

        Set<String> allKeys = new LinkedHashSet<>();
        for (MatchedStubContext ctx : contexts) {
            allKeys.addAll(ctx.parsedResponseFields().keySet());
        }

        for (String key : allKeys) {
            String pointer = "/" + key;
            if (echoedPointers.contains(pointer)) {
                continue;
            }

            Set<Object> distinctValues = new LinkedHashSet<>();
            for (MatchedStubContext ctx : contexts) {
                if (ctx.parsedResponseFields().containsKey(key)) {
                    distinctValues.add(ctx.parsedResponseFields().get(key));
                }
            }

            if (distinctValues.size() == 1) {
                sharedBaselines.put(key, distinctValues.iterator().next());
            } else if (distinctValues.size() > 1) {
                distinguishingTraits.put(key, new ArrayList<>(distinctValues));
            }
        }
    }

    private MatchedStubContext matchStub(
            String method, String rawUrl, Map<String, PathItem> paths, StubMapping stub, OpenAPI openAPI) {

        URI uri = URI.create(rawUrl.startsWith("/") ? rawUrl : "/" + rawUrl);
        String concretePath = uri.getPath();
        Map<String, String> queryParams = extractQueryParams(rawUrl);

        for (Map.Entry<String, PathItem> entry : paths.entrySet()) {
            String pathTemplate = entry.getKey();
            PathItem pathItem = entry.getValue();

            Map<String, String> pathParams = matchPathTemplate(pathTemplate, concretePath);
            if (pathParams == null) {
                continue;
            }

            Operation operation = getOperationForMethod(pathItem, method);
            if (operation == null) {
                continue;
            }

            String opId = operation.getOperationId() != null
                    ? operation.getOperationId()
                    : method.toLowerCase(Locale.ROOT) + pathTemplate.replaceAll("[^a-zA-Z0-9]", "_");

            String responseSchemaName = resolveResponseSchemaName(operation, openAPI);

            // Parse response body and correlate parameters
            Map<String, Object> responseFields = new LinkedHashMap<>();
            List<ParameterEcho> echoes = new ArrayList<>();
            List<TemporalFieldInfo> temporal = new ArrayList<>();

            String body = stub.getResponse().getBody();
            if (body != null && !body.isBlank()) {
                try {
                    JsonNode node = objectMapper.readTree(body);
                    if (node instanceof ObjectNode objNode) {
                        Iterator<Map.Entry<String, JsonNode>> fields = objNode.fields();
                        while (fields.hasNext()) {
                            Map.Entry<String, JsonNode> f = fields.next();
                            String fName = f.getKey();
                            JsonNode fVal = f.getValue();
                            Object valObj = fVal.isValueNode() ? fVal.asText() : fVal.toString();
                            responseFields.put(fName, valObj);

                            String strVal = fVal.asText();

                            // Path echo check
                            boolean isEcho = false;
                            for (Map.Entry<String, String> pEntry : pathParams.entrySet()) {
                                if (pEntry.getValue().equals(strVal)) {
                                    echoes.add(new ParameterEcho("path", pEntry.getKey(), strVal, "/" + fName));
                                    isEcho = true;
                                    break;
                                }
                            }
                            // Query echo check
                            if (!isEcho) {
                                for (Map.Entry<String, String> qEntry : queryParams.entrySet()) {
                                    if (qEntry.getValue().equals(strVal)) {
                                        echoes.add(new ParameterEcho("query", qEntry.getKey(), strVal, "/" + fName));
                                        isEcho = true;
                                        break;
                                    }
                                }
                            }

                            // Temporal check
                            String schemaFormat = getSchemaFormatForProperty(operation, openAPI, fName);
                            if (schemaFormat != null
                                    || ISO_DATE_REGEX.matcher(strVal).matches()) {
                                String format = schemaFormat != null
                                        ? schemaFormat
                                        : (strVal.contains("T") ? "date-time" : "date");
                                temporal.add(new TemporalFieldInfo("/" + fName, format, strVal, isEcho));
                            }
                        }
                    }
                } catch (JsonProcessingException ignored) {
                }
            }

            return new MatchedStubContext(
                    method,
                    pathTemplate,
                    opId,
                    responseSchemaName,
                    pathParams,
                    queryParams,
                    responseFields,
                    echoes,
                    temporal,
                    stub);
        }

        return null;
    }

    private Map<String, String> matchPathTemplate(String template, String concrete) {
        List<String> paramNames = new ArrayList<>();
        Matcher m = PATH_PARAM_REGEX.matcher(template);
        StringBuilder regex = new StringBuilder("^");
        int lastIdx = 0;
        while (m.find()) {
            regex.append(Pattern.quote(template.substring(lastIdx, m.start())));
            paramNames.add(m.group(1));
            regex.append("([^/?#]+)");
            lastIdx = m.end();
        }
        regex.append(Pattern.quote(template.substring(lastIdx)));
        regex.append("$");

        Pattern compiled = Pattern.compile(regex.toString());
        Matcher concreteMatcher = compiled.matcher(concrete);
        if (!concreteMatcher.matches()) {
            return null;
        }

        Map<String, String> extracted = new LinkedHashMap<>();
        for (int i = 0; i < paramNames.size(); i++) {
            extracted.put(paramNames.get(i), concreteMatcher.group(i + 1));
        }
        return extracted;
    }

    private Operation getOperationForMethod(PathItem pathItem, String method) {
        return switch (method.toUpperCase(Locale.ROOT)) {
            case "GET" -> pathItem.getGet();
            case "POST" -> pathItem.getPost();
            case "PUT" -> pathItem.getPut();
            case "DELETE" -> pathItem.getDelete();
            case "PATCH" -> pathItem.getPatch();
            case "HEAD" -> pathItem.getHead();
            case "OPTIONS" -> pathItem.getOptions();
            case "TRACE" -> pathItem.getTrace();
            default -> null;
        };
    }

    @SuppressWarnings("rawtypes")
    private String resolveResponseSchemaName(Operation op, OpenAPI openAPI) {
        if (op == null || op.getResponses() == null) {
            return "UnknownResponse";
        }
        ApiResponse resp = op.getResponses().get("200");
        if (resp == null) {
            resp = op.getResponses().get("201");
        }
        if (resp == null) {
            resp = op.getResponses().get("default");
        }
        if (resp == null || resp.getContent() == null) {
            return "UnknownResponse";
        }
        MediaType mt = resp.getContent().get("application/json");
        if (mt == null) {
            mt = resp.getContent().values().iterator().hasNext()
                    ? resp.getContent().values().iterator().next()
                    : null;
        }
        if (mt != null && mt.getSchema() != null) {
            Schema<?> s = mt.getSchema();
            if (s.get$ref() != null) {
                String ref = s.get$ref();
                return ref.substring(ref.lastIndexOf('/') + 1);
            }
            if (s.getName() != null && !s.getName().isBlank()) {
                return s.getName();
            }
            if (s.getTitle() != null && !s.getTitle().isBlank()) {
                return s.getTitle();
            }
            if (openAPI.getComponents() != null && openAPI.getComponents().getSchemas() != null) {
                for (Map.Entry<String, Schema> entry :
                        openAPI.getComponents().getSchemas().entrySet()) {
                    if (entry.getValue().equals(s)
                            || (entry.getValue().getProperties() != null
                                    && entry.getValue().getProperties().equals(s.getProperties()))) {
                        return entry.getKey();
                    }
                }
            }
        }
        return "UnknownResponse";
    }

    @SuppressWarnings("rawtypes")
    private String getSchemaFormatForProperty(Operation op, OpenAPI openAPI, String propName) {
        if (op == null || op.getResponses() == null) {
            return null;
        }
        ApiResponse resp = op.getResponses().get("200");
        if (resp == null) {
            resp = op.getResponses().get("201");
        }
        if (resp == null || resp.getContent() == null) {
            return null;
        }
        MediaType mt = resp.getContent().get("application/json");
        if (mt == null || mt.getSchema() == null) {
            return null;
        }

        Schema<?> schema = resolveSchema(mt.getSchema(), openAPI);
        if (schema.getProperties() != null) {
            Schema<?> propSchema = schema.getProperties().get(propName);
            if (propSchema != null) {
                propSchema = resolveSchema(propSchema, openAPI);
                if (propSchema instanceof DateSchema || "date".equalsIgnoreCase(propSchema.getFormat())) {
                    return "date";
                }
                if (propSchema instanceof DateTimeSchema || "date-time".equalsIgnoreCase(propSchema.getFormat())) {
                    return "date-time";
                }
            }
        }
        return null;
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

    private String extractStubUrl(StubMapping stub) {
        if (stub.getRequest().getUrl() != null) {
            return stub.getRequest().getUrl();
        }
        if (stub.getRequest().getUrlPath() != null) {
            return stub.getRequest().getUrlPath();
        }
        if (stub.getRequest().getUrlPattern() != null) {
            return stub.getRequest().getUrlPattern();
        }
        return "/";
    }

    private Map<String, String> extractQueryParams(String rawUrl) {
        Map<String, String> params = new LinkedHashMap<>();
        int qIdx = rawUrl.indexOf('?');
        if (qIdx >= 0 && qIdx < rawUrl.length() - 1) {
            String query = rawUrl.substring(qIdx + 1);
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                    String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                    params.put(k, v);
                }
            }
        }
        return params;
    }

    private record OperationKey(String method, String pathTemplate) {}

    private record MatchedStubContext(
            String httpMethod,
            String openApiPathTemplate,
            String operationId,
            String responseSchemaName,
            Map<String, String> pathParams,
            Map<String, String> queryParams,
            Map<String, Object> parsedResponseFields,
            List<ParameterEcho> parameterEchoes,
            List<TemporalFieldInfo> temporalFields,
            StubMapping stub) {}
}
