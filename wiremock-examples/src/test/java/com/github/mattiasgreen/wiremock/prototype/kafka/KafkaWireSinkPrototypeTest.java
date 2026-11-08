package com.github.mattiasgreen.wiremock.prototype.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Live Verification Test for the In-Process Netty Kafka Sink Prototype.
 *
 * <p>Proves that a standard, unmodified Apache Kafka client (kafka-clients 3.9.0) can produce
 * records directly to our in-process Netty port without any real Kafka broker daemons.
 */
class KafkaWireSinkPrototypeTest {

    private KafkaWireSinkPrototype sink;
    private KafkaProducer<String, String> producer;

    @BeforeEach
    void setUp() throws Exception {
        sink = new KafkaWireSinkPrototype(0); // bind on ephemeral port
        sink.start();

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:" + sink.getPort());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000");
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");

        producer = new KafkaProducer<>(props);
    }

    @AfterEach
    void tearDown() {
        if (producer != null) {
            producer.close(Duration.ofSeconds(2));
        }
        if (sink != null) {
            sink.close();
        }
    }

    @Test
    void shouldSinkKafkaProducedRecordWithHeaders() throws Exception {
        // Given
        String topic = "orders.events";
        String key = "ORD-4491";
        String payload = "{\"orderId\":\"4491\",\"amount\":1299.00,\"currency\":\"SEK\"}";

        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, payload);
        record.headers().add(new RecordHeader("x-correlation-id", "corr-8831".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("x-event-type", "OrderCreated".getBytes(StandardCharsets.UTF_8)));

        // When: Standard KafkaProducer sends record to in-process Netty sink
        Future<RecordMetadata> future = producer.send(record);
        RecordMetadata metadata = future.get(5, TimeUnit.SECONDS);

        // Then: Client receives valid Kafka RecordMetadata with partition 0 and offset 0
        assertThat(metadata).isNotNull();
        assertThat(metadata.topic()).isEqualTo(topic);
        assertThat(metadata.partition()).isEqualTo(0);
        assertThat(metadata.offset()).isEqualTo(0L);

        // Then: In-process sink captured the record accurately
        assertThat(sink.getReceivedRecords()).hasSize(1);
        KafkaWireSinkPrototype.ReceivedRecord sunk = sink.getReceivedRecords().get(0);
        assertThat(sunk.topic()).isEqualTo(topic);
        assertThat(sunk.partition()).isEqualTo(0);
        assertThat(sunk.offset()).isEqualTo(0L);
        assertThat(sunk.key()).isEqualTo(key);
        assertThat(sunk.value()).isEqualTo(payload);
        assertThat(sunk.headers())
                .extracting(KafkaWireSinkPrototype.HeaderEntry::key)
                .containsExactlyInAnyOrder("x-correlation-id", "x-event-type");
    }

    @Test
    void shouldSinkMultipleRecordsWithMonotonicOffsetsAcrossTopics() throws Exception {
        // When: Multiple records are published across different topics
        Future<RecordMetadata> f1 = producer.send(new ProducerRecord<>("orders.events", "k1", "{\"order\":1}"));
        Future<RecordMetadata> f2 = producer.send(new ProducerRecord<>("orders.events", "k2", "{\"order\":2}"));
        Future<RecordMetadata> f3 = producer.send(new ProducerRecord<>("payments.events", "p1", "{\"payment\":1}"));

        RecordMetadata m1 = f1.get(5, TimeUnit.SECONDS);
        RecordMetadata m2 = f2.get(5, TimeUnit.SECONDS);
        RecordMetadata m3 = f3.get(5, TimeUnit.SECONDS);

        // Then: Client receives valid record metadata
        assertThat(m1.topic()).isEqualTo("orders.events");
        assertThat(m1.offset()).isEqualTo(0L);

        assertThat(m2.topic()).isEqualTo("orders.events");
        assertThat(m2.offset()).isEqualTo(1L);

        assertThat(m3.topic()).isEqualTo("payments.events");
        assertThat(m3.offset()).isGreaterThanOrEqualTo(0L);

        // Then: All 3 records captured in in-process sink memory
        assertThat(sink.getReceivedRecords()).hasSize(3);
        assertThat(sink.getReceivedRecords().get(0).value()).isEqualTo("{\"order\":1}");
        assertThat(sink.getReceivedRecords().get(1).value()).isEqualTo("{\"order\":2}");
        assertThat(sink.getReceivedRecords().get(2).value()).isEqualTo("{\"payment\":1}");
    }
}
