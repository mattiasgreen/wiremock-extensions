package com.github.mattiasgreen.wiremock.casual;

import io.netty.bootstrap.ServerBootstrap;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.transaction.xa.Xid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.laz.casual.api.flags.AtmiFlags;
import se.laz.casual.api.network.protocol.messages.CasualNWMessage;
import se.laz.casual.api.network.protocol.messages.CasualNWMessageType;
import se.laz.casual.api.network.protocol.messages.exception.CasualProtocolException;
import se.laz.casual.api.xa.XAReturnCode;
import se.laz.casual.api.xa.XID;
import se.laz.casual.network.CasualNWMessageDecoder;
import se.laz.casual.network.CasualNWMessageEncoder;
import se.laz.casual.network.ProtocolMatcher;
import se.laz.casual.network.ProtocolVersion;
import se.laz.casual.network.inbound.ProtocolVersionValueHolder;
import se.laz.casual.network.protocol.messages.CasualNWMessageImpl;
import se.laz.casual.network.protocol.messages.domain.CasualDomainConnectReplyMessage;
import se.laz.casual.network.protocol.messages.domain.CasualDomainConnectRequestMessage;
import se.laz.casual.network.protocol.messages.domain.CasualDomainDiscoveryReplyMessage;
import se.laz.casual.network.protocol.messages.domain.CasualDomainDiscoveryRequestMessage;
import se.laz.casual.network.protocol.messages.domain.Queue;
import se.laz.casual.network.protocol.messages.domain.Service;
import se.laz.casual.network.protocol.messages.queue.CasualDequeueReplyMessage;
import se.laz.casual.network.protocol.messages.queue.CasualDequeueRequestMessage;
import se.laz.casual.network.protocol.messages.queue.CasualEnqueueReplyMessage;
import se.laz.casual.network.protocol.messages.queue.CasualEnqueueRequestMessage;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallReplyMessage;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallRequestMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceCommitReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceCommitRequestMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourcePrepareReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourcePrepareRequestMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceRollbackReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceRollbackRequestMessage;

/**
 * In-process Casual Domain Gateway server listening on a TCP port using Netty.
 */
