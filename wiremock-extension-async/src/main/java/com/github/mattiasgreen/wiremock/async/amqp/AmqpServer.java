package com.github.mattiasgreen.wiremock.async.amqp;

import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.apache.qpid.protonj2.buffer.ProtonBuffer;
import org.apache.qpid.protonj2.buffer.ProtonBufferAllocator;
import org.apache.qpid.protonj2.client.AdvancedMessage;
import org.apache.qpid.protonj2.client.Message;
import org.apache.qpid.protonj2.client.exceptions.ClientException;
import org.apache.qpid.protonj2.client.impl.ClientMessageSupport;
import org.apache.qpid.protonj2.engine.Connection;
import org.apache.qpid.protonj2.engine.Engine;
import org.apache.qpid.protonj2.engine.EngineFactory;
import org.apache.qpid.protonj2.engine.OutgoingDelivery;
import org.apache.qpid.protonj2.engine.Sender;
import org.apache.qpid.protonj2.engine.Session;
import org.apache.qpid.protonj2.engine.sasl.SaslOutcome;
import org.apache.qpid.protonj2.engine.sasl.SaslServerContext;
import org.apache.qpid.protonj2.engine.sasl.SaslServerListener;
import org.apache.qpid.protonj2.types.Symbol;
import org.apache.qpid.protonj2.types.messaging.Accepted;
import org.apache.qpid.protonj2.types.messaging.ApplicationProperties;
import org.apache.qpid.protonj2.types.messaging.Data;
import org.apache.qpid.protonj2.types.messaging.Properties;
import org.apache.qpid.protonj2.types.messaging.Source;
import org.apache.qpid.protonj2.types.messaging.Target;
import org.apache.qpid.protonj2.types.transport.AMQPHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-process AMQP 1.0 mock server powered by Apache Qpid Proton-J2 and Netty.
 * Provides SASL anonymous authentication, credit flow control, and settlement tracking.
 */
