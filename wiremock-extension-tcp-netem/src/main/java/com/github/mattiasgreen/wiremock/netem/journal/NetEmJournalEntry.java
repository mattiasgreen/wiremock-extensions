package com.github.mattiasgreen.wiremock.netem.journal;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Compact immutable record representing a completed TCP proxy connection/transfer event.
 * Designed for high throughput and minimal memory footprint in central mock servers (~80-120 bytes shallow).
 */
public record NetEmJournalEntry(
        @JsonProperty("id") long id,
        @JsonProperty("timestampEpochMs") long timestampEpochMs,
        @JsonProperty("proxyName") String proxyName,
        @JsonProperty("clientAddress") String clientAddress,
        @JsonProperty("correlationId") String correlationId,
        @JsonProperty("durationMs") long durationMs,
        @JsonProperty("bytesUpstream") long bytesUpstream,
        @JsonProperty("bytesDownstream") long bytesDownstream,
        @JsonProperty("impaired") boolean impaired,
        @JsonProperty("appliedToxics") String appliedToxics,
        @JsonProperty("terminationReason") String terminationReason) {

    public NetEmJournalEntry {
        proxyName = proxyName != null ? proxyName : "unknown";
        clientAddress = clientAddress != null ? clientAddress : "";
        correlationId = correlationId != null ? correlationId : "";
        appliedToxics = appliedToxics != null ? appliedToxics : "";
        terminationReason = terminationReason != null ? terminationReason : "NORMAL_EOF";
    }
}
