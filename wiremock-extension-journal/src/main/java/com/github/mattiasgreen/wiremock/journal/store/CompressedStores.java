package com.github.mattiasgreen.wiremock.journal.store;

import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.store.*;
import java.util.Objects;

/**
 * Stores implementation providing high-density compressed request journaling while delegating
 * all other stores to WireMock's DefaultStores.
 */
public class CompressedStores implements Stores {

    private final Stores delegate;
    private final CompressedRequestJournalStore journalStore;

    public CompressedStores(FileSource fileSource) {
        this(new DefaultStores(fileSource), new CompressedRequestJournalStore());
    }

    public CompressedStores(Stores delegate, CompressedRequestJournalStore journalStore) {
        this.delegate = Objects.requireNonNull(delegate, "delegate Stores must not be null");
        this.journalStore = Objects.requireNonNull(journalStore, "journalStore must not be null");
    }

    @Override
    public RequestJournalStore getRequestJournalStore() {
        return journalStore;
    }

    public CompressedRequestJournalStore getCompressedJournalStore() {
        return journalStore;
    }

    @Override
    public StubMappingStore getStubStore() {
        return delegate.getStubStore();
    }

    @Override
    public SettingsStore getSettingsStore() {
        return delegate.getSettingsStore();
    }

    @Override
    public ScenariosStore getScenariosStore() {
        return delegate.getScenariosStore();
    }

    @Override
    public RecorderStateStore getRecorderStateStore() {
        return delegate.getRecorderStateStore();
    }

    @Override
    public BlobStore getMappingsBlobStore() {
        return delegate.getMappingsBlobStore();
    }

    @Override
    public BlobStore getFilesBlobStore() {
        return delegate.getFilesBlobStore();
    }

    @Override
    public BlobStore getBlobStore(String name) {
        return delegate.getBlobStore(name);
    }

    @Override
    public ObjectStore getObjectStore(String name) {
        return delegate.getObjectStore(name);
    }

    @Override
    public ObjectStore getObjectStore(String name, Stores.PersistenceType persistenceType) {
        return delegate.getObjectStore(name, persistenceType);
    }

    @Override
    public ObjectStore getObjectStore(String name, Stores.PersistenceType persistenceType, int maxItems) {
        return delegate.getObjectStore(name, persistenceType, maxItems);
    }

    @Override
    public void start() {
        delegate.start();
    }

    @Override
    public void stop() {
        delegate.stop();
        journalStore.clear();
    }
}
