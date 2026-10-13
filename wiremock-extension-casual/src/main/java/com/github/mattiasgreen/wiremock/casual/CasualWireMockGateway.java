package com.github.mattiasgreen.wiremock.casual;

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
import io.netty.handler.codec.ByteToMessageDecoder;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.laz.casual.api.buffer.type.JsonBuffer;
import se.laz.casual.api.buffer.type.ServiceBuffer;
import se.laz.casual.api.flags.ErrorState;
import se.laz.casual.api.flags.TransactionState;
import se.laz.casual.api.network.protocol.messages.CasualNWMessage;
import se.laz.casual.api.network.protocol.messages.CasualNWMessageType;
import se.laz.casual.network.ProtocolVersion;
import se.laz.casual.network.messages.domain.TransactionType;
import se.laz.casual.network.protocol.decoding.CasualMessageDecoder;
import se.laz.casual.network.protocol.encoding.CasualMessageEncoder;
import se.laz.casual.network.protocol.messages.CasualNWMessageHeader;
import se.laz.casual.network.protocol.messages.CasualNWMessageImpl;
import se.laz.casual.network.protocol.messages.domain.CasualDomainConnectReplyMessage;
import se.laz.casual.network.protocol.messages.domain.CasualDomainConnectRequestMessage;
import se.laz.casual.network.protocol.messages.domain.CasualDomainDiscoveryReplyMessage;
import se.laz.casual.network.protocol.messages.domain.CasualDomainDiscoveryRequestMessage;
import se.laz.casual.network.protocol.messages.domain.Service;
import se.laz.casual.network.protocol.messages.parseinfo.MessageHeaderSizes;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallReplyMessage;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallRequestMessage;

/**
 * Super-lightweight in-process Casual Domain Gateway server.
 * Listens on a TCP port using Netty and bridges Casual XATMI protocol messages
 * to WireMock HTTP stubbing endpoints without requiring Jakarta EE.
 */
