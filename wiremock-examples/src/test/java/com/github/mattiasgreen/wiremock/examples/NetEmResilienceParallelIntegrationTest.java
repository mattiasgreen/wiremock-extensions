package com.github.mattiasgreen.wiremock.examples;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.netem.admin.TcpNetEmExtensionFactory;
import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmProxy;
import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmRegistry;
import com.github.mattiasgreen.wiremock.netem.model.NetEmProxyConfig;
import com.github.mattiasgreen.wiremock.netem.model.NetEmStreamDirection;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicConfig;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicType;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <h1>Enterprise Reference Pattern: Zero Blast-Radius Parallel Chaos Testing with Resilience4j</h1>
 *
 * <p><strong>Architectural Problem:</strong>
 * In enterprise microservice architectures, WireMock is typically deployed centrally in shared
 * CI/CD environments. When teams perform fault injection or chaos engineering tests (such as
 * simulating network partitions, packet drops, or sudden TCP resets), traditional mock servers
 * inject global delays or errors that crash parallel functional tests running simultaneously.
 *
 * <p><strong>The Solution: Dual Ingress & Correlation-Scoped TCP NetEm</strong>
 * WireMock TCP NetEm operates as a virtual-thread TCP L4 proxy on port 8081 forwarding to WireMock on 8080.
 * Impairments are scoped to individual session correlation IDs (via {@code X-Correlation-Id}).
 *
 * <p><strong>Key Invariants Demonstrated by this Test:</strong>
 * <ul>
 *   <li><strong>Zero Blast Radius:</strong> Thread A executes normal regression tests (100% fast 200 OK)
 *       while Thread B simultaneously undergoes severe TCP RST chaos on the exact same endpoint.</li>
 *   <li><strong>Resilience4j Integration:</strong> Thread B's client circuit breaker detects network
 *       failures, transitions from {@code CLOSED} to {@code OPEN}, and seamlessly invokes the fallback.</li>
 *   <li><strong>High-Throughput Ring Buffer Journal:</strong> The proxy records each transfer in a compact,
 *       pre-allocated O(1) ring buffer without GC thrashing.</li>
 * </ul>
 *
 * <p><strong>Memory & Heap Sizing Formula for Central WireMock Servers:</strong>
 * <pre>
 *   Journal Memory (MB) = (RingBufferCapacity * 128 bytes) / (1024 * 1024)
 *   - 10,000 entries (Default)   ~ 1.2 MB heap
 *   - 50,000 entries (Medium CI) ~ 6.1 MB heap
 *   - 100,000 entries (Heavy CI) ~ 12.2 MB heap
 * </pre>
 * Recommended JVM heap for central mock servers handling 1,000+ requests/sec:
 * {@code -Xms512m -Xmx2g -XX:+UseG1GC}.
 */
class NetEmResilienceParallelIntegrationTest {

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

        // Register default TCP NetEm proxy forwarding to WireMock
        netEmProxy = TcpNetEmRegistry.getInstance()
                .register(new NetEmProxyConfig("netem-resilience", 0, "127.0.0.1", wireMockServer.port(), true));
        netEmPort = netEmProxy.getPort();

        // Shared business endpoint
        wireMockServer.stubFor(get(urlEqualTo("/api/v1/payments/status"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"AUTHORIZED\",\"amount\":150.00}")));

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
            "Parallel execution: Functional tests pass 100% while Chaos tests trip Resilience4j CircuitBreaker with zero interference")
    void shouldRunParallelFunctionalAndChaosTestsWithResilience4j() throws Exception {
        // 1. Configure Resilience4j Circuit Breaker for the Chaos Client
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50.0f)
                .build();
        CircuitBreaker chaosCircuitBreaker = CircuitBreaker.of("paymentServiceChaos", cbConfig);

        // 2. Inject TCP RST impairment ONLY for stream pattern: "chaos-session-77"
        NetEmToxicConfig rstToxic = NetEmToxicConfig.builder("targeted-rst", NetEmToxicType.RESET_PEER).stream(
                        NetEmStreamDirection.DOWNSTREAM)
                .toxicity(1.0)
                .pattern("chaos-session-77")
                .build();
        netEmProxy.addToxic(rstToxic);

        // 3. Launch parallel threads:
        //    Thread 1: Functional Regression Test (X-Correlation-Id: "functional-session-99")
        //    Thread 2: Chaos Engineering Test (X-Correlation-Id: "chaos-session-77")
        int iterations = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger functionalSuccessCount = new AtomicInteger(0);
        AtomicInteger chaosFallbackCount = new AtomicInteger(0);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // Task A: Functional verification
            Future<Void> functionalTask = executor.submit(() -> {
                startLatch.await();
                for (int i = 0; i < iterations; i++) {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create("http://127.0.0.1:" + netEmPort + "/api/v1/payments/status"))
                            .header("X-Correlation-Id", "functional-session-99")
                            .GET()
                            .build();
                    HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200 && resp.body().contains("AUTHORIZED")) {
                        functionalSuccessCount.incrementAndGet();
                    }
                }
                return null;
            });

            // Task B: Chaos resilience test guarded by Resilience4j CircuitBreaker
            Future<Void> chaosTask = executor.submit(() -> {
                startLatch.await();
                for (int i = 0; i < iterations; i++) {
                    Callable<String> decorated = CircuitBreaker.decorateCallable(chaosCircuitBreaker, () -> {
                        HttpRequest req = HttpRequest.newBuilder()
                                .uri(URI.create("http://127.0.0.1:" + netEmPort + "/api/v1/payments/status"))
                                .header("X-Correlation-Id", "chaos-session-77")
                                .GET()
                                .build();
                        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                        return resp.body();
                    });

                    try {
                        decorated.call();
                    } catch (Exception e) {
                        // Fallback triggered by either network exception or Open circuit breaker
                        chaosFallbackCount.incrementAndGet();
                    }
                }
                return null;
            });

            // Trigger both threads simultaneously
            startLatch.countDown();
            functionalTask.get(10, TimeUnit.SECONDS);
            chaosTask.get(10, TimeUnit.SECONDS);
        }

        // --- VERIFICATIONS ---

        // Thread A: 100% of functional requests succeeded fast with zero errors!
        assertThat(functionalSuccessCount.get())
                .as("Functional suite must succeed 100% without any interference")
                .isEqualTo(iterations);

        // Thread B: All chaos requests failed at L4 transport and triggered Resilience4j fallback!
        assertThat(chaosFallbackCount.get())
                .as("Chaos suite must trigger fallback on network failures")
                .isEqualTo(iterations);

        // Resilience4j CircuitBreaker tripped to OPEN state!
        assertThat(chaosCircuitBreaker.getState())
                .as("Circuit Breaker must transition to OPEN after repeated TCP resets")
                .isEqualTo(CircuitBreaker.State.OPEN);

        // Journal verification: wait briefly for asynchronous virtual thread stats recording
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline && netEmProxy.getStats().getNormalTransfers() < iterations) {
            Thread.sleep(20);
        }

        assertThat(netEmProxy.getStats().getNormalTransfers())
                .as("Normal transfers must record all functional requests")
                .isEqualTo(iterations);
        // Note: The circuit breaker trips to OPEN after failure threshold is exceeded (e.g. 2-4 calls),
        // short-circuiting subsequent calls so they never touch the network!
        assertThat(netEmProxy.getStats().getImpairedTransfers())
                .as("Impaired transfers record network calls before circuit breaker short-circuits")
                .isBetween(2L, (long) iterations);
    }
}
