package com.github.mattiasgreen.wiremock.openapi;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Parses and extracts high-level metadata, declared servers, and operation counts
 * from OpenAPI 3.0 / 3.1 specifications without creating stub mappings.
 */
public class OpenApiSpecInspector {

    public OpenApiSpecInfo inspect(String specContent) {
        Objects.requireNonNull(specContent, "specContent must not be null");

        ParseOptions parseOptions = new ParseOptions();
        parseOptions.setResolve(true);
        parseOptions.setResolveFully(true);

        SwaggerParseResult parseResult = new OpenAPIParser().readContents(specContent, null, parseOptions);
        OpenAPI openAPI = parseResult.getOpenAPI();

        if (openAPI == null) {
            String errorMsg = parseResult.getMessages() != null
                            && !parseResult.getMessages().isEmpty()
                    ? String.join(", ", parseResult.getMessages())
                    : "Failed to parse OpenAPI spec";
            throw new IllegalArgumentException(errorMsg);
        }

        String title = openAPI.getInfo() != null && openAPI.getInfo().getTitle() != null
                ? openAPI.getInfo().getTitle()
                : "OpenAPI Specification";
        String version = openAPI.getInfo() != null && openAPI.getInfo().getVersion() != null
                ? openAPI.getInfo().getVersion()
                : "1.0.0";
        String description = openAPI.getInfo() != null && openAPI.getInfo().getDescription() != null
                ? openAPI.getInfo().getDescription()
                : "";

        List<OpenApiServerInfo> servers = new ArrayList<>();
        if (openAPI.getServers() != null) {
            for (Server server : openAPI.getServers()) {
                if (server.getUrl() != null && !server.getUrl().isBlank()) {
                    servers.add(new OpenApiServerInfo(
                            server.getUrl(), server.getDescription() != null ? server.getDescription() : ""));
                }
            }
        }

        int totalOperations = 0;
        if (openAPI.getPaths() != null) {
            for (PathItem pathItem : openAPI.getPaths().values()) {
                if (pathItem.getGet() != null) totalOperations++;
                if (pathItem.getPost() != null) totalOperations++;
                if (pathItem.getPut() != null) totalOperations++;
                if (pathItem.getDelete() != null) totalOperations++;
                if (pathItem.getPatch() != null) totalOperations++;
                if (pathItem.getHead() != null) totalOperations++;
                if (pathItem.getOptions() != null) totalOperations++;
                if (pathItem.getTrace() != null) totalOperations++;
            }
        }

        return new OpenApiSpecInfo(title, version, description, servers, totalOperations);
    }
}
