package com.github.mattiasgreen.wiremock.netem.impairments;

import com.github.mattiasgreen.wiremock.netem.model.NetEmStreamDirection;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicConfig;
import java.io.IOException;
import java.util.Objects;

public class SlowCloseImpairment implements NetEmImpairment {

    private final NetEmToxicConfig config;
    private final long delayMs;

    public SlowCloseImpairment(NetEmToxicConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.delayMs = config.getLongAttribute("delay", 1000L);
    }

    @Override
    public NetEmToxicConfig getConfig() {
        return config;
    }

    @Override
    public void onEof(NetEmContext context, NetEmStreamDirection direction, ByteConsumer forwarder) throws IOException {
        if (shouldApply(context, direction) && delayMs > 0) {
            context.getStats().incrementImpairmentsInjected();
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("NetEm slow_close interrupted", e);
            }
        }
    }
}
