package com.github.mattiasgreen.wiremock.netem.impairments;

import com.github.mattiasgreen.wiremock.netem.journal.NetEmCircularJournal;
import com.github.mattiasgreen.wiremock.netem.journal.NetEmJournalEntry;
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
    private volatile String correlationId;
    private volatile String terminationReason = "NORMAL_EOF";

    // Per-HTTP transaction tracking (for keep-alive connection reuse)
    private final AtomicBoolean txActive = new AtomicBoolean(false);
    private final AtomicLong txBytesUpstream = new AtomicLong(0);
    private final AtomicLong txBytesDownstream = new AtomicLong(0);
    private final Set<String> txAppliedToxics = ConcurrentHashMap.newKeySet();
    private volatile long txStartTimeEpochMs = System.currentTimeMillis();
    private volatile String txCorrelationId;
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

    public String getCorrelationId() {
        return correlationId;
    }

    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
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

    // --- Per-transaction lifecycle methods ---

    public void startTransaction(String correlationId) {
        txActive.set(true);
        txStartTimeEpochMs = System.currentTimeMillis();
        this.txCorrelationId = correlationId;
        this.correlationId = correlationId;
        txBytesUpstream.set(0);
        txBytesDownstream.set(0);
        txAppliedToxics.clear();
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

        NetEmJournalEntry entry = new NetEmJournalEntry(
                journal.getTotalRecordedCount(),
                txStartTimeEpochMs,
                proxyName,
                clientAddr,
                txCorrelationId != null ? txCorrelationId : correlationId,
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
