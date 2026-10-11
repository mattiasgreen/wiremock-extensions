package com.github.mattiasgreen.wiremock.netem.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class NetEmStats {

    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicLong totalConnections = new AtomicLong(0);
    private final AtomicLong bytesUpstream = new AtomicLong(0);
    private final AtomicLong bytesDownstream = new AtomicLong(0);
    private final AtomicLong impairmentsInjected = new AtomicLong(0);

    // Differentiated metrics (Normal vs Impaired traffic)
    private final AtomicLong normalTransfers = new AtomicLong(0);
    private final AtomicLong impairedTransfers = new AtomicLong(0);
    private final AtomicLong bytesUpstreamNormal = new AtomicLong(0);
    private final AtomicLong bytesUpstreamImpaired = new AtomicLong(0);
    private final AtomicLong bytesDownstreamNormal = new AtomicLong(0);
    private final AtomicLong bytesDownstreamImpaired = new AtomicLong(0);

    private final ConcurrentMap<String, AtomicLong> toxicCounters = new ConcurrentHashMap<>();

    public void incrementActiveConnections() {
        activeConnections.incrementAndGet();
        totalConnections.incrementAndGet();
    }

    public void decrementActiveConnections() {
        activeConnections.decrementAndGet();
    }

    public void addBytesUpstream(long bytes) {
        bytesUpstream.addAndGet(bytes);
    }

    public void addBytesDownstream(long bytes) {
        bytesDownstream.addAndGet(bytes);
    }

    public void incrementImpairmentsInjected() {
        impairmentsInjected.incrementAndGet();
    }

    public void incrementImpairmentsInjected(String toxicName) {
        impairmentsInjected.incrementAndGet();
        if (toxicName != null && !toxicName.isBlank()) {
            toxicCounters.computeIfAbsent(toxicName, k -> new AtomicLong(0)).incrementAndGet();
        }
    }

    public void recordTransferCompletion(boolean impaired, long upBytes, long downBytes) {
        if (impaired) {
            impairedTransfers.incrementAndGet();
            bytesUpstreamImpaired.addAndGet(upBytes);
            bytesDownstreamImpaired.addAndGet(downBytes);
        } else {
            normalTransfers.incrementAndGet();
            bytesUpstreamNormal.addAndGet(upBytes);
            bytesDownstreamNormal.addAndGet(downBytes);
        }
    }

    @JsonProperty("activeConnections")
    public int getActiveConnections() {
        return activeConnections.get();
    }

    @JsonProperty("totalConnections")
    public long getTotalConnections() {
        return totalConnections.get();
    }

    @JsonProperty("bytesUpstream")
    public long getBytesUpstream() {
        return bytesUpstream.get();
    }

    @JsonProperty("bytesDownstream")
    public long getBytesDownstream() {
        return bytesDownstream.get();
    }

    @JsonProperty("impairmentsInjected")
    public long getImpairmentsInjected() {
        return impairmentsInjected.get();
    }

    @JsonProperty("normalTransfers")
    public long getNormalTransfers() {
        return normalTransfers.get();
    }

    @JsonProperty("impairedTransfers")
    public long getImpairedTransfers() {
        return impairedTransfers.get();
    }

    @JsonProperty("bytesUpstreamNormal")
    public long getBytesUpstreamNormal() {
        return bytesUpstreamNormal.get();
    }

    @JsonProperty("bytesUpstreamImpaired")
    public long getBytesUpstreamImpaired() {
        return bytesUpstreamImpaired.get();
    }

    @JsonProperty("bytesDownstreamNormal")
    public long getBytesDownstreamNormal() {
        return bytesDownstreamNormal.get();
    }

    @JsonProperty("bytesDownstreamImpaired")
    public long getBytesDownstreamImpaired() {
        return bytesDownstreamImpaired.get();
    }

    @JsonProperty("toxicCounters")
    public Map<String, Long> getToxicCounters() {
        Map<String, Long> result = new ConcurrentHashMap<>();
        toxicCounters.forEach((k, v) -> result.put(k, v.get()));
        return Collections.unmodifiableMap(result);
    }

    public void reset() {
        activeConnections.set(0);
        totalConnections.set(0);
        bytesUpstream.set(0);
        bytesDownstream.set(0);
        impairmentsInjected.set(0);
        normalTransfers.set(0);
        impairedTransfers.set(0);
        bytesUpstreamNormal.set(0);
        bytesUpstreamImpaired.set(0);
        bytesDownstreamNormal.set(0);
        bytesDownstreamImpaired.set(0);
        toxicCounters.clear();
    }
}
