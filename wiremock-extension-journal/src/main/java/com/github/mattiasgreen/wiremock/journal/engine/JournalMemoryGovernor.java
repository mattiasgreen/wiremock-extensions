package com.github.mattiasgreen.wiremock.journal.engine;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryNotificationInfo;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;

/**
 * Autonomous memory governor that tracks real-time JVM heap headroom and post-GC memory pool thresholds.
 * Calculates batch eviction requirements in a single evaluation to protect against OOM without
 * repetitive JMX polling loops.
 */
public class JournalMemoryGovernor {

    private final boolean enabled;
    private final long minHeadroomBytes;
    private final long maxJournalMemoryBytes;
    private final LongSupplier headroomSupplier;
    private final AtomicBoolean criticalLowMemory = new AtomicBoolean(false);

    public JournalMemoryGovernor() {
        this(JournalConfig.loadDefault());
    }

    public JournalMemoryGovernor(JournalConfig config) {
        this(config.adaptivePruningEnabled(), config.minHeadroomBytes(), config.maxJournalMemoryBytes(), null);
    }

    public JournalMemoryGovernor(long minHeadroomBytes, long maxJournalMemoryBytes) {
        this(true, minHeadroomBytes, maxJournalMemoryBytes, null);
    }

    public JournalMemoryGovernor(
            boolean enabled, long minHeadroomBytes, long maxJournalMemoryBytes, LongSupplier headroomSupplier) {
        this.enabled = enabled;
        this.minHeadroomBytes = minHeadroomBytes;
        this.maxJournalMemoryBytes = maxJournalMemoryBytes;
        this.headroomSupplier = headroomSupplier != null ? headroomSupplier : JournalMemoryGovernor::defaultHeadroom;
    }

    private static long defaultHeadroom() {
        long maxMem = Runtime.getRuntime().maxMemory();
        long totalMem = Runtime.getRuntime().totalMemory();
        long freeMem = Runtime.getRuntime().freeMemory();
        long usedMem = totalMem - freeMem;
        return Math.max(0, maxMem - usedMem);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getAvailableHeadroomBytes() {
        return headroomSupplier.getAsLong();
    }

    public long getMinHeadroomBytes() {
        return minHeadroomBytes;
    }

    public long getMaxJournalMemoryBytes() {
        return maxJournalMemoryBytes;
    }

    /**
     * Determines whether memory conditions necessitate journal pruning.
     */
    public boolean isEvictionRequired(long currentJournalMemoryBytes) {
        if (!enabled) {
            return false;
        }

        if (maxJournalMemoryBytes > 0 && currentJournalMemoryBytes > maxJournalMemoryBytes) {
            return true;
        }

        if (criticalLowMemory.get()) {
            return true;
        }

        return getAvailableHeadroomBytes() < minHeadroomBytes;
    }

    /**
     * Calculates the exact batch size of historical events to evict based on memory deficit
     * and average event size, eliminating repetitive 1-by-1 eviction loops.
     */
    public int calculateRequiredEvictions(
            long currentJournalMemoryBytes, long averageEventSizeBytes, int currentEventCount) {
        if (!enabled || currentEventCount == 0) {
            return 0;
        }

        long avgSize = Math.max(256, averageEventSizeBytes);

        // 1. Quota check against configured journal memory limit
        if (maxJournalMemoryBytes > 0 && currentJournalMemoryBytes > maxJournalMemoryBytes) {
            long deficit = currentJournalMemoryBytes - maxJournalMemoryBytes;
            int evictionsNeeded = (int) Math.ceil((double) deficit / (double) avgSize);
            return Math.min(currentEventCount, Math.max(1, evictionsNeeded));
        }

        // 2. Headroom and emergency GC pressure check
        long availableHeadroom = getAvailableHeadroomBytes();
        boolean lowHeadroom = availableHeadroom < minHeadroomBytes;
        if (lowHeadroom || criticalLowMemory.get()) {
            // Evict a bounded batch (up to 20% of current journal, between 10 and 50 events)
            // to relieve memory pressure smoothly without panic-wiping the entire journal on transient GC spikes.
            int batchSize = Math.min(currentEventCount, Math.max(10, currentEventCount / 5));
            return Math.min(batchSize, 50);
        }

        return 0;
    }

    /**
     * Evaluates memory headroom and triggers batch eviction in a single step if required.
     */
    public void ensureHeadroom(
            long currentJournalMemoryBytes,
            long averageEventSizeBytes,
            int currentEventCount,
            Consumer<Integer> batchEvictor) {
        int count = calculateRequiredEvictions(currentJournalMemoryBytes, averageEventSizeBytes, currentEventCount);
        if (count > 0 && batchEvictor != null) {
            batchEvictor.accept(count);
        }
    }

    public void registerLowMemoryListener(Consumer<String> emergencyPruner) {
        if (!enabled) {
            return;
        }
        try {
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getType() == MemoryType.HEAP && pool.isCollectionUsageThresholdSupported()) {
                    long max = pool.getUsage().getMax();
                    if (max > 0) {
                        pool.setCollectionUsageThreshold((long) (max * 0.85));
                    }
                }
            }

            NotificationEmitter emitter = (NotificationEmitter) ManagementFactory.getMemoryMXBean();
            NotificationListener listener = (notification, handback) -> {
                if (MemoryNotificationInfo.MEMORY_COLLECTION_THRESHOLD_EXCEEDED.equals(notification.getType())) {
                    criticalLowMemory.set(true);
                    if (emergencyPruner != null) {
                        emergencyPruner.accept("GC collection threshold exceeded");
                    }
                    criticalLowMemory.set(false);
                }
            };
            emitter.addNotificationListener(listener, null, null);
        } catch (SecurityException | UnsupportedOperationException | IllegalArgumentException ignored) {
            // Memory pool threshold notification not supported in all runtimes
        }
    }
}
