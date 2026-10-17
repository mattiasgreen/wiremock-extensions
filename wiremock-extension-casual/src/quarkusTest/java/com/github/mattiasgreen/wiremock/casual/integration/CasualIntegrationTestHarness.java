package com.github.mattiasgreen.wiremock.casual.integration;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.mattiasgreen.wiremock.casual.CasualWireMockGateway;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.transaction.xa.Xid;
import se.laz.casual.api.buffer.CasualBufferUtils;
import se.laz.casual.api.buffer.type.JsonBuffer;
import se.laz.casual.api.buffer.type.ServiceBuffer;
import se.laz.casual.api.flags.ErrorState;
import se.laz.casual.api.flags.TransactionState;
import se.laz.casual.api.xa.XAReturnCode;
import se.laz.casual.api.xa.XID;
import se.laz.casual.client.CasualTcpClient;
import se.laz.casual.network.ProtocolVersion;
import se.laz.casual.network.messages.domain.TransactionType;
import se.laz.casual.network.protocol.messages.domain.Service;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallReplyMessage;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallRequestMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceCommitReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceCommitRequestMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourcePrepareReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourcePrepareRequestMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceRollbackReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceRollbackRequestMessage;
import se.laz.casual.server.CasualServer;
import se.laz.casual.server.CasualTransactionHandler;

/**
 * Orchestrates the self-contained 3-node Casual / WireMock Man-in-the-Middle integration test topology:
 * <ul>
 *   <li><b>Node 1 ({@link ClientInitiatorNode})</b>: Simulates the {@code casual-quarkus} client domain and
 *       Narayana 2PC Transaction Coordinator. Initiates global XA transactions ({@link Xid}), performs
 *       domain discovery and transactional {@code tpcall}, and drives Two-Phase Commit ({@code prepare},
 *       {@code commit}, {@code rollback}).</li>
 *   <li><b>Node 2 ({@link CasualWireMockGateway} + {@link WireMockServer})</b>: Intercepts Casual wire
 *       traffic, matches/records stubs against WireMock, forwards unmatched calls + 2PC messages to Node 3,
 *       or acts as a synthetic XA Resource Manager when Node 3 is offline/stubbed.</li>
 *   <li><b>Node 3 ({@link UpstreamDomainNode})</b>: Simulates the upstream {@code casual-quarkus} server
 *       domain hosting {@code casual/example} and a stateful XA Resource Manager ledger tracking prepared,
 *       committed, and rolled-back XIDs.</li>
 * </ul>
 */
public final class CasualIntegrationTestHarness implements AutoCloseable {

    private final UpstreamDomainNode upstreamNode;
    private final WireMockServer wireMockServer;
    private final CasualWireMockGateway mitmGateway;
    private final ClientInitiatorNode clientInitiator;

    public CasualIntegrationTestHarness() throws Exception {
        this.upstreamNode = new UpstreamDomainNode();
        this.upstreamNode.start();

        this.wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        this.wireMockServer.start();

        this.mitmGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockBaseUrl(wireMockServer.baseUrl())
                .upstream("127.0.0.1", upstreamNode.getPort())
                .recordStubs(true)
                .recordFaults(true)
                .recordNotFound(false)
                .build();
        this.mitmGateway.start();

        this.clientInitiator = new ClientInitiatorNode("127.0.0.1", mitmGateway.getPort());
        this.clientInitiator.connect();
    }

    public WireMockServer getWireMockServer() {
        return wireMockServer;
    }

    public CasualWireMockGateway getMitmGateway() {
        return mitmGateway;
    }

    public UpstreamDomainNode getUpstreamNode() {
        return upstreamNode;
    }

    public ClientInitiatorNode getClientInitiator() {
        return clientInitiator;
    }

    /**
     * Stops Node 3 (Upstream Server B) while keeping Node 1 (Client) and Node 2 (WireMock MitM)
     * online to prove full decoupling and offline replay.
     */
    public void stopUpstreamServer() {
        upstreamNode.close();
    }

    @Override
    public void close() {
        if (clientInitiator != null) {
            clientInitiator.close();
        }
        if (mitmGateway != null) {
            mitmGateway.close();
        }
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
        stopUpstreamServer();
    }

