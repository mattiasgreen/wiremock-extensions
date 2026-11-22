package com.github.mattiasgreen.wiremock.async.admin;

import com.github.mattiasgreen.wiremock.async.destination.DestinationProvider;
import com.github.mattiasgreen.wiremock.async.engine.AsyncEngine;
import com.github.mattiasgreen.wiremock.async.trigger.AsyncMessagePublishingListener;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.extension.ExtensionFactory;
import com.github.tomakehurst.wiremock.extension.Extensions;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import com.github.tomakehurst.wiremock.http.HttpServer;
import com.github.tomakehurst.wiremock.http.HttpServerFactory;
import com.github.tomakehurst.wiremock.http.HttpServerFactoryLoader;
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
        AsyncEngine engine = explicitEngine != null ? explicitEngine : AsyncEngine.create();
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
        registerServerStopHook(wireMockServices, engine);
        return List.of(
                new AsyncAdminEndpoint(engine, engine.getBrokerRegistry()),
                new AsyncMessagePublishingListener(engine::publish));
    }

    private static void registerServerStopHook(WireMockServices wireMockServices, AsyncEngine engine) {
        if (wireMockServices == null || !(wireMockServices.getOptions() instanceof WireMockConfiguration config)) {
            return;
        }
        HttpServerFactory delegateFactory = resolveDelegateHttpServerFactory(config);
        if (delegateFactory == null) {
            return;
        }
        config.httpServerFactory((options, adminRequestHandler, stubRequestHandler) -> {
            HttpServer delegate = delegateFactory.buildHttpServer(options, adminRequestHandler, stubRequestHandler);
            return new LifecycleAwareHttpServer(delegate, engine::close);
        });
    }

    private static HttpServerFactory resolveDelegateHttpServerFactory(WireMockConfiguration config) {
        if (config.hasDefaultHttpServerFactory() && !HttpServerFactoryLoader.isJetty11()) {
            return new HttpServerFactoryLoader(
                            config, Extensions.NONE, HttpServerFactoryLoader.systemServiceLoader(), false)
                    .load();
        }
        return config.httpServerFactory();
    }

    private static final class LifecycleAwareHttpServer implements HttpServer {
        private final HttpServer delegate;
        private final Runnable onStop;

        private LifecycleAwareHttpServer(HttpServer delegate, Runnable onStop) {
            this.delegate = delegate;
            this.onStop = onStop;
        }

        @Override
        public void start() {
            delegate.start();
        }

        @Override
        public void stop() {
            try {
                delegate.stop();
            } finally {
                onStop.run();
            }
        }

        @Override
        public boolean isRunning() {
            return delegate.isRunning();
        }

        @Override
        public int port() {
            return delegate.port();
        }

        @Override
        public int httpsPort() {
            return delegate.httpsPort();
        }
    }
}
