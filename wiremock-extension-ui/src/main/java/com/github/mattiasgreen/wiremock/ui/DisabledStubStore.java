package com.github.mattiasgreen.wiremock.ui;

import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe in-memory store for disabled/archived WireMock stub mappings.
 * When stubs are disabled, they are parked here and removed from active matching.
 */
public class DisabledStubStore {

    private final ConcurrentMap<UUID, StubMapping> store = new ConcurrentHashMap<>();

    public void put(StubMapping stub) {
        Objects.requireNonNull(stub, "stub must not be null");
        UUID id = stub.getId();
        if (id == null && stub.getUuid() != null) {
            id = stub.getUuid();
        }
        if (id != null) {
            store.put(id, stub);
        }
    }

    public StubMapping get(UUID id) {
        if (id == null) return null;
        return store.get(id);
    }

    public StubMapping remove(UUID id) {
        if (id == null) return null;
        return store.remove(id);
    }

    public Collection<StubMapping> getAll() {
        return List.copyOf(store.values());
    }

    public int size() {
        return store.size();
    }

    public void clear() {
        store.clear();
    }
}
