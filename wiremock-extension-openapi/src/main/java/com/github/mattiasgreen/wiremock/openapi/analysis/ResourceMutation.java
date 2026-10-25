package com.github.mattiasgreen.wiremock.openapi.analysis;

import java.util.List;
import java.util.Objects;

/**
 * Metadata describing a detected mutation or read-your-own-writes lifecycle sequence across stubs.
 *
 * @param resourcePathTemplate OpenAPI path template representing the mutated resource (e.g. "/api/cases/{caseId}")
 * @param mutatingMethod HTTP method triggering the mutation (e.g. "POST", "PATCH", "PUT", "DELETE")
 * @param mutatedFields JSON pointers of fields that changed between reads or writes (e.g. ["/status", "/updatedAt"])
 * @param readYourOwnWritesDetected whether multiple reads or a write followed by a read exhibited state progression
 */
public record ResourceMutation(
        String resourcePathTemplate,
        String mutatingMethod,
        List<String> mutatedFields,
        boolean readYourOwnWritesDetected) {

    public ResourceMutation {
        Objects.requireNonNull(resourcePathTemplate, "resourcePathTemplate must not be null");
        Objects.requireNonNull(mutatingMethod, "mutatingMethod must not be null");
        mutatedFields = mutatedFields != null ? List.copyOf(mutatedFields) : List.of();
    }
}
