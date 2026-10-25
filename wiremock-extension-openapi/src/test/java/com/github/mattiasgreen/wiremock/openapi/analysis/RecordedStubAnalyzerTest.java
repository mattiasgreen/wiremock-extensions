package com.github.mattiasgreen.wiremock.openapi.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RecordedStubAnalyzerTest {

    private static final String CRM_OPENAPI_YAML =
            """
            openapi: 3.0.3
            info:
              title: Banking CRM Service
              version: 1.0.0
            paths:
              /api/crm/customers/{customerId}/kyc:
                get:
                  operationId: getCustomerKyc
                  parameters:
                    - name: customerId
                      in: path
                      required: true
                      schema:
                        type: string
                    - name: asOf
                      in: query
                      required: false
                      schema:
                        type: string
                        format: date
                  responses:
                    '200':
                      description: KYC details
                      content:
                        application/json:
                          schema:
                            $ref: '#/components/schemas/CustomerKycProfile'
            components:
              schemas:
                CustomerKycProfile:
                  type: object
                  required:
                    - customerId
                    - fullName
                    - kycStatus
                    - riskTier
                    - segment
                    - onboardedDate
                  properties:
                    customerId:
                      type: string
                    fullName:
                      type: string
                    kycStatus:
                      type: string
                      enum: [VERIFIED, PENDING, REJECTED]
                    riskTier:
                      type: string
                      enum: [LOW, ELEVATED, HIGH]
                    segment:
                      type: string
                      enum: [PREMIER, STANDARD]
                    onboardedDate:
                      type: string
                      format: date
                    asOfDate:
                      type: string
                      format: date
                    processedBy:
                      type: string
            """;

    @Test
    @DisplayName(
            "Should deterministically correlate recorded stubs, detect echoes, temporal fields, and archetype traits")
    void shouldAnalyzeRecordedStubsAgainstOpenApi() {
        // Given two recorded stubs captured during E2E runs
        StubMapping stubCust42 = WireMock.get(WireMock.urlEqualTo("/api/crm/customers/CUST-42/kyc?asOf=2026-10-25"))
                .willReturn(
                        WireMock.aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(
                                        """
                                {
                                  "customerId": "CUST-42",
                                  "fullName": "Eleanor Vance",
                                  "kycStatus": "VERIFIED",
                                  "riskTier": "LOW",
                                  "segment": "PREMIER",
                                  "onboardedDate": "2022-10-25",
                                  "asOfDate": "2026-10-25",
                                  "processedBy": "upstream-server-B"
                                }
                                """))
                .build();
        stubCust42.setId(UUID.randomUUID());

        StubMapping stubCust99 = WireMock.get(WireMock.urlEqualTo("/api/crm/customers/CUST-99/kyc?asOf=2026-10-25"))
                .willReturn(
                        WireMock.aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(
                                        """
                                {
                                  "customerId": "CUST-99",
                                  "fullName": "Marcus Thorne",
                                  "kycStatus": "PENDING",
                                  "riskTier": "HIGH",
                                  "segment": "STANDARD",
                                  "onboardedDate": "2026-10-15",
                                  "asOfDate": "2026-10-25",
                                  "processedBy": "upstream-server-B"
                                }
                                """))
                .build();
        stubCust99.setId(UUID.randomUUID());

        RecordedStubAnalyzer analyzer = new RecordedStubAnalyzer();

        // When
        RecordedStubAnalysisReport report = analyzer.analyze(CRM_OPENAPI_YAML, List.of(stubCust42, stubCust99));

        // Then
        assertThat(report.specTitle()).isEqualTo("Banking CRM Service");
        assertThat(report.operations()).hasSize(1);
        assertThat(report.unmatchedStubUrls()).isEmpty();

        OperationStubAnalysis opAnalysis = report.operations().getFirst();
        assertThat(opAnalysis.operationId()).isEqualTo("getCustomerKyc");
        assertThat(opAnalysis.openApiPathTemplate()).isEqualTo("/api/crm/customers/{customerId}/kyc");
        assertThat(opAnalysis.responseSchemaName()).isEqualTo("CustomerKycProfile");
        assertThat(opAnalysis.matchedStubIds()).containsExactlyInAnyOrder(stubCust42.getId(), stubCust99.getId());

        // Parameter Echoes
        assertThat(opAnalysis.parameterEchoes())
                .extracting(ParameterEcho::responseJsonPointer)
                .contains("/customerId", "/asOfDate");
        assertThat(opAnalysis.parameterEchoes())
                .extracting(ParameterEcho::parameterName)
                .contains("customerId", "asOf");

        // Temporal Fields
        assertThat(opAnalysis.temporalFields())
                .extracting(TemporalFieldInfo::jsonPointer)
                .contains("/onboardedDate", "/asOfDate");

        // Baseline defaults vs Archetype traits
        assertThat(opAnalysis.sharedBaselineFields()).containsEntry("processedBy", "upstream-server-B");

        assertThat(opAnalysis.distinguishingTraitFields()).containsKey("riskTier");
        assertThat(opAnalysis.distinguishingTraitFields().get("riskTier")).containsExactly("LOW", "HIGH");
        assertThat(opAnalysis.distinguishingTraitFields()).containsKey("kycStatus");
        assertThat(opAnalysis.distinguishingTraitFields().get("kycStatus")).containsExactly("VERIFIED", "PENDING");
        assertThat(opAnalysis.distinguishingTraitFields()).containsKey("segment");
        assertThat(opAnalysis.distinguishingTraitFields().get("segment")).containsExactly("PREMIER", "STANDARD");

        // Test Generalization
        StubMapping generalized = analyzer.generalizeStub(stubCust42, opAnalysis);
        assertThat(generalized.getRequest().getUrlPathTemplate()).isEqualTo("/api/crm/customers/{customerId}/kyc");
        assertThat(generalized.getRequest().getQueryParameters()).containsKey("asOf");
        assertThat(generalized.getResponse().getBody())
                .contains("\"customerId\":\"{{request.path.customerId}}\"")
                .contains("\"asOfDate\":\"{{request.query.asOf.[0]}}\"");
        assertThat(generalized.getResponse().getTransformers()).contains("response-template");
    }
}
