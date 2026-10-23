package se.laz.casual.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.transaction.xa.Xid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.laz.casual.api.buffer.CasualBufferUtils;
import se.laz.casual.api.buffer.type.JsonBuffer;
import se.laz.casual.api.buffer.type.ServiceBuffer;
import se.laz.casual.api.flags.AtmiFlags;
import se.laz.casual.api.flags.Flag;
import se.laz.casual.api.flags.XAFlags;
import se.laz.casual.api.network.protocol.messages.CasualNWMessage;
import se.laz.casual.api.network.protocol.messages.CasualNWMessageType;
import se.laz.casual.api.network.protocol.messages.exception.CasualProtocolException;
import se.laz.casual.api.xa.XID;
import se.laz.casual.jca.SpanId;
import se.laz.casual.network.CasualNWMessageDecoder;
import se.laz.casual.network.CasualNWMessageEncoder;
import se.laz.casual.network.ProtocolVersion;
import se.laz.casual.network.inbound.ProtocolVersionValueHolder;
import se.laz.casual.network.protocol.encoding.CasualMessageEncoder;
import se.laz.casual.network.protocol.messages.CasualNWMessageImpl;
import se.laz.casual.network.protocol.messages.domain.CasualDomainConnectReplyMessage;
import se.laz.casual.network.protocol.messages.domain.CasualDomainConnectRequestMessage;
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
 * Lightweight Netty client for communicating with Casual domains over raw TCP.
 * Supports domain connect handshake, discovery, service calls (tpcall), and 2PC/XA transaction messages.
 * <p>
 * Intended for upstream contribution to {@code se.laz.casual:casual-client}.
 */
