package com.github.mattiasgreen.wiremock.journal.engine;

import java.util.function.LongSupplier;

/**
 * Adaptive memory guard that tracks real-time JVM heap headroom and post-GC memory pool thresholds
 * to guarantee that journal retention never causes an OutOfMemoryError.
 *
 * @deprecated Prefer using {@link JournalMemoryGovernor} for autonomous batch eviction calculations.
 */
@Deprecated(since = "1.15.0")
public class AdaptiveAntiOomGuard extends JournalMemoryGovernor {

    public AdaptiveAntiOomGuard() {
        super();
    }

    public AdaptiveAntiOomGuard(JournalConfig config) {
        super(config);
    }

    public AdaptiveAntiOomGuard(long minHeadroomBytes, long maxJournalMemoryBytes) {
        super(minHeadroomBytes, maxJournalMemoryBytes);
    }

    public AdaptiveAntiOomGuard(boolean enabled, long minHeadroomBytes, long maxJournalMemoryBytes) {
        super(enabled, minHeadroomBytes, maxJournalMemoryBytes, null);
    }

    public AdaptiveAntiOomGuard(
            boolean enabled, long minHeadroomBytes, long maxJournalMemoryBytes, LongSupplier headroomSupplier) {
        super(enabled, minHeadroomBytes, maxJournalMemoryBytes, headroomSupplier);
    }
}
