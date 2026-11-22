package com.github.mattiasgreen.wiremock.casual;

import com.github.mattiasgreen.wiremock.async.admin.AsyncAdminEndpoint;
import com.github.mattiasgreen.wiremock.async.connection.AsyncBrokerRegistry;
import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.extension.ExtensionFactory;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Extension factory enabling auto-discovery of Casual admin endpoints in WireMock standalone,
 * scoping {@link CasualRegistry} per server instance, and optionally contributing a
 * {@link CasualDestinationProvider} extension when {@code wiremock-extension-async} is present.
 */
public class CasualExtensionFactory implements ExtensionFactory {

    private final CasualRegistry explicitRegistry;

    public CasualExtensionFactory() {
        this.explicitRegistry = null;
    }

    public CasualExtensionFactory(CasualRegistry registry) {
        this.explicitRegistry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @Override
    public List<Extension> create(WireMockServices services) {
        CasualRegistry registry = explicitRegistry;
        if (registry == null) {
            registry = CasualRegistry.create();
            CasualRegistry.setDefault(registry);
        }
        registry.setWireMockServices(services);
        if (services != null && services.getAdmin() != null) {
            CasualRegistry.registerForAdmin(services.getAdmin(), registry);
        }

        List<Extension> extensions = new ArrayList<>();
        extensions.add(new CasualAdminEndpoint(registry));
        try {
            Class.forName("com.github.mattiasgreen.wiremock.async.destination.DestinationProvider");
            extensions.add(AsyncDestinationBridge.createProvider(registry, services));
        } catch (ClassNotFoundException | LinkageError ignored) {
            // wiremock-extension-async not on runtime classpath
        }
        return List.copyOf(extensions);
    }

    private static final class AsyncDestinationBridge {
        private static Extension createProvider(CasualRegistry registry, WireMockServices services) {
            Supplier<Optional<AsyncBrokerRegistry>> brokerRegistrySupplier = () -> {
                if (services == null || services.getExtensions() == null) {
                    return Optional.empty();
                }
                return services.getExtensions().ofType(AsyncAdminEndpoint.class).values().stream()
                        .findFirst()
                        .map(AsyncAdminEndpoint::getBrokerRegistry);
            };
            return new CasualDestinationProvider(
                    () -> registry.getGateway().map(CasualWireMockGateway::getQueueStore), brokerRegistrySupplier);
        }
    }
}
