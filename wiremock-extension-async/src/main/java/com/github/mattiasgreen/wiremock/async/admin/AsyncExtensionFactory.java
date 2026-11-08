package com.github.mattiasgreen.wiremock.async.admin;

import com.github.mattiasgreen.wiremock.async.engine.AsyncEngine;
import com.github.mattiasgreen.wiremock.async.trigger.AsyncMessagePublishingListener;
import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.extension.ExtensionFactory;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import java.util.List;
import java.util.Objects;

/**
 * ExtensionFactory SPI discovery provider registering {@link AsyncAdminEndpoint} and
 * {@link AsyncMessagePublishingListener}.
 *
 * <p><b>Note:</b> This is Beta functionality subject to evolution.
 */
public class AsyncExtensionFactory implements ExtensionFactory {

    private final AsyncEngine engine;

    public AsyncExtensionFactory() {
        this(AsyncEngine.getInstance());
    }

    public AsyncExtensionFactory(AsyncEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
    }

    @Override
    public List<Extension> create(WireMockServices wireMockServices) {
        return List.of(new AsyncAdminEndpoint(engine), new AsyncMessagePublishingListener(engine::publish));
    }
}
