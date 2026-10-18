package com.github.mattiasgreen.wiremock.casual.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mattiasgreen.wiremock.casual.integration.CasualIntegrationTestHarness.ClientInitiatorNode;
import com.github.mattiasgreen.wiremock.casual.integration.CasualIntegrationTestHarness.TransactionOutcome;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.laz.casual.api.flags.ErrorState;
import se.laz.casual.api.flags.TransactionState;
import se.laz.casual.api.xa.XAReturnCode;

/**
 * End-to-End 3-node integration tests verifying {@code CasualWireMockGateway} acting as a
 * transparent Man-in-the-Middle proxy, recorder, and synthetic XA Resource Manager between
 * a transactional Casual Client Initiator (Node 1) and an Upstream Casual Server (Node 3).
 * <p>
 * Run via: {@code ./gradlew :wiremock-extension-casual:quarkusIntegrationTest}
 */
class CasualEndToEndIntegrationTest {

    private CasualIntegrationTestHarness harness;

    @BeforeEach
    void setUp() throws Exception {
        harness = new CasualIntegrationTestHarness();
    }

    @AfterEach
    void tearDown() {
        if (harness != null) {
            harness.close();
        }
    }

    @Test
    @DisplayName("Scenario 1: Transparent 2PC Proxy Pass-Through (Client -> WireMock MitM -> Upstream Server)")
    void shouldProxyServiceCallAndTwoPhaseCommitTransparentlyToUpstreamServer() throws Exception {
        // Ensure no pre-existing stubs exist; disable recording to test pure transparent pass-through
        harness.getWireMockServer().resetAll();

        TransactionOutcome outcome = harness.getClientInitiator()
                .executeInTransaction("casual/example", "{\"customer\":\"Alice\",\"amount\":250}");

        assertThat(outcome.serviceErrorState()).isEqualTo(ErrorState.OK);
        assertThat(outcome.serviceTransactionState()).isEqualTo(TransactionState.TX_ACTIVE);
        assertThat(outcome.replyPayload()).contains("\"processedBy\":\"upstream-server-B\"");
        assertThat(outcome.prepareReturnCode()).isEqualTo(XAReturnCode.XA_OK);
        assertThat(outcome.commitReturnCode()).isEqualTo(XAReturnCode.XA_OK);
        assertThat(outcome.committed()).isTrue();

        // Verify Upstream Server B actually executed the service call AND prepared + committed the exact Xid
        assertThat(harness.getUpstreamNode().getServiceCallCount()).isEqualTo(1);
        assertThat(harness.getUpstreamNode().getPreparedXids()).contains(outcome.xid());
        assertThat(harness.getUpstreamNode().getCommittedXids()).contains(outcome.xid());
    }

    @Test
    @DisplayName("Scenario 2: Transparent Stub Recording & Server Decoupling (Offline Replay with Synthetic 2PC)")
    void shouldRecordUpstreamCallAndReplayWithSynthetic2pcWhenUpstreamServerIsStopped() throws Exception {
        String payload = "{\"orderId\":\"ORD-777\",\"action\":\"RESERVE\"}";

        // 1. First transactional call while Upstream Server B is online -> proxied & recorded into WireMock
        TransactionOutcome liveOutcome = harness.getClientInitiator().executeInTransaction("casual/example", payload);

        assertThat(liveOutcome.committed()).isTrue();
        assertThat(liveOutcome.replyPayload()).contains("\"processedBy\":\"upstream-server-B\"");
        assertThat(harness.getUpstreamNode().getServiceCallCount()).isEqualTo(1);
        assertThat(harness.getWireMockServer().getStubMappings()).isNotEmpty();

        // 2. Shut down Upstream Server B completely!
        harness.stopUpstreamServer();

        // 3. Second transactional call with Upstream Server B offline -> replayed from WireMock stub
        //    and completed via CasualWireMockGateway's synthetic XA_OK 2PC voter
        TransactionOutcome replayOutcome = harness.getClientInitiator().executeInTransaction("casual/example", payload);

        assertThat(replayOutcome.serviceErrorState()).isEqualTo(ErrorState.OK);
        assertThat(replayOutcome.replyPayload()).isEqualTo(liveOutcome.replyPayload());
        assertThat(replayOutcome.prepareReturnCode()).isEqualTo(XAReturnCode.XA_OK);
        assertThat(replayOutcome.commitReturnCode()).isEqualTo(XAReturnCode.XA_OK);
        assertThat(replayOutcome.committed()).isTrue();

        // Upstream call count remains 1 because Upstream Server B was offline during the second call
        assertThat(harness.getUpstreamNode().getServiceCallCount()).isEqualTo(1);
        assertThat(harness.getUpstreamNode().getCommittedXids()).doesNotContain(replayOutcome.xid());
    }

