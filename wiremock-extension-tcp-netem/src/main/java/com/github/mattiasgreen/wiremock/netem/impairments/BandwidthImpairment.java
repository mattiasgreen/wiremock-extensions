package com.github.mattiasgreen.wiremock.netem.impairments;

import com.github.mattiasgreen.wiremock.netem.model.NetEmStreamDirection;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicConfig;
import java.io.IOException;
import java.util.Objects;

public class BandwidthImpairment implements NetEmImpairment {

    private final NetEmToxicConfig config;
    private final long rateBytesPerSec;

    public BandwidthImpairment(NetEmToxicConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        long rate = config.getLongAttribute("rate", 0L);
        // Toxiproxy rate is in KB/s (e.g. 10 KB/s = 10,000 bytes/sec)
        this.rateBytesPerSec = rate > 0 ? rate * 1000L : 0L;
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
        if (shouldApply(context, direction) && rateBytesPerSec > 0 && length > 0) {
            long sleepMs = (length * 1000L) / rateBytesPerSec;
            if (sleepMs > 0) {
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("NetEm bandwidth throttling interrupted", e);
                }
            }
            context.getStats().incrementImpairmentsInjected();
        }
        forwarder.accept(buffer, offset, length);
    }
}
