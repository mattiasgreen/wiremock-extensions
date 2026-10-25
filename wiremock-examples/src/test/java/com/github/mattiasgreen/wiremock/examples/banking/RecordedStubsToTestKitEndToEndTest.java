package com.github.mattiasgreen.wiremock.examples.banking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.mattiasgreen.wiremock.examples.banking.sdk.CreditCardOnboardingEvaluator;
import com.github.mattiasgreen.wiremock.examples.banking.sdk.CreditCardOnboardingEvaluator.EvaluationResult;
import com.github.mattiasgreen.wiremock.examples.banking.sdk.CrmClient;
import com.github.mattiasgreen.wiremock.examples.banking.sdk.CrmCustomerMother;
import com.github.mattiasgreen.wiremock.examples.banking.sdk.CrmSimulationTestKit;
import com.github.mattiasgreen.wiremock.examples.banking.sdk.CustomerKycProfile;
import com.github.mattiasgreen.wiremock.examples.banking.sdk.HttpCrmClient;
import com.github.mattiasgreen.wiremock.examples.banking.sdk.KycStatus;
import com.github.mattiasgreen.wiremock.openapi.analysis.OperationStubAnalysis;
import com.github.mattiasgreen.wiremock.openapi.analysis.ParameterEcho;
import com.github.mattiasgreen.wiremock.openapi.analysis.RecordedStubAnalysisReport;
import com.github.mattiasgreen.wiremock.openapi.analysis.RecordedStubAnalyzer;
import com.github.mattiasgreen.wiremock.openapi.analysis.TemporalFieldInfo;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

class RecordedStubsToTestKitEndToEndTest {

    public enum ExecutionMode {
        LEVEL_2_MOCKITO,
        LEVEL_3_WIREMOCK
    }

    @Test
    @DisplayName("Act 1-3: Demonstrate pitfalls of out-of-the-box recorded stubs and generalize via OpenAPI analysis")
    void shouldDemonstratePitfallsOfRawRecordedStubsAndGeneralizeViaOpenApi() throws Exception {
        try (BankingSimulationHarness harness = new BankingSimulationHarness()) {
            WireMockServer wireMock = harness.getWireMockServer();
            String wmBase = wireMock.baseUrl();
            CrmClient liveProxyClient = new HttpCrmClient(wmBase);

            // =========================================================================
            // ACT 1: Execute 2 E2E Scenarios through Live Proxy, then Snapshot Recorded Stubs
            // =========================================================================
            LocalDate recordDate = LocalDate.of(2026, 10, 25);
            CustomerKycProfile p1 = liveProxyClient.getCustomerKyc("CUST-42", recordDate);
            assertThat(p1.fullName()).isEqualTo("Eleanor Vance");
            assertThat(p1.kycStatus()).isEqualTo(KycStatus.VERIFIED);

            CustomerKycProfile p2 = liveProxyClient.getCustomerKyc("CUST-88", recordDate);
            assertThat(p2.fullName()).isEqualTo("Marcus Thorne");
            assertThat(p2.kycStatus()).isEqualTo(KycStatus.PENDING);

            // Snapshot the recorded proxied events directly into active stubs via Admin API
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpResponse<String> snapResp = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(wmBase + "/__admin/stubs/snapshot-filtered"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"project\":\"crm-service\",\"activate\":true,\"onlyProxied\":true}",
                                    StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(snapResp.statusCode()).isEqualTo(200);
            com.fasterxml.jackson.databind.JsonNode snapJson =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(snapResp.body());
            assertThat(snapJson.path("status").asText()).isEqualTo("active");

            // Shut down Upstream Backend Server B completely so we are 100% offline!
            harness.stopUpstreamBackend();

            // Verification of happy path against raw recorded stub (exact recorded ID + exact date)
            CustomerKycProfile offlineP1 = liveProxyClient.getCustomerKyc("CUST-42", recordDate);
            assertThat(offlineP1.fullName()).isEqualTo("Eleanor Vance");

            // =========================================================================
            // ACT 2: PROVE PITFALLS OF RAW RECORDED STUBS
            // =========================================================================

            // Pitfall 1: Identity Pinning (Querying unrecorded ID fails because stub pinned CUST-42)
            assertThatThrownBy(() -> liveProxyClient.getCustomerKyc("CUST-777", recordDate))
                    .isInstanceOf(RuntimeException.class);

            // Pitfall 2: Temporal Request Brittle-Lock (Querying next day fails because urlEqualTo pinned
            // ?asOf=2026-10-25)
            assertThatThrownBy(() -> liveProxyClient.getCustomerKyc("CUST-42", recordDate.plusDays(1)))
                    .isInstanceOf(RuntimeException.class);

            // =========================================================================
            // ACT 3: Deterministic OpenAPI + Stub Analysis & Generalization
            // =========================================================================
            String specYaml;
            try (InputStream in =
                    RecordedStubsToTestKitEndToEndTest.class.getResourceAsStream("/openapi/banking-crm-openapi.yaml")) {
                assertThat(in).isNotNull();
                specYaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }

            List<StubMapping> snapshottedStubs = wireMock.listAllStubMappings().getMappings().stream()
                    .filter(s -> s.getName() != null && s.getName().startsWith("[RECORDED"))
                    .toList();
            assertThat(snapshottedStubs).hasSize(2);

            RecordedStubAnalyzer analyzer = new RecordedStubAnalyzer();
            RecordedStubAnalysisReport report = analyzer.analyze(specYaml, snapshottedStubs);

            assertThat(report.operations()).hasSize(1);
            OperationStubAnalysis opAnalysis = report.operations().getFirst();
            assertThat(opAnalysis.operationId()).isEqualTo("getCustomerKyc");
            assertThat(opAnalysis.openApiPathTemplate()).isEqualTo("/api/crm/customers/{customerId}/kyc");
            assertThat(opAnalysis.responseSchemaName()).isEqualTo("CustomerKycProfile");

            // Assert Parameter Echoes discovered
            assertThat(opAnalysis.parameterEchoes())
                    .extracting(ParameterEcho::parameterName)
                    .contains("customerId", "asOf");

            // Assert Temporal Fields discovered
            assertThat(opAnalysis.temporalFields())
                    .extracting(TemporalFieldInfo::jsonPointer)
                    .contains("/onboardedDate", "/asOfDate");

            // Assert Distinguishing Archetype Traits discovered
            assertThat(opAnalysis.distinguishingTraitFields()).containsKey("riskTier");
            assertThat(opAnalysis.distinguishingTraitFields()).containsKey("kycStatus");
            assertThat(opAnalysis.distinguishingTraitFields()).containsKey("segment");

            // Generalize the stub and register it at top priority (1)
            StubMapping generalized = analyzer.generalizeStub(snapshottedStubs.getFirst(), opAnalysis);
            generalized.setPriority(1);
            wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                            com.github.tomakehurst.wiremock.client.WireMock.urlPathTemplate(
                                    "/api/crm/customers/{customerId}/kyc"))
                    .atPriority(1)
                    .withQueryParam("asOf", com.github.tomakehurst.wiremock.client.WireMock.matching(".+"))
                    .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(generalized.getResponse().getBody())
                            .withTransformers("response-template")));

