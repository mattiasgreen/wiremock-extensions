package com.github.mattiasgreen.wiremock.netem.impairments;

import com.github.mattiasgreen.wiremock.netem.model.NetEmStreamDirection;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicConfig;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

public interface NetEmImpairment {

    NetEmToxicConfig getConfig();

    default boolean shouldApply(NetEmContext context, NetEmStreamDirection direction) {
        NetEmToxicConfig config = getConfig();

        // 1. Direction check
        if (config.getStream() != NetEmStreamDirection.BOTH && config.getStream() != direction) {
            return false;
        }

        // 2. Correlation-ID matching
        String requiredCorrelationId = config.getCorrelationId();
        if (requiredCorrelationId != null && !requiredCorrelationId.isBlank()) {
            String actualCorrelationId = context.getCorrelationId();
            if (actualCorrelationId == null || !requiredCorrelationId.equalsIgnoreCase(actualCorrelationId.trim())) {
                return false;
            }
        }

        // 3. Toxicity probability (connection-scoped)
        double toxicity = config.getToxicity();
        if (toxicity >= 1.0) {
            return true;
        }
        if (toxicity <= 0.0) {
            return false;
        }

        String stateKey = "toxic_applied_" + config.getName();
        Boolean applied = (Boolean) context.getAttribute(stateKey);
        if (applied == null) {
            applied = ThreadLocalRandom.current().nextDouble() < toxicity;
            context.setAttribute(stateKey, applied);
        }
        return applied;
    }

    default void onConnectionOpen(NetEmContext context, NetEmStreamDirection direction) throws IOException {}

    default void intercept(
            byte[] buffer,
            int offset,
            int length,
            NetEmContext context,
            NetEmStreamDirection direction,
            ByteConsumer forwarder)
            throws IOException {
        forwarder.accept(buffer, offset, length);
    }

    default void onEof(NetEmContext context, NetEmStreamDirection direction, ByteConsumer forwarder)
            throws IOException {}
}