    @Test
    @DisplayName("Scenario 3: Application Fault & Rollback Simulation (TPESVCFAIL + ROLLBACK_ONLY)")
    void shouldTriggerClientRollbackWhenStubReturnsTpesvcfailAndRollbackOnly() throws Exception {
        // Stop Upstream Server B to prove fault simulation is 100% self-contained in WireMock MitM
        harness.stopUpstreamServer();

        harness.getWireMockServer()
                .stubFor(post(urlEqualTo("/__casual/services/casual/example"))
                        .withRequestBody(equalToJson("{\"account\":\"ACC-OVERDRAWN\"}"))
                        .willReturn(aResponse()
                                .withStatus(500)
                                .withHeader("Content-Type", "application/json")
                                .withHeader("X-Casual-Error-State", "TPESVCFAIL")
                                .withHeader("X-Casual-User-Supplied-Error", "4001")
                                .withHeader("X-Casual-Transaction-State", "ROLLBACK_ONLY")
                                .withBody("{\"error\":\"Insufficient funds\"}")));

        TransactionOutcome outcome =
                harness.getClientInitiator().executeInTransaction("casual/example", "{\"account\":\"ACC-OVERDRAWN\"}");

        assertThat(outcome.serviceErrorState()).isEqualTo(ErrorState.TPESVCFAIL);
        assertThat(outcome.userSuppliedError()).isEqualTo(4001L);
        assertThat(outcome.serviceTransactionState()).isEqualTo(TransactionState.ROLLBACK_ONLY);
        assertThat(outcome.replyPayload()).isEqualTo("{\"error\":\"Insufficient funds\"}");
        assertThat(outcome.prepareReturnCode()).isNull();
        assertThat(outcome.commitReturnCode()).isNull();
        assertThat(outcome.rollbackReturnCode()).isEqualTo(XAReturnCode.XA_OK);
        assertThat(outcome.committed()).isFalse();
    }

