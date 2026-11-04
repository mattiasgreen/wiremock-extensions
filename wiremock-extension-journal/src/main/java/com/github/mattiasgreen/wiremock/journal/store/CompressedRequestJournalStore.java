package com.github.mattiasgreen.wiremock.journal.store;

import com.github.mattiasgreen.wiremock.journal.engine.AdaptiveAntiOomGuard;
import com.github.mattiasgreen.wiremock.journal.engine.DictionaryPool;
import com.github.mattiasgreen.wiremock.journal.engine.JournalConfig;
import com.github.mattiasgreen.wiremock.journal.engine.ZstdCodec;
import com.github.mattiasgreen.wiremock.journal.model.ClusterSignature;
import com.github.mattiasgreen.wiremock.journal.model.CompressedServeEvent;
import com.github.mattiasgreen.wiremock.journal.model.JournalStats;
import com.github.tomakehurst.wiremock.store.RequestJournalStore;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * High-density RequestJournalStore backed by in-memory Zstandard compression and adaptive anti-OOM eviction.
 */
public final class CompressedRequestJournalStore implements RequestJournalStore {

    private final ConcurrentLinkedDeque<UUID> deque = new ConcurrentLinkedDeque<>();
    private final ConcurrentHashMap<UUID, ServeEvent> serveEvents = new ConcurrentHashMap<>();

    private final ZstdCodec codec;
    private final DictionaryPool dictionaryPool;
    private final AdaptiveAntiOomGuard antiOomGuard;
    private final JournalConfig config;

    private final AtomicLong rawBytesTotal = new AtomicLong(0);
    private final AtomicLong compressedBytesTotal = new AtomicLong(0);
    private final AtomicLong totalEvictions = new AtomicLong(0);

    private final AtomicLong eventsSinceLastRebalance = new AtomicLong(0);
    private final AtomicLong evictionsSinceLastRebalance = new AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicBoolean isRebalancing =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public CompressedRequestJournalStore() {
        this(JournalConfig.loadDefault());
    }

    public CompressedRequestJournalStore(JournalConfig config) {
        this(new ZstdCodec(), new DictionaryPool(config), new AdaptiveAntiOomGuard(config), config);
    }

    public CompressedRequestJournalStore(
            ZstdCodec codec, DictionaryPool dictionaryPool, AdaptiveAntiOomGuard antiOomGuard) {
        this(codec, dictionaryPool, antiOomGuard, JournalConfig.loadDefault());
    }

    public CompressedRequestJournalStore(
            ZstdCodec codec, DictionaryPool dictionaryPool, AdaptiveAntiOomGuard antiOomGuard, JournalConfig config) {
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        this.dictionaryPool = Objects.requireNonNull(dictionaryPool, "dictionaryPool must not be null");
        this.antiOomGuard = Objects.requireNonNull(antiOomGuard, "antiOomGuard must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");

        // Register emergency pruning listener
        this.antiOomGuard.registerLowMemoryListener(reason -> pruneOldestUnderPressure(10));

        // Listen for completed dictionary training to automatically upgrade cold-start events
        if (config.compressionEnabled()) {
            this.dictionaryPool.onDictionaryTrained(this::rebalanceClusterAsync);
        }
    }

    public JournalConfig getConfig() {
        return config;
    }

    public ZstdCodec getCodec() {
        return codec;
    }

    public DictionaryPool getDictionaryPool() {
        return dictionaryPool;
    }

    public AdaptiveAntiOomGuard getAntiOomGuard() {
        return antiOomGuard;
    }

    @Override
    public void add(ServeEvent serveEvent) {
        if (serveEvent == null) {
            return;
        }

        // 1. Check memory pressure and prune if necessary
        ensureMemoryHeadroom();

        // 2. Store event (compressed if enabled, raw otherwise)
        ServeEvent eventToStore = config.compressionEnabled() ? toCompressed(serveEvent) : serveEvent;

        UUID id = eventToStore.getId();
        serveEvents.put(id, eventToStore);
        deque.addFirst(id);

        rawBytesTotal.addAndGet(calculateRawSize(eventToStore));
        compressedBytesTotal.addAndGet(calculateStoredSize(eventToStore));

        if (config.compressionEnabled() && eventsSinceLastRebalance.incrementAndGet() >= 5000) {
            rebalanceAsync(false);
        }
    }

    @Override
    public Stream<ServeEvent> getAll() {
        return deque.stream().map(serveEvents::get).filter(Objects::nonNull);
    }

    @Override
    public void removeLast() {
        UUID id = deque.pollLast();
        if (id != null) {
            ServeEvent removed = serveEvents.remove(id);
            if (removed != null) {
                rawBytesTotal.addAndGet(-calculateRawSize(removed));
                compressedBytesTotal.addAndGet(-calculateStoredSize(removed));
            }
            totalEvictions.incrementAndGet();
            if (config.compressionEnabled() && evictionsSinceLastRebalance.incrementAndGet() >= 1000) {
                rebalanceAsync(false);
            }
        }
    }

    @Override
    public Stream<UUID> getAllKeys() {
        return deque.stream();
    }

    @Override
    public Optional<ServeEvent> get(UUID id) {
        return Optional.ofNullable(serveEvents.get(id));
    }

    @Override
    public void put(UUID id, ServeEvent serveEvent) {
        if (serveEvent == null || id == null) {
            return;
        }

        ServeEvent old = serveEvents.get(id);
        if (old != null) {
            rawBytesTotal.addAndGet(-calculateRawSize(old));
            compressedBytesTotal.addAndGet(-calculateStoredSize(old));
        }

        ServeEvent eventToStore = config.compressionEnabled() ? toCompressed(serveEvent) : serveEvent;
        serveEvents.put(id, eventToStore);

        rawBytesTotal.addAndGet(calculateRawSize(eventToStore));
        compressedBytesTotal.addAndGet(calculateStoredSize(eventToStore));

        if (!deque.contains(id)) {
            deque.addFirst(id);
        }
    }

