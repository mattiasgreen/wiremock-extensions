package com.github.mattiasgreen.wiremock.casual;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.ImmutableRequest;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.laz.casual.api.flags.AtmiFlags;
import se.laz.casual.api.flags.ErrorState;
import se.laz.casual.network.ProtocolVersion;
import se.laz.casual.network.messages.domain.TransactionType;
import se.laz.casual.network.protocol.messages.domain.CasualDomainDiscoveryReplyMessage;
import se.laz.casual.network.protocol.messages.domain.CasualDomainDiscoveryRequestMessage;
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
 * In-process Casual Domain Gateway server for WireMock.
 * Listens on a TCP port and bridges Casual XATMI protocol messages
 * to WireMock stub definitions using {@link CasualHttpMediation}, {@link CasualStorePort},
 * and {@link CasualTransactionCoordinator}.
 */
public class CasualWireMockGateway
        implements AutoCloseable, CasualServer.TransactionHandler, CasualServer.QueueHandler {

    private static final Logger LOG = LoggerFactory.getLogger(CasualWireMockGateway.class);

    static {
        CasualProtocolConfig.ensureInitialized();
    }

    private final int port;
    private final CasualStorePort storePort;
    private final int upstreamTimeoutSeconds;
    private final int workerThreads;
    private final UUID domainId;
    private final String domainName;

    private final ConcurrentHashMap<String, CasualClient> upstreamClients = new ConcurrentHashMap<>();
    private final Set<UUID> inFlightDiscoveryExecutions = ConcurrentHashMap.newKeySet();
    private final Set<String> inFlightProxyCalls = ConcurrentHashMap.newKeySet();
    private final CasualTransactionCoordinator transactionCoordinator = new CasualTransactionCoordinator();
    private final CasualQueueStore queueStore = new CasualQueueStore();

    private CasualServer server;

    public CasualWireMockGateway(int port, CasualStorePort storePort) {
        this(port, storePort, resolveDefaultTimeout());
    }

    public CasualWireMockGateway(int port, String wireMockBaseUrl) {
        this(port, new WireMockStoreAdapter(null, null, wireMockBaseUrl), resolveDefaultTimeout());
    }

    public CasualWireMockGateway(int port, WireMockServer wireMockServer) {
        this(port, new WireMockStoreAdapter(wireMockServer), resolveDefaultTimeout());
    }

    public CasualWireMockGateway(
            int port, String wireMockBaseUrl, WireMockServer wireMockServer, int upstreamTimeoutSeconds) {
        this(port, new WireMockStoreAdapter(wireMockServer, null, wireMockBaseUrl), upstreamTimeoutSeconds);
    }

    public CasualWireMockGateway(int port, CasualStorePort storePort, int upstreamTimeoutSeconds) {
        this(port, storePort, upstreamTimeoutSeconds, 0);
    }

    public CasualWireMockGateway(int port, CasualStorePort storePort, int upstreamTimeoutSeconds, int workerThreads) {
        this.port = port;
        this.storePort = storePort != null ? storePort : new WireMockStoreAdapter(null, null, "http://localhost:8080");
        this.upstreamTimeoutSeconds = upstreamTimeoutSeconds > 0 ? upstreamTimeoutSeconds : resolveDefaultTimeout();
        this.workerThreads = Math.max(0, workerThreads);
        this.domainId = UUID.randomUUID();
        this.domainName = "wiremock-casual-domain";
    }

    private static int resolveDefaultTimeout() {
        return Integer.getInteger(CasualClient.TIMEOUT_PROPERTY, CasualClient.DEFAULT_TIMEOUT_SECONDS);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int port = 0;
        private String wireMockBaseUrl;
        private WireMockServer wireMockServer;
        private CasualStorePort storePort;
        private int upstreamTimeoutSeconds = 0;
        private int workerThreads = 0;

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

        public Builder storePort(CasualStorePort storePort) {
            this.storePort = storePort;
            return this;
        }

        public Builder upstreamTimeoutSeconds(int seconds) {
            this.upstreamTimeoutSeconds = seconds;
            return this;
        }

        public Builder workerThreads(int workerThreads) {
            this.workerThreads = Math.max(0, workerThreads);
            return this;
        }

        public CasualWireMockGateway build() {
            CasualStorePort resolvedStore = storePort;
            if (resolvedStore == null) {
                resolvedStore = new WireMockStoreAdapter(wireMockServer, null, wireMockBaseUrl);
            }
            return new CasualWireMockGateway(port, resolvedStore, upstreamTimeoutSeconds, workerThreads);
        }
    }

    public synchronized void start() throws InterruptedException {
        server = CasualServer.builder()
                .port(port)
                .domainId(domainId)
                .domainName(domainName)
                .discoveryHandler(new CasualServer.DiscoveryHandler() {
                    @Override
                    public List<Service> discover(List<String> serviceNames) {
                        return handleDiscovery(serviceNames);
                    }

                    @Override
                    public List<Service> discover(CasualDomainDiscoveryRequestMessage request) {
                        return handleDiscovery(request);
                    }
                })
                .serviceHandler(this::handleServiceCall)
                .transactionHandler(this)
                .queueHandler(this)
                .queueDiscoveryHandler((names, ver) -> queueStore.getDiscoveredQueues(names, ver))
                .workerThreads(workerThreads)
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

    public CasualTransactionCoordinator getTransactionCoordinator() {
        return transactionCoordinator;
    }

    public CasualQueueStore getQueueStore() {
        return queueStore;
    }

    public CasualStorePort getStorePort() {
        return storePort;
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.close();
            server = null;
        }
        for (Map.Entry<String, CasualClient> entry : upstreamClients.entrySet()) {
            try {
                entry.getValue().close();
            } catch (Exception e) {
                LOG.debug("Error closing pooled upstream client [{}]: {}", entry.getKey(), e.getMessage());
            }
        }
        upstreamClients.clear();
        LOG.info("CasualWireMockGateway stopped");
    }

    public CasualServiceCallReplyMessage handleServiceCall(CasualServiceCallRequestMessage req) {
        String serviceName = req.getServiceName();
        ImmutableRequest wiremockRequest = CasualHttpMediation.buildProbeRequest(storePort.getBaseUrl(), req);

        Optional<StubMapping> matchedStubOpt = storePort.findMatchingStub(wiremockRequest);

        if (matchedStubOpt.isPresent()) {
            StubMapping stub = matchedStubOpt.get();

            // Branch A: Matched a Casual Proxy Stub (targetBaseUrl = "casual://host:port")
            if (isCasualProxyStub(stub)) {
                String targetUrl = extractCasualTargetUrl(stub);
                if (LOG.isDebugEnabled()) {
                    boolean transactional =
                            !isNoReply(req) && !isNoTran(req) && transactionCoordinator.isTransactional(req.getXid());
                    LOG.debug(
                            "[CASUAL-GATEWAY-PROXY] Service [{}] matched proxy stub [{}] -> pure TCP forwarding to {} (flags={}, transactional={}, timeoutNs={})",
                            sanitizeLog(serviceName),
                            stub.getId(),
                            sanitizeLog(targetUrl),
                            CasualProtocolConfig.safeXatmiFlags(req),
                            transactional,
                            req.getTimeout());
                }
                return executePureCasualProxy(req, stub, wiremockRequest, targetUrl);
            }

            // Branch B: Matched an Offline Mock Stub
            LOG.debug("[CASUAL-GATEWAY-MOCK] Service [{}] matched offline mock stub [{}]", serviceName, stub.getId());
            CasualHttpMediation.ExecutionResult result =
                    CasualHttpMediation.translateOfflineStub(req, stub, wiremockRequest, storePort);
            if (!isNoReply(req) && !isNoTran(req)) {
                transactionCoordinator.recordLocalCall(
                        req.getXid(), result.prepareVote(), result.commitVote(), result.rollbackVote());
            }
            storePort.recordServeEvent(result.serveEvent());
            return result.reply();
        }

        // Branch C: Unmatched -> fail fast
        String bufferType =
                (req.getServiceBuffer() != null && !req.getServiceBuffer().isEmpty())
                        ? req.getServiceBuffer().getType()
                        : "JSON";

        if (!CasualBufferUtils.isJsonType(bufferType) && !CasualBufferUtils.isCStringType(bufferType)) {
            LOG.error(
                    "[CASUAL-GATEWAY-INVALID-TYPE] Service [{}] bufferType [{}] unmatched without proxy. Returning TPEITYPE.",
                    serviceName,
                    bufferType);
            ServeEvent unmatchedEvent =
                    CasualHttpMediation.buildUnmatchedServeEvent(wiremockRequest, ErrorState.TPEITYPE);
            storePort.recordServeEvent(unmatchedEvent);
            return CasualHttpMediation.buildErrorReply(req, ErrorState.TPEITYPE);
        }

        LOG.error(
                "[CASUAL-GATEWAY-NOTFOUND] Service [{}] unmatched in WireMock stubs. Returning TPENOENT.", serviceName);
        ServeEvent unmatchedEvent = CasualHttpMediation.buildUnmatchedServeEvent(wiremockRequest, ErrorState.TPENOENT);
        storePort.recordServeEvent(unmatchedEvent);
        return CasualHttpMediation.buildErrorReply(req, ErrorState.TPENOENT);
    }

    private CasualServiceCallReplyMessage executePureCasualProxy(
            CasualServiceCallRequestMessage req, StubMapping stub, ImmutableRequest wiremockRequest, String targetUrl) {
        String serviceName = req.getServiceName() != null ? req.getServiceName() : "";
        String cycleKey = (req.getExecution() != null ? req.getExecution().toString() : "no-exec") + ":" + serviceName;
        if (!inFlightProxyCalls.add(cycleKey)) {
            LOG.warn(
                    "[CASUAL-GATEWAY-LOOP] Detected proxy routing cycle for service [{}] (execution={}) -> failing fast with TPENOENT",
                    sanitizeLog(serviceName),
                    req.getExecution());
            ServeEvent loopEvent = CasualHttpMediation.buildUnmatchedServeEvent(wiremockRequest, ErrorState.TPENOENT);
            storePort.recordServeEvent(loopEvent);
            return CasualHttpMediation.buildErrorReply(req, ErrorState.TPENOENT);
        }
        try {
            URI uri = URI.create(targetUrl);
            CasualClient client = getOrCreateUpstreamClient(uri.getHost(), uri.getPort());

            if (!isNoReply(req) && !isNoTran(req) && transactionCoordinator.isTransactional(req.getXid())) {
                transactionCoordinator.enlistUpstreamClient(req.getXid(), client);
            }

            CasualServiceCallReplyMessage upstreamReply = client.forwardServiceCall(req);

            ServeEvent proxiedEvent =
                    CasualHttpMediation.buildProxiedServeEvent(stub, wiremockRequest, targetUrl, upstreamReply);
            storePort.recordServeEvent(proxiedEvent);

            return upstreamReply;
        } catch (Exception e) {
            LOG.error(
                    "[CASUAL-GATEWAY-UPSTREAM-FAIL] Failed to proxy Casual call [{}] to {}: {}",
                    sanitizeLog(serviceName),
                    sanitizeLog(targetUrl),
                    sanitizeLog(e.getMessage()),
                    e);
            return CasualHttpMediation.buildErrorReply(req, ErrorState.TPESVCERR);
        } finally {
            inFlightProxyCalls.remove(cycleKey);
        }
    }

    private List<Service> handleDiscovery(List<String> requestedNames) {
        return discoverServices(null, null, requestedNames);
    }

    private List<Service> handleDiscovery(CasualDomainDiscoveryRequestMessage req) {
        if (req == null) {
            return Collections.emptyList();
        }
        return discoverServices(req.getExecution(), req.getDomainId(), req.getServiceNames());
    }

    private List<Service> discoverServices(UUID execution, UUID requesterDomainId, List<String> requestedNames) {
        if (execution != null && !inFlightDiscoveryExecutions.add(execution)) {
            LOG.debug("[CASUAL-GATEWAY-DISCOVERY] Suppressing cyclic discovery probe (execution={})", execution);
            return Collections.emptyList();
        }
        try {
            Map<String, Service> aggregated = new LinkedHashMap<>();

            Set<String> upstreamTargets = new LinkedHashSet<>();
            Set<String> localStubNames = new LinkedHashSet<>();
            List<StubMapping> nonProxyStubs = new ArrayList<>();

            for (StubMapping stub : storePort.getActiveStubMappings()) {
                if (isCasualProxyStub(stub)) {
                    String target = extractCasualTargetUrl(stub);
                    if (target != null && target.startsWith("casual://")) {
                        upstreamTargets.add(target);
                    }
                } else {
                    nonProxyStubs.add(stub);
                    extractLocalStubServiceName(stub).ifPresent(localStubNames::add);
                }
            }

            List<String> namesToQuery = requestedNames != null ? requestedNames : Collections.emptyList();
            for (String targetUrl : upstreamTargets) {
                try {
                    URI uri = URI.create(targetUrl);
                    CasualClient client = getOrCreateUpstreamClient(uri.getHost(), uri.getPort());
                    UUID remoteDomainId = client.getRemoteDomainId();
                    if (requesterDomainId != null && requesterDomainId.equals(remoteDomainId)) {
                        LOG.debug(
                                "[CASUAL-GATEWAY-DISCOVERY] Skipping split-horizon upstream discovery to [{}] (domainId={})",
                                sanitizeLog(targetUrl),
                                remoteDomainId);
                        continue;
                    }
                    CasualDomainDiscoveryReplyMessage upstreamReply = execution != null
                            ? client.discover(execution, namesToQuery)
                            : client.discover(namesToQuery);
                    if (upstreamReply != null && upstreamReply.getServices() != null) {
                        for (Service s : upstreamReply.getServices()) {
                            aggregated.put(s.getName(), s);
                        }
                    }
                } catch (Exception e) {
                    LOG.warn(
                            "[CASUAL-GATEWAY-DISCOVERY] Failed to query upstream [{}] for domain discovery: {}",
                            sanitizeLog(targetUrl),
                            sanitizeLog(e.getMessage()));
                }
            }

            for (String stubName : localStubNames) {
                if (!aggregated.containsKey(stubName)) {
                    aggregated.put(stubName, Service.of(stubName, "wiremock-stub", TransactionType.AUTOMATIC));
                }
            }

            if (!namesToQuery.isEmpty()) {
                for (String name : namesToQuery) {
                    if (name == null || aggregated.containsKey(name)) {
                        continue;
                    }
                    String serviceUrl = "/__casual/services/" + name;
                    boolean matchesLocalStub = nonProxyStubs.stream()
                            .anyMatch(stub -> stub.getRequest() != null
                                    && stub.getRequest().getUrlMatcher() != null
                                    && stub.getRequest()
                                            .getUrlMatcher()
                                            .match(serviceUrl)
                                            .isExactMatch());
                    if (matchesLocalStub) {
                        aggregated.put(name, Service.of(name, "wiremock-stub", TransactionType.AUTOMATIC));
                    }
                }
            }

            return new ArrayList<>(aggregated.values());
        } finally {
            if (execution != null) {
                inFlightDiscoveryExecutions.remove(execution);
            }
        }
    }

    private Optional<String> extractLocalStubServiceName(StubMapping stub) {
        if (stub == null || stub.getRequest() == null) {
            return Optional.empty();
        }
        String prefix = "/__casual/services/";
        String exactUrl = stub.getRequest().getUrl();
        if (exactUrl == null) {
            exactUrl = stub.getRequest().getUrlPath();
        }
        if (exactUrl != null && exactUrl.startsWith(prefix)) {
            String candidate = exactUrl.substring(prefix.length());
            int queryIdx = candidate.indexOf('?');
            if (queryIdx >= 0) {
                candidate = candidate.substring(0, queryIdx);
            }
            if (!candidate.isBlank()) {
                return Optional.of(candidate);
            }
        }
        String patternUrl = stub.getRequest().getUrlPattern();
        if (patternUrl == null) {
            patternUrl = stub.getRequest().getUrlPathPattern();
        }
        if (patternUrl != null && patternUrl.startsWith(prefix)) {
            String candidate = patternUrl.substring(prefix.length());
            if (!candidate.isBlank() && !containsRegexMeta(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static boolean containsRegexMeta(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '*' || c == '.' || c == '(' || c == '[' || c == '+' || c == '?' || c == '^' || c == '$') {
                return true;
            }
        }
        return false;
    }

    private static String sanitizeLog(String value) {
        return value != null ? value.replaceAll("[\r\n]", "") : "";
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

    private synchronized CasualClient getOrCreateUpstreamClient(String host, int port) {
        String key = host + ":" + port;
        CasualClient client = upstreamClients.get(key);
        if (client == null || !client.isConnected()) {
            client = new CasualClient(host, port, domainId, domainName, upstreamTimeoutSeconds);
            client.connect();
            upstreamClients.put(key, client);
        }
        return client;
    }

    private static boolean isNoReply(CasualServiceCallRequestMessage req) {
        return CasualProtocolConfig.safeXatmiFlags(req).isSet(AtmiFlags.TPNOREPLY);
    }

    private static boolean isNoTran(CasualServiceCallRequestMessage req) {
        return CasualProtocolConfig.safeXatmiFlags(req).isSet(AtmiFlags.TPNOTRAN);
    }

    @Override
    public CasualTransactionResourcePrepareReplyMessage prepare(CasualTransactionResourcePrepareRequestMessage req) {
        return transactionCoordinator.prepare(req);
    }

    @Override
    public CasualTransactionResourceCommitReplyMessage commit(CasualTransactionResourceCommitRequestMessage req) {
        queueStore.commit(req.getXid());
        return transactionCoordinator.commit(req);
    }

    @Override
    public CasualTransactionResourceRollbackReplyMessage rollback(CasualTransactionResourceRollbackRequestMessage req) {
        queueStore.rollback(req.getXid());
        return transactionCoordinator.rollback(req);
    }

    @Override
    public CasualEnqueueReplyMessage enqueue(CasualEnqueueRequestMessage req, ProtocolVersion version) {
        ImmutableRequest probeReq = CasualHttpMediation.buildQueueEnqueueProbeRequest(storePort.getBaseUrl(), req);
        CasualEnqueueReplyMessage reply = queueStore.enqueue(req, version);
        int statusCode = 200;
        String jsonPayload = CasualBufferUtils.extractPayload(
                req.getMessage() != null ? req.getMessage().getPayload() : null);
        ServeEvent event = CasualHttpMediation.buildQueueServeEvent(
                probeReq, statusCode, jsonPayload != null ? jsonPayload : "{}", req.getQueueName(), "enqueue");
        storePort.recordServeEvent(event);
        return reply;
    }

    @Override
    public CasualDequeueReplyMessage dequeue(CasualDequeueRequestMessage req, ProtocolVersion version) {
        ImmutableRequest probeReq = CasualHttpMediation.buildQueueDequeueProbeRequest(storePort.getBaseUrl(), req);
        CasualDequeueReplyMessage reply = queueStore.dequeue(req, version);
        int statusCode = 200;
        String body = "[]";
        if (reply.getMessages() != null && !reply.getMessages().isEmpty()) {
            body = CasualBufferUtils.extractPayload(reply.getMessages().get(0).getPayload());
        } else {
            statusCode = 204;
        }
        ServeEvent event = CasualHttpMediation.buildQueueServeEvent(
                probeReq, statusCode, body != null ? body : "[]", req.getQueueName(), "dequeue");
        storePort.recordServeEvent(event);
        return reply;
    }
}
