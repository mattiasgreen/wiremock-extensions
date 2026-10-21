package com.github.mattiasgreen.wiremock.netem.impairments;

import com.github.mattiasgreen.wiremock.netem.journal.NetEmCircularJournal;
import com.github.mattiasgreen.wiremock.netem.journal.NetEmJournalEntry;
import com.github.mattiasgreen.wiremock.netem.matcher.ByteStreamMatcher;
import com.github.mattiasgreen.wiremock.netem.model.NetEmStats;
import com.github.mattiasgreen.wiremock.netem.model.NetEmStreamDirection;
import java.net.Socket;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class NetEmContext {

    private final long connectionId;
    private final String proxyName;
    private final Socket clientSocket;
    private final Socket targetSocket;
    private final NetEmStats stats;
    private final ConcurrentMap<String, Object> attributes = new ConcurrentHashMap<>();
    private final Set<String> appliedToxics = ConcurrentHashMap.newKeySet();
    private final AtomicLong bytesUpstream = new AtomicLong(0);
    private final AtomicLong bytesDownstream = new AtomicLong(0);
    private final long startTimeEpochMs = System.currentTimeMillis();
    private volatile String scope = "";
    private volatile String terminationReason = "NORMAL_EOF";

    // Retain initial upstream bytes (up to 8192 bytes) for late-binding declarative stub toxic matching
    private final java.io.ByteArrayOutputStream upstreamBuffer = new java.io.ByteArrayOutputStream(8192);

    // Dynamic stream pattern scanners and match state
    private final ConcurrentMap<String, ByteStreamMatcher.StreamScanner> scanners = new ConcurrentHashMap<>();
    private final Set<String> matchedToxicNames = ConcurrentHashMap.newKeySet();

    // Per-HTTP transaction tracking (for keep-alive connection reuse)
    private final AtomicBoolean txActive = new AtomicBoolean(false);
    private final AtomicLong txBytesUpstream = new AtomicLong(0);
    private final AtomicLong txBytesDownstream = new AtomicLong(0);
    private final Set<String> txAppliedToxics = ConcurrentHashMap.newKeySet();
    private volatile long txStartTimeEpochMs = System.currentTimeMillis();
    private volatile String txScope = "";
    private volatile String txTerminationReason = "NORMAL_EOF";

    public NetEmContext(long connectionId, Socket clientSocket, Socket targetSocket, NetEmStats stats) {
        this(connectionId, "default", clientSocket, targetSocket, stats);
    }

    public NetEmContext(
            long connectionId, String proxyName, Socket clientSocket, Socket targetSocket, NetEmStats stats) {
        this.connectionId = connectionId;
        this.proxyName = proxyName != null ? proxyName : "default";
        this.clientSocket = Objects.requireNonNull(clientSocket, "clientSocket must not be null");
        this.targetSocket = Objects.requireNonNull(targetSocket, "targetSocket must not be null");
        this.stats = Objects.requireNonNull(stats, "stats must not be null");
    }

    public long getConnectionId() {
        return connectionId;
    }

    public String getProxyName() {
        return proxyName;
    }

    public Socket getClientSocket() {
        return clientSocket;
    }

    public Socket getTargetSocket() {
        return targetSocket;
    }

    public NetEmStats getStats() {
        return stats;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope != null ? scope : "";
        if (txActive.get()) {
            this.txScope = this.scope;
        }
    }

    /**
     * @deprecated Use {@link #getScope()} instead.
     */
    @Deprecated
    public String getCorrelationId() {
        return scope;
    }

    /**
     * @deprecated Use {@link #setScope(String)} instead.
     */
    @Deprecated
    public void setCorrelationId(String correlationId) {
        setScope(correlationId);
    }

    public long getStartTimeEpochMs() {
        return startTimeEpochMs;
    }

    public long getDurationMs() {
        return Math.max(0, System.currentTimeMillis() - startTimeEpochMs);
    }

    public void addBytesUpstream(long bytes) {
        bytesUpstream.addAndGet(bytes);
        stats.addBytesUpstream(bytes);
        if (txActive.get()) {
            txBytesUpstream.addAndGet(bytes);
        }
    }

    public void addBytesDownstream(long bytes) {
        bytesDownstream.addAndGet(bytes);
        stats.addBytesDownstream(bytes);
        if (txActive.get()) {
            txBytesDownstream.addAndGet(bytes);
        }
    }

    public long getBytesUpstream() {
        return bytesUpstream.get();
    }

    public long getBytesDownstream() {
        return bytesDownstream.get();
    }

    public void markToxicApplied(String toxicName) {
        if (toxicName != null && !toxicName.isBlank()) {
            appliedToxics.add(toxicName);
            stats.incrementImpairmentsInjected(toxicName);
            if (txActive.get()) {
                txAppliedToxics.add(toxicName);
            }
        }
    }

    public boolean isImpaired() {
        return !appliedToxics.isEmpty();
    }

    public Set<String> getAppliedToxics() {
        return Collections.unmodifiableSet(appliedToxics);
    }

    public String getAppliedToxicsString() {
        return String.join(", ", appliedToxics);
    }

    public String getTerminationReason() {
        return terminationReason;
    }

    public void setTerminationReason(String terminationReason) {
        if (terminationReason != null) {
            this.terminationReason = terminationReason;
            this.txTerminationReason = terminationReason;
        }
    }

    // --- Stream Pattern Matching State ---

    public ByteStreamMatcher.StreamScanner getOrCreateScanner(String toxicName, String pattern, String patternType) {
        return scanners.computeIfAbsent(toxicName, k -> new ByteStreamMatcher(pattern, patternType).createScanner());
    }

    public void markPatternMatched(String toxicName, String pattern) {
        if (toxicName != null) {
            matchedToxicNames.add(toxicName);
        }
        if (pattern != null && !pattern.isBlank() && (this.scope == null || this.scope.isBlank())) {
            this.scope = pattern;
            if (txActive.get()) {
                this.txScope = pattern;
            }
        }
    }

    public boolean hasMatchedToxic(String toxicName) {
        return toxicName != null && matchedToxicNames.contains(toxicName);
    }

    public synchronized void recordUpstreamBytes(byte[] buffer, int offset, int length) {
        if (upstreamBuffer.size() < 8192 && buffer != null && length > 0) {
            int toWrite = Math.min(length, 8192 - upstreamBuffer.size());
            upstreamBuffer.write(buffer, offset, toWrite);
        }
    }

    public synchronized boolean matchesUpstreamPrefix(String pattern, String patternType) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        byte[] bytes = upstreamBuffer.toByteArray();
        if (bytes.length == 0) {
            return false;
        }
        ByteStreamMatcher matcher = new ByteStreamMatcher(pattern, patternType);
        return matcher.matches(bytes, 0, bytes.length);
    }

    // --- Per-transaction lifecycle methods ---

    public void startTransaction() {
        startTransaction("");
    }

    public void startTransaction(String initialScope) {
        txActive.set(true);
        txStartTimeEpochMs = System.currentTimeMillis();
        this.txScope = initialScope != null ? initialScope : "";
        if (initialScope != null && !initialScope.isBlank()) {
            this.scope = initialScope;
        }
        txBytesUpstream.set(0);
        txBytesDownstream.set(0);
        txAppliedToxics.clear();
        matchedToxicNames.clear();
        synchronized (this) {
            upstreamBuffer.reset();
        }
        for (ByteStreamMatcher.StreamScanner scanner : scanners.values()) {
            scanner.reset();
        }
        txTerminationReason = "NORMAL_EOF";
    }

    public boolean isTransactionActive() {
        return txActive.get();
    }

    public void completeTransaction(NetEmCircularJournal journal, String defaultReason) {
        if (!txActive.compareAndSet(true, false)) {
            return;
        }
        long duration = Math.max(0, System.currentTimeMillis() - txStartTimeEpochMs);
        long up = txBytesUpstream.get();
        long down = txBytesDownstream.get();
        boolean impaired = !txAppliedToxics.isEmpty();
        String applied = String.join(", ", txAppliedToxics);
        String reason = "NORMAL_EOF".equals(txTerminationReason) ? defaultReason : txTerminationReason;

        stats.recordTransferCompletion(impaired, up, down);

        String clientAddr = clientSocket.getRemoteSocketAddress() != null
                ? clientSocket.getRemoteSocketAddress().toString()
                : "unknown";

        String recordedScope = (txScope != null && !txScope.isBlank()) ? txScope : scope;

        NetEmJournalEntry entry = new NetEmJournalEntry(
                journal.getTotalRecordedCount(),
                txStartTimeEpochMs,
                proxyName,
                clientAddr,
                recordedScope,
                duration,
                up,
                down,
                impaired,
                applied,
                reason);
        journal.record(entry);
    }

    public Socket getSourceSocket(NetEmStreamDirection direction) {
        return direction == NetEmStreamDirection.UPSTREAM ? clientSocket : targetSocket;
    }

    public Socket getDestinationSocket(NetEmStreamDirection direction) {
        return direction == NetEmStreamDirection.UPSTREAM ? targetSocket : clientSocket;
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    public void setAttribute(String key, Object value) {
        if (value != null) {
            attributes.put(key, value);
        } else {
            attributes.remove(key);
        }
    }

    public boolean isClosed() {
        return clientSocket.isClosed() || targetSocket.isClosed();
    }
}
