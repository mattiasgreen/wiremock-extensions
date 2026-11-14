package com.github.mattiasgreen.wiremock.ui;

import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.extension.ExtensionFactory;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import java.util.List;

public class UiExtensionFactory implements ExtensionFactory {

    @Override
    public List<Extension> create(WireMockServices services) {
        StubLifecycleService service = new StubLifecycleService(services.getAdmin());
        return List.of(new UiAdminApiEndpoint(), new StubLifecycleAdminEndpoint(service));
    }
}
