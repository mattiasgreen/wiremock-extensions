package com.github.mattiasgreen.wiremock.casual;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Singleton registry holding the current Casual configuration and active gateway instance.
 * Allows runtime inspection and dynamic reconfiguration via Admin API endpoints.
 */
public class CasualRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(CasualRegistry.class);
    private static final CasualRegistry INSTANCE = new CasualRegistry();

    private final AtomicReference<CasualConfiguration> configRef =
            new AtomicReference<>(CasualConfiguration.fromEnvironment());
    private final AtomicReference<CasualWireMockGateway> gatewayRef = new AtomicReference<>(null);

    private CasualRegistry() {}

    public static CasualRegistry getInstance() {
        return INSTANCE;
    }

    public CasualConfiguration getConfig() {
        return configRef.get();
    }

    public void setConfig(CasualConfiguration config) {
        configRef.set(Objects.requireNonNull(config, "config must not be null"));
    }

    public Optional<CasualWireMockGateway> getGateway() {
        return Optional.ofNullable(gatewayRef.get());
    }

    public void setGateway(CasualWireMockGateway gateway) {
        gatewayRef.set(gateway);
    }

    public synchronized CasualConfiguration updateConfig(CasualConfiguration newConfig) {
        if (newConfig == null) {
            return getConfig();
        }

        CasualConfiguration current = configRef.get();
        current.updateFrom(newConfig);

        CasualWireMockGateway gateway = gatewayRef.get();
        if (gateway != null) {
            gateway.setUpstream(current.getUpstreamHost(), current.getUpstreamPort(), current.isUpstreamEnabled());
            gateway.setRecordStubs(current.isRecordStubs());
            gateway.setRecordFaults(current.isRecordFaults());
            gateway.setRecordNotFound(current.isRecordNotFound());
            String safeHost = current.getUpstreamHost() != null
                    ? current.getUpstreamHost().replace('\r', '_').replace('\n', '_')
                    : "null";
            LOG.info(
                    "Updated Casual runtime configuration: upstream={}:{} (enabled={}), record={}",
                    safeHost,
                    current.getUpstreamPort(),
                    current.isUpstreamEnabled(),
                    current.isRecordStubs());
        }

        return current;
    }

    public synchronized void reset() {
        CasualWireMockGateway gateway = gatewayRef.getAndSet(null);
        if (gateway != null) {
            try {
                gateway.close();
            } catch (Exception e) {
                LOG.warn("Error closing Casual gateway during reset", e);
            }
        }
        configRef.set(CasualConfiguration.fromEnvironment());
    }
}
