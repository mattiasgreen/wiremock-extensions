package com.github.mattiasgreen.wiremock.bundle;

import com.github.mattiasgreen.wiremock.casual.CasualConfiguration;
import com.github.mattiasgreen.wiremock.casual.CasualRegistry;
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
        CasualConfiguration casualConfig = CasualRegistry.getInstance().getConfig();

        System.out.println("=================================================================");
        System.out.println("🚀 WireMock Suite Server running at http://localhost:" + server.port());
        System.out.println("👉 WireMock Console UI: http://localhost:" + server.port() + "/__admin/ui");
        System.out.println(
                "👉 TCP NetEm Ingress:   http://localhost:" + netemPort + " (dual-ingress -> :" + server.port() + ")");
        if (casualConfig.isEnabled()) {
            int casualPort = CasualRegistry.getInstance()
                    .getGateway()
                    .map(CasualWireMockGateway::getPort)
                    .orElse(casualConfig.getPort());
            String upstreamDesc = casualConfig.hasUpstreamConfigured()
                    ? (casualConfig.getUpstreamHost() + ":" + casualConfig.getUpstreamPort()
                            + (casualConfig.isUpstreamEnabled() ? " [ENABLED]" : " [DISABLED]"))
                    : "NONE";
            System.out.println("👉 Casual TCP Gateway:  casual://127.0.0.1:" + casualPort + " (upstream: "
                    + upstreamDesc + ", record: " + (casualConfig.isRecordStubs() ? "ON" : "OFF") + ")");
            System.out.println("👉 Casual Config API:   http://localhost:" + server.port() + "/__admin/casual/config");
        } else {
            System.out.println("👉 Casual TCP Gateway:  DISABLED");
        }
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

        CasualConfiguration casualConfig = CasualConfiguration.fromEnvironment();
        CasualRegistry.getInstance().setConfig(casualConfig);

        if (casualConfig.isEnabled()) {
            try {
                CasualWireMockGateway.Builder gatewayBuilder = CasualWireMockGateway.builder()
                        .port(casualConfig.getPort())
                        .wireMockBaseUrl("http://localhost:" + server.port())
                        .recordStubs(casualConfig.isRecordStubs())
                        .recordFaults(casualConfig.isRecordFaults())
                        .recordNotFound(casualConfig.isRecordNotFound());

                if (casualConfig.hasUpstreamConfigured()) {
                    gatewayBuilder.upstream(casualConfig.getUpstreamHost(), casualConfig.getUpstreamPort());
                    gatewayBuilder.upstreamEnabled(casualConfig.isUpstreamEnabled());
                }

                CasualWireMockGateway casualGateway = gatewayBuilder.build();
                casualGateway.start();
                CasualRegistry.getInstance().setGateway(casualGateway);
            } catch (Exception e) {
                System.err.println("Could not bind default Casual TCP gateway on port " + casualConfig.getPort() + ": "
                        + e.getMessage());
            }
        }

        return server;
    }
}
