package com.github.mattiasgreen.wiremock.bundle;

import com.github.mattiasgreen.wiremock.casual.CasualWireMockGateway;
import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmRegistry;
import com.github.mattiasgreen.wiremock.netem.model.NetEmProxyConfig;
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

        WireMockServer server = startServer(port);

        int netemPort = Integer.getInteger("wiremock.netem.port", server.port() + 1);
        int casualPort = Integer.getInteger("wiremock.casual.port", 7772);

        System.out.println("=================================================================");
        System.out.println("🚀 WireMock Suite Server running at http://localhost:" + server.port());
        System.out.println("👉 WireMock Console UI: http://localhost:" + server.port() + "/__admin/ui");
        System.out.println(
                "👉 TCP NetEm Ingress:   http://localhost:" + netemPort + " (dual-ingress -> :" + server.port() + ")");
        System.out.println("👉 Casual TCP Gateway:  casual://127.0.0.1:" + casualPort);
        System.out.println("👉 NetEm Journal:       http://localhost:" + server.port() + "/__admin/tcp-netem/journal");
        System.out.println("👉 Prometheus Metrics:  http://localhost:" + server.port() + "/__admin/metrics/prometheus");
        System.out.println("👉 Tracing & Logging:   W3C Context Propagation & MDC Active");
        System.out.println("👉 Admin API:           http://localhost:" + server.port() + "/__admin/mappings");
        System.out.println("=================================================================");
        System.out.println("Press Ctrl+C to terminate the server.");
    }

    public static WireMockServer startServer(int port) {
        System.setProperty("wiremock.verbose.json", "true");

        WireMockServer server =
                new WireMockServer(WireMockConfiguration.options().port(port).extensionScanningEnabled(true));
        server.start();

        int netemPort = Integer.getInteger("wiremock.netem.port", server.port() + 1);
        try {
            TcpNetEmRegistry.getInstance()
                    .register(new NetEmProxyConfig("wiremock-netem", netemPort, "127.0.0.1", server.port(), true));
        } catch (Exception e) {
            System.err.println("Could not bind default TCP NetEm proxy on port " + netemPort + ": " + e.getMessage());
        }

        int casualPort = Integer.getInteger("wiremock.casual.port", 7772);
        try {
            CasualWireMockGateway casualGateway = CasualWireMockGateway.builder()
                    .port(casualPort)
                    .wireMockBaseUrl("http://localhost:" + server.port())
                    .build();
            casualGateway.start();
        } catch (Exception e) {
            System.err.println(
                    "Could not bind default Casual TCP gateway on port " + casualPort + ": " + e.getMessage());
        }

        return server;
    }
}
