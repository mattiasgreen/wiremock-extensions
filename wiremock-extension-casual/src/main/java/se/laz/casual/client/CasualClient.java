package se.laz.casual.client;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import se.laz.casual.api.buffer.CasualBufferUtils;
import se.laz.casual.api.buffer.type.ServiceBuffer;
import se.laz.casual.network.ProtocolVersion;
import se.laz.casual.network.protocol.messages.domain.CasualDomainDiscoveryReplyMessage;
import se.laz.casual.network.protocol.messages.domain.Service;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallReplyMessage;

/**
 * High-level, standalone client and CLI runner for communicating with Casual domains over raw TCP.
 * <p>
 * Supports programmatic usage ({@link #call(String, String)}, {@link #discover(String...)})
 * and standalone CLI execution ({@link #main(String[])}) for manual testing and smoke-testing.
 * <p>
 * Intended for upstream contribution to {@code se.laz.casual:casual-client}.
 */
public class CasualClient implements AutoCloseable {

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 7772;
    public static final int DEFAULT_TIMEOUT_SECONDS = 4;

    private final CasualTcpClient tcpClient;
    private final String host;
    private final int port;
    private final int timeoutSeconds;

    public CasualClient(String host, int port) {
        this(host, port, DEFAULT_TIMEOUT_SECONDS);
    }

    public CasualClient(String host, int port, int timeoutSeconds) {
        this(host, port, UUID.randomUUID(), "casual-client", timeoutSeconds);
    }

    public CasualClient(String host, int port, UUID domainId, String domainName, int timeoutSeconds) {
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.port = port;
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
        this.tcpClient = new CasualTcpClient(host, port, domainId, domainName, this.timeoutSeconds);
    }

    public CasualClient(CasualTcpClient tcpClient) {
        this.tcpClient = Objects.requireNonNull(tcpClient, "tcpClient must not be null");
        this.host = DEFAULT_HOST;
        this.port = DEFAULT_PORT;
        this.timeoutSeconds = tcpClient.getDefaultTimeoutSeconds();
    }

    public static CasualClient create(String host, int port) {
        return new CasualClient(host, port);
    }

    public static CasualClient create(String host, int port, int timeoutSeconds) {
        return new CasualClient(host, port, timeoutSeconds);
    }

    public synchronized CasualClient connect() throws Exception {
        if (!tcpClient.isConnected()) {
            tcpClient.connect();
        }
        return this;
    }

    public synchronized boolean isConnected() {
        return tcpClient.isConnected();
    }

    public ProtocolVersion getProtocolVersion() {
        return tcpClient.getProtocolVersion();
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    /**
     * Executes a Casual service call (tpcall) with a JSON payload.
     * Automatically connects if not already connected.
     */
    public CasualCallResult call(String serviceName, String jsonPayload) throws Exception {
        return call(serviceName, "JSON", jsonPayload);
    }

    /**
     * Executes a Casual service call (tpcall) with specified buffer type and payload.
     * Automatically connects if not already connected.
     */
    public CasualCallResult call(String serviceName, String bufferType, String payload) throws Exception {
        Objects.requireNonNull(serviceName, "serviceName must not be null");
        connect();

        ServiceBuffer buffer = CasualBufferUtils.wrapPayload(bufferType, payload);
        long startNanos = System.nanoTime();
        CasualServiceCallReplyMessage reply = tcpClient.tpcall(serviceName, buffer);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        String replyType = reply.getServiceBuffer() != null
                ? reply.getServiceBuffer().getType()
                : (bufferType != null ? bufferType : "JSON");
        String replyPayload = CasualBufferUtils.extractPayload(reply.getServiceBuffer());

        return new CasualCallResult(
                serviceName,
                reply.getError(),
                reply.getUserDefinedCode(),
                reply.getExecution(),
                replyType,
                replyPayload,
                durationMs);
    }

    /**
     * Discovers advertised services from the connected Casual domain.
     */
    public CasualDomainDiscoveryReplyMessage discover(String... serviceNames) throws Exception {
        return discover(serviceNames != null ? Arrays.asList(serviceNames) : Collections.emptyList());
    }

    /**
     * Discovers advertised services from the connected Casual domain.
     */
    public CasualDomainDiscoveryReplyMessage discover(List<String> serviceNames) throws Exception {
        connect();
        return tcpClient.discoverServices(serviceNames);
    }

    /**
     * Performs a handshake ping to verify connection and domain negotiation.
     */
    public boolean ping() {
        try {
            connect();
            return isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public synchronized void close() {
        tcpClient.close();
    }

    // =========================================================================
    // CLI Entry Point & Runner
    // =========================================================================

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err, System.in);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, System.in);
    }

