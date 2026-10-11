package com.github.mattiasgreen.wiremock.netem.admin;

import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.extension.ExtensionFactory;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import java.util.List;

public class TcpNetEmExtensionFactory implements ExtensionFactory {

    @Override
    public List<Extension> create(WireMockServices wireMockServices) {
        return List.of(new TcpNetEmAdminEndpoint());
    }
}
