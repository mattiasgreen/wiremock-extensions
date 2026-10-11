package com.github.mattiasgreen.wiremock.bundle;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

public class StandaloneDevServer {

    public static void main(String[] args) {
        int port = 8080;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {
            }
        }

        System.setProperty("wiremock.verbose.json", "true");

        WireMockServer server =
                new WireMockServer(WireMockConfiguration.options().port(port).extensionScanningEnabled(true));

        server.start();

        server.stubFor(get(urlEqualTo("/api/v1/users"))
                .withName("List Users")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]")));

        java.util.Map<String, Object> orderExample = java.util.Map.of(
                "path", "/api/v1/orders",
                "headers", java.util.Map.of("Content-Type", "application/json"),
                "body",
                        "{\n  \"item\": \"Wireless Noise-Canceling Headphones\",\n  \"quantity\": 1,\n  \"price\": 199.99,\n  \"customer\": \"alex@example.com\"\n}");

        server.stubFor(post(urlEqualTo("/api/v1/orders"))
                .withName("Create Order")
                .withRequestBody(matchingJsonPath("$.item"))
                .withMetadata(com.github.tomakehurst.wiremock.common.Metadata.metadata()
                        .attr("exampleRequest", orderExample))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"orderId\":\"ord-9921\",\"status\":\"CREATED\"}")));

        server.stubFor(get(urlEqualTo("/api/v1/slow"))
                .withName("Slow Latency Simulation")
                .willReturn(aResponse().withStatus(200).withFixedDelay(150).withBody("{\"latency\":\"delayed\"}")));

        server.stubFor(get(urlEqualTo("/api/v1/error"))
                .withName("Internal Server Error Mock")
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Internal Server Error\",\"code\":500}")));

        int netemPort = Integer.getInteger("wiremock.netem.port", port + 1);
        try {
            com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmRegistry.getInstance()
                    .register(new com.github.mattiasgreen.wiremock.netem.model.NetEmProxyConfig(
                            "wiremock-netem", netemPort, "127.0.0.1", port, true));
        } catch (Exception e) {
            System.err.println("Could not bind default TCP NetEm proxy on port " + netemPort + ": " + e.getMessage());
        }

        System.out.println("=================================================================");
        System.out.println("🚀 WireMock Suite Server running at http://localhost:" + port);
        System.out.println("👉 WireMock Console UI: http://localhost:" + port + "/__admin/ui");
        System.out.println(
                "👉 TCP NetEm Ingress:   http://localhost:" + netemPort + " (dual-ingress -> :" + port + ")");
        System.out.println("👉 NetEm Journal:       http://localhost:" + port + "/__admin/tcp-netem/journal");
        System.out.println("👉 Prometheus Metrics:  http://localhost:" + port + "/__admin/metrics/prometheus");
        System.out.println("👉 Tracing & Logging:   W3C Context Propagation & MDC Active");
        System.out.println("👉 Admin API:           http://localhost:" + port + "/__admin/mappings");
        System.out.println("=================================================================");
        System.out.println("Press Ctrl+C to terminate the server.");
    }
}
