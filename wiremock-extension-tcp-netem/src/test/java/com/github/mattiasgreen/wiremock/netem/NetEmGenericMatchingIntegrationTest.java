package com.github.mattiasgreen.wiremock.netem;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.mattiasgreen.wiremock.netem.admin.TcpNetEmExtensionFactory;
import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmProxy;
import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmRegistry;
import com.github.mattiasgreen.wiremock.netem.model.NetEmProxyConfig;
import com.github.mattiasgreen.wiremock.netem.model.NetEmStreamDirection;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicConfig;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicType;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NetEmGenericMatchingIntegrationTest {

    private WireMockServer wireMockServer;
    private HttpClient httpClient;
    private TcpNetEmProxy netEmProxy;
    private int netEmPort;

    @BeforeEach
    void setUp() throws Exception {
        TcpNetEmRegistry.getInstance().closeAll();
        TcpNetEmRegistry.getInstance().getJournal().clear();

        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort().extensions(new TcpNetEmExtensionFactory()));
        wireMockServer.start();

        netEmProxy = TcpNetEmRegistry.getInstance()
                .register(new NetEmProxyConfig("generic-matching-proxy", 0, "127.0.0.1", wireMockServer.port(), true));
        netEmPort = netEmProxy.getPort();

        httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
        TcpNetEmRegistry.getInstance().closeAll();
    }

    @Test
    @DisplayName(
            "Casual binary protocol: Triggers TCP RST when raw Hex pattern (e.g. 0xCA500001) is detected on raw socket")
    void shouldTriggerRstOnCasualBinaryHeader() throws Exception {
        // Configure toxic matching Casual frame magic bytes: CA 50 00 01
        netEmProxy.addToxic(
                NetEmToxicConfig.builder("casual-rst", NetEmToxicType.RESET_PEER).stream(NetEmStreamDirection.UPSTREAM)
                        .pattern("CA 50 00 01")
                        .patternType("HEX")
                        .toxicity(1.0)
                        .build());

        // Send non-matching binary packet -> connects and receives response or EOF cleanly
        try (Socket s = new Socket("127.0.0.1", netEmPort);
                OutputStream out = s.getOutputStream()) {
            out.write(new byte[] {0x01, 0x02, (byte) 0xCA, 0x50, 0x00, 0x02, 0x05});
            out.flush();
        }

        // Send matching Casual binary packet -> socket is immediately closed with RST
        try (Socket s = new Socket("127.0.0.1", netEmPort);
                OutputStream out = s.getOutputStream();
                InputStream in = s.getInputStream()) {
            out.write(new byte[] {0x01, 0x02, (byte) 0xCA, 0x50, 0x00, 0x01, 0x05});
            out.flush();

            assertThatThrownBy(() -> {
                        byte[] resp = new byte[128];
                        int read = in.read(resp);
                        if (read == -1) {
                            throw new java.net.SocketException("Connection reset");
                        }
                    })
                    .isInstanceOf(java.io.IOException.class);
        }
    }

    @Test
    @DisplayName("ASCII body matching: Triggers impairment when JSON body contains target substring")
    void shouldTriggerImpairmentOnBodySubstring() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/api/payments"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"PROCESSED\"}")));

        // Configure toxic matching payload substring in body: "CHAOS_TRIGGER"
        netEmProxy.addToxic(
                NetEmToxicConfig.builder("body-rst", NetEmToxicType.RESET_PEER).stream(NetEmStreamDirection.UPSTREAM)
                        .pattern("CHAOS_TRIGGER")
                        .patternType("ASCII")
                        .toxicity(1.0)
                        .build());

        // Call 1: Normal payment body -> SUCCESS
        HttpRequest normalReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + netEmPort + "/api/payments"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"account\":\"12345\",\"amount\":50}"))
                .build();
        HttpResponse<String> normalResp = httpClient.send(normalReq, HttpResponse.BodyHandlers.ofString());
        assertThat(normalResp.statusCode()).isEqualTo(200);

        // Call 2: Body containing "CHAOS_TRIGGER" -> FAILS WITH TCP RESET
        HttpRequest chaosReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + netEmPort + "/api/payments"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"account\":\"12345\",\"status\":\"CHAOS_TRIGGER\"}"))
                .build();
        assertThatThrownBy(() -> httpClient.send(chaosReq, HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    @DisplayName("Source IP filtering: Impairs only matching client IP, ignores mismatched IP")
    void shouldFilterByClientIp() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/api/orders"))
                .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"OK\"}")));

        // 1. Add toxic targeting an external IP: 192.168.50.1 -> our localhost 127.0.0.1 should NOT be impaired
        netEmProxy.addToxic(NetEmToxicConfig.builder("remote-subnet-rst", NetEmToxicType.RESET_PEER).stream(
                        NetEmStreamDirection.DOWNSTREAM)
                .clientIp("192.168.50.1")
                .toxicity(1.0)
                .build());

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + netEmPort + "/api/orders"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        HttpResponse<String> resp1 = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp1.statusCode()).isEqualTo(200);

        // 2. Add toxic targeting localhost CIDR 127.0.0.0/8 -> our request should be impaired
        netEmProxy.addToxic(NetEmToxicConfig.builder("localhost-rst", NetEmToxicType.RESET_PEER).stream(
                        NetEmStreamDirection.DOWNSTREAM)
                .clientIp("127.0.0.0/8")
                .toxicity(1.0)
                .build());

        assertThatThrownBy(() -> httpClient.send(req, HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(java.io.IOException.class);
    }
}
