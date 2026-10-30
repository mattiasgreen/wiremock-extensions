package com.github.mattiasgreen.wiremock.casual;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import javax.transaction.xa.Xid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * Fast in-memory unit tests for {@link CasualWireMockGateway} and its mediation seam.
 * Operates without sockets, threads, or HTTP ports using {@link InMemoryCasualStoreAdapter}.
 */
class CasualWireMockGatewayUnitTest {

    private InMemoryCasualStoreAdapter store;
    private CasualWireMockGateway gateway;

    @BeforeEach
    void setUp() {
        store = new InMemoryCasualStoreAdapter();
        gateway = CasualWireMockGateway.builder().storePort(store).build();
    }

    @Test
    @DisplayName("Unit Test: HTTP Status Codes & Error Headers cleanly map to Casual ErrorState")
    void testCleanStatusMappingToCasualErrorStates() {
        store.addStub("/__casual/services/test/ok", 200, "{\"status\":\"ok\"}");
        store.addStub("/__casual/services/test/fail", 500, "{\"error\":\"failed\"}");
        store.addStub("/__casual/services/test/timeout", 504, "{\"error\":\"gateway timeout\"}");
        store.addStub("/__casual/services/test/inval", 400, "{\"error\":\"bad request\"}");
        store.addStub(
                "/__casual/services/test/custom-err",
                500,
                "{\"error\":\"custom error\"}",
                Map.of(
                        "X-Casual-Error-State", "TPESVCERR",
                        "X-Casual-User-Supplied-Error", "8888"));

        // 1. HTTP 200 -> OK + TX_ACTIVE
        CasualServiceCallReplyMessage okReply = gateway.handleServiceCall(buildRequest("test/ok", "{}"));
        assertThat(okReply.getError()).isEqualTo(ErrorState.OK);
        assertThat(okReply.getTransactionState()).isEqualTo(TransactionState.TX_ACTIVE);
        assertThat(CasualBufferUtils.extractPayload(okReply.getServiceBuffer())).isEqualTo("{\"status\":\"ok\"}");

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

        store.addStub(
                "/__casual/services/order/charge",
                500,
                "{\"error\":\"db prepare crash\"}",
                Map.of("X-Casual-XA-Prepare-Vote", "XA_RBROLLBACK"));

        // Execute transactional call for faulty branch
        gateway.handleServiceCall(buildTransactionalRequest("order/charge", "{\"item\":\"faulty\"}", xidFault));

        // Update stub for happy branch
        store.addStub("/__casual/services/order/charge", 200, "{\"status\":\"success\"}");
        gateway.handleServiceCall(buildTransactionalRequest("order/charge", "{\"item\":\"happy\"}", xidHappy));

        // Prepare Happy Path -> returns XA_OK
        CasualTransactionResourcePrepareReplyMessage prepareHappy = gateway.prepare(
                CasualTransactionResourcePrepareRequestMessage.of(UUID.randomUUID(), xidHappy, 1, Flag.of()));
        assertThat(prepareHappy.getTransactionReturnCode()).isEqualTo(XAReturnCode.XA_OK);

        // Prepare Fault Path -> returns XA_RBROLLBACK
        CasualTransactionResourcePrepareReplyMessage prepareFault = gateway.prepare(
                CasualTransactionResourcePrepareRequestMessage.of(UUID.randomUUID(), xidFault, 1, Flag.of()));
        assertThat(prepareFault.getTransactionReturnCode()).isEqualTo(XAReturnCode.XA_RBROLLBACK);

        // Subsequent prepare on cleaned-up branch defaults to standard protocol outcome without lingering overrides
        CasualTransactionResourcePrepareReplyMessage subsequentPrepare = gateway.prepare(
                CasualTransactionResourcePrepareRequestMessage.of(UUID.randomUUID(), xidFault, 1, Flag.of()));
        assertThat(subsequentPrepare.getTransactionReturnCode()).isEqualTo(XAReturnCode.XA_OK);
    }

    @Test
    @DisplayName("Unit Test: 2PC Phase 2 Heuristic Hazard Commit Vote Override")
    void testCommitHeuristicHazardOverride() {
        Xid xidHeuristic = XID.of(new byte[] {20, 21}, new byte[] {22, 23}, 99);

        store.addStub(
                "/__casual/services/order/commit-hazard",
                500,
                "{\"error\":\"in-doubt commit state\"}",
                Map.of("X-Casual-XA-Commit-Vote", "XA_HEURHAZ"));

        gateway.handleServiceCall(
                buildTransactionalRequest("order/commit-hazard", "{\"item\":\"hazard\"}", xidHeuristic));

        // Prepare succeeds
        CasualTransactionResourcePrepareReplyMessage prepareReply = gateway.prepare(
                CasualTransactionResourcePrepareRequestMessage.of(UUID.randomUUID(), xidHeuristic, 1, Flag.of()));
        assertThat(prepareReply.getTransactionReturnCode()).isEqualTo(XAReturnCode.XA_OK);

        // Commit returns XA_HEURHAZ
        CasualTransactionResourceCommitReplyMessage commitReply = gateway.commit(
                CasualTransactionResourceCommitRequestMessage.of(UUID.randomUUID(), xidHeuristic, 1, Flag.of()));
        assertThat(commitReply.getTransactionReturnCode()).isEqualTo(XAReturnCode.XA_HEURHAZ);

        // Subsequent commit returns standard XA_OK (state cleaned up, zero leak)
        CasualTransactionResourceCommitReplyMessage subsequentCommit = gateway.commit(
                CasualTransactionResourceCommitRequestMessage.of(UUID.randomUUID(), xidHeuristic, 1, Flag.of()));
        assertThat(subsequentCommit.getTransactionReturnCode()).isEqualTo(XAReturnCode.XA_OK);
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
