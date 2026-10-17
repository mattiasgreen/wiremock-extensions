package com.github.mattiasgreen.wiremock.examples.banking;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.transaction.xa.Xid;
import se.laz.casual.api.buffer.CasualBufferUtils;
import se.laz.casual.api.flags.ErrorState;
import se.laz.casual.api.flags.TransactionState;
import se.laz.casual.api.xa.XAReturnCode;
import se.laz.casual.api.xa.XID;
import se.laz.casual.client.CasualTcpClient;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceCommitReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourcePrepareReplyMessage;

/**
 * Node 1 (Server A — Customer Portal &amp; Product System SUT):
 * Hosts the Banking Customer Portal Web UI ({@code /portal}) and the Product System
 * orchestrator that coordinates downstream calls across:
 * <ul>
 *   <li><b>Case Management (HTTP)</b>: {@code POST /api/cases}, {@code POST /api/cases/{id}/offer},
 *       {@code POST /api/cases/{id}/complete}</li>
 *   <li><b>CRM System (HTTP)</b>: {@code GET /api/crm/customers/{id}/kyc},
 *       {@code GET /api/crm/customers/{id}/eligibility}, {@code POST /api/crm/customers/{id}/sales}</li>
 *   <li><b>Billing Platform (Casual XATMI + 2PC/XA)</b>: {@code tpcall("billing/charge")} inside a
 *       global XA transaction ({@link Xid}) followed by 2PC {@code prepare} and {@code commit}</li>
 * </ul>
 */
public final class ProductPortalServer implements AutoCloseable {

    private static final int BILLING_XA_RESOURCE_ID = 201;

    private final String wireMockBaseUrl;
    private final HttpServer portalServer;
    private final HttpClient httpClient;
    private final CasualTcpClient casualClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger txSequence = new AtomicInteger(1);

    private volatile boolean running;

    /**
     * Outcome of Phase 1 (Product Application &amp; Automated Offer Orchestration).
     *
     * @param caseId created Case ID
     * @param customerId customer identifier
     * @param productCode applied banking product code
     * @param kycStatus CRM KYC status
     * @param eligible whether customer passed CRM do-not-sell and eligibility checks
     * @param annualFee calculated annual fee
     * @param creditLimit calculated credit limit
     * @param caseStatus updated Case status ({@code OFFER_READY})
     */
    public record ApplicationOfferResult(
            String caseId,
            String customerId,
            String productCode,
            String kycStatus,
            boolean eligible,
            double annualFee,
            int creditLimit,
            String caseStatus) {}

    /**
     * Outcome of Phase 2 (Customer Offer Approval, Casual 2PC Billing &amp; Purchase Completion).
     *
     * @param caseId completed Case ID
     * @param customerId customer identifier
     * @param productCode purchased banking product code
     * @param invoiceId Billing Platform invoice ID
     * @param billedAmount billed amount
     * @param xaCommitted whether the 2PC XA transaction prepared and committed with {@code XA_OK}
     * @param caseStatus final Case status ({@code PURCHASED})
     * @param salesRecordId CRM sales record ID
     */
    public record PurchaseConfirmationResult(
            String caseId,
            String customerId,
            String productCode,
            String invoiceId,
            double billedAmount,
            boolean xaCommitted,
            String caseStatus,
            String salesRecordId) {}

    /**
     * Creates Node 1 (Product Portal Server) pointing all outgoing HTTP and Casual traffic
     * through Node 2 (WireMock MitM).
     *
     * @param wireMockBaseUrl base HTTP URL of WireMock
     * @param casualGatewayHost host of {@code CasualWireMockGateway}
     * @param casualGatewayPort TCP port of {@code CasualWireMockGateway}
     * @throws IOException if the portal HTTP server cannot bind to a dynamic port
     */
    public ProductPortalServer(String wireMockBaseUrl, String casualGatewayHost, int casualGatewayPort)
            throws IOException {
        this.wireMockBaseUrl = wireMockBaseUrl.replaceAll("/+$", "");
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.casualClient = new CasualTcpClient(
                casualGatewayHost, casualGatewayPort, UUID.randomUUID(), "banking-product-portal-domain");
        this.portalServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.portalServer.setExecutor(Executors.newCachedThreadPool());
        registerPortalRoutes();
    }

