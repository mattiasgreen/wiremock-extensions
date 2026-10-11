package com.github.mattiasgreen.wiremock.netem.impairments;

import com.github.mattiasgreen.wiremock.netem.model.NetEmStreamDirection;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicConfig;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

public class LatencyImpairment implements NetEmImpairment {

    private final NetEmToxicConfig config;
    private final long latencyMs;
    private final long jitterMs;

    public LatencyImpairment(NetEmToxicConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.latencyMs = config.getLongAttribute("latency", 0L);
        this.jitterMs = config.getLongAttribute("jitter", 0L);
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
            long delay = latencyMs;
            if (jitterMs > 0) {
                long jitter = ThreadLocalRandom.current().nextLong(-jitterMs, jitterMs + 1);
                delay = Math.max(0, delay + jitter);
            }
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("NetEm latency interrupted", e);
                }
            }
            context.markToxicApplied(config.getName());
        }
        forwarder.accept(buffer, offset, length);
    }
}
