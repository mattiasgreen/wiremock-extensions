package com.github.mattiasgreen.wiremock.casual;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import se.laz.casual.network.protocol.messages.domain.CasualDomainDiscoveryReplyMessage;
import se.laz.casual.network.protocol.messages.domain.Service;

/**
 * Interactive terminal runner and CLI tool for inspecting and invoking Casual XATMI services.
 *
 * <p>Separated from {@link CasualClient} protocol mechanics to keep the wire engine focused and deep.
 */
public final class CasualCli {

    private CasualCli() {}

    public static void main(String[] args) {
        System.exit(run(args, System.in, System.out, System.err));
    }

    public static int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
        if (args == null || args.length == 0 || hasFlag(args, "-h") || hasFlag(args, "--help")) {
            printUsage(out);
            return 0;
        }

        boolean noColor = hasFlag(args, "--no-color") || System.getenv("NO_COLOR") != null;
        ConsoleColor color = new ConsoleColor(!noColor);

        String host = getOption(args, "-H", "--host", CasualClient.DEFAULT_HOST);
        int port = getIntOption(args, "-p", "--port", CasualClient.DEFAULT_PORT);
        int timeout = getIntOption(args, "-t", "--timeout", CasualClient.resolveDefaultTimeout());
        boolean isBinary = hasFlag(args, "-B") || hasFlag(args, "--binary");
        String defaultBufferType = isBinary ? "X_OCTET" : "JSON";
        String bufferType = getOption(args, "-b", "--buffer-type", defaultBufferType);
        if (isBinary
                || "X_OCTET".equalsIgnoreCase(bufferType)
                || "FIELDED".equalsIgnoreCase(bufferType)
                || "CFIELD/".equalsIgnoreCase(bufferType)) {
            isBinary = true;
        }
        String filePath = getOption(args, "-f", "--file", null);
        String hexPayload = getOption(args, "--hex", "-x", null);
        String base64Payload = getOption(args, "--base64", null, null);
        String outputFile = getOption(args, "-o", "--output", null);

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
                        || arg.equals("--file")
                        || arg.equals("--hex")
                        || arg.equals("-x")
                        || arg.equals("--base64")
                        || arg.equals("-o")
                        || arg.equals("--output")) {
                    i++;
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
                case "discover" -> {
                    List<String> requestedServices =
                            positionals.size() > 1 ? positionals.subList(1, positionals.size()) : List.of();
                    return executeDiscover(client, host, port, requestedServices, color, out, err);
                }
                case "call" -> {
                    if (positionals.size() < 2) {
                        err.println(color.red("Error: Missing required argument <service> for call command."));
                        printUsage(err);
                        return 1;
                    }
                    String serviceName = positionals.get(1);
                    String payload = resolvePayload(
                            positionals.size() > 2 ? positionals.get(2) : null,
                            filePath,
                            hexPayload,
                            base64Payload,
                            isBinary,
                            in);
                    return executeCall(
                            client, host, port, serviceName, bufferType, payload, outputFile, color, out, err);
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
            String outputFile,
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

            byte[] payloadBytes = result.getBytes();
            if (!result.isJson()) {
                out.printf("Size:       %d bytes%n", payloadBytes.length);
                out.println(c.bold("Hex Dump:"));
                out.print(result.toHexDump(256));
                out.println(c.bold("Base64:"));
                out.println(result.payload());
            } else {
                out.println(c.bold("Payload:"));
                if (result.payload() != null && !result.payload().isBlank()) {
                    out.println(result.payload());
                } else {
                    out.println(c.muted("(empty payload)"));
                }
            }

            if (outputFile != null && !outputFile.isBlank()) {
                try {
                    File outF = new File(outputFile);
                    Files.write(outF.toPath(), payloadBytes);
                    out.println(c.green(String.format(
                            Locale.ROOT, "✓ Saved %d binary bytes to %s", payloadBytes.length, outputFile)));
                } catch (IOException e) {
                    err.println(c.red(String.format(
                            Locale.ROOT, "Failed saving output file '%s': %s", outputFile, e.getMessage())));
                }
            }

            return result.isSuccess() ? 0 : 2;
        } catch (Exception e) {
            err.println(c.red(String.format(
                    Locale.ROOT, "Call to service '%s' failed on %s:%d: %s", serviceName, host, port, e.getMessage())));
            return 1;
        }
    }

    private static String resolvePayload(
            String positionalPayload,
            String filePath,
            String hexPayload,
            String base64Payload,
            boolean isBinary,
            InputStream in)
            throws IOException {
        if (hexPayload != null && !hexPayload.isBlank()) {
            byte[] bytes = CasualBufferUtils.fromHex(hexPayload);
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (base64Payload != null && !base64Payload.isBlank()) {
            return base64Payload.trim();
        }
        if (filePath != null && !filePath.isBlank()) {
            File file = new File(filePath);
            if (isBinary) {
                byte[] bytes = Files.readAllBytes(file.toPath());
                return Base64.getEncoder().encodeToString(bytes);
            }
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        }
        if (positionalPayload != null) {
            if (positionalPayload.startsWith("@")) {
                File file = new File(positionalPayload.substring(1));
                if (isBinary) {
                    byte[] bytes = Files.readAllBytes(file.toPath());
                    return Base64.getEncoder().encodeToString(bytes);
                }
                return Files.readString(file.toPath(), StandardCharsets.UTF_8);
            }
            if (positionalPayload.equals("-")) {
                byte[] bytes = in.readAllBytes();
                if (isBinary) {
                    return Base64.getEncoder().encodeToString(bytes);
                }
                return new String(bytes, StandardCharsets.UTF_8);
            }
            if (isBinary) {
                byte[] hexBytes = CasualBufferUtils.fromHex(positionalPayload);
                if (hexBytes.length > 0) {
                    return Base64.getEncoder().encodeToString(hexBytes);
                }
                return Base64.getEncoder().encodeToString(positionalPayload.getBytes(StandardCharsets.UTF_8));
            }
            return positionalPayload;
        }
        return isBinary ? "" : "{}";
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
              -b, --buffer-type <type>   Buffer type: JSON, STRING, X_OCTET, FIELDED (default: JSON)
              -B, --binary               Shorthand for --buffer-type X_OCTET with raw byte I/O
              -f, --file <path>          Read request payload from file (raw bytes if binary)
              --hex <hexString>          Send hex-encoded binary payload (e.g. "01 02 AA BB")
              --base64 <b64String>       Send Base64-encoded binary payload
              -o, --output <path>        Save reply payload bytes to a file
              --no-color                 Disable ANSI terminal colors
              -h, --help                 Display this help message

            Examples:
              CasualClient ping --port 7772
              CasualClient discover orderService paymentService --port 7772
              CasualClient call echoService '{"message":"hello"}' --port 7772
              CasualClient call createOrder --file order.json --port 7772
              CasualClient call uploadDocument --binary --file document.pdf --port 7772
              CasualClient call processData --hex "48 65 6C 6C 6F" -b X_OCTET --port 7772
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
            if ((shortFlag != null && args[i].equalsIgnoreCase(shortFlag))
                    || (longFlag != null && args[i].equalsIgnoreCase(longFlag))) {
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
