package com.github.mattiasgreen.wiremock.casual;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.admin.model.ListStubMappingsResult;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.DataTruncationSettings;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.ImmutableRequest;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.Response;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.verification.RequestJournal;
import java.io.IOException;
import java.lang.reflect.Field;
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
import se.laz.casual.api.buffer.type.ServiceBuffer;
import se.laz.casual.api.flags.ErrorState;
import se.laz.casual.api.flags.TransactionState;
import se.laz.casual.api.network.protocol.messages.CasualNWMessage;
import se.laz.casual.api.network.protocol.messages.CasualNWMessageType;
import se.laz.casual.api.network.protocol.messages.exception.CasualProtocolException;
import se.laz.casual.api.xa.XAReturnCode;
import se.laz.casual.api.xa.XID;
import se.laz.casual.client.CasualTcpClient;
import se.laz.casual.jca.SpanId;
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
 * to WireMock stub definitions without requiring Jakarta EE.
 * <p>
 * Features:
 * <ul>
 *   <li>In-memory WireMock stub probing evaluating priorities, URLs, and body matchers</li>
 *   <li>Stub-driven dynamic upstream proxying to {@code casual://host:port}</li>
 *   <li>Pure Casual-to-Casual socket forwarding with zero HTTP loopback overhead on proxy calls</li>
 *   <li>Multi-upstream 2PC / XA transaction stickiness across distributed branches</li>
 *   <li>Stub-driven domain discovery query routing</li>
 *   <li>Human-readable buffer extraction and WireMock Request Journal integration</li>
 * </ul>
 */
public class CasualWireMockGateway implements AutoCloseable, CasualTransactionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(CasualWireMockGateway.class);

    private final int port;
    private final String wireMockBaseUrl;
    private final WireMockServer wireMockServer;
    private final int upstreamTimeoutSeconds;
    private final UUID domainId;
    private final String domainName;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    // Upstream client connection pool keyed by "host:port"
    private final ConcurrentHashMap<String, CasualTcpClient> upstreamClients = new ConcurrentHashMap<>();

    // Multi-upstream 2PC stickiness mapping Xid -> Set<CasualTcpClient>
    // In the Casual wire protocol, PREPARE_REQUEST, COMMIT_REQUEST, and REQUEST_ROLLBACK
    // frames only carry (execution, xid, resourceId) - they DO NOT carry a serviceName.
    // Therefore, 2PC frames cannot be routed by probing WireMock stubs when they arrive.
    // We maintain Xid stickiness to the CasualTcpClient(s) enlisted during tpcall,
    // enabling proper 2PC fan-out across one or multiple upstream clusters.
    private final ConcurrentHashMap<Xid, Set<CasualTcpClient>> enlistedXidClients = new ConcurrentHashMap<>();

    private final Set<Xid> localMatchedXids = ConcurrentHashMap.newKeySet();
    private final Map<Xid, XAReturnCode> prepareVoteOverrides = new ConcurrentHashMap<>();
    private final Map<Xid, XAReturnCode> commitVoteOverrides = new ConcurrentHashMap<>();
    private final Map<Xid, XAReturnCode> rollbackVoteOverrides = new ConcurrentHashMap<>();

    private CasualServer server;

    public CasualWireMockGateway(int port, String wireMockBaseUrl) {
        this(port, wireMockBaseUrl, null, resolveDefaultTimeout());
    }

    public CasualWireMockGateway(int port, WireMockServer wireMockServer) {
        this(port, wireMockServer != null ? wireMockServer.baseUrl() : null, wireMockServer, resolveDefaultTimeout());
    }

    public CasualWireMockGateway(
            int port, String wireMockBaseUrl, WireMockServer wireMockServer, int upstreamTimeoutSeconds) {
        this.port = port;
        this.wireMockBaseUrl = wireMockBaseUrl != null ? wireMockBaseUrl.replaceAll("/+$", "") : "";
        this.wireMockServer = wireMockServer;
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
        private WireMockServer wireMockServer;
        private int upstreamTimeoutSeconds = 0;

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder wireMockBaseUrl(String wireMockBaseUrl) {
            this.wireMockBaseUrl = wireMockBaseUrl;
            return this;
        }

        public Builder wireMockServer(WireMockServer wireMockServer) {
            this.wireMockServer = wireMockServer;
            if (wireMockServer != null && (this.wireMockBaseUrl == null || this.wireMockBaseUrl.isBlank())) {
                this.wireMockBaseUrl = wireMockServer.baseUrl();
            }
            return this;
        }

        public Builder upstreamTimeoutSeconds(int seconds) {
            this.upstreamTimeoutSeconds = seconds;
            return this;
        }

        public CasualWireMockGateway build() {
            if ((wireMockBaseUrl == null || wireMockBaseUrl.isBlank()) && wireMockServer == null) {
                throw new IllegalStateException("wireMockBaseUrl or wireMockServer must be specified");
            }
            return new CasualWireMockGateway(port, wireMockBaseUrl, wireMockServer, upstreamTimeoutSeconds);
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
        if (server == null) {
            throw new IllegalStateException("Gateway has not been started");
        }
        return server.getPort();
    }

    public synchronized boolean isRunning() {
        return server != null && server.isRunning();
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.close();
            server = null;
        }
        for (Map.Entry<String, CasualTcpClient> entry : upstreamClients.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                LOG.debug("Error closing pooled upstream client [{}]: {}", entry.getKey(), e.getMessage());
            }
        }
        upstreamClients.clear();
        enlistedXidClients.clear();
        localMatchedXids.clear();
        prepareVoteOverrides.clear();
        commitVoteOverrides.clear();
        rollbackVoteOverrides.clear();
        LOG.info("CasualWireMockGateway stopped");
    }

    // Discovery inspects active WireMock proxy stubs to discover available upstreams.
    // When a project is switched to STUBS MODE in the UI (proxy stub disabled),
    // discovery only queries local stubs and avoids network timeouts against offline upstreams.
    private List<Service> handleDiscovery(List<String> requestedNames) {
        Map<String, Service> aggregated = new LinkedHashMap<>();

        // 1. Collect unique casual:// targets from active proxy stubs and literal local stub names
        Set<String> upstreamTargets = new LinkedHashSet<>();
        Set<String> localStubNames = new LinkedHashSet<>();

        for (StubMapping stub : getActiveStubMappings()) {
            if (isCasualProxyStub(stub)) {
                String target = extractCasualTargetUrl(stub);
                if (target != null && target.startsWith("casual://")) {
                    upstreamTargets.add(target);
                }
            } else {
                extractLocalStubServiceName(stub).ifPresent(localStubNames::add);
            }
        }

        // 2. Query each active upstream domain catalog
        for (String targetUrl : upstreamTargets) {
            try {
                URI uri = URI.create(targetUrl);
                CasualTcpClient client = getOrCreateUpstreamClient(uri.getHost(), uri.getPort());
                List<String> namesToQuery = requestedNames != null ? requestedNames : Collections.emptyList();
                CasualDomainDiscoveryReplyMessage upstreamReply =
                        client.discoverServices(namesToQuery, upstreamTimeoutSeconds, TimeUnit.SECONDS);
                if (upstreamReply != null && upstreamReply.getServices() != null) {
                    for (Service s : upstreamReply.getServices()) {
                        aggregated.put(s.getName(), s);
                    }
                    LOG.debug(
                            "[CASUAL-GATEWAY-DISCOVERY] Discovered {} service(s) from upstream [{}]",
                            upstreamReply.getServices().size(),
                            targetUrl);
                }
            } catch (Exception e) {
                LOG.warn(
                        "[CASUAL-GATEWAY-DISCOVERY] Failed to query upstream [{}] for domain discovery: {}",
                        targetUrl,
                        e.getMessage());
            }
        }

        // 3. Merge local static stubs
        for (String stubName : localStubNames) {
            if (!aggregated.containsKey(stubName)) {
                aggregated.put(stubName, Service.of(stubName, "wiremock-stub", TransactionType.AUTOMATIC));
            }
        }

        // 4. If explicit names were queried, ensure they are registered so callers aren't blocked
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

    private Optional<String> extractLocalStubServiceName(StubMapping stub) {
        if (stub == null || stub.getRequest() == null) {
            return Optional.empty();
        }
        String url = stub.getRequest().getUrl();
        if (url == null) {
            url = stub.getRequest().getUrlPath();
        }
        if (url == null) {
            url = stub.getRequest().getUrlPattern();
        }
        String prefix = "/__casual/services/";
        if (url != null && url.startsWith(prefix)) {
            String candidate = url.substring(prefix.length());
            if (!candidate.isBlank() && !candidate.contains("*") && !candidate.contains(".")) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    CasualServiceCallReplyMessage handleServiceCall(CasualServiceCallRequestMessage req) {
        String serviceName = req.getServiceName();
        ServiceBuffer requestBuffer = req.getServiceBuffer();
        String bufferType = (requestBuffer != null && !requestBuffer.isEmpty()) ? requestBuffer.getType() : "JSON";
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

        String baseUrl = (wireMockBaseUrl != null && !wireMockBaseUrl.isBlank())
                ? wireMockBaseUrl
                : (wireMockServer != null ? wireMockServer.baseUrl() : "http://localhost:8080");
        String absoluteUrl = baseUrl + "/__casual/services/" + serviceName;

        // Build in-memory probe request
        ImmutableRequest wiremockRequest = ImmutableRequest.create()
                .withMethod(RequestMethod.POST)
                .withAbsoluteUrl(absoluteUrl)
                .withHeader(
                        "Content-Type",
                        CasualBufferUtils.isJsonType(bufferType) ? "application/json" : "application/octet-stream")
                .withHeader("X-Casual-Service", serviceName)
                .withHeader("X-Casual-Buffer-Type", bufferType)
                .withHeader("X-Casual-Execution", executionId)
                .withBody(requestPayload.getBytes(StandardCharsets.UTF_8))
                .build();

        // In-memory WireMock stub probing:
        // Probing WireMock stubs in-memory evaluates priority order, URL patterns,
        // custom headers, and body patterns (e.g. equalToJson) with microsecond latency
        // and zero Jetty/HTTP loopback overhead.
        Optional<StubMapping> matchedStubOpt = findMatchingStub(wiremockRequest);

        if (matchedStubOpt.isPresent()) {
            StubMapping stub = matchedStubOpt.get();

            // Branch A: Matched a Casual Proxy Stub (targetBaseUrl = "casual://host:port")
            if (isCasualProxyStub(stub)) {
                String targetUrl = extractCasualTargetUrl(stub);
                LOG.debug(
                        "[CASUAL-GATEWAY-PROXY] Service [{}] matched proxy stub [{}] -> pure TCP forwarding to {}",
                        serviceName,
                        stub.getId(),
                        targetUrl);
                return executePureCasualProxy(req, stub, wiremockRequest, targetUrl);
            }

            // Branch B: Matched an Offline Mock Stub
            LOG.debug("[CASUAL-GATEWAY-MOCK] Service [{}] matched offline mock stub [{}]", serviceName, stub.getId());
            return executeOfflineStub(req, stub, wiremockRequest, bufferType);
        }

        // Branch C: Unmatched -> fail fast
        if (!CasualBufferUtils.isJsonType(bufferType) && !CasualBufferUtils.isCStringType(bufferType)) {
            LOG.error(
                    "[CASUAL-GATEWAY-INVALID-TYPE] Service [{}] bufferType [{}] unmatched without proxy. Returning TPEITYPE.",
                    serviceName,
                    bufferType);
            recordUnmatchedServeEvent(wiremockRequest, ErrorState.TPEITYPE);
            return buildErrorReply(req, ErrorState.TPEITYPE);
        }
        LOG.error(
                "[CASUAL-GATEWAY-NOTFOUND] Service [{}] unmatched in WireMock stubs. Returning TPENOENT.", serviceName);
        recordUnmatchedServeEvent(wiremockRequest, ErrorState.TPENOENT);
        return buildErrorReply(req, ErrorState.TPENOENT);
    }

    private CasualServiceCallReplyMessage executePureCasualProxy(
            CasualServiceCallRequestMessage req, StubMapping stub, Request wiremockRequest, String targetUrl) {
        try {
            URI uri = URI.create(targetUrl);
            CasualTcpClient client = getOrCreateUpstreamClient(uri.getHost(), uri.getPort());

            if (isTransactional(req.getXid())) {
                enlistedXidClients
                        .computeIfAbsent(req.getXid(), k -> ConcurrentHashMap.newKeySet())
                        .add(client);
            }

            CasualServiceCallRequestMessage upstreamReq = adaptRequestForUpstream(client, req);
            CasualNWMessage<CasualServiceCallReplyMessage> replyMsg =
                    client.sendAndReceive(upstreamReq.getType(), upstreamReq, upstreamTimeoutSeconds, TimeUnit.SECONDS);
            CasualServiceCallReplyMessage upstreamReply = replyMsg.getMessage();

            // Record proxied serve event in WireMock Request Journal for UI observability and snapshotting
            recordProxiedServeEvent(stub, wiremockRequest, targetUrl, upstreamReply);

            // Return upstream reply adapted for inbound client's protocol version
            return buildReplyForInboundClient(req, upstreamReply);
        } catch (Exception e) {
            LOG.error(
                    "[CASUAL-GATEWAY-UPSTREAM-FAIL] Failed to proxy Casual call [{}] to {}: {}",
                    req.getServiceName(),
                    targetUrl,
                    e.getMessage(),
                    e);
            return buildErrorReply(req, ErrorState.TPESVCERR);
        }
    }

    private CasualServiceCallReplyMessage executeOfflineStub(
            CasualServiceCallRequestMessage req, StubMapping stub, Request wiremockRequest, String requestBufferType) {
        ResponseDefinition responseDef = stub.getResponse();
        int statusCode = responseDef.getStatus();
        ErrorState errorState = ErrorState.OK;
        long userSuppliedError = 0;
        TransactionState transactionState = TransactionState.TX_ACTIVE;
        ServiceBuffer replyBuffer = ServiceBuffer.empty();

        HttpHeaders headers = responseDef.getHeaders();
        if (headers != null
                && headers.getHeader("X-Casual-Error-State") != null
                && headers.getHeader("X-Casual-Error-State").isPresent()) {
            try {
                errorState = ErrorState.valueOf(headers.getHeader("X-Casual-Error-State")
                        .firstValue()
                        .trim()
                        .toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errorState = (statusCode >= 200 && statusCode < 300) ? ErrorState.OK : ErrorState.TPESVCFAIL;
            }
        } else if (statusCode < 200 || statusCode >= 300) {
            if (statusCode == 400) errorState = ErrorState.TPEINVAL;
            else if (statusCode == 404) errorState = ErrorState.TPENOENT;
            else if (statusCode == 408 || statusCode == 504) errorState = ErrorState.TPETIME;
            else if (statusCode == 429) errorState = ErrorState.TPELIMIT;
            else if (statusCode == 502 || statusCode == 503) errorState = ErrorState.TPESVCERR;
            else errorState = ErrorState.TPESVCFAIL;
        }

        if (headers != null) {
            HttpHeader userHeader = headers.getHeader("X-Casual-User-Supplied-Error");
            if (userHeader == null || !userHeader.isPresent()) {
                userHeader = headers.getHeader("X-Casual-User-Code");
            }
            if (userHeader == null || !userHeader.isPresent()) {
                userHeader = headers.getHeader("X-Casual-User-Defined-Code");
            }
            if (userHeader != null && userHeader.isPresent()) {
                try {
                    userSuppliedError = Long.parseLong(userHeader.firstValue().trim());
                } catch (NumberFormatException ignored) {
                }
            }

            HttpHeader txHeader = headers.getHeader("X-Casual-Transaction-State");
            if (txHeader != null && txHeader.isPresent()) {
                try {
                    transactionState = TransactionState.valueOf(
                            txHeader.firstValue().trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                }
            } else if (errorState != ErrorState.OK) {
                transactionState = TransactionState.ROLLBACK_ONLY;
            }
        } else if (errorState != ErrorState.OK) {
            transactionState = TransactionState.ROLLBACK_ONLY;
        }

        if (isTransactional(req.getXid())) {
            localMatchedXids.add(req.getXid());
            recordVoteOverrideFromHeaders(headers, "X-Casual-XA-Prepare-Vote", req.getXid(), prepareVoteOverrides);
            recordVoteOverrideFromHeaders(headers, "X-Casual-XA-Commit-Vote", req.getXid(), commitVoteOverrides);
            recordVoteOverrideFromHeaders(headers, "X-Casual-XA-Rollback-Vote", req.getXid(), rollbackVoteOverrides);
        }

        byte[] rawByteBody = responseDef.getByteBody();
        String respBody = responseDef.getBody();
        if (respBody == null && responseDef.getBase64Body() != null) {
            respBody = responseDef.getBase64Body();
        }
        if (respBody == null && responseDef.getReponseBody() != null) {
            respBody = responseDef.getReponseBody().asString();
        }
        if (respBody == null && responseDef.getBodyFileName() != null) {
            respBody = readBodyFromFile(responseDef.getBodyFileName());
        }
        String replyBufferType = (headers != null
                        && headers.getHeader("X-Casual-Buffer-Type") != null
                        && headers.getHeader("X-Casual-Buffer-Type").isPresent())
                ? headers.getHeader("X-Casual-Buffer-Type").firstValue()
                : requestBufferType;
        if (respBody != null && !respBody.isBlank()) {
            replyBuffer = CasualBufferUtils.wrapPayload(replyBufferType, respBody);
        } else if (rawByteBody != null && rawByteBody.length > 0) {
            replyBuffer = CasualBufferUtils.wrapPayload(replyBufferType, rawByteBody);
        }

        recordOfflineServeEvent(stub, wiremockRequest, responseDef, replyBufferType, respBody);

        ProtocolVersion version = determineProtocolVersion(req);
        CasualServiceCallReplyMessage.Builder replyBuilder = CasualServiceCallReplyMessage.createBuilder()
                .setProtocolVersion(version)
                .setExecution(req.getExecution())
                .setError(errorState)
                .setUserSuppliedError(userSuppliedError)
                .setTransactionState(transactionState)
                .setServiceBuffer(replyBuffer);
        if (version.isLessThan(ProtocolVersion.VERSION_1_3)) {
            replyBuilder.setXid(req.getXid());
        }
        return replyBuilder.build();
    }

    private String readBodyFromFile(String bodyFileName) {
        if (bodyFileName == null || bodyFileName.isBlank()) {
            return null;
        }
        // 1. Try in-memory wireMockServer stores reflection
        if (wireMockServer != null) {
            try {
                Field appField = WireMockServer.class.getDeclaredField("wireMockApp");
                appField.setAccessible(true);
                Object wireMockApp = appField.get(wireMockServer);
                Field storesField = wireMockApp.getClass().getDeclaredField("stores");
                storesField.setAccessible(true);
                com.github.tomakehurst.wiremock.store.Stores stores =
                        (com.github.tomakehurst.wiremock.store.Stores) storesField.get(wireMockApp);
                if (stores != null && stores.getFilesBlobStore() != null) {
                    byte[] bytes = stores.getFilesBlobStore()
                            .getStreamSource(bodyFileName)
                            .getStream()
                            .readAllBytes();
                    if (bytes != null && bytes.length > 0) {
                        return new String(bytes, StandardCharsets.UTF_8);
                    }
                }
            } catch (ReflectiveOperationException | IOException | RuntimeException ignored) {
            }
            try {
                byte[] bytes = wireMockServer
                        .getOptions()
                        .filesRoot()
                        .getBinaryFileNamed(bodyFileName)
                        .readContents();
                if (bytes != null && bytes.length > 0) {
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            } catch (RuntimeException ignored) {
            }
        }
        // 2. Try WireMockServices SPI stores
        Optional<WireMockServices> servicesOpt = CasualRegistry.getInstance().getWireMockServices();
        if (servicesOpt.isPresent()) {
            try {
                byte[] bytes = servicesOpt
                        .get()
                        .getStores()
                        .getFilesBlobStore()
                        .getStreamSource(bodyFileName)
                        .getStream()
                        .readAllBytes();
                if (bytes != null && bytes.length > 0) {
                    return new String(bytes, StandardCharsets.UTF_8);
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return null;
    }

    private void recordVoteOverrideFromHeaders(
            HttpHeaders headers, String headerName, Xid xid, Map<Xid, XAReturnCode> targetMap) {
        if (headers == null) return;
        HttpHeader header = headers.getHeader(headerName);
        if (header != null && header.isPresent()) {
            try {
                XAReturnCode vote =
                        XAReturnCode.valueOf(header.firstValue().trim().toUpperCase(Locale.ROOT));
                targetMap.put(xid, vote);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private Optional<StubMapping> findMatchingStub(Request wiremockRequest) {
        List<StubMapping> mappings = new ArrayList<>(getActiveStubMappings());
        // WireMock Priority sorting: lower integer = higher priority, default is 5
        mappings.sort((a, b) -> {
            int pa = a.getPriority() != null ? a.getPriority() : 5;
            int pb = b.getPriority() != null ? b.getPriority() : 5;
            return Integer.compare(pa, pb);
        });

        for (StubMapping mapping : mappings) {
            if (mapping.getRequest() != null
                    && mapping.getRequest().match(wiremockRequest).isExactMatch()) {
                return Optional.of(mapping);
            }
        }
        return Optional.empty();
    }

    private boolean isCasualProxyStub(StubMapping stub) {
        if (stub == null) return false;
        String target = extractCasualTargetUrl(stub);
        return target != null && target.startsWith("casual://");
    }

    private String extractCasualTargetUrl(StubMapping stub) {
        if (stub == null) return null;
        if (stub.getMetadata() != null) {
            Object target = stub.getMetadata().get("targetBaseUrl");
            if (target != null && !target.toString().isBlank()) {
                return target.toString().trim();
            }
            Object proxyUrl = stub.getMetadata().get("proxyBaseUrl");
            if (proxyUrl != null && !proxyUrl.toString().isBlank()) {
                return proxyUrl.toString().trim();
            }
        }
        if (stub.getResponse() != null && stub.getResponse().getProxyBaseUrl() != null) {
            String p = stub.getResponse().getProxyBaseUrl().trim();
            if (p.startsWith("casual://")) {
                return p;
            }
        }
        return null;
    }

    private synchronized CasualTcpClient getOrCreateUpstreamClient(String host, int port) {
        String key = host + ":" + port;
        CasualTcpClient client = upstreamClients.get(key);
        if (client == null || !client.isConnected()) {
            try {
                client = new CasualTcpClient(host, port, domainId, domainName, upstreamTimeoutSeconds);
                client.connect();
                upstreamClients.put(key, client);
            } catch (Exception e) {
                LOG.error(
                        "[CASUAL-GATEWAY-CONNECT-FAIL] Could not connect to upstream {}:{}: {}",
                        host,
                        port,
                        e.getMessage());
                throw new RuntimeException("Could not connect to upstream " + host + ":" + port, e);
            }
        }
        return client;
    }

    private CasualServiceCallRequestMessage adaptRequestForUpstream(
            CasualTcpClient client, CasualServiceCallRequestMessage req) {
        ProtocolVersion upstreamVersion = client.getProtocolVersion();
        if (upstreamVersion != null) {
            boolean needsRebuild = false;
            if (upstreamVersion.isGreaterThanOrEqualTo(ProtocolVersion.VERSION_1_3)) {
                if (req.getType() != CasualNWMessageType.SERVICE_CALL_REQUEST_V_1_3) {
                    needsRebuild = true;
                }
            } else {
                if (req.getType() != CasualNWMessageType.SERVICE_CALL_REQUEST) {
                    needsRebuild = true;
                }
            }
            if (needsRebuild) {
                CasualServiceCallRequestMessage.Builder b = CasualServiceCallRequestMessage.createBuilder()
                        .setProtocolVersion(upstreamVersion)
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
                    } else if (upstreamVersion.isGreaterThanOrEqualTo(ProtocolVersion.VERSION_1_3)) {
                        b.setParentSpan(SpanId.of());
                    }
                } catch (CasualProtocolException ignored) {
                    if (upstreamVersion.isGreaterThanOrEqualTo(ProtocolVersion.VERSION_1_3)) {
                        b.setParentSpan(SpanId.of());
                    }
                }
                return b.build();
            }
        }
        return req;
    }

    private CasualServiceCallReplyMessage buildReplyForInboundClient(
            CasualServiceCallRequestMessage req, CasualServiceCallReplyMessage upstreamReply) {
        ProtocolVersion clientVersion = determineProtocolVersion(req);
        CasualServiceCallReplyMessage.Builder b = CasualServiceCallReplyMessage.createBuilder()
                .setProtocolVersion(clientVersion)
                .setExecution(upstreamReply.getExecution())
                .setError(upstreamReply.getError())
                .setUserSuppliedError(upstreamReply.getUserDefinedCode())
                .setTransactionState(upstreamReply.getTransactionState())
                .setServiceBuffer(upstreamReply.getServiceBuffer());
        if (clientVersion.isLessThan(ProtocolVersion.VERSION_1_3) && req != null) {
            b.setXid(req.getXid());
        }
        return b.build();
    }

    private void recordProxiedServeEvent(
            StubMapping stub, Request wiremockRequest, String targetUrl, CasualServiceCallReplyMessage upstreamReply) {
        ErrorState errorState = upstreamReply.getError();
        int statusCode = 200;
        if (errorState == ErrorState.TPENOENT) statusCode = 404;
        else if (errorState == ErrorState.TPEINVAL) statusCode = 400;
        else if (errorState == ErrorState.TPETIME) statusCode = 504;
        else if (errorState == ErrorState.TPESVCERR) statusCode = 502;
        else if (errorState != ErrorState.OK) statusCode = 500;

        byte[] rawBytes = CasualBufferUtils.toByteArray(upstreamReply.getServiceBuffer());
        String replyPayload = CasualBufferUtils.extractPayload(upstreamReply.getServiceBuffer());
        String replyBufferType = (upstreamReply.getServiceBuffer() != null
                        && !upstreamReply.getServiceBuffer().isEmpty())
                ? upstreamReply.getServiceBuffer().getType()
                : "JSON";

        String contentType;
        if (CasualBufferUtils.isJsonType(replyBufferType)
                || (CasualBufferUtils.isFieldedType(replyBufferType)
                        && replyPayload.trim().startsWith("{"))) {
            contentType = "application/json";
        } else if (CasualBufferUtils.isCStringType(replyBufferType)) {
            contentType = "text/plain; charset=UTF-8";
        } else {
            contentType = "application/octet-stream";
        }

        HttpHeaders headers = new HttpHeaders(
                new HttpHeader("Content-Type", contentType),
                new HttpHeader("X-Casual-Error-State", errorState.name()),
                new HttpHeader(
                        "X-Casual-Transaction-State",
                        upstreamReply.getTransactionState().name()),
                new HttpHeader("X-Casual-Buffer-Type", replyBufferType));

        ResponseDefinition responseDef = ResponseDefinitionBuilder.responseDefinition()
                .proxiedFrom(targetUrl)
                .withStatus(statusCode)
                .withHeaders(headers)
                .withBody(replyPayload)
                .build();

        byte[] bodyForResponse = (CasualBufferUtils.isJsonType(replyBufferType)
                        || CasualBufferUtils.isCStringType(replyBufferType)
                        || (CasualBufferUtils.isFieldedType(replyBufferType)
                                && replyPayload.trim().startsWith("{")))
                ? replyPayload.getBytes(StandardCharsets.UTF_8)
                : rawBytes;

        Response response = Response.response()
                .status(statusCode)
                .headers(headers)
                .body(bodyForResponse)
                .build();

        ServeEvent serveEvent = ServeEvent.of(wiremockRequest)
                .withStubMapping(stub)
                .withResponseDefinition(responseDef)
                .complete(response, DataTruncationSettings.DEFAULTS);

        recordServeEvent(serveEvent);
    }

    private void recordOfflineServeEvent(
            StubMapping stub, Request wiremockRequest, ResponseDefinition responseDef, String bufferType, String body) {
        Response.Builder responseBuilder =
                Response.response().status(responseDef.getStatus()).headers(responseDef.getHeaders());
        if (responseDef.getBody() != null) {
            responseBuilder.body(responseDef.getBody());
        } else if (responseDef.getByteBody() != null) {
            responseBuilder.body(responseDef.getByteBody());
        } else {
            responseBuilder.body(body != null ? body : "");
        }
        Response response = responseBuilder.build();

        ServeEvent serveEvent = ServeEvent.of(wiremockRequest)
                .withStubMapping(stub)
                .withResponseDefinition(responseDef)
                .complete(response, DataTruncationSettings.DEFAULTS);

        recordServeEvent(serveEvent);
    }

    private void recordUnmatchedServeEvent(Request wiremockRequest, ErrorState errorState) {
        ResponseDefinition responseDef = ResponseDefinition.notConfigured();
        String errName = errorState != null ? errorState.name() : "TPENOENT";
        int status = errorState == ErrorState.TPEITYPE ? 415 : 404;
        Response response = Response.response()
                .status(status)
                .headers(new HttpHeaders(
                        new HttpHeader("Content-Type", "application/json"),
                        new HttpHeader("X-Casual-Error-State", errName),
                        new HttpHeader("X-Casual-Service", wiremockRequest.getHeader("X-Casual-Service"))))
                .body(String.format(
                        Locale.ROOT,
                        "{\"error\": \"%s\", \"message\": \"Service unmatched in WireMock stubs\"}",
                        errName))
                .build();
        ServeEvent serveEvent = ServeEvent.of(wiremockRequest)
                .withResponseDefinition(responseDef)
                .complete(response, DataTruncationSettings.DEFAULTS);
        recordServeEvent(serveEvent);
    }

    private void recordServeEvent(ServeEvent serveEvent) {
        // 1. Try in-memory WireMockServer requestJournal reflection (specific to this gateway instance)
        if (wireMockServer != null) {
            try {
                Field appField = WireMockServer.class.getDeclaredField("wireMockApp");
                appField.setAccessible(true);
                Object wireMockApp = appField.get(wireMockServer);

                // Prefer direct stores.getRequestJournalStore().add()
                try {
                    Field storesField = wireMockApp.getClass().getDeclaredField("stores");
                    storesField.setAccessible(true);
                    com.github.tomakehurst.wiremock.store.Stores stores =
                            (com.github.tomakehurst.wiremock.store.Stores) storesField.get(wireMockApp);
                    if (stores != null && stores.getRequestJournalStore() != null) {
                        stores.getRequestJournalStore().add(serveEvent);
                        return;
                    }
                } catch (Throwable ignored) {
                }

                // Fall back to requestJournal.requestReceived()
                Field journalField = wireMockApp.getClass().getDeclaredField("requestJournal");
                journalField.setAccessible(true);
                RequestJournal journal = (RequestJournal) journalField.get(wireMockApp);
                if (journal != null) {
                    journal.requestReceived(serveEvent);
                    return;
                }
            } catch (Throwable t) {
                LOG.debug("Could not add ServeEvent to in-memory wireMockServer journal: {}", t.getMessage());
            }
        }

        // 2. Fall back to RequestJournalStore via WireMockServices SPI in CasualRegistry
        Optional<WireMockServices> servicesOpt = CasualRegistry.getInstance().getWireMockServices();
        if (servicesOpt.isPresent()) {
            try {
                servicesOpt.get().getStores().getRequestJournalStore().add(serveEvent);
                return;
            } catch (Exception e) {
                LOG.debug("Could not add ServeEvent to RequestJournalStore: {}", e.getMessage());
            }
        }
    }

    private List<StubMapping> getActiveStubMappings() {
        if (wireMockServer != null) {
            return wireMockServer.getStubMappings();
        }
        Optional<WireMockServices> servicesOpt = CasualRegistry.getInstance().getWireMockServices();
        if (servicesOpt.isPresent()) {
            try {
                return servicesOpt.get().getStores().getStubStore().getAll().toList();
            } catch (Exception ignored) {
            }
        }
        if (!wireMockBaseUrl.isBlank()) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(wireMockBaseUrl + "/__admin/mappings"))
                        .GET()
                        .timeout(Duration.ofSeconds(2))
                        .build();
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    ListStubMappingsResult result = Json.read(response.body(), ListStubMappingsResult.class);
                    if (result != null && result.getMappings() != null) {
                        return result.getMappings();
                    }
                }
            } catch (IOException | InterruptedException | RuntimeException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOG.debug("Failed to query WireMock mappings over HTTP: {}", e.getMessage());
            }
        }
        return Collections.emptyList();
    }

    private CasualServiceCallReplyMessage buildErrorReply(CasualServiceCallRequestMessage req, ErrorState errorState) {
        ProtocolVersion version = determineProtocolVersion(req);
        CasualServiceCallReplyMessage.Builder replyBuilder = CasualServiceCallReplyMessage.createBuilder()
                .setProtocolVersion(version)
                .setExecution(req != null ? req.getExecution() : UUID.randomUUID())
                .setError(errorState)
                .setTransactionState(TransactionState.TX_ACTIVE)
                .setServiceBuffer(ServiceBuffer.empty());
        if (version.isLessThan(ProtocolVersion.VERSION_1_3) && req != null) {
            replyBuilder.setXid(req.getXid());
        }
        return replyBuilder.build();
    }

    private ProtocolVersion determineProtocolVersion(CasualServiceCallRequestMessage req) {
        if (req != null && req.getType() != null) {
            if (req.getType() == CasualNWMessageType.SERVICE_CALL_REQUEST_V_1_3) {
                return ProtocolVersion.VERSION_1_3;
            }
        }
        return ProtocolVersion.VERSION_1_0;
    }

    private boolean isTransactional(Xid xid) {
        return xid != null && !XID.NULL_XID.equals(xid);
    }

    private void cleanupTransactionState(Xid xid) {
        if (xid != null) {
            enlistedXidClients.remove(xid);
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

        Set<CasualTcpClient> clients = (xid != null) ? enlistedXidClients.get(xid) : null;
        if (clients != null && !clients.isEmpty()) {
            // Fan out prepare to all enlisted upstream clients
            boolean allReadOnly = true;
            for (CasualTcpClient client : clients) {
                try {
                    CasualNWMessage<CasualTransactionResourcePrepareReplyMessage> reply = client.sendAndReceive(
                            CasualNWMessageType.PREPARE_REQUEST, req, upstreamTimeoutSeconds, TimeUnit.SECONDS);
                    XAReturnCode code = reply.getMessage().getTransactionReturnCode();
                    if (code != XAReturnCode.XA_OK && code != XAReturnCode.XA_RDONLY) {
                        cleanupTransactionState(xid);
                        return CasualTransactionResourcePrepareReplyMessage.of(
                                req.getExecution(), xid, req.getResourceId(), code);
                    }
                    if (code != XAReturnCode.XA_RDONLY) {
                        allReadOnly = false;
                    }
                } catch (Exception e) {
                    LOG.error("[CASUAL-GATEWAY-2PC] Failed to forward Casual 2PC prepare request to upstream", e);
                    cleanupTransactionState(xid);
                    return CasualTransactionResourcePrepareReplyMessage.of(
                            req.getExecution(), xid, req.getResourceId(), XAReturnCode.XAER_RMERR);
                }
            }
            XAReturnCode finalVote = allReadOnly ? XAReturnCode.XA_RDONLY : XAReturnCode.XA_OK;
            if (finalVote == XAReturnCode.XA_RDONLY) {
                cleanupTransactionState(xid);
            }
            return CasualTransactionResourcePrepareReplyMessage.of(
                    req.getExecution(), xid, req.getResourceId(), finalVote);
        }

        return CasualTransactionResourcePrepareReplyMessage.of(
                req.getExecution(), xid, req.getResourceId(), XAReturnCode.XA_OK);
    }

    @Override
    public CasualTransactionResourceCommitReplyMessage commit(CasualTransactionResourceCommitRequestMessage req) {
        Xid xid = req.getXid();
        XAReturnCode override = (xid != null) ? commitVoteOverrides.remove(xid) : null;
        Set<CasualTcpClient> clients = (xid != null) ? enlistedXidClients.remove(xid) : null;
        cleanupTransactionState(xid);

        if (override != null) {
            return CasualTransactionResourceCommitReplyMessage.of(
                    req.getExecution(), xid, req.getResourceId(), override);
        }

        if (clients != null && !clients.isEmpty()) {
            for (CasualTcpClient client : clients) {
                try {
                    CasualNWMessage<CasualTransactionResourceCommitReplyMessage> reply = client.sendAndReceive(
                            CasualNWMessageType.COMMIT_REQUEST, req, upstreamTimeoutSeconds, TimeUnit.SECONDS);
                    XAReturnCode code = reply.getMessage().getTransactionReturnCode();
                    if (code != XAReturnCode.XA_OK) {
                        return CasualTransactionResourceCommitReplyMessage.of(
                                req.getExecution(), xid, req.getResourceId(), code);
                    }
                } catch (Exception e) {
                    LOG.error("[CASUAL-GATEWAY-2PC] Failed to forward Casual 2PC commit request to upstream", e);
                    return CasualTransactionResourceCommitReplyMessage.of(
                            req.getExecution(), xid, req.getResourceId(), XAReturnCode.XAER_RMERR);
                }
            }
        }

        return CasualTransactionResourceCommitReplyMessage.of(
                req.getExecution(), xid, req.getResourceId(), XAReturnCode.XA_OK);
    }

    @Override
    public CasualTransactionResourceRollbackReplyMessage rollback(CasualTransactionResourceRollbackRequestMessage req) {
        Xid xid = req.getXid();
        XAReturnCode override = (xid != null) ? rollbackVoteOverrides.remove(xid) : null;
        Set<CasualTcpClient> clients = (xid != null) ? enlistedXidClients.remove(xid) : null;
        cleanupTransactionState(xid);

        if (override != null) {
            return CasualTransactionResourceRollbackReplyMessage.of(
                    req.getExecution(), xid, req.getResourceId(), override);
        }

        if (clients != null && !clients.isEmpty()) {
            for (CasualTcpClient client : clients) {
                try {
                    CasualNWMessage<CasualTransactionResourceRollbackReplyMessage> reply = client.sendAndReceive(
                            CasualNWMessageType.REQUEST_ROLLBACK, req, upstreamTimeoutSeconds, TimeUnit.SECONDS);
                    XAReturnCode code = reply.getMessage().getTransactionReturnCode();
                    if (code != XAReturnCode.XA_OK) {
                        return CasualTransactionResourceRollbackReplyMessage.of(
                                req.getExecution(), xid, req.getResourceId(), code);
                    }
                } catch (Exception e) {
                    LOG.error("[CASUAL-GATEWAY-2PC] Failed to forward Casual 2PC rollback request to upstream", e);
                    return CasualTransactionResourceRollbackReplyMessage.of(
                            req.getExecution(), xid, req.getResourceId(), XAReturnCode.XAER_RMERR);
                }
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
