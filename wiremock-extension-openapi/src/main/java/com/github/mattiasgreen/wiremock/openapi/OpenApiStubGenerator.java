package com.github.mattiasgreen.wiremock.openapi;

import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathTemplate;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.Metadata;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Core engine to parse OpenAPI 3.0 / 3.1 specifications and synthesize WireMock StubMapping objects.
 */
public class OpenApiStubGenerator {

    private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{[^}]+}");

    private final OpenApiStubOptions options;
    private final SchemaDataSynthesizer dataSynthesizer;

    public OpenApiStubGenerator() {
        this(OpenApiStubOptions.defaults());
    }

    public OpenApiStubGenerator(OpenApiStubOptions options) {
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.dataSynthesizer = new SchemaDataSynthesizer(options);
    }

    /**
     * Parses OpenAPI content (YAML or JSON) and generates WireMock stubs.
     *
     * @param specContent Raw OpenAPI YAML or JSON specification string
     * @return List of synthesized WireMock StubMappings
     */
    public List<StubMapping> generateStubs(String specContent) {
        Objects.requireNonNull(specContent, "specContent must not be null");

        ParseOptions parseOptions = new ParseOptions();
        parseOptions.setResolve(true);
        parseOptions.setResolveFully(true);

        SwaggerParseResult parseResult = new OpenAPIParser().readContents(specContent, null, parseOptions);
        OpenAPI openAPI = parseResult.getOpenAPI();

        if (openAPI == null || openAPI.getPaths() == null) {
            if (parseResult.getMessages() != null && !parseResult.getMessages().isEmpty()) {
                throw new IllegalArgumentException(
                        "Failed to parse OpenAPI spec: " + String.join(", ", parseResult.getMessages()));
            }
            return Collections.emptyList();
        }

        return generateStubs(openAPI);
    }

    /**
     * Generates WireMock stubs from an already parsed OpenAPI object model.
     */
    public List<StubMapping> generateStubs(OpenAPI openAPI) {
        List<StubMapping> stubs = new ArrayList<>();

        if (openAPI.getPaths() == null) {
            return stubs;
        }

        for (Map.Entry<String, PathItem> pathEntry : openAPI.getPaths().entrySet()) {
            String path = pathEntry.getKey();
            PathItem pathItem = pathEntry.getValue();

            processOperation(stubs, openAPI, path, RequestMethod.GET, pathItem.getGet(), pathItem);
            processOperation(stubs, openAPI, path, RequestMethod.POST, pathItem.getPost(), pathItem);
            processOperation(stubs, openAPI, path, RequestMethod.PUT, pathItem.getPut(), pathItem);
            processOperation(stubs, openAPI, path, RequestMethod.DELETE, pathItem.getDelete(), pathItem);
            processOperation(stubs, openAPI, path, RequestMethod.PATCH, pathItem.getPatch(), pathItem);
            processOperation(stubs, openAPI, path, RequestMethod.HEAD, pathItem.getHead(), pathItem);
            processOperation(stubs, openAPI, path, RequestMethod.OPTIONS, pathItem.getOptions(), pathItem);
            processOperation(stubs, openAPI, path, RequestMethod.TRACE, pathItem.getTrace(), pathItem);
        }

        return stubs;
    }

    private void processOperation(
            List<StubMapping> stubs,
            OpenAPI openAPI,
            String path,
            RequestMethod method,
            Operation operation,
            PathItem pathItem) {
        if (operation == null || operation.getResponses() == null) {
            return;
        }

        // Combine path-level parameters and operation-level parameters
        List<Parameter> allParams = new ArrayList<>();
        if (pathItem.getParameters() != null) {
            allParams.addAll(pathItem.getParameters());
        }
        if (operation.getParameters() != null) {
            allParams.addAll(operation.getParameters());
        }

        for (Map.Entry<String, ApiResponse> responseEntry :
                operation.getResponses().entrySet()) {
            String responseCodeStr = responseEntry.getKey();
            ApiResponse apiResponse = responseEntry.getValue();

            int statusCode = parseStatusCode(responseCodeStr);
            if (options.includeOnlySuccessResponses() && (statusCode < 200 || statusCode >= 300)) {
                continue;
            }

            StubMapping stub = createStub(openAPI, path, method, operation, allParams, statusCode, apiResponse);
            if (stub != null) {
                stubs.add(stub);
            }
        }
    }

    private StubMapping createStub(
            OpenAPI openAPI,
            String path,
            RequestMethod method,
            Operation operation,
            List<Parameter> parameters,
            int statusCode,
            ApiResponse apiResponse) {

        RequestPatternBuilder requestBuilder;
        boolean hasPathParams = PATH_PARAM_PATTERN.matcher(path).find();

        if (hasPathParams) {
            requestBuilder = RequestPatternBuilder.newRequestPattern(method, urlPathTemplate(path));
        } else {
            requestBuilder = RequestPatternBuilder.newRequestPattern(method, urlEqualTo(path));
        }

        // Query & Header matchers for required parameters
        for (Parameter param : parameters) {
            if (Boolean.TRUE.equals(param.getRequired())) {
                String paramIn = param.getIn();
                if ("query".equalsIgnoreCase(paramIn) && options.matchRequiredQueryParams()) {
                    requestBuilder.withQueryParam(param.getName(), matching(".+"));
                } else if ("header".equalsIgnoreCase(paramIn) && options.matchRequiredHeaders()) {
                    requestBuilder.withHeader(param.getName(), matching(".+"));
                }
            }
        }

        // Build Response Definition
        ResponseDefinitionBuilder responseBuilder =
                ResponseDefinitionBuilder.responseDefinition().withStatus(statusCode);

        if (apiResponse.getContent() != null && !apiResponse.getContent().isEmpty()) {
            // Find application/json or fallback to first available content type
            String contentType = "application/json";
            MediaType mediaType = apiResponse.getContent().get(contentType);
            if (mediaType == null) {
                Map.Entry<String, MediaType> first =
                        apiResponse.getContent().entrySet().iterator().next();
                contentType = first.getKey();
                mediaType = first.getValue();
            }

            responseBuilder.withHeader("Content-Type", contentType);
            String body = dataSynthesizer.synthesizeResponseBody(mediaType, openAPI);
            if (body != null) {
                responseBuilder.withBody(body);
            }
        }

        // Set metadata and name
        String stubName = buildStubName(operation, method, path, statusCode);
        StubMapping stubMapping = new StubMapping(requestBuilder.build(), responseBuilder.build());
        stubMapping.setName(stubName);

        Metadata.Builder metadataBuilder = Metadata.metadata()
                .attr("source", "openapi")
                .attr("path", path)
                .attr("method", method.getName())
                .attr("statusCode", statusCode);

        if (operation.getOperationId() != null) {
            metadataBuilder.attr("operationId", operation.getOperationId());
        }
        if (operation.getTags() != null && !operation.getTags().isEmpty()) {
            metadataBuilder.attr("tags", operation.getTags());
        }

        // Build ready-made example request
        Map<String, Object> exampleRequest = buildExampleRequest(path, parameters, operation.getRequestBody(), openAPI);
        metadataBuilder.attr("exampleRequest", exampleRequest);

        stubMapping.setMetadata(metadataBuilder.build());
        return stubMapping;
    }

    private Map<String, Object> buildExampleRequest(
            String pathTemplate,
            List<Parameter> parameters,
            io.swagger.v3.oas.models.parameters.RequestBody requestBody,
            OpenAPI openAPI) {

        String resolvedPath = pathTemplate;
        Map<String, String> exampleHeaders = new java.util.LinkedHashMap<>();
        Map<String, String> exampleQueryParams = new java.util.LinkedHashMap<>();

        for (Parameter param : parameters) {
            String paramIn = param.getIn();
            String sampleVal = dataSynthesizer.synthesizeParameterValue(param, openAPI);

            if ("path".equalsIgnoreCase(paramIn)) {
                resolvedPath = resolvedPath.replace("{" + param.getName() + "}", sampleVal);
            } else if ("query".equalsIgnoreCase(paramIn)) {
                if (Boolean.TRUE.equals(param.getRequired())) {
                    exampleQueryParams.put(param.getName(), sampleVal);
                }
            } else if ("header".equalsIgnoreCase(paramIn)) {
                if (Boolean.TRUE.equals(param.getRequired())) {
                    exampleHeaders.put(param.getName(), sampleVal);
                }
            }
        }

        // Append required query parameters to the resolved URL
        if (!exampleQueryParams.isEmpty()) {
            StringBuilder queryBuilder = new StringBuilder();
            boolean first = true;
            for (Map.Entry<String, String> qEntry : exampleQueryParams.entrySet()) {
                if (first) {
                    queryBuilder.append("?");
                    first = false;
                } else {
                    queryBuilder.append("&");
                }
                queryBuilder.append(qEntry.getKey()).append("=").append(qEntry.getValue());
            }
            resolvedPath = resolvedPath + queryBuilder;
        }

        String requestBodySample = null;
        if (requestBody != null) {
            if (!exampleHeaders.containsKey("Content-Type")) {
                exampleHeaders.put("Content-Type", "application/json");
            }
            requestBodySample = dataSynthesizer.synthesizeRequestBody(requestBody, openAPI);
        }

        Map<String, Object> example = new java.util.LinkedHashMap<>();
        example.put("path", resolvedPath);
        example.put("headers", exampleHeaders);
        example.put("queryParams", exampleQueryParams);
        if (requestBodySample != null) {
            example.put("body", requestBodySample);
        }

        return example;
    }

    private String buildStubName(Operation operation, RequestMethod method, String path, int statusCode) {
        if (operation.getSummary() != null && !operation.getSummary().isBlank()) {
            return String.format("[%s %d] %s", method.getName(), statusCode, operation.getSummary());
        }
        if (operation.getOperationId() != null && !operation.getOperationId().isBlank()) {
            return String.format("[%s %d] %s", method.getName(), statusCode, operation.getOperationId());
        }
        return String.format("[%s %d] %s", method.getName(), statusCode, path);
    }

    private int parseStatusCode(String codeStr) {
        if ("default".equalsIgnoreCase(codeStr)) {
            return 200;
        }
        try {
            return Integer.parseInt(codeStr);
        } catch (NumberFormatException e) {
            return 200;
        }
    }
}