public class CasualWireMockGateway implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(CasualWireMockGateway.class);

    private final int port;
    private final String wireMockBaseUrl;
    private final UUID domainId;
    private final String domainName;
    private final HttpClient httpClient;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ChannelFuture channelFuture;
    private volatile int boundPort;

    public CasualWireMockGateway(int port, String wireMockBaseUrl) {
        this.port = port;
        this.wireMockBaseUrl = wireMockBaseUrl.replaceAll("/+$", "");
        this.domainId = UUID.randomUUID();
        this.domainName = "wiremock-casual-domain";
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
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
                        ch.pipeline().addLast(new CasualGatewayHandler());
                    }
                });

        channelFuture = bootstrap.bind(port).sync();
        InetSocketAddress socketAddress =
                (InetSocketAddress) channelFuture.channel().localAddress();
        this.boundPort = socketAddress.getPort();
        LOG.info("CasualWireMockGateway listening on TCP port {}", boundPort);
    }

    public synchronized int getPort() {
        return boundPort > 0 ? boundPort : port;
    }

    @Override
    public synchronized void close() {
        if (channelFuture != null) {
            channelFuture.channel().close();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        LOG.info("CasualWireMockGateway stopped");
    }

    /**
     * Netty decoder for 32-byte header + payload framing.
     */
    private static class CasualFrameDecoder extends ByteToMessageDecoder {
        private static final int HEADER_SIZE = MessageHeaderSizes.getHeaderNetworkSize();

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (in.readableBytes() < HEADER_SIZE) {
                return;
            }

            in.markReaderIndex();
            byte[] headerBytes = new byte[HEADER_SIZE];
            in.readBytes(headerBytes);

            CasualNWMessageHeader header = CasualMessageDecoder.networkHeaderToCasualHeader(headerBytes);
            long payloadSize = header.getPayloadSize();

            if (in.readableBytes() < payloadSize) {
                in.resetReaderIndex();
                return;
            }

            byte[] payloadBytes = new byte[(int) payloadSize];
            in.readBytes(payloadBytes);

            CasualNWMessage<?> message =
                    CasualMessageDecoder.read(payloadBytes, header, () -> ProtocolVersion.VERSION_1_0);
            out.add(message);
        }
    }

    /**
     * Inbound handler that translates Casual messages and dispatches to WireMock.
     */
    private class CasualGatewayHandler extends SimpleChannelInboundHandler<CasualNWMessage<?>> {

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

            // Dynamically declare requested services as known/discoverable
            List<Service> services = req.getServiceNames().stream()
                    .map(name -> Service.of(name, "wiremock-category", TransactionType.AUTOMATIC))
                    .toList();
            reply.setServices(services);
            reply.setQueues(Collections.emptyList());

            CasualNWMessage<CasualDomainDiscoveryReplyMessage> replyMsg =
                    CasualNWMessageImpl.of(msg.getCorrelationId(), reply);
            writeMessage(ctx, replyMsg);
        }

        private void handleServiceCall(
                ChannelHandlerContext ctx, CasualNWMessage<CasualServiceCallRequestMessage> msg) {
            CasualServiceCallRequestMessage req = msg.getMessage();
            String serviceName = req.getServiceName();
            ServiceBuffer requestBuffer = req.getServiceBuffer();

            String requestPayload = "";
            String bufferType = "JSON";

            if (requestBuffer != null && !requestBuffer.isEmpty()) {
                bufferType = requestBuffer.getType();
                StringBuilder sb = new StringBuilder();
                for (byte[] part : requestBuffer.getBytes()) {
                    sb.append(new String(part, StandardCharsets.UTF_8));
                }
                requestPayload = sb.toString();
            }

            // Bridge to WireMock HTTP endpoint
            String wireMockUrl = wireMockBaseUrl + "/__casual/services/" + serviceName;
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(wireMockUrl))
                    .header("Content-Type", "application/json")
                    .header("X-Casual-Service", serviceName)
                    .header("X-Casual-Buffer-Type", bufferType)
                    .POST(HttpRequest.BodyPublishers.ofString(requestPayload, StandardCharsets.UTF_8))
                    .build();

            ErrorState errorState;
            ServiceBuffer replyBuffer = ServiceBuffer.empty();

            try {
                HttpResponse<String> response =
                        httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int statusCode = response.statusCode();

                if (statusCode >= 200 && statusCode < 300) {
                    errorState = ErrorState.OK;
                    String respBody = response.body();
                    replyBuffer = ServiceBuffer.of(JsonBuffer.of(respBody));
                } else if (statusCode == 404) {
                    errorState = ErrorState.TPENOENT;
                } else {
                    errorState = ErrorState.TPESVCFAIL;
                }
            } catch (IOException | InterruptedException e) {
                LOG.error("Failed to dispatch Casual service call to WireMock", e);
                errorState = ErrorState.TPESVCERR;
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }

            CasualServiceCallReplyMessage reply = CasualServiceCallReplyMessage.createBuilder()
                    .setProtocolVersion(ProtocolVersion.VERSION_1_0)
                    .setExecution(req.getExecution())
                    .setXid(req.getXid())
                    .setError(errorState)
                    .setTransactionState(TransactionState.TX_ACTIVE)
                    .setServiceBuffer(replyBuffer)
                    .build();

            CasualNWMessage<CasualServiceCallReplyMessage> replyMsg =
                    CasualNWMessageImpl.of(msg.getCorrelationId(), reply);
            writeMessage(ctx, replyMsg);
        }

        private <T extends se.laz.casual.api.network.protocol.messages.CasualNetworkTransmittable> void writeMessage(
                ChannelHandlerContext ctx, CasualNWMessage<T> msg) {
            ByteBuf buf = ctx.alloc().buffer();
            CasualMessageEncoder.write(buf, msg);
            ctx.writeAndFlush(buf);
        }
    }
}
