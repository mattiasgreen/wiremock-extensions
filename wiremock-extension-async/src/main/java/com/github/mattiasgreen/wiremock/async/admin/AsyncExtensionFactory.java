package com.github.mattiasgreen.wiremock.async.admin;

import com.github.mattiasgreen.wiremock.async.connection.AsyncBrokerRegistry;
import com.github.mattiasgreen.wiremock.async.destination.DestinationProvider;
import com.github.mattiasgreen.wiremock.async.engine.AsyncEngine;
import com.github.mattiasgreen.wiremock.async.trigger.AsyncMessagePublishingListener;
import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.extension.ExtensionFactory;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import java.util.List;
import java.util.Objects;

/**
 * ExtensionFactory SPI discovery provider registering {@link AsyncAdminEndpoint} and
 * {@link AsyncMessagePublishingListener}, scoping {@link AsyncEngine} and {@link AsyncBrokerRegistry}
 * per WireMockServer instance, and connecting {@link AsyncEngine} to WireMock's core
 * {@link com.github.tomakehurst.wiremock.store.StubMappingStore} and
 * {@link com.github.tomakehurst.wiremock.store.RequestJournalStore}.
 *
 * <p><b>Note:</b> This is Beta functionality subject to evolution.
 */
public class AsyncExtensionFactory implements ExtensionFactory {

    private final AsyncEngine explicitEngine;

    public AsyncExtensionFactory() {
        this.explicitEngine = null;
    }

    public AsyncExtensionFactory(AsyncEngine engine) {
        this.explicitEngine = Objects.requireNonNull(engine, "engine must not be null");
    }

    @Override
    public List<Extension> create(WireMockServices wireMockServices) {
        AsyncEngine engine = explicitEngine;
        if (engine == null) {
            engine = AsyncEngine.create();
            AsyncEngine.setDefault(engine);
            AsyncBrokerRegistry.setDefault(engine.getBrokerRegistry());
        }
        if (wireMockServices != null) {
            if (wireMockServices.getStores() != null) {
                if (wireMockServices.getStores().getRequestJournalStore() != null) {
                    engine.setJournalConsumer(wireMockServices.getStores().getRequestJournalStore()::add);
                }
                if (wireMockServices.getStores().getStubStore() != null) {
                    engine.setStubStore(wireMockServices.getStores().getStubStore());
                }
            }
            if (wireMockServices.getExtensions() != null) {
                engine.setExtensionProvidersSupplier(() -> wireMockServices
                        .getExtensions()
                        .ofType(DestinationProvider.class)
                        .values());
            }
        }
        return List.of(
                new AsyncAdminEndpoint(engine, engine.getBrokerRegistry()),
                new AsyncMessagePublishingListener(engine::publish));
    }
}
