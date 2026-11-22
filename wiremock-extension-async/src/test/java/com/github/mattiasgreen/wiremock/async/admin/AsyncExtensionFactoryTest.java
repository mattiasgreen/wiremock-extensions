package com.github.mattiasgreen.wiremock.async.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mattiasgreen.wiremock.async.bridge.MessageBroker;
import com.github.mattiasgreen.wiremock.async.connection.BrokerConnection;
import com.github.mattiasgreen.wiremock.async.engine.AsyncEngine;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.extension.ExtensionFactory;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AsyncExtensionFactoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Should instantiate both AsyncAdminEndpoint and AsyncMessagePublishingListener")
    void shouldCreateAsyncExtensions() {
        AsyncExtensionFactory factory = new AsyncExtensionFactory();
        List<Extension> extensions = factory.create(null);

        assertThat(extensions).hasSize(2);
        assertThat(extensions.stream().anyMatch(e -> e instanceof AsyncAdminEndpoint))
                .isTrue();
        assertThat(extensions.stream().anyMatch(e -> e.getName().equals("async-message-publisher")))
                .isTrue();
    }

    @Test
    @DisplayName("Should be discoverable via java.util.ServiceLoader")
    void shouldBeDiscoverableViaSpi() {
        ServiceLoader<ExtensionFactory> loader = ServiceLoader.load(ExtensionFactory.class);
        boolean found = false;
        for (ExtensionFactory factory : loader) {
            if (factory instanceof AsyncExtensionFactory) {
                found = true;
                break;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    @DisplayName("Should isolate AsyncEngine, AsyncBrokerRegistry, and stub stores across multiple WireMockServers")
    void shouldIsolateAsyncEnginesAcrossMultipleWireMockServers() throws Exception {
        WireMockServer serverA = null;
        WireMockServer serverB = null;
        try {
            serverA = new WireMockServer(
                    WireMockConfiguration.options().dynamicPort().extensions(new AsyncExtensionFactory()));
            serverA.start();

            serverB = new WireMockServer(
                    WireMockConfiguration.options().dynamicPort().extensions(new AsyncExtensionFactory()));
            serverB.start();

            HttpClient client = HttpClient.newHttpClient();
            String connJson =
                    """
                    {
                      "id": "isolated-kafka-a",
                      "protocol": "kafka",
                      "name": "Server A Kafka",
                      "endpoint": "kafka-a:9092"
                    }
                    """;
            HttpResponse<String> postConnA = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(serverA.baseUrl() + "/__admin/async/connections"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(connJson))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(postConnA.statusCode()).isEqualTo(201);

            HttpResponse<String> getConnA = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(serverA.baseUrl() + "/__admin/async/connections"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> getConnB = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(serverB.baseUrl() + "/__admin/async/connections"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(MAPPER.readTree(getConnA.body()).get("count").asInt()).isEqualTo(1);
            assertThat(MAPPER.readTree(getConnB.body()).get("count").asInt()).isZero();

            serverA.stubFor(WireMock.post(WireMock.urlEqualTo("/__async/kafka/orders"))
                    .willReturn(WireMock.ok("ok"))
                    .withMetadata(Map.of("async", Map.of("mode", "mock", "channel", "orders"))));

            JsonNode statusA = MAPPER.readTree(client.send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create(serverA.baseUrl() + "/__admin/async/status"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString())
                    .body());
            JsonNode statusB = MAPPER.readTree(client.send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create(serverB.baseUrl() + "/__admin/async/status"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString())
                    .body());

            assertThat(statusA.get("asyncStubCount").asLong()).isEqualTo(1L);
            assertThat(statusB.get("asyncStubCount").asLong()).isZero();
        } finally {
            if (serverA != null && serverA.isRunning()) {
                serverA.stop();
            }
            if (serverB != null && serverB.isRunning()) {
                serverB.stop();
            }
        }
    }

    @Test
    @DisplayName("Should automatically close AsyncEngine and AsyncBrokerRegistry when WireMockServer stops")
    void shouldCloseEngineAndBrokerRegistryWhenWireMockServerStops() throws Exception {
        AsyncEngine engine = AsyncEngine.create();
        AtomicInteger brokerCloseCount = new AtomicInteger(0);
        engine.getBrokerRegistry()
                .registerBrokerFactory("custom-pool", conn -> new CloseTrackingBroker(brokerCloseCount));
        engine.getBrokerRegistry()
                .register(
                        new BrokerConnection("pooled-1", "custom-pool", "Pooled", "custom://localhost:1234", Map.of()));
        assertThat(engine.getBrokerRegistry().getOrCreateBroker("pooled-1")).isPresent();

        WireMockServer server = new WireMockServer(
                WireMockConfiguration.options().dynamicPort().extensions(new AsyncExtensionFactory(engine)));
        server.start();

        engine.startKafkaSink(0, record -> {});
        engine.startAmqpServer(0);
        int kafkaPort = engine.getKafkaSinkPort();
        int amqpPort = engine.getAmqpServerPort();
        Thread subscription = engine.subscribe("orders.in", msg -> {});

        assertThat(engine.isKafkaSinkRunning()).isTrue();
        assertThat(engine.isAmqpServerRunning()).isTrue();
        assertThat(kafkaPort).isPositive();
        assertThat(amqpPort).isPositive();

        // Stop server WITHOUT calling engine.close()
        server.stop();

        assertThat(engine.isKafkaSinkRunning()).isFalse();
        assertThat(engine.isAmqpServerRunning()).isFalse();
        assertThat(brokerCloseCount.get()).isEqualTo(1);

        subscription.join(2_000);
        assertThat(subscription.isAlive()).isFalse();

        // Bound ports must be released and immediately re-bindable
        try (ServerSocket kafkaCheck = new ServerSocket(kafkaPort);
                ServerSocket amqpCheck = new ServerSocket(amqpPort)) {
            assertThat(kafkaCheck.isBound()).isTrue();
            assertThat(amqpCheck.isBound()).isTrue();
        }
    }

    @Test
    @DisplayName("Should automatically close AsyncEngine and AsyncBrokerRegistry on POST /__admin/shutdown")
    void shouldCloseEngineAndBrokerRegistryOnAdminShutdown() throws Exception {
        AsyncEngine engine = AsyncEngine.create();
        AtomicInteger brokerCloseCount = new AtomicInteger(0);
        engine.getBrokerRegistry()
                .registerBrokerFactory("custom-pool", conn -> new CloseTrackingBroker(brokerCloseCount));
        engine.getBrokerRegistry()
                .register(new BrokerConnection(
                        "pooled-2", "custom-pool", "Pooled 2", "custom://localhost:1234", Map.of()));
        assertThat(engine.getBrokerRegistry().getOrCreateBroker("pooled-2")).isPresent();

        WireMockServer server = new WireMockServer(
                WireMockConfiguration.options().dynamicPort().extensions(new AsyncExtensionFactory(engine)));
        server.start();
        engine.startKafkaSink(0, record -> {});
        assertThat(engine.isKafkaSinkRunning()).isTrue();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(server.baseUrl() + "/__admin/shutdown"))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);

        // Wait briefly for WireMock's async shutdown thread to finish stopping Jetty
        long deadline = System.currentTimeMillis() + 3_000;
        while (server.isRunning() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }

        assertThat(engine.isKafkaSinkRunning()).isFalse();
        assertThat(brokerCloseCount.get()).isEqualTo(1);
    }

    private static final class CloseTrackingBroker implements MessageBroker, AutoCloseable {
        private final AtomicInteger closeCount;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private CloseTrackingBroker(AtomicInteger closeCount) {
            this.closeCount = closeCount;
        }

        @Override
        public void send(String channel, AsyncMessage message) {}

        @Override
        public AsyncMessage poll(String channel) {
            return null;
        }

        @Override
        public int size(String channel) {
            return 0;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                closeCount.incrementAndGet();
            }
        }
    }
}
