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
        if (hasHelpFlag(args)) {
            printHelp();
            return;
        }
        int port = parseMainPort(args, 8080);
        String rootDir = parseRootDir(args, ".");
        WireMockServer server = startServer(port, rootDir);

        int netemPort = resolveNetEmPort(server.port());
        CasualConfiguration casualConfig = CasualRegistry.getInstance().getConfig();

        System.out.println("=================================================================");
        System.out.println("🚀 WireMock Suite Server running at http://localhost:" + server.port());
        System.out.println("👉 WireMock Console UI: http://localhost:" + server.port() + "/__admin/ui");
        System.out.println("👉 Root Directory:      " + rootDir);
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

    public static boolean hasHelpFlag(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if ("--help".equalsIgnoreCase(arg) || "-help".equalsIgnoreCase(arg) || "-h".equalsIgnoreCase(arg)) {
                return true;
            }
        }
        return false;
    }

    public static void printHelp() {
        System.out.println("WireMock Standalone with All Extensions");
        System.out.println("Usage: java -jar wiremock-extension-bundle-standalone.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --port <port>             HTTP port to bind (default: 8080)");
        System.out.println("  --root-dir <path>         Path for mappings and __files (default: .)");
        System.out.println("  --help, -h                Show this help message");
        System.out.println();
        System.out.println("Environment Variables:");
        System.out.println("  WIREMOCK_NETEM_PORT       Dedicated Layer 4 TCP proxy port (default: 8081)");
        System.out.println("  WIREMOCK_CASUAL_PORT      Dedicated TCP port for Casual / XATMI gateway (default: 7772)");
        System.out.println("  WIREMOCK_CASUAL_ENABLED   Enable/disable Casual TCP gateway (default: true)");
        System.out.println();
        System.out.println("For standard WireMock CLI options, run with WireMock runner:");
        System.out.println("  java -cp wiremock-*-standalone.jar wiremock.Run --help");
    }

    public static String parseRootDir(String[] args, String defaultDir) {
        if (args == null || args.length == 0) {
            return defaultDir;
        }
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--root-dir".equals(arg) || "-root-dir".equals(arg)) {
                if (i + 1 < args.length) {
                    return args[i + 1];
                }
            } else if (arg.startsWith("--root-dir=")) {
                return arg.substring("--root-dir=".length());
            } else if (arg.startsWith("-root-dir=")) {
                return arg.substring("-root-dir=".length());
            }
        }
        return defaultDir;
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
        return startServer(port, ".");
    }

    public static WireMockServer startServer(int port, String rootDir) {
        System.setProperty("wiremock.verbose.json", "true");

        String effectiveRootDir = (rootDir != null && !rootDir.isBlank()) ? rootDir : ".";
        WireMockConfiguration options = WireMockConfiguration.options()
                .port(port)
                .usingFilesUnderDirectory(effectiveRootDir)
                .extensionScanningEnabled(true);
        WireMockServer server = new WireMockServer(options);
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