public class CasualTcpClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(CasualTcpClient.class);

    public static final String TIMEOUT_PROPERTY = "wiremock.casual.upstream.timeout.seconds";
    public static final int DEFAULT_TIMEOUT_SECONDS = 4;

    private final String host;
    private final int port;
    private final UUID domainId;
    private final String domainName;
    private final int defaultTimeoutSeconds;
    private final Map<UUID, CompletableFuture<CasualNWMessage<?>>> pendingReplies = new ConcurrentHashMap<>();

    private ProtocolVersionValueHolder protocolHolder;
    private volatile ProtocolVersion protocolVersion = ProtocolVersion.VERSION_1_0;
    private EventLoopGroup workerGroup;
    private Channel channel;

    public CasualTcpClient(String host, int port) {
        this(host, port, UUID.randomUUID(), "casual-client", resolveDefaultTimeout());
    }

    public CasualTcpClient(String host, int port, UUID domainId, String domainName) {
        this(host, port, domainId, domainName, resolveDefaultTimeout());
    }

    public CasualTcpClient(String host, int port, UUID domainId, String domainName, int timeoutSeconds) {
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.port = port;
        this.domainId = Objects.requireNonNull(domainId, "domainId must not be null");
        this.domainName = Objects.requireNonNull(domainName, "domainName must not be null");
        this.defaultTimeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : resolveDefaultTimeout();
    }

    private static int resolveDefaultTimeout() {
        return Integer.getInteger(TIMEOUT_PROPERTY, DEFAULT_TIMEOUT_SECONDS);
    }

    public int getDefaultTimeoutSeconds() {
        return defaultTimeoutSeconds;
    }

    public synchronized ProtocolVersion getProtocolVersion() {
        return protocolVersion;
    }

    public synchronized void connect() throws Exception {
        if (isConnected()) {
            return;
        }

        String safeHost = host.replaceAll("[\r\n]", "");
        LOG.debug("[CASUAL-CLIENT] Connecting to upstream Casual domain at {}:{}", safeHost, port);

        this.protocolHolder = ProtocolVersionValueHolder.of();
        workerGroup = new NioEventLoopGroup(1);
        Bootstrap bootstrap = new Bootstrap();
        bootstrap
                .group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) TimeUnit.SECONDS.toMillis(defaultTimeoutSeconds))
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(CasualNWMessageDecoder.of(protocolHolder));
                        ch.pipeline().addLast(CasualNWMessageEncoder.of());
                        ch.pipeline().addLast(new ClientReplyHandler());
                    }
                });

        ChannelFuture future = bootstrap.connect(host, port).sync();
        this.channel = future.channel();

        // Perform initial domain connect handshake with full supported protocol version list
        CasualDomainConnectRequestMessage connectReq = CasualDomainConnectRequestMessage.createBuilder()
                .withExecution(UUID.randomUUID())
                .withDomainId(domainId)
                .withDomainName(domainName)
                .withProtocols(ProtocolVersion.supportedVersionNumbers())
                .build();

        CasualNWMessage<CasualDomainConnectReplyMessage> reply = sendAndReceive(
                CasualNWMessageType.DOMAIN_CONNECT_REQUEST, connectReq, defaultTimeoutSeconds, TimeUnit.SECONDS);

        if (reply.getMessage() == null) {
            close();
            throw new IllegalStateException(
                    "Failed to complete Casual domain connect handshake with " + safeHost + ":" + port);
        }

        long actualProtocolVersion = ProtocolVersion.supportedVersionNumbers().stream()
                .filter(version -> version == reply.getMessage().getProtocolVersion())
                .findFirst()
                .orElse(ProtocolVersion.VERSION_1_0.getVersion());
        this.protocolVersion = ProtocolVersion.unmarshall(actualProtocolVersion);
        try {
            protocolHolder.accept(this.protocolVersion);
        } catch (CasualProtocolException e) {
            LOG.debug("Protocol version already accepted: {}", e.getMessage());
        }

        LOG.info(
                "[CASUAL-CLIENT] Handshake complete with domain [{}] at {}:{} (negotiated protocol version: {})",
                reply.getMessage().getDomainName(),
                safeHost,
                port,
                this.protocolVersion);
    }

    public synchronized boolean isConnected() {
        return channel != null && channel.isActive();
    }

    public CasualDomainDiscoveryReplyMessage discoverServices(List<String> serviceNames) throws Exception {
        return discoverServices(serviceNames, defaultTimeoutSeconds, TimeUnit.SECONDS);
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

        LOG.debug(
                "[CASUAL-CLIENT] Forwarding DOMAIN_DISCOVERY_REQUEST for {} service(s) to upstream {}:{}",
                discoveryReq.getServiceNames().size(),
                host,
                port);

        CasualNWMessage<CasualDomainDiscoveryReplyMessage> reply =
                sendAndReceive(CasualNWMessageType.DOMAIN_DISCOVERY_REQUEST, discoveryReq, timeout, unit);
        return reply.getMessage();
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
        ProtocolVersion version = getProtocolVersion();
        CasualServiceCallRequestMessage callReq = CasualServiceCallRequestMessage.createBuilder()
                .setProtocolVersion(version != null ? version : ProtocolVersion.VERSION_1_0)
                .setExecution(UUID.randomUUID())
                .setServiceName(serviceName)
                .setTimeout((long) defaultTimeoutSeconds * 1000)
                .setParentName(domainName)
                .setParentSpan(SpanId.of())
                .setXid(xid != null ? xid : XID.NULL_XID)
                .setXatmiFlags(Flag.of(AtmiFlags.NOFLAG))
                .setServiceBuffer(buffer)
                .build();

        CasualNWMessage<CasualServiceCallReplyMessage> reply = sendAndReceive(
                CasualNWMessageType.SERVICE_CALL_REQUEST, callReq, defaultTimeoutSeconds, TimeUnit.SECONDS);
        return reply.getMessage();
    }

    public CasualTransactionResourcePrepareReplyMessage prepare(Xid xid, int resourceId) throws Exception {
        return prepare(xid, resourceId, Flag.of(XAFlags.TMNOFLAGS));
    }

    public CasualTransactionResourcePrepareReplyMessage prepare(Xid xid, int resourceId, Flag<XAFlags> flags)
            throws Exception {
        CasualTransactionResourcePrepareRequestMessage req =
                CasualTransactionResourcePrepareRequestMessage.of(UUID.randomUUID(), xid, resourceId, flags);
        CasualNWMessage<CasualTransactionResourcePrepareReplyMessage> reply =
                sendAndReceive(CasualNWMessageType.PREPARE_REQUEST, req, defaultTimeoutSeconds, TimeUnit.SECONDS);
        return reply.getMessage();
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
        CasualNWMessage<CasualTransactionResourceCommitReplyMessage> reply =
                sendAndReceive(CasualNWMessageType.COMMIT_REQUEST, req, defaultTimeoutSeconds, TimeUnit.SECONDS);
        return reply.getMessage();
    }

    public CasualTransactionResourceRollbackReplyMessage rollback(Xid xid, int resourceId) throws Exception {
        return rollback(xid, resourceId, Flag.of(XAFlags.TMNOFLAGS));
    }

    public CasualTransactionResourceRollbackReplyMessage rollback(Xid xid, int resourceId, Flag<XAFlags> flags)
            throws Exception {
        CasualTransactionResourceRollbackRequestMessage req =
                CasualTransactionResourceRollbackRequestMessage.of(UUID.randomUUID(), xid, resourceId, flags);
        CasualNWMessage<CasualTransactionResourceRollbackReplyMessage> reply =
                sendAndReceive(CasualNWMessageType.REQUEST_ROLLBACK, req, defaultTimeoutSeconds, TimeUnit.SECONDS);
        return reply.getMessage();
    }

    @SuppressWarnings("unchecked")
    public <
                    T extends se.laz.casual.api.network.protocol.messages.CasualNetworkTransmittable,
                    R extends se.laz.casual.api.network.protocol.messages.CasualNetworkTransmittable>
            CasualNWMessage<R> sendAndReceive(CasualNWMessageType type, T payload, long timeout, TimeUnit unit)
                    throws Exception {
        UUID correlationId = UUID.randomUUID();
        CasualNWMessage<T> msg = CasualNWMessageImpl.of(correlationId, payload);

        CompletableFuture<CasualNWMessage<?>> future = new CompletableFuture<>();
        pendingReplies.put(correlationId, future);

        if (LOG.isDebugEnabled()) {
            LOG.debug(
                    "[CASUAL-CLIENT] Sending frame type={} correlationId={} to {}:{} (timeout={} {})",
                    type,
                    correlationId,
                    host,
                    port,
                    timeout,
                    unit);
        }

        ByteBuf buf;
        synchronized (this) {
            if (channel == null || !channel.isActive()) {
                String safeHost = host.replaceAll("[\r\n]", "");
                throw new IllegalStateException("CasualTcpClient is not connected to " + safeHost + ":" + port);
            }
            buf = channel.alloc().buffer();
            CasualMessageEncoder.write(buf, msg);

            if (LOG.isTraceEnabled()) {
                byte[] bytes = new byte[buf.readableBytes()];
                buf.getBytes(buf.readerIndex(), bytes);
                LOG.trace(
                        "[CASUAL-CLIENT-HEX] Outbound frame ({} bytes):\n{}",
                        bytes.length,
                        CasualBufferUtils.toHexDump(bytes, 128));
            }

            channel.writeAndFlush(buf);
        }

        CasualNWMessage<?> reply;
        try {
            reply = future.get(timeout, unit);
        } catch (TimeoutException e) {
            pendingReplies.remove(correlationId);
            long timeoutSec = unit.toSeconds(timeout);
            String safeHost = host.replaceAll("[\r\n]", "");
            String errorMsg = String.format(
                    "Upstream Casual call [%s] to [%s:%d] timed out after %ds (correlationId=%s). "
                            + "If your network or upstream service is slower, increase the timeout using system property "
                            + "-D%s=<seconds> or builder.upstreamTimeoutSeconds(int).",
                    type, safeHost, port, timeoutSec, correlationId, TIMEOUT_PROPERTY);
            LOG.error("[CASUAL-CLIENT-TIMEOUT] {}", errorMsg);
            throw new TimeoutException(errorMsg);
        }
        return (CasualNWMessage<R>) reply;
    }

    @Override
    public synchronized void close() {
        if (channel != null) {
            channel.close();
            channel = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        pendingReplies.clear();
        LOG.debug("[CASUAL-CLIENT] Closed connection to {}:{}", host, port);
    }

    private class ClientReplyHandler extends SimpleChannelInboundHandler<CasualNWMessage<?>> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, CasualNWMessage<?> msg) {
            UUID correlationId = msg.getCorrelationId();
            if (LOG.isDebugEnabled()) {
                LOG.debug("[CASUAL-CLIENT] Received reply type={} correlationId={}", msg.getType(), correlationId);
            }
            CompletableFuture<CasualNWMessage<?>> future = pendingReplies.remove(correlationId);
            if (future != null) {
                future.complete(msg);
            } else {
                LOG.warn(
                        "[CASUAL-CLIENT] Received unmapped or orphaned reply frame type={} correlationId={}",
                        msg.getType(),
                        correlationId);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            String safeHost = host.replaceAll("[\r\n]", "");
            LOG.error(
                    "[CASUAL-CLIENT-ERROR] Netty channel error communicating with upstream {}:{}: {}",
                    safeHost,
                    port,
                    cause.getMessage(),
                    cause);
            ctx.close();
        }
    }
}
