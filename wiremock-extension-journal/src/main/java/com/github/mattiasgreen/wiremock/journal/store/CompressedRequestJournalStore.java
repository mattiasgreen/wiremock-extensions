package com.github.mattiasgreen.wiremock.journal.store;

import com.github.mattiasgreen.wiremock.journal.engine.AdaptiveJournalCompactor;
import com.github.mattiasgreen.wiremock.journal.engine.DictionaryPool;
import com.github.mattiasgreen.wiremock.journal.engine.JournalConfig;
import com.github.mattiasgreen.wiremock.journal.engine.JournalMemoryGovernor;
import com.github.mattiasgreen.wiremock.journal.engine.JournalSearchIndex;
import com.github.mattiasgreen.wiremock.journal.engine.ZstdCodec;
import com.github.mattiasgreen.wiremock.journal.model.ClusterSignature;
import com.github.mattiasgreen.wiremock.journal.model.CompressedServeEvent;
import com.github.mattiasgreen.wiremock.journal.model.JournalStats;
import com.github.tomakehurst.wiremock.store.RequestJournalStore;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * High-density RequestJournalStore backed by in-memory Zstandard compression,
 * ingestion-time token indexing, and autonomous memory headroom governance.
 */
public final class CompressedRequestJournalStore implements RequestJournalStore {

    private final ConcurrentLinkedDeque<UUID> deque = new ConcurrentLinkedDeque<>();
    private final ConcurrentHashMap<UUID, ServeEvent> serveEvents = new ConcurrentHashMap<>();

    private final AdaptiveJournalCompactor compactor;
    private final JournalMemoryGovernor governor;
    private final JournalConfig config;

    private final AtomicLong rawBytesTotal = new AtomicLong(0);
    private final AtomicLong compressedBytesTotal = new AtomicLong(0);
    private final AtomicLong totalEvictions = new AtomicLong(0);

    private final AtomicLong eventsSinceLastRebalance = new AtomicLong(0);
    private final AtomicLong evictionsSinceLastRebalance = new AtomicLong(0);
    private final AtomicBoolean isRebalancing = new AtomicBoolean(false);

    public CompressedRequestJournalStore() {
        this(JournalConfig.loadDefault());
    }

    public CompressedRequestJournalStore(JournalConfig config) {
        this(new AdaptiveJournalCompactor(config), new JournalMemoryGovernor(config));
    }

    public CompressedRequestJournalStore(
            ZstdCodec codec, DictionaryPool dictionaryPool, JournalMemoryGovernor governor) {
        this(codec, dictionaryPool, governor, JournalConfig.loadDefault());
    }

    public CompressedRequestJournalStore(
            ZstdCodec codec, DictionaryPool dictionaryPool, JournalMemoryGovernor governor, JournalConfig config) {
        this(new AdaptiveJournalCompactor(codec, dictionaryPool, new JournalSearchIndex(), config), governor);
    }

    public CompressedRequestJournalStore(AdaptiveJournalCompactor compactor, JournalMemoryGovernor governor) {
        this.compactor = Objects.requireNonNull(compactor, "compactor must not be null");
        this.governor = Objects.requireNonNull(governor, "governor must not be null");
        this.config = compactor.getConfig();

        // Register emergency pruning listener with governor
        this.governor.registerLowMemoryListener(reason -> pruneOldestBatch(20));

        // Listen for completed dictionary training to automatically upgrade cold-start events
        if (config.compressionEnabled()) {
            this.compactor.getDictionaryPool().onDictionaryTrained(this::rebalanceClusterAsync);
        }
    }

    public JournalConfig getConfig() {
        return config;
    }

    public AdaptiveJournalCompactor getCompactor() {
        return compactor;
    }

    public JournalMemoryGovernor getGovernor() {
        return governor;
    }

    public JournalSearchIndex getSearchIndex() {
        return compactor.getSearchIndex();
    }

