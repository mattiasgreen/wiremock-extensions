package com.github.mattiasgreen.wiremock.openapi.analysis;

import java.util.List;
import java.util.Objects;

/**
 * Top-level report produced by {@link RecordedStubAnalyzer}.
 *
 * @param specTitle title of the parsed OpenAPI specification
 * @param specVersion version of the parsed OpenAPI specification
 * @param operations analyses of operations discovered across the recorded stubs
 * @param unmatchedStubUrls URLs of any recorded stubs that did not match an OpenAPI operation
 */
public record RecordedStubAnalysisReport(
        String specTitle, String specVersion, List<OperationStubAnalysis> operations, List<String> unmatchedStubUrls) {

    public RecordedStubAnalysisReport {
        Objects.requireNonNull(specTitle, "specTitle must not be null");
        Objects.requireNonNull(specVersion, "specVersion must not be null");
        operations = operations != null ? List.copyOf(operations) : List.of();
        unmatchedStubUrls = unmatchedStubUrls != null ? List.copyOf(unmatchedStubUrls) : List.of();
    }
}
