package com.github.mattiasgreen.wiremock.casual;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
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
 * can be stubbed via WireMock HTTP definitions without a C++ server or Jakarta EE app server.
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
                client.tpcall("createOrder", "{\"amount\": 150.0, \"currency\": \"SEK\"}");

        assertThat(reply).isNotNull();
        assertThat(reply.getError()).isEqualTo(ErrorState.OK);

        ServiceBuffer buffer = reply.getServiceBuffer();
        assertThat(buffer).isNotNull();
        assertThat(buffer.getType()).isEqualTo(se.laz.casual.api.buffer.CasualBufferType.JSON.getName());

        String responsePayload = new String(buffer.getBytes().get(0), StandardCharsets.UTF_8);
        assertThat(responsePayload).contains("ORD-9988").contains("CONFIRMED");
    }

    @Test
    @DisplayName("Should return TPENOENT error when WireMock returns 404 for unstubbed service")
    void shouldReturnTpenoentWhenServiceNotFound() throws Exception {
        CasualServiceCallReplyMessage reply = client.tpcall("nonExistentService", "{}");

        assertThat(reply).isNotNull();
        assertThat(reply.getError()).isEqualTo(ErrorState.TPENOENT);
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
}
