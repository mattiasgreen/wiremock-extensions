package com.github.mattiasgreen.wiremock.journal.engine;

import java.util.Locale;

/**
 * Global configuration options for the adaptive request journal extension.
 * Supports environment variables and Java system properties.
 */
public record JournalConfig(
        boolean compressionEnabled,
        boolean adaptivePruningEnabled,
        int maxDictionaries,
        int trainingSampleThreshold,
        int targetDictSizeBytes,
        long maxJournalMemoryBytes,
        long minHeadroomBytes) {
    public static final String ENV_COMPRESSION_ENABLED = "WIREMOCK_JOURNAL_COMPRESSION_ENABLED";
    public static final String PROP_COMPRESSION_ENABLED = "wiremock.journal.compression.enabled";

    public static final String ENV_ADAPTIVE_PRUNING_ENABLED = "WIREMOCK_JOURNAL_ADAPTIVE_PRUNING_ENABLED";
    public static final String PROP_ADAPTIVE_PRUNING_ENABLED = "wiremock.journal.adaptive.pruning.enabled";

    public static final String ENV_MAX_DICTIONARIES = "WIREMOCK_JOURNAL_MAX_DICTIONARIES";
    public static final String PROP_MAX_DICTIONARIES = "wiremock.journal.max.dictionaries";

    public static final String ENV_MAX_MEMORY_MB = "WIREMOCK_JOURNAL_MAX_MEMORY_MB";
    public static final String PROP_MAX_MEMORY_MB = "wiremock.journal.max.memory.mb";

    public static final int DEFAULT_MAX_DICTIONARIES = 64;
    public static final int DEFAULT_TRAINING_SAMPLE_THRESHOLD = 20;
    public static final int DEFAULT_DICT_SIZE_BYTES = 32 * 1024;
    public static final long DEFAULT_MIN_HEADROOM_BYTES = 32L * 1024 * 1024; // 32 MB safety margin
    public static final double DEFAULT_MIN_HEADROOM_RATIO = 0.15; // 15% headroom minimum

    public static JournalConfig loadDefault() {
        boolean compression = getBoolean(ENV_COMPRESSION_ENABLED, PROP_COMPRESSION_ENABLED, true);
        boolean pruning = getBoolean(ENV_ADAPTIVE_PRUNING_ENABLED, PROP_ADAPTIVE_PRUNING_ENABLED, true);
        int maxDicts = getInt(ENV_MAX_DICTIONARIES, PROP_MAX_DICTIONARIES, DEFAULT_MAX_DICTIONARIES);
        long maxMemoryBytes = getLongMb(ENV_MAX_MEMORY_MB, PROP_MAX_MEMORY_MB, 256L);
        long minHeadroom = resolveMinHeadroomBytes();

        return new JournalConfig(
                compression,
                pruning,
                maxDicts,
                DEFAULT_TRAINING_SAMPLE_THRESHOLD,
                DEFAULT_DICT_SIZE_BYTES,
                maxMemoryBytes,
                minHeadroom);
    }

    private static boolean getBoolean(String envKey, String propKey, boolean defaultValue) {
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.isBlank()) {
            return Boolean.parseBoolean(envVal.trim().toLowerCase(Locale.ROOT));
        }
        String propVal = System.getProperty(propKey);
        if (propVal != null && !propVal.isBlank()) {
            return Boolean.parseBoolean(propVal.trim().toLowerCase(Locale.ROOT));
        }
        return defaultValue;
    }

    private static int getInt(String envKey, String propKey, int defaultValue) {
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.isBlank()) {
            try {
                return Integer.parseInt(envVal.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        String propVal = System.getProperty(propKey);
        if (propVal != null && !propVal.isBlank()) {
            try {
                return Integer.parseInt(propVal.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private static long getLongMb(String envKey, String propKey, long defaultMb) {
        String envVal = System.getenv(envKey);
        if (envVal != null && !envVal.isBlank()) {
            try {
                return Long.parseLong(envVal.trim()) * 1024 * 1024;
            } catch (NumberFormatException ignored) {
            }
        }
        String propVal = System.getProperty(propKey);
        if (propVal != null && !propVal.isBlank()) {
            try {
                return Long.parseLong(propVal.trim()) * 1024 * 1024;
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultMb * 1024 * 1024;
    }

    private static long resolveMinHeadroomBytes() {
        long maxMem = Runtime.getRuntime().maxMemory();
        long ratioBased = (long) (maxMem * DEFAULT_MIN_HEADROOM_RATIO);
        return Math.max(DEFAULT_MIN_HEADROOM_BYTES, Math.min(ratioBased, 128L * 1024 * 1024));
    }
}
