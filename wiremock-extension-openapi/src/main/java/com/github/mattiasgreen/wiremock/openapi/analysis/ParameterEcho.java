package com.github.mattiasgreen.wiremock.openapi.analysis;

import java.util.Objects;

/**
 * Represents a value from the request (path, query, or body) that is echoed back in the response JSON.
 *
 * @param parameterLocation location of parameter ("path", "query", or "body")
 * @param parameterName name of parameter (e.g. "customerId", "asOf")
 * @param recordedValue concrete recorded value (e.g. "CUST-42", "2026-10-25")
 * @param responseJsonPointer JSON pointer to the echoed property in the response (e.g. "/customerId", "/asOfDate")
 */
public record ParameterEcho(
        String parameterLocation, String parameterName, String recordedValue, String responseJsonPointer) {

    public ParameterEcho {
        Objects.requireNonNull(parameterLocation, "parameterLocation must not be null");
        Objects.requireNonNull(parameterName, "parameterName must not be null");
        Objects.requireNonNull(recordedValue, "recordedValue must not be null");
        Objects.requireNonNull(responseJsonPointer, "responseJsonPointer must not be null");
    }
}