    /**
     * Starts the Customer Portal HTTP server and connects the Casual TCP client to the gateway.
     *
     * @throws Exception if startup or Casual handshake fails
     */
    public synchronized void start() throws Exception {
        if (running) {
            return;
        }
        casualClient.connect();
        portalServer.start();
        running = true;
    }

    /**
     * Returns the bound HTTP port of the Customer Portal server.
     *
     * @return portal HTTP port
     */
    public int getPort() {
        return portalServer.getAddress().getPort();
    }

    /**
     * Returns the Customer Portal UI URL (e.g. {@code http://127.0.0.1:12345/portal}).
     *
     * @return portal UI URL
     */
    public String getPortalUrl() {
        return "http://127.0.0.1:" + getPort() + "/portal";
    }

    /**
     * Executes Phase 1 of the scenario:
     * <ol>
     *   <li>Customer applies for a banking product.</li>
     *   <li>Case is created in Case Management with the application stored ({@code POST /api/cases}).</li>
     *   <li>Product System orchestrates CRM KYC check ({@code GET /api/crm/customers/{id}/kyc}).</li>
     *   <li>Product System orchestrates CRM eligibility &amp; do-not-sell check
     *       ({@code GET /api/crm/customers/{id}/eligibility}).</li>
     *   <li>Product System calculates price/offer and updates Case ({@code POST /api/cases/{caseId}/offer}).</li>
     * </ol>
     *
     * @param customerId customer ID (e.g. {@code CUST-42})
     * @param productCode product code (e.g. {@code PLATINUM-CREDIT})
     * @return the resulting {@link ApplicationOfferResult}
     * @throws Exception if any downstream call fails
     */
    public ApplicationOfferResult submitApplication(String customerId, String productCode) throws Exception {
        // 1. Create Case with application stored
        JsonNode createdCase = httpPostJson(
                "/api/cases", Map.of("customerId", customerId, "productCode", productCode, "channel", "WEB_PORTAL"));
        String caseId = createdCase.path("caseId").asText("CASE-1001");

        // 2. CRM KYC check
        JsonNode kycNode = httpGetJson("/api/crm/customers/" + customerId + "/kyc");
        String kycStatus = kycNode.path("kycStatus").asText("UNKNOWN");
        if (!"VERIFIED".equalsIgnoreCase(kycStatus)) {
            throw new IllegalStateException("Customer KYC not verified: " + kycStatus);
        }

        // 3. CRM Do-Not-Sell & Purchase Eligibility check
        JsonNode eligNode = httpGetJson("/api/crm/customers/" + customerId + "/eligibility");
        boolean doNotSell = eligNode.path("doNotSell").asBoolean(true);
        boolean eligible = eligNode.path("eligibleForPurchase").asBoolean(false);
        if (doNotSell || !eligible) {
            throw new IllegalStateException("Customer is not eligible for purchase");
        }

        // 4. Calculate offer and store on Case
        double annualFee = 299.00;
        int creditLimit = 25000;
        JsonNode offerCase = httpPostJson(
                "/api/cases/" + caseId + "/offer",
                Map.of("caseId", caseId, "annualFee", annualFee, "creditLimit", creditLimit));

        return new ApplicationOfferResult(
                caseId,
                customerId,
                productCode,
                kycStatus,
                true,
                offerCase.path("annualFee").asDouble(annualFee),
                offerCase.path("creditLimit").asInt(creditLimit),
                offerCase.path("status").asText("OFFER_READY"));
    }