public class AmqpServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AmqpServer.class);

    private final int port;
    private final Consumer<AsyncMessage> messageConsumer;
    private final Map<String, Deque<AsyncMessage>> stagedFeeds = new ConcurrentHashMap<>();
    private final List<Sender> activeSenders = new CopyOnWriteArrayList<>();
    private final List<String> settlementAudit = new CopyOnWriteArrayList<>();
    private final AtomicLong deliveryTagCounter = new AtomicLong(1);

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ChannelFuture channelFuture;
    private volatile int boundPort = -1;

    public AmqpServer(int port, Consumer<AsyncMessage> messageConsumer) {
        this.port = port;
        this.messageConsumer = messageConsumer;
    }

    public synchronized void start() throws InterruptedException {
        if (boundPort > 0) {
            return;
        }

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(2);

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new AmqpNettyHandler());
                    }
                });

        channelFuture = bootstrap.bind(port).sync();
        InetSocketAddress localAddress =
                (InetSocketAddress) channelFuture.channel().localAddress();
        boundPort = localAddress.getPort();
        LOG.info("[AMQP-SERVER] In-process AMQP 1.0 server listening on port {}", boundPort);
    }

    public synchronized int getPort() {
        return boundPort;
    }

    public synchronized List<String> listQueues() {
        return Collections.unmodifiableList(new ArrayList<>(stagedFeeds.keySet()));
    }

    public synchronized List<AsyncMessage> peekQueue(String queue) {
        if (queue == null) return Collections.emptyList();
        Deque<AsyncMessage> q = stagedFeeds.get(queue);
        return q != null ? Collections.unmodifiableList(new ArrayList<>(q)) : Collections.emptyList();
    }

    public synchronized boolean deleteMessage(String queue, String messageId) {
        if (queue == null || messageId == null) return false;
        Deque<AsyncMessage> q = stagedFeeds.get(queue);
        if (q == null) return false;
        return q.removeIf(m -> messageId.equals(m.id()));
    }

    public synchronized void purgeQueue(String queue) {
        if (queue == null) return;
        Deque<AsyncMessage> q = stagedFeeds.get(queue);
        if (q != null) {
            q.clear();
        }
    }

    public synchronized boolean isRunning() {
        return boundPort > 0 && channelFuture != null && channelFuture.channel().isActive();
    }

    public void stageFeedMessage(String queue, AsyncMessage message) {
        if (queue == null || message == null) {
            return;
        }
        stagedFeeds.computeIfAbsent(queue, k -> new ArrayDeque<>()).add(message);
        dispatchFeedsToMatchingSenders(queue);
    }

    public List<String> getSettlementAudit() {
        return Collections.unmodifiableList(settlementAudit);
    }

    private synchronized void dispatchFeedsToMatchingSenders(String queue) {
        for (Sender sender : activeSenders) {
            if (sender.isSendable() && sender.getRemoteSource() != null) {
                String senderAddress = sender.getRemoteSource().getAddress();
                if (queue.equals(senderAddress)) {
                    drainQueueToSender(sender, queue);
                }
            }
        }
    }

    private synchronized void drainQueueToSender(Sender sender, String queue) {
        Deque<AsyncMessage> feedQueue = stagedFeeds.get(queue);
        if (feedQueue == null) {
            return;
        }

        while (sender.getCredit() > 0 && !feedQueue.isEmpty()) {
            AsyncMessage msg = feedQueue.poll();
            if (msg == null) {
                break;
            }

            try {
                AdvancedMessage<byte[]> advMsg = AdvancedMessage.create();
                Properties props = new Properties();
                if (msg.id() != null) {
                    props.setMessageId(msg.id());
                }
                if (msg.correlationId() != null) {
                    props.setCorrelationId(msg.correlationId());
                }
                if (msg.replyTo() != null) {
                    props.setReplyTo(msg.replyTo());
                }
                props.setTo(queue);
                advMsg.properties(props);

                if (msg.headers() != null && !msg.headers().isEmpty()) {
                    Map<String, Object> appProps = new HashMap<>(msg.headers());
                    advMsg.applicationProperties(new ApplicationProperties(appProps));
                }

                byte[] payloadBytes =
                        msg.payload() != null ? msg.payload().getBytes(StandardCharsets.UTF_8) : new byte[0];
                advMsg.addBodySection(new Data(payloadBytes));

                ProtonBuffer encoded =
                        ClientMessageSupport.encodeMessage(advMsg, null, ProtonBufferAllocator.defaultAllocator());

                OutgoingDelivery delivery = sender.next();
                byte[] tag = ("tag-" + deliveryTagCounter.getAndIncrement()).getBytes(StandardCharsets.UTF_8);
                delivery.setTag(tag);
                delivery.streamBytes(encoded, true);

                delivery.deliveryStateUpdatedHandler(d -> {
                    String state = String.valueOf(
                                    d.getRemoteState() != null
                                            ? d.getRemoteState().getType()
                                            : "SETTLED")
                            .toUpperCase(Locale.ROOT);
                    settlementAudit.add("DISPOSITION:" + queue + ":" + state);
                    LOG.debug("[AMQP-SERVER] Delivery disposition updated for queue {}: {}", queue, state);
                });
            } catch (ClientException e) {
                LOG.error(
                        "[AMQP-SERVER] Failed to encode and send AMQP message to queue {}: {}",
                        queue,
                        e.getMessage(),
                        e);
            }
        }
    }

    @Override
    public synchronized void close() {
        stagedFeeds.clear();
        activeSenders.clear();
        settlementAudit.clear();
        boundPort = -1;

        if (channelFuture != null) {
            channelFuture.channel().close();
            channelFuture = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        LOG.info("[AMQP-SERVER] AMQP 1.0 server stopped");
    }

    private class AmqpNettyHandler extends SimpleChannelInboundHandler<ByteBuf> {

        private Engine engine;
        private Connection connection;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            initEngine(ctx);
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            initEngine(ctx);
        }

        private synchronized void initEngine(ChannelHandlerContext ctx) {
            if (engine != null) {
                return;
            }
            engine = EngineFactory.PROTON.createEngine();

            // Configure SASL Anonymous & Plain listener
            engine.saslDriver().server().setListener(new SaslServerListener() {
                @Override
                public void handleSaslHeader(SaslServerContext context, AMQPHeader header) {
                    context.sendMechanisms(
                            new Symbol[] {Symbol.getSASLSymbol("ANONYMOUS"), Symbol.getSASLSymbol("PLAIN")});
                }

                @Override
                public void handleSaslInit(SaslServerContext context, Symbol mechanism, ProtonBuffer initResponse) {
                    context.sendOutcome(SaslOutcome.SASL_OK, null);
                }

                @Override
                public void handleSaslResponse(SaslServerContext context, ProtonBuffer response) {
                    context.sendOutcome(SaslOutcome.SASL_OK, null);
                }
            });

            // Route engine output back to Netty channel
            engine.outputHandler(buffer -> {
                int readable = buffer.getReadableBytes();
                byte[] bytes = new byte[readable];
                buffer.copyInto(buffer.getReadOffset(), bytes, 0, readable);
                ctx.writeAndFlush(Unpooled.wrappedBuffer(bytes));
            });

            // Start connection lifecycle
            connection = engine.start();
            connection.openHandler(c -> {
                c.setContainerId("wiremock-amqp-server");
                c.open();
            });

            connection.sessionOpenHandler(Session::open);

            // Client consuming from server: server is Sender
            connection.senderOpenHandler(sender -> {
                Source remoteSource = sender.getRemoteSource();
                if (remoteSource != null) {
                    sender.setSource(remoteSource.copy());
                }
                sender.open();
                activeSenders.add(sender);

                sender.creditStateUpdateHandler(s -> {
                    if (s.getCredit() > 0 && s.getRemoteSource() != null) {
                        drainQueueToSender(s, s.getRemoteSource().getAddress());
                    }
                });

                if (sender.getCredit() > 0 && sender.getRemoteSource() != null) {
                    drainQueueToSender(sender, sender.getRemoteSource().getAddress());
                }

                sender.localDetachHandler(s -> activeSenders.remove(s));
                sender.detachHandler(s -> activeSenders.remove(s));
                sender.closeHandler(s -> activeSenders.remove(s));
            });

            // Client producing to server: server is Receiver
            connection.receiverOpenHandler(receiver -> {
                Target remoteTarget = receiver.getRemoteTarget();
                if (remoteTarget != null) {
                    receiver.setTarget(remoteTarget.copy());
                }
                receiver.open();
                receiver.addCredit(100);

                receiver.deliveryReadHandler(delivery -> {
                    if (!delivery.isPartial()) {
                        ProtonBuffer buffer = delivery.readAll();
                        try {
                            Message<?> decoded = ClientMessageSupport.decodeMessage(buffer, null);
                            Target target = receiver.getRemoteTarget() instanceof Target t ? t : null;
                            String destination = target != null && target.getAddress() != null
                                    ? target.getAddress()
                                    : (decoded.to() != null ? decoded.to() : "default");

                            String correlationId =
                                    decoded.correlationId() != null ? String.valueOf(decoded.correlationId()) : null;
                            String replyTo = decoded.replyTo();
                            String messageId = decoded.messageId() != null
                                    ? String.valueOf(decoded.messageId())
                                    : UUID.randomUUID().toString();

                            Map<String, String> headers = new HashMap<>();
                            decoded.forEachProperty((k, v) -> headers.put(k, String.valueOf(v)));

                            String payloadStr = "";
                            Object body = decoded.body();
                            if (body instanceof byte[] b) {
                                payloadStr = new String(b, StandardCharsets.UTF_8);
                            } else if (body instanceof String s) {
                                payloadStr = s;
                            } else if (body != null) {
                                payloadStr = String.valueOf(body);
                            }

                            AsyncMessage asyncMsg = AsyncMessage.builder()
                                    .id(messageId)
                                    .correlationId(correlationId)
                                    .replyTo(replyTo)
                                    .destination(destination)
                                    .protocol("amqp")
                                    .payload(payloadStr)
                                    .headers(headers)
                                    .build();

                            if (messageConsumer != null) {
                                messageConsumer.accept(asyncMsg);
                            }

                            delivery.disposition(Accepted.getInstance(), true);
                            receiver.addCredit(1);
                        } catch (ClientException e) {
                            LOG.error("[AMQP-SERVER] Failed to decode inbound AMQP message: {}", e.getMessage(), e);
                        }
                    }
                });
            });
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
            initEngine(ctx);
            if (engine != null && engine.isRunning()) {
                byte[] bytes = new byte[msg.readableBytes()];
                msg.readBytes(bytes);
                engine.ingest(ProtonBufferAllocator.defaultAllocator().copy(bytes));
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (engine != null) {
                engine.shutdown();
                engine = null;
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            String sanitized = Objects.toString(cause.getMessage(), "").replaceAll("[\r\n]", "");
            LOG.error(
                    "[AMQP-SERVER] Connection error from {}: {}", ctx.channel().remoteAddress(), sanitized);
            ctx.close();
        }
    }
}
