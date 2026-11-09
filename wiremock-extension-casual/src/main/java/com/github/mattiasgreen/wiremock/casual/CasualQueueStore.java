package com.github.mattiasgreen.wiremock.casual;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import javax.transaction.xa.Xid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.laz.casual.api.buffer.type.ServiceBuffer;
import se.laz.casual.api.queue.QueueErrorCode;
import se.laz.casual.api.queue.QueueMessage;
import se.laz.casual.api.xa.XID;
import se.laz.casual.network.ProtocolVersion;
import se.laz.casual.network.protocol.messages.domain.Queue;
import se.laz.casual.network.protocol.messages.queue.CasualDequeueReplyMessage;
import se.laz.casual.network.protocol.messages.queue.CasualDequeueRequestMessage;
import se.laz.casual.network.protocol.messages.queue.CasualEnqueueReplyMessage;
import se.laz.casual.network.protocol.messages.queue.CasualEnqueueRequestMessage;
import se.laz.casual.network.protocol.messages.queue.DequeueMessage;
import se.laz.casual.network.protocol.messages.queue.EnqueueMessage;

/**
 * Thread-safe in-memory store for Casual Queue simulation.
 * Manages FIFO queues, dead letter queues (&lt;queue&gt;.error), retry counts,
 * rollback retry delays, and 2PC/XA transaction commit/rollback enlistment.
 */
public class CasualQueueStore {

    private static final Logger LOG = LoggerFactory.getLogger(CasualQueueStore.class);

    public static final long DEFAULT_RETRIES = 3L;
    public static final long DEFAULT_RETRY_DELAY_MS = 1000L;

    public static class QueueConfig {
        private final String name;
        private final long retries;
        private final long retryDelayMs;
        private final boolean enqueueEnabled;
        private final boolean dequeueEnabled;

        public QueueConfig(
                String name, long retries, long retryDelayMs, boolean enqueueEnabled, boolean dequeueEnabled) {
            this.name = Objects.requireNonNull(name, "name must not be null");
            this.retries = retries >= 0 ? retries : DEFAULT_RETRIES;
            this.retryDelayMs = retryDelayMs >= 0 ? retryDelayMs : DEFAULT_RETRY_DELAY_MS;
            this.enqueueEnabled = enqueueEnabled;
            this.dequeueEnabled = dequeueEnabled;
        }

        public String getName() {
            return name;
        }

        public long getRetries() {
            return retries;
        }

        public long getRetryDelayMs() {
            return retryDelayMs;
        }

        public boolean isEnqueueEnabled() {
            return enqueueEnabled;
        }

        public boolean isDequeueEnabled() {
            return dequeueEnabled;
        }
    }

    public static class StoredMessage {
        private final UUID id;
        private final String correlationInformation;
        private final String replyQueue;
        private final ServiceBuffer payload;
        private volatile Instant availableSince;
        private final AtomicLong redelivered = new AtomicLong(0);
        private final Instant timestamp;

        public StoredMessage(
                UUID id,
                String correlationInformation,
                String replyQueue,
                ServiceBuffer payload,
                Instant availableSince) {
            this.id = id != null ? id : UUID.randomUUID();
            this.correlationInformation = correlationInformation != null ? correlationInformation : "";
            this.replyQueue = replyQueue != null ? replyQueue : "";
            this.payload = payload;
            this.availableSince = availableSince != null ? availableSince : Instant.now();
            this.timestamp = Instant.now();
        }

        public UUID getId() {
            return id;
        }

        public String getCorrelationInformation() {
            return correlationInformation;
        }

        public String getReplyQueue() {
            return replyQueue;
        }

        public ServiceBuffer getPayload() {
            return payload;
        }

        public Instant getAvailableSince() {
            return availableSince;
        }

        public void setAvailableSince(Instant availableSince) {
            this.availableSince = availableSince;
        }

        public long getRedelivered() {
            return redelivered.get();
        }

        public void incrementRedelivered() {
            this.redelivered.incrementAndGet();
        }

        public Instant getTimestamp() {
            return timestamp;
        }

        public DequeueMessage toDequeueMessage() {
            QueueMessage qm = QueueMessage.createBuilder()
                    .withId(id)
                    .withCorrelationInformation(correlationInformation)
                    .withReplyQueue(replyQueue)
                    .withPayload(payload)
                    .withRedelivered(redelivered.get())
                    .withAvailableSince(availableSince)
                    .withTimestamp(timestamp)
                    .build();
            return DequeueMessage.of(qm);
        }
    }

    private static class TxEnqueueEntry {
        final String queueName;
        final StoredMessage message;

        TxEnqueueEntry(String queueName, StoredMessage message) {
            this.queueName = queueName;
            this.message = message;
        }
    }

    private static class TxDequeueEntry {
        final String queueName;
        final StoredMessage message;

        TxDequeueEntry(String queueName, StoredMessage message) {
            this.queueName = queueName;
            this.message = message;
        }
    }

