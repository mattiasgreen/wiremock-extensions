package com.github.mattiasgreen.wiremock.ui;

import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.extension.ExtensionFactory;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import java.util.List;

public class UiExtensionFactory implements ExtensionFactory {

    private final DisabledStubStore disabledStubStore = new DisabledStubStore();

    @Override
    public List<Extension> create(WireMockServices services) {
        StubLifecycleService service = new StubLifecycleService(services.getAdmin(), disabledStubStore);
        return List.of(new UiAdminApiEndpoint(), new StubLifecycleAdminEndpoint(service));
    }
}
