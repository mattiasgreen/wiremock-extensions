package com.github.mattiasgreen.wiremock.journal.extension;

import com.github.mattiasgreen.wiremock.journal.engine.DictionaryPool;
import com.github.mattiasgreen.wiremock.journal.engine.ZstdCodec;
import com.github.mattiasgreen.wiremock.journal.model.ClusterSignature;
import com.github.mattiasgreen.wiremock.journal.model.CompressedServeEvent;
import com.github.mattiasgreen.wiremock.journal.store.CompressedRequestJournalStore;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ServeEventListener;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import com.github.tomakehurst.wiremock.store.RequestJournalStore;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.util.Objects;

/**
 * Global ServeEventListener that intercepts served requests and upgrades raw journal entries
 * into high-density CompressedServeEvents in-place.
 */
public class JournalCompactionListener implements ServeEventListener {

    public static final String LISTENER_NAME = "journal-compaction-listener";

    private final WireMockServices services;
    private final ZstdCodec codec;
    private final DictionaryPool dictionaryPool;

    public JournalCompactionListener(WireMockServices services, ZstdCodec codec, DictionaryPool dictionaryPool) {
        this.services = services;
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        this.dictionaryPool = Objects.requireNonNull(dictionaryPool, "dictionaryPool must not be null");
    }

    @Override
    public String getName() {
        return LISTENER_NAME;
    }

    @Override
    public boolean applyGlobally() {
        return true;
    }

    @Override
    public void afterComplete(ServeEvent serveEvent, Parameters parameters) {
        if (serveEvent == null || serveEvent instanceof CompressedServeEvent) {
            return;
        }

        if (services == null || services.getStores() == null) {
            return;
        }

        RequestJournalStore store = services.getStores().getRequestJournalStore();
        if (store instanceof CompressedRequestJournalStore) {
            // Already handled natively by store
            return;
        }

        try {
            ClusterSignature signature = ClusterSignature.of(serveEvent);
            CompressedServeEvent compressed = new CompressedServeEvent(serveEvent, signature, codec, dictionaryPool);
            store.put(serveEvent.getId(), compressed);
        } catch (RuntimeException e) {
            System.getLogger(JournalCompactionListener.class.getName())
                    .log(System.Logger.Level.DEBUG, "Fallback compaction failed: {0}", e.getMessage());
        }
    }
}
