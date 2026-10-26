package com.github.mattiasgreen.wiremock.casual;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.recordSpec;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.transaction.xa.Xid;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import se.laz.casual.api.buffer.CasualBufferUtils;
import se.laz.casual.api.buffer.type.JsonBuffer;
import se.laz.casual.api.buffer.type.ServiceBuffer;
import se.laz.casual.api.flags.AtmiFlags;
import se.laz.casual.api.flags.ErrorState;
import se.laz.casual.api.flags.Flag;
import se.laz.casual.api.flags.TransactionState;
import se.laz.casual.api.network.protocol.messages.CasualNWMessageType;
import se.laz.casual.api.xa.XAReturnCode;
import se.laz.casual.api.xa.XID;
import se.laz.casual.network.ProtocolVersion;
import se.laz.casual.network.protocol.messages.domain.CasualDomainDiscoveryReplyMessage;
import se.laz.casual.network.protocol.messages.domain.Service;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallReplyMessage;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallRequestMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceCommitReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourcePrepareReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceRollbackReplyMessage;

/**
 * PoC Integration test verifying that Casual wire protocol requests
 * can be stubbed via WireMock HTTP definitions, and transparently proxied to upstream
 * Casual domains with optional stub recording.
 */
class CasualWireMockGatewayTest {

    private WireMockServer wireMockServer;
    private CasualWireMockGateway gateway;
    private SimpleCasualTestClient client;

    @BeforeEach
    void setUp() throws Exception {
        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        wireMockServer.start();

        gateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(wireMockServer)
                .build();
        gateway.start();

        client = new SimpleCasualTestClient("127.0.0.1", gateway.getPort());
        client.connect();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (gateway != null) {
            gateway.close();
        }
        if (wireMockServer != null) {
            wireMockServer.resetAll();
            wireMockServer.stop();
        }
    }

    @Test
    @DisplayName("Should successfully perform domain discovery for requested services")
    void shouldDiscoverServices() throws Exception {
        CasualDomainDiscoveryReplyMessage reply = client.discoverServices(List.of("orderService", "paymentService"));

        assertThat(reply).isNotNull();
        assertThat(reply.getDomainName()).isEqualTo("wiremock-casual-domain");
        assertThat(reply.getServices())
                .extracting(Service::getName)
                .containsExactlyInAnyOrder("orderService", "paymentService");
    }

