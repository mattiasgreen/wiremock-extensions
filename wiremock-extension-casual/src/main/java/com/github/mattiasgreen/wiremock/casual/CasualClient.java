package com.github.mattiasgreen.wiremock.casual;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.transaction.xa.Xid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.laz.casual.api.buffer.type.JsonBuffer;
import se.laz.casual.api.buffer.type.ServiceBuffer;
import se.laz.casual.api.flags.AtmiFlags;
import se.laz.casual.api.flags.Flag;
import se.laz.casual.api.flags.XAFlags;
import se.laz.casual.api.network.protocol.messages.CasualNWMessage;
import se.laz.casual.api.network.protocol.messages.exception.CasualProtocolException;
import se.laz.casual.api.xa.XID;
import se.laz.casual.config.ConfigurationOptions;
import se.laz.casual.config.ConfigurationService;
import se.laz.casual.internal.network.NetworkConnection;
import se.laz.casual.jca.SpanId;
import se.laz.casual.network.ProtocolVersion;
import se.laz.casual.network.outbound.NettyConnectionInformation;
import se.laz.casual.network.outbound.NettyNetworkConnection;
import se.laz.casual.network.protocol.messages.CasualNWMessageImpl;
import se.laz.casual.network.protocol.messages.domain.CasualDomainDiscoveryReplyMessage;
import se.laz.casual.network.protocol.messages.domain.CasualDomainDiscoveryRequestMessage;
import se.laz.casual.network.protocol.messages.domain.Service;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallReplyMessage;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallRequestMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceCommitReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceCommitRequestMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourcePrepareReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourcePrepareRequestMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceRollbackReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceRollbackRequestMessage;

/**
 * Unified high-level Casual client and CLI runner backed by {@link NettyNetworkConnection}.
 * Supports domain connect handshake, discovery, service calls (tpcall), and 2PC/XA transaction messages.
 */
