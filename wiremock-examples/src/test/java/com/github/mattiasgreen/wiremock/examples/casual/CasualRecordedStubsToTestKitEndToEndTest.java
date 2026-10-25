package com.github.mattiasgreen.wiremock.examples.casual;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mattiasgreen.wiremock.examples.banking.BankingSimulationHarness;
import com.github.mattiasgreen.wiremock.examples.banking.UpstreamBackendServer;
import com.github.mattiasgreen.wiremock.openapi.analysis.OperationStubAnalysis;
import com.github.mattiasgreen.wiremock.openapi.analysis.ParameterEcho;
import com.github.mattiasgreen.wiremock.openapi.analysis.RecordedStubAnalysisReport;
import com.github.mattiasgreen.wiremock.openapi.analysis.RecordedStubAnalyzer;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.laz.casual.api.buffer.CasualBufferUtils;
import se.laz.casual.api.flags.ErrorState;
import se.laz.casual.client.CasualTcpClient;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallReplyMessage;

/**
 * End-to-end integration test demonstrating how binary Casual XATMI protocol traffic
 * (Netty TCP gateway) can be recorded into WireMock stubs, and generalized via OpenAPI
 * 3.0 analysis into dynamic, portable simulation stubs for SDK bootstrapping and offline testing.
 */