    /**
     * Outcome of a full 2PC transactional invocation initiated by {@link ClientInitiatorNode}.
     */
    public record TransactionOutcome(
            Xid xid,
            ErrorState serviceErrorState,
            long userSuppliedError,
            TransactionState serviceTransactionState,
            String replyPayload,
            XAReturnCode prepareReturnCode,
            XAReturnCode commitReturnCode,
            XAReturnCode rollbackReturnCode,
            boolean committed) {}

    /**
     * Node 1: Simulates the {@code casual-quarkus} Client Initiator &amp; Narayana 2PC Transaction Coordinator.
     */
    public static final class ClientInitiatorNode implements AutoCloseable {

        private static final int RESOURCE_ID = 101;
        private final CasualTcpClient tcpClient;
        private final AtomicInteger txSequence = new AtomicInteger(1);

        public ClientInitiatorNode(String host, int port) {
            this.tcpClient = new CasualTcpClient(host, port, UUID.randomUUID(), "casual-quarkus-client-domain");
        }

        public void connect() throws Exception {
            tcpClient.connect();
        }

        /**
         * Executes a service call inside a global 2PC XA transaction:
         * <ol>
         *   <li>Allocates a unique global {@link Xid}.</li>
         *   <li>Invokes {@code SERVICE_CALL_REQUEST} carrying the {@link Xid}.</li>
         *   <li>If {@code ErrorState == OK} and {@code TransactionState == TX_ACTIVE}, executes
         *       2PC Phase 1 ({@code PREPARE_REQUEST}) followed by Phase 2 ({@code COMMIT_REQUEST} if
         *       {@code XA_OK}, or aborts if prepare fails).</li>
         *   <li>If {@code ErrorState != OK} or {@code TransactionState != TX_ACTIVE}, drives
         *       {@code REQUEST_ROLLBACK}.</li>
         * </ol>
         */
        public TransactionOutcome executeInTransaction(String serviceName, String jsonPayload) throws Exception {
            int seq = txSequence.getAndIncrement();
            Xid xid = XID.of(
                    ("gtrid-quarkus-" + seq).getBytes(StandardCharsets.UTF_8),
                    ("bqual-quarkus-" + seq).getBytes(StandardCharsets.UTF_8),
                    42);

            CasualServiceCallReplyMessage callReply = tcpClient.tpcall(serviceName, jsonPayload, xid);
            String replyPayload = CasualBufferUtils.extractPayload(callReply.getServiceBuffer());

            // Check if service call succeeded and kept transaction active
            if (callReply.getError() == ErrorState.OK
                    && callReply.getTransactionState() == TransactionState.TX_ACTIVE) {
                CasualTransactionResourcePrepareReplyMessage prepareReply = tcpClient.prepare(xid, RESOURCE_ID);
                XAReturnCode prepareVote = prepareReply.getTransactionReturnCode();

                if (prepareVote == XAReturnCode.XA_OK) {
                    CasualTransactionResourceCommitReplyMessage commitReply = tcpClient.commit(xid, RESOURCE_ID, false);
                    XAReturnCode commitVote = commitReply.getTransactionReturnCode();
                    return new TransactionOutcome(
                            xid,
                            callReply.getError(),
                            callReply.getUserDefinedCode(),
                            callReply.getTransactionState(),
                            replyPayload,
                            prepareVote,
                            commitVote,
                            null,
                            commitVote == XAReturnCode.XA_OK);
                } else if (prepareVote == XAReturnCode.XA_RDONLY) {
                    return new TransactionOutcome(
                            xid,
                            callReply.getError(),
                            callReply.getUserDefinedCode(),
                            callReply.getTransactionState(),
                            replyPayload,
                            prepareVote,
                            XAReturnCode.XA_OK,
                            null,
                            true);
                } else {
                    // Prepare failed (e.g. XAER_RMERR or XA_RBROLLBACK) -> Transaction aborted in Phase 1
                    return new TransactionOutcome(
                            xid,
                            callReply.getError(),
                            callReply.getUserDefinedCode(),
                            callReply.getTransactionState(),
                            replyPayload,
                            prepareVote,
                            null,
                            null,
                            false);
                }
            } else {
                // Service call failed (e.g. TPESVCFAIL) or marked transaction ROLLBACK_ONLY -> Rollback XA branch
                CasualTransactionResourceRollbackReplyMessage rollbackReply = tcpClient.rollback(xid, RESOURCE_ID);
                return new TransactionOutcome(
                        xid,
                        callReply.getError(),
                        callReply.getUserDefinedCode(),
                        callReply.getTransactionState(),
                        replyPayload,
                        null,
                        null,
                        rollbackReply.getTransactionReturnCode(),
                        false);
            }
        }

