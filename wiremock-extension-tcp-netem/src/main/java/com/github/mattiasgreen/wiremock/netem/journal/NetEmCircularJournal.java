package com.github.mattiasgreen.wiremock.netem.journal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * High-performance, lock-free circular ring buffer journal for TCP NetEm proxy events.
 *
 * <p><strong>Memory & Heap Sizing:</strong>
 * Designed for central mock servers handling concurrent high-volume CI/CD test suites.
 * Uses a pre-allocated {@link AtomicReferenceArray} of {@link NetEmJournalEntry} records.
 * <ul>
 *   <li>Each entry shallow size: ~80-120 bytes.</li>
 *   <li>Default capacity: 10,000 entries (~1.2 MB heap).</li>
 *   <li>50,000 entries: ~6.1 MB heap.</li>
 *   <li>100,000 entries: ~12.2 MB heap.</li>
 * </ul>
 * Ring-buffer overwrites are O(1) with zero ongoing allocation overhead in steady state.
 */
public class NetEmCircularJournal {

    public static final int DEFAULT_CAPACITY = 10_000;
    public static final String CAPACITY_PROPERTY = "wiremock.netem.journal.capacity";

    private final int capacity;
    private final AtomicReferenceArray<NetEmJournalEntry> ringBuffer;
    private final AtomicLong sequence = new AtomicLong(0);

    public NetEmCircularJournal() {
        this(resolveConfiguredCapacity());
    }

    public NetEmCircularJournal(int capacity) {
        if (capacity < 100) {
            capacity = 100;
        } else if (capacity > 1_000_000) {
            capacity = 1_000_000;
        }
        this.capacity = capacity;
        this.ringBuffer = new AtomicReferenceArray<>(capacity);
    }

    private static int resolveConfiguredCapacity() {
        String prop = System.getProperty(CAPACITY_PROPERTY);
        if (prop != null && !prop.isBlank()) {
            try {
                return Integer.parseInt(prop.trim());
            } catch (NumberFormatException ignored) {
                // fallback to default
            }
        }
        return DEFAULT_CAPACITY;
    }

    /**
     * Appends an entry to the circular journal ring buffer in O(1) time.
     */
    public void record(NetEmJournalEntry entry) {
        if (entry == null) {
            return;
        }
        long seq = sequence.getAndIncrement();
        int slot = (int) (seq % capacity);
        ringBuffer.set(slot, entry);
    }

    /**
     * Retrieves up to {@code limit} recent entries in reverse chronological order (newest first),
     * applying optional filters.
     */
    public List<NetEmJournalEntry> query(int limit, String proxyName, String correlationId, Boolean impaired) {
        int effectiveLimit = Math.clamp(limit <= 0 ? 100 : limit, 1, 1000);
        long currentSeq = sequence.get();
        long minSeq = Math.max(0, currentSeq - capacity);

        List<NetEmJournalEntry> results = new ArrayList<>();
        String normalizedProxy = proxyName != null && !proxyName.isBlank() ? proxyName.toLowerCase(Locale.ROOT) : null;
        String normalizedCorr =
                correlationId != null && !correlationId.isBlank() ? correlationId.toLowerCase(Locale.ROOT) : null;

        for (long seq = currentSeq - 1; seq >= minSeq && results.size() < effectiveLimit; seq--) {
            int slot = (int) (seq % capacity);
            NetEmJournalEntry entry = ringBuffer.get(slot);
            if (entry == null || entry.id() != seq) {
                continue;
            }

            if (normalizedProxy != null
                    && !entry.proxyName().toLowerCase(Locale.ROOT).contains(normalizedProxy)) {
                continue;
            }
            if (normalizedCorr != null
                    && !entry.correlationId().toLowerCase(Locale.ROOT).contains(normalizedCorr)) {
                continue;
            }
            if (impaired != null && entry.impaired() != impaired.booleanValue()) {
                continue;
            }

            results.add(entry);
        }

        return results;
    }

    /**
     * Clears all entries in the ring buffer.
     */
    public void clear() {
        for (int i = 0; i < capacity; i++) {
            ringBuffer.set(i, null);
        }
        sequence.set(0);
    }

    public int getCapacity() {
        return capacity;
    }

    public long getTotalRecordedCount() {
        return sequence.get();
    }
}
