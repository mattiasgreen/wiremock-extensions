package com.github.mattiasgreen.wiremock.netem.impairments;

import com.github.mattiasgreen.wiremock.netem.model.NetEmStreamDirection;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicConfig;
import java.io.IOException;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public class LimitDataImpairment implements NetEmImpairment {

    private final NetEmToxicConfig config;
    private final long maxBytes;

    public LimitDataImpairment(NetEmToxicConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.maxBytes = config.getLongAttribute("bytes", 1024L);
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
        if (!shouldApply(context, direction)) {
            forwarder.accept(buffer, offset, length);
            return;
        }

        String counterKey = "limit_data_counter_" + config.getName() + "_" + direction.getValue();
        AtomicLong counter = (AtomicLong) context.getAttribute(counterKey);
        if (counter == null) {
            counter = new AtomicLong(0);
            context.setAttribute(counterKey, counter);
        }

        long currentTotal = counter.get();
        if (currentTotal >= maxBytes) {
            context.getStats().incrementImpairmentsInjected();
            closeSocket(context, direction);
            return;
        }

        long available = maxBytes - currentTotal;
        if (length <= available) {
            counter.addAndGet(length);
            forwarder.accept(buffer, offset, length);
            if (counter.get() >= maxBytes) {
                context.getStats().incrementImpairmentsInjected();
                closeSocket(context, direction);
            }
        } else {
            // Write partial up to limit then cut off
            int allowed = (int) available;
            counter.addAndGet(allowed);
            context.getStats().incrementImpairmentsInjected();
            forwarder.accept(buffer, offset, allowed);
            closeSocket(context, direction);
        }
    }

    private void closeSocket(NetEmContext context, NetEmStreamDirection direction) throws IOException {
        context.markToxicApplied(config.getName());
        context.setTerminationReason("LIMIT_DATA");
        Socket dest = context.getDestinationSocket(direction);
        if (!dest.isClosed()) {
            dest.close();
        }
    }
}
