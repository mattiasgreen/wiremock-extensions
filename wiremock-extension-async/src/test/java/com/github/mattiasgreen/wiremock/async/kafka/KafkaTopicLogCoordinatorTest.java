package com.github.mattiasgreen.wiremock.async.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KafkaTopicLogCoordinatorTest {

    @Test
    @DisplayName("Should append records with stable monotonic offsets and IDs and fetch by offset")
    void shouldAppendAndFetchWithMonotonicOffsets() {
        KafkaTopicLogCoordinator coordinator = new KafkaTopicLogCoordinator();

        KafkaProduceRecord first = coordinator.append("orders.events", 0, "k1", "{\"id\":1}", Map.of("h", "1"), 1000L);
        KafkaProduceRecord second = coordinator.append("orders.events", 0, "k2", "{\"id\":2}", Map.of("h", "2"), 2000L);

        assertThat(first.offset()).isEqualTo(0L);
        assertThat(first.recordId()).isEqualTo("orders.events:0:0");
        assertThat(second.offset()).isEqualTo(1L);
        assertThat(second.recordId()).isEqualTo("orders.events:0:1");

        KafkaTopicLogCoordinator.FetchSlice all = coordinator.fetch("orders.events", 0, 0L);
        assertThat(all.records()).containsExactly(first, second);
        assertThat(all.logStartOffset()).isEqualTo(0L);
        assertThat(all.highWatermark()).isEqualTo(2L);

        KafkaTopicLogCoordinator.FetchSlice fromOne = coordinator.fetch("orders.events", 0, 1L);
        assertThat(fromOne.records()).containsExactly(second);
        assertThat(fromOne.highWatermark()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Should stage feed records with monotonic offsets even when callers pass default offset 0")
    void shouldStageFeedRecordsWithMonotonicOffsets() {
        KafkaTopicLogCoordinator coordinator = new KafkaTopicLogCoordinator();

        KafkaProduceRecord s1 = coordinator.stage(KafkaProduceRecord.of("feed.events", "k1", "v1"));
        KafkaProduceRecord s2 = coordinator.stage(KafkaProduceRecord.of("feed.events", "k2", "v2"));
        KafkaProduceRecord s3 =
                coordinator.stage(new KafkaProduceRecord("feed.events", 0, "k3", "v3", Map.of(), 5L, 5000L));
        KafkaProduceRecord s4 = coordinator.stage(KafkaProduceRecord.of("feed.events", "k4", "v4"));

        assertThat(s1.offset()).isEqualTo(0L);
        assertThat(s2.offset()).isEqualTo(1L);
        assertThat(s3.offset()).isEqualTo(5L);
        assertThat(s4.offset()).isEqualTo(6L);
        assertThat(coordinator.getAllRecords()).containsExactly(s1, s2, s3, s4);
    }

    @Test
    @DisplayName(
            "Should delete by stable recordId, offset, or key and purge topics without regressing monotonic offsets")
    void shouldDeleteAndPurgeWithoutRegressingOffsets() {
        KafkaTopicLogCoordinator coordinator = new KafkaTopicLogCoordinator();

        KafkaProduceRecord r0 = coordinator.append("orders.events", 0, "k0", "v0", Map.of(), 1000L);
        KafkaProduceRecord r1 = coordinator.append("orders.events", 0, "k1", "v1", Map.of(), 2000L);
        KafkaProduceRecord r2 = coordinator.append("orders.events", 0, "k2", "v2", Map.of(), 3000L);
        coordinator.append("other.events", 0, "o0", "ov0", Map.of(), 4000L);

        assertThat(coordinator.deleteRecord("orders.events", r0.recordId())).isTrue();
        assertThat(coordinator.deleteRecord("orders.events", "nonexistent")).isFalse();

        KafkaTopicLogCoordinator.FetchSlice afterDeleteFirst = coordinator.fetch("orders.events", 0, 0L);
        assertThat(afterDeleteFirst.records()).containsExactly(r1, r2);
        assertThat(afterDeleteFirst.logStartOffset()).isEqualTo(1L);
        assertThat(afterDeleteFirst.highWatermark()).isEqualTo(3L);

        assertThat(coordinator.deleteRecord("orders.events", "k1")).isTrue();
        assertThat(coordinator.fetch("orders.events", 0, 0L).records()).containsExactly(r2);

        coordinator.purgeTopic("orders.events");
        KafkaTopicLogCoordinator.FetchSlice afterPurge = coordinator.fetch("orders.events", 0, 0L);
        assertThat(afterPurge.records()).isEmpty();
        assertThat(afterPurge.logStartOffset()).isEqualTo(3L);
        assertThat(afterPurge.highWatermark()).isEqualTo(3L);

        // Next record after purge continues monotonically at offset 3
        KafkaProduceRecord r3 = coordinator.append("orders.events", 0, "k3", "v3", Map.of(), 5000L);
        assertThat(r3.offset()).isEqualTo(3L);
        // Other topic unaffected
        assertThat(coordinator.fetch("other.events", 0, 0L).records()).hasSize(1);
    }

    @Test
    @DisplayName("Should isolate committed offsets per (groupId, topic, partition) and return -1 when uncommitted")
    void shouldIsolateCommittedOffsetsPerGroupTopicPartition() {
        KafkaTopicLogCoordinator coordinator = new KafkaTopicLogCoordinator();

        assertThat(coordinator.fetchCommittedOffset("group-a", "orders.events", 0))
                .isEqualTo(KafkaTopicLogCoordinator.NO_COMMITTED_OFFSET);

        coordinator.commitOffset("group-a", "orders.events", 0, 4L);
        coordinator.commitOffset("group-a", "orders.events", 1, 7L);
        coordinator.commitOffset("group-b", "orders.events", 0, 1L);
        coordinator.commitOffset("group-a", "payments.events", 0, 2L);

        assertThat(coordinator.fetchCommittedOffset("group-a", "orders.events", 0))
                .isEqualTo(4L);
        assertThat(coordinator.fetchCommittedOffset("group-a", "orders.events", 1))
                .isEqualTo(7L);
        assertThat(coordinator.fetchCommittedOffset("group-b", "orders.events", 0))
                .isEqualTo(1L);
        assertThat(coordinator.fetchCommittedOffset("group-b", "orders.events", 1))
                .isEqualTo(KafkaTopicLogCoordinator.NO_COMMITTED_OFFSET);
        assertThat(coordinator.fetchCommittedOffset("group-a", "payments.events", 0))
                .isEqualTo(2L);

        assertThat(coordinator.fetchAllCommittedOffsets("group-b"))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(new KafkaTopicLogCoordinator.TopicPartitionKey("orders.events", 0), 1L));
    }

    @Test
    @DisplayName("Should honor ListOffsets earliest (-2), latest (-1), and explicit timestamps")
    void shouldHonorListOffsetsEarliestLatestAndTimestamp() {
        KafkaTopicLogCoordinator coordinator = new KafkaTopicLogCoordinator();

        // Empty topic: both earliest and latest are 0
        assertThat(coordinator.listOffset("orders.events", 0, KafkaTopicLogCoordinator.EARLIEST_TIMESTAMP))
                .isEqualTo(0L);
        assertThat(coordinator.listOffset("orders.events", 0, KafkaTopicLogCoordinator.LATEST_TIMESTAMP))
                .isEqualTo(0L);

        KafkaProduceRecord r0 = coordinator.append("orders.events", 0, "k0", "v0", Map.of(), 1000L);
        coordinator.append("orders.events", 0, "k1", "v1", Map.of(), 2000L);
        coordinator.append("orders.events", 0, "k2", "v2", Map.of(), 3000L);

        assertThat(coordinator.listOffset("orders.events", 0, KafkaTopicLogCoordinator.EARLIEST_TIMESTAMP))
                .isEqualTo(0L);
        assertThat(coordinator.listOffset("orders.events", 0, KafkaTopicLogCoordinator.LATEST_TIMESTAMP))
                .isEqualTo(3L);
        assertThat(coordinator.listOffset("orders.events", 0, 1500L)).isEqualTo(1L);
        assertThat(coordinator.listOffset("orders.events", 0, 2000L)).isEqualTo(1L);
        assertThat(coordinator.listOffset("orders.events", 0, 9999L)).isEqualTo(3L);

        // Deleting offset 0 advances earliest to 1 while latest remains 3
        coordinator.deleteRecord("orders.events", r0.recordId());
        assertThat(coordinator.listOffset("orders.events", 0, KafkaTopicLogCoordinator.EARLIEST_TIMESTAMP))
                .isEqualTo(1L);
        assertThat(coordinator.listOffset("orders.events", 0, KafkaTopicLogCoordinator.LATEST_TIMESTAMP))
                .isEqualTo(3L);
    }
}
