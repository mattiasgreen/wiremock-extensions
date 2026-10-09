package com.github.mattiasgreen.wiremock.stateful.extension;

import com.github.mattiasgreen.wiremock.stateful.engine.StateEngine;
import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.extension.ExtensionFactory;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import java.util.List;
import java.util.Objects;

public class StatefulExtensionFactory implements ExtensionFactory {

    private final StateEngine sharedEngine;

    public StatefulExtensionFactory() {
        this(new StateEngine());
    }

    public StatefulExtensionFactory(StateEngine sharedEngine) {
        this.sharedEngine = Objects.requireNonNull(sharedEngine, "sharedEngine must not be null");
    }

    public StateEngine getSharedEngine() {
        return sharedEngine;
    }

    @Override
    public List<Extension> create(WireMockServices services) {
        return List.<Extension>of(
                new StatefulAdminEndpoint(sharedEngine), new StatefulResponseTransformer(sharedEngine));
    }
}