    /**
     * Executes Phase 2 of the scenario:
     * <ol>
     *   <li>Customer approves purchase order.</li>
     *   <li>Customer is billed via Billing Platform over Casual XATMI + 2PC/XA
     *       ({@code tpcall("billing/charge")} + {@code prepare} + {@code commit}).</li>
     *   <li>Product is marked purchased in Case Management ({@code POST /api/cases/{caseId}/complete}).</li>
     *   <li>CRM is updated with sales info ({@code POST /api/crm/customers/{id}/sales}).</li>
     * </ol>
     *
     * @param caseId case ID from Phase 1
     * @param customerId customer ID
     * @param productCode product code
     * @param amount offer amount to bill
     * @return the resulting {@link PurchaseConfirmationResult}
     * @throws Exception if billing 2PC or HTTP orchestration fails
     */
    public PurchaseConfirmationResult approvePurchase(
            String caseId, String customerId, String productCode, double amount) throws Exception {
        // 1. Bill customer over Casual XATMI inside a 2PC XA Transaction
        int seq = txSequence.getAndIncrement();
        Xid xid = XID.of(
                ("gtrid-banking-" + seq).getBytes(StandardCharsets.UTF_8),
                ("bqual-banking-" + seq).getBytes(StandardCharsets.UTF_8),
                42);

        String billingPayload = objectMapper.writeValueAsString(Map.of(
                "caseId", caseId,
                "customerId", customerId,
                "productCode", productCode,
                "amount", amount,
                "currency", "EUR"));

        CasualServiceCallReplyMessage callReply = casualClient.tpcall("billing/charge", billingPayload, xid);
        if (callReply.getError() != ErrorState.OK || callReply.getTransactionState() != TransactionState.TX_ACTIVE) {
            casualClient.rollback(xid, BILLING_XA_RESOURCE_ID);
            throw new IllegalStateException("Casual billing/charge failed: " + callReply.getError());
        }

        CasualTransactionResourcePrepareReplyMessage prepReply = casualClient.prepare(xid, BILLING_XA_RESOURCE_ID);
        if (prepReply.getTransactionReturnCode() != XAReturnCode.XA_OK
                && prepReply.getTransactionReturnCode() != XAReturnCode.XA_RDONLY) {
            throw new IllegalStateException("Casual 2PC prepare failed: " + prepReply.getTransactionReturnCode());
        }

        CasualTransactionResourceCommitReplyMessage commitReply =
                casualClient.commit(xid, BILLING_XA_RESOURCE_ID, false);
        boolean xaCommitted = commitReply.getTransactionReturnCode() == XAReturnCode.XA_OK;
        if (!xaCommitted) {
            throw new IllegalStateException("Casual 2PC commit failed: " + commitReply.getTransactionReturnCode());
        }

        String billingReplyStr = CasualBufferUtils.extractPayload(callReply.getServiceBuffer());
        JsonNode billingJson = objectMapper.readTree(billingReplyStr);
        String invoiceId = billingJson.path("invoiceId").asText("INV-9001");

        // 2. Complete Case in Case Management
        JsonNode completedCase = httpPostJson(
                "/api/cases/" + caseId + "/complete",
                Map.of("caseId", caseId, "invoiceId", invoiceId, "status", "PURCHASED"));
        String caseStatus = completedCase.path("status").asText("PURCHASED");

        // 3. Update CRM with sales info
        JsonNode crmSales = httpPostJson(
                "/api/crm/customers/" + customerId + "/sales",
                Map.of(
                        "customerId", customerId,
                        "caseId", caseId,
                        "invoiceId", invoiceId,
                        "productCode", productCode,
                        "amount", amount));
        String salesRecordId = crmSales.path("salesRecordId").asText("SALE-5001");

        return new PurchaseConfirmationResult(
                caseId, customerId, productCode, invoiceId, amount, true, caseStatus, salesRecordId);
    }

