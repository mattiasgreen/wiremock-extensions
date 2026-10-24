package com.github.mattiasgreen.wiremock.casual;

import com.github.tomakehurst.wiremock.extension.WireMockServices;
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
    private final AtomicReference<WireMockServices> servicesRef = new AtomicReference<>(null);

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

    public Optional<WireMockServices> getWireMockServices() {
        return Optional.ofNullable(servicesRef.get());
    }

    public void setWireMockServices(WireMockServices services) {
        servicesRef.set(services);
    }

    public synchronized CasualConfiguration updateConfig(CasualConfiguration newConfig) {
        if (newConfig == null) {
            return getConfig();
        }

        CasualConfiguration current = configRef.get();
        current.updateFrom(newConfig);

        CasualWireMockGateway gateway = gatewayRef.get();
        if (gateway != null) {
            LOG.info(
                    "Updated Casual runtime configuration: enabled={}, port={}",
                    current.isEnabled(),
                    current.getPort());
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
