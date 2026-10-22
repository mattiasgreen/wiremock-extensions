package com.github.mattiasgreen.wiremock.casual.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mattiasgreen.wiremock.casual.integration.CasualIntegrationTestHarness.ClientInitiatorNode;
import com.github.mattiasgreen.wiremock.casual.integration.CasualIntegrationTestHarness.TransactionOutcome;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.laz.casual.api.buffer.type.ServiceBuffer;
import se.laz.casual.api.flags.ErrorState;
import se.laz.casual.api.flags.TransactionState;
import se.laz.casual.api.xa.XAReturnCode;
import se.laz.casual.network.protocol.messages.domain.CasualDomainDiscoveryReplyMessage;
import se.laz.casual.network.protocol.messages.domain.Service;

/**
 * End-to-End 3-node integration suite verifying {@code CasualWireMockGateway} as a
 * transparent Man-in-the-Middle proxy, recorder, synthetic XA voter, and chaos interceptor:
 * <ol>
 *   <li><b>Scenario 1: Transparent 2PC Proxy Pass-Through &amp; Unified Journaling</b></li>
 *   <li><b>Scenario 2: Transparent Stub Recording &amp; Server Decoupling (Offline Replay)</b></li>
 *   <li><b>Scenario 3: 2PC Resilience &amp; Heuristic Fault Injection (Prepare, Commit &amp; Timeout)</b></li>
 *   <li><b>Scenario 4: Multi-Protocol L4 Chaos &amp; Concurrent State Isolation (NetEm TCP Latency)</b></li>
 *   <li><b>Scenario 5: Transparent Discovery Aggregation Across Multi-Node Topology</b></li>
 *   <li><b>Scenario 6: Transparent Non-JSON Fielded Binary Buffer 2PC Pass-Through</b></li>
 * </ol>
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
    @DisplayName("Scenario 1: Transparent 2PC Proxy Pass-Through & Unified Journaling (Client -> MitM -> Upstream)")
    void shouldProxyServiceCallAndTwoPhaseCommitTransparentlyWithJournalLogging() throws Exception {
        harness.getWireMockServer().resetAll();

        harness.getWireMockServer()
                .stubFor(post(urlMatching("/__casual/services/.*"))
                        .withName("Live Casual Proxy (Billing)")
                        .atPriority(10)
                        .withMetadata(Map.of("project", "billing-service", "mode", "proxy"))
                        .willReturn(aResponse().withStatus(200).withTransformers("casual-proxy-transformer")));

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

        // Verify WireMock Request Journal recorded the call as a matched proxy event with upstream response
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
    @DisplayName("Scenario 2: Transparent Stub Recording & Server Decoupling (Offline Replay with Synthetic 2PC)")
    void shouldRecordUpstreamCallAndReplayWithSynthetic2pcWhenUpstreamServerIsStopped() throws Exception {
        String payload = "{\"orderId\":\"ORD-777\",\"action\":\"RESERVE\"}";

        // 1. Live transactional call while Upstream Server B is online -> proxied & recorded
        TransactionOutcome liveOutcome = harness.getClientInitiator().executeInTransaction("casual/example", payload);

        assertThat(liveOutcome.committed()).isTrue();
        assertThat(liveOutcome.replyPayload()).contains("\"processedBy\":\"upstream-server-B\"");
        assertThat(harness.getUpstreamNode().getServiceCallCount()).isEqualTo(1);
        assertThat(harness.getWireMockServer().getStubMappings()).isNotEmpty();

        // 2. Shut down Upstream Server B completely
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
    @DisplayName("Scenario 3: 2PC Resilience & Heuristic Fault Injection (Prepare Abort, Heuristic Hazard & Timeout)")
    void shouldSimulateTwoPhaseCommitFaultsAndHeuristicOverridesOffline() throws Exception {
        harness.stopUpstreamServer();

        // 1. L7 Call Timeout Simulation (HTTP 504 -> TPETIME & ROLLBACK_ONLY)
        harness.getWireMockServer()
                .stubFor(post(urlEqualTo("/__casual/services/casual/example"))
                        .withRequestBody(equalToJson("{\"fault\":\"timeout\"}"))
                        .willReturn(aResponse()
                                .withStatus(504)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"error\":\"gateway timeout\"}")));

        TransactionOutcome timeoutOutcome =
                harness.getClientInitiator().executeInTransaction("casual/example", "{\"fault\":\"timeout\"}");
        assertThat(timeoutOutcome.serviceErrorState()).isEqualTo(ErrorState.TPETIME);
        assertThat(timeoutOutcome.serviceTransactionState()).isEqualTo(TransactionState.ROLLBACK_ONLY);
        assertThat(timeoutOutcome.prepareReturnCode()).isNull();
        assertThat(timeoutOutcome.rollbackReturnCode()).isEqualTo(XAReturnCode.XA_OK);
        assertThat(timeoutOutcome.committed()).isFalse();

        // 2. 2PC Phase 1 Forced Rollback Simulation (X-Casual-XA-Prepare-Vote -> XA_RBROLLBACK)
        harness.getWireMockServer()
                .stubFor(post(urlEqualTo("/__casual/services/casual/example"))
                        .withRequestBody(equalToJson("{\"fault\":\"prepare-rollback\"}"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withHeader("X-Casual-XA-Prepare-Vote", "XA_RBROLLBACK")
                                .withBody("{\"error\":\"database rejected prepare\"}")));

        TransactionOutcome prepOutcome =
                harness.getClientInitiator().executeInTransaction("casual/example", "{\"fault\":\"prepare-rollback\"}");
        assertThat(prepOutcome.serviceErrorState()).isEqualTo(ErrorState.OK);
        assertThat(prepOutcome.prepareReturnCode()).isEqualTo(XAReturnCode.XA_RBROLLBACK);
        assertThat(prepOutcome.commitReturnCode()).isNull();
        assertThat(prepOutcome.rollbackReturnCode()).isNull();
        assertThat(prepOutcome.committed()).isFalse();

        // 3. 2PC Phase 2 Heuristic Hazard Simulation (X-Casual-XA-Commit-Vote -> XA_HEURHAZ)
        harness.getWireMockServer()
                .stubFor(post(urlEqualTo("/__casual/services/casual/example"))
                        .withRequestBody(equalToJson("{\"fault\":\"commit-hazard\"}"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withHeader("X-Casual-XA-Commit-Vote", "XA_HEURHAZ")
                                .withBody("{\"status\":\"IN_DOUBT_COMMIT_HAZARD\"}")));

        TransactionOutcome commitOutcome =
                harness.getClientInitiator().executeInTransaction("casual/example", "{\"fault\":\"commit-hazard\"}");
        assertThat(commitOutcome.serviceErrorState()).isEqualTo(ErrorState.OK);
        assertThat(commitOutcome.prepareReturnCode()).isEqualTo(XAReturnCode.XA_OK);
        assertThat(commitOutcome.commitReturnCode()).isEqualTo(XAReturnCode.XA_HEURHAZ);
        assertThat(commitOutcome.committed()).isFalse();
    }

    @Test
    @DisplayName("Scenario 4: Multi-Protocol L4 Chaos & Concurrent State Isolation (NetEm Latency via Toxiproxy API)")
    void shouldExecuteConcurrentTransactionsUnderNetEmTcpLatencyWithoutStatePollution() throws Exception {
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
        int netEmPort =
                objectMapper.readTree(createResp.body()).get("listenPort").asInt();
        assertThat(netEmPort).isGreaterThan(0);

        // 2. Add 200ms Latency Toxic via Admin API
        String toxicJson =
                """
                {
                    "type": "latency",
                    "name": "casual-downstream-latency",
                    "stream": "downstream",
                    "toxicity": 1.0,
                    "attributes": {
                        "latency": 200,
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

        // 3. Configure stubs for concurrent happy and faulty transactions (Server B offline)
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

        // 4. Connect concurrent transactional clients through the NetEm impaired TCP proxy port
        try (ClientInitiatorNode happyClient = new ClientInitiatorNode("127.0.0.1", netEmPort);
                ClientInitiatorNode faultClient = new ClientInitiatorNode("127.0.0.1", netEmPort)) {
            happyClient.connect();
            faultClient.connect();

            long startTime = System.nanoTime();

            CompletableFuture<TransactionOutcome> happyFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return happyClient.executeInTransaction("casual/example", "{\"client\":\"happy\"}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            CompletableFuture<TransactionOutcome> faultFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return faultClient.executeInTransaction("casual/example", "{\"client\":\"faulty\"}");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            TransactionOutcome happyOutcome = happyFuture.get(10, TimeUnit.SECONDS);
            TransactionOutcome faultOutcome = faultFuture.get(10, TimeUnit.SECONDS);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime);

            // Verifies the NetEm virtual-thread latency was applied over the wire
            assertThat(durationMs).isGreaterThanOrEqualTo(150);

            // Happy transaction must commit successfully
            assertThat(happyOutcome.serviceErrorState()).isEqualTo(ErrorState.OK);
            assertThat(happyOutcome.prepareReturnCode()).isEqualTo(XAReturnCode.XA_OK);
            assertThat(happyOutcome.commitReturnCode()).isEqualTo(XAReturnCode.XA_OK);
            assertThat(happyOutcome.committed()).isTrue();

            // Faulty transaction must abort at prepare stage without contaminating the happy transaction
            assertThat(faultOutcome.serviceErrorState()).isEqualTo(ErrorState.OK);
            assertThat(faultOutcome.prepareReturnCode()).isEqualTo(XAReturnCode.XA_RBROLLBACK);
            assertThat(faultOutcome.committed()).isFalse();

            // Verify gateway override maps are completely clean
            assertThat(harness.getMitmGateway().getPrepareVoteOverridesSize()).isEqualTo(0);
            assertThat(harness.getMitmGateway().getCommitVoteOverridesSize()).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("Scenario 5: Transparent Discovery Aggregation Across Multi-Node Topology")
    void shouldAggregateDiscoveryFromUpstreamServerAndLocalMitmStubs() throws Exception {
        // Stub a local service on WireMock MitM
        harness.getWireMockServer()
                .stubFor(post(urlEqualTo("/__casual/services/localMitmPayment"))
                        .willReturn(aResponse().withStatus(200).withBody("{\"stub\":true}")));

        // Query discovery from Client Initiator (Node 1) -> MitM Gateway (Node 2)
        CasualDomainDiscoveryReplyMessage discoveryReply = harness.getClientInitiator()
                .getTcpClient()
                .discoverServices(List.of("casual/example", "localMitmPayment"));

        assertThat(discoveryReply).isNotNull();
        List<String> discoveredServices =
                discoveryReply.getServices().stream().map(Service::getName).toList();

        // Must discover both upstream Server B service ("casual/example") and MitM stub ("localMitmPayment")
        assertThat(discoveredServices).contains("casual/example", "localMitmPayment");
    }

    @Test
    @DisplayName("Scenario 6: Transparent Non-JSON Fielded Binary Buffer 2PC Pass-Through")
    void shouldProxyNonJsonFieldedBufferAndCommitTwoPhaseCommit() throws Exception {
        byte[] fieldedBytes = new byte[] {0x00, 0x05, 0x41, 0x42, 0x43};
        ServiceBuffer fieldedBuffer = ServiceBuffer.of("CFIELD/", List.of(fieldedBytes));

        TransactionOutcome outcome = harness.getClientInitiator().executeInTransaction("casual/example", fieldedBuffer);

        assertThat(outcome.serviceErrorState()).isEqualTo(ErrorState.OK);
        assertThat(outcome.serviceTransactionState()).isEqualTo(TransactionState.TX_ACTIVE);
        assertThat(outcome.prepareReturnCode()).isEqualTo(XAReturnCode.XA_OK);
        assertThat(outcome.commitReturnCode()).isEqualTo(XAReturnCode.XA_OK);
        assertThat(outcome.committed()).isTrue();

        assertThat(harness.getUpstreamNode().getServiceCallCount()).isEqualTo(1);
        assertThat(harness.getUpstreamNode().getCommittedXids()).contains(outcome.xid());
    }
}
