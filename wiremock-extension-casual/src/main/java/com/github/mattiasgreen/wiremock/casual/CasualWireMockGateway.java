package com.github.mattiasgreen.wiremock.casual;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import javax.transaction.xa.Xid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.laz.casual.api.buffer.CasualBufferUtils;
import se.laz.casual.api.buffer.type.JsonBuffer;
import se.laz.casual.api.buffer.type.ServiceBuffer;
import se.laz.casual.api.flags.AtmiFlags;
import se.laz.casual.api.flags.ErrorState;
import se.laz.casual.api.flags.Flag;
import se.laz.casual.api.flags.TransactionState;
import se.laz.casual.api.network.protocol.messages.CasualNWMessage;
import se.laz.casual.api.network.protocol.messages.CasualNWMessageType;
import se.laz.casual.api.xa.XAReturnCode;
import se.laz.casual.api.xa.XID;
import se.laz.casual.client.CasualTcpClient;
import se.laz.casual.network.ProtocolVersion;
import se.laz.casual.network.messages.domain.TransactionType;
import se.laz.casual.network.protocol.messages.domain.CasualDomainDiscoveryReplyMessage;
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
 * Super-lightweight in-process Casual Domain Gateway server for WireMock.
 * Listens on a TCP port and bridges Casual XATMI protocol messages
 * to WireMock HTTP stubbing endpoints without requiring Jakarta EE.
 * <p>
 * Supports transparent proxy forwarding to an upstream Casual domain when
 * stubs are unmatched, upstream domain discovery aggregation, Base64 non-JSON
 * buffer handling, fail-fast mechanics, and 2PC/XA transaction pass-through.
 */
