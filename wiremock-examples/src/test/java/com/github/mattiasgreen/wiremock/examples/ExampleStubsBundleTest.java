package com.github.mattiasgreen.wiremock.examples;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExampleStubsBundleTest {

    private WireMockServer server;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
    }

    @Test
    void testExampleStubsBundleCanBeImportedAndExecuted() throws Exception {
        Path bundlePath = Files.exists(Path.of("example-stubs.json"))
                ? Path.of("example-stubs.json")
                : Path.of("wiremock-examples", "example-stubs.json");

        assertThat(Files.exists(bundlePath)).isTrue();
        String bundleJson = Files.readString(bundlePath);

        // Import the bundle into WireMock Admin API
        HttpRequest importRequest = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/mappings/import"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(bundleJson))
                .build();

        HttpResponse<String> importResponse = httpClient.send(importRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(importResponse.statusCode()).isEqualTo(200);

        // 1. Verify /api/v1/users
        HttpResponse<String> usersResponse = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(server.baseUrl() + "/api/v1/users"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(usersResponse.statusCode()).isEqualTo(200);
        assertThat(usersResponse.body()).contains("Alice", "Bob");

        // 2. Verify /api/v1/orders
        HttpResponse<String> ordersResponse = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(server.baseUrl() + "/api/v1/orders"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"item\": \"Headphones\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(ordersResponse.statusCode()).isEqualTo(201);
        assertThat(ordersResponse.body()).contains("ord-9921");

        // 3. Verify /api/v1/slow
        HttpResponse<String> slowResponse = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(server.baseUrl() + "/api/v1/slow"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(slowResponse.statusCode()).isEqualTo(200);
        assertThat(slowResponse.body()).contains("delayed");

        // 4. Verify /api/v1/error
        HttpResponse<String> errorResponse = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(server.baseUrl() + "/api/v1/error"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(errorResponse.statusCode()).isEqualTo(500);
        assertThat(errorResponse.body()).contains("Internal Server Error");

        // 5. Verify Casual proxy stub mapping is present in bundle
        assertThat(server.listAllStubMappings().getMappings()).anySatisfy(stub -> {
            assertThat(stub.getName()).isEqualTo("Casual Upstream Dynamic Proxy");
            assertThat(stub.getMetadata().getString("targetBaseUrl")).isEqualTo("casual://127.0.0.1:7771");
        });
    }

    @Test
    void testCasualProxyStubCanBeImportedIndividually() throws Exception {
        Path stubPath = Files.exists(Path.of("casual-proxy-stub.json"))
                ? Path.of("casual-proxy-stub.json")
                : Path.of("wiremock-examples", "casual-proxy-stub.json");

        assertThat(Files.exists(stubPath)).isTrue();
        String stubJson = Files.readString(stubPath);

        HttpRequest importRequest = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/mappings"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(stubJson))
                .build();

        HttpResponse<String> importResponse = httpClient.send(importRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(importResponse.statusCode()).isEqualTo(201);

        assertThat(server.listAllStubMappings().getMappings()).anySatisfy(stub -> {
            assertThat(stub.getName()).isEqualTo("Casual Upstream Dynamic Proxy");
            assertThat(stub.getMetadata().getString("targetBaseUrl")).isEqualTo("casual://127.0.0.1:7771");
            assertThat(stub.getMetadata().getString("mode")).isEqualTo("proxy");
            assertThat(stub.getMetadata().getString("protocol")).isEqualTo("casual");
        });
    }
}
