package com.github.mattiasgreen.wiremock.async.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KafkaConsumerCoordinatorTest {

    private KafkaWireSink sink;

    @BeforeEach
    void setUp() throws InterruptedException {
        sink = new KafkaWireSink(0, null);
        sink.start();
    }

    @AfterEach
    void tearDown() {
        if (sink != null) {
            sink.close();
        }
    }

    @Test
    @DisplayName("Should coordinate standard KafkaConsumer join, sync, poll, and commitSync loop")
    void shouldCoordinateKafkaConsumerLoop() {
        String topic = "orders.events";

        // Pre-stage mock records
        sink.stageRecord(new KafkaProduceRecord(
                topic,
                0,
                "ord-1",
                "{\"orderId\":\"ORD-1\",\"total\":100}",
                Map.of("x-source", "web"),
                0L,
                System.currentTimeMillis()));
        sink.stageRecord(new KafkaProduceRecord(
                topic,
                0,
                "ord-2",
                "{\"orderId\":\"ORD-2\",\"total\":200}",
                Map.of("x-source", "api"),
                1L,
                System.currentTimeMillis()));

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:" + sink.getPort());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-group");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "6000");
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "2000");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(topic));

            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));
            assertThat(records).isNotNull();
            assertThat(records.count()).isGreaterThanOrEqualTo(2);

            ConsumerRecord<String, String> r1 = records.iterator().next();
            assertThat(r1.key()).isEqualTo("ord-1");
            assertThat(r1.value()).contains("ORD-1");

            // Commit offsets synchronously to verify offset commit handling
            consumer.commitSync();
        }
    }

    @Test
    @DisplayName(
            "Should make records produced on one TCP connection immediately visible to a real KafkaConsumer on another connection")
    void shouldShareProducedRecordsAcrossSeparateTcpConnections() throws Exception {
        String topic = "cross.socket.orders";

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps())) {
            producer.send(new ProducerRecord<>(topic, "k-1", "{\"id\":1}")).get(5, TimeUnit.SECONDS);
            producer.send(new ProducerRecord<>(topic, "k-2", "{\"id\":2}")).get(5, TimeUnit.SECONDS);
        }

        // Visible to Explorer/peek API immediately
        assertThat(sink.getStagedRecords())
                .extracting(KafkaProduceRecord::recordId)
                .containsExactly(topic + ":0:0", topic + ":0:1");

        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(consumerProps("cross-socket-group", "earliest"))) {
            consumer.subscribe(Collections.singletonList(topic));

            List<ConsumerRecord<String, String>> polled = pollUntilCount(consumer, 2, Duration.ofSeconds(5));
            assertThat(polled).hasSize(2);
            assertThat(polled.get(0).key()).isEqualTo("k-1");
            assertThat(polled.get(0).offset()).isEqualTo(0L);
            assertThat(polled.get(1).key()).isEqualTo("k-2");
            assertThat(polled.get(1).offset()).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("Should isolate committed offsets across consumer groups and honor earliest vs latest ListOffsets")
    void shouldIsolateConsumerGroupOffsetsAndHonorEarliestVsLatest() throws Exception {
        String topic = "group.isolation.orders";

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps())) {
            producer.send(new ProducerRecord<>(topic, "k-0", "v-0")).get(5, TimeUnit.SECONDS);
            producer.send(new ProducerRecord<>(topic, "k-1", "v-1")).get(5, TimeUnit.SECONDS);
        }

        // Group A consumes and commits offset 2
        try (KafkaConsumer<String, String> consumerA = new KafkaConsumer<>(consumerProps("group-a", "earliest"))) {
            consumerA.subscribe(Collections.singletonList(topic));
            List<ConsumerRecord<String, String>> recordsA = pollUntilCount(consumerA, 2, Duration.ofSeconds(5));
            assertThat(recordsA).hasSize(2);
            consumerA.commitSync();
        }

        // Rejoining Group A sees no old records (starts from committed offset 2)
        try (KafkaConsumer<String, String> consumerA2 = new KafkaConsumer<>(consumerProps("group-a", "earliest"))) {
            consumerA2.subscribe(Collections.singletonList(topic));
            ConsumerRecords<String, String> recordsA2 = consumerA2.poll(Duration.ofMillis(400));
            assertThat(recordsA2.count()).isZero();
        }

        // Group B is isolated from Group A and receives both historical records
        try (KafkaConsumer<String, String> consumerB = new KafkaConsumer<>(consumerProps("group-b", "earliest"))) {
            consumerB.subscribe(Collections.singletonList(topic));
            List<ConsumerRecord<String, String>> recordsB = pollUntilCount(consumerB, 2, Duration.ofSeconds(5));
            assertThat(recordsB).extracting(ConsumerRecord::key).containsExactly("k-0", "k-1");
        }

        // Group Latest uses auto.offset.reset=latest (ListOffsets -1) and only sees newly produced records
        try (KafkaConsumer<String, String> consumerLatest =
                new KafkaConsumer<>(consumerProps("group-latest", "latest"))) {
            consumerLatest.subscribe(Collections.singletonList(topic));
            // Complete join/sync/ListOffsets(-1) before producing
            assertThat(consumerLatest.poll(Duration.ofMillis(500)).count()).isZero();

            try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps())) {
                producer.send(new ProducerRecord<>(topic, "k-2", "v-2")).get(5, TimeUnit.SECONDS);
            }

            List<ConsumerRecord<String, String>> latestRecords =
                    pollUntilCount(consumerLatest, 1, Duration.ofSeconds(5));
            assertThat(latestRecords).hasSize(1);
            assertThat(latestRecords.get(0).key()).isEqualTo("k-2");
            assertThat(latestRecords.get(0).offset()).isEqualTo(2L);
        }
    }

    @Test
    @DisplayName("Should immediately reflect delete and purge mutations in live KafkaConsumer fetches")
    void shouldReflectDeleteAndPurgeInLiveConsumers() throws Exception {
        String topic = "delete.purge.orders";

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps())) {
            producer.send(new ProducerRecord<>(topic, "k-0", "v-0")).get(5, TimeUnit.SECONDS);
            producer.send(new ProducerRecord<>(topic, "k-1", "v-1")).get(5, TimeUnit.SECONDS);
            producer.send(new ProducerRecord<>(topic, "k-2", "v-2")).get(5, TimeUnit.SECONDS);
        }

        // Delete middle record by stable recordId
        assertThat(sink.deleteStagedRecord(topic, topic + ":0:1")).isTrue();

        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(consumerProps("delete-check-group", "earliest"))) {
            consumer.subscribe(Collections.singletonList(topic));
            List<ConsumerRecord<String, String>> polled = pollUntilCount(consumer, 2, Duration.ofSeconds(5));
            assertThat(polled).extracting(ConsumerRecord::key).containsExactly("k-0", "k-2");
            assertThat(polled).extracting(ConsumerRecord::offset).containsExactly(0L, 2L);
        }

        // Purge topic and verify new consumer sees empty log, then sees next monotonic offset 3
        sink.purgeTopic(topic);
        assertThat(sink.getStagedRecords()).isEmpty();

        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(consumerProps("purge-check-group", "earliest"))) {
            consumer.subscribe(Collections.singletonList(topic));
            assertThat(consumer.poll(Duration.ofMillis(400)).count()).isZero();

            try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps())) {
                producer.send(new ProducerRecord<>(topic, "k-3", "v-3")).get(5, TimeUnit.SECONDS);
            }

            List<ConsumerRecord<String, String>> polled = pollUntilCount(consumer, 1, Duration.ofSeconds(5));
            assertThat(polled).hasSize(1);
            assertThat(polled.get(0).key()).isEqualTo("k-3");
            assertThat(polled.get(0).offset()).isEqualTo(3L);
        }
    }

    private Properties producerProps() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:" + sink.getPort());
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "wiremock-async-test-producer");
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000");
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        return props;
    }

    private Properties consumerProps(String groupId, String autoOffsetReset) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:" + sink.getPort());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "6000");
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "2000");
        return props;
    }

    private static List<ConsumerRecord<String, String>> pollUntilCount(
            KafkaConsumer<String, String> consumer, int expectedCount, Duration timeout) {
        List<ConsumerRecord<String, String>> collected = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (collected.size() < expectedCount && System.nanoTime() < deadline) {
            ConsumerRecords<String, String> batch = consumer.poll(Duration.ofMillis(200));
            for (ConsumerRecord<String, String> r : batch) {
                collected.add(r);
            }
        }
        return collected;
    }
}
