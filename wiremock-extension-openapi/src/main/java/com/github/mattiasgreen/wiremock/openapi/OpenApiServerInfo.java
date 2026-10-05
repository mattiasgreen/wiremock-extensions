package com.github.mattiasgreen.wiremock.openapi;

/**
 * Metadata descriptor for an upstream server declared in an OpenAPI specification.
 */
public record OpenApiServerInfo(String url, String description) {}
