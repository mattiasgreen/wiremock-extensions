package com.github.mattiasgreen.wiremock.netem;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.mattiasgreen.wiremock.netem.admin.TcpNetEmExtensionFactory;
import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmProxy;
import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmRegistry;
import com.github.mattiasgreen.wiremock.netem.model.NetEmProxyConfig;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.Metadata;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NetEmDeclarativeStubTest {

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
                .register(new NetEmProxyConfig("declarative-proxy", 0, "127.0.0.1", wireMockServer.port(), true));
        netEmPort = netEmProxy.getPort();

        httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
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
    @DisplayName("Declarative stub metadata dynamically applies toxics scoped to the request")
    void shouldApplyToxicsFromDeclarativeStubMetadata() throws Exception {
        // 1. Normal stub (no netem metadata)
        wireMockServer.stubFor(get(urlEqualTo("/api/normal"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"fast\"}")));

        // 2. Latency stub (declared via metadata.netem.toxics)
        wireMockServer.stubFor(get(urlEqualTo("/api/declarative-latency"))
                .withMetadata(Metadata.metadata()
                        .attr(
                                "netem",
                                Map.of(
                                        "toxics",
                                        List.of(Map.of(
                                                "name", "metadata-latency",
                                                "type", "latency",
                                                "stream", "downstream",
                                                "toxicity", 1.0,
                                                "attributes", Map.of("latency", 200L))))))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"delayed\"}")));

        // 3. Reset peer stub (declared via metadata.netem.toxics)
        wireMockServer.stubFor(get(urlEqualTo("/api/declarative-rst"))
                .withMetadata(Metadata.metadata()
                        .attr(
                                "netem",
                                Map.of(
                                        "toxics",
                                        List.of(Map.of(
                                                "name", "metadata-rst",
                                                "type", "reset_peer",
                                                "stream", "downstream",
                                                "toxicity", 1.0)))))
                .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"never_seen\"}")));

        // Warm-up call to avoid JVM/Jetty class-loading jitter
        httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + netEmPort + "/api/normal"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // Call 1: Normal request -> fast 200 OK
        long t0 = System.currentTimeMillis();
        HttpResponse<String> normalResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + netEmPort + "/api/normal"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        long normalDuration = System.currentTimeMillis() - t0;
        assertThat(normalResp.statusCode()).isEqualTo(200);
        assertThat(normalResp.body()).contains("fast");
        assertThat(normalDuration).isLessThan(100);

        // Call 2: Declarative latency request -> delayed 200 OK
        long t1 = System.currentTimeMillis();
        HttpResponse<String> latencyResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + netEmPort + "/api/declarative-latency"))
                        .header("X-Correlation-Id", "session-decl-latency")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        long latencyDuration = System.currentTimeMillis() - t1;
        assertThat(latencyResp.statusCode()).isEqualTo(200);
        assertThat(latencyResp.body()).contains("delayed");
        assertThat(latencyDuration).isGreaterThanOrEqualTo(150);

        // Call 3: Declarative RST request -> throws IOException (connection reset)
        assertThatThrownBy(() -> httpClient.send(
                        HttpRequest.newBuilder()
                                .uri(URI.create("http://127.0.0.1:" + netEmPort + "/api/declarative-rst"))
                                .header("X-Correlation-Id", "session-decl-rst")
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(IOException.class);

        // Call 4: Subsequent normal request -> fast 200 OK (no leftover toxics!)
        HttpResponse<String> postNormalResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + netEmPort + "/api/normal"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(postNormalResp.statusCode()).isEqualTo(200);
        assertThat(postNormalResp.body()).contains("fast");

        // Call 5: Wait for asynchronous cleanup after grace period & TTL
        long deadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < deadline && !netEmProxy.getToxics().isEmpty()) {
            Thread.sleep(50);
        }
        assertThat(netEmProxy.getToxics()).isEmpty();
    }
}