    private final Map<String, QueueConfig> queueConfigs = new ConcurrentHashMap<>();
    private final Map<String, Deque<StoredMessage>> queues = new ConcurrentHashMap<>();
    private final Map<Xid, List<TxEnqueueEntry>> uncommittedEnqueues = new ConcurrentHashMap<>();
    private final Map<Xid, List<TxDequeueEntry>> inFlightDequeues = new ConcurrentHashMap<>();

    public CasualQueueStore() {}

    public synchronized void createQueue(String name, long retries, long retryDelayMs) {
        queueConfigs.put(name, new QueueConfig(name, retries, retryDelayMs, true, true));
        queues.computeIfAbsent(name, k -> new ArrayDeque<>());
        // Auto-create DLQ (.error)
        String dlqName = name + ".error";
        queueConfigs.putIfAbsent(dlqName, new QueueConfig(dlqName, 0, 0, true, true));
        queues.computeIfAbsent(dlqName, k -> new ArrayDeque<>());
    }

    public synchronized QueueConfig getOrCreateConfig(String name) {
        QueueConfig config = queueConfigs.get(name);
        if (config == null) {
            createQueue(name, DEFAULT_RETRIES, DEFAULT_RETRY_DELAY_MS);
            config = queueConfigs.get(name);
        }
        return config;
    }

    public synchronized CasualEnqueueReplyMessage enqueue(
            CasualEnqueueRequestMessage request, ProtocolVersion version) {
        String queueName = request.getQueueName();
        EnqueueMessage em = request.getMessage();
        QueueConfig config = getOrCreateConfig(queueName);

        if (!config.isEnqueueEnabled()) {
            return buildEnqueueReply(request.getExecution(), UUID.randomUUID(), version, QueueErrorCode.SYSTEM);
        }

        UUID messageId = em.getId() != null ? em.getId() : UUID.randomUUID();
        StoredMessage stored = new StoredMessage(
                messageId, em.getCorrelationInformation(), em.getReplyQueue(), em.getPayload(), em.getAvailableSince());

        Xid xid = request.getXid();
        if (isTransactional(xid)) {
            uncommittedEnqueues
                    .computeIfAbsent(xid, k -> new CopyOnWriteArrayList<>())
                    .add(new TxEnqueueEntry(queueName, stored));
            LOG.debug(
                    "[CASUAL-QUEUE-STORE] Staged uncommitted enqueue id={} on queue={} tx={}",
                    messageId,
                    queueName,
                    xid);
        } else {
            Deque<StoredMessage> q = queues.computeIfAbsent(queueName, k -> new ArrayDeque<>());
            q.addLast(stored);
            LOG.debug("[CASUAL-QUEUE-STORE] Direct enqueue id={} on queue={}", messageId, queueName);
        }

        return buildEnqueueReply(request.getExecution(), messageId, version, QueueErrorCode.OK);
    }

    public synchronized CasualDequeueReplyMessage dequeue(
            CasualDequeueRequestMessage request, ProtocolVersion version) {
        String queueName = request.getQueueName();
        QueueConfig config = getOrCreateConfig(queueName);

        if (!config.isDequeueEnabled()) {
            return buildDequeueReply(request.getExecution(), Collections.emptyList(), version, QueueErrorCode.SYSTEM);
        }

        Deque<StoredMessage> q = queues.computeIfAbsent(queueName, k -> new ArrayDeque<>());
        Instant now = Instant.now();

        StoredMessage selected = null;
        for (StoredMessage candidate : q) {
            if (!candidate.getAvailableSince().isAfter(now)) {
                selected = candidate;
                break;
            }
        }

        if (selected == null) {
            return buildDequeueReply(
                    request.getExecution(), Collections.emptyList(), version, QueueErrorCode.NO_MESSAGE);
        }

        q.remove(selected);
        Xid xid = request.getXid();
        if (isTransactional(xid)) {
            inFlightDequeues
                    .computeIfAbsent(xid, k -> new CopyOnWriteArrayList<>())
                    .add(new TxDequeueEntry(queueName, selected));
            LOG.debug(
                    "[CASUAL-QUEUE-STORE] In-flight transactional dequeue id={} on queue={} tx={}",
                    selected.getId(),
                    queueName,
                    xid);
        } else {
            LOG.debug("[CASUAL-QUEUE-STORE] Direct dequeue id={} on queue={}", selected.getId(), queueName);
        }

        List<DequeueMessage> messages = List.of(selected.toDequeueMessage());
        return buildDequeueReply(request.getExecution(), messages, version, QueueErrorCode.OK);
    }

