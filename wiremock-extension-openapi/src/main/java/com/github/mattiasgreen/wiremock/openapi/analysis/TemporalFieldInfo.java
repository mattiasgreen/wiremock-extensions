package com.github.mattiasgreen.wiremock.openapi.analysis;

import java.util.Objects;

/**
 * Metadata about a date or date-time field detected in the recorded response.
 *
 * @param jsonPointer JSON pointer to the temporal property (e.g. "/onboardedDate", "/asOfDate")
 * @param schemaFormat OpenAPI schema format ("date" or "date-time")
 * @param recordedValue concrete recorded timestamp or date string (e.g. "2023-10-25")
 * @param echoedFromRequest whether this temporal value was directly echoed from a request parameter
 */
public record TemporalFieldInfo(
        String jsonPointer, String schemaFormat, String recordedValue, boolean echoedFromRequest) {

    public TemporalFieldInfo {
        Objects.requireNonNull(jsonPointer, "jsonPointer must not be null");
        Objects.requireNonNull(schemaFormat, "schemaFormat must not be null");
        Objects.requireNonNull(recordedValue, "recordedValue must not be null");
    }
}
