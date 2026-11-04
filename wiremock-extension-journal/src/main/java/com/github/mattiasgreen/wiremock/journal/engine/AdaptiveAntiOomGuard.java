package com.github.mattiasgreen.wiremock.journal.engine;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryNotificationInfo;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;

/**
 * Adaptive memory guard that tracks real-time JVM heap headroom and post-GC memory pool thresholds
 * to guarantee that journal retention never causes an OutOfMemoryError.
 */
public class AdaptiveAntiOomGuard {

    private final boolean enabled;
    private final long minHeadroomBytes;
    private final long maxJournalMemoryBytes;
    private final AtomicBoolean criticalLowMemory = new AtomicBoolean(false);

    public AdaptiveAntiOomGuard() {
        this(JournalConfig.loadDefault());
    }

    public AdaptiveAntiOomGuard(JournalConfig config) {
        this(config.adaptivePruningEnabled(), config.minHeadroomBytes(), config.maxJournalMemoryBytes());
    }

    public AdaptiveAntiOomGuard(long minHeadroomBytes, long maxJournalMemoryBytes) {
        this(true, minHeadroomBytes, maxJournalMemoryBytes);
    }

    public AdaptiveAntiOomGuard(boolean enabled, long minHeadroomBytes, long maxJournalMemoryBytes) {
        this.enabled = enabled;
        this.minHeadroomBytes = minHeadroomBytes;
        this.maxJournalMemoryBytes = maxJournalMemoryBytes;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getAvailableHeadroomBytes() {
        long maxMem = Runtime.getRuntime().maxMemory();
        long totalMem = Runtime.getRuntime().totalMemory();
        long freeMem = Runtime.getRuntime().freeMemory();
        long usedMem = totalMem - freeMem;
        return Math.max(0, maxMem - usedMem);
    }

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

        long availableHeadroom = getAvailableHeadroomBytes();
        return availableHeadroom < minHeadroomBytes;
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
                        // Set post-GC threshold at 85% of pool
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
