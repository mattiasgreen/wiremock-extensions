package com.github.mattiasgreen.wiremock.journal.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Snapshot metrics describing journal memory savings, compression density, and dictionary state.
 */
public record JournalStats(
        @JsonProperty("totalEvents") long totalEvents,
        @JsonProperty("rawBytesTotal") long rawBytesTotal,
        @JsonProperty("compressedBytesTotal") long compressedBytesTotal,
        @JsonProperty("compressionRatio") double compressionRatio,
        @JsonProperty("activeDictionaries") int activeDictionaries,
        @JsonProperty("totalEvictions") long totalEvictions,
        @JsonProperty("usedMemoryBytes") long usedMemoryBytes,
        @JsonProperty("maxMemoryBytes") long maxMemoryBytes) {}
