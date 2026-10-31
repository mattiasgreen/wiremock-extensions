package com.github.mattiasgreen.wiremock.netem.admin;

import com.github.mattiasgreen.wiremock.netem.engine.NetEmEngine;
import com.github.mattiasgreen.wiremock.netem.stub.NetEmStubListener;
import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.extension.ExtensionFactory;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import java.util.List;
import java.util.Objects;

public class TcpNetEmExtensionFactory implements ExtensionFactory {

    private final NetEmEngine engine;

    public TcpNetEmExtensionFactory() {
        this(NetEmEngine.getDefault());
    }

    public TcpNetEmExtensionFactory(NetEmEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
    }

    @Override
    public List<Extension> create(WireMockServices wireMockServices) {
        return List.of(new TcpNetEmAdminEndpoint(engine), new NetEmStubListener(engine));
    }
}
