package com.github.mattiasgreen.wiremock.netem.impairments;

import com.github.mattiasgreen.wiremock.netem.model.NetEmStreamDirection;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicConfig;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

public class SlicerImpairment implements NetEmImpairment {

    private final NetEmToxicConfig config;
    private final int averageSize;
    private final int sizeVariation;
    private final long delayMs;

    public SlicerImpairment(NetEmToxicConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.averageSize = config.getIntAttribute("average_size", 64);
        this.sizeVariation = config.getIntAttribute("size_variation", 16);
        this.delayMs = config.getLongAttribute("delay", 10L);
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
        if (!shouldApply(context, direction) || length <= 0) {
            forwarder.accept(buffer, offset, length);
            return;
        }

        context.getStats().incrementImpairmentsInjected();
        int remaining = length;
        int currentOffset = offset;

        while (remaining > 0) {
            int variation =
                    sizeVariation > 0 ? ThreadLocalRandom.current().nextInt(-sizeVariation, sizeVariation + 1) : 0;
            int chunkSize = Math.max(1, Math.min(remaining, averageSize + variation));

            forwarder.accept(buffer, currentOffset, chunkSize);
            currentOffset += chunkSize;
            remaining -= chunkSize;

            if (remaining > 0 && delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("NetEm slicer interrupted", e);
                }
            }
        }
    }
}