    public static int run(String[] args, PrintStream out, PrintStream err, InputStream in) {
        if (args == null || args.length == 0 || hasFlag(args, "-h") || hasFlag(args, "--help")) {
            printUsage(out);
            return 0;
        }

        String host = getOption(args, "-H", "--host", DEFAULT_HOST);
        int port = getIntOption(args, "-p", "--port", DEFAULT_PORT);
        int timeout = getIntOption(args, "-t", "--timeout", DEFAULT_TIMEOUT_SECONDS);
        String bufferType = getOption(args, "-b", "--buffer-type", "JSON");
        String filePath = getOption(args, "-f", "--file", null);
        boolean noColor = hasFlag(args, "--no-color") || System.getenv("NO_COLOR") != null;

        ConsoleColor color = new ConsoleColor(!noColor);

        // Find non-flag arguments (positional)
        List<String> positionals = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("-")) {
                if (arg.equals("-H")
                        || arg.equals("--host")
                        || arg.equals("-p")
                        || arg.equals("--port")
                        || arg.equals("-t")
                        || arg.equals("--timeout")
                        || arg.equals("-b")
                        || arg.equals("--buffer-type")
                        || arg.equals("-f")
                        || arg.equals("--file")) {
                    i++; // Skip option value
                }
            } else {
                positionals.add(arg);
            }
        }

        if (positionals.isEmpty()) {
            err.println(color.red("Error: No command specified."));
            printUsage(err);
            return 1;
        }

        String command = positionals.get(0).toLowerCase(Locale.ROOT);

        try (CasualClient client = new CasualClient(host, port, timeout)) {
            switch (command) {
                case "ping" -> {
                    return executePing(client, host, port, color, out, err);
                }
                case "discover", "discovery" -> {
                    List<String> services = positionals.size() > 1
                            ? positionals.subList(1, positionals.size())
                            : Collections.emptyList();
                    return executeDiscover(client, host, port, services, color, out, err);
                }
                case "call", "tpcall" -> {
                    if (positionals.size() < 2) {
                        err.println(
                                color.red(
                                        "Error: 'call' command requires a service name. Example: CasualClient call createOrder '{\"amount\": 100}'"));
                        return 1;
                    }
                    String serviceName = positionals.get(1);
                    String payload = resolvePayload(positionals.size() > 2 ? positionals.get(2) : null, filePath, in);
                    return executeCall(client, host, port, serviceName, bufferType, payload, color, out, err);
                }
                default -> {
                    err.println(color.red("Error: Unknown command '" + command + "'."));
                    printUsage(err);
                    return 1;
                }
            }
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            err.println(color.red("Execution failed: " + msg));
            return 1;
        }
    }

    private static int executePing(
            CasualClient client, String host, int port, ConsoleColor c, PrintStream out, PrintStream err) {
        out.printf("Connecting to Casual domain at %s:%d...%n", host, port);
        try {
            client.connect();
            out.println(c.green("✓ Connected successfully!"));
            out.printf("  Negotiated Protocol Version: %s%n", client.getProtocolVersion());
            return 0;
        } catch (Exception e) {
            err.println(c.red(String.format(
                    Locale.ROOT, "✗ Failed to connect to Casual domain at %s:%d: %s", host, port, e.getMessage())));
            return 1;
        }
    }

    private static int executeDiscover(
            CasualClient client,
            String host,
            int port,
            List<String> requestedServices,
            ConsoleColor c,
            PrintStream out,
            PrintStream err) {
        try {
            client.connect();
            long startNanos = System.nanoTime();
            CasualDomainDiscoveryReplyMessage reply = client.discover(requestedServices);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            out.println(c.bold("=== Casual Domain Discovery Reply ==="));
            out.printf("Domain Name: %s%n", c.cyan(reply.getDomainName()));
            out.printf("Domain ID:   %s%n", reply.getDomainId());
            out.printf("Latency:     %d ms%n", elapsedMs);

            List<Service> services = reply.getServices();
            out.printf("Services:    %d found%n", services.size());
            for (Service s : services) {
                out.printf("  • %s (type=%s)%n", c.yellow(s.getName()), s.getTransactionType());
            }
            return 0;
        } catch (Exception e) {
            err.println(c.red(
                    String.format(Locale.ROOT, "Discovery failed against %s:%d: %s", host, port, e.getMessage())));
            return 1;
        }
    }

    private static int executeCall(
            CasualClient client,
            String host,
            int port,
            String serviceName,
            String bufferType,
            String payload,
            ConsoleColor c,
            PrintStream out,
            PrintStream err) {
        try {
            out.printf("Calling %s on %s:%d (buffer=%s)...%n", c.cyan(serviceName), host, port, bufferType);
            CasualCallResult result = client.call(serviceName, bufferType, payload);

            out.println(c.bold("=== Casual Service Call Reply ==="));
            out.printf("Service:    %s%n", result.serviceName());

            String statusStr = result.errorState().name();
            if (result.isSuccess()) {
                out.printf(
                        "Status:     %s%n",
                        c.green(statusStr + " (code=" + result.errorState().getValue() + ")"));
            } else {
                out.printf(
                        "Status:     %s%n",
                        c.red(statusStr + " (code=" + result.errorState().getValue() + ")"));
            }

            out.printf("User Code:  %d%n", result.userCode());
            out.printf("Duration:   %d ms%n", result.durationMs());
            if (result.execution() != null) {
                out.printf("Execution:  %s%n", result.execution());
            }
            out.printf("Buffer:     %s%n", result.bufferType());

            out.println(c.bold("Payload:"));
            if (result.payload() != null && !result.payload().isBlank()) {
                out.println(result.payload());
            } else {
                out.println(c.muted("(empty payload)"));
            }

            return result.isSuccess() ? 0 : 2;
        } catch (Exception e) {
            err.println(c.red(String.format(
                    Locale.ROOT, "Call to service '%s' failed on %s:%d: %s", serviceName, host, port, e.getMessage())));
            return 1;
        }
    }

    private static String resolvePayload(String positionalPayload, String filePath, InputStream in) throws IOException {
        if (filePath != null && !filePath.isBlank()) {
            File file = new File(filePath);
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        }
        if (positionalPayload != null) {
            if (positionalPayload.startsWith("@")) {
                File file = new File(positionalPayload.substring(1));
                return Files.readString(file.toPath(), StandardCharsets.UTF_8);
            }
            if (positionalPayload.equals("-")) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            return positionalPayload;
        }
        return "{}";
    }

    private static void printUsage(PrintStream out) {
        out.println(
                """
            CasualClient - Standalone client for testing Casual XATMI protocol services

            Usage:
              CasualClient <command> [options] [arguments]

            Commands:
              call <service> [payload]   Call a Casual service (tpcall)
              discover [services...]     Discover advertised services from Casual domain
              ping                       Check connection and protocol handshake

            Options:
              -H, --host <host>          Casual domain host (default: 127.0.0.1)
              -p, --port <port>          Casual domain TCP port (default: 7772)
              -t, --timeout <seconds>    Call timeout in seconds (default: 4)
              -b, --buffer-type <type>   Buffer type: JSON, STRING, X_OCTET (default: JSON)
              -f, --file <path>          Read request payload from file
              --no-color                 Disable ANSI terminal colors
              -h, --help                 Display this help message

            Examples:
              CasualClient ping --port 7772
              CasualClient discover orderService paymentService --port 7772
              CasualClient call echoService '{"message":"hello"}' --port 7772
              CasualClient call createOrder --file order.json --port 7772
            """);
    }

    private static boolean hasFlag(String[] args, String flag) {
        for (String a : args) {
            if (flag.equalsIgnoreCase(a)) return true;
        }
        return false;
    }

    private static String getOption(String[] args, String shortFlag, String longFlag, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equalsIgnoreCase(shortFlag) || args[i].equalsIgnoreCase(longFlag)) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }

    private static int getIntOption(String[] args, String shortFlag, String longFlag, int defaultValue) {
        String val = getOption(args, shortFlag, longFlag, null);
        if (val != null) {
            try {
                return Integer.parseInt(val);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private static final class ConsoleColor {
        private final boolean enabled;

        ConsoleColor(boolean enabled) {
            this.enabled = enabled;
        }

        String green(String text) {
            return enabled ? "\u001B[32m" + text + "\u001B[0m" : text;
        }

        String red(String text) {
            return enabled ? "\u001B[31m" + text + "\u001B[0m" : text;
        }

        String cyan(String text) {
            return enabled ? "\u001B[36m" + text + "\u001B[0m" : text;
        }

        String yellow(String text) {
            return enabled ? "\u001B[33m" + text + "\u001B[0m" : text;
        }

        String bold(String text) {
            return enabled ? "\u001B[1m" + text + "\u001B[0m" : text;
        }

        String muted(String text) {
            return enabled ? "\u001B[90m" + text + "\u001B[0m" : text;
        }
    }
}
