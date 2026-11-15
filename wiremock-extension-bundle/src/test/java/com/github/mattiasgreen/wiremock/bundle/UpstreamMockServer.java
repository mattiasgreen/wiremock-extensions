package com.github.mattiasgreen.wiremock.bundle;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * Lightweight mock upstream service running on port 8089 to simulate an authentic
 * external API backend for testing live proxying, traffic recording, and mode toggling.
 */
public class UpstreamMockServer {

    public static void main(String[] args) {
        int port = 8089;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {
            }
        }

        WireMockServer server =
                new WireMockServer(WireMockConfiguration.options().port(port));
        server.start();

        // 1. Live Catalog Endpoint
        server.stubFor(
                get(urlEqualTo("/api/v1/live-catalog"))
                        .withName("Live Upstream Catalog API")
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withHeader("X-Upstream-Server", "Warehouse-Alpha")
                                        .withBody(
                                                """
                                {
                                  "service": "Inventory & Catalog Upstream",
                                  "environment": "production-live",
                                  "items": [
                                    {"id": "item-101", "name": "Mechanical Keyboard Pro", "price": 149.99, "stock": 42},
                                    {"id": "item-102", "name": "Ultra-Wide Gaming Monitor 34\\"", "price": 499.00, "stock": 15},
                                    {"id": "item-103", "name": "Wireless Ergonomic Mouse", "price": 79.50, "stock": 88}
                                  ]
                                }
                                """)));

        // 2. Real-time Inventory Status Endpoint
        server.stubFor(
                get(urlPathMatching("/api/v1/inventory/.*"))
                        .withName("Live Inventory Check API")
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withHeader("X-Upstream-Server", "Warehouse-Alpha")
                                        .withBody(
                                                """
                                {
                                  "status": "IN_STOCK",
                                  "location": "Warehouse-East-Bay",
                                  "lastAudit": "2026-10-05T08:00:00Z"
                                }
                                """)));

        System.out.println("=================================================================");
        System.out.println("🏭 Upstream Mock Service running at http://localhost:" + port);
        System.out.println("👉 Catalog Endpoint:   http://localhost:" + port + "/api/v1/live-catalog");
        System.out.println("👉 Inventory Endpoint: http://localhost:" + port + "/api/v1/inventory/item-101");
        System.out.println("=================================================================");
        System.out.println("Press Ctrl+C to terminate this upstream service.");
    }
}
