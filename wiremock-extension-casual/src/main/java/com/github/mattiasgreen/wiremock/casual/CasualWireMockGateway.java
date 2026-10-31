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
import se.laz.casual.api.flags.ErrorState;
import se.laz.casual.config.ConfigurationOptions;
import se.laz.casual.config.ConfigurationService;
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

/**
 * In-process Casual Domain Gateway server for WireMock.
 * Listens on a TCP port and bridges Casual XATMI protocol messages
 * to WireMock stub definitions using {@link CasualHttpMediation}, {@link CasualStorePort},
 * and {@link CasualTransactionCoordinator}.
 */
public class CasualWireMockGateway implements AutoCloseable, CasualServer.TransactionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(CasualWireMockGateway.class);

    static {
        ConfigurationService.setConfiguration(ConfigurationOptions.CASUAL_OUTBOUND_UNMANAGED, true);
        ConfigurationService.setConfiguration(ConfigurationOptions.CASUAL_UNMANAGED, true);
    }

    private final int port;
    private final CasualStorePort storePort;
    private final int upstreamTimeoutSeconds;
    private final UUID domainId;
    private final String domainName;

    private final ConcurrentHashMap<String, CasualClient> upstreamClients = new ConcurrentHashMap<>();
    private final CasualTransactionCoordinator transactionCoordinator = new CasualTransactionCoordinator();

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
        this.port = port;
        this.storePort = storePort != null ? storePort : new WireMockStoreAdapter(null, null, "http://localhost:8080");
        this.upstreamTimeoutSeconds = upstreamTimeoutSeconds > 0 ? upstreamTimeoutSeconds : resolveDefaultTimeout();
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

        public CasualWireMockGateway build() {
            CasualStorePort resolvedStore = storePort;
            if (resolvedStore == null) {
                resolvedStore = new WireMockStoreAdapter(wireMockServer, null, wireMockBaseUrl);
            }
            return new CasualWireMockGateway(port, resolvedStore, upstreamTimeoutSeconds);
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

    public CasualTransactionCoordinator getTransactionCoordinator() {
        return transactionCoordinator;
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
                LOG.debug(
                        "[CASUAL-GATEWAY-PROXY] Service [{}] matched proxy stub [{}] -> pure TCP forwarding to {}",
                        serviceName,
                        stub.getId(),
                        targetUrl);
                return executePureCasualProxy(req, stub, wiremockRequest, targetUrl);
            }

            // Branch B: Matched an Offline Mock Stub
            LOG.debug("[CASUAL-GATEWAY-MOCK] Service [{}] matched offline mock stub [{}]", serviceName, stub.getId());
            CasualHttpMediation.ExecutionResult result =
                    CasualHttpMediation.translateOfflineStub(req, stub, wiremockRequest, storePort);
            transactionCoordinator.recordLocalCall(
                    req.getXid(), result.prepareVote(), result.commitVote(), result.rollbackVote());
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
        try {
            URI uri = URI.create(targetUrl);
            CasualClient client = getOrCreateUpstreamClient(uri.getHost(), uri.getPort());

            if (transactionCoordinator.isTransactional(req.getXid())) {
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
                    req.getServiceName(),
                    targetUrl,
                    e.getMessage(),
                    e);
            return CasualHttpMediation.buildErrorReply(req, ErrorState.TPESVCERR);
        }
    }

    private List<Service> handleDiscovery(List<String> requestedNames) {
        Map<String, Service> aggregated = new LinkedHashMap<>();

        Set<String> upstreamTargets = new LinkedHashSet<>();
        Set<String> localStubNames = new LinkedHashSet<>();

        for (StubMapping stub : storePort.getActiveStubMappings()) {
            if (isCasualProxyStub(stub)) {
                String target = extractCasualTargetUrl(stub);
                if (target != null && target.startsWith("casual://")) {
                    upstreamTargets.add(target);
                }
            } else {
                extractLocalStubServiceName(stub).ifPresent(localStubNames::add);
            }
        }

        for (String targetUrl : upstreamTargets) {
            try {
                URI uri = URI.create(targetUrl);
                CasualClient client = getOrCreateUpstreamClient(uri.getHost(), uri.getPort());
                List<String> namesToQuery = requestedNames != null ? requestedNames : Collections.emptyList();
                CasualDomainDiscoveryReplyMessage upstreamReply = client.discover(namesToQuery);
                if (upstreamReply != null && upstreamReply.getServices() != null) {
                    for (Service s : upstreamReply.getServices()) {
                        aggregated.put(s.getName(), s);
                    }
                }
            } catch (Exception e) {
                LOG.warn(
                        "[CASUAL-GATEWAY-DISCOVERY] Failed to query upstream [{}] for domain discovery: {}",
                        targetUrl,
                        e.getMessage());
            }
        }

        for (String stubName : localStubNames) {
            if (!aggregated.containsKey(stubName)) {
                aggregated.put(stubName, Service.of(stubName, "wiremock-stub", TransactionType.AUTOMATIC));
            }
        }

        if (requestedNames != null && !requestedNames.isEmpty()) {
            for (String name : requestedNames) {
                if (!aggregated.containsKey(name)) {
                    aggregated.put(name, Service.of(name, "wiremock-category", TransactionType.AUTOMATIC));
                }
            }
        }

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

    @Override
    public CasualTransactionResourcePrepareReplyMessage prepare(CasualTransactionResourcePrepareRequestMessage req) {
        return transactionCoordinator.prepare(req);
    }

    @Override
    public CasualTransactionResourceCommitReplyMessage commit(CasualTransactionResourceCommitRequestMessage req) {
        return transactionCoordinator.commit(req);
    }

    @Override
    public CasualTransactionResourceRollbackReplyMessage rollback(CasualTransactionResourceRollbackRequestMessage req) {
        return transactionCoordinator.rollback(req);
    }
}
