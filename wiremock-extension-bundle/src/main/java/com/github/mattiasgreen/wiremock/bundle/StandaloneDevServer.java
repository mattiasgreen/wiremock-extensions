package com.github.mattiasgreen.wiremock.bundle;

import com.github.mattiasgreen.wiremock.casual.CasualConfiguration;
import com.github.mattiasgreen.wiremock.casual.CasualRegistry;
import com.github.mattiasgreen.wiremock.casual.CasualWireMockGateway;
import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmRegistry;
import com.github.mattiasgreen.wiremock.netem.model.NetEmProxyConfig;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

public class StandaloneDevServer {

    public static final String ENV_NETEM_PORT = "WIREMOCK_NETEM_PORT";
    public static final String PROP_NETEM_PORT = "wiremock.netem.port";
    public static final int DEFAULT_NETEM_PORT = 8081;

    public static void main(String[] args) {
        int port = parseMainPort(args, 8080);
        WireMockServer server = startServer(port);

        int netemPort = resolveNetEmPort(server.port());
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
            System.out.println(
                    "👉 Casual TCP Gateway:  casual://127.0.0.1:" + casualPort + " (stub-driven proxy & 2PC enabled)");
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

    public static int parseMainPort(String[] args, int defaultPort) {
        if (args == null || args.length == 0) {
            return defaultPort;
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--port".equals(arg) || "-port".equals(arg)) {
                if (i + 1 < args.length) {
                    try {
                        return Integer.parseInt(args[i + 1]);
                    } catch (NumberFormatException ignored) {
                    }
                }
            } else if (arg.startsWith("--port=")) {
                try {
                    return Integer.parseInt(arg.substring("--port=".length()));
                } catch (NumberFormatException ignored) {
                }
            } else if (arg.startsWith("-port=")) {
                try {
                    return Integer.parseInt(arg.substring("-port=".length()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        for (String arg : args) {
            if (arg != null && !arg.startsWith("-")) {
                try {
                    return Integer.parseInt(arg);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return defaultPort;
    }

    public static int resolveNetEmPort(int wireMockPort) {
        String env = System.getenv(ENV_NETEM_PORT);
        if (env != null && !env.isBlank()) {
            try {
                return Integer.parseInt(env.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        String prop = System.getProperty(PROP_NETEM_PORT);
        if (prop != null && !prop.isBlank()) {
            try {
                return Integer.parseInt(prop.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return wireMockPort > 0 ? (wireMockPort == 8080 ? DEFAULT_NETEM_PORT : wireMockPort + 1) : 0;
    }

    public static WireMockServer startServer(int port) {
        System.setProperty("wiremock.verbose.json", "true");

        WireMockServer server =
                new WireMockServer(WireMockConfiguration.options().port(port).extensionScanningEnabled(true));
        server.start();

        int netemPort = resolveNetEmPort(server.port());
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
                CasualWireMockGateway casualGateway = CasualWireMockGateway.builder()
                        .port(casualConfig.getPort())
                        .wireMockServer(server)
                        .build();
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
