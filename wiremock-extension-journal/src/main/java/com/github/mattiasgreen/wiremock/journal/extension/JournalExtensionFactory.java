package com.github.mattiasgreen.wiremock.journal.extension;

import com.github.mattiasgreen.wiremock.journal.admin.JournalAdminEndpoint;
import com.github.mattiasgreen.wiremock.journal.store.CompressedRequestJournalStore;
import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.extension.ExtensionFactory;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import com.github.tomakehurst.wiremock.store.RequestJournalStore;
import java.util.List;

/**
 * WireMock SPI ExtensionFactory registering journal admin endpoints.
 */
public class JournalExtensionFactory implements ExtensionFactory {

    private static final System.Logger LOGGER = System.getLogger(JournalExtensionFactory.class.getName());

    @Override
    public List<Extension> create(WireMockServices services) {
        CompressedRequestJournalStore directStore = null;
        if (services != null && services.getStores() != null) {
            RequestJournalStore store = services.getStores().getRequestJournalStore();
            if (store instanceof CompressedRequestJournalStore c) {
                directStore = c;
            }
        }

        if (directStore == null) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "CompressedStores was not configured as the WireMock store provider. "
                            + "Configure Stores via wiremockOptions.withStores(new CompressedStores()) "
                            + "for adaptive Zstd dictionary compression and memory headroom governance.");
        }

        JournalAdminEndpoint adminEndpoint = directStore != null
                ? new JournalAdminEndpoint(directStore)
                : new JournalAdminEndpoint(services != null ? services.getStores() : null);

        return List.of(adminEndpoint);
    }
}