public class CasualServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(CasualServer.class);

    static {
        CasualProtocolConfig.ensureInitialized();
    }

    @FunctionalInterface
    public interface ServiceHandler {
        CasualServiceCallReplyMessage handle(CasualServiceCallRequestMessage request);

        default CasualServiceCallReplyMessage handle(
                CasualServiceCallRequestMessage request, java.net.SocketAddress remoteAddress) {
            return handle(request);
        }
    }

    @FunctionalInterface
    public interface DiscoveryHandler {
        List<Service> discover(List<String> serviceNames);

        default List<Service> discover(CasualDomainDiscoveryRequestMessage request) {
            return discover(request != null ? request.getServiceNames() : Collections.emptyList());
        }
    }

    public interface QueueHandler {
        CasualEnqueueReplyMessage enqueue(CasualEnqueueRequestMessage request, ProtocolVersion version);

        CasualDequeueReplyMessage dequeue(CasualDequeueRequestMessage request, ProtocolVersion version);
    }

    @FunctionalInterface
    public interface QueueDiscoveryHandler {
        List<Queue> discover(List<String> queueNames, ProtocolVersion version);
    }

    public interface TransactionHandler {
        CasualTransactionResourcePrepareReplyMessage prepare(CasualTransactionResourcePrepareRequestMessage request);

        CasualTransactionResourceCommitReplyMessage commit(CasualTransactionResourceCommitRequestMessage request);

        CasualTransactionResourceRollbackReplyMessage rollback(CasualTransactionResourceRollbackRequestMessage request);
    }

    private final int port;
    private final UUID domainId;
    private final String domainName;
    private final ServiceHandler serviceHandler;
    private final DiscoveryHandler discoveryHandler;
    private final TransactionHandler transactionHandler;
    private final QueueHandler queueHandler;
    private final QueueDiscoveryHandler queueDiscoveryHandler;
    private final int workerThreads;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private volatile ExecutorService requestExecutor;
    private ChannelFuture channelFuture;
    private volatile int boundPort;

    public CasualServer(
            int port,
            UUID domainId,
            String domainName,
            ServiceHandler serviceHandler,
            DiscoveryHandler discoveryHandler) {
        this(port, domainId, domainName, serviceHandler, discoveryHandler, null, null, null);
    }

    public CasualServer(
            int port,
            UUID domainId,
            String domainName,
            ServiceHandler serviceHandler,
            DiscoveryHandler discoveryHandler,
            TransactionHandler transactionHandler) {
        this(port, domainId, domainName, serviceHandler, discoveryHandler, transactionHandler, null, null);
    }

    public CasualServer(
            int port,
            UUID domainId,
            String domainName,
            ServiceHandler serviceHandler,
            DiscoveryHandler discoveryHandler,
            TransactionHandler transactionHandler,
            QueueHandler queueHandler,
            QueueDiscoveryHandler queueDiscoveryHandler) {
        this(
                port,
                domainId,
                domainName,
                serviceHandler,
                discoveryHandler,
                transactionHandler,
                queueHandler,
                queueDiscoveryHandler,
                0);
    }

    public CasualServer(
            int port,
            UUID domainId,
            String domainName,
            ServiceHandler serviceHandler,
            DiscoveryHandler discoveryHandler,
            TransactionHandler transactionHandler,
            QueueHandler queueHandler,
            QueueDiscoveryHandler queueDiscoveryHandler,
            int workerThreads) {
        this.port = port;
        this.domainId = Objects.requireNonNull(domainId, "domainId must not be null");
        this.domainName = Objects.requireNonNull(domainName, "domainName must not be null");
        this.serviceHandler = Objects.requireNonNull(serviceHandler, "serviceHandler must not be null");
        this.discoveryHandler = discoveryHandler;
        this.transactionHandler = transactionHandler;
        this.queueHandler = queueHandler;
        this.queueDiscoveryHandler = queueDiscoveryHandler;
        this.workerThreads = Math.max(0, workerThreads);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int port = 0;
        private UUID domainId = UUID.randomUUID();
        private String domainName = "casual-server";
        private ServiceHandler serviceHandler;
        private DiscoveryHandler discoveryHandler;
        private TransactionHandler transactionHandler;
        private QueueHandler queueHandler;
        private QueueDiscoveryHandler queueDiscoveryHandler;
        private int workerThreads = 0;

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

        public Builder serviceHandler(ServiceHandler serviceHandler) {
            this.serviceHandler = serviceHandler;
            return this;
        }

        public Builder discoveryHandler(DiscoveryHandler discoveryHandler) {
            this.discoveryHandler = discoveryHandler;
            return this;
        }

        public Builder transactionHandler(TransactionHandler transactionHandler) {
            this.transactionHandler = transactionHandler;
            return this;
        }

        public Builder queueHandler(QueueHandler queueHandler) {
            this.queueHandler = queueHandler;
            return this;
        }

        public Builder queueDiscoveryHandler(QueueDiscoveryHandler queueDiscoveryHandler) {
            this.queueDiscoveryHandler = queueDiscoveryHandler;
            return this;
        }

        public Builder workerThreads(int workerThreads) {
            this.workerThreads = Math.max(0, workerThreads);
            return this;
        }

        public CasualServer build() {
            return new CasualServer(
                    port,
                    domainId,
                    domainName,
                    serviceHandler,
                    discoveryHandler,
                    transactionHandler,
                    queueHandler,
                    queueDiscoveryHandler,
                    workerThreads);
        }
    }

    public synchronized void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = workerThreads > 0 ? new NioEventLoopGroup(workerThreads) : new NioEventLoopGroup();
        requestExecutor = Executors.newVirtualThreadPerTaskExecutor();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ProtocolVersionValueHolder protocolHolder = ProtocolVersionValueHolder.of();
                        ch.pipeline().addLast(CasualNWMessageDecoder.of(protocolHolder));
                        ch.pipeline().addLast(CasualNWMessageEncoder.of());
                        ch.pipeline().addLast(new ServerInboundHandler(protocolHolder));
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

    public synchronized boolean isRunning() {
        return channelFuture != null && channelFuture.channel().isOpen();
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
        if (requestExecutor != null) {
            requestExecutor.shutdownNow();
            requestExecutor = null;
        }
        LOG.info("CasualServer stopped");
    }

    public ServerInboundHandler createInboundHandler() {
        return new ServerInboundHandler(ProtocolVersionValueHolder.of());
    }

    public ServerInboundHandler createInboundHandler(ProtocolVersionValueHolder protocolHolder) {
        return new ServerInboundHandler(protocolHolder);
    }

    public class ServerInboundHandler extends SimpleChannelInboundHandler<CasualNWMessage<?>> {

        private final ProtocolVersionValueHolder protocolHolder;

        public ServerInboundHandler() {
            this(ProtocolVersionValueHolder.of());
        }

        public ServerInboundHandler(ProtocolVersionValueHolder protocolHolder) {
            this.protocolHolder = Objects.requireNonNull(protocolHolder, "protocolHolder cannot be null");
        }

        @Override
        @SuppressWarnings("unchecked")
        public void channelRead0(ChannelHandlerContext ctx, CasualNWMessage<?> msg) {
            CasualNWMessageType type = msg.getType();

            if (LOG.isDebugEnabled()) {
                LOG.debug(
                        "[CASUAL-SERVER-INBOUND] Received frame type={} correlationId={} from={}",
                        type,
                        msg.getCorrelationId(),
                        ctx.channel().remoteAddress());
            }

            if (type == CasualNWMessageType.DOMAIN_CONNECT_REQUEST) {
                handleDomainConnect(ctx, (CasualNWMessage<CasualDomainConnectRequestMessage>) msg);
            } else if (type == CasualNWMessageType.DOMAIN_DISCOVERY_REQUEST) {
                dispatch(
                        ctx,
                        () -> handleDomainDiscovery(ctx, (CasualNWMessage<CasualDomainDiscoveryRequestMessage>) msg));
            } else if (type == CasualNWMessageType.SERVICE_CALL_REQUEST
                    || type == CasualNWMessageType.SERVICE_CALL_REQUEST_V_1_3
                    || type == CasualNWMessageType.SERVICE_CALL_REQUEST_V_1_5) {
                dispatch(ctx, () -> handleServiceCall(ctx, (CasualNWMessage<CasualServiceCallRequestMessage>) msg));
            } else if (type == CasualNWMessageType.PREPARE_REQUEST) {
                dispatch(
                        ctx,
                        () -> handlePrepare(
                                ctx, (CasualNWMessage<CasualTransactionResourcePrepareRequestMessage>) msg));
            } else if (type == CasualNWMessageType.COMMIT_REQUEST) {
                dispatch(
                        ctx,
                        () -> handleCommit(ctx, (CasualNWMessage<CasualTransactionResourceCommitRequestMessage>) msg));
            } else if (type == CasualNWMessageType.REQUEST_ROLLBACK) {
                dispatch(
                        ctx,
                        () -> handleRollback(
                                ctx, (CasualNWMessage<CasualTransactionResourceRollbackRequestMessage>) msg));
            } else if (type == CasualNWMessageType.ENQUEUE_REQUEST) {
                handleEnqueue(ctx, (CasualNWMessage<CasualEnqueueRequestMessage>) msg);
            } else if (type == CasualNWMessageType.DEQUEUE_REQUEST) {
                handleDequeue(ctx, (CasualNWMessage<CasualDequeueRequestMessage>) msg);
            } else {
                String safeType = String.valueOf(type).replaceAll("[\r\n]", "");
                LOG.error(
                        "[CASUAL-SERVER-UNSUPPORTED] Unhandled Casual message type [{}] (correlationId={}) from [{}]. Closing channel immediately to fail fast and prevent client hang.",
                        safeType,
                        msg.getCorrelationId(),
                        ctx.channel().remoteAddress());
                ctx.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.error(
                    "[CASUAL-SERVER-ERROR] Netty channel error from [{}]: {}",
                    ctx.channel().remoteAddress(),
                    cause.getMessage(),
                    cause);
            ctx.close();
        }

        private void handleDomainConnect(
                ChannelHandlerContext ctx, CasualNWMessage<CasualDomainConnectRequestMessage> msg) {
            CasualDomainConnectRequestMessage req = msg.getMessage();
            long matchedVersion = ProtocolMatcher.match(req.getProtocols());
            ProtocolVersion negotiated = ProtocolVersion.unmarshall(matchedVersion);
            try {
                protocolHolder.accept(negotiated);
            } catch (CasualProtocolException e) {
                LOG.debug("Protocol version already accepted: {}", e.getMessage());
            }

            if (LOG.isInfoEnabled()) {
                LOG.info(
                        "[CASUAL-SERVER-CONNECT] Client from [{}] requested protocols {} -> negotiated version: {}",
                        ctx.channel().remoteAddress(),
                        req.getProtocols(),
                        negotiated);
            }

            CasualDomainConnectReplyMessage reply = CasualDomainConnectReplyMessage.createBuilder()
                    .withExecution(req.getExecution())
                    .withDomainId(domainId)
                    .withDomainName(domainName)
                    .withProtocolVersion(matchedVersion)
                    .build();

            CasualNWMessage<CasualDomainConnectReplyMessage> replyMsg =
                    CasualNWMessageImpl.of(msg.getCorrelationId(), reply);
            ctx.writeAndFlush(replyMsg);
        }

        private void handleDomainDiscovery(
                ChannelHandlerContext ctx, CasualNWMessage<CasualDomainDiscoveryRequestMessage> msg) {
            CasualDomainDiscoveryRequestMessage req = msg.getMessage();
            ProtocolVersion version = ProtocolVersion.VERSION_1_0;
            try {
                version = protocolHolder.get();
            } catch (CasualProtocolException e) {
                LOG.debug("Protocol version not yet available on discovery: {}", e.getMessage());
            }
            CasualDomainDiscoveryReplyMessage reply =
                    CasualDomainDiscoveryReplyMessage.of(req.getExecution(), domainId, domainName, version);

            List<Service> services =
                    discoveryHandler != null ? discoveryHandler.discover(req) : Collections.emptyList();
            reply.setServices(services);

            List<Queue> queues = queueDiscoveryHandler != null
                    ? queueDiscoveryHandler.discover(req.getQueueNames(), version)
                    : Collections.emptyList();
            reply.setQueues(queues);

            CasualNWMessage<CasualDomainDiscoveryReplyMessage> replyMsg =
                    CasualNWMessageImpl.of(msg.getCorrelationId(), reply);
            ctx.writeAndFlush(replyMsg);
        }

        private void dispatch(ChannelHandlerContext ctx, Runnable task) {
            ExecutorService executor = requestExecutor;
            if (executor == null || executor.isShutdown()) {
                runSafely(ctx, task);
                return;
            }
            executor.execute(() -> runSafely(ctx, task));
        }

        private void runSafely(ChannelHandlerContext ctx, Runnable task) {
            try {
                task.run();
            } catch (Throwable t) {
                String safeMsg = t.getMessage() != null ? t.getMessage().replaceAll("[\r\n]", "") : "";
                LOG.error(
                        "[CASUAL-SERVER-WORKER-ERROR] Unhandled error processing Casual request from [{}]: {}",
                        ctx.channel().remoteAddress(),
                        safeMsg,
                        t);
                ctx.close();
            }
        }

        private void handleEnqueue(ChannelHandlerContext ctx, CasualNWMessage<CasualEnqueueRequestMessage> msg) {
            if (queueHandler == null) {
                LOG.error(
                        "[CASUAL-SERVER-QUEUE] Enqueue request received but no QueueHandler registered. Closing connection.");
                ctx.close();
                return;
            }
            dispatch(ctx, () -> {
                ProtocolVersion version = ProtocolVersion.VERSION_1_0;
                try {
                    version = protocolHolder.get();
                } catch (CasualProtocolException e) {
                    LOG.debug("Protocol version not yet available on enqueue: {}", e.getMessage());
                }
                CasualEnqueueReplyMessage reply = queueHandler.enqueue(msg.getMessage(), version);
                CasualNWMessage<CasualEnqueueReplyMessage> replyMsg =
                        CasualNWMessageImpl.of(msg.getCorrelationId(), reply);
                ctx.writeAndFlush(replyMsg);
            });
        }

        private void handleDequeue(ChannelHandlerContext ctx, CasualNWMessage<CasualDequeueRequestMessage> msg) {
            if (queueHandler == null) {
                LOG.error(
                        "[CASUAL-SERVER-QUEUE] Dequeue request received but no QueueHandler registered. Closing connection.");
                ctx.close();
                return;
            }
            dispatch(ctx, () -> {
                ProtocolVersion version = ProtocolVersion.VERSION_1_0;
                try {
                    version = protocolHolder.get();
                } catch (CasualProtocolException e) {
                    LOG.debug("Protocol version not yet available on dequeue: {}", e.getMessage());
                }
                CasualDequeueReplyMessage reply = queueHandler.dequeue(msg.getMessage(), version);
                CasualNWMessage<CasualDequeueReplyMessage> replyMsg =
                        CasualNWMessageImpl.of(msg.getCorrelationId(), reply);
                ctx.writeAndFlush(replyMsg);
            });
        }

        private void handleServiceCall(
                ChannelHandlerContext ctx, CasualNWMessage<CasualServiceCallRequestMessage> msg) {
            CasualServiceCallRequestMessage req = msg.getMessage();
            CasualServiceCallReplyMessage reply =
                    serviceHandler.handle(req, ctx.channel().remoteAddress());
            if (CasualProtocolConfig.safeXatmiFlags(req).isSet(AtmiFlags.TPNOREPLY)) {
                // TPNOREPLY is fire-and-forget on the wire: never send a SERVICE_CALL_REPLY back.
                return;
            }
            ProtocolVersion connVersion = ProtocolVersion.VERSION_1_0;
            try {
                connVersion = protocolHolder.get();
            } catch (CasualProtocolException e) {
                LOG.debug("Protocol version not yet available on service call: {}", e.getMessage());
            }
            if (connVersion != null) {
                boolean needsRebuild = false;
                if (connVersion.isGreaterThanOrEqualTo(ProtocolVersion.VERSION_1_5)) {
                    if (reply.getType() != CasualNWMessageType.SERVICE_CALL_REPLY_V_1_5) {
                        needsRebuild = true;
                    }
                } else if (connVersion.isGreaterThanOrEqualTo(ProtocolVersion.VERSION_1_3)) {
                    if (reply.getType() != CasualNWMessageType.SERVICE_CALL_REPLY_V_1_3) {
                        needsRebuild = true;
                    }
                } else {
                    if (reply.getType() != CasualNWMessageType.SERVICE_CALL_REPLY) {
                        needsRebuild = true;
                    }
                }
                if (needsRebuild) {
                    CasualServiceCallReplyMessage.Builder b = CasualServiceCallReplyMessage.createBuilder()
                            .setProtocolVersion(connVersion)
                            .setExecution(reply.getExecution())
                            .setError(reply.getError())
                            .setUserSuppliedError(reply.getUserDefinedCode())
                            .setTransactionState(reply.getTransactionState())
                            .setServiceBuffer(reply.getServiceBuffer());
                    if (connVersion.isLessThan(ProtocolVersion.VERSION_1_3)) {
                        Xid replyXid =
                                (reply.getType() == CasualNWMessageType.SERVICE_CALL_REPLY) ? reply.getXid() : null;
                        if (replyXid == null && msg.getMessage() != null) {
                            replyXid = msg.getMessage().getXid();
                        }
                        b.setXid(replyXid != null ? replyXid : XID.NULL_XID);
                    }
                    reply = b.build();
                }
            }
            CasualNWMessage<CasualServiceCallReplyMessage> replyMsg =
                    CasualNWMessageImpl.of(msg.getCorrelationId(), reply);
            ctx.writeAndFlush(replyMsg);
        }

        private void handlePrepare(
                ChannelHandlerContext ctx, CasualNWMessage<CasualTransactionResourcePrepareRequestMessage> msg) {
            CasualTransactionResourcePrepareRequestMessage req = msg.getMessage();
            CasualTransactionResourcePrepareReplyMessage reply = transactionHandler != null
                    ? transactionHandler.prepare(req)
                    : CasualTransactionResourcePrepareReplyMessage.of(
                            req.getExecution(), req.getXid(), req.getResourceId(), XAReturnCode.XA_OK);
            ctx.writeAndFlush(CasualNWMessageImpl.of(msg.getCorrelationId(), reply));
        }

        private void handleCommit(
                ChannelHandlerContext ctx, CasualNWMessage<CasualTransactionResourceCommitRequestMessage> msg) {
            CasualTransactionResourceCommitRequestMessage req = msg.getMessage();
            CasualTransactionResourceCommitReplyMessage reply = transactionHandler != null
                    ? transactionHandler.commit(req)
                    : CasualTransactionResourceCommitReplyMessage.of(
                            req.getExecution(), req.getXid(), req.getResourceId(), XAReturnCode.XA_OK);
            ctx.writeAndFlush(CasualNWMessageImpl.of(msg.getCorrelationId(), reply));
        }

        private void handleRollback(
                ChannelHandlerContext ctx, CasualNWMessage<CasualTransactionResourceRollbackRequestMessage> msg) {
            CasualTransactionResourceRollbackRequestMessage req = msg.getMessage();
            CasualTransactionResourceRollbackReplyMessage reply = transactionHandler != null
                    ? transactionHandler.rollback(req)
                    : CasualTransactionResourceRollbackReplyMessage.of(
                            req.getExecution(), req.getXid(), req.getResourceId(), XAReturnCode.XA_OK);
            ctx.writeAndFlush(CasualNWMessageImpl.of(msg.getCorrelationId(), reply));
        }
    }
}
