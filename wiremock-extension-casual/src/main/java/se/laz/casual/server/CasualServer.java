package se.laz.casual.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.laz.casual.api.network.protocol.messages.CasualNWMessage;
import se.laz.casual.api.network.protocol.messages.CasualNWMessageType;
import se.laz.casual.api.xa.XAReturnCode;
import se.laz.casual.network.ProtocolVersion;
import se.laz.casual.network.protocol.decoding.CasualFrameDecoder;
import se.laz.casual.network.protocol.encoding.CasualMessageEncoder;
import se.laz.casual.network.protocol.messages.CasualNWMessageImpl;
import se.laz.casual.network.protocol.messages.domain.CasualDomainConnectReplyMessage;
import se.laz.casual.network.protocol.messages.domain.CasualDomainConnectRequestMessage;
import se.laz.casual.network.protocol.messages.domain.CasualDomainDiscoveryReplyMessage;
import se.laz.casual.network.protocol.messages.domain.CasualDomainDiscoveryRequestMessage;
import se.laz.casual.network.protocol.messages.domain.Service;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallReplyMessage;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallRequestMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceCommitReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceCommitRequestMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourcePrepareReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourcePrepareRequestMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceRollbackReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceRollbackRequestMessage;

/**
 * Super-lightweight in-process Casual Domain Gateway server.
 * Listens on a TCP port using Netty and delegates service invocations to a {@link CasualServiceHandler}.
 * <p>
 * Intended for upstream contribution to {@code se.laz.casual:casual-server}.
 */
