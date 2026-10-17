package com.github.mattiasgreen.wiremock.casual.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.casual.integration.CasualIntegrationTestHarness.TransactionOutcome;
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
}