    @Override
    public void remove(UUID id) {
        if (id == null) {
            return;
        }
        deque.remove(id);
        ServeEvent removed = serveEvents.remove(id);
        if (removed != null) {
            rawBytesTotal.addAndGet(-calculateRawSize(removed));
            compressedBytesTotal.addAndGet(-calculateStoredSize(removed));
        }
    }

    @Override
    public void clear() {
        deque.clear();
        serveEvents.clear();
        rawBytesTotal.set(0);
        compressedBytesTotal.set(0);
        dictionaryPool.clear();
    }

    private CompressedServeEvent toCompressed(ServeEvent serveEvent) {
        if (serveEvent instanceof CompressedServeEvent c) {
            return c;
        }
        ClusterSignature signature = ClusterSignature.of(serveEvent);
        return new CompressedServeEvent(serveEvent, signature, codec, dictionaryPool);
    }

    private long calculateRawSize(ServeEvent event) {
        if (event instanceof CompressedServeEvent c) {
            return c.getRawRequestSize() + c.getRawResponseSize();
        }
        long reqLen = (event != null
                        && event.getRequest() != null
                        && event.getRequest().getBody() != null)
                ? event.getRequest().getBody().length
                : 0;
        long respLen = (event != null
                        && event.getResponse() != null
                        && event.getResponse().getBody() != null)
                ? event.getResponse().getBody().length
                : 0;
        return reqLen + respLen;
    }

    private long calculateStoredSize(ServeEvent event) {
        if (event instanceof CompressedServeEvent c) {
            return c.getStoredMemorySizeBytes();
        }
        return calculateRawSize(event) + 512;
    }

    private void ensureMemoryHeadroom() {
        long currentMem = Math.max(0, compressedBytesTotal.get());
        for (int i = 0; i < 10 && antiOomGuard.isEvictionRequired(currentMem) && !deque.isEmpty(); i++) {
            removeLast();
            currentMem = Math.max(0, compressedBytesTotal.get());
        }
    }

    private void pruneOldestUnderPressure(int count) {
        for (int i = 0; i < count && !deque.isEmpty(); i++) {
            removeLast();
        }
    }

    public void rebalanceClusterAsync(ClusterSignature signature) {
        if (!config.compressionEnabled() || signature == null) {
            return;
        }
        Thread.ofVirtual().name("rebalance-" + signature.key()).start(() -> {
            for (UUID id : deque) {
                ServeEvent current = serveEvents.get(id);
                if (current instanceof CompressedServeEvent c && !c.isUsedDictionary()) {
                    if (signature.equals(c.getSignature()) && dictionaryPool.hasDictionary(signature)) {
                        upgradeEvent(id, c, signature);
                    }
                }
            }
        });
    }

    public void rebalanceAsync(boolean full) {
        if (!config.compressionEnabled() || !isRebalancing.compareAndSet(false, true)) {
            return;
        }
        eventsSinceLastRebalance.set(0);
        evictionsSinceLastRebalance.set(0);

        Thread.ofVirtual().name("rebalance-all").start(() -> {
            try {
                for (UUID id : deque) {
                    ServeEvent current = serveEvents.get(id);
                    if (current instanceof CompressedServeEvent c) {
                        ClusterSignature sig = full ? ClusterSignature.of(c) : c.getSignature();
                        if (!c.isUsedDictionary() && dictionaryPool.hasDictionary(sig)) {
                            upgradeEvent(id, c, sig);
                        } else if (full && !sig.equals(c.getSignature())) {
                            upgradeEvent(id, c, sig);
                        }
                    }
                }
            } finally {
                isRebalancing.set(false);
            }
        });
    }

    private void upgradeEvent(UUID id, CompressedServeEvent oldEvent, ClusterSignature targetSig) {
        CompressedServeEvent upgraded = new CompressedServeEvent(oldEvent, targetSig, codec, dictionaryPool);
        serveEvents.put(id, upgraded);
        long delta = upgraded.getStoredMemorySizeBytes() - oldEvent.getStoredMemorySizeBytes();
        compressedBytesTotal.addAndGet(delta);
    }

    public JournalStats getStats() {
        long raw = Math.max(0, rawBytesTotal.get());
        long comp = Math.max(1, compressedBytesTotal.get());
        double ratio = (double) raw / (double) comp;

        long maxMem = Runtime.getRuntime().maxMemory();
        long usedMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        return new JournalStats(
                serveEvents.size(),
                raw,
                comp,
                ratio,
                dictionaryPool.getActiveDictionaryCount(),
                totalEvictions.get(),
                usedMem,
                maxMem);
    }

    public List<UUID> search(String query) {
        if (query == null || query.isBlank()) {
            return new ArrayList<>(deque);
        }

        List<UUID> matches = new ArrayList<>();
        for (UUID id : deque) {
            ServeEvent event = serveEvents.get(id);
            if (event instanceof CompressedServeEvent c) {
                if (c.matchesText(query)) {
                    matches.add(id);
                }
            } else if (event != null) {
                String reqStr = event.getRequest() != null ? event.getRequest().getBodyAsString() : "";
                String respStr =
                        event.getResponse() != null ? event.getResponse().getBodyAsString() : "";
                if (reqStr.contains(query) || respStr.contains(query)) {
                    matches.add(id);
                }
            }
        }
        return matches;
    }
}
