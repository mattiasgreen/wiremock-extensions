package com.github.mattiasgreen.wiremock.casual;

import com.github.tomakehurst.wiremock.core.Admin;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import com.github.tomakehurst.wiremock.store.Stores;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-server registry holding Casual configuration, active gateway, and WireMock services.
 * Supports isolated {@link CasualRegistry} instances per server while preserving backward-compatible
 * {@link #getInstance()} delegation and cross-server {@link Stores} lookup for {@link WireMockStoreAdapter}.
 */
public class CasualRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(CasualRegistry.class);
    private static final AtomicReference<CasualRegistry> LATEST_INSTANCE = new AtomicReference<>(new CasualRegistry());
    private static final Map<Admin, CasualRegistry> BY_ADMIN = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Stores, WireMockServices> SERVICES_BY_STORES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final AtomicReference<CasualConfiguration> configRef =
            new AtomicReference<>(CasualConfiguration.fromEnvironment());
    private final AtomicReference<CasualWireMockGateway> gatewayRef = new AtomicReference<>(null);
    private final AtomicReference<WireMockServices> servicesRef = new AtomicReference<>(null);

    private CasualRegistry() {}

    public static CasualRegistry create() {
        return new CasualRegistry();
    }

    public static CasualRegistry getInstance() {
        return LATEST_INSTANCE.get();
    }

    public static void setDefault(CasualRegistry registry) {
        LATEST_INSTANCE.set(Objects.requireNonNull(registry, "registry must not be null"));
    }

    public static Optional<CasualRegistry> forAdmin(Admin admin) {
        if (admin == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_ADMIN.get(admin));
    }

    public static void registerForAdmin(Admin admin, CasualRegistry registry) {
        if (admin != null && registry != null) {
            BY_ADMIN.put(admin, registry);
        }
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

    public Optional<WireMockServices> getWireMockServices(Stores stores) {
        if (stores == null) {
            return getWireMockServices();
        }
        WireMockServices mapped = SERVICES_BY_STORES.get(stores);
        if (mapped != null) {
            return Optional.of(mapped);
        }
        WireMockServices fallback = servicesRef.get();
        if (fallback != null && fallback.getStores() == stores) {
            return Optional.of(fallback);
        }
        return Optional.empty();
    }

    public void setWireMockServices(WireMockServices services) {
        servicesRef.set(services);
        if (services != null && services.getStores() != null) {
            SERVICES_BY_STORES.put(services.getStores(), services);
        }
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