    private JsonNode httpGetJson(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(wireMockBaseUrl + path))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("HTTP GET " + path + " returned " + resp.statusCode());
        }
        return objectMapper.readTree(resp.body());
    }

    private JsonNode httpPostJson(String path, Object payload) throws Exception {
        String json = objectMapper.writeValueAsString(payload);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(wireMockBaseUrl + path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("HTTP POST " + path + " returned " + resp.statusCode());
        }
        return objectMapper.readTree(resp.body());
    }

    private void registerPortalRoutes() {
        portalServer.createContext("/portal", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("/portal".equals(path) || "/portal/".equals(path)) {
                byte[] html = buildPortalHtml().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, html.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(html);
                }
            } else if ("/portal/api/apply".equals(path) && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                try {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    JsonNode node = body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
                    String customerId = node.path("customerId").asText("CUST-42");
                    String productCode = node.path("productCode").asText("PLATINUM-CREDIT");
                    ApplicationOfferResult res = submitApplication(customerId, productCode);
                    sendJsonResponse(exchange, 200, res);
                } catch (Exception e) {
                    sendJsonResponse(exchange, 500, Map.of("error", String.valueOf(e.getMessage())));
                }
            } else if ("/portal/api/approve".equals(path) && "POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                try {
                    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    JsonNode node = body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
                    String caseId = node.path("caseId").asText("CASE-1001");
                    String customerId = node.path("customerId").asText("CUST-42");
                    String productCode = node.path("productCode").asText("PLATINUM-CREDIT");
                    double amount = node.path("amount").asDouble(299.00);
                    PurchaseConfirmationResult res = approvePurchase(caseId, customerId, productCode, amount);
                    sendJsonResponse(exchange, 200, res);
                } catch (Exception e) {
                    sendJsonResponse(exchange, 500, Map.of("error", String.valueOf(e.getMessage())));
                }
            } else {
                sendJsonResponse(exchange, 404, Map.of("error", "Not found"));
            }
        });
    }

    private void sendJsonResponse(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(payload);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String buildPortalHtml() {
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8">
                  <title>Nordic Retail Bank — Product Portal</title>
                  <style>
                    :root {
                      --bg: #0f172a;
                      --card: #1e293b;
                      --border: #334155;
                      --text: #f8fafc;
                      --muted: #94a3b8;
                      --accent: #3b82f6;
                      --success: #10b981;
                      --teal: #0d9488;
                    }
                    * { box-sizing: border-box; font-family: system-ui, -apple-system, sans-serif; }
                    body { margin: 0; background: var(--bg); color: var(--text); padding: 28px; }
                    .container { max-width: 860px; margin: 0 auto; }
                    .header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; padding-bottom: 16px; border-bottom: 1px solid var(--border); }
                    .brand { font-size: 1.35rem; font-weight: 700; display: flex; align-items: center; gap: 10px; }
                    .customer-pill { background: var(--card); border: 1px solid var(--border); padding: 6px 14px; border-radius: 999px; font-size: 0.85rem; color: var(--muted); }
                    .card { background: var(--card); border: 1px solid var(--border); border-radius: 12px; padding: 20px; margin-bottom: 20px; }
                    .card-title { font-size: 1.1rem; font-weight: 600; margin: 0 0 8px 0; }
                    .card-sub { color: var(--muted); font-size: 0.9rem; margin-bottom: 16px; }
                    .btn { border: none; border-radius: 8px; padding: 10px 18px; font-size: 0.95rem; font-weight: 600; cursor: pointer; color: white; background: var(--accent); }
                    .btn-success { background: var(--success); }
                    .btn-secondary { background: #475569; }
                    .btn:disabled { opacity: 0.5; cursor: not-allowed; }
                    .hidden { display: none !important; }
                    .steps { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; margin: 14px 0; }
                    .step-item { background: #0f172a; border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; font-size: 0.85rem; }
                    .badge { display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 0.75rem; font-weight: 700; margin-left: 6px; }
                    .badge-http { background: #2563eb; color: white; }
                    .badge-casual { background: var(--teal); color: white; }
                    .badge-ok { background: var(--success); color: white; }
                  </style>
                </head>
                <body>
                  <div class="container">
                    <div class="header">
                      <div class="brand">🏦 Nordic Retail Bank — Product Portal</div>
                      <div class="customer-pill" id="portal-customer-pill">👤 Eleanor Vance (CUST-42)</div>
                    </div>

                    <div class="card" id="application-card">
                      <div class="card-title">Platinum Credit Line</div>
                      <div class="card-sub">Instant KYC &amp; eligibility verification with €25,000 revolving credit limit.</div>
                      <button id="btn-submit-application" class="btn" data-testid="btn-submit-application">1. Submit Product Application</button>
                    </div>

                    <div class="card hidden" id="offer-panel" data-testid="offer-panel">
                      <div class="card-title">✅ Application Approved — Personalized Offer Ready</div>
                      <div class="card-sub" id="offer-summary-text">Case CASE-1001 · Annual Fee: €299.00 · Credit Limit: €25,000</div>
                      <div class="steps">
                        <div class="step-item">📋 Case Created: <strong id="offer-case-id">CASE-1001</strong> <span class="badge badge-http">HTTP</span></div>
                        <div class="step-item">🛡️ CRM KYC Status: <strong id="offer-kyc-status">VERIFIED</strong> <span class="badge badge-http">HTTP</span></div>
                        <div class="step-item">✔️ CRM Eligibility: <strong id="offer-eligibility">ELIGIBLE</strong> <span class="badge badge-http">HTTP</span></div>
                        <div class="step-item">💶 Offer Stored: <strong id="offer-case-status">OFFER_READY</strong> <span class="badge badge-http">HTTP</span></div>
                      </div>
                      <button id="btn-approve-purchase" class="btn btn-success" data-testid="btn-approve-purchase">2. Approve Offer &amp; Purchase (€299.00)</button>
                    </div>

                    <div class="card hidden" id="purchase-confirmation" data-testid="purchase-confirmation">
                      <div class="card-title">🎉 Purchase Confirmed &amp; Provisioned</div>
                      <div class="card-sub">All downstream HTTP and Casual 2PC XA transactions completed.</div>
                      <div class="steps">
                        <div class="step-item">🧾 Billing Invoice: <strong id="conf-invoice-id">INV-9001</strong> <span class="badge badge-casual">CASUAL 2PC</span></div>
                        <div class="step-item">🔒 2PC XA Commit: <strong id="conf-xa-status">XA_OK</strong> <span class="badge badge-ok">COMMITTED</span></div>
                        <div class="step-item">📁 Case Status: <strong id="conf-case-status">PURCHASED</strong> <span class="badge badge-http">HTTP</span></div>
                        <div class="step-item">📈 CRM Sales Record: <strong id="conf-sales-id">SALE-5001</strong> <span class="badge badge-http">HTTP</span></div>
                      </div>
                      <button id="btn-reset-portal" class="btn btn-secondary" data-testid="btn-reset-portal">↻ Start New Application</button>
                    </div>
                  </div>

                  <script>
                    let currentCaseId = 'CASE-1001';
                    const btnApply = document.getElementById('btn-submit-application');
                    const offerPanel = document.getElementById('offer-panel');
                    const btnApprove = document.getElementById('btn-approve-purchase');
                    const confPanel = document.getElementById('purchase-confirmation');
                    const btnReset = document.getElementById('btn-reset-portal');

                    btnApply.addEventListener('click', async () => {
                      btnApply.disabled = true;
                      const res = await fetch('/portal/api/apply', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ customerId: 'CUST-42', productCode: 'PLATINUM-CREDIT' })
                      });
                      const data = await res.json();
                      currentCaseId = data.caseId;
                      document.getElementById('offer-case-id').textContent = data.caseId;
                      document.getElementById('offer-kyc-status').textContent = data.kycStatus;
                      document.getElementById('offer-eligibility').textContent = data.eligible ? 'ELIGIBLE' : 'BLOCKED';
                      document.getElementById('offer-case-status').textContent = data.caseStatus;
                      offerPanel.classList.remove('hidden');
                    });

                    btnApprove.addEventListener('click', async () => {
                      btnApprove.disabled = true;
                      const res = await fetch('/portal/api/approve', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                          caseId: currentCaseId,
                          customerId: 'CUST-42',
                          productCode: 'PLATINUM-CREDIT',
                          amount: 299.00
                        })
                      });
                      const data = await res.json();
                      document.getElementById('conf-invoice-id').textContent = data.invoiceId;
                      document.getElementById('conf-xa-status').textContent = data.xaCommitted ? 'XA_OK' : 'ROLLED_BACK';
                      document.getElementById('conf-case-status').textContent = data.caseStatus;
                      document.getElementById('conf-sales-id').textContent = data.salesRecordId;
                      confPanel.classList.remove('hidden');
                    });

                    btnReset.addEventListener('click', () => {
                      offerPanel.classList.add('hidden');
                      confPanel.classList.add('hidden');
                      btnApply.disabled = false;
                      btnApprove.disabled = false;
                    });
                  </script>
                </body>
                </html>
                """;
    }

    @Override
    public synchronized void close() {
        if (!running) {
            return;
        }
        running = false;
        portalServer.stop(0);
        casualClient.close();
    }
}
