package com.github.mattiasgreen.wiremock.netem.impairments;

import com.github.mattiasgreen.wiremock.netem.model.NetEmStreamDirection;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicConfig;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Objects;

public class TimeoutImpairment implements NetEmImpairment {

    private final NetEmToxicConfig config;
    private final long timeoutMs;

    public TimeoutImpairment(NetEmToxicConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.timeoutMs = config.getLongAttribute("timeout", 0L);
    }

    @Override
    public NetEmToxicConfig getConfig() {
        return config;
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
            context.markToxicApplied(config.getName());
            context.setTerminationReason("TIMEOUT");
            try {
                if (timeoutMs > 0) {
                    Thread.sleep(timeoutMs);
                } else {
                    Thread.sleep(Long.MAX_VALUE);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("NetEm timeout interrupted", e);
            }
            // Close destination socket after timeout (blackhole)
            Socket dest = context.getDestinationSocket(direction);
            if (!dest.isClosed()) {
                dest.close();
            }
            throw new SocketTimeoutException("NetEm: Connection blackholed/timed out");
        }
        forwarder.accept(buffer, offset, length);
    }
}
