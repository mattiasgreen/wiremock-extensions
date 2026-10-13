package com.github.mattiasgreen.wiremock.casual;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
import se.laz.casual.network.protocol.encoding.CasualMessageEncoder;
import se.laz.casual.network.protocol.messages.CasualNWMessageImpl;
import se.laz.casual.network.protocol.messages.domain.CasualDomainConnectReplyMessage;
import se.laz.casual.network.protocol.messages.domain.CasualDomainConnectRequestMessage;
import se.laz.casual.network.protocol.messages.domain.CasualDomainDiscoveryReplyMessage;
import se.laz.casual.network.protocol.messages.domain.CasualDomainDiscoveryRequestMessage;
import se.laz.casual.network.protocol.messages.domain.Service;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallReplyMessage;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallRequestMessage;

/**
 * Super-lightweight in-process Casual Domain Gateway server.
 * Listens on a TCP port using Netty and bridges Casual XATMI protocol messages
 * to WireMock HTTP stubbing endpoints without requiring Jakarta EE.
 * <p>
 * Supports transparent proxy forwarding to an upstream Casual domain when
 * stubs are unmatched, and optional automatic stub recording.
 */
public class CasualWireMockGateway implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(CasualWireMockGateway.class);

    private final int port;
    private final String wireMockBaseUrl;
    private final String upstreamHost;
    private final int upstreamPort;
    private final boolean recordStubs;
    private final UUID domainId;
    private final String domainName;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private ChannelFuture channelFuture;
    private volatile int boundPort;
    private CasualTcpClient upstreamClient;

    public CasualWireMockGateway(int port, String wireMockBaseUrl) {
        this(port, wireMockBaseUrl, null, 0, false);
    }

    public CasualWireMockGateway(
            int port, String wireMockBaseUrl, String upstreamHost, int upstreamPort, boolean recordStubs) {
        this.port = port;
        this.wireMockBaseUrl = wireMockBaseUrl.replaceAll("/+$", "");
        this.upstreamHost = upstreamHost;
        this.upstreamPort = upstreamPort;
        this.recordStubs = recordStubs;
        this.domainId = UUID.randomUUID();
        this.domainName = "wiremock-casual-domain";
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.objectMapper = new ObjectMapper();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int port = 0;
        private String wireMockBaseUrl;
        private String upstreamHost;
        private int upstreamPort = 0;
        private boolean recordStubs = false;

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder wireMockBaseUrl(String wireMockBaseUrl) {
            this.wireMockBaseUrl = wireMockBaseUrl;
            return this;
        }

        public Builder upstream(String host, int port) {
            this.upstreamHost = host;
            this.upstreamPort = port;
            return this;
        }

        public Builder recordStubs(boolean recordStubs) {
            this.recordStubs = recordStubs;
            return this;
        }

        public CasualWireMockGateway build() {
            if (wireMockBaseUrl == null || wireMockBaseUrl.isBlank()) {
                throw new IllegalStateException("wireMockBaseUrl must be specified");
            }
            return new CasualWireMockGateway(port, wireMockBaseUrl, upstreamHost, upstreamPort, recordStubs);
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

    public boolean hasUpstream() {
        return upstreamHost != null && !upstreamHost.isBlank() && upstreamPort > 0;
    }

    private synchronized CasualTcpClient getUpstreamClient() throws Exception {
        if (upstreamClient == null || !upstreamClient.isConnected()) {
            if (upstreamClient != null) {
                upstreamClient.close();
            }
            upstreamClient = new CasualTcpClient(upstreamHost, upstreamPort);
            upstreamClient.connect();
        }
        return upstreamClient;
    }

    @Override
    public synchronized void close() {
        if (upstreamClient != null) {
            upstreamClient.close();
            upstreamClient = null;
        }
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
        LOG.info("CasualWireMockGateway stopped");
    }

    /**
     * Inbound handler that translates Casual messages, dispatches to WireMock,
     * and transparently proxies unmatched calls to upstream Casual servers.
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

            String requestPayload = extractPayload(requestBuffer);
            String bufferType = (requestBuffer != null && !requestBuffer.isEmpty()) ? requestBuffer.getType() : "JSON";

            // Bridge to WireMock HTTP endpoint
            String wireMockUrl = wireMockBaseUrl + "/__casual/services/" + serviceName;
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(wireMockUrl))
                    .header("Content-Type", "application/json")
                    .header("X-Casual-Service", serviceName)
                    .header("X-Casual-Buffer-Type", bufferType)
                    .POST(HttpRequest.BodyPublishers.ofString(requestPayload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = null;
            try {
                response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException | InterruptedException e) {
                LOG.error("Failed to dispatch Casual service call to WireMock", e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                sendErrorReply(ctx, msg, ErrorState.TPESVCERR);
                return;
            }

            // A request was matched if WireMock provided a Matched-Stub-Id header
            boolean wasMatched =
                    response.headers().firstValue("Matched-Stub-Id").isPresent();

            if (wasMatched) {
                // Matched stub: serve response directly from WireMock
                handleMatchedResponse(ctx, msg, response);
            } else if (hasUpstream()) {
                // Unmatched request: transparently forward to upstream Casual server
                handleUpstreamForward(ctx, msg, serviceName, requestPayload);
            } else {
                // Unmatched request and no upstream configured: return TPENOENT
                sendErrorReply(ctx, msg, ErrorState.TPENOENT);
            }
        }

        private void handleMatchedResponse(
                ChannelHandlerContext ctx,
                CasualNWMessage<CasualServiceCallRequestMessage> msg,
                HttpResponse<String> response) {
            CasualServiceCallRequestMessage req = msg.getMessage();
            int statusCode = response.statusCode();
            ErrorState errorState;
            ServiceBuffer replyBuffer = ServiceBuffer.empty();

            if (statusCode >= 200 && statusCode < 300) {
                errorState = ErrorState.OK;
                String respBody = response.body();
                replyBuffer = ServiceBuffer.of(JsonBuffer.of(respBody));
            } else {
                Optional<String> customErrorHeader = response.headers().firstValue("X-Casual-Error-State");
                if (customErrorHeader.isPresent()) {
                    try {
                        errorState = ErrorState.valueOf(
                                customErrorHeader.get().trim().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException e) {
                        errorState = ErrorState.TPESVCFAIL;
                    }
                } else if (statusCode == 404) {
                    errorState = ErrorState.TPENOENT;
                } else {
                    errorState = ErrorState.TPESVCFAIL;
                }

                String respBody = response.body();
                if (respBody != null && !respBody.isBlank()) {
                    replyBuffer = ServiceBuffer.of(JsonBuffer.of(respBody));
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

        private void handleUpstreamForward(
                ChannelHandlerContext ctx,
                CasualNWMessage<CasualServiceCallRequestMessage> msg,
                String serviceName,
                String requestPayload) {
            try {
                CasualTcpClient client = getUpstreamClient();
                CasualNWMessage<CasualServiceCallReplyMessage> upstreamReplyMsg =
                        client.forwardCall(msg, 10, TimeUnit.SECONDS);
                CasualServiceCallReplyMessage upstreamReply = upstreamReplyMsg.getMessage();

                if (recordStubs && upstreamReply.getError() == ErrorState.OK) {
                    String replyPayload = extractPayload(upstreamReply.getServiceBuffer());
                    recordStubToWireMock(serviceName, requestPayload, replyPayload);
                }

                // Return upstream reply to client echoing original correlation ID
                CasualNWMessage<CasualServiceCallReplyMessage> replyToClient =
                        CasualNWMessageImpl.of(msg.getCorrelationId(), upstreamReply);
                writeMessage(ctx, replyToClient);
            } catch (Exception e) {
                LOG.error("Failed to forward Casual service call to upstream", e);
                sendErrorReply(ctx, msg, ErrorState.TPESVCERR);
            }
        }

        private void recordStubToWireMock(String serviceName, String requestPayload, String replyPayload) {
            try {
                Map<String, Object> mapping = new LinkedHashMap<>();
                Map<String, Object> requestMap = new LinkedHashMap<>();
                requestMap.put("method", "POST");
                requestMap.put("url", "/__casual/services/" + serviceName);

                if (requestPayload != null && !requestPayload.trim().isEmpty()) {
                    requestMap.put("bodyPatterns", List.of(Map.of("equalToJson", requestPayload)));
                }
                mapping.put("request", requestMap);

                Map<String, Object> responseMap = new LinkedHashMap<>();
                responseMap.put("status", 200);
                responseMap.put("body", replyPayload != null ? replyPayload : "");
                responseMap.put("headers", Map.of("Content-Type", "application/json"));
                mapping.put("response", responseMap);

                String json = objectMapper.writeValueAsString(mapping);
                HttpRequest recordRequest = HttpRequest.newBuilder()
                        .uri(URI.create(wireMockBaseUrl + "/__admin/mappings"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> recordResponse =
                        httpClient.send(recordRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                String safeServiceName = serviceName.replaceAll("[\r\n]", "");
                if (recordResponse.statusCode() >= 200 && recordResponse.statusCode() < 300) {
                    LOG.info("Recorded stub for Casual service {} to WireMock", safeServiceName);
                } else {
                    LOG.warn(
                            "Failed to record stub for Casual service {}: status {}",
                            safeServiceName,
                            recordResponse.statusCode());
                }
            } catch (IOException | InterruptedException e) {
                LOG.warn("Failed to record stub for Casual service to WireMock", e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void sendErrorReply(
                ChannelHandlerContext ctx,
                CasualNWMessage<CasualServiceCallRequestMessage> msg,
                ErrorState errorState) {
            CasualServiceCallRequestMessage req = msg.getMessage();
            CasualServiceCallReplyMessage reply = CasualServiceCallReplyMessage.createBuilder()
                    .setProtocolVersion(ProtocolVersion.VERSION_1_0)
                    .setExecution(req.getExecution())
                    .setXid(req.getXid())
                    .setError(errorState)
                    .setTransactionState(TransactionState.TX_ACTIVE)
                    .setServiceBuffer(ServiceBuffer.empty())
                    .build();

            CasualNWMessage<CasualServiceCallReplyMessage> replyMsg =
                    CasualNWMessageImpl.of(msg.getCorrelationId(), reply);
            writeMessage(ctx, replyMsg);
        }

        private String extractPayload(ServiceBuffer buffer) {
            if (buffer == null || buffer.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (byte[] part : buffer.getBytes()) {
                sb.append(new String(part, StandardCharsets.UTF_8));
            }
            return sb.toString();
        }

        private <T extends se.laz.casual.api.network.protocol.messages.CasualNetworkTransmittable> void writeMessage(
                ChannelHandlerContext ctx, CasualNWMessage<T> msg) {
            ByteBuf buf = ctx.alloc().buffer();
            CasualMessageEncoder.write(buf, msg);
            ctx.writeAndFlush(buf);
        }
    }
}
