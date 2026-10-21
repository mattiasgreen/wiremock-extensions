package com.github.mattiasgreen.wiremock.netem.journal;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Compact immutable record representing a completed TCP proxy connection/transfer event.
 * Designed for high throughput and minimal memory footprint in central mock servers (~80-120 bytes shallow).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NetEmJournalEntry(
        @JsonProperty("id") long id,
        @JsonProperty("timestampEpochMs") long timestampEpochMs,
        @JsonProperty("proxyName") String proxyName,
        @JsonProperty("clientAddress") String clientAddress,
        @JsonProperty("scope") String scope,
        @JsonProperty("durationMs") long durationMs,
        @JsonProperty("bytesUpstream") long bytesUpstream,
        @JsonProperty("bytesDownstream") long bytesDownstream,
        @JsonProperty("impaired") boolean impaired,
        @JsonProperty("appliedToxics") String appliedToxics,
        @JsonProperty("terminationReason") String terminationReason) {

    public NetEmJournalEntry {
        proxyName = proxyName != null ? proxyName : "unknown";
        clientAddress = clientAddress != null ? clientAddress : "";
        scope = scope != null ? scope : "";
        appliedToxics = appliedToxics != null ? appliedToxics : "";
        terminationReason = terminationReason != null ? terminationReason : "NORMAL_EOF";
    }

    @JsonCreator
    public static NetEmJournalEntry fromJson(
            @JsonProperty("id") long id,
            @JsonProperty("timestampEpochMs") long timestampEpochMs,
            @JsonProperty("proxyName") String proxyName,
            @JsonProperty("clientAddress") String clientAddress,
            @JsonProperty("scope") String scope,
            @JsonProperty("correlationId") String legacyCorrelationId,
            @JsonProperty("durationMs") long durationMs,
            @JsonProperty("bytesUpstream") long bytesUpstream,
            @JsonProperty("bytesDownstream") long bytesDownstream,
            @JsonProperty("impaired") boolean impaired,
            @JsonProperty("appliedToxics") String appliedToxics,
            @JsonProperty("terminationReason") String terminationReason) {
        String effectiveScope =
                (scope != null && !scope.isBlank()) ? scope : (legacyCorrelationId != null ? legacyCorrelationId : "");
        return new NetEmJournalEntry(
                id,
                timestampEpochMs,
                proxyName,
                clientAddress,
                effectiveScope,
                durationMs,
                bytesUpstream,
                bytesDownstream,
                impaired,
                appliedToxics,
                terminationReason);
    }

    /**
     * @deprecated Use {@link #scope()} instead.
     */
    @Deprecated
    public String correlationId() {
        return scope;
    }
}
