package com.github.mattiasgreen.wiremock.casual;

import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.transaction.xa.Xid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.laz.casual.api.buffer.type.JsonBuffer;
import se.laz.casual.api.buffer.type.ServiceBuffer;
import se.laz.casual.api.flags.AtmiFlags;
import se.laz.casual.api.flags.Flag;
import se.laz.casual.api.flags.XAFlags;
import se.laz.casual.api.network.protocol.messages.CasualNWMessage;
import se.laz.casual.api.network.protocol.messages.exception.CasualProtocolException;
import se.laz.casual.api.xa.XID;
import se.laz.casual.config.ConfigurationOptions;
import se.laz.casual.config.ConfigurationService;
import se.laz.casual.internal.network.NetworkConnection;
import se.laz.casual.jca.SpanId;
import se.laz.casual.network.ProtocolVersion;
import se.laz.casual.network.outbound.NettyConnectionInformation;
import se.laz.casual.network.outbound.NettyNetworkConnection;
import se.laz.casual.network.protocol.messages.CasualNWMessageImpl;
import se.laz.casual.network.protocol.messages.domain.CasualDomainDiscoveryReplyMessage;
import se.laz.casual.network.protocol.messages.domain.CasualDomainDiscoveryRequestMessage;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallReplyMessage;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallRequestMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceCommitReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceCommitRequestMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourcePrepareReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourcePrepareRequestMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceRollbackReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceRollbackRequestMessage;

/**
 * High-level Casual client backed by {@link NettyNetworkConnection}.
 * Supports domain connect handshake, discovery, service calls (tpcall), and 2PC/XA transaction messages.
 *
 * <p>Terminal CLI and console execution concerns are encapsulated in {@link CasualCli}.
 */