public class CasualWireMockGateway implements AutoCloseable, CasualTransactionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(CasualWireMockGateway.class);

    private final int port;
    private final String wireMockBaseUrl;
    private volatile String upstreamHost;
    private volatile int upstreamPort;
    private volatile boolean upstreamEnabled;
    private volatile boolean recordStubs;
    private volatile boolean recordFaults;
    private volatile boolean recordNotFound;
    private final int upstreamTimeoutSeconds;
    private final UUID domainId;
    private final String domainName;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final Map<String, CasualServiceCallRequestMessage> inFlightRequests = new ConcurrentHashMap<>();
    private final Set<Xid> upstreamEnlistedXids = ConcurrentHashMap.newKeySet();
    private final Set<Xid> localMatchedXids = ConcurrentHashMap.newKeySet();
    private final Map<Xid, XAReturnCode> prepareVoteOverrides = new ConcurrentHashMap<>();
    private final Map<Xid, XAReturnCode> commitVoteOverrides = new ConcurrentHashMap<>();
    private final Map<Xid, XAReturnCode> rollbackVoteOverrides = new ConcurrentHashMap<>();

    private CasualServer server;
    private CasualTcpClient upstreamClient;

    public CasualWireMockGateway(int port, String wireMockBaseUrl) {
        this(port, wireMockBaseUrl, null, 0, false, true, false);
    }

    public CasualWireMockGateway(
            int port, String wireMockBaseUrl, String upstreamHost, int upstreamPort, boolean recordStubs) {
        this(port, wireMockBaseUrl, upstreamHost, upstreamPort, recordStubs, true, false);
    }

    public CasualWireMockGateway(
            int port,
            String wireMockBaseUrl,
            String upstreamHost,
            int upstreamPort,
            boolean recordStubs,
            boolean recordFaults) {
        this(port, wireMockBaseUrl, upstreamHost, upstreamPort, recordStubs, recordFaults, false);
    }

    public CasualWireMockGateway(
            int port,
            String wireMockBaseUrl,
            String upstreamHost,
            int upstreamPort,
            boolean recordStubs,
            boolean recordFaults,
            boolean recordNotFound) {
        this(
                port,
                wireMockBaseUrl,
                upstreamHost,
                upstreamPort,
                recordStubs,
                recordFaults,
                recordNotFound,
                resolveDefaultTimeout());
    }

    public CasualWireMockGateway(
            int port,
            String wireMockBaseUrl,
            String upstreamHost,
            int upstreamPort,
            boolean recordStubs,
            boolean recordFaults,
            boolean recordNotFound,
            int upstreamTimeoutSeconds) {
        this.port = port;
        this.wireMockBaseUrl = wireMockBaseUrl.replaceAll("/+$", "");
        this.upstreamHost = upstreamHost;
        this.upstreamPort = upstreamPort;
        this.upstreamEnabled = upstreamHost != null && !upstreamHost.isBlank() && upstreamPort > 0;
        this.recordStubs = recordStubs;
        this.recordFaults = recordFaults;
        this.recordNotFound = recordNotFound;
        this.upstreamTimeoutSeconds = upstreamTimeoutSeconds > 0 ? upstreamTimeoutSeconds : resolveDefaultTimeout();
        this.domainId = UUID.randomUUID();
        this.domainName = "wiremock-casual-domain";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(this.upstreamTimeoutSeconds))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    private static int resolveDefaultTimeout() {
        return Integer.getInteger(CasualTcpClient.TIMEOUT_PROPERTY, CasualTcpClient.DEFAULT_TIMEOUT_SECONDS);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int port = 0;
        private String wireMockBaseUrl;
        private String upstreamHost;
        private int upstreamPort = 0;
        private boolean upstreamEnabled = true;
        private boolean recordStubs = false;
        private boolean recordFaults = true;
        private boolean recordNotFound = false;
        private int upstreamTimeoutSeconds = 0;
        private CasualProxyTransformer proxyTransformer;

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

        public Builder upstreamEnabled(boolean upstreamEnabled) {
            this.upstreamEnabled = upstreamEnabled;
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

        public Builder recordNotFound(boolean recordNotFound) {
            this.recordNotFound = recordNotFound;
            return this;
        }

        public Builder upstreamTimeoutSeconds(int seconds) {
            this.upstreamTimeoutSeconds = seconds;
            return this;
        }

        public Builder proxyTransformer(CasualProxyTransformer proxyTransformer) {
            this.proxyTransformer = proxyTransformer;
            return this;
        }

        public CasualWireMockGateway build() {
            if (wireMockBaseUrl == null || wireMockBaseUrl.isBlank()) {
                throw new IllegalStateException("wireMockBaseUrl must be specified");
            }
            CasualWireMockGateway gateway = new CasualWireMockGateway(
                    port,
                    wireMockBaseUrl,
                    upstreamHost,
                    upstreamPort,
                    recordStubs,
                    recordFaults,
                    recordNotFound,
                    upstreamTimeoutSeconds);
            gateway.setUpstreamEnabled(
                    upstreamEnabled && upstreamHost != null && !upstreamHost.isBlank() && upstreamPort > 0);
            if (proxyTransformer != null) {
                proxyTransformer.setGateway(gateway);
            }
            return gateway;
        }
    }

    public synchronized void start() throws InterruptedException {
        server = CasualServer.builder()
                .port(port)
                .domainId(domainId)
                .domainName(domainName)
                .discoveryHandler(this::handleDiscovery)
                .serviceHandler(this::handleServiceCall)
                .transactionHandler(this)
                .build();
        server.start();
        LOG.info("CasualWireMockGateway listening on TCP port {}", server.getPort());
    }

    public synchronized int getPort() {
        return server != null ? server.getPort() : port;
    }

    public synchronized boolean isRunning() {
        return server != null && server.isRunning();
    }

    public boolean hasUpstream() {
        return upstreamEnabled && upstreamHost != null && !upstreamHost.isBlank() && upstreamPort > 0;
    }

    public synchronized void setUpstream(String host, int port, boolean enabled) {
        this.upstreamHost = host;
        this.upstreamPort = port;
        this.upstreamEnabled = enabled;
        if (upstreamClient != null) {
            try {
                upstreamClient.close();
            } catch (Exception e) {
                LOG.debug("Error closing existing upstream client during reconnect", e);
            }
            upstreamClient = null;
        }
    }

    public void setUpstreamEnabled(boolean enabled) {
        this.upstreamEnabled = enabled;
    }

    public boolean isUpstreamEnabled() {
        return upstreamEnabled;
    }

    public String getUpstreamHost() {
        return upstreamHost;
    }

    public int getUpstreamPort() {
        return upstreamPort;
    }

    public boolean isRecordStubs() {
        return recordStubs;
    }

    public void setRecordStubs(boolean recordStubs) {
        this.recordStubs = recordStubs;
    }

    public boolean isRecordFaults() {
        return recordFaults;
    }

    public void setRecordFaults(boolean recordFaults) {
        this.recordFaults = recordFaults;
    }

    public boolean isRecordNotFound() {
        return recordNotFound;
    }

    public void setRecordNotFound(boolean recordNotFound) {
        this.recordNotFound = recordNotFound;
    }

    public int getUpstreamTimeoutSeconds() {
        return upstreamTimeoutSeconds;
    }

    private synchronized CasualTcpClient getUpstreamClient() throws Exception {
        if (upstreamClient == null || !upstreamClient.isConnected()) {
            if (upstreamClient != null) {
                upstreamClient.close();
            }
            upstreamClient =
                    new CasualTcpClient(upstreamHost, upstreamPort, domainId, domainName, upstreamTimeoutSeconds);
            upstreamClient.connect();
        }
        return upstreamClient;
    }

    @Override
    public synchronized void close() {
        if (upstreamClient != null) {
            try {
                upstreamClient.close();
            } catch (Exception e) {
                LOG.warn("Failed to cleanly close upstream client", e);
            }
            upstreamClient = null;
        }
        if (server != null) {
            server.close();
            server = null;
        }
        LOG.info("CasualWireMockGateway stopped");
    }

    private List<Service> handleDiscovery(List<String> requestedNames) {
        Map<String, Service> aggregated = new LinkedHashMap<>();

        // 1. If upstream is configured, query upstream domain catalog
        if (hasUpstream()) {
            try {
                CasualTcpClient client = getUpstreamClient();
                List<String> namesToQuery = requestedNames != null ? requestedNames : Collections.emptyList();
                CasualDomainDiscoveryReplyMessage upstreamReply =
                        client.discoverServices(namesToQuery, upstreamTimeoutSeconds, TimeUnit.SECONDS);

                if (upstreamReply != null && upstreamReply.getServices() != null) {
                    for (Service s : upstreamReply.getServices()) {
                        aggregated.put(s.getName(), s);
                    }
                    LOG.debug(
                            "[CASUAL-GATEWAY-DISCOVERY] Discovered {} service(s) from upstream Casual domain",
                            upstreamReply.getServices().size());
                }
            } catch (Exception e) {
                LOG.warn(
                        "[CASUAL-GATEWAY-DISCOVERY] Failed to query upstream for domain discovery: {}. "
                                + "Proceeding with local stubs. If this was a timeout, increase timeout using -D{}=<seconds>.",
                        e.getMessage(),
                        CasualTcpClient.TIMEOUT_PROPERTY);
            }
        }

        // 2. Discover local stubs from WireMock mappings
        Set<String> localStubNames = queryLocalWireMockServices();
        for (String stubName : localStubNames) {
            if (!aggregated.containsKey(stubName)) {
                aggregated.put(stubName, Service.of(stubName, "wiremock-stub", TransactionType.AUTOMATIC));
            }
        }

        // 3. If explicit names were queried, ensure they are registered so callers aren't blocked
        if (requestedNames != null && !requestedNames.isEmpty()) {
            for (String name : requestedNames) {
                if (!aggregated.containsKey(name)) {
                    aggregated.put(name, Service.of(name, "wiremock-category", TransactionType.AUTOMATIC));
                }
            }
        }

        LOG.debug("[CASUAL-GATEWAY-DISCOVERY] Returning {} discovered service(s) to client", aggregated.size());
        return new ArrayList<>(aggregated.values());
    }

    private Set<String> queryLocalWireMockServices() {
        Set<String> services = new LinkedHashSet<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(wireMockBaseUrl + "/__admin/mappings"))
                    .GET()
                    .timeout(Duration.ofSeconds(2))
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                Map<?, ?> root = objectMapper.readValue(response.body(), Map.class);
                Object mappingsObj = root.get("mappings");
                if (mappingsObj instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> stubMap && stubMap.get("request") instanceof Map<?, ?> reqMap) {
                            String url = (String) reqMap.get("url");
                            String urlPattern = (String) reqMap.get("urlPattern");
                            String urlPath = (String) reqMap.get("urlPath");
                            String urlPathTemplate = (String) reqMap.get("urlPathTemplate");
                            String rawUrl = url != null
                                    ? url
                                    : (urlPath != null
                                            ? urlPath
                                            : (urlPathTemplate != null ? urlPathTemplate : urlPattern));
                            if (rawUrl != null && rawUrl.startsWith("/__casual/services/")) {
                                String svc = rawUrl.substring("/__casual/services/".length());
                                if (!svc.isBlank() && !svc.contains("*") && !svc.contains(".")) {
                                    services.add(svc);
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException | InterruptedException e) {
            LOG.debug("Could not query WireMock mappings for discovery: {}", e.getMessage());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return services;
    }

    CasualServiceCallReplyMessage handleServiceCall(CasualServiceCallRequestMessage req) {
        String serviceName = req.getServiceName();
        ServiceBuffer requestBuffer = req.getServiceBuffer();
        String bufferType = (requestBuffer != null && !requestBuffer.isEmpty()) ? requestBuffer.getType() : "JSON";
        boolean isJson = CasualBufferUtils.isJsonType(bufferType);
        byte[] rawBytes = CasualBufferUtils.toByteArray(requestBuffer);

        if (LOG.isDebugEnabled()) {
            LOG.debug(
                    "[CASUAL-GATEWAY-INBOUND] Service [{}] bufferType [{}] size [{}] bytes, xid [{}] execution [{}]",
                    serviceName,
                    bufferType,
                    rawBytes.length,
                    req.getXid(),
                    req.getExecution());
        }
        if (LOG.isTraceEnabled()) {
            LOG.trace(
                    "[CASUAL-GATEWAY-HEX] Service [{}] payload hex:\n{}",
                    serviceName,
                    CasualBufferUtils.toHexDump(rawBytes, 128));
        }

        String requestPayload = CasualBufferUtils.extractPayload(requestBuffer);
        String executionId = (req.getExecution() != null ? req.getExecution() : UUID.randomUUID()).toString();

        // Bridge to WireMock HTTP endpoint
        String wireMockUrl = wireMockBaseUrl + "/__casual/services/" + serviceName;
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(wireMockUrl))
                .header("Content-Type", isJson ? "application/json" : "application/octet-stream")
                .header("X-Casual-Service", serviceName)
                .header("X-Casual-Buffer-Type", bufferType)
                .header("X-Casual-Execution", executionId);

        if (isTransactional(req.getXid())) {
            requestBuilder.header("X-Casual-Xid", req.getXid().toString());
        }

        HttpRequest httpRequest = requestBuilder
                .POST(HttpRequest.BodyPublishers.ofString(requestPayload, StandardCharsets.UTF_8))
                .build();

        inFlightRequests.put(executionId, req);
        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            LOG.error("[CASUAL-GATEWAY-ERROR] Failed to dispatch Casual service call [{}] to WireMock", serviceName, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return buildErrorReply(req, ErrorState.TPESVCERR);
        } finally {
            inFlightRequests.remove(executionId);
        }

        boolean wasMatched = response.headers().firstValue("Matched-Stub-Id").isPresent();

        if (wasMatched) {
            if (LOG.isDebugEnabled()) {
                LOG.debug(
                        "[CASUAL-GATEWAY-MATCH] Service [{}] matched WireMock stub [{}] status [{}]",
                        serviceName,
                        response.headers().firstValue("Matched-Stub-Id").orElse("unknown"),
                        response.statusCode());
            }
            if (isTransactional(req.getXid()) && !upstreamEnlistedXids.contains(req.getXid())) {
                localMatchedXids.add(req.getXid());
            }
            return buildMatchedReply(req, response);
        } else if (hasUpstream()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug(
                        "[CASUAL-GATEWAY-UNMATCHED] Service [{}] unmatched in WireMock. Forwarding to upstream {}:{}",
                        serviceName,
                        upstreamHost,
                        upstreamPort);
            }
            return forwardToUpstream(req, serviceName, requestPayload);
        } else if (!isJson) {
            LOG.error(
                    "[CASUAL-GATEWAY-REJECT] Service [{}] received non-JSON buffer [{}] ({} bytes) with no matching stub and no upstream proxy. "
                            + "Failing fast with ErrorState.TPEITYPE.",
                    serviceName,
                    bufferType,
                    rawBytes.length);
            return buildErrorReply(req, ErrorState.TPEITYPE);
        } else {
            LOG.error(
                    "[CASUAL-GATEWAY-NOTFOUND] Service [{}] unmatched in WireMock and no upstream configured. Returning TPENOENT.",
                    serviceName);
            return buildErrorReply(req, ErrorState.TPENOENT);
        }
    }

    /**
     * Invoked in-process by {@link CasualProxyTransformer} when WireMock matches an active
     * Casual proxy stub ({@code metadata.mode = "proxy"}). Forwards the request to the upstream
     * Casual TCP server and returns the upstream reply as a WireMock {@link ResponseDefinition}
     * so it is logged as {@code PROXIED} in the WireMock Request Journal.
     */
    ResponseDefinition proxyServeEventToUpstream(ServeEvent serveEvent) {
        String executionId = serveEvent.getRequest().getHeader("X-Casual-Execution");
        CasualServiceCallRequestMessage req = executionId != null ? inFlightRequests.get(executionId) : null;

        String url = serveEvent.getRequest().getUrl();
        String serviceName = serveEvent.getRequest().getHeader("X-Casual-Service");
        if (serviceName == null || serviceName.isBlank()) {
            String prefix = "/__casual/services/";
            serviceName = url != null && url.startsWith(prefix) ? url.substring(prefix.length()) : "casual/default";
        }
        String requestPayload = serveEvent.getRequest().getBodyAsString();
        if (requestPayload == null) {
            requestPayload = "";
        }

        if (req == null) {
            req = CasualServiceCallRequestMessage.createBuilder()
                    .setProtocolVersion(ProtocolVersion.VERSION_1_0)
                    .setExecution(UUID.randomUUID())
                    .setServiceName(serviceName)
                    .setTimeout(10000L)
                    .setParentName(domainName)
                    .setXid(XID.NULL_XID)
                    .setXatmiFlags(Flag.of(AtmiFlags.NOFLAG))
                    .setServiceBuffer(ServiceBuffer.of(JsonBuffer.of(requestPayload)))
                    .build();
        }

        CasualServiceCallReplyMessage upstreamReply = forwardToUpstream(req, serviceName, requestPayload);
        ErrorState errorState = upstreamReply.getError();
        int statusCode = 200;
        if (errorState == ErrorState.TPENOENT) {
            statusCode = 404;
        } else if (errorState == ErrorState.TPEINVAL) {
            statusCode = 400;
        } else if (errorState == ErrorState.TPETIME) {
            statusCode = 504;
        } else if (errorState == ErrorState.TPESVCERR) {
            statusCode = 502;
        } else if (errorState != ErrorState.OK) {
            statusCode = 500;
        }

        String replyPayload = CasualBufferUtils.extractPayload(upstreamReply.getServiceBuffer());
        ResponseDefinitionBuilder builder = ResponseDefinitionBuilder.responseDefinition()
                .withStatus(statusCode)
                .withHeader("Content-Type", "application/json")
                .withHeader("X-Casual-Error-State", errorState.name())
                .withHeader(
                        "X-Casual-Transaction-State",
                        upstreamReply.getTransactionState().name())
                .withBody(replyPayload);

        if (upstreamReply.getUserDefinedCode() != 0) {
            builder.withHeader("X-Casual-User-Supplied-Error", String.valueOf(upstreamReply.getUserDefinedCode()));
        }

        return builder.build();
    }

    private CasualServiceCallReplyMessage buildMatchedReply(
            CasualServiceCallRequestMessage req, HttpResponse<String> response) {
        int statusCode = response.statusCode();
        ErrorState errorState;
        long userSuppliedError = 0;
        TransactionState transactionState = TransactionState.TX_ACTIVE;
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
        } else if (statusCode == 400) {
            errorState = ErrorState.TPEINVAL;
        } else if (statusCode == 404) {
            errorState = ErrorState.TPENOENT;
        } else if (statusCode == 408 || statusCode == 504) {
            errorState = ErrorState.TPETIME;
        } else if (statusCode == 429) {
            errorState = ErrorState.TPELIMIT;
        } else if (statusCode == 502 || statusCode == 503) {
            errorState = ErrorState.TPESVCERR;
        } else {
            errorState = ErrorState.TPESVCFAIL;
        }

        Optional<String> userErrorHeader = response.headers()
                .firstValue("X-Casual-User-Supplied-Error")
                .or(() -> response.headers().firstValue("X-Casual-User-Code"))
                .or(() -> response.headers().firstValue("X-Casual-User-Defined-Code"));
        if (userErrorHeader.isPresent()) {
            try {
                userSuppliedError = Long.parseLong(userErrorHeader.get().trim());
            } catch (NumberFormatException ignored) {
            }
        }

        Optional<String> txStateHeader = response.headers().firstValue("X-Casual-Transaction-State");
        if (txStateHeader.isPresent()) {
            try {
                transactionState =
                        TransactionState.valueOf(txStateHeader.get().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
            }
        } else if (errorState != ErrorState.OK) {
            transactionState = TransactionState.ROLLBACK_ONLY;
        }

        if (isTransactional(req.getXid())) {
            recordVoteOverride(response, "X-Casual-XA-Prepare-Vote", req.getXid(), prepareVoteOverrides);
            recordVoteOverride(response, "X-Casual-XA-Commit-Vote", req.getXid(), commitVoteOverrides);
            recordVoteOverride(response, "X-Casual-XA-Rollback-Vote", req.getXid(), rollbackVoteOverrides);
        }

        String respBody = response.body();
        Optional<String> bufferTypeHeader = response.headers().firstValue("X-Casual-Buffer-Type");
        String bufferType = bufferTypeHeader.orElse("JSON");
        if (respBody != null && !respBody.isBlank()) {
            replyBuffer = CasualBufferUtils.wrapPayload(bufferType, respBody);
        }

        return CasualServiceCallReplyMessage.createBuilder()
                .setProtocolVersion(ProtocolVersion.VERSION_1_0)
                .setExecution(req.getExecution())
                .setXid(req.getXid())
                .setError(errorState)
                .setUserSuppliedError(userSuppliedError)
                .setTransactionState(transactionState)
                .setServiceBuffer(replyBuffer)
                .build();
    }

    private void recordVoteOverride(
            HttpResponse<String> response, String headerName, Xid xid, Map<Xid, XAReturnCode> targetMap) {
        Optional<String> voteHeader = response.headers().firstValue(headerName);
        if (voteHeader.isPresent()) {
            try {
                XAReturnCode vote = XAReturnCode.valueOf(voteHeader.get().trim().toUpperCase(Locale.ROOT));
                targetMap.put(xid, vote);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private CasualServiceCallReplyMessage forwardToUpstream(
            CasualServiceCallRequestMessage req, String serviceName, String requestPayload) {
        try {
            if (isTransactional(req.getXid())) {
                upstreamEnlistedXids.add(req.getXid());
            }
            CasualTcpClient client = getUpstreamClient();
            CasualNWMessage<CasualServiceCallReplyMessage> upstreamReplyMsg = client.sendAndReceive(
                    CasualNWMessageType.SERVICE_CALL_REQUEST, req, upstreamTimeoutSeconds, TimeUnit.SECONDS);
            CasualServiceCallReplyMessage upstreamReply = upstreamReplyMsg.getMessage();

            ErrorState errorState = upstreamReply.getError();
            boolean isRecordable = (errorState == ErrorState.OK)
                    || (recordFaults && errorState == ErrorState.TPESVCFAIL)
                    || (recordNotFound && errorState == ErrorState.TPENOENT);

            if (recordStubs && isRecordable) {
                String replyPayload = CasualBufferUtils.extractPayload(upstreamReply.getServiceBuffer());
                recordStubToWireMock(serviceName, requestPayload, replyPayload, req, upstreamReply);
            }

            return upstreamReply;
        } catch (Exception e) {
            LOG.error(
                    "[CASUAL-GATEWAY-UPSTREAM-FAIL] Failed to forward Casual service call [{}] to upstream {}:{}: {}",
                    serviceName,
                    upstreamHost,
                    upstreamPort,
                    e.getMessage(),
                    e);
            return buildErrorReply(req, ErrorState.TPESVCERR);
        }
    }

    private void recordStubToWireMock(
            String serviceName,
            String requestPayload,
            String replyPayload,
            CasualServiceCallRequestMessage req,
            CasualServiceCallReplyMessage upstreamReply) {
        try {
            String reqType =
                    (req.getServiceBuffer() != null && !req.getServiceBuffer().isEmpty())
                            ? req.getServiceBuffer().getType()
                            : "JSON";
            boolean isReqJson = CasualBufferUtils.isJsonType(reqType);

            String replyType = (upstreamReply.getServiceBuffer() != null
                            && !upstreamReply.getServiceBuffer().isEmpty())
                    ? upstreamReply.getServiceBuffer().getType()
                    : "JSON";
            boolean isReplyJson = CasualBufferUtils.isJsonType(replyType);

            ErrorState errorState = upstreamReply.getError();
            Map<String, Object> mapping = new LinkedHashMap<>();
            Map<String, Object> requestMap = new LinkedHashMap<>();
            requestMap.put("method", "POST");
            requestMap.put("url", "/__casual/services/" + serviceName);

            if (requestPayload != null && !requestPayload.trim().isEmpty()) {
                if (isReqJson) {
                    requestMap.put("bodyPatterns", List.of(Map.of("equalToJson", requestPayload)));
                } else {
                    requestMap.put("bodyPatterns", List.of(Map.of("equalTo", requestPayload)));
                }
            }
            Map<String, Object> reqHeaders = new LinkedHashMap<>();
            reqHeaders.put("X-Casual-Buffer-Type", Map.of("equalTo", reqType));
            requestMap.put("headers", reqHeaders);
            mapping.put("request", requestMap);

            int statusCode = 200;
            if (errorState == ErrorState.TPENOENT) {
                statusCode = 404;
            } else if (errorState == ErrorState.TPEINVAL) {
                statusCode = 400;
            } else if (errorState == ErrorState.TPETIME) {
                statusCode = 504;
            } else if (errorState != ErrorState.OK) {
                statusCode = 500;
            }

            Map<String, String> responseHeaders = new LinkedHashMap<>();
            responseHeaders.put("Content-Type", isReplyJson ? "application/json" : "application/octet-stream");
            responseHeaders.put("X-Casual-Buffer-Type", replyType);
            responseHeaders.put("X-Casual-Error-State", errorState.name());
            if (upstreamReply.getUserDefinedCode() != 0) {
                responseHeaders.put("X-Casual-User-Supplied-Error", String.valueOf(upstreamReply.getUserDefinedCode()));
            }
            if (upstreamReply.getTransactionState() != TransactionState.TX_ACTIVE) {
                responseHeaders.put(
                        "X-Casual-Transaction-State",
                        upstreamReply.getTransactionState().name());
            }

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
                LOG.debug(
                        "[CASUAL-GATEWAY-RECORD] Successfully recorded stub for [{}] (bufferType={})",
                        serviceName,
                        replyType);
            } else {
                LOG.warn(
                        "[CASUAL-GATEWAY-RECORD] Failed to record stub for [{}] - HTTP status {}",
                        serviceName,
                        recordResponse.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            LOG.warn("[CASUAL-GATEWAY-RECORD] Exception recording stub for [{}]", serviceName, e);
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

    private boolean isTransactional(Xid xid) {
        return xid != null && !XID.NULL_XID.equals(xid);
    }

    private boolean shouldForwardTransactionToUpstream(Xid xid) {
        if (!hasUpstream()) {
            return false;
        }
        return upstreamEnlistedXids.contains(xid) || !localMatchedXids.contains(xid);
    }

    private void cleanupTransactionState(Xid xid) {
        if (xid != null) {
            upstreamEnlistedXids.remove(xid);
            localMatchedXids.remove(xid);
            prepareVoteOverrides.remove(xid);
            commitVoteOverrides.remove(xid);
            rollbackVoteOverrides.remove(xid);
        }
    }

    @Override
    public CasualTransactionResourcePrepareReplyMessage prepare(CasualTransactionResourcePrepareRequestMessage req) {
        Xid xid = req.getXid();
        XAReturnCode override = (xid != null) ? prepareVoteOverrides.remove(xid) : null;
        if (override != null) {
            if (override != XAReturnCode.XA_OK && override != XAReturnCode.XA_RDONLY) {
                cleanupTransactionState(xid);
            }
            return CasualTransactionResourcePrepareReplyMessage.of(
                    req.getExecution(), xid, req.getResourceId(), override);
        }

        if (shouldForwardTransactionToUpstream(xid)) {
            try {
                CasualTcpClient client = getUpstreamClient();
                CasualNWMessage<CasualTransactionResourcePrepareReplyMessage> reply = client.sendAndReceive(
                        CasualNWMessageType.PREPARE_REQUEST, req, upstreamTimeoutSeconds, TimeUnit.SECONDS);
                return reply.getMessage();
            } catch (Exception e) {
                LOG.error("[CASUAL-GATEWAY-2PC] Failed to forward Casual 2PC prepare request to upstream", e);
                return CasualTransactionResourcePrepareReplyMessage.of(
                        req.getExecution(), xid, req.getResourceId(), XAReturnCode.XAER_RMERR);
            }
        }

        return CasualTransactionResourcePrepareReplyMessage.of(
                req.getExecution(), xid, req.getResourceId(), XAReturnCode.XA_OK);
    }

    @Override
    public CasualTransactionResourceCommitReplyMessage commit(CasualTransactionResourceCommitRequestMessage req) {
        Xid xid = req.getXid();
        boolean shouldForward = shouldForwardTransactionToUpstream(xid);
        XAReturnCode override = (xid != null) ? commitVoteOverrides.remove(xid) : null;
        cleanupTransactionState(xid);

        if (override != null) {
            return CasualTransactionResourceCommitReplyMessage.of(
                    req.getExecution(), xid, req.getResourceId(), override);
        }

        if (shouldForward) {
            try {
                CasualTcpClient client = getUpstreamClient();
                CasualNWMessage<CasualTransactionResourceCommitReplyMessage> reply = client.sendAndReceive(
                        CasualNWMessageType.COMMIT_REQUEST, req, upstreamTimeoutSeconds, TimeUnit.SECONDS);
                return reply.getMessage();
            } catch (Exception e) {
                LOG.error("[CASUAL-GATEWAY-2PC] Failed to forward Casual 2PC commit request to upstream", e);
                return CasualTransactionResourceCommitReplyMessage.of(
                        req.getExecution(), xid, req.getResourceId(), XAReturnCode.XAER_RMERR);
            }
        }

        return CasualTransactionResourceCommitReplyMessage.of(
                req.getExecution(), xid, req.getResourceId(), XAReturnCode.XA_OK);
    }

    @Override
    public CasualTransactionResourceRollbackReplyMessage rollback(CasualTransactionResourceRollbackRequestMessage req) {
        Xid xid = req.getXid();
        boolean shouldForward = shouldForwardTransactionToUpstream(xid);
        XAReturnCode override = (xid != null) ? rollbackVoteOverrides.remove(xid) : null;
        cleanupTransactionState(xid);

        if (override != null) {
            return CasualTransactionResourceRollbackReplyMessage.of(
                    req.getExecution(), xid, req.getResourceId(), override);
        }

        if (shouldForward) {
            try {
                CasualTcpClient client = getUpstreamClient();
                CasualNWMessage<CasualTransactionResourceRollbackReplyMessage> reply = client.sendAndReceive(
                        CasualNWMessageType.REQUEST_ROLLBACK, req, upstreamTimeoutSeconds, TimeUnit.SECONDS);
                return reply.getMessage();
            } catch (Exception e) {
                LOG.error("[CASUAL-GATEWAY-2PC] Failed to forward Casual 2PC rollback request to upstream", e);
                return CasualTransactionResourceRollbackReplyMessage.of(
                        req.getExecution(), xid, req.getResourceId(), XAReturnCode.XAER_RMERR);
            }
        }

        return CasualTransactionResourceRollbackReplyMessage.of(
                req.getExecution(), xid, req.getResourceId(), XAReturnCode.XA_OK);
    }

    public int getPrepareVoteOverridesSize() {
        return prepareVoteOverrides.size();
    }

    public int getCommitVoteOverridesSize() {
        return commitVoteOverrides.size();
    }

    public int getRollbackVoteOverridesSize() {
        return rollbackVoteOverrides.size();
    }
}
