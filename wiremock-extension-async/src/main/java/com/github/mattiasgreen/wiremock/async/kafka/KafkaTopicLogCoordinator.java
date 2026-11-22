package com.github.mattiasgreen.wiremock.async.kafka;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;

/**
 * Pure-Java, socket-free, thread-safe in-memory Kafka topic log and consumer-group coordinator.
 *
 * <p>Owned by {@link KafkaWireSink} and shared across all per-connection {@link KafkaWireCodec}
 * instances so producers, consumers, feed staging, and Async Destination Explorer operations
 * observe a single consistent state.
 *
 * <p><b>Note:</b> This is Beta functionality subject to evolution.
 */
public class KafkaTopicLogCoordinator {

    /** Kafka {@code ListOffsets} sentinel timestamp requesting the earliest available offset. */
    public static final long EARLIEST_TIMESTAMP = -2L;

    /** Kafka {@code ListOffsets} sentinel timestamp requesting the latest (log end) offset. */
    public static final long LATEST_TIMESTAMP = -1L;

    /** Kafka {@code OffsetFetch} sentinel indicating no committed offset exists for the group. */
    public static final long NO_COMMITTED_OFFSET = -1L;

    private static final Comparator<TopicPartitionKey> TOPIC_PARTITION_ORDER =
            Comparator.comparing(TopicPartitionKey::topic).thenComparingInt(TopicPartitionKey::partition);

    private final ConcurrentMap<TopicPartitionKey, PartitionLog> partitionLogs = new ConcurrentHashMap<>();
    private final ConcurrentMap<GroupTopicPartitionKey, Long> committedOffsets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, List<String>> memberSubscriptions = new ConcurrentHashMap<>();

    public record TopicPartitionKey(String topic, int partition) {
        public TopicPartitionKey {
            topic = Objects.requireNonNullElse(topic, "");
        }
    }

    private record GroupTopicPartitionKey(String groupId, String topic, int partition) {
        private GroupTopicPartitionKey {
            groupId = Objects.requireNonNullElse(groupId, "");
            topic = Objects.requireNonNullElse(topic, "");
        }
    }

    public record FetchSlice(List<KafkaProduceRecord> records, long logStartOffset, long highWatermark) {
        public FetchSlice {
            records = records != null ? List.copyOf(records) : Collections.emptyList();
        }
    }

    public KafkaProduceRecord append(
            String topic, int partition, String key, String value, Map<String, String> headers, long timestamp) {
        String safeTopic = Objects.requireNonNullElse(topic, "");
        PartitionLog log = partitionLogs.computeIfAbsent(
                new TopicPartitionKey(safeTopic, partition), ignored -> new PartitionLog());
        return log.append(safeTopic, partition, key, value, headers, timestamp);
    }

    public KafkaProduceRecord stage(KafkaProduceRecord record) {
        if (record == null) {
            return null;
        }
        PartitionLog log = partitionLogs.computeIfAbsent(
                new TopicPartitionKey(record.topic(), record.partition()), ignored -> new PartitionLog());
        return log.stage(record);
    }

    public FetchSlice fetch(String topic, int partition, long fromOffset) {
        PartitionLog log = partitionLogs.get(new TopicPartitionKey(Objects.requireNonNullElse(topic, ""), partition));
        if (log == null) {
            return new FetchSlice(Collections.emptyList(), 0L, 0L);
        }
        return log.fetch(fromOffset);
    }

    public long listOffset(String topic, int partition, long timestamp) {
        PartitionLog log = partitionLogs.get(new TopicPartitionKey(Objects.requireNonNullElse(topic, ""), partition));
        if (log == null) {
            return 0L;
        }
        return log.listOffset(timestamp);
    }

    public long logStartOffset(String topic, int partition) {
        PartitionLog log = partitionLogs.get(new TopicPartitionKey(Objects.requireNonNullElse(topic, ""), partition));
        return log != null ? log.logStartOffset() : 0L;
    }

    public long highWatermark(String topic, int partition) {
        PartitionLog log = partitionLogs.get(new TopicPartitionKey(Objects.requireNonNullElse(topic, ""), partition));
        return log != null ? log.highWatermark() : 0L;
    }

    public List<KafkaProduceRecord> getAllRecords() {
        List<TopicPartitionKey> keys = new ArrayList<>(partitionLogs.keySet());
        keys.sort(TOPIC_PARTITION_ORDER);
        List<KafkaProduceRecord> all = new ArrayList<>();
        for (TopicPartitionKey key : keys) {
            PartitionLog log = partitionLogs.get(key);
            if (log != null) {
                all.addAll(log.snapshot());
            }
        }
        return Collections.unmodifiableList(all);
    }

    public Set<String> listTopics() {
        List<String> topics = new ArrayList<>();
        for (TopicPartitionKey key : partitionLogs.keySet()) {
            if (!key.topic().isBlank()) {
                topics.add(key.topic());
            }
        }
        Collections.sort(topics);
        return Collections.unmodifiableSet(new LinkedHashSet<>(topics));
    }

