package com.github.mattiasgreen.wiremock.casual;

import com.github.mattiasgreen.wiremock.async.destination.AsyncDestinationService;
import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.extension.ExtensionFactory;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import java.util.List;

/**
 * Extension factory enabling auto-discovery of Casual admin endpoints in WireMock standalone
 * and registering WireMockServices with the CasualRegistry for in-memory probing and journaling.
 */
public class CasualExtensionFactory implements ExtensionFactory {

    @Override
    public List<Extension> create(WireMockServices services) {
        CasualRegistry.getInstance().setWireMockServices(services);
        registerDestinationProviderIfAsyncPresent();
        return List.of(new CasualAdminEndpoint());
    }

    private static void registerDestinationProviderIfAsyncPresent() {
        try {
            Class.forName("com.github.mattiasgreen.wiremock.async.destination.AsyncDestinationService");
            AsyncDestinationService.getInstance().registerProvider(new CasualDestinationProvider());
        } catch (ClassNotFoundException | LinkageError ignored) {
            // wiremock-extension-async not on runtime classpath
        }
    }
}
