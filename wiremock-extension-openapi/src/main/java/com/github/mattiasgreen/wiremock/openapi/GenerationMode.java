package com.github.mattiasgreen.wiremock.openapi;

/**
 * Strategy mode for synthesizing WireMock stubs from OpenAPI operations.
 */
public enum GenerationMode {
    /**
     * Synthesizes offline static response bodies and schemas with example/synthetic data.
     */
    SYNTHETIC,

    /**
     * Synthesizes live proxy stubs forwarding requests to an upstream server (proxyBaseUrl).
     */
    PROXY
}
