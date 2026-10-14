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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.laz.casual.api.buffer.type.JsonBuffer;
import se.laz.casual.api.buffer.type.ServiceBuffer;
import se.laz.casual.api.flags.AtmiFlags;
import se.laz.casual.api.flags.Flag;
import se.laz.casual.api.network.protocol.messages.CasualNWMessage;
import se.laz.casual.api.network.protocol.messages.CasualNWMessageType;
import se.laz.casual.api.xa.XID;
import se.laz.casual.network.ProtocolVersion;
import se.laz.casual.network.protocol.decoding.CasualFrameDecoder;
import se.laz.casual.network.protocol.encoding.CasualMessageEncoder;
import se.laz.casual.network.protocol.messages.CasualNWMessageImpl;
import se.laz.casual.network.protocol.messages.domain.CasualDomainConnectReplyMessage;
import se.laz.casual.network.protocol.messages.domain.CasualDomainConnectRequestMessage;
import se.laz.casual.network.protocol.messages.domain.CasualDomainDiscoveryReplyMessage;
import se.laz.casual.network.protocol.messages.domain.CasualDomainDiscoveryRequestMessage;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallReplyMessage;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallRequestMessage;

/**
 * Lightweight Netty client for communicating with Casual domains over raw TCP.
 * Supports domain connect handshake, discovery, and service calls (tpcall).
 * <p>
 * Intended for upstream contribution to {@code se.laz.casual:casual-client}.
 */
public class CasualTcpClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(CasualTcpClient.class);

    private final String host;
    private final int port;
    private final UUID domainId;
    private final String domainName;
    private final Map<UUID, CompletableFuture<CasualNWMessage<?>>> pendingReplies = new ConcurrentHashMap<>();

    private EventLoopGroup workerGroup;
    private Channel channel;

    public CasualTcpClient(String host, int port) {
        this(host, port, UUID.randomUUID(), "casual-client");
    }

    public CasualTcpClient(String host, int port, UUID domainId, String domainName) {
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.port = port;
        this.domainId = Objects.requireNonNull(domainId, "domainId must not be null");
        this.domainName = Objects.requireNonNull(domainName, "domainName must not be null");
    }

    public synchronized void connect() throws Exception {
        if (isConnected()) {
            return;
        }

        workerGroup = new NioEventLoopGroup(1);
        Bootstrap bootstrap = new Bootstrap();
        bootstrap
                .group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new CasualFrameDecoder());
                        ch.pipeline().addLast(new ClientReplyHandler());
                    }
                });

        ChannelFuture future = bootstrap.connect(host, port).sync();
        this.channel = future.channel();

        // Perform initial domain connect handshake
        CasualDomainConnectRequestMessage connectReq = CasualDomainConnectRequestMessage.createBuilder()
                .withExecution(UUID.randomUUID())
                .withDomainId(domainId)
                .withDomainName(domainName)
                .withProtocols(List.of(ProtocolVersion.VERSION_1_0.getVersion()))
                .build();

        CasualNWMessage<CasualDomainConnectReplyMessage> reply =
                sendAndReceive(CasualNWMessageType.DOMAIN_CONNECT_REQUEST, connectReq, 5, TimeUnit.SECONDS);

        if (reply.getMessage() == null) {
            close();
            throw new IllegalStateException(
                    "Failed to complete Casual domain connect handshake with " + host + ":" + port);
        }
        String safeHost = host.replaceAll("[\r\n]", "");
        LOG.debug("Connected to Casual domain at {}:{}", safeHost, port);
    }

    public synchronized boolean isConnected() {
        return channel != null && channel.isActive();
    }

    public CasualDomainDiscoveryReplyMessage discoverServices(List<String> serviceNames) throws Exception {
        CasualDomainDiscoveryRequestMessage.CasualDomainDiscoveryRequestMessageBuilder builder =
                CasualDomainDiscoveryRequestMessage.createBuilder();
        builder.setExecution(UUID.randomUUID());
        builder.setDomainId(domainId);
        builder.setDomainName(domainName);
        builder.setServiceNames(serviceNames);
        builder.setQueueNames(Collections.emptyList());

        CasualDomainDiscoveryRequestMessage discoveryReq = builder.build();

        CasualNWMessage<CasualDomainDiscoveryReplyMessage> reply =
                sendAndReceive(CasualNWMessageType.DOMAIN_DISCOVERY_REQUEST, discoveryReq, 5, TimeUnit.SECONDS);
        return reply.getMessage();
    }

    public CasualServiceCallReplyMessage tpcall(String serviceName, String jsonPayload) throws Exception {
        ServiceBuffer buffer = ServiceBuffer.of(JsonBuffer.of(jsonPayload));
        CasualServiceCallRequestMessage callReq = CasualServiceCallRequestMessage.createBuilder()
                .setProtocolVersion(ProtocolVersion.VERSION_1_0)
                .setExecution(UUID.randomUUID())
                .setServiceName(serviceName)
                .setTimeout(10000L)
                .setParentName(domainName)
                .setXid(XID.NULL_XID)
                .setXatmiFlags(Flag.of(AtmiFlags.NOFLAG))
                .setServiceBuffer(buffer)
                .build();

        CasualNWMessage<CasualServiceCallReplyMessage> reply =
                sendAndReceive(CasualNWMessageType.SERVICE_CALL_REQUEST, callReq, 10, TimeUnit.SECONDS);
        return reply.getMessage();
    }

    /**
     * Forwards an existing service call request message to the connected upstream domain.
     */
    public CasualNWMessage<CasualServiceCallReplyMessage> forwardCall(
            CasualNWMessage<CasualServiceCallRequestMessage> requestMsg, long timeout, TimeUnit unit) throws Exception {
        UUID correlationId = UUID.randomUUID();
        CasualNWMessage<CasualServiceCallRequestMessage> msgToSend =
                CasualNWMessageImpl.of(correlationId, requestMsg.getMessage());

        CompletableFuture<CasualNWMessage<?>> future = new CompletableFuture<>();
        pendingReplies.put(correlationId, future);

        ByteBuf buf = channel.alloc().buffer();
        CasualMessageEncoder.write(buf, msgToSend);
        channel.writeAndFlush(buf);

        @SuppressWarnings("unchecked")
        CasualNWMessage<CasualServiceCallReplyMessage> reply =
                (CasualNWMessage<CasualServiceCallReplyMessage>) future.get(timeout, unit);
        return reply;
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

        ByteBuf buf = channel.alloc().buffer();
        CasualMessageEncoder.write(buf, msg);
        channel.writeAndFlush(buf);

        CasualNWMessage<?> reply = future.get(timeout, unit);
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
    }

    private class ClientReplyHandler extends SimpleChannelInboundHandler<CasualNWMessage<?>> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, CasualNWMessage<?> msg) {
            UUID correlationId = msg.getCorrelationId();
            CompletableFuture<CasualNWMessage<?>> future = pendingReplies.remove(correlationId);
            if (future != null) {
                future.complete(msg);
            }
        }
    }
}
