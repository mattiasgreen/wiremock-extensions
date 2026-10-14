package com.github.mattiasgreen.wiremock.casual;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import se.laz.casual.api.buffer.type.ServiceBuffer;
import se.laz.casual.api.flags.ErrorState;
import se.laz.casual.api.flags.TransactionState;
import se.laz.casual.network.protocol.messages.domain.CasualDomainDiscoveryReplyMessage;
import se.laz.casual.network.protocol.messages.domain.Service;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallReplyMessage;

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

        gateway = new CasualWireMockGateway(0, wireMockServer.baseUrl());
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
    @DisplayName("Should forward unmatched service call to upstream Casual server")
    void shouldForwardUnmatchedServiceToUpstreamCasualServer() throws Exception {
        // Set up upstream WireMock server and upstream Casual gateway
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();
        CasualWireMockGateway upstreamGateway = new CasualWireMockGateway(0, upstreamWireMock.baseUrl());
        upstreamGateway.start();

        try {
            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/upstreamOrderService"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"orderId\": \"UPSTREAM-123\", \"routedVia\": \"upstream\"}")));

            // Configure proxy gateway pointing to upstream
            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockBaseUrl(wireMockServer.baseUrl())
                    .upstream("127.0.0.1", upstreamGateway.getPort())
                    .recordStubs(false)
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
    @DisplayName("Should forward unmatched call to upstream and record stub for subsequent replay")
    void shouldForwardAndRecordUnmatchedServiceWhenRecordStubsEnabled() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();
        CasualWireMockGateway upstreamGateway = new CasualWireMockGateway(0, upstreamWireMock.baseUrl());
        upstreamGateway.start();

        try {
            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/recordedInventoryService"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"sku\": \"SKU-555\", \"inStock\": true}")));

            // Configure proxy gateway pointing to upstream with recordStubs = true
            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockBaseUrl(wireMockServer.baseUrl())
                    .upstream("127.0.0.1", upstreamGateway.getPort())
                    .recordStubs(true)
                    .build();
            proxyGateway.start();

            try (SimpleCasualTestClient proxyClient = new SimpleCasualTestClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                // 1st Call: Unmatched in wireMockServer -> forwarded to upstream and recorded
                CasualServiceCallReplyMessage firstReply =
                        proxyClient.tpcall("recordedInventoryService", "{\"sku\": \"SKU-555\"}");

                assertThat(firstReply).isNotNull();
                assertThat(firstReply.getError()).isEqualTo(ErrorState.OK);
                String firstBody =
                        new String(firstReply.getServiceBuffer().getBytes().get(0), StandardCharsets.UTF_8);
                assertThat(firstBody).contains("SKU-555").contains("true");

                // Upstream received exactly 1 request
                upstreamWireMock.verify(1, postRequestedFor(urlEqualTo("/__casual/services/recordedInventoryService")));

                // 2nd Call: Must match the newly recorded stub in wireMockServer directly!
                CasualServiceCallReplyMessage secondReply =
                        proxyClient.tpcall("recordedInventoryService", "{\"sku\": \"SKU-555\"}");

                assertThat(secondReply).isNotNull();
                assertThat(secondReply.getError()).isEqualTo(ErrorState.OK);
                String secondBody =
                        new String(secondReply.getServiceBuffer().getBytes().get(0), StandardCharsets.UTF_8);
                assertThat(secondBody).contains("SKU-555").contains("true");

                // Upstream must STILL have only been called once! The second call was served from recorded WireMock
                // stub
                upstreamWireMock.verify(1, postRequestedFor(urlEqualTo("/__casual/services/recordedInventoryService")));

                // Verify wireMockServer now has the recorded stub mapping
                wireMockServer.verify(
                        2,
                        postRequestedFor(urlEqualTo("/__casual/services/recordedInventoryService"))
                                .withRequestBody(equalToJson("{\"sku\": \"SKU-555\"}")));
            } finally {
                proxyGateway.close();
            }
        } finally {
            upstreamGateway.close();
            upstreamWireMock.stop();
        }
    }

    @Test
    @DisplayName("Should forward upstream error states transparently to client")
    void shouldForwardUpstreamErrorStates() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();
        CasualWireMockGateway upstreamGateway = new CasualWireMockGateway(0, upstreamWireMock.baseUrl());
        upstreamGateway.start();

        try {
            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/upstreamFailing"))
                    .willReturn(aResponse().withStatus(500).withBody("Upstream fatal error")));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockBaseUrl(wireMockServer.baseUrl())
                    .upstream("127.0.0.1", upstreamGateway.getPort())
                    .recordStubs(false)
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
    @DisplayName("Should record and replay upstream faults when recordStubs and recordFaults are enabled")
    void shouldRecordAndReplayUpstreamFaultsWhenRecordStubsEnabled() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();
        CasualWireMockGateway upstreamGateway = new CasualWireMockGateway(0, upstreamWireMock.baseUrl());
        upstreamGateway.start();

        try {
            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/upstreamAccountBlocked"))
                    .willReturn(aResponse()
                            .withStatus(500)
                            .withHeader("Content-Type", "application/json")
                            .withHeader("X-Casual-Error-State", "TPESVCFAIL")
                            .withBody("{\"error\": \"Account blocked\", \"code\": \"ACC-BLOCKED\"}")));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockBaseUrl(wireMockServer.baseUrl())
                    .upstream("127.0.0.1", upstreamGateway.getPort())
                    .recordStubs(true)
                    .recordFaults(true)
                    .build();
            proxyGateway.start();

            try (SimpleCasualTestClient proxyClient = new SimpleCasualTestClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                // 1st Call: Forwarded to upstream and recorded as a fault stub
                CasualServiceCallReplyMessage firstReply =
                        proxyClient.tpcall("upstreamAccountBlocked", "{\"accountId\": \"ACC-999\"}");

                assertThat(firstReply).isNotNull();
                assertThat(firstReply.getError()).isEqualTo(ErrorState.TPESVCFAIL);
                String firstBody =
                        new String(firstReply.getServiceBuffer().getBytes().get(0), StandardCharsets.UTF_8);
                assertThat(firstBody).contains("Account blocked").contains("ACC-BLOCKED");

                upstreamWireMock.verify(1, postRequestedFor(urlEqualTo("/__casual/services/upstreamAccountBlocked")));

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

    @Test
    @DisplayName("Should NOT record TPENOENT by default so subsequent calls continue to forward upstream")
    void shouldNotRecordTpenoentByDefault() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();
        CasualWireMockGateway upstreamGateway = new CasualWireMockGateway(0, upstreamWireMock.baseUrl());
        upstreamGateway.start();

        try {
            // Upstream returns TPENOENT (no stub / 404)
            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockBaseUrl(wireMockServer.baseUrl())
                    .upstream("127.0.0.1", upstreamGateway.getPort())
                    .recordStubs(true)
                    .recordFaults(true)
                    // recordNotFound is false by default
                    .build();
            proxyGateway.start();

            try (SimpleCasualTestClient proxyClient = new SimpleCasualTestClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                CasualServiceCallReplyMessage reply1 = proxyClient.tpcall("missingService", "{}");
                assertThat(reply1.getError()).isEqualTo(ErrorState.TPENOENT);

                CasualServiceCallReplyMessage reply2 = proxyClient.tpcall("missingService", "{}");
                assertThat(reply2.getError()).isEqualTo(ErrorState.TPENOENT);

                // Both calls reached upstream because TPENOENT was NOT recorded as a matching stub!
                upstreamWireMock.verify(2, postRequestedFor(urlEqualTo("/__casual/services/missingService")));
            } finally {
                proxyGateway.close();
            }
        } finally {
            upstreamGateway.close();
            upstreamWireMock.stop();
        }
    }

    @Test
    @DisplayName("Should record TPENOENT when recordNotFound is explicitly enabled")
    void shouldRecordTpenoentWhenRecordNotFoundExplicitlyEnabled() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();
        CasualWireMockGateway upstreamGateway = new CasualWireMockGateway(0, upstreamWireMock.baseUrl());
        upstreamGateway.start();

        try {
            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockBaseUrl(wireMockServer.baseUrl())
                    .upstream("127.0.0.1", upstreamGateway.getPort())
                    .recordStubs(true)
                    .recordFaults(true)
                    .recordNotFound(true)
                    .build();
            proxyGateway.start();

            try (SimpleCasualTestClient proxyClient = new SimpleCasualTestClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                CasualServiceCallReplyMessage reply1 = proxyClient.tpcall("notFoundService", "{}");
                assertThat(reply1.getError()).isEqualTo(ErrorState.TPENOENT);

                CasualServiceCallReplyMessage reply2 = proxyClient.tpcall("notFoundService", "{}");
                assertThat(reply2.getError()).isEqualTo(ErrorState.TPENOENT);

                // Upstream called only once because TPENOENT was recorded into wireMockServer!
                upstreamWireMock.verify(1, postRequestedFor(urlEqualTo("/__casual/services/notFoundService")));
            } finally {
                proxyGateway.close();
            }
        } finally {
            upstreamGateway.close();
            upstreamWireMock.stop();
        }
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
        CasualWireMockGateway upstreamGateway = new CasualWireMockGateway(0, upstreamWireMock.baseUrl());
        upstreamGateway.start();

        try {
            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/timedOutTxService"))
                    .willReturn(aResponse()
                            .withStatus(500)
                            .withHeader("Content-Type", "application/json")
                            .withHeader("X-Casual-Error-State", "TPESVCFAIL")
                            .withHeader("X-Casual-Transaction-State", "TIMEOUT_ROLLBACK_ONLY")
                            .withBody("{\"error\": \"Transaction deadline exceeded\"}")));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockBaseUrl(wireMockServer.baseUrl())
                    .upstream("127.0.0.1", upstreamGateway.getPort())
                    .recordStubs(true)
                    .recordFaults(true)
                    .build();
            proxyGateway.start();

            try (SimpleCasualTestClient proxyClient = new SimpleCasualTestClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                // 1st call: Forwarded upstream & recorded
                CasualServiceCallReplyMessage reply1 = proxyClient.tpcall("timedOutTxService", "{}");
                assertThat(reply1.getTransactionState()).isEqualTo(TransactionState.TIMEOUT_ROLLBACK_ONLY);
                assertThat(reply1.getError()).isEqualTo(ErrorState.TPESVCFAIL); // Recorded as 500 fault

                upstreamWireMock.verify(1, postRequestedFor(urlEqualTo("/__casual/services/timedOutTxService")));

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
}