        @Override
        public void close() {
            tcpClient.close();
        }
    }

    /**
     * Node 3: Real Upstream Casual Server B hosting {@code casual/example} and a stateful XA Resource Manager.
     */
    public static final class UpstreamDomainNode implements CasualTransactionHandler, AutoCloseable {

        private final CasualServer server;
        private final AtomicInteger serviceCallCount = new AtomicInteger();
        private final Set<Xid> activeBranchXids = ConcurrentHashMap.newKeySet();
        private final Set<Xid> preparedXids = ConcurrentHashMap.newKeySet();
        private final Set<Xid> committedXids = ConcurrentHashMap.newKeySet();
        private final Set<Xid> rolledBackXids = ConcurrentHashMap.newKeySet();

        public UpstreamDomainNode() {
            this.server = CasualServer.builder()
                    .port(0)
                    .domainId(UUID.randomUUID())
                    .domainName("casual-quarkus-upstream-server")
                    .discoveryHandler(this::handleDiscovery)
                    .serviceHandler(this::handleServiceCall)
                    .transactionHandler(this)
                    .build();
        }

        public void start() throws InterruptedException {
            server.start();
        }

        public int getPort() {
            return server.getPort();
        }

        public int getServiceCallCount() {
            return serviceCallCount.get();
        }

        public Set<Xid> getPreparedXids() {
            return Collections.unmodifiableSet(preparedXids);
        }

        public Set<Xid> getCommittedXids() {
            return Collections.unmodifiableSet(committedXids);
        }

        public Set<Xid> getRolledBackXids() {
            return Collections.unmodifiableSet(rolledBackXids);
        }

        private List<Service> handleDiscovery(List<String> serviceNames) {
            return serviceNames.stream()
                    .map(name -> Service.of(name, "quarkus-upstream", TransactionType.AUTOMATIC))
                    .toList();
        }

        private CasualServiceCallReplyMessage handleServiceCall(CasualServiceCallRequestMessage req) {
            serviceCallCount.incrementAndGet();
            if (req.getXid() != null && !XID.NULL_XID.equals(req.getXid())) {
                activeBranchXids.add(req.getXid());
            }

            String reqJson = CasualBufferUtils.extractPayload(req.getServiceBuffer());
            String replyJson = "{\"service\":\""
                    + req.getServiceName()
                    + "\",\"processedBy\":\"upstream-server-B\",\"echo\":"
                    + (reqJson == null || reqJson.isBlank() ? "{}" : reqJson)
                    + "}";

            return CasualServiceCallReplyMessage.createBuilder()
                    .setProtocolVersion(ProtocolVersion.VERSION_1_0)
                    .setExecution(req.getExecution())
                    .setXid(req.getXid())
                    .setError(ErrorState.OK)
                    .setTransactionState(TransactionState.TX_ACTIVE)
                    .setServiceBuffer(ServiceBuffer.of(JsonBuffer.of(replyJson)))
                    .build();
        }

        @Override
        public CasualTransactionResourcePrepareReplyMessage prepare(
                CasualTransactionResourcePrepareRequestMessage req) {
            preparedXids.add(req.getXid());
            return CasualTransactionResourcePrepareReplyMessage.of(
                    req.getExecution(), req.getXid(), req.getResourceId(), XAReturnCode.XA_OK);
        }

        @Override
        public CasualTransactionResourceCommitReplyMessage commit(CasualTransactionResourceCommitRequestMessage req) {
            committedXids.add(req.getXid());
            activeBranchXids.remove(req.getXid());
            return CasualTransactionResourceCommitReplyMessage.of(
                    req.getExecution(), req.getXid(), req.getResourceId(), XAReturnCode.XA_OK);
        }

        @Override
        public CasualTransactionResourceRollbackReplyMessage rollback(
                CasualTransactionResourceRollbackRequestMessage req) {
            rolledBackXids.add(req.getXid());
            activeBranchXids.remove(req.getXid());
            return CasualTransactionResourceRollbackReplyMessage.of(
                    req.getExecution(), req.getXid(), req.getResourceId(), XAReturnCode.XA_OK);
        }

        @Override
        public void close() {
            server.close();
        }
    }
}
