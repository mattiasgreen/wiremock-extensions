package com.github.mattiasgreen.wiremock.examples.banking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.transaction.xa.Xid;
import se.laz.casual.api.buffer.CasualBufferUtils;
import se.laz.casual.api.buffer.type.JsonBuffer;
import se.laz.casual.api.buffer.type.ServiceBuffer;
import se.laz.casual.api.flags.ErrorState;
import se.laz.casual.api.flags.TransactionState;
import se.laz.casual.api.xa.XAReturnCode;
import se.laz.casual.api.xa.XID;
import se.laz.casual.network.ProtocolVersion;
import se.laz.casual.network.messages.domain.TransactionType;
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
 * Node 3 (Server B — All-in-One Upstream Backend):
 * Hosts both the live HTTP backend services (Case Management API and CRM API)
 * and the live Casual XATMI + 2PC/XA Billing Platform server ({@code billing/charge}).
 */
public final class UpstreamBackendServer implements CasualTransactionHandler, AutoCloseable {

    private final HttpServer httpServer;
    private final CasualServer casualServer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicInteger caseApiCallCount = new AtomicInteger();
    private final AtomicInteger crmApiCallCount = new AtomicInteger();
    private final AtomicInteger billingCasualCallCount = new AtomicInteger();

    private final Set<Xid> activeBranchXids = ConcurrentHashMap.newKeySet();
    private final Set<Xid> preparedXids = ConcurrentHashMap.newKeySet();
    private final Set<Xid> committedXids = ConcurrentHashMap.newKeySet();
    private final Set<Xid> rolledBackXids = ConcurrentHashMap.newKeySet();

    private volatile boolean running;

    /**
     * Creates a new all-in-one upstream backend server with dynamic HTTP and Casual TCP ports.
     *
     * @throws IOException if the HTTP server cannot bind to an ephemeral port
     */
    public UpstreamBackendServer() throws IOException {
        this.httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.httpServer.setExecutor(Executors.newCachedThreadPool());
        registerHttpRoutes();

        this.casualServer = CasualServer.builder()
                .port(0)
                .domainId(UUID.randomUUID())
                .domainName("banking-upstream-server-b")
                .discoveryHandler(this::handleDiscovery)
                .serviceHandler(this::handleCasualServiceCall)
                .transactionHandler(this)
                .build();
    }

    /**
     * Starts both the HTTP backend server and the Casual TCP server.
     *
     * @throws InterruptedException if Casual server startup is interrupted
     */
    public synchronized void start() throws InterruptedException {
        if (running) {
            return;
        }
        httpServer.start();
        casualServer.start();
        running = true;
    }

    /**
     * Returns the bound HTTP port of Server B.
     *
     * @return the HTTP port
     */
    public int getHttpPort() {
        return httpServer.getAddress().getPort();
    }

    /**
     * Returns the base HTTP URL of Server B (e.g. {@code http://127.0.0.1:12345}).
     *
     * @return the base HTTP URL
     */
    public String getHttpBaseUrl() {
        return "http://127.0.0.1:" + getHttpPort();
    }

    /**
     * Returns the bound Casual TCP port of Server B.
     *
     * @return the Casual TCP port
     */
    public int getCasualPort() {
        return casualServer.getPort();
    }

    /**
     * Returns the total number of Case Management HTTP calls handled by Server B.
     *
     * @return Case API call count
     */
    public int getCaseApiCallCount() {
        return caseApiCallCount.get();
    }

    /**
     * Returns the total number of CRM HTTP calls handled by Server B.
     *
     * @return CRM API call count
     */
    public int getCrmApiCallCount() {
        return crmApiCallCount.get();
    }

    /**
     * Returns the total number of Billing Casual service calls handled by Server B.
     *
     * @return Billing Casual call count
     */
    public int getBillingCasualCallCount() {
        return billingCasualCallCount.get();
    }

    /**
     * Returns the set of XA transaction XIDs prepared on Server B.
     *
     * @return unmodifiable set of prepared XIDs
     */
    public Set<Xid> getPreparedXids() {
        return Collections.unmodifiableSet(preparedXids);
    }

