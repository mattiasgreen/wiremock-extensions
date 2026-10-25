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

    @Test
    @DisplayName("Should track unmatched stubs and handle unencoded special characters in URLs gracefully")
    void shouldTrackUnmatchedStubsAndHandleSpecialCharactersInUrls() {
        StubMapping unmatchedStub = WireMock.get(WireMock.urlEqualTo("/api/unknown/service?query=[test]|raw"))
                .willReturn(WireMock.aResponse().withStatus(404))
                .build();
        unmatchedStub.setId(UUID.randomUUID());

        RecordedStubAnalyzer analyzer = new RecordedStubAnalyzer();
        RecordedStubAnalysisReport report = analyzer.analyze(CRM_OPENAPI_YAML, List.of(unmatchedStub));

        assertThat(report.operations()).isEmpty();
        assertThat(report.unmatchedStubUrls()).containsExactly("GET /api/unknown/service?query=[test]|raw");
    }

    @Test
    @DisplayName("Should preserve custom corporate routing headers during stub generalization")
    void shouldPreserveConfiguredCustomHeaders() {
        StubMapping stubWithHeaders = WireMock.get(WireMock.urlEqualTo("/api/crm/customers/CUST-42/kyc"))
                .withHeader("X-Tenant-Id", WireMock.equalTo("tenant-nordic"))
                .withHeader("X-Custom-Auth", WireMock.equalTo("ephemeral-token"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("X-Tenant-Id", "tenant-nordic")
                        .withHeader("X-Ephemeral-Session", "sess-123")
                        .withBody("{\"customerId\":\"CUST-42\",\"fullName\":\"Eleanor\"}"))
                .build();
        stubWithHeaders.setId(UUID.randomUUID());

        RecordedStubAnalyzer analyzer = new RecordedStubAnalyzer(java.util.Set.of("content-type", "x-tenant-id"));
        RecordedStubAnalysisReport report = analyzer.analyze(CRM_OPENAPI_YAML, List.of(stubWithHeaders));
        assertThat(report.operations()).hasSize(1);

        StubMapping generalized =
                analyzer.generalizeStub(stubWithHeaders, report.operations().getFirst());

        // Keeps X-Tenant-Id and Content-Type, strips ephemeral headers
        assertThat(generalized.getRequest().getHeaders()).containsKey("X-Tenant-Id");
        assertThat(generalized.getRequest().getHeaders()).doesNotContainKey("X-Custom-Auth");

        assertThat(generalized
                        .getResponse()
                        .getHeaders()
                        .getHeader("X-Tenant-Id")
                        .firstValue())
                .isEqualTo("tenant-nordic");
        assertThat(generalized
                        .getResponse()
                        .getHeaders()
                        .getHeader("X-Ephemeral-Session")
                        .isPresent())
                .isFalse();
    }

    private static final String CASE_MGMT_OPENAPI_YAML =
            """
            openapi: 3.0.3
            info:
              title: Case Management Service
              version: 1.0.0
            paths:
              /api/cases:
                post:
                  operationId: createCase
                  responses:
                    '201':
                      description: Created
              /api/cases/{caseId}:
                get:
                  operationId: getCase
                  parameters:
                    - name: caseId
                      in: path
                      required: true
                      schema:
                        type: string
                  responses:
                    '200':
                      description: Details
                patch:
                  operationId: patchCase
                  parameters:
                    - name: caseId
                      in: path
                      required: true
                      schema:
                        type: string
                  responses:
                    '200':
                      description: Updated
            """;

    @Test
    @DisplayName("Should detect nested JSON echoes and templatize deep JSON pointers")
    void shouldDetectNestedJsonEchoesAndTemplatizeDeepPointers() {
        StubMapping stubDeep = WireMock.get(WireMock.urlEqualTo("/api/cases/CASE-99"))
                .willReturn(
                        WireMock.aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody(
                                        """
                                {
                                  "caseId": "CASE-99",
                                  "details": {
                                    "contact": {
                                      "email": "eleanor@example.com",
                                      "reporterId": "CASE-99"
                                    }
                                  }
                                }
                                """))
                .build();
        stubDeep.setId(UUID.randomUUID());

        RecordedStubAnalyzer analyzer = new RecordedStubAnalyzer();
        RecordedStubAnalysisReport report = analyzer.analyze(CASE_MGMT_OPENAPI_YAML, List.of(stubDeep));

        assertThat(report.operations()).hasSize(1);
        OperationStubAnalysis op = report.operations().getFirst();

        assertThat(op.parameterEchoes())
                .extracting(ParameterEcho::responseJsonPointer)
                .contains("/caseId", "/details/contact/reporterId");

        StubMapping generalized = analyzer.generalizeStub(stubDeep, op);
        assertThat(generalized.getResponse().getBody())
                .contains("\"caseId\":\"{{request.path.caseId}}\"")
                .contains("\"reporterId\":\"{{request.path.caseId}}\"");
    }

    @Test
    @DisplayName("Should detect request body parameter echoes on POST/PUT and synthesize jsonPath templates")
    void shouldDetectRequestBodyParameterEchoesOnPost() {
        StubMapping postStub = WireMock.post(WireMock.urlEqualTo("/api/cases"))
                .withRequestBody(
                        WireMock.equalToJson(
                                """
                        {
                          "title": "Fraud Alert",
                          "requester": {
                            "department": "Risk"
                          }
                        }
                        """))
                .willReturn(
                        WireMock.aResponse()
                                .withStatus(201)
                                .withHeader("Content-Type", "application/json")
                                .withBody(
                                        """
                                {
                                  "id": "CASE-500",
                                  "caseTitle": "Fraud Alert",
                                  "assignedDept": "Risk",
                                  "status": "OPEN"
                                }
                                """))
                .build();
        postStub.setId(UUID.randomUUID());

        RecordedStubAnalyzer analyzer = new RecordedStubAnalyzer();
        RecordedStubAnalysisReport report = analyzer.analyze(CASE_MGMT_OPENAPI_YAML, List.of(postStub));

        assertThat(report.operations()).hasSize(1);
        OperationStubAnalysis op = report.operations().getFirst();

        assertThat(op.parameterEchoes())
                .extracting(ParameterEcho::responseJsonPointer)
                .contains("/caseTitle", "/assignedDept");
        assertThat(op.parameterEchoes())
                .extracting(ParameterEcho::parameterName)
                .contains("$.title", "$.requester.department");
        assertThat(op.parameterEchoes())
                .extracting(ParameterEcho::parameterLocation)
                .containsOnly("body");

        StubMapping generalized = analyzer.generalizeStub(postStub, op);
        assertThat(generalized.getResponse().getBody())
                .contains("\"caseTitle\":\"{{jsonPath request.body '$.title'}}\"")
                .contains("\"assignedDept\":\"{{jsonPath request.body '$.requester.department'}}\"");
    }

    @Test
    @DisplayName("Should detect resource mutations and read-your-own-writes across recording timeline")
    void shouldDetectResourceMutationsAndReadYourOwnWrites() {
        StubMapping getInitial = WireMock.get(WireMock.urlEqualTo("/api/cases/CASE-1"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"CASE-1\",\"status\":\"OPEN\"}"))
                .build();
        getInitial.setId(UUID.randomUUID());

        StubMapping patchStub = WireMock.patch(WireMock.urlEqualTo("/api/cases/CASE-1"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"CASE-1\",\"status\":\"RESOLVED\"}"))
                .build();
        patchStub.setId(UUID.randomUUID());

        StubMapping getAfterPatch = WireMock.get(WireMock.urlEqualTo("/api/cases/CASE-1"))
                .willReturn(WireMock.aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"CASE-1\",\"status\":\"RESOLVED\"}"))
                .build();
        getAfterPatch.setId(UUID.randomUUID());

        RecordedStubAnalyzer analyzer = new RecordedStubAnalyzer();
        RecordedStubAnalysisReport report =
                analyzer.analyze(CASE_MGMT_OPENAPI_YAML, List.of(getInitial, patchStub, getAfterPatch));

        assertThat(report.detectedMutations()).hasSize(1);
        ResourceMutation mutation = report.detectedMutations().getFirst();
        assertThat(mutation.resourcePathTemplate()).isEqualTo("/api/cases/{caseId}");
        assertThat(mutation.mutatingMethod()).isEqualTo("PATCH");
        assertThat(mutation.mutatedFields()).contains("/status");
        assertThat(mutation.readYourOwnWritesDetected()).isTrue();
    }
}
