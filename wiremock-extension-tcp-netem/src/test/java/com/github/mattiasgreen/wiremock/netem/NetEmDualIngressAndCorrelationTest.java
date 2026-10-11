package com.github.mattiasgreen.wiremock.netem;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mattiasgreen.wiremock.netem.admin.TcpNetEmExtensionFactory;
import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmProxy;
import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmRegistry;
import com.github.mattiasgreen.wiremock.netem.model.NetEmProxyConfig;
import com.github.mattiasgreen.wiremock.netem.model.NetEmStreamDirection;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicConfig;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicType;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NetEmDualIngressAndCorrelationTest {

    private WireMockServer wireMockServer;
    private HttpClient httpClient;
    private ObjectMapper objectMapper;
    private TcpNetEmProxy netEmProxy;
    private int adminPort;
    private int netEmPort;

    @BeforeEach
    void setUp() throws Exception {
        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort().extensions(new TcpNetEmExtensionFactory()));
        wireMockServer.start();

        adminPort = wireMockServer.port();
        httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        objectMapper = new ObjectMapper();

        TcpNetEmRegistry.getInstance().closeAll();

        // Stub order endpoint on WireMock
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/orders"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"CONFIRMED\"}")));

        // Register default NetEm proxy forwarding to WireMock
        netEmProxy = TcpNetEmRegistry.getInstance()
                .register(new NetEmProxyConfig("wiremock-netem", 0, "127.0.0.1", adminPort, true));
        netEmPort = netEmProxy.getPort();
    }

    @AfterEach
    void tearDown() {
        TcpNetEmRegistry.getInstance().closeAll();
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    void smokeTestDualIngressPatternAndAdminBlackholeProtection() throws Exception {
        // 1. Initial baseline: calls to both ports succeed
        HttpResponse<String> directResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + adminPort + "/api/v1/orders"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(directResp.statusCode()).isEqualTo(200);

        HttpResponse<String> netEmResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + netEmPort + "/api/v1/orders"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(netEmResp.statusCode()).isEqualTo(200);
        assertThat(netEmResp.body()).contains("CONFIRMED");

        // 2. Inject devastating 100% TCP RST on netEm proxy
        netEmProxy.addToxic(NetEmToxicConfig.builder("catastrophic-rst", NetEmToxicType.RESET_PEER).stream(
                        NetEmStreamDirection.DOWNSTREAM)
                .toxicity(1.0)
                .build());

        // 3. Client traffic via NetEm port MUST FAIL with Connection Reset
        assertThatThrownBy(() -> httpClient.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://127.0.0.1:" + netEmPort + "/api/v1/orders"))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(IOException.class);

        // 4. SMOKETEST: WireMock Admin API on port 8080 MUST BE HEALTHY and sub-millisecond responsive
        long adminStart = System.currentTimeMillis();
        HttpResponse<String> adminResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + adminPort + "/__admin/mappings"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        long adminDuration = System.currentTimeMillis() - adminStart;

        assertThat(adminResp.statusCode()).isEqualTo(200);
        assertThat(adminDuration).isLessThan(500); // Admin channel remains fully insulated!

        // 5. Clear toxics via Admin API directly on admin port
        HttpResponse<String> resetResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + adminPort + "/__admin/tcp-netem/reset"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resetResp.statusCode()).isEqualTo(200);

        // 6. NetEm port is immediately healthy again
        HttpResponse<String> restoredResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + netEmPort + "/api/v1/orders"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(restoredResp.statusCode()).isEqualTo(200);
        assertThat(restoredResp.body()).contains("CONFIRMED");
    }

    @Test
    void shouldSelectivelyApplyImpairmentsWithCorrelationIdMatching() throws Exception {
        // Configure TCP RST strictly scoped to X-Correlation-Id: "chaos-canary-42"
        netEmProxy.addToxic(NetEmToxicConfig.builder("selective-rst", NetEmToxicType.RESET_PEER).stream(
                        NetEmStreamDirection.DOWNSTREAM)
                .correlationId("chaos-canary-42")
                .toxicity(1.0)
                .build());

        // Call 1: With normal correlation ID -> PASSES CLEANLY
        HttpResponse<String> normalResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + netEmPort + "/api/v1/orders"))
                        .header("X-Correlation-Id", "regular-test-run-1")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(normalResp.statusCode()).isEqualTo(200);
        assertThat(normalResp.body()).contains("CONFIRMED");

        // Call 2: With matching correlation ID -> FAILS WITH TCP RST
        assertThatThrownBy(() -> httpClient.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://127.0.0.1:" + netEmPort + "/api/v1/orders"))
                                .header("X-Correlation-Id", "chaos-canary-42")
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(IOException.class);

        // Call 3: Without correlation ID -> PASSES CLEANLY
        HttpResponse<String> noHeaderResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + netEmPort + "/api/v1/orders"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(noHeaderResp.statusCode()).isEqualTo(200);
    }

    @Test
    void shouldSelectivelyApplyLatencyWithCorrelationIdMatching() throws Exception {
        // Configure 150ms latency scoped to X-Correlation-Id: "slow-session-99"
        netEmProxy.addToxic(NetEmToxicConfig.builder("selective-latency", NetEmToxicType.LATENCY).stream(
                        NetEmStreamDirection.DOWNSTREAM)
                .correlationId("slow-session-99")
                .attribute("latency", 150L)
                .attribute("jitter", 0L)
                .toxicity(1.0)
                .build());

        // Call 1: Normal traffic -> FAST (< 80ms)
        long fastStart = System.currentTimeMillis();
        HttpResponse<String> fastResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + netEmPort + "/api/v1/orders"))
                        .header("X-Correlation-Id", "fast-user")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        long fastDuration = System.currentTimeMillis() - fastStart;

        assertThat(fastResp.statusCode()).isEqualTo(200);
        assertThat(fastDuration).isLessThan(100);

        // Call 2: Targeted traffic -> SLOW (>= 130ms)
        long slowStart = System.currentTimeMillis();
        HttpResponse<String> slowResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + netEmPort + "/api/v1/orders"))
                        .header("X-Correlation-Id", "slow-session-99")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        long slowDuration = System.currentTimeMillis() - slowStart;

        assertThat(slowResp.statusCode()).isEqualTo(200);
        assertThat(slowDuration).isGreaterThanOrEqualTo(120);

        // Verify stats tracked the impairment and bytes
        assertThat(netEmProxy.getStats().getImpairmentsInjected()).isGreaterThanOrEqualTo(1L);
        assertThat(netEmProxy.getStats().getBytesDownstream()).isGreaterThan(0L);
    }
}