    public synchronized void commit(Xid xid) {
        if (!isTransactional(xid)) {
            return;
        }

        List<TxEnqueueEntry> enqueues = uncommittedEnqueues.remove(xid);
        if (enqueues != null) {
            for (TxEnqueueEntry entry : enqueues) {
                queues.computeIfAbsent(entry.queueName, k -> new ArrayDeque<>()).addLast(entry.message);
                LOG.debug(
                        "[CASUAL-QUEUE-STORE] Committed enqueue id={} to queue={}",
                        entry.message.getId(),
                        entry.queueName);
            }
        }

        List<TxDequeueEntry> dequeues = inFlightDequeues.remove(xid);
        if (dequeues != null) {
            for (TxDequeueEntry entry : dequeues) {
                LOG.debug(
                        "[CASUAL-QUEUE-STORE] Committed dequeue (permanently consumed) id={} from queue={}",
                        entry.message.getId(),
                        entry.queueName);
            }
        }
    }

    public synchronized void rollback(Xid xid) {
        if (!isTransactional(xid)) {
            return;
        }

        List<TxEnqueueEntry> enqueues = uncommittedEnqueues.remove(xid);
        if (enqueues != null) {
            LOG.debug(
                    "[CASUAL-QUEUE-STORE] Rolled back and discarded {} uncommitted enqueue(s) for tx={}",
                    enqueues.size(),
                    xid);
        }

        List<TxDequeueEntry> dequeues = inFlightDequeues.remove(xid);
        if (dequeues != null) {
            for (TxDequeueEntry entry : dequeues) {
                QueueConfig config = getOrCreateConfig(entry.queueName);
                entry.message.incrementRedelivered();

                if (entry.message.getRedelivered() >= config.getRetries()) {
                    String dlq = entry.queueName + ".error";
                    getOrCreateConfig(dlq);
                    queues.computeIfAbsent(dlq, k -> new ArrayDeque<>()).addLast(entry.message);
                    LOG.warn(
                            "[CASUAL-QUEUE-STORE] Message id={} exceeded max retries ({} >= {}). Routed to DLQ queue [{}]",
                            entry.message.getId(),
                            entry.message.getRedelivered(),
                            config.getRetries(),
                            dlq);
                } else {
                    entry.message.setAvailableSince(Instant.now().plusMillis(config.getRetryDelayMs()));
                    queues.computeIfAbsent(entry.queueName, k -> new ArrayDeque<>())
                            .addFirst(entry.message);
                    LOG.info(
                            "[CASUAL-QUEUE-STORE] Rolled back dequeue id={}. Retry count {}/{}, delayed until {}",
                            entry.message.getId(),
                            entry.message.getRedelivered(),
                            config.getRetries(),
                            entry.message.getAvailableSince());
                }
            }
        }
    }

    public synchronized List<Queue> getDiscoveredQueues(List<String> requestedNames, ProtocolVersion version) {
        List<Queue> result = new ArrayList<>();
        ProtocolVersion ver = version != null ? version : ProtocolVersion.VERSION_1_0;

        for (QueueConfig config : queueConfigs.values()) {
            if (requestedNames == null || requestedNames.isEmpty() || requestedNames.contains(config.getName())) {
                Queue q = Queue.createBuilder()
                        .withName(config.getName())
                        .withProtocolVersion(ver)
                        .withRetries(config.getRetries())
                        .withRetryDelay(config.getRetryDelayMs())
                        .withEnqueueEnabled(config.isEnqueueEnabled())
                        .withDequeueEnabled(config.isDequeueEnabled())
                        .build();
                result.add(q);
            }
        }
        return result;
    }

    public synchronized int getQueueSize(String queueName) {
        Deque<StoredMessage> q = queues.get(queueName);
        return q != null ? q.size() : 0;
    }

    public synchronized void clear() {
        queues.clear();
        uncommittedEnqueues.clear();
        inFlightDequeues.clear();
    }

    private boolean isTransactional(Xid xid) {
        return xid != null && !XID.NULL_XID.equals(xid);
    }

    private CasualEnqueueReplyMessage buildEnqueueReply(
            UUID execution, UUID messageId, ProtocolVersion version, QueueErrorCode code) {
        ProtocolVersion ver = version != null ? version : ProtocolVersion.VERSION_1_0;
        CasualEnqueueReplyMessage.Builder b = CasualEnqueueReplyMessage.createBuilder()
                .withExecution(execution)
                .withId(messageId)
                .withProtocolVersion(ver);

        if (ver.isGreaterThanOrEqualTo(ProtocolVersion.VERSION_1_3)) {
            b.withCode(code);
        }
        return b.build();
    }

    private CasualDequeueReplyMessage buildDequeueReply(
            UUID execution, List<DequeueMessage> messages, ProtocolVersion version, QueueErrorCode code) {
        ProtocolVersion ver = version != null ? version : ProtocolVersion.VERSION_1_0;
        CasualDequeueReplyMessage.Builder b = CasualDequeueReplyMessage.createBuilder()
                .withExecution(execution)
                .withMessages(messages)
                .withProtocolVersion(ver);

        if (ver.isGreaterThanOrEqualTo(ProtocolVersion.VERSION_1_3)) {
            b.withCode(code);
        }
        return b.build();
    }
}