    public ZstdCodec getCodec() {
        return compactor.getCodec();
    }

    public DictionaryPool getDictionaryPool() {
        return compactor.getDictionaryPool();
    }

    public JournalMemoryGovernor getAntiOomGuard() {
        return governor;
    }

    public long getDecompressionCount() {
        return compactor.getSearchIndex().getTransientDecompressions();
    }

    @Override
    public void add(ServeEvent serveEvent) {
        if (serveEvent == null) {
            return;
        }

        // 1. Autonomous headroom check and batch eviction
        ensureMemoryHeadroom();

        // 2. Compact event (compressed if enabled, raw otherwise)
        ServeEvent eventToStore;
        if (config.compressionEnabled()) {
            eventToStore = compactor.compact(serveEvent);
        } else {
            eventToStore = serveEvent;
            compactor.getSearchIndex().index(serveEvent.getId(), serveEvent);
        }

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
                compactor.getSearchIndex().remove(id);
                totalEvictions.incrementAndGet();
                if (config.compressionEnabled() && evictionsSinceLastRebalance.incrementAndGet() >= 1000) {
                    rebalanceAsync(false);
                }
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
            compactor.getSearchIndex().remove(id);
        }

        ServeEvent eventToStore = config.compressionEnabled() ? compactor.compact(serveEvent) : serveEvent;
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
            compactor.getSearchIndex().remove(id);
        }
    }

    @Override
    public void clear() {
        deque.clear();
        serveEvents.clear();
        rawBytesTotal.set(0);
        compressedBytesTotal.set(0);
        compactor.clear();
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
        int eventCount = deque.size();
        if (eventCount == 0) {
            return;
        }
        long currentMem = Math.max(0, compressedBytesTotal.get());
        long avgSize = Math.max(256, currentMem / eventCount);
        governor.ensureHeadroom(currentMem, avgSize, eventCount, this::pruneOldestBatch);
    }

    public void pruneOldestBatch(int count) {
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
                    if (signature.equals(c.getSignature())
                            && compactor.getDictionaryPool().hasDictionary(signature)) {
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
                        if (!c.isUsedDictionary()
                                && compactor.getDictionaryPool().hasDictionary(sig)) {
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
        CompressedServeEvent upgraded = compactor.recompact(oldEvent, targetSig);
        ServeEvent previous = serveEvents.computeIfPresent(id, (k, existing) -> upgraded);
        if (previous != null) {
            long delta = upgraded.getStoredMemorySizeBytes() - oldEvent.getStoredMemorySizeBytes();
            compressedBytesTotal.addAndGet(delta);
        }
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
                compactor.getDictionaryPool().getActiveDictionaryCount(),
                totalEvictions.get(),
                usedMem,
                maxMem);
    }

    /**
     * Resolves queries via the inverted token index with zero decompression on hits.
     * Falls back to transient decompression only when the query cannot be resolved via token index.
     */
    public List<UUID> search(String query) {
        if (query == null || query.isBlank()) {
            return new ArrayList<>(deque);
        }

        // 1. Try resolving via inverted token index (zero decompression)
        Optional<Set<UUID>> indexedMatches = compactor.getSearchIndex().search(query);
        if (indexedMatches.isPresent()) {
            Set<UUID> hitSet = indexedMatches.get();
            if (hitSet.isEmpty()) {
                return Collections.emptyList();
            }
            List<UUID> ordered = new ArrayList<>(hitSet.size());
            for (UUID id : deque) {
                if (hitSet.contains(id)) {
                    ordered.add(id);
                }
            }
            return ordered;
        }

        // 2. Fallback to transient decompression search for unindexed partial substrings
        List<UUID> matches = new ArrayList<>();
        for (UUID id : deque) {
            ServeEvent event = serveEvents.get(id);
            if (event instanceof CompressedServeEvent c) {
                compactor.getSearchIndex().recordTransientDecompression();
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
