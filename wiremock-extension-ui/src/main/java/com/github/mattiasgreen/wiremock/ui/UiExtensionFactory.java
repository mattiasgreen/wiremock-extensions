package com.github.mattiasgreen.wiremock.ui;

import com.github.mattiasgreen.wiremock.support.IndexedBodyLookup;
import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.extension.ExtensionFactory;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import java.util.List;

public class UiExtensionFactory implements ExtensionFactory {

    @Override
    public List<Extension> create(WireMockServices services) {
        StubLifecycleService service = new StubLifecycleService(services != null ? services.getAdmin() : null);
        IndexedBodyLookup indexedBodyLookup = services != null
                        && services.getStores() != null
                        && services.getStores().getRequestJournalStore() instanceof IndexedBodyLookup lookup
                ? lookup
                : null;
        UiAdminApiEndpoint uiEndpoint =
                new UiAdminApiEndpoint(new JournalFilterService(new ClientSourceResolver(), indexedBodyLookup));
        return List.of(uiEndpoint, new StubLifecycleAdminEndpoint(service));
    }
}
