package com.github.mattiasgreen.wiremock.support;

import java.util.Map;

/**
 * Immutable point-in-time snapshot of traffic counters for a single TCP NetEm proxy.
 *
 * @param name configured proxy name
 * @param activeConnections current number of open TCP connections
 * @param normalTransfers cumulative count of unimpaired transfers
 * @param impairedTransfers cumulative count of impaired transfers
 * @param toxicCounters per-toxic impairment counters
 * @param bytesUpstreamNormal cumulative upstream bytes for unimpaired transfers
 * @param bytesDownstreamNormal cumulative downstream bytes for unimpaired transfers
 * @param bytesUpstreamImpaired cumulative upstream bytes for impaired transfers
 * @param bytesDownstreamImpaired cumulative downstream bytes for impaired transfers
 */
public record NetEmProxyMetricsSnapshot(
        String name,
        long activeConnections,
        long normalTransfers,
        long impairedTransfers,
        Map<String, Long> toxicCounters,
        long bytesUpstreamNormal,
        long bytesDownstreamNormal,
        long bytesUpstreamImpaired,
        long bytesDownstreamImpaired) {

    public NetEmProxyMetricsSnapshot {
        toxicCounters = toxicCounters != null ? Map.copyOf(toxicCounters) : Map.of();
    }
}