class CasualRecordedStubsToTestKitEndToEndTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName(
            "End-to-End Casual XATMI: Live Proxy Recording -> Identity Pinning Pitfall -> OpenAPI Generalization -> Portable Replay")
    void shouldRecordAndGeneralizeCasualXatmiStubsViaOpenApi() throws Exception {
        try (BankingSimulationHarness harness = new BankingSimulationHarness()) {
            WireMockServer wireMock = harness.getWireMockServer();
            UpstreamBackendServer upstream = harness.getUpstreamBackend();
            String wmBase = wireMock.baseUrl();

            // Connect a real Netty CasualTcpClient to the WireMock Casual Gateway
            try (CasualTcpClient client = new CasualTcpClient(
                    "127.0.0.1", harness.getCasualGateway().getPort(), UUID.randomUUID(), "casual-e2e-test-domain")) {
                client.connect();

                // =========================================================================
                // ACT 1: Execute Live Proxy RPC Call through Netty Gateway & WireMock
                // =========================================================================
                String recordedPayload =
                        """
                        {
                          "customerId": "CUST-42",
                          "amount": 299.00,
                          "productCode": "PLATINUM-CREDIT"
                        }
                        """;

                CasualServiceCallReplyMessage liveReply = client.tpcall("billing/charge", recordedPayload);
                assertThat(liveReply.getError()).isEqualTo(ErrorState.OK);

                String liveJson = CasualBufferUtils.extractPayload(liveReply.getServiceBuffer());
                JsonNode liveNode = objectMapper.readTree(liveJson);
                assertThat(liveNode.path("invoiceId").asText()).isEqualTo("INV-9001");
                assertThat(liveNode.path("customerId").asText()).isEqualTo("CUST-42");
                assertThat(liveNode.path("amount").asDouble()).isEqualTo(299.00);
                assertThat(liveNode.path("productCode").asText()).isEqualTo("PLATINUM-CREDIT");
                assertThat(liveNode.path("status").asText()).isEqualTo("CHARGED");
                assertThat(liveNode.path("processedBy").asText()).isEqualTo("upstream-server-B");

                assertThat(upstream.getBillingCasualCallCount()).isEqualTo(1);

                // Snapshot the recorded proxied event into an active static stub via Admin API
                HttpClient httpClient = HttpClient.newHttpClient();
                HttpResponse<String> snapResp = httpClient.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create(wmBase + "/__admin/stubs/snapshot-filtered"))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(
                                        "{\"project\":\"billing-service\",\"activate\":true,\"onlyProxied\":true}",
                                        StandardCharsets.UTF_8))
                                .build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                assertThat(snapResp.statusCode()).isEqualTo(200);

                // Shut down Upstream Backend Server B completely so we are 100% offline!
                harness.stopUpstreamBackend();
                assertThat(upstream.isRunning()).isFalse();

                // =========================================================================
                // ACT 2: PROVE IDENTITY PINNING PITFALL OF RAW RECORDED CASUAL STUB
                // =========================================================================

                // Sub-case 2A: Querying exact recorded payload succeeds from static stub
                CasualServiceCallReplyMessage offlineReplay = client.tpcall("billing/charge", recordedPayload);
                assertThat(offlineReplay.getError()).isEqualTo(ErrorState.OK);
                String offlineJson = CasualBufferUtils.extractPayload(offlineReplay.getServiceBuffer());
                assertThat(objectMapper.readTree(offlineJson).path("customerId").asText())
                        .isEqualTo("CUST-42");

                // Sub-case 2B: Unrecorded dynamic ID fails because raw recorded stub pinned CUST-42 via equalToJson!
                String dynamicPayload =
                        """
                        {
                          "customerId": "CUST-777",
                          "amount": 499.00,
                          "productCode": "GOLD-CREDIT"
                        }
                        """;
                CasualServiceCallReplyMessage failedReply = client.tpcall("billing/charge", dynamicPayload);
                // Proves the pitfall: The raw recorded stub doesn't match; proxy fallback fails because upstream is
                // offline -> TPESVCERR
                assertThat(failedReply.getError()).isEqualTo(ErrorState.TPESVCERR);

                // =========================================================================
                // ACT 3: OpenAPI Analysis & Generalization of Casual RPC Stub
                // =========================================================================
                String specYaml;
                try (InputStream in = CasualRecordedStubsToTestKitEndToEndTest.class.getResourceAsStream(
                        "/openapi/casual-billing-openapi.yaml")) {
                    assertThat(in).isNotNull();
                    specYaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }

                List<StubMapping> snapshottedStubs = wireMock.listAllStubMappings().getMappings().stream()
                        .filter(s -> s.getName() != null && s.getName().startsWith("[RECORDED"))
                        .filter(s -> s.getRequest().getUrl() != null
                                && s.getRequest().getUrl().contains("billing/charge"))
                        .toList();
                assertThat(snapshottedStubs).isNotEmpty();
                StubMapping rawStub = snapshottedStubs.getFirst();

                RecordedStubAnalyzer analyzer = new RecordedStubAnalyzer();
                RecordedStubAnalysisReport report = analyzer.analyze(specYaml, List.of(rawStub));

                assertThat(report.operations()).hasSize(1);
                OperationStubAnalysis op = report.operations().getFirst();
                assertThat(op.operationId()).isEqualTo("chargeCustomer");
                assertThat(op.openApiPathTemplate()).isEqualTo("/billing/charge");

                // Discover echoed request body parameters
                assertThat(op.parameterEchoes())
                        .extracting(ParameterEcho::parameterName)
                        .contains("$.customerId", "$.amount", "$.productCode");

                // Generalize the stub and register it on WireMock with higher priority (priority 1)
                StubMapping generalized = analyzer.generalizeStub(rawStub, op);
                generalized.setPriority(1);
                wireMock.addStubMapping(generalized);

                // =========================================================================
                // ACT 4: PROVE DYNAMIC OFF-LINE REPLAY OVER LIVE CASUAL NETTY TCP
                // =========================================================================
                CasualServiceCallReplyMessage generalizedReply = client.tpcall("billing/charge", dynamicPayload);
                assertThat(generalizedReply.getError()).isEqualTo(ErrorState.OK);

                String generalizedJson = CasualBufferUtils.extractPayload(generalizedReply.getServiceBuffer());
                JsonNode generalizedNode = objectMapper.readTree(generalizedJson);

                // Customer ID, Product Code, and Amount are now dynamically echoed via Handlebars!
                assertThat(generalizedNode.path("customerId").asText()).isEqualTo("CUST-777");
                assertThat(generalizedNode.path("productCode").asText()).isEqualTo("GOLD-CREDIT");
                assertThat(generalizedNode.path("amount").asDouble()).isEqualTo(499.00);
                assertThat(generalizedNode.path("status").asText()).isEqualTo("CHARGED");

                // Upstream Server B hit count is STILL 1 (offline during the entire Act 2 & 4)
                assertThat(upstream.getBillingCasualCallCount()).isEqualTo(1);
            }
        }
    }
}
