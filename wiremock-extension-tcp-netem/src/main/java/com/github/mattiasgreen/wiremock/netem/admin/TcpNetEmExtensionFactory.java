package com.github.mattiasgreen.wiremock.netem.admin;

import com.github.mattiasgreen.wiremock.netem.engine.NetEmEngine;
import com.github.mattiasgreen.wiremock.netem.stub.NetEmStubListener;
import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.extension.ExtensionFactory;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import java.util.List;
import java.util.Objects;

/**
 * {@link ExtensionFactory} wiring an isolated {@link NetEmEngine} per WireMockServer instance.
 */
public class TcpNetEmExtensionFactory implements ExtensionFactory {

    private final NetEmEngine explicitEngine;

    public TcpNetEmExtensionFactory() {
        this.explicitEngine = null;
    }

    public TcpNetEmExtensionFactory(NetEmEngine engine) {
        this.explicitEngine = Objects.requireNonNull(engine, "engine must not be null");
    }

    @Override
    public List<Extension> create(WireMockServices wireMockServices) {
        NetEmEngine engine = explicitEngine;
        if (engine == null) {
            engine = new NetEmEngine();
            NetEmEngine.setDefault(engine);
        }
        if (wireMockServices != null && wireMockServices.getAdmin() != null) {
            NetEmEngine.registerForAdmin(wireMockServices.getAdmin(), engine);
        }
        return List.of(new TcpNetEmAdminEndpoint(engine), new NetEmStubListener(engine), engine);
    }
}