    /**
     * Returns the set of XA transaction XIDs committed on Server B.
     *
     * @return unmodifiable set of committed XIDs
     */
    public Set<Xid> getCommittedXids() {
        return Collections.unmodifiableSet(committedXids);
    }

    /**
     * Returns the set of XA transaction XIDs rolled back on Server B.
     *
     * @return unmodifiable set of rolled-back XIDs
     */
    public Set<Xid> getRolledBackXids() {
        return Collections.unmodifiableSet(rolledBackXids);
    }

    /**
     * Returns whether Server B is currently running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running;
    }

    private void registerHttpRoutes() {
        // Case Management HTTP Endpoints
        httpServer.createContext("/api/cases", exchange -> {
            caseApiCallCount.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if ("POST".equalsIgnoreCase(method) && "/api/cases".equals(path)) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String productCode = "PLATINUM-CREDIT";
                String customerId = "CUST-42";
                try {
                    JsonNode n = objectMapper.readTree(body);
                    if (n.has("productCode")) {
                        productCode = n.path("productCode").asText("PLATINUM-CREDIT");
                    }
                    if (n.has("customerId")) {
                        customerId = n.path("customerId").asText("CUST-42");
                    }
                } catch (JsonProcessingException ignored) {
                }
                sendJson(
                        exchange,
                        200,
                        "{\"caseId\":\"CASE-1001\",\"customerId\":\"" + customerId + "\","
                                + "\"productCode\":\"" + productCode + "\",\"status\":\"APPLICATION_RECEIVED\","
                                + "\"processedBy\":\"upstream-server-B\"}");
            } else if ("POST".equalsIgnoreCase(method) && path.endsWith("/offer")) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                double annualFee = 299.00;
                int creditLimit = 25000;
                try {
                    JsonNode n = objectMapper.readTree(body);
                    if (n.has("annualFee")) {
                        annualFee = n.path("annualFee").asDouble(299.00);
                    }
                    if (n.has("creditLimit")) {
                        creditLimit = n.path("creditLimit").asInt(25000);
                    }
                } catch (JsonProcessingException ignored) {
                }
                sendJson(
                        exchange,
                        200,
                        "{\"caseId\":\"CASE-1001\",\"status\":\"OFFER_READY\","
                                + "\"annualFee\":" + annualFee + ",\"creditLimit\":" + creditLimit + ","
                                + "\"processedBy\":\"upstream-server-B\"}");
            } else if ("POST".equalsIgnoreCase(method) && path.endsWith("/complete")) {
                sendJson(
                        exchange,
                        200,
                        "{\"caseId\":\"CASE-1001\",\"status\":\"PURCHASED\","
                                + "\"processedBy\":\"upstream-server-B\"}");
            } else {
                sendJson(exchange, 404, "{\"error\":\"Unknown case route\"}");
            }
        });

        // CRM System HTTP Endpoints
        httpServer.createContext("/api/crm/customers/", exchange -> {
            crmApiCallCount.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();

            if ("GET".equalsIgnoreCase(method) && path.endsWith("/kyc")) {
                sendJson(
                        exchange,
                        200,
                        "{\"customerId\":\"CUST-42\",\"fullName\":\"Eleanor Vance\","
                                + "\"kycStatus\":\"VERIFIED\",\"riskTier\":\"LOW\","
                                + "\"processedBy\":\"upstream-server-B\"}");
            } else if ("GET".equalsIgnoreCase(method) && path.endsWith("/eligibility")) {
                sendJson(
                        exchange,
                        200,
                        "{\"customerId\":\"CUST-42\",\"doNotSell\":false,"
                                + "\"eligibleForPurchase\":true,\"segment\":\"PREMIER\","
                                + "\"processedBy\":\"upstream-server-B\"}");
            } else if ("POST".equalsIgnoreCase(method) && path.endsWith("/sales")) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String productCode = "PLATINUM-CREDIT";
                String customerId = "CUST-42";
                try {
                    JsonNode n = objectMapper.readTree(body);
                    if (n.has("productCode")) {
                        productCode = n.path("productCode").asText("PLATINUM-CREDIT");
                    }
                    if (n.has("customerId")) {
                        customerId = n.path("customerId").asText("CUST-42");
                    }
                } catch (JsonProcessingException ignored) {
                }
                sendJson(
                        exchange,
                        200,
                        "{\"customerId\":\"" + customerId + "\",\"salesRecordId\":\"SALE-5001\","
                                + "\"productCode\":\"" + productCode + "\",\"status\":\"RECORDED\","
                                + "\"processedBy\":\"upstream-server-B\"}");
            } else {
                sendJson(exchange, 404, "{\"error\":\"Unknown CRM route\"}");
            }
        });
    }

    private void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        String correlationId = exchange.getRequestHeaders().getFirst("X-Correlation-Id");
        if (correlationId != null && !correlationId.isBlank()) {
            exchange.getResponseHeaders().set("X-Correlation-Id", correlationId);
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private List<Service> handleDiscovery(List<String> serviceNames) {
        return serviceNames.stream()
                .map(name -> Service.of(name, "banking-billing", TransactionType.AUTOMATIC))
                .toList();
    }

    private CasualServiceCallReplyMessage handleCasualServiceCall(CasualServiceCallRequestMessage req) {
        billingCasualCallCount.incrementAndGet();
        if (req.getXid() != null && !XID.NULL_XID.equals(req.getXid())) {
            activeBranchXids.add(req.getXid());
        }

        String reqPayload = CasualBufferUtils.extractPayload(req.getServiceBuffer());
        double amount = 299.00;
        String productCode = "PLATINUM-CREDIT";
        String customerId = "CUST-42";
        try {
            if (reqPayload != null && !reqPayload.isBlank()) {
                JsonNode n = objectMapper.readTree(reqPayload);
                if (n.has("amount")) {
                    amount = n.path("amount").asDouble(299.00);
                }
                if (n.has("productCode")) {
                    productCode = n.path("productCode").asText("PLATINUM-CREDIT");
                }
                if (n.has("customerId")) {
                    customerId = n.path("customerId").asText("CUST-42");
                }
            }
        } catch (JsonProcessingException ignored) {
        }

        String replyJson = "{\"invoiceId\":\"INV-9001\",\"customerId\":\"" + customerId + "\","
                + "\"amount\":" + amount + ",\"currency\":\"EUR\",\"status\":\"CHARGED\","
                + "\"productCode\":\"" + productCode + "\","
                + "\"service\":\""
                + req.getServiceName()
                + "\",\"processedBy\":\"upstream-server-B\"}";

        return CasualServiceCallReplyMessage.createBuilder()
                .setProtocolVersion(ProtocolVersion.VERSION_1_0)
                .setExecution(req.getExecution())
                .setXid(req.getXid())
                .setError(ErrorState.OK)
                .setTransactionState(TransactionState.TX_ACTIVE)
                .setServiceBuffer(ServiceBuffer.of(JsonBuffer.of(replyJson)))
                .build();
    }

    @Override
    public CasualTransactionResourcePrepareReplyMessage prepare(CasualTransactionResourcePrepareRequestMessage req) {
        preparedXids.add(req.getXid());
        return CasualTransactionResourcePrepareReplyMessage.of(
                req.getExecution(), req.getXid(), req.getResourceId(), XAReturnCode.XA_OK);
    }

    @Override
    public CasualTransactionResourceCommitReplyMessage commit(CasualTransactionResourceCommitRequestMessage req) {
        committedXids.add(req.getXid());
        activeBranchXids.remove(req.getXid());
        return CasualTransactionResourceCommitReplyMessage.of(
                req.getExecution(), req.getXid(), req.getResourceId(), XAReturnCode.XA_OK);
    }

    @Override
    public CasualTransactionResourceRollbackReplyMessage rollback(CasualTransactionResourceRollbackRequestMessage req) {
        rolledBackXids.add(req.getXid());
        activeBranchXids.remove(req.getXid());
        return CasualTransactionResourceRollbackReplyMessage.of(
                req.getExecution(), req.getXid(), req.getResourceId(), XAReturnCode.XA_OK);
    }

    @Override
    public synchronized void close() {
        if (!running) {
            return;
        }
        running = false;
        httpServer.stop(0);
        casualServer.close();
    }
}