public class CasualServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(CasualServer.class);

    private final int port;
    private final UUID domainId;
    private final String domainName;
    private final CasualServiceHandler serviceHandler;
    private final CasualDiscoveryHandler discoveryHandler;
    private final CasualTransactionHandler transactionHandler;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ChannelFuture channelFuture;
    private volatile int boundPort;

    public CasualServer(
            int port,
            UUID domainId,
            String domainName,
            CasualServiceHandler serviceHandler,
            CasualDiscoveryHandler discoveryHandler) {
        this(port, domainId, domainName, serviceHandler, discoveryHandler, null);
    }

    public CasualServer(
            int port,
            UUID domainId,
            String domainName,
            CasualServiceHandler serviceHandler,
            CasualDiscoveryHandler discoveryHandler,
            CasualTransactionHandler transactionHandler) {
        this.port = port;
        this.domainId = Objects.requireNonNull(domainId, "domainId must not be null");
        this.domainName = Objects.requireNonNull(domainName, "domainName must not be null");
        this.serviceHandler = Objects.requireNonNull(serviceHandler, "serviceHandler must not be null");
        this.discoveryHandler = discoveryHandler;
        this.transactionHandler = transactionHandler;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int port = 0;
        private UUID domainId = UUID.randomUUID();
        private String domainName = "casual-server";
        private CasualServiceHandler serviceHandler;
        private CasualDiscoveryHandler discoveryHandler;
        private CasualTransactionHandler transactionHandler;

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder domainId(UUID domainId) {
            this.domainId = domainId;
            return this;
        }

        public Builder domainName(String domainName) {
            this.domainName = domainName;
            return this;
        }

        public Builder serviceHandler(CasualServiceHandler serviceHandler) {
            this.serviceHandler = serviceHandler;
            return this;
        }

        public Builder discoveryHandler(CasualDiscoveryHandler discoveryHandler) {
            this.discoveryHandler = discoveryHandler;
            return this;
        }

        public Builder transactionHandler(CasualTransactionHandler transactionHandler) {
            this.transactionHandler = transactionHandler;
            return this;
        }

        public CasualServer build() {
            return new CasualServer(port, domainId, domainName, serviceHandler, discoveryHandler, transactionHandler);
        }
    }

    public synchronized void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new CasualFrameDecoder());
                        ch.pipeline().addLast(new ServerInboundHandler());
                    }
                });

        channelFuture = bootstrap.bind(port).sync();
        InetSocketAddress socketAddress =
                (InetSocketAddress) channelFuture.channel().localAddress();
        this.boundPort = socketAddress.getPort();
        LOG.info("CasualServer listening on TCP port {}", boundPort);
    }

    public synchronized int getPort() {
        return boundPort > 0 ? boundPort : port;
    }

    @Override
    public synchronized void close() {
        if (channelFuture != null) {
            channelFuture.channel().close();
            channelFuture = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        LOG.info("CasualServer stopped");
    }

    private class ServerInboundHandler extends SimpleChannelInboundHandler<CasualNWMessage<?>> {

        @Override
        @SuppressWarnings("unchecked")
        protected void channelRead0(ChannelHandlerContext ctx, CasualNWMessage<?> msg) {
            CasualNWMessageType type = msg.getType();

            if (type == CasualNWMessageType.DOMAIN_CONNECT_REQUEST) {
                handleDomainConnect(ctx, (CasualNWMessage<CasualDomainConnectRequestMessage>) msg);
            } else if (type == CasualNWMessageType.DOMAIN_DISCOVERY_REQUEST) {
                handleDomainDiscovery(ctx, (CasualNWMessage<CasualDomainDiscoveryRequestMessage>) msg);
            } else if (type == CasualNWMessageType.SERVICE_CALL_REQUEST) {
                handleServiceCall(ctx, (CasualNWMessage<CasualServiceCallRequestMessage>) msg);
            } else if (type == CasualNWMessageType.PREPARE_REQUEST) {
                handlePrepare(ctx, (CasualNWMessage<CasualTransactionResourcePrepareRequestMessage>) msg);
            } else if (type == CasualNWMessageType.COMMIT_REQUEST) {
                handleCommit(ctx, (CasualNWMessage<CasualTransactionResourceCommitRequestMessage>) msg);
            } else if (type == CasualNWMessageType.REQUEST_ROLLBACK) {
                handleRollback(ctx, (CasualNWMessage<CasualTransactionResourceRollbackRequestMessage>) msg);
            } else {
                String safeType = String.valueOf(type).replaceAll("[\r\n]", "");
                LOG.warn("Unsupported Casual message type received: {}", safeType);
            }
        }

        private void handleDomainConnect(
                ChannelHandlerContext ctx, CasualNWMessage<CasualDomainConnectRequestMessage> msg) {
            CasualDomainConnectRequestMessage req = msg.getMessage();
            CasualDomainConnectReplyMessage reply = CasualDomainConnectReplyMessage.createBuilder()
                    .withExecution(req.getExecution())
                    .withDomainId(domainId)
                    .withDomainName(domainName)
                    .withProtocolVersion(ProtocolVersion.VERSION_1_0.getVersion())
                    .build();

            CasualNWMessage<CasualDomainConnectReplyMessage> replyMsg =
                    CasualNWMessageImpl.of(msg.getCorrelationId(), reply);
            writeMessage(ctx, replyMsg);
        }

        private void handleDomainDiscovery(
                ChannelHandlerContext ctx, CasualNWMessage<CasualDomainDiscoveryRequestMessage> msg) {
            CasualDomainDiscoveryRequestMessage req = msg.getMessage();
            CasualDomainDiscoveryReplyMessage reply = CasualDomainDiscoveryReplyMessage.of(
                    req.getExecution(), domainId, domainName, ProtocolVersion.VERSION_1_0);

            List<Service> services = discoveryHandler != null
                    ? discoveryHandler.discover(req.getServiceNames())
                    : Collections.emptyList();
            reply.setServices(services);
            reply.setQueues(Collections.emptyList());

            CasualNWMessage<CasualDomainDiscoveryReplyMessage> replyMsg =
                    CasualNWMessageImpl.of(msg.getCorrelationId(), reply);
            writeMessage(ctx, replyMsg);
        }

        private void handleServiceCall(
                ChannelHandlerContext ctx, CasualNWMessage<CasualServiceCallRequestMessage> msg) {
            CasualServiceCallReplyMessage reply = serviceHandler.handle(msg.getMessage());
            CasualNWMessage<CasualServiceCallReplyMessage> replyMsg =
                    CasualNWMessageImpl.of(msg.getCorrelationId(), reply);
            writeMessage(ctx, replyMsg);
        }

        private void handlePrepare(
                ChannelHandlerContext ctx, CasualNWMessage<CasualTransactionResourcePrepareRequestMessage> msg) {
            CasualTransactionResourcePrepareRequestMessage req = msg.getMessage();
            CasualTransactionResourcePrepareReplyMessage reply = transactionHandler != null
                    ? transactionHandler.prepare(req)
                    : CasualTransactionResourcePrepareReplyMessage.of(
                            req.getExecution(), req.getXid(), req.getResourceId(), XAReturnCode.XA_OK);
            writeMessage(ctx, CasualNWMessageImpl.of(msg.getCorrelationId(), reply));
        }

        private void handleCommit(
                ChannelHandlerContext ctx, CasualNWMessage<CasualTransactionResourceCommitRequestMessage> msg) {
            CasualTransactionResourceCommitRequestMessage req = msg.getMessage();
            CasualTransactionResourceCommitReplyMessage reply = transactionHandler != null
                    ? transactionHandler.commit(req)
                    : CasualTransactionResourceCommitReplyMessage.of(
                            req.getExecution(), req.getXid(), req.getResourceId(), XAReturnCode.XA_OK);
            writeMessage(ctx, CasualNWMessageImpl.of(msg.getCorrelationId(), reply));
        }

        private void handleRollback(
                ChannelHandlerContext ctx, CasualNWMessage<CasualTransactionResourceRollbackRequestMessage> msg) {
            CasualTransactionResourceRollbackRequestMessage req = msg.getMessage();
            CasualTransactionResourceRollbackReplyMessage reply = transactionHandler != null
                    ? transactionHandler.rollback(req)
                    : CasualTransactionResourceRollbackReplyMessage.of(
                            req.getExecution(), req.getXid(), req.getResourceId(), XAReturnCode.XA_OK);
            writeMessage(ctx, CasualNWMessageImpl.of(msg.getCorrelationId(), reply));
        }

        private <T extends se.laz.casual.api.network.protocol.messages.CasualNetworkTransmittable> void writeMessage(
                ChannelHandlerContext ctx, CasualNWMessage<T> msg) {
            ByteBuf buf = ctx.alloc().buffer();
            CasualMessageEncoder.write(buf, msg);
            ctx.writeAndFlush(buf);
        }
    }
}
