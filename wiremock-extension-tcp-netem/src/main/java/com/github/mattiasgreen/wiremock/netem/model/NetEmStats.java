package com.github.mattiasgreen.wiremock.netem.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class NetEmStats {

    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicLong totalConnections = new AtomicLong(0);
    private final AtomicLong bytesUpstream = new AtomicLong(0);
    private final AtomicLong bytesDownstream = new AtomicLong(0);
    private final AtomicLong impairmentsInjected = new AtomicLong(0);

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

    public void reset() {
        activeConnections.set(0);
        totalConnections.set(0);
        bytesUpstream.set(0);
        bytesDownstream.set(0);
        impairmentsInjected.set(0);
    }
}