    public boolean deleteRecord(String topic, String keyOrId) {
        if (topic == null) {
            return false;
        }
        boolean removed = false;
        Predicate<KafkaProduceRecord> matcher = r -> keyOrId == null
                || keyOrId.equals(r.recordId())
                || keyOrId.equals(String.valueOf(r.offset()))
                || keyOrId.equals(r.key())
                || keyOrId.equals(r.value());
        for (Map.Entry<TopicPartitionKey, PartitionLog> entry : partitionLogs.entrySet()) {
            if (entry.getKey().topic().equals(topic) && entry.getValue().deleteMatching(matcher)) {
                removed = true;
            }
        }
        return removed;
    }

    public void purgeTopic(String topic) {
        if (topic == null) {
            return;
        }
        for (Map.Entry<TopicPartitionKey, PartitionLog> entry : partitionLogs.entrySet()) {
            if (entry.getKey().topic().equals(topic)) {
                entry.getValue().purge();
            }
        }
    }

    public void commitOffset(String groupId, String topic, int partition, long offset) {
        committedOffsets.put(new GroupTopicPartitionKey(groupId, topic, partition), offset);
    }

    public long fetchCommittedOffset(String groupId, String topic, int partition) {
        return committedOffsets.getOrDefault(
                new GroupTopicPartitionKey(groupId, topic, partition), NO_COMMITTED_OFFSET);
    }

    public Map<TopicPartitionKey, Long> fetchAllCommittedOffsets(String groupId) {
        String safeGroup = Objects.requireNonNullElse(groupId, "");
        Map<TopicPartitionKey, Long> result = new LinkedHashMap<>();
        for (Map.Entry<GroupTopicPartitionKey, Long> entry : committedOffsets.entrySet()) {
            GroupTopicPartitionKey key = entry.getKey();
            if (key.groupId().equals(safeGroup)) {
                result.put(new TopicPartitionKey(key.topic(), key.partition()), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    public void recordMemberSubscription(String memberId, List<String> topics) {
        if (memberId == null || memberId.isBlank() || topics == null || topics.isEmpty()) {
            return;
        }
        memberSubscriptions.put(memberId, List.copyOf(topics));
    }

    public List<String> getMemberSubscription(String memberId) {
        if (memberId == null) {
            return Collections.emptyList();
        }
        return memberSubscriptions.getOrDefault(memberId, Collections.emptyList());
    }

    public void clear() {
        partitionLogs.clear();
        committedOffsets.clear();
        memberSubscriptions.clear();
    }

    private static final class PartitionLog {
        private final List<KafkaProduceRecord> records = new ArrayList<>();
        private long nextOffset = 0L;

        synchronized KafkaProduceRecord append(
                String topic, int partition, String key, String value, Map<String, String> headers, long timestamp) {
            long assignedOffset = nextOffset++;
            KafkaProduceRecord record =
                    new KafkaProduceRecord(topic, partition, key, value, headers, assignedOffset, timestamp);
            records.add(record);
            return record;
        }

        synchronized KafkaProduceRecord stage(KafkaProduceRecord record) {
            long assignedOffset = record.offset() >= nextOffset ? record.offset() : nextOffset;
            nextOffset = assignedOffset + 1;
            KafkaProduceRecord stored = assignedOffset == record.offset() ? record : record.withOffset(assignedOffset);
            records.add(stored);
            return stored;
        }

        synchronized FetchSlice fetch(long fromOffset) {
            List<KafkaProduceRecord> matching = new ArrayList<>();
            for (KafkaProduceRecord r : records) {
                if (r.offset() >= fromOffset) {
                    matching.add(r);
                }
            }
            return new FetchSlice(matching, logStartOffsetLocked(), nextOffset);
        }

        synchronized long listOffset(long timestamp) {
            if (timestamp == EARLIEST_TIMESTAMP) {
                return logStartOffsetLocked();
            }
            if (timestamp == LATEST_TIMESTAMP) {
                return nextOffset;
            }
            for (KafkaProduceRecord r : records) {
                if (r.timestamp() >= timestamp) {
                    return r.offset();
                }
            }
            return nextOffset;
        }

        synchronized long logStartOffset() {
            return logStartOffsetLocked();
        }

        synchronized long highWatermark() {
            return nextOffset;
        }

        synchronized boolean deleteMatching(Predicate<KafkaProduceRecord> matcher) {
            return records.removeIf(matcher);
        }

        synchronized void purge() {
            records.clear();
        }

        synchronized List<KafkaProduceRecord> snapshot() {
            return List.copyOf(records);
        }

        private long logStartOffsetLocked() {
            return records.isEmpty() ? nextOffset : records.get(0).offset();
        }
    }
}
