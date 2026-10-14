package com.github.mattiasgreen.wiremock.casual;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.laz.casual.api.buffer.CasualBufferUtils;
import se.laz.casual.api.buffer.type.JsonBuffer;
import se.laz.casual.api.buffer.type.ServiceBuffer;
import se.laz.casual.api.flags.ErrorState;
import se.laz.casual.api.flags.TransactionState;
import se.laz.casual.api.network.protocol.messages.CasualNWMessage;
import se.laz.casual.api.network.protocol.messages.CasualNWMessageType;
import se.laz.casual.client.CasualTcpClient;
import se.laz.casual.network.ProtocolVersion;
import se.laz.casual.network.messages.domain.TransactionType;
import se.laz.casual.network.protocol.messages.domain.Service;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallReplyMessage;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallRequestMessage;
import se.laz.casual.server.CasualServer;

/**
 * Super-lightweight in-process Casual Domain Gateway server for WireMock.
 * Listens on a TCP port and bridges Casual XATMI protocol messages
 * to WireMock HTTP stubbing endpoints without requiring Jakarta EE.
 * <p>
 * Supports transparent proxy forwarding to an upstream Casual domain when
 * stubs are unmatched, and optional automatic stub recording for both successes and faults.
 */
public class CasualWireMockGateway implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(CasualWireMockGateway.class);

    private final int port;
    private final String wireMockBaseUrl;
    private final String upstreamHost;
    private final int upstreamPort;
    private final boolean recordStubs;
    private final boolean recordFaults;
    private final UUID domainId;
    private final String domainName;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private CasualServer server;
    private CasualTcpClient upstreamClient;

    public CasualWireMockGateway(int port, String wireMockBaseUrl) {
        this(port, wireMockBaseUrl, null, 0, false, true);
    }

    public CasualWireMockGateway(
            int port, String wireMockBaseUrl, String upstreamHost, int upstreamPort, boolean recordStubs) {
        this(port, wireMockBaseUrl, upstreamHost, upstreamPort, recordStubs, true);
    }

    public CasualWireMockGateway(
            int port,
            String wireMockBaseUrl,
            String upstreamHost,
            int upstreamPort,
            boolean recordStubs,
            boolean recordFaults) {
        this.port = port;
        this.wireMockBaseUrl = wireMockBaseUrl.replaceAll("/+$", "");
        this.upstreamHost = upstreamHost;
        this.upstreamPort = upstreamPort;
        this.recordStubs = recordStubs;
        this.recordFaults = recordFaults;
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
        private boolean recordFaults = true;

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

        public Builder recordFaults(boolean recordFaults) {
            this.recordFaults = recordFaults;
            return this;
        }

        public CasualWireMockGateway build() {
            if (wireMockBaseUrl == null || wireMockBaseUrl.isBlank()) {
                throw new IllegalStateException("wireMockBaseUrl must be specified");
            }
            return new CasualWireMockGateway(
                    port, wireMockBaseUrl, upstreamHost, upstreamPort, recordStubs, recordFaults);
        }
    }

    public synchronized void start() throws InterruptedException {
        server = CasualServer.builder()
                .port(port)
                .domainId(domainId)
                .domainName(domainName)
                .discoveryHandler(this::handleDiscovery)
                .serviceHandler(this::handleServiceCall)
                .build();
        server.start();
        LOG.info("CasualWireMockGateway listening on TCP port {}", server.getPort());
    }

    public synchronized int getPort() {
        return server != null ? server.getPort() : port;
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
        if (server != null) {
            server.close();
            server = null;
        }
        LOG.info("CasualWireMockGateway stopped");
    }

    private List<Service> handleDiscovery(List<String> serviceNames) {
        return serviceNames.stream()
                .map(name -> Service.of(name, "wiremock-category", TransactionType.AUTOMATIC))
                .toList();
    }

    private CasualServiceCallReplyMessage handleServiceCall(CasualServiceCallRequestMessage req) {
        String serviceName = req.getServiceName();
        ServiceBuffer requestBuffer = req.getServiceBuffer();

        String requestPayload = CasualBufferUtils.extractPayload(requestBuffer);
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

        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            LOG.error("Failed to dispatch Casual service call to WireMock", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return buildErrorReply(req, ErrorState.TPESVCERR);
        }

        // A request was matched if WireMock provided a Matched-Stub-Id header
        boolean wasMatched = response.headers().firstValue("Matched-Stub-Id").isPresent();

        if (wasMatched) {
            // Matched stub: serve response directly from WireMock
            return buildMatchedReply(req, response);
        } else if (hasUpstream()) {
            // Unmatched request: transparently forward to upstream Casual server
            return forwardToUpstream(req, serviceName, requestPayload);
        } else {
            // Unmatched request and no upstream configured: return TPENOENT
            return buildErrorReply(req, ErrorState.TPENOENT);
        }
    }

    private CasualServiceCallReplyMessage buildMatchedReply(
            CasualServiceCallRequestMessage req, HttpResponse<String> response) {
        int statusCode = response.statusCode();
        ErrorState errorState;
        ServiceBuffer replyBuffer = ServiceBuffer.empty();

        Optional<String> customErrorHeader = response.headers().firstValue("X-Casual-Error-State");
        if (customErrorHeader.isPresent()) {
            try {
                errorState = ErrorState.valueOf(customErrorHeader.get().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errorState = (statusCode >= 200 && statusCode < 300) ? ErrorState.OK : ErrorState.TPESVCFAIL;
            }
        } else if (statusCode >= 200 && statusCode < 300) {
            errorState = ErrorState.OK;
        } else if (statusCode == 404) {
            errorState = ErrorState.TPENOENT;
        } else {
            errorState = ErrorState.TPESVCFAIL;
        }

        String respBody = response.body();
        if (respBody != null && !respBody.isBlank()) {
            replyBuffer = ServiceBuffer.of(JsonBuffer.of(respBody));
        }

        return CasualServiceCallReplyMessage.createBuilder()
                .setProtocolVersion(ProtocolVersion.VERSION_1_0)
                .setExecution(req.getExecution())
                .setXid(req.getXid())
                .setError(errorState)
                .setTransactionState(TransactionState.TX_ACTIVE)
                .setServiceBuffer(replyBuffer)
                .build();
    }

    private CasualServiceCallReplyMessage forwardToUpstream(
            CasualServiceCallRequestMessage req, String serviceName, String requestPayload) {
        try {
            CasualTcpClient client = getUpstreamClient();
            CasualNWMessage<CasualServiceCallReplyMessage> upstreamReplyMsg =
                    client.sendAndReceive(CasualNWMessageType.SERVICE_CALL_REQUEST, req, 10, TimeUnit.SECONDS);
            CasualServiceCallReplyMessage upstreamReply = upstreamReplyMsg.getMessage();

            ErrorState errorState = upstreamReply.getError();
            boolean shouldRecord = recordStubs && (errorState == ErrorState.OK || recordFaults);

            if (shouldRecord) {
                String replyPayload = CasualBufferUtils.extractPayload(upstreamReply.getServiceBuffer());
                recordStubToWireMock(serviceName, requestPayload, replyPayload, errorState);
            }

            return upstreamReply;
        } catch (Exception e) {
            LOG.error("Failed to forward Casual service call to upstream", e);
            return buildErrorReply(req, ErrorState.TPESVCERR);
        }
    }

    private void recordStubToWireMock(
            String serviceName, String requestPayload, String replyPayload, ErrorState errorState) {
        try {
            Map<String, Object> mapping = new LinkedHashMap<>();
            Map<String, Object> requestMap = new LinkedHashMap<>();
            requestMap.put("method", "POST");
            requestMap.put("url", "/__casual/services/" + serviceName);

            if (requestPayload != null && !requestPayload.trim().isEmpty()) {
                requestMap.put("bodyPatterns", List.of(Map.of("equalToJson", requestPayload)));
            }
            mapping.put("request", requestMap);

            int statusCode = 200;
            if (errorState == ErrorState.TPENOENT) {
                statusCode = 404;
            } else if (errorState != ErrorState.OK) {
                statusCode = 500;
            }

            Map<String, String> responseHeaders = new LinkedHashMap<>();
            responseHeaders.put("Content-Type", "application/json");
            responseHeaders.put("X-Casual-Error-State", errorState.name());

            Map<String, Object> responseMap = new LinkedHashMap<>();
            responseMap.put("status", statusCode);
            responseMap.put("body", replyPayload != null ? replyPayload : "");
            responseMap.put("headers", responseHeaders);
            mapping.put("response", responseMap);

            String json = objectMapper.writeValueAsString(mapping);
            HttpRequest recordRequest = HttpRequest.newBuilder()
                    .uri(URI.create(wireMockBaseUrl + "/__admin/mappings"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> recordResponse =
                    httpClient.send(recordRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (recordResponse.statusCode() >= 200 && recordResponse.statusCode() < 300) {
                LOG.info("Recorded stub for Casual service to WireMock");
            } else {
                LOG.warn("Failed to record stub for Casual service to WireMock");
            }
        } catch (IOException | InterruptedException e) {
            LOG.warn("Failed to record stub for Casual service to WireMock", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private CasualServiceCallReplyMessage buildErrorReply(CasualServiceCallRequestMessage req, ErrorState errorState) {
        return CasualServiceCallReplyMessage.createBuilder()
                .setProtocolVersion(ProtocolVersion.VERSION_1_0)
                .setExecution(req.getExecution())
                .setXid(req.getXid())
                .setError(errorState)
                .setTransactionState(TransactionState.TX_ACTIVE)
                .setServiceBuffer(ServiceBuffer.empty())
                .build();
    }
}
