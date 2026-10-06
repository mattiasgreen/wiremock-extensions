package com.github.mattiasgreen.wiremock.openapi;

import java.util.List;

/**
 * High-level metadata extracted from an OpenAPI specification before importing.
 */
public record OpenApiSpecInfo(
        String title, String version, String description, List<OpenApiServerInfo> servers, int totalOperations) {}
