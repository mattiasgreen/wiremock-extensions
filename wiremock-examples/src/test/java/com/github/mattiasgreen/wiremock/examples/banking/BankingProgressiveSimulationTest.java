package com.github.mattiasgreen.wiremock.examples.banking;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.examples.banking.ProductPortalServer.ApplicationOfferResult;
import com.github.mattiasgreen.wiremock.examples.banking.ProductPortalServer.PurchaseConfirmationResult;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Headless 3-node JUnit integration test verifying the end-to-end Banking Product
 * progressive simulation lifecycle across HTTP (Case Management + CRM) and Casual 2PC (Billing):
 * <ol>
 *   <li><b>Run 1 (Fully Integrated / {@code LIVE PROXY})</b>: Executes the 10-step banking product
 *       application and purchase flow against live Server B and verifies all 7 downstream calls
 *       (3 Case HTTP, 3 CRM HTTP, 1 Billing Casual 2PC) are recorded as {@code proxy} in the
 *       WireMock Request Journal.</li>
 *   <li><b>Snapshot &amp; Flip to {@code STUBS MODE}</b>: Invokes the UI lifecycle endpoints
 *       ({@code /__admin/projects/_all/recordings/snapshot} and {@code /__admin/projects/_all/mode})
 *       and shuts down Server B completely.</li>
 *   <li><b>Run 2 (Fully Simulated / {@code STUBS MODE})</b>: Re-runs the exact same 10-step scenario
 *       with Server B offline and verifies all HTTP and Casual 2PC calls succeed from recorded stubs.</li>
 * </ol>
 */
class BankingProgressiveSimulationTest {

