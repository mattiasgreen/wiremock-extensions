package com.github.mattiasgreen.wiremock.async.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mattiasgreen.wiremock.async.connection.AsyncBrokerRegistry;
import com.github.mattiasgreen.wiremock.async.engine.AsyncEngine;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.extension.ExtensionFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AsyncExtensionFactoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Should instantiate both AsyncAdminEndpoint and AsyncMessagePublishingListener")
    void shouldCreateAsyncExtensions() {
        AsyncEngine previousEngine = AsyncEngine.getInstance();
        AsyncBrokerRegistry previousRegistry = AsyncBrokerRegistry.getInstance();
        try {
            AsyncExtensionFactory factory = new AsyncExtensionFactory();
            List<Extension> extensions = factory.create(null);

            assertThat(extensions).hasSize(2);
            assertThat(extensions.stream().anyMatch(e -> e instanceof AsyncAdminEndpoint))
                    .isTrue();
            assertThat(extensions.stream().anyMatch(e -> e.getName().equals("async-message-publisher")))
                    .isTrue();
        } finally {
            AsyncEngine.setDefault(previousEngine);
            AsyncBrokerRegistry.setDefault(previousRegistry);
        }
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
        AsyncEngine previousEngine = AsyncEngine.getInstance();
        AsyncBrokerRegistry previousRegistry = AsyncBrokerRegistry.getInstance();
        WireMockServer serverA = null;
        WireMockServer serverB = null;
        AsyncEngine engineA = null;
        AsyncEngine engineB = null;
        try {
            serverA = new WireMockServer(
                    WireMockConfiguration.options().dynamicPort().extensions(new AsyncExtensionFactory()));
            serverA.start();
            engineA = AsyncEngine.getInstance();

            serverB = new WireMockServer(
                    WireMockConfiguration.options().dynamicPort().extensions(new AsyncExtensionFactory()));
            serverB.start();
            engineB = AsyncEngine.getInstance();

            assertThat(engineA).isNotSameAs(engineB);
            assertThat(engineA.getBrokerRegistry()).isNotSameAs(engineB.getBrokerRegistry());

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
            if (engineA != null) {
                engineA.close();
            }
            if (engineB != null) {
                engineB.close();
            }
            if (serverA != null && serverA.isRunning()) {
                serverA.stop();
            }
            if (serverB != null && serverB.isRunning()) {
                serverB.stop();
            }
            AsyncEngine.setDefault(previousEngine);
            AsyncBrokerRegistry.setDefault(previousRegistry);
        }
    }
}
