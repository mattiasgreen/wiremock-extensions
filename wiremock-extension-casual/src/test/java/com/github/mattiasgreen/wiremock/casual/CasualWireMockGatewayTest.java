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
import se.laz.casual.api.buffer.type.ServiceBuffer;
import se.laz.casual.api.flags.ErrorState;
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
}
