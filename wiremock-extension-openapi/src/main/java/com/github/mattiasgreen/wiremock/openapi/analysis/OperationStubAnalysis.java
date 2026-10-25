package com.github.mattiasgreen.wiremock.openapi.analysis;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregated analysis of one or more recorded WireMock stubs mapped to a single OpenAPI operation.
 *
 * @param httpMethod HTTP method (e.g. "GET")
 * @param openApiPathTemplate OpenAPI path template (e.g. "/api/crm/customers/{customerId}/kyc")
 * @param operationId OpenAPI operationId (e.g. "getCustomerKyc")
 * @param responseSchemaName response schema name if resolved from components (e.g. "CustomerKycProfile")
 * @param matchedStubIds IDs of WireMock stubs mapped to this operation
 * @param parameterEchoes detected values echoed from request to response
 * @param temporalFields detected date and timestamp fields
 * @param sharedBaselineFields properties with identical values across all recordings of this operation
 * @param distinguishingTraitFields properties whose values differ across recordings (distinguishing traits)
 */
public record OperationStubAnalysis(
        String httpMethod,
        String openApiPathTemplate,
        String operationId,
        String responseSchemaName,
        List<UUID> matchedStubIds,
        List<ParameterEcho> parameterEchoes,
        List<TemporalFieldInfo> temporalFields,
        Map<String, Object> sharedBaselineFields,
        Map<String, List<Object>> distinguishingTraitFields) {

    public OperationStubAnalysis {
        Objects.requireNonNull(httpMethod, "httpMethod must not be null");
        Objects.requireNonNull(openApiPathTemplate, "openApiPathTemplate must not be null");
        Objects.requireNonNull(operationId, "operationId must not be null");
        matchedStubIds = matchedStubIds != null ? List.copyOf(matchedStubIds) : List.of();
        parameterEchoes = parameterEchoes != null ? List.copyOf(parameterEchoes) : List.of();
        temporalFields = temporalFields != null ? List.copyOf(temporalFields) : List.of();
        sharedBaselineFields = sharedBaselineFields != null ? Map.copyOf(sharedBaselineFields) : Map.of();
        distinguishingTraitFields =
                distinguishingTraitFields != null ? Map.copyOf(distinguishingTraitFields) : Map.of();
    }
}
