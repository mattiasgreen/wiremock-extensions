package com.github.mattiasgreen.wiremock.journal.extension;

import com.github.mattiasgreen.wiremock.journal.admin.JournalAdminEndpoint;
import com.github.mattiasgreen.wiremock.journal.engine.DictionaryPool;
import com.github.mattiasgreen.wiremock.journal.engine.ZstdCodec;
import com.github.mattiasgreen.wiremock.journal.store.CompressedRequestJournalStore;
import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.extension.ExtensionFactory;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import com.github.tomakehurst.wiremock.store.RequestJournalStore;
import java.util.List;

/**
 * WireMock SPI ExtensionFactory registering adaptive journal compaction listeners and admin endpoints.
 */
public class JournalExtensionFactory implements ExtensionFactory {

    private static final System.Logger LOGGER = System.getLogger(JournalExtensionFactory.class.getName());

    private final ZstdCodec codec;
    private final DictionaryPool dictionaryPool;

    public JournalExtensionFactory() {
        this(new ZstdCodec(), new DictionaryPool());
    }

    public JournalExtensionFactory(ZstdCodec codec, DictionaryPool dictionaryPool) {
        this.codec = codec;
        this.dictionaryPool = dictionaryPool;
    }

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
                            + "Falling back to JournalCompactionListener with degraded performance. "
                            + "Configure Stores via wiremockOptions.withStores(new CompressedStores()) for optimal memory efficiency.");
        }

        JournalAdminEndpoint adminEndpoint = directStore != null
                ? new JournalAdminEndpoint(directStore)
                : new JournalAdminEndpoint(services != null ? services.getStores() : null);

        @SuppressWarnings("removal")
        JournalCompactionListener listener = new JournalCompactionListener(services, codec, dictionaryPool);

        return List.of(adminEndpoint, listener);
    }
}
