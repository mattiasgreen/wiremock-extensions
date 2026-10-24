package com.github.mattiasgreen.wiremock.casual;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.UUID;
import javax.transaction.xa.Xid;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import se.laz.casual.api.buffer.type.JsonBuffer;
import se.laz.casual.api.buffer.type.ServiceBuffer;
import se.laz.casual.api.flags.ErrorState;
import se.laz.casual.api.flags.Flag;
import se.laz.casual.api.flags.TransactionState;
import se.laz.casual.api.xa.XAReturnCode;
import se.laz.casual.api.xa.XID;
import se.laz.casual.network.ProtocolVersion;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallReplyMessage;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallRequestMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceCommitReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceCommitRequestMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourcePrepareReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourcePrepareRequestMessage;

/**
 * Fast in-memory unit tests for {@link CasualWireMockGateway}.
 * Tests HTTP-to-Casual status code mappings, error states, and thread-safe XA transaction overrides.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CasualWireMockGatewayUnitTest {

    private WireMockServer wireMockServer;
    private CasualWireMockGateway gateway;

    @BeforeAll
    void start() {
        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        wireMockServer.start();

        gateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(wireMockServer)
                .build();
    }

    @AfterAll
    void stop() {
        if (gateway != null) {
            gateway.close();
        }
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    @DisplayName("Unit Test: HTTP Status Codes & Error Headers cleanly map to Casual ErrorState")
    void testCleanStatusMappingToCasualErrorStates() {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/test/ok"))
                .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"ok\"}")));

        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/test/fail"))
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":\"failed\"}")));

        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/test/timeout"))
                .willReturn(aResponse().withStatus(504).withBody("{\"error\":\"gateway timeout\"}")));

        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/test/inval"))
                .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"bad request\"}")));

        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/test/custom-err"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("X-Casual-Error-State", "TPESVCERR")
                        .withHeader("X-Casual-User-Supplied-Error", "8888")
                        .withBody("{\"error\":\"custom error\"}")));

        // 1. HTTP 200 -> OK + TX_ACTIVE
        CasualServiceCallReplyMessage okReply = gateway.handleServiceCall(buildRequest("test/ok", "{}"));
        assertThat(okReply.getError()).isEqualTo(ErrorState.OK);
        assertThat(okReply.getTransactionState()).isEqualTo(TransactionState.TX_ACTIVE);

        // 2. HTTP 500 -> TPESVCFAIL + ROLLBACK_ONLY
        CasualServiceCallReplyMessage failReply = gateway.handleServiceCall(buildRequest("test/fail", "{}"));
        assertThat(failReply.getError()).isEqualTo(ErrorState.TPESVCFAIL);
        assertThat(failReply.getTransactionState()).isEqualTo(TransactionState.ROLLBACK_ONLY);

        // 3. HTTP 504 -> TPETIME + ROLLBACK_ONLY
        CasualServiceCallReplyMessage timeoutReply = gateway.handleServiceCall(buildRequest("test/timeout", "{}"));
        assertThat(timeoutReply.getError()).isEqualTo(ErrorState.TPETIME);
        assertThat(timeoutReply.getTransactionState()).isEqualTo(TransactionState.ROLLBACK_ONLY);

        // 4. HTTP 400 -> TPEINVAL + ROLLBACK_ONLY
        CasualServiceCallReplyMessage invalReply = gateway.handleServiceCall(buildRequest("test/inval", "{}"));
        assertThat(invalReply.getError()).isEqualTo(ErrorState.TPEINVAL);
        assertThat(invalReply.getTransactionState()).isEqualTo(TransactionState.ROLLBACK_ONLY);

        // 5. Custom header TPESVCERR + user error 8888
        CasualServiceCallReplyMessage customReply = gateway.handleServiceCall(buildRequest("test/custom-err", "{}"));
        assertThat(customReply.getError()).isEqualTo(ErrorState.TPESVCERR);
        assertThat(customReply.getUserDefinedCode()).isEqualTo(8888L);
        assertThat(customReply.getTransactionState()).isEqualTo(TransactionState.ROLLBACK_ONLY);
    }

    @Test
    @DisplayName("Unit Test: Thread-safe XA Prepare vote overrides & automatic cleanup on abort")
    void testThreadSafeXidOverridesAndPrepareAbortCleanup() {
        Xid xidFault = XID.of(new byte[] {1, 2, 3}, new byte[] {4, 5, 6}, 42);
        Xid xidHappy = XID.of(new byte[] {7, 8, 9}, new byte[] {10, 11, 12}, 42);

        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/order/charge"))
                .withRequestBody(equalToJson("{\"item\":\"faulty\"}"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("X-Casual-XA-Prepare-Vote", "XA_RBROLLBACK")
                        .withBody("{\"error\":\"db prepare crash\"}")));

        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/order/charge"))
                .withRequestBody(equalToJson("{\"item\":\"happy\"}"))
                .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"success\"}")));

        // Execute both transactional calls
        gateway.handleServiceCall(buildTransactionalRequest("order/charge", "{\"item\":\"faulty\"}", xidFault));
        gateway.handleServiceCall(buildTransactionalRequest("order/charge", "{\"item\":\"happy\"}", xidHappy));

        // Verify only the fault Xid registered an override
        assertThat(gateway.getPrepareVoteOverridesSize()).isEqualTo(1);

        // Prepare Happy Path -> returns XA_OK
        CasualTransactionResourcePrepareReplyMessage prepareHappy = gateway.prepare(
                CasualTransactionResourcePrepareRequestMessage.of(UUID.randomUUID(), xidHappy, 1, Flag.of()));
        assertThat(prepareHappy.getTransactionReturnCode()).isEqualTo(XAReturnCode.XA_OK);

        // Prepare Fault Path -> returns XA_RBROLLBACK
        CasualTransactionResourcePrepareReplyMessage prepareFault = gateway.prepare(
                CasualTransactionResourcePrepareRequestMessage.of(UUID.randomUUID(), xidFault, 1, Flag.of()));
        assertThat(prepareFault.getTransactionReturnCode()).isEqualTo(XAReturnCode.XA_RBROLLBACK);

        // Verify state maps were automatically cleaned up on abort (zero memory leak)
        assertThat(gateway.getPrepareVoteOverridesSize()).isEqualTo(0);
        assertThat(gateway.getCommitVoteOverridesSize()).isEqualTo(0);
    }

    @Test
    @DisplayName("Unit Test: 2PC Phase 2 Heuristic Hazard Commit Vote Override")
    void testCommitHeuristicHazardOverride() {
        Xid xidHeuristic = XID.of(new byte[] {20, 21}, new byte[] {22, 23}, 99);

        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/order/commit-hazard"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("X-Casual-XA-Commit-Vote", "XA_HEURHAZ")
                        .withBody("{\"error\":\"in-doubt commit state\"}")));

        gateway.handleServiceCall(
                buildTransactionalRequest("order/commit-hazard", "{\"item\":\"hazard\"}", xidHeuristic));

        assertThat(gateway.getCommitVoteOverridesSize()).isEqualTo(1);

        // Prepare succeeds
        CasualTransactionResourcePrepareReplyMessage prepareReply = gateway.prepare(
                CasualTransactionResourcePrepareRequestMessage.of(UUID.randomUUID(), xidHeuristic, 1, Flag.of()));
        assertThat(prepareReply.getTransactionReturnCode()).isEqualTo(XAReturnCode.XA_OK);

        // Commit returns XA_HEURHAZ
        CasualTransactionResourceCommitReplyMessage commitReply = gateway.commit(
                CasualTransactionResourceCommitRequestMessage.of(UUID.randomUUID(), xidHeuristic, 1, Flag.of()));
        assertThat(commitReply.getTransactionReturnCode()).isEqualTo(XAReturnCode.XA_HEURHAZ);

        // Cleaned up
        assertThat(gateway.getCommitVoteOverridesSize()).isEqualTo(0);
    }

    private CasualServiceCallRequestMessage buildRequest(String serviceName, String jsonPayload) {
        return buildTransactionalRequest(serviceName, jsonPayload, null);
    }

    private CasualServiceCallRequestMessage buildTransactionalRequest(String serviceName, String jsonPayload, Xid xid) {
        CasualServiceCallRequestMessage.Builder builder = CasualServiceCallRequestMessage.createBuilder()
                .setProtocolVersion(ProtocolVersion.VERSION_1_0)
                .setExecution(UUID.randomUUID())
                .setServiceName(serviceName)
                .setServiceBuffer(ServiceBuffer.of(JsonBuffer.of(jsonPayload)));
        if (xid != null) {
            builder.setXid(xid);
        } else {
            builder.setXid(XID.NULL_XID);
        }
        return builder.build();
    }
}