public class CasualClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(CasualClient.class);

    static {
        ConfigurationService.setConfiguration(ConfigurationOptions.CASUAL_OUTBOUND_UNMANAGED, true);
        ConfigurationService.setConfiguration(ConfigurationOptions.CASUAL_UNMANAGED, true);
    }

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 7772;
    public static final int DEFAULT_TIMEOUT_SECONDS = 4;
    public static final String TIMEOUT_PROPERTY = "wiremock.casual.upstream.timeout.seconds";

    private final String host;
    private final int port;
    private final UUID domainId;
    private final String domainName;
    private final int timeoutSeconds;

    private volatile NetworkConnection networkConnection;

    public synchronized NetworkConnection getActiveConnection() {
        connect();
        return networkConnection;
    }

    public CasualClient(String host, int port) {
        this(host, port, resolveDefaultTimeout());
    }

    public CasualClient(String host, int port, int timeoutSeconds) {
        this(host, port, UUID.randomUUID(), "casual-client", timeoutSeconds);
    }

    public CasualClient(String host, int port, UUID domainId, String domainName) {
        this(host, port, domainId, domainName, resolveDefaultTimeout());
    }

    public CasualClient(String host, int port, UUID domainId, String domainName, int timeoutSeconds) {
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.port = port;
        this.domainId = Objects.requireNonNull(domainId, "domainId must not be null");
        this.domainName = Objects.requireNonNull(domainName, "domainName must not be null");
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : resolveDefaultTimeout();
    }

    public static CasualClient create(String host, int port) {
        return new CasualClient(host, port);
    }

    public static CasualClient create(String host, int port, int timeoutSeconds) {
        return new CasualClient(host, port, timeoutSeconds);
    }

    private static int resolveDefaultTimeout() {
        return Integer.getInteger(TIMEOUT_PROPERTY, DEFAULT_TIMEOUT_SECONDS);
    }

    public synchronized CasualClient connect() {
        if (isConnected()) {
            return this;
        }
        String safeHost = host.replaceAll("[\r\n]", "");
        LOG.debug("[CASUAL-CLIENT] Connecting to Casual domain at {}:{}", safeHost, port);

        NettyConnectionInformation ci = NettyConnectionInformation.createBuilder()
                .withAddress(new InetSocketAddress(host, port))
                .withDomainId(domainId)
                .withDomainName(domainName)
                .build();

        this.networkConnection = NettyNetworkConnection.of(
                ci,
                reason ->
                        LOG.warn("[CASUAL-CLIENT] Connection lost to {}:{}: {}", safeHost, port, reason.getMessage()));
        LOG.info(
                "[CASUAL-CLIENT] Connected to Casual domain at {}:{} (negotiated protocol version: {})",
                safeHost,
                port,
                networkConnection.getProtocolVersion());
        return this;
    }

    public synchronized boolean isConnected() {
        return networkConnection != null && networkConnection.isActive();
    }

    public synchronized ProtocolVersion getProtocolVersion() {
        return networkConnection != null ? networkConnection.getProtocolVersion() : ProtocolVersion.VERSION_1_0;
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

    public CasualCallResult call(String serviceName, String jsonPayload) throws Exception {
        return call(serviceName, "JSON", jsonPayload);
    }

    public CasualCallResult call(String serviceName, String bufferType, String payload) throws Exception {
        Objects.requireNonNull(serviceName, "serviceName must not be null");
        connect();

        ServiceBuffer buffer = CasualBufferUtils.wrapPayload(bufferType, payload);
        long startNanos = System.nanoTime();
        CasualServiceCallReplyMessage reply = tpcall(serviceName, buffer);
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

    public CasualCallResult call(String serviceName, byte[] binaryPayload) throws Exception {
        return call(serviceName, "X_OCTET", binaryPayload);
    }

    public CasualCallResult call(String serviceName, String bufferType, byte[] binaryPayload) throws Exception {
        Objects.requireNonNull(serviceName, "serviceName must not be null");
        connect();

        ServiceBuffer buffer = CasualBufferUtils.wrapPayload(bufferType, binaryPayload);
        long startNanos = System.nanoTime();
        CasualServiceCallReplyMessage reply = tpcall(serviceName, buffer);
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        String replyType = reply.getServiceBuffer() != null
                ? reply.getServiceBuffer().getType()
                : (bufferType != null ? bufferType : "X_OCTET");
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

    public CasualServiceCallReplyMessage tpcall(String serviceName, String jsonPayload) throws Exception {
        return tpcall(serviceName, jsonPayload, XID.NULL_XID);
    }

    public CasualServiceCallReplyMessage tpcall(String serviceName, String jsonPayload, Xid xid) throws Exception {
        ServiceBuffer buffer = ServiceBuffer.of(JsonBuffer.of(jsonPayload));
        return tpcall(serviceName, buffer, xid);
    }

    public CasualServiceCallReplyMessage tpcall(String serviceName, ServiceBuffer buffer) throws Exception {
        return tpcall(serviceName, buffer, XID.NULL_XID);
    }

    public CasualServiceCallReplyMessage tpcall(String serviceName, ServiceBuffer buffer, Xid xid) throws Exception {
        connect();
        ProtocolVersion version = getProtocolVersion();
        CasualServiceCallRequestMessage.Builder b = CasualServiceCallRequestMessage.createBuilder()
                .setProtocolVersion(version != null ? version : ProtocolVersion.VERSION_1_0)
                .setExecution(UUID.randomUUID())
                .setServiceName(serviceName)
                .setTimeout((long) timeoutSeconds * 1000)
                .setParentName(domainName)
                .setXid(xid != null ? xid : XID.NULL_XID)
                .setXatmiFlags(Flag.of(AtmiFlags.NOFLAG))
                .setServiceBuffer(buffer);

        if (version != null && version.isGreaterThanOrEqualTo(ProtocolVersion.VERSION_1_3)) {
            b.setParentSpan(SpanId.of());
        }

        CasualServiceCallRequestMessage callReq = b.build();
        return forwardServiceCall(callReq);
    }

    /**
     * Forwards an existing {@link CasualServiceCallRequestMessage} over TCP, automatically
     * adapting protocol version and SpanId fields to the upstream domain's negotiated version.
     */
    public CasualServiceCallReplyMessage forwardServiceCall(CasualServiceCallRequestMessage req) throws Exception {
        connect();
        ProtocolVersion version = getProtocolVersion();
        CasualServiceCallRequestMessage adapted = req;
        if (version != null) {
            CasualServiceCallRequestMessage.Builder b = CasualServiceCallRequestMessage.createBuilder()
                    .setProtocolVersion(version)
                    .setExecution(req.getExecution())
                    .setServiceName(req.getServiceName())
                    .setTimeout(req.getTimeout())
                    .setParentName(req.getParentName())
                    .setXid(req.getXid())
                    .setXatmiFlags(req.getXatmiFlags())
                    .setServiceBuffer(req.getServiceBuffer());
            try {
                SpanId span = req.getParentSpan();
                if (span != null) {
                    b.setParentSpan(span);
                } else if (version.isGreaterThanOrEqualTo(ProtocolVersion.VERSION_1_3)) {
                    b.setParentSpan(SpanId.of());
                }
            } catch (CasualProtocolException expected) {
                LOG.trace("SpanId not supported for protocol version", expected);
                if (version.isGreaterThanOrEqualTo(ProtocolVersion.VERSION_1_3)) {
                    b.setParentSpan(SpanId.of());
                }
            }
            adapted = b.build();
        }

        UUID correlationId = UUID.randomUUID();
        CasualNWMessage<CasualServiceCallRequestMessage> nwMsg = CasualNWMessageImpl.of(correlationId, adapted);
        CompletableFuture<CasualNWMessage<CasualServiceCallReplyMessage>> future =
                getActiveConnection().request(nwMsg);
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS).getMessage();
        } catch (TimeoutException e) {
            String safeHost = host.replaceAll("[\r\n]", "");
            throw new TimeoutException(String.format(
                    "Upstream Casual call [%s] to [%s:%d] timed out after %ds",
                    req.getServiceName(), safeHost, port, timeoutSeconds));
        }
    }

    public CasualDomainDiscoveryReplyMessage discover(String... serviceNames) throws Exception {
        return discover(serviceNames != null ? Arrays.asList(serviceNames) : Collections.emptyList());
    }

    public CasualDomainDiscoveryReplyMessage discover(List<String> serviceNames) throws Exception {
        return discoverServices(serviceNames, timeoutSeconds, TimeUnit.SECONDS);
    }

    public CasualDomainDiscoveryReplyMessage discoverServices(List<String> serviceNames) throws Exception {
        return discoverServices(serviceNames, timeoutSeconds, TimeUnit.SECONDS);
    }

    public CasualDomainDiscoveryReplyMessage discoverServices(List<String> serviceNames, long timeout, TimeUnit unit)
            throws Exception {
        CasualDomainDiscoveryRequestMessage.CasualDomainDiscoveryRequestMessageBuilder builder =
                CasualDomainDiscoveryRequestMessage.createBuilder();
        builder.setExecution(UUID.randomUUID());
        builder.setDomainId(domainId);
        builder.setDomainName(domainName);
        builder.setServiceNames(serviceNames != null ? serviceNames : Collections.emptyList());
        builder.setQueueNames(Collections.emptyList());

        CasualDomainDiscoveryRequestMessage discoveryReq = builder.build();
        UUID correlationId = UUID.randomUUID();
        CasualNWMessage<CasualDomainDiscoveryRequestMessage> nwMsg =
                CasualNWMessageImpl.of(correlationId, discoveryReq);
        CompletableFuture<CasualNWMessage<CasualDomainDiscoveryReplyMessage>> future =
                getActiveConnection().request(nwMsg);
        try {
            return future.get(timeout, unit).getMessage();
        } catch (TimeoutException e) {
            String safeHost = host.replaceAll("[\r\n]", "");
            throw new TimeoutException(String.format(
                    "Casual domain discovery to [%s:%d] timed out after %s %s", safeHost, port, timeout, unit));
        }
    }

    public CasualTransactionResourcePrepareReplyMessage prepare(Xid xid, int resourceId) throws Exception {
        return prepare(xid, resourceId, Flag.of(XAFlags.TMNOFLAGS));
    }

    public CasualTransactionResourcePrepareReplyMessage prepare(Xid xid, int resourceId, Flag<XAFlags> flags)
            throws Exception {
        CasualTransactionResourcePrepareRequestMessage req =
                CasualTransactionResourcePrepareRequestMessage.of(UUID.randomUUID(), xid, resourceId, flags);
        UUID correlationId = UUID.randomUUID();
        CasualNWMessage<CasualTransactionResourcePrepareRequestMessage> nwMsg =
                CasualNWMessageImpl.of(correlationId, req);
        CompletableFuture<CasualNWMessage<CasualTransactionResourcePrepareReplyMessage>> future =
                getActiveConnection().request(nwMsg);
        return future.get(timeoutSeconds, TimeUnit.SECONDS).getMessage();
    }

    public CasualTransactionResourceCommitReplyMessage commit(Xid xid, int resourceId, boolean onePhase)
            throws Exception {
        Flag<XAFlags> flags = Flag.of(onePhase ? XAFlags.TMONEPHASE : XAFlags.TMNOFLAGS);
        return commit(xid, resourceId, flags);
    }

    public CasualTransactionResourceCommitReplyMessage commit(Xid xid, int resourceId, Flag<XAFlags> flags)
            throws Exception {
        CasualTransactionResourceCommitRequestMessage req =
                CasualTransactionResourceCommitRequestMessage.of(UUID.randomUUID(), xid, resourceId, flags);
        UUID correlationId = UUID.randomUUID();
        CasualNWMessage<CasualTransactionResourceCommitRequestMessage> nwMsg =
                CasualNWMessageImpl.of(correlationId, req);
        CompletableFuture<CasualNWMessage<CasualTransactionResourceCommitReplyMessage>> future =
                getActiveConnection().request(nwMsg);
        return future.get(timeoutSeconds, TimeUnit.SECONDS).getMessage();
    }

    public CasualTransactionResourceRollbackReplyMessage rollback(Xid xid, int resourceId) throws Exception {
        return rollback(xid, resourceId, Flag.of(XAFlags.TMNOFLAGS));
    }

    public CasualTransactionResourceRollbackReplyMessage rollback(Xid xid, int resourceId, Flag<XAFlags> flags)
            throws Exception {
        CasualTransactionResourceRollbackRequestMessage req =
                CasualTransactionResourceRollbackRequestMessage.of(UUID.randomUUID(), xid, resourceId, flags);
        UUID correlationId = UUID.randomUUID();
        CasualNWMessage<CasualTransactionResourceRollbackRequestMessage> nwMsg =
                CasualNWMessageImpl.of(correlationId, req);
        CompletableFuture<CasualNWMessage<CasualTransactionResourceRollbackReplyMessage>> future =
                getActiveConnection().request(nwMsg);
        return future.get(timeoutSeconds, TimeUnit.SECONDS).getMessage();
    }

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
        if (networkConnection != null) {
            try {
                networkConnection.close();
            } catch (Exception e) {
                LOG.trace("Exception while closing network connection", e);
            }
            networkConnection = null;
        }
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
        boolean noColor = hasFlag(args, "--no-color") || System.getenv("NO_COLOR") != null;

        ConsoleColor color = new ConsoleColor(!noColor);

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
