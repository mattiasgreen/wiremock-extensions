package com.github.mattiasgreen.wiremock.netem.engine;

import com.github.mattiasgreen.wiremock.netem.impairments.ByteConsumer;
import com.github.mattiasgreen.wiremock.netem.impairments.NetEmContext;
import com.github.mattiasgreen.wiremock.netem.impairments.NetEmImpairment;
import com.github.mattiasgreen.wiremock.netem.model.NetEmStreamDirection;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

public class NetEmPipe implements Runnable {

    private static final int BUFFER_SIZE = 8192;
    private static final String CORRELATION_HEADER = "x-correlation-id:";

    private final Socket sourceSocket;
    private final Socket destinationSocket;
    private final NetEmStreamDirection direction;
    private final NetEmContext context;
    private final Supplier<Collection<NetEmImpairment>> impairmentsSupplier;

    public NetEmPipe(
            Socket sourceSocket,
            Socket destinationSocket,
            NetEmStreamDirection direction,
            NetEmContext context,
            Supplier<Collection<NetEmImpairment>> impairmentsSupplier) {
        this.sourceSocket = Objects.requireNonNull(sourceSocket, "sourceSocket must not be null");
        this.destinationSocket = Objects.requireNonNull(destinationSocket, "destinationSocket must not be null");
        this.direction = Objects.requireNonNull(direction, "direction must not be null");
        this.context = Objects.requireNonNull(context, "context must not be null");
        this.impairmentsSupplier = Objects.requireNonNull(impairmentsSupplier, "impairmentsSupplier must not be null");
    }

    public NetEmPipe(
            Socket sourceSocket,
            Socket destinationSocket,
            NetEmStreamDirection direction,
            NetEmContext context,
            List<NetEmImpairment> impairments) {
        this(sourceSocket, destinationSocket, direction, context, () -> impairments != null ? impairments : List.of());
    }

    @Override
    public void run() {
        try (InputStream in = sourceSocket.getInputStream();
                OutputStream out = destinationSocket.getOutputStream()) {

            // Notify open on initial impairments
            for (NetEmImpairment impairment : impairmentsSupplier.get()) {
                impairment.onConnectionOpen(context, direction);
            }

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;

            ByteConsumer terminalWriter = (buf, off, len) -> {
                out.write(buf, off, len);
                out.flush();
            };

            while ((bytesRead = in.read(buffer)) != -1) {
                if (direction == NetEmStreamDirection.UPSTREAM) {
                    String correlationId = extractCorrelationId(buffer, bytesRead);
                    if (correlationId != null) {
                        context.setCorrelationId(correlationId);
                    }
                }

                // Dynamically fetch current active impairments
                List<NetEmImpairment> activeImpairments = new ArrayList<>(impairmentsSupplier.get());

                // Compose pipeline chain in reverse order
                ByteConsumer chain = terminalWriter;
                for (int i = activeImpairments.size() - 1; i >= 0; i--) {
                    NetEmImpairment impairment = activeImpairments.get(i);
                    ByteConsumer next = chain;
                    chain = (b, off, len) -> impairment.intercept(b, off, len, context, direction, next);
                }

                chain.accept(buffer, 0, bytesRead);

                if (direction == NetEmStreamDirection.UPSTREAM) {
                    context.getStats().addBytesUpstream(bytesRead);
                } else {
                    context.getStats().addBytesDownstream(bytesRead);
                }
            }

            // EOF handling
            for (NetEmImpairment impairment : impairmentsSupplier.get()) {
                impairment.onEof(context, direction, terminalWriter);
            }

            try {
                if (!destinationSocket.isOutputShutdown()) {
                    destinationSocket.shutdownOutput();
                }
            } catch (IOException ignored) {
                // Ignore if socket was already reset or closed
            }

        } catch (SocketException ignored) {
            // Expected when client disconnects or reset_peer was injected
        } catch (IOException ignored) {
            // Expected on network interruptions or socket closure
        } finally {
            closeQuietly(destinationSocket);
            closeQuietly(sourceSocket);
        }
    }

    static boolean isNewHttpRequest(byte[] buffer, int length) {
        if (length < 3) {
            return false;
        }
        String prefix = new String(buffer, 0, Math.min(length, 10), StandardCharsets.US_ASCII);
        return prefix.startsWith("GET ")
                || prefix.startsWith("POST ")
                || prefix.startsWith("PUT ")
                || prefix.startsWith("DELETE ")
                || prefix.startsWith("PATCH ")
                || prefix.startsWith("HEAD ")
                || prefix.startsWith("OPTIONS ");
    }

    static String extractCorrelationId(byte[] buffer, int length) {
        int inspectLen = Math.min(length, 4096);
        String text = new String(buffer, 0, inspectLen, StandardCharsets.US_ASCII);
        int idx = text.toLowerCase(Locale.ROOT).indexOf(CORRELATION_HEADER);
        if (idx != -1) {
            int start = idx + CORRELATION_HEADER.length();
            int end = text.indexOf('\r', start);
            if (end == -1) {
                end = text.indexOf('\n', start);
            }
            if (end == -1) {
                end = text.length();
            }
            return text.substring(start, end).trim();
        }
        return null;
    }

    private static void closeQuietly(Socket socket) {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
            // Quiet close
        }
    }
}