    @Test
    @DisplayName("Should stub Casual tpcall with matching WireMock HTTP stub mapping")
    void shouldStubCasualServiceCallSuccessfully() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/createOrder"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"orderId\": \"ORD-9988\", \"status\": \"CONFIRMED\"}")));

        CasualServiceCallReplyMessage reply =
                this.client.tpcall("createOrder", "{\"amount\": 150.0, \"currency\": \"SEK\"}");

        assertThat(reply).isNotNull();
        assertThat(reply.getError()).isEqualTo(ErrorState.OK);

        ServiceBuffer buffer = reply.getServiceBuffer();
        assertThat(buffer).isNotNull();
        assertThat(buffer.getType()).isEqualTo(se.laz.casual.api.buffer.CasualBufferType.JSON.getName());

        String responsePayload = new String(buffer.getBytes().get(0), StandardCharsets.UTF_8);
        assertThat(responsePayload).contains("ORD-9988").contains("CONFIRMED");
    }

    @Test
    @DisplayName("Should return TPENOENT error when WireMock returns unmatched for unstubbed service")
    void shouldReturnTpenoentWhenServiceNotFound() throws Exception {
        CasualServiceCallReplyMessage reply = client.tpcall("nonExistentService", "{}");
        assertThat(reply).isNotNull();
        assertThat(reply.getError()).isEqualTo(ErrorState.TPENOENT);
    }

    @Test
    @DisplayName("Should serve matched 404 stub directly from WireMock without proxying")
    void shouldServeMatched404StubDirectly() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/explicitNotFound"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"Entity not found\"}")));

        CasualServiceCallReplyMessage reply = client.tpcall("explicitNotFound", "{}");

        assertThat(reply).isNotNull();
        assertThat(reply.getError()).isEqualTo(ErrorState.TPENOENT);
        assertThat(reply.getServiceBuffer()).isNotNull();
        assertThat(reply.getServiceBuffer().getBytes()).isNotEmpty();

        String body = new String(reply.getServiceBuffer().getBytes().get(0), StandardCharsets.UTF_8);
        assertThat(body).contains("Entity not found");
    }

    @Test
    @DisplayName("Should return TPESVCFAIL error when WireMock stub returns 500 server error")
    void shouldReturnTpesvcfailOnServerError() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/failingService"))
                .willReturn(aResponse().withStatus(500).withBody("Internal error")));

        CasualServiceCallReplyMessage reply = client.tpcall("failingService", "{}");

        assertThat(reply).isNotNull();
        assertThat(reply.getError()).isEqualTo(ErrorState.TPESVCFAIL);
    }

    @Test
    @DisplayName("Should forward service call to upstream Casual server via stub-driven proxy")
    void shouldForwardUnmatchedServiceToUpstreamCasualServer() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();
        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamWireMock)
                .build();
        upstreamGateway.start();

        try {
            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/upstreamOrderService"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"orderId\": \"UPSTREAM-123\", \"routedVia\": \"upstream\"}")));

            // Configure stub-driven proxy route
            wireMockServer.stubFor(post(urlMatching("/__casual/services/.*"))
                    .atPriority(10)
                    .withMetadata(
                            Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (SimpleCasualTestClient proxyClient = new SimpleCasualTestClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                // Call unstubbed service on proxyGateway -> should forward to upstreamGateway
                CasualServiceCallReplyMessage reply =
                        proxyClient.tpcall("upstreamOrderService", "{\"item\": \"laptop\"}");

                assertThat(reply).isNotNull();
                assertThat(reply.getError()).isEqualTo(ErrorState.OK);

                String body = new String(reply.getServiceBuffer().getBytes().get(0), StandardCharsets.UTF_8);
                assertThat(body).contains("UPSTREAM-123").contains("upstream");

                upstreamWireMock.verify(1, postRequestedFor(urlEqualTo("/__casual/services/upstreamOrderService")));
            } finally {
                proxyGateway.close();
            }
        } finally {
            upstreamGateway.close();
            upstreamWireMock.stop();
        }
    }

    @Test
    @DisplayName(
            "Should forward customer and spouse calls to upstream CRM risk control and snapshot record with exact body matching for offline replay")
    void shouldForwardAndRecordUnmatchedServiceWhenRecordStubsEnabled() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();
        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamWireMock)
                .build();
        upstreamGateway.start();

        try {
            // Upstream CRM Risk Control: Customer has LOW risk, Spouse has MEDIUM risk
            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/crmRiskControlService"))
                    .withRequestBody(equalToJson("{\"customerId\": \"CUST-001\", \"role\": \"customer\"}"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"customerId\": \"CUST-001\", \"riskStatus\": \"LOW\", \"approved\": true}")));

            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/crmRiskControlService"))
                    .withRequestBody(equalToJson("{\"customerId\": \"CUST-002\", \"role\": \"spouse\"}"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(
                                    "{\"customerId\": \"CUST-002\", \"riskStatus\": \"MEDIUM\", \"approved\": true}")));

            wireMockServer.stubFor(post(urlMatching("/__casual/services/.*"))
                    .atPriority(10)
                    .withMetadata(
                            Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (SimpleCasualTestClient proxyClient = new SimpleCasualTestClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                // 1. Call Customer -> Forwarded to upstream and recorded in Request Journal
                CasualServiceCallReplyMessage customerReply = proxyClient.tpcall(
                        "crmRiskControlService", "{\"customerId\": \"CUST-001\", \"role\": \"customer\"}");
                assertThat(customerReply).isNotNull();
                assertThat(customerReply.getError()).isEqualTo(ErrorState.OK);
                String customerBody =
                        new String(customerReply.getServiceBuffer().getBytes().get(0), StandardCharsets.UTF_8);
                assertThat(customerBody).contains("CUST-001").contains("LOW");

                // 2. Call Spouse -> Forwarded to upstream and recorded in Request Journal
                CasualServiceCallReplyMessage spouseReply = proxyClient.tpcall(
                        "crmRiskControlService", "{\"customerId\": \"CUST-002\", \"role\": \"spouse\"}");
                assertThat(spouseReply).isNotNull();
                assertThat(spouseReply.getError()).isEqualTo(ErrorState.OK);
                String spouseBody =
                        new String(spouseReply.getServiceBuffer().getBytes().get(0), StandardCharsets.UTF_8);
                assertThat(spouseBody).contains("CUST-002").contains("MEDIUM");

                // Upstream received exactly 1 request for customer and 1 for spouse
                upstreamWireMock.verify(
                        1,
                        postRequestedFor(urlEqualTo("/__casual/services/crmRiskControlService"))
                                .withRequestBody(
                                        equalToJson("{\"customerId\": \"CUST-001\", \"role\": \"customer\"}")));
                upstreamWireMock.verify(
                        1,
                        postRequestedFor(urlEqualTo("/__casual/services/crmRiskControlService"))
                                .withRequestBody(equalToJson("{\"customerId\": \"CUST-002\", \"role\": \"spouse\"}")));

                // 3. Snapshot record journal entries into offline stub mappings
                wireMockServer.snapshotRecord(recordSpec()
                        .makeStubsPersistent(false)
                        .extractTextBodiesOver(Long.MAX_VALUE)
                        .extractBinaryBodiesOver(Long.MAX_VALUE));

                // 4. Terminate upstream server completely to guarantee 100% offline replay
                upstreamGateway.close();
                upstreamWireMock.stop();

                // 5. Replay Customer call offline -> Must match recorded customer stub directly!
                CasualServiceCallReplyMessage replayCustomer = proxyClient.tpcall(
                        "crmRiskControlService", "{\"customerId\": \"CUST-001\", \"role\": \"customer\"}");
                assertThat(replayCustomer).isNotNull();
                assertThat(replayCustomer.getError()).isEqualTo(ErrorState.OK);
                String replayCustBody =
                        new String(replayCustomer.getServiceBuffer().getBytes().get(0), StandardCharsets.UTF_8);
                assertThat(replayCustBody).contains("CUST-001").contains("LOW");

                // 6. Replay Spouse call offline -> Must match recorded spouse stub directly without collision!
                CasualServiceCallReplyMessage replaySpouse = proxyClient.tpcall(
                        "crmRiskControlService", "{\"customerId\": \"CUST-002\", \"role\": \"spouse\"}");
                assertThat(replaySpouse).isNotNull();
                assertThat(replaySpouse.getError()).isEqualTo(ErrorState.OK);
                String replaySpouseBody =
                        new String(replaySpouse.getServiceBuffer().getBytes().get(0), StandardCharsets.UTF_8);
                assertThat(replaySpouseBody).contains("CUST-002").contains("MEDIUM");

                // 7. Unknown payload does NOT match customer or spouse stubs; falls through to proxy route
                // and fails because upstream is shut down
                CasualServiceCallReplyMessage unknownReply = proxyClient.tpcall(
                        "crmRiskControlService", "{\"customerId\": \"CUST-999\", \"role\": \"unknown\"}");
                assertThat(unknownReply).isNotNull();
                assertThat(unknownReply.getError()).isEqualTo(ErrorState.TPESVCERR);
            } finally {
                proxyGateway.close();
            }
        } finally {
            if (upstreamGateway.isRunning()) {
                upstreamGateway.close();
            }
            if (upstreamWireMock.isRunning()) {
                upstreamWireMock.stop();
            }
        }
    }

    @Test
    @DisplayName("Should forward upstream error states transparently to client")
    void shouldForwardUpstreamErrorStates() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();
        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamWireMock)
                .build();
        upstreamGateway.start();

        try {
            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/upstreamFailing"))
                    .willReturn(aResponse().withStatus(500).withBody("Upstream fatal error")));

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/upstreamFailing"))
                    .withMetadata(
                            Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (SimpleCasualTestClient proxyClient = new SimpleCasualTestClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                CasualServiceCallReplyMessage reply = proxyClient.tpcall("upstreamFailing", "{}");

                assertThat(reply).isNotNull();
                assertThat(reply.getError()).isEqualTo(ErrorState.TPESVCFAIL);
            } finally {
                proxyGateway.close();
            }
        } finally {
            upstreamGateway.close();
            upstreamWireMock.stop();
        }
    }

    @Test
    @DisplayName("Should record and replay upstream faults via snapshot record")
    void shouldRecordAndReplayUpstreamFaultsWhenRecordStubsEnabled() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();
        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamWireMock)
                .build();
        upstreamGateway.start();

        try {
            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/upstreamAccountBlocked"))
                    .willReturn(aResponse()
                            .withStatus(500)
                            .withHeader("Content-Type", "application/json")
                            .withHeader("X-Casual-Error-State", "TPESVCFAIL")
                            .withBody("{\"error\": \"Account blocked\", \"code\": \"ACC-BLOCKED\"}")));

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/upstreamAccountBlocked"))
                    .atPriority(10)
                    .withMetadata(
                            Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (SimpleCasualTestClient proxyClient = new SimpleCasualTestClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                // 1st Call: Forwarded to upstream and recorded in Request Journal
                CasualServiceCallReplyMessage firstReply =
                        proxyClient.tpcall("upstreamAccountBlocked", "{\"accountId\": \"ACC-999\"}");

                assertThat(firstReply).isNotNull();
                assertThat(firstReply.getError()).isEqualTo(ErrorState.TPESVCFAIL);
                String firstBody =
                        new String(firstReply.getServiceBuffer().getBytes().get(0), StandardCharsets.UTF_8);
                assertThat(firstBody).contains("Account blocked").contains("ACC-BLOCKED");

                upstreamWireMock.verify(1, postRequestedFor(urlEqualTo("/__casual/services/upstreamAccountBlocked")));

                // Snapshot record journal entries into offline stub mappings
                wireMockServer.snapshotRecord(recordSpec()
                        .makeStubsPersistent(false)
                        .extractTextBodiesOver(Long.MAX_VALUE)
                        .extractBinaryBodiesOver(Long.MAX_VALUE));

                // 2nd Call: Replayed directly from the newly recorded fault stub in wireMockServer
                CasualServiceCallReplyMessage secondReply =
                        proxyClient.tpcall("upstreamAccountBlocked", "{\"accountId\": \"ACC-999\"}");

                assertThat(secondReply).isNotNull();
                assertThat(secondReply.getError()).isEqualTo(ErrorState.TPESVCFAIL);
                String secondBody =
                        new String(secondReply.getServiceBuffer().getBytes().get(0), StandardCharsets.UTF_8);
                assertThat(secondBody).contains("Account blocked").contains("ACC-BLOCKED");

                // Upstream was still only called once!
                upstreamWireMock.verify(1, postRequestedFor(urlEqualTo("/__casual/services/upstreamAccountBlocked")));
            } finally {
                proxyGateway.close();
            }
        } finally {
            upstreamGateway.close();
            upstreamWireMock.stop();
        }
    }

    @Test
    @DisplayName("Should prioritize high-priority mock stub over catch-all proxy stub")
    void shouldPrioritizeLocalMockStubOverCatchAllProxyStub() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();
        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamWireMock)
                .build();
        upstreamGateway.start();

        try {
            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/myService"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"source\": \"upstream\"}")));

            // Catch-all proxy at priority 10
            wireMockServer.stubFor(post(urlMatching("/__casual/services/.*"))
                    .atPriority(10)
                    .withMetadata(
                            Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            // Local mock stub at priority 1
            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/myService"))
                    .atPriority(1)
                    .willReturn(aResponse().withStatus(200).withBody("{\"source\": \"local-mock\"}")));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (SimpleCasualTestClient proxyClient = new SimpleCasualTestClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                CasualServiceCallReplyMessage reply = proxyClient.tpcall("myService", "{}");
                assertThat(reply.getError()).isEqualTo(ErrorState.OK);
                String body = new String(reply.getServiceBuffer().getBytes().get(0), StandardCharsets.UTF_8);
                assertThat(body).contains("local-mock");

                // Upstream must NOT be called
                upstreamWireMock.verify(0, postRequestedFor(urlEqualTo("/__casual/services/myService")));
            } finally {
                proxyGateway.close();
            }
        } finally {
            upstreamGateway.close();
            upstreamWireMock.stop();
        }
    }

    @Test
    @DisplayName(
            "Should route different services to different upstreams and fan-out 2PC prepare/commit across all enlisted upstreams")
    void shouldRouteToMultipleUpstreamsAndFanOut2pc() throws Exception {
        WireMockServer upstream1WM = new WireMockServer(wireMockConfig().dynamicPort());
        WireMockServer upstream2WM = new WireMockServer(wireMockConfig().dynamicPort());
        upstream1WM.start();
        upstream2WM.start();

        CasualWireMockGateway upstream1 = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstream1WM)
                .build();
        CasualWireMockGateway upstream2 = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstream2WM)
                .build();
        upstream1.start();
        upstream2.start();

        try {
            upstream1WM.stubFor(post(urlEqualTo("/__casual/services/orderService"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"order\": \"123\"}")));
            upstream2WM.stubFor(post(urlEqualTo("/__casual/services/paymentService"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"payment\": \"456\"}")));

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/orderService"))
                    .withMetadata(Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstream1.getPort()))
                    .willReturn(aResponse().withStatus(200)));
            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/paymentService"))
                    .withMetadata(Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstream2.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (SimpleCasualTestClient proxyClient = new SimpleCasualTestClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                Xid xid = XID.of(
                        "gtrid-multi".getBytes(StandardCharsets.UTF_8),
                        "bqual-multi".getBytes(StandardCharsets.UTF_8),
                        42);

                CasualServiceCallReplyMessage r1 = proxyClient.tpcall("orderService", "{}", xid);
                assertThat(r1.getError()).isEqualTo(ErrorState.OK);

                CasualServiceCallReplyMessage r2 = proxyClient.tpcall("paymentService", "{}", xid);
                assertThat(r2.getError()).isEqualTo(ErrorState.OK);

                // Both upstreams called
                upstream1WM.verify(1, postRequestedFor(urlEqualTo("/__casual/services/orderService")));
                upstream2WM.verify(1, postRequestedFor(urlEqualTo("/__casual/services/paymentService")));

                // 2PC prepare fans out to both upstreams
                CasualTransactionResourcePrepareReplyMessage prepReply = proxyClient.prepare(xid, 1);
                assertThat(prepReply.getTransactionReturnCode()).isEqualTo(XAReturnCode.XA_OK);

                // 2PC commit fans out to both upstreams
                CasualTransactionResourceCommitReplyMessage commitReply = proxyClient.commit(xid, 1, false);
                assertThat(commitReply.getTransactionReturnCode()).isEqualTo(XAReturnCode.XA_OK);
            } finally {
                proxyGateway.close();
            }
        } finally {
            upstream1.close();
            upstream2.close();
            upstream1WM.stop();
            upstream2WM.stop();
        }
    }

    @Test
    @DisplayName("Should stub Casual failure mode with custom ErrorState, user code, and transaction state")
    void shouldStubCasualFailureModeWithCustomErrorStateUserCodeAndTxState() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/failingTxService"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("X-Casual-Error-State", "TPETRAN")
                        .withHeader("X-Casual-User-Supplied-Error", "8877")
                        .withHeader("X-Casual-Transaction-State", "ROLLBACK_ONLY")
                        .withBody("{\"error\": \"Transaction aborted\", \"retry\": false}")));

        CasualServiceCallReplyMessage reply = client.tpcall("failingTxService", "{\"txId\": \"TX-001\"}");

        assertThat(reply).isNotNull();
        assertThat(reply.getError()).isEqualTo(ErrorState.TPETRAN);
        assertThat(reply.getUserDefinedCode()).isEqualTo(8877L);
        assertThat(reply.getTransactionState()).isEqualTo(TransactionState.ROLLBACK_ONLY);

        String body = new String(reply.getServiceBuffer().getBytes().get(0), StandardCharsets.UTF_8);
        assertThat(body).contains("Transaction aborted");
    }

    @Test
    @DisplayName("Should map standard HTTP status codes to appropriate Casual error states when header is omitted")
    void shouldMapStandardHttpStatusCodesToCasualErrorStates() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/invalidArgs"))
                .willReturn(aResponse().withStatus(400).withBody("Bad request")));
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/timedOut"))
                .willReturn(aResponse().withStatus(504)));
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/rateLimited"))
                .willReturn(aResponse().withStatus(429)));
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/backendError"))
                .willReturn(aResponse().withStatus(502)));

        assertThat(client.tpcall("invalidArgs", "{}").getError()).isEqualTo(ErrorState.TPEINVAL);
        assertThat(client.tpcall("timedOut", "{}").getError()).isEqualTo(ErrorState.TPETIME);
        assertThat(client.tpcall("rateLimited", "{}").getError()).isEqualTo(ErrorState.TPELIMIT);
        assertThat(client.tpcall("backendError", "{}").getError()).isEqualTo(ErrorState.TPESVCERR);
    }

    @ParameterizedTest
    @EnumSource(TransactionState.class)
    @DisplayName("Should faithfully stub all Casual TransactionState enum modes")
    void shouldStubAllTransactionStatesCorrectly(TransactionState txState) throws Exception {
        String serviceName = "txStateService_" + txState.name();
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/" + serviceName))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("X-Casual-Transaction-State", txState.name())
                        .withBody("{\"txStatus\": \"" + txState.name() + "\"}")));

        CasualServiceCallReplyMessage reply = client.tpcall(serviceName, "{}");
        assertThat(reply).isNotNull();
        assertThat(reply.getTransactionState()).isEqualTo(txState);
    }

    @Test
    @DisplayName("Should default TransactionState to TX_ACTIVE when header is absent or invalid")
    void shouldDefaultToTxActiveWhenTransactionStateHeaderIsMissingOrInvalid() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/missingTxHeader"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/invalidTxHeader"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("X-Casual-Transaction-State", "NON_EXISTENT_STATE")
                        .withBody("{}")));

        CasualServiceCallReplyMessage reply1 = client.tpcall("missingTxHeader", "{}");
        assertThat(reply1.getTransactionState()).isEqualTo(TransactionState.TX_ACTIVE);

        CasualServiceCallReplyMessage reply2 = client.tpcall("invalidTxHeader", "{}");
        assertThat(reply2.getTransactionState()).isEqualTo(TransactionState.TX_ACTIVE);
    }

    @Test
    @DisplayName("Should record and replay non-default upstream TransactionState")
    void shouldRecordAndReplayUpstreamTransactionState() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();
        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamWireMock)
                .build();
        upstreamGateway.start();

        try {
            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/timedOutTxService"))
                    .willReturn(aResponse()
                            .withStatus(500)
                            .withHeader("Content-Type", "application/json")
                            .withHeader("X-Casual-Error-State", "TPESVCFAIL")
                            .withHeader("X-Casual-Transaction-State", "TIMEOUT_ROLLBACK_ONLY")
                            .withBody("{\"error\": \"Transaction deadline exceeded\"}")));

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/timedOutTxService"))
                    .atPriority(10)
                    .withMetadata(
                            Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (SimpleCasualTestClient proxyClient = new SimpleCasualTestClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                // 1st call: Forwarded upstream & recorded in Request Journal
                CasualServiceCallReplyMessage reply1 = proxyClient.tpcall("timedOutTxService", "{}");
                assertThat(reply1.getTransactionState()).isEqualTo(TransactionState.TIMEOUT_ROLLBACK_ONLY);
                assertThat(reply1.getError()).isEqualTo(ErrorState.TPESVCFAIL);

                upstreamWireMock.verify(1, postRequestedFor(urlEqualTo("/__casual/services/timedOutTxService")));

                // Snapshot record journal entries into offline stub mappings
                wireMockServer.snapshotRecord(recordSpec()
                        .makeStubsPersistent(false)
                        .extractTextBodiesOver(Long.MAX_VALUE)
                        .extractBinaryBodiesOver(Long.MAX_VALUE));

                // 2nd call: Replayed from recorded WireMock stub
                CasualServiceCallReplyMessage reply2 = proxyClient.tpcall("timedOutTxService", "{}");
                assertThat(reply2.getTransactionState()).isEqualTo(TransactionState.TIMEOUT_ROLLBACK_ONLY);

                // Upstream was not called again
                upstreamWireMock.verify(1, postRequestedFor(urlEqualTo("/__casual/services/timedOutTxService")));
            } finally {
                proxyGateway.close();
            }
        } finally {
            upstreamGateway.close();
            upstreamWireMock.stop();
        }
    }

    @Test
    @DisplayName("Should return XA_OK by default for 2PC prepare, commit, and rollback on stubbed transactional calls")
    void shouldReturnXaOkByDefaultForStubbed2pcTransactions() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/txPaymentService"))
                .willReturn(aResponse().withStatus(200).withBody("{\"status\": \"RESERVED\"}")));

        Xid xid1 =
                XID.of("gtrid-001".getBytes(StandardCharsets.UTF_8), "bqual-001".getBytes(StandardCharsets.UTF_8), 42);
        CasualServiceCallReplyMessage callReply = client.tpcall("txPaymentService", "{\"amount\": 100}", xid1);
        assertThat(callReply.getError()).isEqualTo(ErrorState.OK);
        assertThat(callReply.getTransactionState()).isEqualTo(TransactionState.TX_ACTIVE);
        if (callReply.getType() == CasualNWMessageType.SERVICE_CALL_REPLY) {
            assertThat(callReply.getXid()).isEqualTo(xid1);
        }

        CasualTransactionResourcePrepareReplyMessage prepareReply = client.prepare(xid1, 1);
        assertThat(prepareReply.getTransactionReturnCode()).isEqualTo(XAReturnCode.XA_OK);

        CasualTransactionResourceCommitReplyMessage commitReply = client.commit(xid1, 1, false);
        assertThat(commitReply.getTransactionReturnCode()).isEqualTo(XAReturnCode.XA_OK);

        Xid xid2 =
                XID.of("gtrid-002".getBytes(StandardCharsets.UTF_8), "bqual-002".getBytes(StandardCharsets.UTF_8), 42);
        client.tpcall("txPaymentService", "{\"amount\": 200}", xid2);
        CasualTransactionResourceRollbackReplyMessage rollbackReply = client.rollback(xid2, 1);
        assertThat(rollbackReply.getTransactionReturnCode()).isEqualTo(XAReturnCode.XA_OK);
    }

    @Test
    @DisplayName("Should honor stubbed X-Casual-XA-*-Vote headers for 2PC chaos simulation")
    void shouldHonorStubbedXaVoteHeadersFor2pcChaosSimulation() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/chaosPrepareService"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("X-Casual-XA-Prepare-Vote", "XAER_RMERR")
                        .withBody("{\"status\": \"OK_DURING_CALL_FAILS_AT_PREPARE\"}")));

        Xid xid = XID.of(
                "gtrid-chaos".getBytes(StandardCharsets.UTF_8), "bqual-chaos".getBytes(StandardCharsets.UTF_8), 42);
        CasualServiceCallReplyMessage callReply = client.tpcall("chaosPrepareService", "{}", xid);
        assertThat(callReply.getError()).isEqualTo(ErrorState.OK);

        CasualTransactionResourcePrepareReplyMessage prepareReply = client.prepare(xid, 1);
        assertThat(prepareReply.getTransactionReturnCode()).isEqualTo(XAReturnCode.XAER_RMERR);
    }

    @Test
    @DisplayName("Should transparently forward 2PC prepare and commit to upstream Casual server when call was proxied")
    void shouldTransparentlyForward2pcMessagesToUpstreamCasualServer() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();
        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamWireMock)
                .build();
        upstreamGateway.start();

        try {
            // Configure upstream server to return XA_RDONLY on prepare so we can prove the reply came from upstream
            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/upstreamTxService"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("X-Casual-XA-Prepare-Vote", "XA_RDONLY")
                            .withBody("{\"upstream\": true}")));

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/upstreamTxService"))
                    .withMetadata(
                            Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (SimpleCasualTestClient proxyClient = new SimpleCasualTestClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                Xid xid = XID.of(
                        "gtrid-proxy-2pc".getBytes(StandardCharsets.UTF_8),
                        "bqual-proxy-2pc".getBytes(StandardCharsets.UTF_8),
                        42);

                CasualServiceCallReplyMessage callReply =
                        proxyClient.tpcall("upstreamTxService", "{\"op\":\"read\"}", xid);
                assertThat(callReply.getError()).isEqualTo(ErrorState.OK);

                // Prepare must be forwarded to upstreamGateway, which returns XA_RDONLY
                CasualTransactionResourcePrepareReplyMessage prepareReply = proxyClient.prepare(xid, 1);
                assertThat(prepareReply.getTransactionReturnCode()).isEqualTo(XAReturnCode.XA_RDONLY);

                // Commit must also complete cleanly
                CasualTransactionResourceCommitReplyMessage commitReply = proxyClient.commit(xid, 1, false);
                assertThat(commitReply.getTransactionReturnCode()).isEqualTo(XAReturnCode.XA_OK);
            } finally {
                proxyGateway.close();
            }
        } finally {
            upstreamGateway.close();
            upstreamWireMock.stop();
        }
    }

    @Test
    @DisplayName("Should forward discovery to upstream and merge with local WireMock stubs")
    void shouldDiscoverUpstreamServicesAndMergeWithLocalStubs() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();

        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamWireMock)
                .build();
        upstreamGateway.start();

        try {
            // Upstream provides upstreamCatalogService
            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/upstreamCatalogService"))
                    .willReturn(aResponse().withStatus(200).withBody("{}")));

            // Local stub in proxy's WireMock
            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/localStubService"))
                    .atPriority(1)
                    .willReturn(aResponse().withStatus(200).withBody("{}")));

            // Catch-all proxy stub pointing to upstream
            wireMockServer.stubFor(post(urlMatching("/__casual/services/.*"))
                    .atPriority(10)
                    .withMetadata(
                            Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (SimpleCasualTestClient proxyClient = new SimpleCasualTestClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                // Query for local service and an upstream service
                CasualDomainDiscoveryReplyMessage discoveryReply =
                        proxyClient.discoverServices(List.of("upstreamCatalogService", "localStubService"));

                assertThat(discoveryReply).isNotNull();
                List<String> serviceNames = discoveryReply.getServices().stream()
                        .map(Service::getName)
                        .toList();

                assertThat(serviceNames).contains("upstreamCatalogService", "localStubService");
            } finally {
                proxyGateway.close();
            }
        } finally {
            upstreamGateway.close();
            upstreamWireMock.stop();
        }
    }

    @Test
    @DisplayName("Should fail fast with TPEITYPE on unmatched non-JSON buffer when no upstream is configured")
    void shouldFailFastWithTpeitypeOnUnmatchedNonJsonBufferWithoutUpstream() throws Exception {
        // Send a binary CFIELD/ buffer without creating a matching stub
        byte[] fieldedBytes = new byte[] {0x00, 0x0A, 0x42, (byte) 0xFF};
        ServiceBuffer fieldedBuffer = ServiceBuffer.of("CFIELD/", List.of(fieldedBytes));

        CasualServiceCallReplyMessage reply = client.tpcall("unstubbedBinaryService", fieldedBuffer);

        assertThat(reply).isNotNull();
        // Guaranteed fail-fast: must immediately return TPEITYPE (Invalid buffer type)
        assertThat(reply.getError()).isEqualTo(ErrorState.TPEITYPE);
    }

    @Test
    @DisplayName("Should proxy and record non-JSON fielded binary buffer as Base64 and replay offline")
    void shouldProxyAndRecordFieldedBinaryBufferAsBase64AndReplayOffline() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();

        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamWireMock)
                .build();
        upstreamGateway.start();

        try {
            byte[] upstreamReplyBytes = new byte[] {0x11, 0x22, 0x33, 0x44};
            String base64Reply = Base64.getEncoder().encodeToString(upstreamReplyBytes);

            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/fieldedService"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/octet-stream")
                            .withHeader("X-Casual-Buffer-Type", "CFIELD/")
                            .withBody(base64Reply)));

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/fieldedService"))
                    .atPriority(10)
                    .withMetadata(
                            Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (SimpleCasualTestClient proxyClient = new SimpleCasualTestClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                byte[] requestBytes = new byte[] {0x01, 0x02, 0x03};
                ServiceBuffer requestBuffer = ServiceBuffer.of("CFIELD/", List.of(requestBytes));

                // 1. Call via proxy gateway -> forwarded to upstreamGateway and recorded in wireMockServer
                CasualServiceCallReplyMessage reply1 = proxyClient.tpcall("fieldedService", requestBuffer);
                assertThat(reply1.getError()).isEqualTo(ErrorState.OK);
                assertThat(reply1.getServiceBuffer().getType()).isEqualTo("CFIELD/");
                assertThat(CasualBufferUtils.toByteArray(reply1.getServiceBuffer()))
                        .isEqualTo(upstreamReplyBytes);

                // Snapshot record journal entries into offline stub mappings
                wireMockServer.snapshotRecord(recordSpec()
                        .makeStubsPersistent(false)
                        .extractTextBodiesOver(Long.MAX_VALUE)
                        .extractBinaryBodiesOver(Long.MAX_VALUE));

                // Stop upstream gateway completely to test offline replay from recorded Base64 stub
                upstreamGateway.close();
                upstreamWireMock.stop();

                // 2. Call again with upstream offline -> must be served from WireMock's recorded Base64 stub!
                CasualServiceCallReplyMessage reply2 = proxyClient.tpcall("fieldedService", requestBuffer);
                assertThat(reply2.getError()).isEqualTo(ErrorState.OK);
                assertThat(reply2.getServiceBuffer().getType()).isEqualTo("CFIELD/");
                assertThat(CasualBufferUtils.toByteArray(reply2.getServiceBuffer()))
                        .isEqualTo(upstreamReplyBytes);
            } finally {
                proxyGateway.close();
            }
        } finally {
            if (upstreamGateway.isRunning()) {
                upstreamGateway.close();
            }
            if (upstreamWireMock.isRunning()) {
                upstreamWireMock.stop();
            }
        }
    }

    @Test
    @DisplayName("Should translate inbound fielded buffer call to JSON stub and return fielded reply")
    void shouldHandleFieldedBufferCallWithConfiguredTableAndMatchWireMockStub() throws Exception {
        String fieldTableJson =
                """
        {
          "groups": [
            {
              "base": 1000,
              "fields": [
                {
                  "name": "FLD_USER_ID",
                  "id": 1,
                  "type": "long"
                },
                {
                  "name": "FLD_USER_NAME",
                  "id": 2,
                  "type": "string"
                }
              ]
            }
          ]
        }
        """;
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("casual-fields-gateway-test", ".json");
        temp.toFile().deleteOnExit();
        java.nio.file.Files.writeString(temp, fieldTableJson, java.nio.charset.StandardCharsets.UTF_8);

        try {
            System.setProperty("CASUAL_FIELD_TABLE", temp.toAbsolutePath().toString());
            se.laz.casual.config.ConfigurationService.setConfiguration(
                    se.laz.casual.config.ConfigurationOptions.CASUAL_FIELD_TABLE,
                    temp.toAbsolutePath().toString());
            assertThat(CasualBufferUtils.isFieldTableAvailable()).isTrue();

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/fmlUserService"))
                    .withRequestBody(equalToJson("{\"FLD_USER_NAME\": \"Alice\", \"FLD_USER_ID\": 1001}"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withHeader("X-Casual-Buffer-Type", "CFIELD/")
                            .withBody("{\"FLD_USER_NAME\": \"Alice\", \"FLD_USER_ID\": 9999}")));

            // Create and encode a real fielded buffer using FieldedTypeBuffer
            se.laz.casual.api.buffer.type.fielded.FieldedTypeBuffer fml =
                    se.laz.casual.api.buffer.type.fielded.FieldedTypeBuffer.create();
            fml.write("FLD_USER_NAME", "Alice");
            fml.write("FLD_USER_ID", 1001L);

            ServiceBuffer requestBuffer = ServiceBuffer.of(".FIELDED", fml.encode());

            CasualServiceCallReplyMessage reply = client.tpcall("fmlUserService", requestBuffer);

            assertThat(reply).isNotNull();
            assertThat(reply.getError()).isEqualTo(ErrorState.OK);
            assertThat(reply.getServiceBuffer().getType()).isEqualTo("CFIELD/");

            // Verify WireMock captured JSON in Request Journal
            wireMockServer.verify(postRequestedFor(urlEqualTo("/__casual/services/fmlUserService"))
                    .withRequestBody(equalToJson("{\"FLD_USER_NAME\": \"Alice\", \"FLD_USER_ID\": 1001}")));

            // Verify reply decoded back to JSON contains the stub values
            String replyJson = CasualBufferUtils.extractPayload(reply.getServiceBuffer());
            assertThat(replyJson).contains("\"FLD_USER_NAME\":\"Alice\"");
            assertThat(replyJson).contains("\"FLD_USER_ID\":9999");
        } finally {
            System.clearProperty("CASUAL_FIELD_TABLE");
            se.laz.casual.config.ConfigurationService.setConfiguration(
                    se.laz.casual.config.ConfigurationOptions.CASUAL_FIELD_TABLE, null);
        }
    }

    @Test
    @DisplayName("Should proxy and record fielded buffer with configured field table and replay offline")
    void shouldProxyAndRecordFieldedBufferWithConfiguredTableAndReplayOffline() throws Exception {
        String fieldTableJson =
                """
        {
          "groups": [
            {
              "base": 1000,
              "fields": [
                {
                  "name": "FLD_USER_ID",
                  "id": 1,
                  "type": "long"
                },
                {
                  "name": "FLD_USER_NAME",
                  "id": 2,
                  "type": "string"
                }
              ]
            }
          ]
        }
        """;
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("casual-fields-proxy-test", ".json");
        temp.toFile().deleteOnExit();
        java.nio.file.Files.writeString(temp, fieldTableJson, java.nio.charset.StandardCharsets.UTF_8);

        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();

        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamWireMock)
                .build();
        upstreamGateway.start();

        try {
            System.setProperty("CASUAL_FIELD_TABLE", temp.toAbsolutePath().toString());
            se.laz.casual.config.ConfigurationService.setConfiguration(
                    se.laz.casual.config.ConfigurationOptions.CASUAL_FIELD_TABLE,
                    temp.toAbsolutePath().toString());
            assertThat(CasualBufferUtils.isFieldTableAvailable()).isTrue();

            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/proxiedFmlService"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withHeader("X-Casual-Buffer-Type", "CFIELD/")
                            .withBody("{\"FLD_USER_NAME\": \"Alice_Proxied\", \"FLD_USER_ID\": 8888}")));

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/proxiedFmlService"))
                    .atPriority(10)
                    .withMetadata(
                            Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (SimpleCasualTestClient proxyClient = new SimpleCasualTestClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                se.laz.casual.api.buffer.type.fielded.FieldedTypeBuffer fml =
                        se.laz.casual.api.buffer.type.fielded.FieldedTypeBuffer.create();
                fml.write("FLD_USER_NAME", "Alice");
                fml.write("FLD_USER_ID", 1001L);

                ServiceBuffer requestBuffer = ServiceBuffer.of(".FIELDED", fml.encode());

                // 1. Call via proxy gateway -> forwarded to upstreamGateway and recorded in wireMockServer
                CasualServiceCallReplyMessage reply1 = proxyClient.tpcall("proxiedFmlService", requestBuffer);
                assertThat(reply1.getError()).isEqualTo(ErrorState.OK);
                assertThat(reply1.getServiceBuffer().getType()).isEqualTo("CFIELD/");
                String reply1Json = CasualBufferUtils.extractPayload(reply1.getServiceBuffer());
                assertThat(reply1Json).contains("\"FLD_USER_NAME\":\"Alice_Proxied\"");
                assertThat(reply1Json).contains("\"FLD_USER_ID\":8888");

                // Snapshot record journal entries into offline stub mappings
                wireMockServer.snapshotRecord(recordSpec()
                        .makeStubsPersistent(false)
                        .extractTextBodiesOver(Long.MAX_VALUE)
                        .extractBinaryBodiesOver(Long.MAX_VALUE));

                // Terminate upstream completely to test offline replay
                upstreamGateway.close();
                upstreamWireMock.stop();

                // 2. Call again with upstream offline -> must be served from WireMock's recorded stub!
                CasualServiceCallReplyMessage reply2 = proxyClient.tpcall("proxiedFmlService", requestBuffer);
                assertThat(reply2.getError()).isEqualTo(ErrorState.OK);
                assertThat(reply2.getServiceBuffer().getType()).isEqualTo("CFIELD/");
                String reply2Json = CasualBufferUtils.extractPayload(reply2.getServiceBuffer());
                assertThat(reply2Json).contains("\"FLD_USER_NAME\":\"Alice_Proxied\"");
                assertThat(reply2Json).contains("\"FLD_USER_ID\":8888");
            } finally {
                proxyGateway.close();
            }
        } finally {
            System.clearProperty("CASUAL_FIELD_TABLE");
            se.laz.casual.config.ConfigurationService.setConfiguration(
                    se.laz.casual.config.ConfigurationOptions.CASUAL_FIELD_TABLE, null);
            if (upstreamGateway.isRunning()) {
                upstreamGateway.close();
            }
            if (upstreamWireMock.isRunning()) {
                upstreamWireMock.stop();
            }
        }
    }

    @Test
    @DisplayName("Should include config property hint in timeout error message when upstream call times out")
    void shouldIncludeConfigPropertyHintOnUpstreamTimeout() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/timeoutTestService"))
                .withMetadata(Map.of(
                        "mode", "proxy",
                        "targetBaseUrl", "casual://127.0.0.1:1"))
                .willReturn(aResponse().withStatus(200)));

        CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(wireMockServer)
                .upstreamTimeoutSeconds(1) // 1s fast timeout
                .build();
        proxyGateway.start();

        try (SimpleCasualTestClient proxyClient = new SimpleCasualTestClient("127.0.0.1", proxyGateway.getPort())) {
            proxyClient.connect();

            CasualServiceCallReplyMessage reply = proxyClient.tpcall("timeoutTestService", "{}");
            assertThat(reply).isNotNull();
            assertThat(reply.getError()).isEqualTo(ErrorState.TPESVCERR);
        } finally {
            proxyGateway.close();
        }
    }

    @Test
    @DisplayName(
            "Should handle 406-byte WildFly JBoss client service call with headers under Protocol 1.5 without decoder exception")
    void shouldHandleWildFlyJBossClient406ByteServiceCallWithoutDecoderException() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/sakVKAnvandare"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"SUCCESS\"}")));

        // Client connects and negotiates VERSION_1_4
        assertThat(client.getProtocolVersion()).isEqualTo(ProtocolVersion.VERSION_1_4);

        // Make service call with xid, simulating WildFly/JBoss Narayana XA client
        Xid xid = XID.of(
                "gtrid-wildfly".getBytes(StandardCharsets.UTF_8),
                "bqual-wildfly".getBytes(StandardCharsets.UTF_8),
                131077);
        CasualServiceCallReplyMessage reply = client.tpcall("sakVKAnvandare", "{}", xid);

        assertThat(reply).isNotNull();
        assertThat(reply.getError()).isEqualTo(ErrorState.OK);
        assertThat(reply.getType()).isEqualTo(CasualNWMessageType.SERVICE_CALL_REPLY_V_1_3);
    }

    @Test
    @DisplayName("Should cleanly decode WildFly 3.3.x client service call on Protocol 1.2 with 8-byte trailing buffer")
    void shouldCleanlyDecodeWildFlyClientOnProtocol12With336Trailer() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/sakVKAnvandare"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"SUCCESS\"}")));

        // Simulate WildFly 3.3.x client by connecting over TCP socket directly proposing [1000, 1001, 1002]
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", gateway.getPort())) {
            socket.setSoTimeout(5000);
            java.io.OutputStream out = socket.getOutputStream();
            java.io.InputStream in = socket.getInputStream();

            // Send domain connect request proposing [1000, 1001, 1002]
            se.laz.casual.network.protocol.messages.domain.CasualDomainConnectRequestMessage connectReq =
                    se.laz.casual.network.protocol.messages.domain.CasualDomainConnectRequestMessage.createBuilder()
                            .withExecution(UUID.randomUUID())
                            .withDomainId(UUID.randomUUID())
                            .withDomainName("wildfly-domain")
                            .withProtocols(List.of(1000L, 1001L, 1002L))
                            .build();
            se.laz.casual.api.network.protocol.messages.CasualNWMessage<
                            se.laz.casual.network.protocol.messages.domain.CasualDomainConnectRequestMessage>
                    connectEnvelope = se.laz.casual.network.protocol.messages.CasualNWMessageImpl.of(
                            UUID.randomUUID(), connectReq);
            for (byte[] b : connectEnvelope.toNetworkBytes()) {
                out.write(b);
            }
            out.flush();

            // Read connect reply header + payload (32 bytes header: UUID (16) + type (8) + payloadSize (8))
            byte[] connectReplyHeader = in.readNBytes(32);
            long replyPayloadSize =
                    java.nio.ByteBuffer.wrap(connectReplyHeader, 24, 8).getLong();
            byte[] connectReplyPayload = in.readNBytes((int) replyPayloadSize);
            long negotiatedVersion = java.nio.ByteBuffer.wrap(connectReplyPayload, connectReplyPayload.length - 8, 8)
                    .getLong();
            assertThat(negotiatedVersion).isEqualTo(1002L); // Negotiated VERSION_1_2

            // Build service call message under VERSION_1_2
            Xid xid = XID.of(
                    "gtrid-wildfly".getBytes(StandardCharsets.UTF_8),
                    "bqual-wildfly".getBytes(StandardCharsets.UTF_8),
                    131077);
            CasualServiceCallRequestMessage callMsg = CasualServiceCallRequestMessage.createBuilder()
                    .setProtocolVersion(ProtocolVersion.VERSION_1_2)
                    .setExecution(UUID.randomUUID())
                    .setServiceName("sakVKAnvandare")
                    .setTimeout(5000L)
                    .setParentName("wildfly-domain")
                    .setXid(xid)
                    .setXatmiFlags(Flag.of(AtmiFlags.NOFLAG))
                    .setServiceBuffer(ServiceBuffer.of(JsonBuffer.of("{}")))
                    .build();

            // In Casual 3.3.x, the message buffer had 8 trailing zero bytes from CALL_DESCRIPTOR allocation
            byte[] normalPayload = callMsg.toNetworkBytes().get(0);
            byte[] wildfly336Payload = new byte[normalPayload.length + 8]; // exact 8 trailing zero bytes!
            System.arraycopy(normalPayload, 0, wildfly336Payload, 0, normalPayload.length);

            // Construct network frame with 406 bytes payload
            se.laz.casual.network.protocol.messages.CasualNWMessageHeader callHeader =
                    se.laz.casual.network.protocol.messages.CasualNWMessageHeader.createBuilder()
                            .setCorrelationId(UUID.randomUUID())
                            .setType(CasualNWMessageType.SERVICE_CALL_REQUEST)
                            .setPayloadSize(wildfly336Payload.length)
                            .build();

            out.write(callHeader.toNetworkBytes());
            out.write(wildfly336Payload);
            out.flush();

            // Read service call reply header (32 bytes header)
            byte[] replyHeaderBytes = in.readNBytes(32);
            long serviceReplyPayloadSize =
                    java.nio.ByteBuffer.wrap(replyHeaderBytes, 24, 8).getLong();
            byte[] serviceReplyPayload = in.readNBytes((int) serviceReplyPayloadSize);

            // Verify clean success without decoder exception and with normalized SERVICE_CALL_REPLY (3101, not 3103)!
            long replyMessageType =
                    java.nio.ByteBuffer.wrap(replyHeaderBytes, 0, 8).getLong();
            assertThat(replyMessageType).isEqualTo((long) CasualNWMessageType.SERVICE_CALL_REPLY.getMessageId());
            assertThat(serviceReplyPayloadSize).isGreaterThan(0);
            wireMockServer.verify(1, postRequestedFor(urlEqualTo("/__casual/services/sakVKAnvandare")));
        }
    }

    @Test
    @DisplayName("Should normalize upstream Protocol 1.3+ reply to Protocol 1.2 client when proxied")
    void shouldNormalizeUpstreamProtocol13ReplyToProtocol12ClientWhenProxied() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();
        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamWireMock)
                .build();
        upstreamGateway.start();

        try {
            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/upstreamAccountBlocked"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"status\":\"ACCOUNT_OK\"}")));

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/upstreamAccountBlocked"))
                    .withMetadata(
                            Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (java.net.Socket socket = new java.net.Socket("127.0.0.1", proxyGateway.getPort())) {
                socket.setSoTimeout(5000);
                java.io.OutputStream out = socket.getOutputStream();
                java.io.InputStream in = socket.getInputStream();

                // Propose [1000, 1001, 1002] to negotiate VERSION_1_2
                se.laz.casual.network.protocol.messages.domain.CasualDomainConnectRequestMessage connectReq =
                        se.laz.casual.network.protocol.messages.domain.CasualDomainConnectRequestMessage.createBuilder()
                                .withExecution(UUID.randomUUID())
                                .withDomainId(UUID.randomUUID())
                                .withDomainName("wildfly-domain")
                                .withProtocols(List.of(1000L, 1001L, 1002L))
                                .build();
                se.laz.casual.api.network.protocol.messages.CasualNWMessage<
                                se.laz.casual.network.protocol.messages.domain.CasualDomainConnectRequestMessage>
                        connectEnvelope = se.laz.casual.network.protocol.messages.CasualNWMessageImpl.of(
                                UUID.randomUUID(), connectReq);
                for (byte[] b : connectEnvelope.toNetworkBytes()) {
                    out.write(b);
                }
                out.flush();

                byte[] connectReplyHeader = in.readNBytes(32);
                long replyPayloadSize =
                        java.nio.ByteBuffer.wrap(connectReplyHeader, 24, 8).getLong();
                byte[] connectReplyPayload = in.readNBytes((int) replyPayloadSize);
                long negotiatedVersion = java.nio.ByteBuffer.wrap(
                                connectReplyPayload, connectReplyPayload.length - 8, 8)
                        .getLong();
                assertThat(negotiatedVersion).isEqualTo(1002L);

                // Call unmatched service on proxy gateway -> forwarded upstream
                Xid originalXid = XID.of(
                        "gtrid-test".getBytes(StandardCharsets.UTF_8),
                        "bqual-test".getBytes(StandardCharsets.UTF_8),
                        131077);
                CasualServiceCallRequestMessage callMsg = CasualServiceCallRequestMessage.createBuilder()
                        .setProtocolVersion(ProtocolVersion.VERSION_1_2)
                        .setExecution(UUID.randomUUID())
                        .setServiceName("upstreamAccountBlocked")
                        .setTimeout(5000L)
                        .setParentName("wildfly-domain")
                        .setXid(originalXid)
                        .setXatmiFlags(Flag.of(AtmiFlags.NOFLAG))
                        .setServiceBuffer(ServiceBuffer.of(JsonBuffer.of("{}")))
                        .build();

                se.laz.casual.api.network.protocol.messages.CasualNWMessage<CasualServiceCallRequestMessage>
                        callEnvelope = se.laz.casual.network.protocol.messages.CasualNWMessageImpl.of(
                                UUID.randomUUID(), callMsg);
                for (byte[] b : callEnvelope.toNetworkBytes()) {
                    out.write(b);
                }
                out.flush();

                byte[] replyHeaderBytes = in.readNBytes(32);
                long replyMessageType =
                        java.nio.ByteBuffer.wrap(replyHeaderBytes, 0, 8).getLong();
                long serviceReplyPayloadSize =
                        java.nio.ByteBuffer.wrap(replyHeaderBytes, 24, 8).getLong();
                byte[] serviceReplyPayload = in.readNBytes((int) serviceReplyPayloadSize);

                // Upstream returned 3103 (SERVICE_CALL_REPLY_V_1_3), but gateway MUST have normalized to 3101
                // (SERVICE_CALL_REPLY)
                assertThat(replyMessageType).isEqualTo((long) CasualNWMessageType.SERVICE_CALL_REPLY.getMessageId());
                assertThat(serviceReplyPayloadSize).isGreaterThan(0);
                upstreamWireMock.verify(1, postRequestedFor(urlEqualTo("/__casual/services/upstreamAccountBlocked")));
            } finally {
                proxyGateway.close();
            }
        } finally {
            upstreamGateway.close();
            upstreamWireMock.stop();
        }
    }

    @Test
    @DisplayName("Should extract CSTRING buffer payload into human-readable string in journal and replay offline")
    void shouldExtractCStringBufferHumanReadable() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();
        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamWireMock)
                .build();
        upstreamGateway.start();

        try {
            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/cstringService"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "text/plain")
                            .withHeader("X-Casual-Buffer-Type", "CSTRING/")
                            .withBody("Hello from upstream CSTRING")));

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/cstringService"))
                    .atPriority(10)
                    .withMetadata(
                            Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (SimpleCasualTestClient proxyClient = new SimpleCasualTestClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                byte[] cstringBytes = "Inbound CSTRING request\0".getBytes(StandardCharsets.UTF_8);
                ServiceBuffer requestBuffer = ServiceBuffer.of("CSTRING/", List.of(cstringBytes));

                CasualServiceCallReplyMessage reply1 = proxyClient.tpcall("cstringService", requestBuffer);
                assertThat(reply1.getError()).isEqualTo(ErrorState.OK);
                assertThat(reply1.getServiceBuffer().getType()).isEqualTo("CSTRING/");
                String replyText =
                        new String(CasualBufferUtils.toByteArray(reply1.getServiceBuffer()), StandardCharsets.UTF_8);
                assertThat(replyText).contains("Hello from upstream CSTRING");

                // Verify the Request Journal captured the string body without trailing null
                wireMockServer.verify(postRequestedFor(urlEqualTo("/__casual/services/cstringService"))
                        .withRequestBody(equalTo("Inbound CSTRING request")));

                // Snapshot record journal entries into offline stub mappings
                wireMockServer.snapshotRecord(recordSpec()
                        .makeStubsPersistent(false)
                        .extractTextBodiesOver(Long.MAX_VALUE)
                        .extractBinaryBodiesOver(Long.MAX_VALUE));

                upstreamGateway.close();
                upstreamWireMock.stop();

                CasualServiceCallReplyMessage reply2 = proxyClient.tpcall("cstringService", requestBuffer);
                assertThat(reply2.getError()).isEqualTo(ErrorState.OK);
                assertThat(reply2.getServiceBuffer().getType()).isEqualTo("CSTRING/");
                String replayedText =
                        new String(CasualBufferUtils.toByteArray(reply2.getServiceBuffer()), StandardCharsets.UTF_8);
                assertThat(replayedText).contains("Hello from upstream CSTRING");
            } finally {
                proxyGateway.close();
            }
        } finally {
            if (upstreamGateway.isRunning()) {
                upstreamGateway.close();
            }
            if (upstreamWireMock.isRunning()) {
                upstreamWireMock.stop();
            }
        }
    }

    @Test
    @DisplayName("Should stub fielded service call with offline mock returning valid binary CFIELD buffer")
    void shouldStubFieldedServiceCallWithOfflineMockReturningValidCfieldBuffer() throws Exception {
        String fieldTableJson =
                """
        {
          "groups": [
            {
              "base": 1000,
              "fields": [
                { "name": "FLD_USER_ID", "id": 1, "type": "long" },
                { "name": "FLD_USER_NAME", "id": 2, "type": "string" },
                { "name": "FLD_ARCHIVE_DATA", "id": 9, "type": "binary" }
              ]
            }
          ]
        }
        """;
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("casual-fields-gateway-test", ".json");
        temp.toFile().deleteOnExit();
        java.nio.file.Files.writeString(temp, fieldTableJson, StandardCharsets.UTF_8);

        try {
            System.setProperty("CASUAL_FIELD_TABLE", temp.toAbsolutePath().toString());
            assertThat(CasualBufferUtils.isFieldTableAvailable()).isTrue();

            String binaryPayloadB64 =
                    Base64.getEncoder().encodeToString("{\"caseId\":\"12356\"}".getBytes(StandardCharsets.UTF_8));
            String responseJson = String.format(
                    java.util.Locale.ROOT,
                    """
                    {
                      "FLD_USER_ID": 1001,
                      "FLD_USER_NAME": "Alice",
                      "FLD_ARCHIVE_DATA": "%s"
                    }
                    """,
                    binaryPayloadB64);

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/crmArchiveService"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withHeader("X-Casual-Buffer-Type", "CFIELD/")
                            .withBody(responseJson)));

            ServiceBuffer requestBuffer = ServiceBuffer.of(".FIELDED", List.of(new byte[] {
                0x00, 0x00, 0x00, 0x00, 0x04, 0x00, 0x03, (byte) 0xE9, // FLD_USER_ID
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x08,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, (byte) 0xE9
            }));

            CasualServiceCallReplyMessage reply = client.tpcall("crmArchiveService", requestBuffer);

            assertThat(reply.getError()).isEqualTo(ErrorState.OK);
            assertThat(reply.getServiceBuffer().getType()).isEqualTo("CFIELD/");

            // Verify the response bytes are valid binary FML, NOT raw JSON ASCII
            byte[] rawBytes = CasualBufferUtils.toByteArray(reply.getServiceBuffer());
            String asciiPrefix = new String(rawBytes, StandardCharsets.UTF_8);
            assertThat(asciiPrefix).doesNotStartWith("{");

            // Verify decoding via FieldedTypeBuffer succeeds without throwing CasualFieldedLookupException
            se.laz.casual.api.buffer.type.fielded.FieldedTypeBuffer decoded =
                    se.laz.casual.api.buffer.type.fielded.FieldedTypeBuffer.create(
                            reply.getServiceBuffer().getBytes());
            assertThat(decoded.read("FLD_USER_ID").getData()).isEqualTo(1001L);
            assertThat(decoded.read("FLD_USER_NAME").getData()).isEqualTo("Alice");

            byte[] decodedBinary = (byte[]) decoded.read("FLD_ARCHIVE_DATA").getData();
            assertThat(new String(decodedBinary, StandardCharsets.UTF_8)).isEqualTo("{\"caseId\":\"12356\"}");
        } finally {
            System.clearProperty("CASUAL_FIELD_TABLE");
            se.laz.casual.config.ConfigurationService.setConfiguration(
                    se.laz.casual.config.ConfigurationOptions.CASUAL_FIELD_TABLE, null);
        }
    }
}