            // PROVE: Now unrecorded ID (CUST-777) AND future date (2030-01-01) succeed and echo properly!
            CustomerKycProfile generalizedProfile =
                    liveProxyClient.getCustomerKyc("CUST-777", LocalDate.of(2030, 1, 1));
            assertThat(generalizedProfile.customerId()).isEqualTo("CUST-777");
            assertThat(generalizedProfile.asOfDate()).isEqualTo(LocalDate.of(2030, 1, 1));
        }
    }

    @ParameterizedTest(name = "Act 4: BDD scenarios with TestKit over {0}")
    @EnumSource(ExecutionMode.class)
    @DisplayName("Act 4: Dual-Mode BDD Verification across Level 2 (Mockito) and Level 3 (WireMock)")
    void shouldExecuteIdenticalBddScenariosAcrossMockitoAndWireMockUsingTestKit(ExecutionMode mode) throws Exception {
        WireMockServer wireMockServer = null;
        CrmClient crmClient;
        CrmSimulationTestKit testKit;

        if (mode == ExecutionMode.LEVEL_2_MOCKITO) {
            crmClient = Mockito.mock(CrmClient.class);
            testKit = CrmSimulationTestKit.forMockito(crmClient);
        } else {
            wireMockServer =
                    new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
            wireMockServer.start();
            testKit = CrmSimulationTestKit.forWireMock(wireMockServer);
            crmClient = new HttpCrmClient(wireMockServer.baseUrl());
        }

        try {
            CreditCardOnboardingEvaluator evaluator = new CreditCardOnboardingEvaluator(crmClient);
            LocalDate evalDate = LocalDate.of(2036, 5, 1); // 10 years in future
            String dynamicVipId = "CUST-DYN-" + UUID.randomUUID();

            // =========================================================================
            // SCENARIO 1: Premier VIP Customer with > 2 years relative tenure
            // =========================================================================
            CustomerKycProfile vipProfile = CrmCustomerMother.premierVipCustomer()
                    .customerId(dynamicVipId)
                    .onboardedDate(evalDate.minusYears(3)) // Dynamically sliding relative date
                    .asOfDate(evalDate)
                    .build();

            testKit.stubCustomerKyc(vipProfile);

            EvaluationResult vipResult = evaluator.evaluate(dynamicVipId, evalDate);
            assertThat(vipResult.approved()).isTrue();
            assertThat(vipResult.feeCode()).isEqualTo("WAIVED_ANNUAL_FEE_VIP");
            assertThat(vipResult.feeAmount()).isEqualTo(0.0);

            // =========================================================================
            // SCENARIO 2: High-Risk Newly Onboarded Customer
            // =========================================================================
            String dynamicRiskId = "CUST-RISK-" + UUID.randomUUID();
            CustomerKycProfile riskProfile = CrmCustomerMother.highRiskNewCustomer()
                    .customerId(dynamicRiskId)
                    .asOfDate(evalDate)
                    .build();

            testKit.stubCustomerKyc(riskProfile);

            EvaluationResult riskResult = evaluator.evaluate(dynamicRiskId, evalDate);
            assertThat(riskResult.approved()).isFalse();
            assertThat(riskResult.feeCode()).isEqualTo("REJECTED_KYC_PENDING");

            // =========================================================================
            // SCENARIO 3: Provider Domain Invariant Enforcement (Fast Fail in Builder)
            // =========================================================================
            assertThatThrownBy(() -> CrmCustomerMother.premierVipCustomer()
                            .kycStatus(KycStatus.REJECTED)
                            .build())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Provider invariant violation");

        } finally {
            if (wireMockServer != null && wireMockServer.isRunning()) {
                wireMockServer.stop();
            }
        }
    }
}
