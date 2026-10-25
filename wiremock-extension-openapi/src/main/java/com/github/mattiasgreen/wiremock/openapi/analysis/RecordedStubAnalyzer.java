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

    public static final Set<String> DEFAULT_PRESERVED_HEADERS =
            Set.of("content-type", "accept", "x-correlation-id", "x-tenant-id", "x-request-id", "x-api-version");

    private final ObjectMapper objectMapper;
    private final Set<String> preservedHeaders;

    public RecordedStubAnalyzer() {
        this(DEFAULT_PRESERVED_HEADERS);
    }

    public RecordedStubAnalyzer(Set<String> preservedHeaders) {
        this.objectMapper = new ObjectMapper();
        this.preservedHeaders = preservedHeaders != null
                ? preservedHeaders.stream()
                        .map(h -> h.toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet())
                : DEFAULT_PRESERVED_HEADERS;
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

        List<ResourceMutation> detectedMutations = detectResourceMutations(groupedStubs);

        return new RecordedStubAnalysisReport(specTitle, specVersion, analyses, detectedMutations, unmatchedUrls);
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

        // Header allow-listing
        if (rawStub.getRequest().getHeaders() != null) {
            for (Map.Entry<String, com.github.tomakehurst.wiremock.matching.MultiValuePattern> entry :
                    rawStub.getRequest().getHeaders().entrySet()) {
                String headerName = entry.getKey();
                if (preservedHeaders.contains(headerName.toLowerCase(Locale.ROOT))) {
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
                if (preservedHeaders.contains(name.toLowerCase(Locale.ROOT))) {
                    respDef.withHeader(name, header.firstValue());
                }
            }
        }

        respDef.withTransformers("response-template");

        StubMapping generalized = new StubMapping(reqPattern.build(), respDef.build());
        generalized.setId(UUID.randomUUID());
        generalized.setPriority(1);
        generalized.setName(String.format(
                Locale.ROOT, "[GENERALIZED %s] %s", analysis.httpMethod(), analysis.openApiPathTemplate()));
        return generalized;
    }

    private String templatizeBody(String jsonBody, List<ParameterEcho> echoes) {
        try {
            JsonNode root = objectMapper.readTree(jsonBody);
            for (ParameterEcho echo : echoes) {
                String templateValue =
                        switch (echo.parameterLocation().toLowerCase(Locale.ROOT)) {
                            case "path" -> String.format(Locale.ROOT, "{{request.path.%s}}", echo.parameterName());
                            case "query" -> String.format(
                                    Locale.ROOT, "{{request.query.%s.[0]}}", echo.parameterName());
                            case "body" -> String.format(
                                    Locale.ROOT, "{{jsonPath request.body '%s'}}", echo.parameterName());
                            default -> null;
                        };
                if (templateValue != null) {
                    applyJsonPointerTemplate(root, echo.responseJsonPointer(), templateValue);
                }
            }
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            return jsonBody;
        }
    }

    private void applyJsonPointerTemplate(JsonNode root, String jsonPointer, String templateValue) {
        if (jsonPointer == null || jsonPointer.isBlank() || !jsonPointer.startsWith("/")) {
            return;
        }
        String[] tokens = jsonPointer.substring(1).split("/");
        JsonNode current = root;
        for (int i = 0; i < tokens.length - 1; i++) {
            String token = unescapePointerToken(tokens[i]);
            if (current.isObject()) {
                current = current.get(token);
            } else if (current.isArray()) {
                try {
                    int idx = Integer.parseInt(token);
                    current = current.get(idx);
                } catch (NumberFormatException e) {
                    return;
                }
            } else {
                return;
            }
            if (current == null) {
                return;
            }
        }
        String lastToken = unescapePointerToken(tokens[tokens.length - 1]);
        if (current instanceof ObjectNode objNode) {
            objNode.put(lastToken, templateValue);
        } else if (current instanceof com.fasterxml.jackson.databind.node.ArrayNode arrNode) {
            try {
                int idx = Integer.parseInt(lastToken);
                arrNode.set(idx, new com.fasterxml.jackson.databind.node.TextNode(templateValue));
            } catch (NumberFormatException ignored) {
            }
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

        Set<String> allPointers = new LinkedHashSet<>();
        for (MatchedStubContext ctx : contexts) {
            allPointers.addAll(ctx.parsedResponseFields().keySet());
        }

        for (String pointer : allPointers) {
            if (echoedPointers.contains(pointer)) {
                continue;
            }

            Set<Object> distinctValues = new LinkedHashSet<>();
            for (MatchedStubContext ctx : contexts) {
                if (ctx.parsedResponseFields().containsKey(pointer)) {
                    distinctValues.add(ctx.parsedResponseFields().get(pointer));
                }
            }

            String fieldKey = pointer.startsWith("/") ? pointer.substring(1) : pointer;
            if (distinctValues.size() == 1) {
                sharedBaselines.put(fieldKey, distinctValues.iterator().next());
            } else if (distinctValues.size() > 1) {
                distinguishingTraits.put(fieldKey, new ArrayList<>(distinctValues));
            }
        }
    }

    private List<ResourceMutation> detectResourceMutations(Map<OperationKey, List<MatchedStubContext>> groupedStubs) {
        List<ResourceMutation> mutations = new ArrayList<>();

        for (Map.Entry<OperationKey, List<MatchedStubContext>> entry : groupedStubs.entrySet()) {
            OperationKey key = entry.getKey();
            List<MatchedStubContext> contexts = entry.getValue();

            // Detect identical GET requests returning different response payloads across the timeline
            if ("GET".equalsIgnoreCase(key.method()) && contexts.size() >= 2) {
                for (int i = 0; i < contexts.size() - 1; i++) {
                    MatchedStubContext ctxA = contexts.get(i);
                    for (int j = i + 1; j < contexts.size(); j++) {
                        MatchedStubContext ctxB = contexts.get(j);
                        if (ctxA.rawUrl().equals(ctxB.rawUrl())) {
                            List<String> changedPointers =
                                    diffResponsePointers(ctxA.parsedResponseFields(), ctxB.parsedResponseFields());
                            if (!changedPointers.isEmpty()) {
                                // Find any mutating call on the same resource
                                String mutatingMethod = findMutatingMethodForResource(groupedStubs, key.pathTemplate());
                                mutations.add(new ResourceMutation(
                                        key.pathTemplate(),
                                        mutatingMethod != null ? mutatingMethod : "EXTERNAL_MUTATION",
                                        changedPointers,
                                        true));
                            }
                        }
                    }
                }
            }
        }

        return mutations;
    }

    private List<String> diffResponsePointers(Map<String, Object> a, Map<String, Object> b) {
        List<String> diffs = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(a.keySet());
        keys.addAll(b.keySet());
        for (String k : keys) {
            Object valA = a.get(k);
            Object valB = b.get(k);
            if (!Objects.equals(valA, valB)) {
                diffs.add(k);
            }
        }
        return diffs;
    }

    private String findMutatingMethodForResource(
            Map<OperationKey, List<MatchedStubContext>> groupedStubs, String getPathTemplate) {
        for (OperationKey key : groupedStubs.keySet()) {
            if (!"GET".equalsIgnoreCase(key.method()) && isSameResourceHierarchy(getPathTemplate, key.pathTemplate())) {
                return key.method();
            }
        }
        return null;
    }

    private boolean isSameResourceHierarchy(String pathA, String pathB) {
        String baseA = pathA.replaceAll("/\\{[^}]+}", "");
        String baseB = pathB.replaceAll("/\\{[^}]+}", "");
        return baseA.equalsIgnoreCase(baseB);
    }

    private MatchedStubContext matchStub(
            String method, String rawUrl, Map<String, PathItem> paths, StubMapping stub, OpenAPI openAPI) {

        String concretePath = rawUrl;
        int qIdx = concretePath.indexOf('?');
        if (qIdx >= 0) {
            concretePath = concretePath.substring(0, qIdx);
        }
        int hashIdx = concretePath.indexOf('#');
        if (hashIdx >= 0) {
            concretePath = concretePath.substring(0, hashIdx);
        }
        if (!concretePath.startsWith("/")) {
            concretePath = "/" + concretePath;
        }
        Map<String, String> queryParams = extractQueryParams(rawUrl);

        // Extract request body leaves if present
        String reqBody = extractRequestBody(stub);
        Map<String, String> requestBodyLeaves = new LinkedHashMap<>();
        if (reqBody != null && !reqBody.isBlank()) {
            try {
                JsonNode reqNode = objectMapper.readTree(reqBody);
                walkJsonLeaves("", reqNode, (pointer, leaf) -> {
                    requestBodyLeaves.put(pointer, leaf.asText());
                });
            } catch (JsonProcessingException ignored) {
            }
        }

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

            // Parse response body and correlate parameters recursively
            Map<String, Object> responseFields = new LinkedHashMap<>();
            List<ParameterEcho> echoes = new ArrayList<>();
            List<TemporalFieldInfo> temporal = new ArrayList<>();

            String body = stub.getResponse().getBody();
            if (body != null && !body.isBlank()) {
                try {
                    JsonNode node = objectMapper.readTree(body);
                    walkJsonLeaves("", node, (respPointer, leaf) -> {
                        String strVal = leaf.asText();
                        responseFields.put(respPointer, leaf.isValueNode() ? strVal : leaf.toString());

                        // 1. Path param echo check
                        boolean isEcho = false;
                        for (Map.Entry<String, String> pEntry : pathParams.entrySet()) {
                            if (pEntry.getValue().equals(strVal)) {
                                echoes.add(new ParameterEcho("path", pEntry.getKey(), strVal, respPointer));
                                isEcho = true;
                                break;
                            }
                        }

                        // 2. Query param echo check
                        if (!isEcho) {
                            for (Map.Entry<String, String> qEntry : queryParams.entrySet()) {
                                if (qEntry.getValue().equals(strVal)) {
                                    echoes.add(new ParameterEcho("query", qEntry.getKey(), strVal, respPointer));
                                    isEcho = true;
                                    break;
                                }
                            }
                        }

                        // 3. Request body echo check
                        if (!isEcho && !requestBodyLeaves.isEmpty()) {
                            for (Map.Entry<String, String> reqEntry : requestBodyLeaves.entrySet()) {
                                if (reqEntry.getValue().equals(strVal)) {
                                    String jsonPath = pointerToJsonPath(reqEntry.getKey());
                                    echoes.add(new ParameterEcho("body", jsonPath, strVal, respPointer));
                                    isEcho = true;
                                    break;
                                }
                            }
                        }

                        // 4. Temporal check
                        String lastToken =
                                unescapePointerToken(respPointer.substring(respPointer.lastIndexOf('/') + 1));
                        String schemaFormat = getSchemaFormatForProperty(operation, openAPI, lastToken);
                        if (schemaFormat != null
                                || ISO_DATE_REGEX.matcher(strVal).matches()) {
                            String format =
                                    schemaFormat != null ? schemaFormat : (strVal.contains("T") ? "date-time" : "date");
                            temporal.add(new TemporalFieldInfo(respPointer, format, strVal, isEcho));
                        }
                    });
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
                    stub,
                    rawUrl);
        }

        return null;
    }

    private void walkJsonLeaves(
            String currentPointer, JsonNode node, java.util.function.BiConsumer<String, JsonNode> leafConsumer) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String childPointer = currentPointer + "/" + escapePointerToken(entry.getKey());
                walkJsonLeaves(childPointer, entry.getValue(), leafConsumer);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                String childPointer = currentPointer + "/" + i;
                walkJsonLeaves(childPointer, node.get(i), leafConsumer);
            }
        } else if (node.isValueNode()) {
            leafConsumer.accept(currentPointer, node);
        }
    }

    private String escapePointerToken(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    private String unescapePointerToken(String token) {
        return token.replace("~1", "/").replace("~0", "~");
    }

    private String pointerToJsonPath(String jsonPointer) {
        if (jsonPointer == null || jsonPointer.isBlank() || "/".equals(jsonPointer)) {
            return "$";
        }
        StringBuilder sb = new StringBuilder("$");
        String[] tokens = jsonPointer.substring(1).split("/");
        for (String t : tokens) {
            String unescaped = unescapePointerToken(t);
            if (unescaped.matches("\\d+")) {
                sb.append("[").append(unescaped).append("]");
            } else {
                sb.append(".").append(unescaped);
            }
        }
        return sb.toString();
    }

    private String extractRequestBody(StubMapping stub) {
        if (stub.getRequest().getBodyPatterns() != null) {
            for (com.github.tomakehurst.wiremock.matching.ContentPattern<?> pattern :
                    stub.getRequest().getBodyPatterns()) {
                if (pattern instanceof com.github.tomakehurst.wiremock.matching.EqualToJsonPattern eqJson) {
                    return eqJson.getExpected();
                }
                if (pattern.getValue() != null) {
                    String val = pattern.getValue().toString();
                    if (!val.isBlank()) {
                        return val;
                    }
                }
            }
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
            StubMapping stub,
            String rawUrl) {}
}
