package com.github.mattiasgreen.wiremock.async.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
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
}
