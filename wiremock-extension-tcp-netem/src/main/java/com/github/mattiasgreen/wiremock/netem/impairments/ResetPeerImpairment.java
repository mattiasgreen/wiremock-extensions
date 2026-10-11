package com.github.mattiasgreen.wiremock.netem.impairments;

import com.github.mattiasgreen.wiremock.netem.model.NetEmStreamDirection;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicConfig;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketException;
import java.util.Objects;

public class ResetPeerImpairment implements NetEmImpairment {

    private final NetEmToxicConfig config;

    public ResetPeerImpairment(NetEmToxicConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    @Override
    public NetEmToxicConfig getConfig() {
        return config;
    }

    @Override
    public void onConnectionOpen(NetEmContext context, NetEmStreamDirection direction) throws IOException {
        // If no correlation-id requirement exists, we can reset immediately on connection open
        String requiredCorrelationId = config.getCorrelationId();
        if ((requiredCorrelationId == null || requiredCorrelationId.isBlank()) && shouldApply(context, direction)) {
            resetSocket(context, direction);
        }
    }

    @Override
    public void intercept(
            byte[] buffer,
            int offset,
            int length,
            NetEmContext context,
            NetEmStreamDirection direction,
            ByteConsumer forwarder)
            throws IOException {
        if (shouldApply(context, direction)) {
            resetSocket(context, direction);
        }
        forwarder.accept(buffer, offset, length);
    }

    private void resetSocket(NetEmContext context, NetEmStreamDirection direction) throws IOException {
        context.getStats().incrementImpairmentsInjected();
        Socket clientSocket = context.getClientSocket();
        if (!clientSocket.isClosed()) {
            try {
                clientSocket.setSoLinger(true, 0);
                clientSocket.close();
            } catch (IOException ignored) {
                // Ignore exception if socket was already closed
            }
        }
        Socket targetSocket = context.getTargetSocket();
        if (!targetSocket.isClosed()) {
            try {
                targetSocket.setSoLinger(true, 0);
                targetSocket.close();
            } catch (IOException ignored) {
                // Ignore
            }
        }
        throw new SocketException("NetEm: TCP RST injected for " + direction.getValue());
    }
}