    @Test
    @DisplayName("Scenario 4: 2PC Phase 1 Chaos Simulation (Service Call Succeeds, Prepare Returns XAER_RMERR)")
    void shouldAbortGlobalTransactionWhenStubInjectsXaerRmerrDuringPrepare() throws Exception {
        harness.stopUpstreamServer();

        harness.getWireMockServer()
                .stubFor(post(urlEqualTo("/__casual/services/casual/example"))
                        .withRequestBody(equalToJson("{\"chaos\":\"prepare-rmerr\"}"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withHeader("X-Casual-XA-Prepare-Vote", "XAER_RMERR")
                                .withBody("{\"status\":\"CALL_OK_BUT_DB_CRASHES_ON_PREPARE\"}")));

        TransactionOutcome outcome =
                harness.getClientInitiator().executeInTransaction("casual/example", "{\"chaos\":\"prepare-rmerr\"}");

        // Service call itself succeeded with TX_ACTIVE, but 2PC Phase 1 prepare voted XAER_RMERR
        assertThat(outcome.serviceErrorState()).isEqualTo(ErrorState.OK);
        assertThat(outcome.serviceTransactionState()).isEqualTo(TransactionState.TX_ACTIVE);
        assertThat(outcome.prepareReturnCode()).isEqualTo(XAReturnCode.XAER_RMERR);
        assertThat(outcome.commitReturnCode()).isNull();
        assertThat(outcome.committed()).isFalse();
    }

    @Test
    @DisplayName(
            "Scenario 5: In-Process CasualProxyTransformer logs 200 OK PROXIED in WireMock Journal and forwards 2PC")
    void shouldLogProxiedServeEventInWireMockJournalWhenCasualProxyStubIsActive() throws Exception {
        harness.getWireMockServer().resetAll();

        harness.getWireMockServer()
                .stubFor(post(com.github.tomakehurst.wiremock.client.WireMock.urlMatching("/__casual/services/.*"))
                        .withName("Live Casual Proxy (Billing)")
                        .atPriority(10)
                        .withMetadata(java.util.Map.of("project", "billing-service", "mode", "proxy"))
                        .willReturn(aResponse().withStatus(200).withTransformers("casual-proxy-transformer")));

        TransactionOutcome outcome = harness.getClientInitiator()
                .executeInTransaction("billing/charge", "{\"customerId\":\"CUST-42\",\"amount\":299}");

        assertThat(outcome.committed()).isTrue();
        assertThat(outcome.replyPayload()).contains("\"processedBy\":\"upstream-server-B\"");
        assertThat(harness.getUpstreamNode().getServiceCallCount()).isEqualTo(1);
        assertThat(harness.getUpstreamNode().getPreparedXids()).contains(outcome.xid());
        assertThat(harness.getUpstreamNode().getCommittedXids()).contains(outcome.xid());

        // Verify WireMock Request Journal recorded the call as a matched proxy event with the upstream response body
        var serveEvents = harness.getWireMockServer().getAllServeEvents();
        assertThat(serveEvents).hasSize(1);
        var event = serveEvents.get(0);
        assertThat(event.getWasMatched()).isTrue();
        assertThat(event.getStubMapping().getMetadata().getString("mode")).isEqualTo("proxy");
        assertThat(event.getResponse().getStatus()).isEqualTo(200);
        assertThat(event.getResponse()
                        .getHeaders()
                        .getHeader("X-Casual-Error-State")
                        .firstValue())
                .isEqualTo("OK");
        assertThat(event.getResponse().getBodyAsString()).contains("\"processedBy\":\"upstream-server-B\"");
    }

    @Test
    @DisplayName("Scenario 6: 2PC Phase 1 Forced Rollback Simulation (Stub returns XA_RBROLLBACK on Prepare)")
    void shouldAbortGlobalTransactionWhenStubInjectsXaRbrollbackDuringPrepare() throws Exception {
        harness.stopUpstreamServer();

        harness.getWireMockServer()
                .stubFor(post(urlEqualTo("/__casual/services/casual/example"))
                        .withRequestBody(equalToJson("{\"action\":\"forced-rollback\"}"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withHeader("X-Casual-XA-Prepare-Vote", "XA_RBROLLBACK")
                                .withBody("{\"error\":\"prepare forced rollback by policy\"}")));

        TransactionOutcome outcome =
                harness.getClientInitiator().executeInTransaction("casual/example", "{\"action\":\"forced-rollback\"}");

        // Service call succeeded with TX_ACTIVE, but prepare returned XA_RBROLLBACK
        assertThat(outcome.serviceErrorState()).isEqualTo(ErrorState.OK);
        assertThat(outcome.serviceTransactionState()).isEqualTo(TransactionState.TX_ACTIVE);
        assertThat(outcome.prepareReturnCode()).isEqualTo(XAReturnCode.XA_RBROLLBACK);
        assertThat(outcome.commitReturnCode()).isNull();
        assertThat(outcome.rollbackReturnCode()).isNull();
        assertThat(outcome.committed()).isFalse();
    }

    @Test
    @DisplayName("Scenario 7: 2PC Phase 2 Commit Heuristic Hazard Simulation (Stub returns XA_HEURHAZ on Commit)")
    void shouldReportHeuristicHazardWhenStubInjectsXaHeurhazDuringCommit() throws Exception {
        harness.stopUpstreamServer();

        harness.getWireMockServer()
                .stubFor(post(urlEqualTo("/__casual/services/casual/example"))
                        .withRequestBody(equalToJson("{\"action\":\"commit-hazard\"}"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withHeader("X-Casual-XA-Commit-Vote", "XA_HEURHAZ")
                                .withBody("{\"status\":\"IN_DOUBT_COMMIT_HAZARD\"}")));

        TransactionOutcome outcome =
                harness.getClientInitiator().executeInTransaction("casual/example", "{\"action\":\"commit-hazard\"}");

        assertThat(outcome.serviceErrorState()).isEqualTo(ErrorState.OK);
        assertThat(outcome.prepareReturnCode()).isEqualTo(XAReturnCode.XA_OK);
        assertThat(outcome.commitReturnCode()).isEqualTo(XAReturnCode.XA_HEURHAZ);
        assertThat(outcome.committed()).isFalse();
    }

    @Test
    @DisplayName("Scenario 8: Casual L7 Call Timeout Simulation (HTTP 504 Gateway Timeout -> TPETIME)")
    void shouldSimulateCasualCallTimeoutWhenStubReturnsHttp504() throws Exception {
        harness.stopUpstreamServer();

        harness.getWireMockServer()
                .stubFor(post(urlEqualTo("/__casual/services/casual/example"))
                        .withRequestBody(equalToJson("{\"action\":\"slow-service\"}"))
                        .willReturn(aResponse()
                                .withStatus(504)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"error\":\"gateway timeout\"}")));

        TransactionOutcome outcome =
                harness.getClientInitiator().executeInTransaction("casual/example", "{\"action\":\"slow-service\"}");

        assertThat(outcome.serviceErrorState()).isEqualTo(ErrorState.TPETIME);
        assertThat(outcome.serviceTransactionState()).isEqualTo(TransactionState.ROLLBACK_ONLY);
        assertThat(outcome.prepareReturnCode()).isNull();
        assertThat(outcome.commitReturnCode()).isNull();
        assertThat(outcome.rollbackReturnCode()).isEqualTo(XAReturnCode.XA_OK);
        assertThat(outcome.committed()).isFalse();
    }

    @Test
    @DisplayName("Scenario 9: Multi-Protocol Network Chaos — NetEm L4 TCP Latency via Toxiproxy Admin API")
    void shouldApplyNetEmTcpLatencyImpairmentToCasualGatewayViaToxiproxyApi() throws Exception {
        HttpClient httpClient = HttpClient.newHttpClient();
        ObjectMapper objectMapper = new ObjectMapper();
        String adminBaseUrl = harness.getWireMockServer().baseUrl() + "/__admin";

        // 1. Create a NetEm TCP Proxy via Toxiproxy Admin API targeting CasualWireMockGateway
        String proxyJson =
                """
                {
                    "name": "casual-netem-proxy",
                    "listenPort": 0,
                    "targetHost": "127.0.0.1",
                    "targetPort": %d,
                    "enabled": true
                }
                """
                        .formatted(harness.getMitmGateway().getPort());

        HttpResponse<String> createResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(adminBaseUrl + "/tcp-netem/proxies"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(proxyJson))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(createResp.statusCode()).isEqualTo(201);
        JsonNode createNode = objectMapper.readTree(createResp.body());
        int netEmPort = createNode.get("listenPort").asInt();
        assertThat(netEmPort).isGreaterThan(0);

        // 2. Add 250ms Latency Toxic via Admin API
        String toxicJson =
                """
                {
                    "type": "latency",
                    "name": "casual-downstream-latency",
                    "stream": "downstream",
                    "toxicity": 1.0,
                    "attributes": {
                        "latency": 250,
                        "jitter": 0
                    }
                }
                """;

        HttpResponse<String> toxicResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(adminBaseUrl + "/tcp-netem/proxies/casual-netem-proxy/toxics"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(toxicJson))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(toxicResp.statusCode()).isEqualTo(200);

        // 3. Connect a transactional client through the NetEm impaired TCP proxy port
        try (ClientInitiatorNode netEmClient = new ClientInitiatorNode("127.0.0.1", netEmPort)) {
            netEmClient.connect();

            long startTime = System.nanoTime();
            TransactionOutcome outcome =
                    netEmClient.executeInTransaction("casual/example", "{\"test\":\"netem-latency\"}");
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);

            assertThat(outcome.committed()).isTrue();
            assertThat(outcome.serviceErrorState()).isEqualTo(ErrorState.OK);
            assertThat(outcome.replyPayload()).contains("\"processedBy\":\"upstream-server-B\"");
            // Verifies the NetEm virtual-thread latency was applied over the wire
            assertThat(durationMs).isGreaterThanOrEqualTo(200);
        }
    }

    @Test
    @DisplayName("Scenario 10: Concurrent Execution & Multi-Threaded State Isolation")
    void shouldExecuteConcurrentHappyAndFaultTransactionsWithoutStatePollution() throws Exception {
        harness.stopUpstreamServer();

        harness.getWireMockServer()
                .stubFor(post(urlEqualTo("/__casual/services/casual/example"))
                        .withRequestBody(equalToJson("{\"client\":\"happy\"}"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"status\":\"happy-success\"}")));

        harness.getWireMockServer()
                .stubFor(post(urlEqualTo("/__casual/services/casual/example"))
                        .withRequestBody(equalToJson("{\"client\":\"faulty\"}"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withHeader("X-Casual-XA-Prepare-Vote", "XA_RBROLLBACK")
                                .withBody("{\"error\":\"isolated-failure\"}")));

        // Execute both transactions concurrently across two virtual/platform threads
        CompletableFuture<TransactionOutcome> happyFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return harness.getClientInitiator().executeInTransaction("casual/example", "{\"client\":\"happy\"}");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        CompletableFuture<TransactionOutcome> faultFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return harness.getClientInitiator().executeInTransaction("casual/example", "{\"client\":\"faulty\"}");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        TransactionOutcome happyOutcome = happyFuture.get(10, TimeUnit.SECONDS);
        TransactionOutcome faultOutcome = faultFuture.get(10, TimeUnit.SECONDS);

        // Happy transaction must commit successfully
        assertThat(happyOutcome.serviceErrorState()).isEqualTo(ErrorState.OK);
        assertThat(happyOutcome.prepareReturnCode()).isEqualTo(XAReturnCode.XA_OK);
        assertThat(happyOutcome.commitReturnCode()).isEqualTo(XAReturnCode.XA_OK);
        assertThat(happyOutcome.committed()).isTrue();

        // Faulty transaction must abort at prepare stage without contaminating the happy transaction
        assertThat(faultOutcome.serviceErrorState()).isEqualTo(ErrorState.OK);
        assertThat(faultOutcome.prepareReturnCode()).isEqualTo(XAReturnCode.XA_RBROLLBACK);
        assertThat(faultOutcome.committed()).isFalse();

        // Verify gateway override maps are completely empty
        assertThat(harness.getMitmGateway().getPrepareVoteOverridesSize()).isEqualTo(0);
        assertThat(harness.getMitmGateway().getCommitVoteOverridesSize()).isEqualTo(0);
    }
}