public class CasualClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(CasualClient.class);

    static {
        ConfigurationService.setConfiguration(ConfigurationOptions.CASUAL_OUTBOUND_UNMANAGED, true);
        ConfigurationService.setConfiguration(ConfigurationOptions.CASUAL_UNMANAGED, true);
    }

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 7772;
    public static final int DEFAULT_TIMEOUT_SECONDS = 4;
    public static final String TIMEOUT_PROPERTY = "wiremock.casual.upstream.timeout.seconds";

    private final String host;
    private final int port;
    private final UUID domainId;
    private final String domainName;
    private final int timeoutSeconds;

    private volatile NetworkConnection networkConnection;

    public synchronized NetworkConnection getActiveConnection() {
        connect();
        return networkConnection;
    }

    public CasualClient(String host, int port) {
        this(host, port, resolveDefaultTimeout());
    }

    public CasualClient(String host, int port, int timeoutSeconds) {
        this(host, port, UUID.randomUUID(), "casual-client", timeoutSeconds);
    }

    public CasualClient(String host, int port, UUID domainId, String domainName) {
        this(host, port, domainId, domainName, resolveDefaultTimeout());
    }

    public CasualClient(String host, int port, UUID domainId, String domainName, int timeoutSeconds) {
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.port = port;
        this.domainId = Objects.requireNonNull(domainId, "domainId must not be null");
        this.domainName = Objects.requireNonNull(domainName, "domainName must not be null");
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : resolveDefaultTimeout();
    }

    public static CasualClient create(String host, int port) {
        return new CasualClient(host, port);
    }

    public static CasualClient create(String host, int port, int timeoutSeconds) {
        return new CasualClient(host, port, timeoutSeconds);
    }

    public static int resolveDefaultTimeout() {
        return Integer.getInteger(TIMEOUT_PROPERTY, DEFAULT_TIMEOUT_SECONDS);
    }

    public synchronized CasualClient connect() {
        if (isConnected()) {
            return this;
        }
        String safeHost = host.replaceAll("[\r\n]", "");
        LOG.debug("[CASUAL-CLIENT] Connecting to Casual domain at {}:{}", safeHost, port);

        NettyConnectionInformation ci = NettyConnectionInformation.createBuilder()
                .withAddress(new InetSocketAddress(host, port))
                .withDomainId(domainId)
                .withDomainName(domainName)
                .build();

        this.networkConnection = NettyNetworkConnection.of(
                ci,
                reason ->
                        LOG.warn("[CASUAL-CLIENT] Connection lost to {}:{}: {}", safeHost, port, reason.getMessage()));
        LOG.info(
                "[CASUAL-CLIENT] Connected to Casual domain at {}:{} (negotiated protocol version: {})",
                safeHost,
                port,
                networkConnection.getProtocolVersion());
        return this;
    }

    public synchronized boolean isConnected() {
        return networkConnection != null && networkConnection.isActive();
    }

    public synchronized ProtocolVersion getProtocolVersion() {
        return networkConnection != null ? networkConnection.getProtocolVersion() : ProtocolVersion.VERSION_1_0;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public CasualCallResult call(String serviceName, String jsonPayload) throws Exception {
        return call(serviceName, "JSON", jsonPayload);
    }

    public CasualCallResult call(String serviceName, String bufferType, String payload) throws Exception {
        Objects.requireNonNull(serviceName, "serviceName must not be null");
        connect();

        ServiceBuffer buffer = CasualBufferUtils.wrapPayload(bufferType, payload);
        long startNanos = System.nanoTime();
        CasualServiceCallReplyMessage reply = tpcall(serviceName, buffer);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        String replyType = reply.getServiceBuffer() != null
                ? reply.getServiceBuffer().getType()
                : (bufferType != null ? bufferType : "JSON");
        String replyPayload = CasualBufferUtils.extractPayload(reply.getServiceBuffer());

        return new CasualCallResult(
                serviceName,
                reply.getError(),
                reply.getUserDefinedCode(),
                reply.getExecution(),
                replyType,
                replyPayload,
                durationMs);
    }

    public CasualCallResult call(String serviceName, byte[] binaryPayload) throws Exception {
        return call(serviceName, "X_OCTET", binaryPayload);
    }

    public CasualCallResult call(String serviceName, String bufferType, byte[] binaryPayload) throws Exception {
        Objects.requireNonNull(serviceName, "serviceName must not be null");
        connect();

        ServiceBuffer buffer = CasualBufferUtils.wrapPayload(bufferType, binaryPayload);
        long startNanos = System.nanoTime();
        CasualServiceCallReplyMessage reply = tpcall(serviceName, buffer);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        String replyType = reply.getServiceBuffer() != null
                ? reply.getServiceBuffer().getType()
                : (bufferType != null ? bufferType : "X_OCTET");
        String replyPayload = CasualBufferUtils.extractPayload(reply.getServiceBuffer());

        return new CasualCallResult(
                serviceName,
                reply.getError(),
                reply.getUserDefinedCode(),
                reply.getExecution(),
                replyType,
                replyPayload,
                durationMs);
    }

    public CasualServiceCallReplyMessage tpcall(String serviceName, String jsonPayload) throws Exception {
        return tpcall(serviceName, jsonPayload, XID.NULL_XID);
    }

    public CasualServiceCallReplyMessage tpcall(String serviceName, String jsonPayload, Xid xid) throws Exception {
        ServiceBuffer buffer = ServiceBuffer.of(JsonBuffer.of(jsonPayload));
        return tpcall(serviceName, buffer, xid);
    }

    public CasualServiceCallReplyMessage tpcall(String serviceName, ServiceBuffer buffer) throws Exception {
        return tpcall(serviceName, buffer, XID.NULL_XID);
    }

    public CasualServiceCallReplyMessage tpcall(String serviceName, ServiceBuffer buffer, Xid xid) throws Exception {
        connect();
        ProtocolVersion version = getProtocolVersion();
        CasualServiceCallRequestMessage.Builder b = CasualServiceCallRequestMessage.createBuilder()
                .setProtocolVersion(version != null ? version : ProtocolVersion.VERSION_1_0)
                .setExecution(UUID.randomUUID())
                .setServiceName(serviceName)
                .setTimeout((long) timeoutSeconds * 1000)
                .setParentName(domainName)
                .setXid(xid != null ? xid : XID.NULL_XID)
                .setXatmiFlags(Flag.of(AtmiFlags.NOFLAG))
                .setServiceBuffer(buffer);

        if (version != null && version.isGreaterThanOrEqualTo(ProtocolVersion.VERSION_1_3)) {
            b.setParentSpan(SpanId.of());
        }

        CasualServiceCallRequestMessage callReq = b.build();
        return forwardServiceCall(callReq);
    }

    /**
     * Forwards an existing {@link CasualServiceCallRequestMessage} over TCP, automatically
     * adapting protocol version and SpanId fields to the upstream domain's negotiated version.
     */
    public CasualServiceCallReplyMessage forwardServiceCall(CasualServiceCallRequestMessage req) throws Exception {
        connect();
        ProtocolVersion version = getProtocolVersion();
        CasualServiceCallRequestMessage adapted = req;
        if (version != null) {
            CasualServiceCallRequestMessage.Builder b = CasualServiceCallRequestMessage.createBuilder()
                    .setProtocolVersion(version)
                    .setExecution(req.getExecution())
                    .setServiceName(req.getServiceName())
                    .setTimeout(req.getTimeout())
                    .setParentName(req.getParentName())
                    .setXid(req.getXid())
                    .setXatmiFlags(req.getXatmiFlags())
                    .setServiceBuffer(req.getServiceBuffer());
            try {
                SpanId span = req.getParentSpan();
                if (span != null) {
                    b.setParentSpan(span);
                } else if (version.isGreaterThanOrEqualTo(ProtocolVersion.VERSION_1_3)) {
                    b.setParentSpan(SpanId.of());
                }
            } catch (CasualProtocolException expected) {
                LOG.trace("SpanId not supported for protocol version", expected);
                if (version.isGreaterThanOrEqualTo(ProtocolVersion.VERSION_1_3)) {
                    b.setParentSpan(SpanId.of());
                }
            }
            adapted = b.build();
        }

        UUID correlationId = UUID.randomUUID();
        CasualNWMessage<CasualServiceCallRequestMessage> nwMsg = CasualNWMessageImpl.of(correlationId, adapted);
        CompletableFuture<CasualNWMessage<CasualServiceCallReplyMessage>> future =
                getActiveConnection().request(nwMsg);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS).getMessage();
        } catch (TimeoutException e) {
            String safeHost = host.replaceAll("[\r\n]", "");
            throw new TimeoutException(String.format(
                    "Upstream Casual call [%s] to [%s:%d] timed out after %ds",
                    req.getServiceName(), safeHost, port, timeoutSeconds));
        }
    }

    public CasualDomainDiscoveryReplyMessage discover(String... serviceNames) throws Exception {
        return discover(serviceNames != null ? Arrays.asList(serviceNames) : Collections.emptyList());
    }

    public CasualDomainDiscoveryReplyMessage discover(List<String> serviceNames) throws Exception {
        return discoverServices(serviceNames, timeoutSeconds, TimeUnit.SECONDS);
    }

    public CasualDomainDiscoveryReplyMessage discoverServices(List<String> serviceNames) throws Exception {
        return discoverServices(serviceNames, timeoutSeconds, TimeUnit.SECONDS);
    }

    public CasualDomainDiscoveryReplyMessage discoverServices(List<String> serviceNames, long timeout, TimeUnit unit)
            throws Exception {
        CasualDomainDiscoveryRequestMessage.CasualDomainDiscoveryRequestMessageBuilder builder =
                CasualDomainDiscoveryRequestMessage.createBuilder();
        builder.setExecution(UUID.randomUUID());
        builder.setDomainId(domainId);
        builder.setDomainName(domainName);
        builder.setServiceNames(serviceNames != null ? serviceNames : Collections.emptyList());
        builder.setQueueNames(Collections.emptyList());

        CasualDomainDiscoveryRequestMessage discoveryReq = builder.build();
        UUID correlationId = UUID.randomUUID();
        CasualNWMessage<CasualDomainDiscoveryRequestMessage> nwMsg =
                CasualNWMessageImpl.of(correlationId, discoveryReq);
        CompletableFuture<CasualNWMessage<CasualDomainDiscoveryReplyMessage>> future =
                getActiveConnection().request(nwMsg);
        try {
            return future.get(timeout, unit).getMessage();
        } catch (TimeoutException e) {
            String safeHost = host.replaceAll("[\r\n]", "");
            throw new TimeoutException(String.format(
                    "Casual domain discovery to [%s:%d] timed out after %s %s", safeHost, port, timeout, unit));
        }
    }

    public CasualTransactionResourcePrepareReplyMessage prepare(Xid xid, int resourceId) throws Exception {
        return prepare(xid, resourceId, Flag.of(XAFlags.TMNOFLAGS));
    }

    public CasualTransactionResourcePrepareReplyMessage prepare(Xid xid, int resourceId, Flag<XAFlags> flags)
            throws Exception {
        CasualTransactionResourcePrepareRequestMessage req =
                CasualTransactionResourcePrepareRequestMessage.of(UUID.randomUUID(), xid, resourceId, flags);
        UUID correlationId = UUID.randomUUID();
        CasualNWMessage<CasualTransactionResourcePrepareRequestMessage> nwMsg =
                CasualNWMessageImpl.of(correlationId, req);
        CompletableFuture<CasualNWMessage<CasualTransactionResourcePrepareReplyMessage>> future =
                getActiveConnection().request(nwMsg);
        return future.get(timeoutSeconds, TimeUnit.SECONDS).getMessage();
    }

    public CasualTransactionResourceCommitReplyMessage commit(Xid xid, int resourceId, boolean onePhase)
            throws Exception {
        Flag<XAFlags> flags = Flag.of(onePhase ? XAFlags.TMONEPHASE : XAFlags.TMNOFLAGS);
        return commit(xid, resourceId, flags);
    }

    public CasualTransactionResourceCommitReplyMessage commit(Xid xid, int resourceId, Flag<XAFlags> flags)
            throws Exception {
        CasualTransactionResourceCommitRequestMessage req =
                CasualTransactionResourceCommitRequestMessage.of(UUID.randomUUID(), xid, resourceId, flags);
        UUID correlationId = UUID.randomUUID();
        CasualNWMessage<CasualTransactionResourceCommitRequestMessage> nwMsg =
                CasualNWMessageImpl.of(correlationId, req);
        CompletableFuture<CasualNWMessage<CasualTransactionResourceCommitReplyMessage>> future =
                getActiveConnection().request(nwMsg);
        return future.get(timeoutSeconds, TimeUnit.SECONDS).getMessage();
    }

    public CasualTransactionResourceRollbackReplyMessage rollback(Xid xid, int resourceId) throws Exception {
        return rollback(xid, resourceId, Flag.of(XAFlags.TMNOFLAGS));
    }

    public CasualTransactionResourceRollbackReplyMessage rollback(Xid xid, int resourceId, Flag<XAFlags> flags)
            throws Exception {
        CasualTransactionResourceRollbackRequestMessage req =
                CasualTransactionResourceRollbackRequestMessage.of(UUID.randomUUID(), xid, resourceId, flags);
        UUID correlationId = UUID.randomUUID();
        CasualNWMessage<CasualTransactionResourceRollbackRequestMessage> nwMsg =
                CasualNWMessageImpl.of(correlationId, req);
        CompletableFuture<CasualNWMessage<CasualTransactionResourceRollbackReplyMessage>> future =
                getActiveConnection().request(nwMsg);
        return future.get(timeoutSeconds, TimeUnit.SECONDS).getMessage();
    }

    public boolean ping() {
        try {
            connect();
            return isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public synchronized void close() {
        if (networkConnection != null) {
            try {
                networkConnection.close();
            } catch (Exception e) {
                LOG.trace("Exception while closing network connection", e);
            }
            networkConnection = null;
        }
    }

    // =========================================================================
    // CLI Entry Point & Runner (delegates to CasualCli)
    // =========================================================================

    public static void main(String[] args) {
        CasualCli.main(args);
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        return CasualCli.run(args, System.in, out, err);
    }

    public static int run(String[] args, PrintStream out, PrintStream err, InputStream in) {
        return CasualCli.run(args, in, out, err);
    }
}