    @Test
    @DisplayName(
            "End-to-End Multi-Protocol Progressive Simulation: Run 1 (Live Proxy) -> Snapshot & Flip -> Stop Server B -> Run 2 (Stubbed Mode) across JsonBuffer and FieldedBuffer (FML)")
    void shouldTransitionBankingScenarioFromLiveProxyToStubbedModeAcrossHttpAndCasual2pc() throws Exception {
        try (BankingSimulationHarness harness = new BankingSimulationHarness()) {
            ProductPortalServer portal = harness.getProductPortal();
            UpstreamBackendServer serverB = harness.getUpstreamBackend();
            HttpClient httpClient = HttpClient.newHttpClient();
            String wmBase = harness.getWireMockServer().baseUrl();

            // =========================================================================
            // PHASE 1: Fully Integrated Run 1 (LIVE PROXY Mode, Server B Online)
            // =========================================================================

            // Flow A: Modern Product using standard JsonBuffer (PLATINUM-CREDIT)
            ApplicationOfferResult offer1 = portal.submitApplication("CUST-42", "PLATINUM-CREDIT");
            assertThat(offer1.caseId()).isEqualTo("CASE-1001");
            assertThat(offer1.kycStatus()).isEqualTo("VERIFIED");
            assertThat(offer1.eligible()).isTrue();
            assertThat(offer1.annualFee()).isEqualTo(299.00);
            assertThat(offer1.caseStatus()).isEqualTo("OFFER_READY");

            PurchaseConfirmationResult purchase1 = portal.approvePurchase(
                    offer1.caseId(),
                    "CUST-42",
                    "PLATINUM-CREDIT",
                    offer1.annualFee(),
                    "BANK-TX-CASE-1001",
                    "NONE",
                    "JSON");
            assertThat(purchase1.invoiceId()).isEqualTo("INV-9001");
            assertThat(purchase1.xaCommitted()).isTrue();
            assertThat(purchase1.caseStatus()).isEqualTo("PURCHASED");
            assertThat(purchase1.salesRecordId()).isEqualTo("SALE-5001");

            // Flow B: Core-Banking Legacy Product using FieldedBuffer with FML_ARCHIVE_JSON (GREEN-AUTO-LOAN)
            ApplicationOfferResult offerGreen =
                    portal.submitApplication("CUST-99", ProductPortalServer.ProductCatalog.GREEN_AUTO_LOAN);
            assertThat(offerGreen.caseId()).isEqualTo("CASE-1002");
            assertThat(offerGreen.kycStatus()).isEqualTo("VERIFIED");
            assertThat(offerGreen.eligible()).isTrue();
            assertThat(offerGreen.annualFee()).isEqualTo(350.00);
            assertThat(offerGreen.caseStatus()).isEqualTo("OFFER_READY");

            PurchaseConfirmationResult purchaseGreen = portal.approvePurchase(
                    offerGreen.caseId(),
                    "CUST-99",
                    ProductPortalServer.ProductCatalog.GREEN_AUTO_LOAN,
                    offerGreen.annualFee(),
                    "BANK-TX-CASE-1002",
                    "NONE",
                    "FIELDED");
            assertThat(purchaseGreen.invoiceId()).isEqualTo("INV-9002");
            assertThat(purchaseGreen.xaCommitted()).isTrue();
            assertThat(purchaseGreen.caseStatus()).isEqualTo("PURCHASED");
            assertThat(purchaseGreen.salesRecordId()).isEqualTo("SALE-5002");

            // Verify Server B actually processed all live HTTP and Casual 2PC calls across both buffer types
            assertThat(serverB.getCaseApiCallCount()).isEqualTo(6);
            assertThat(serverB.getCrmApiCallCount()).isEqualTo(6);
            assertThat(serverB.getBillingCasualCallCount()).isEqualTo(2);
            assertThat(serverB.getPreparedXids()).hasSize(2);
            assertThat(serverB.getCommittedXids()).hasSize(2);

            // Verify WireMock Request Journal recorded all 14 calls as matched proxy events
            List<ServeEvent> run1Events = harness.getWireMockServer().getAllServeEvents();
            assertThat(run1Events).hasSize(14);
            assertThat(run1Events).allSatisfy(event -> {
                assertThat(event.getWasMatched()).isTrue();
                assertThat(event.getStubMapping().getMetadata().getString("mode"))
                        .isEqualTo("proxy");
                assertThat(event.getResponse().getStatus()).isEqualTo(200);
                assertThat(event.getResponse().getBodyAsString()).contains("upstream-server-B");
            });

            // Verify HTTP calls carry distributed trace correlation ID and W3C traceparent
            assertThat(run1Events.stream().filter(e -> !e.getRequest().getUrl().startsWith("/__casual/")))
                    .allSatisfy(event -> {
                        assertThat(event.getRequest().getHeader("X-Correlation-Id"))
                                .startsWith("BANK-TX-");
                        assertThat(event.getRequest().getHeader("traceparent")).isNotBlank();
                    });

            // =========================================================================
            // PHASE 2: Snapshot Recordings & Flip to STUBS MODE, then Stop Server B
            // =========================================================================
            HttpResponse<String> snapshotResp = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(wmBase + "/__admin/projects/_all/recordings/snapshot"))
                            .POST(HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(snapshotResp.statusCode()).isEqualTo(200);
            assertThat(snapshotResp.body()).containsIgnoringWhitespaces("\"totalRecorded\":14");

            HttpResponse<String> modeResp = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(wmBase + "/__admin/projects/_all/mode"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"stubs\"}", StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertThat(modeResp.statusCode()).isEqualTo(200);
            assertThat(modeResp.body()).containsIgnoringWhitespaces("\"activeMode\":\"stubs\"");

            // Clear Request Journal before Run 2 and shut down Server B completely!
            harness.getWireMockServer().resetRequests();
            harness.stopUpstreamBackend();
            assertThat(serverB.isRunning()).isFalse();

            // =========================================================================
            // PHASE 3: Fully Simulated Run 2 (STUBS MODE, Server B Offline)
            // =========================================================================

            // Flow A: Replay Modern Product (JsonBuffer)
            ApplicationOfferResult offer2 = portal.submitApplication("CUST-42", "PLATINUM-CREDIT");
            assertThat(offer2).isEqualTo(offer1);

            PurchaseConfirmationResult purchase2 = portal.approvePurchase(
                    offer2.caseId(),
                    "CUST-42",
                    "PLATINUM-CREDIT",
                    offer2.annualFee(),
                    "BANK-TX-CASE-1001",
                    "NONE",
                    "JSON");
            assertThat(purchase2).isEqualTo(purchase1);

            // Flow B: Replay Core-Banking Legacy Product (FieldedBuffer with FML_ARCHIVE_JSON)
            ApplicationOfferResult offerGreen2 =
                    portal.submitApplication("CUST-99", ProductPortalServer.ProductCatalog.GREEN_AUTO_LOAN);
            assertThat(offerGreen2).isEqualTo(offerGreen);

            PurchaseConfirmationResult purchaseGreen2 = portal.approvePurchase(
                    offerGreen2.caseId(),
                    "CUST-99",
                    ProductPortalServer.ProductCatalog.GREEN_AUTO_LOAN,
                    offerGreen2.annualFee(),
                    "BANK-TX-CASE-1002",
                    "NONE",
                    "FIELDED");
            assertThat(purchaseGreen2).isEqualTo(purchaseGreen);

            // Server B hit counts remain unchanged because Server B was completely offline
            assertThat(serverB.getCaseApiCallCount()).isEqualTo(6);
            assertThat(serverB.getCrmApiCallCount()).isEqualTo(6);
            assertThat(serverB.getBillingCasualCallCount()).isEqualTo(2);
            assertThat(serverB.getCommittedXids()).hasSize(2);

            // Verify WireMock Request Journal shows all 14 calls served from static recorded stubs
            List<ServeEvent> run2Events = harness.getWireMockServer().getAllServeEvents();
            assertThat(run2Events).hasSize(14);
            assertThat(run2Events).allSatisfy(event -> {
                assertThat(event.getWasMatched()).isTrue();
                assertThat(event.getStubMapping().getMetadata().getString("mode"))
                        .isEqualTo("static");
                assertThat(event.getStubMapping().getMetadata().getString("source"))
                        .isEqualTo("recorded-proxy");
                assertThat(event.getResponse().getStatus()).isEqualTo(200);
            });
        }
    }

    @Test
    @DisplayName("Product Catalog: Orchestrate Green Energy Auto Loan with Dynamic Pricing & 2PC Billing")
    void shouldOrchestrateGreenAutoLoanWithDynamicCatalogPricing() throws Exception {
        try (BankingSimulationHarness harness = new BankingSimulationHarness()) {
            ProductPortalServer portal = harness.getProductPortal();

            ApplicationOfferResult offer =
                    portal.submitApplication("CUST-99", ProductPortalServer.ProductCatalog.GREEN_AUTO_LOAN);
            assertThat(offer.productCode()).isEqualTo(ProductPortalServer.ProductCatalog.GREEN_AUTO_LOAN);
            assertThat(offer.annualFee()).isEqualTo(350.00);
            assertThat(offer.creditLimit()).isEqualTo(35000);
            assertThat(offer.kycStatus()).isEqualTo("VERIFIED");
            assertThat(offer.eligible()).isTrue();

            PurchaseConfirmationResult purchase = portal.approvePurchase(
                    offer.caseId(), "CUST-99", ProductPortalServer.ProductCatalog.GREEN_AUTO_LOAN, offer.annualFee());
            assertThat(purchase.productCode()).isEqualTo(ProductPortalServer.ProductCatalog.GREEN_AUTO_LOAN);
            assertThat(purchase.billedAmount()).isEqualTo(350.00);
            assertThat(purchase.xaCommitted()).isTrue();
            assertThat(purchase.caseStatus()).isEqualTo("PURCHASED");
        }
    }

    @Test
    @DisplayName("Resilience & Chaos Simulation: Verify KYC Decline and 2PC Commit Hazard in Banking Portal")
    void shouldSimulateEdgeFaultsAndResilienceInPortal() throws Exception {
        try (BankingSimulationHarness harness = new BankingSimulationHarness()) {
            ProductPortalServer portal = harness.getProductPortal();

            // 1. CRM KYC verification decline simulation
            ApplicationOfferResult kycDeclined = portal.submitApplication(
                    "CUST-RISKY",
                    ProductPortalServer.ProductCatalog.PLATINUM_CREDIT,
                    "BANK-TX-KYC-FAIL",
                    "KYC_REJECTED");
            assertThat(kycDeclined.kycStatus()).isEqualTo("UNVERIFIED");
            assertThat(kycDeclined.eligible()).isFalse();
            assertThat(kycDeclined.caseStatus()).isEqualTo("KYC_DECLINED");

            // 2. Normal application followed by simulated 2PC commit heuristic hazard
            ApplicationOfferResult validOffer =
                    portal.submitApplication("CUST-42", ProductPortalServer.ProductCatalog.SOLAR_MORTGAGE);
            assertThat(validOffer.eligible()).isTrue();
            assertThat(validOffer.annualFee()).isEqualTo(450.00);

            PurchaseConfirmationResult hazardPurchase = portal.approvePurchase(
                    validOffer.caseId(),
                    "CUST-42",
                    ProductPortalServer.ProductCatalog.SOLAR_MORTGAGE,
                    validOffer.annualFee(),
                    "BANK-TX-HAZARD",
                    "COMMIT_HAZARD");
            assertThat(hazardPurchase.xaCommitted()).isFalse();
            assertThat(hazardPurchase.caseStatus()).isEqualTo("PENDING_RECONCILIATION");
            assertThat(hazardPurchase.invoiceId()).contains("HAZARD");
        }
    }
}
