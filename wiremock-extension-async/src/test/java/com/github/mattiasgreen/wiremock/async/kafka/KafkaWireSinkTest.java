package com.github.mattiasgreen.wiremock.async.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KafkaWireSinkTest {

    private KafkaWireSink sink;
    private final List<KafkaProduceRecord> received = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() throws Exception {
        received.clear();
        sink = new KafkaWireSink(0, received::add);
        sink.start();
    }

    @AfterEach
    void tearDown() {
        if (sink != null) {
            sink.close();
        }
    }

    @Test
    @DisplayName(
            "Should negotiate ApiVersions, Metadata, InitProducerId, and ingest Produce records with real KafkaProducer")
    void shouldIngestRecordsFromRealKafkaProducer() throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:" + sink.getPort());
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "wiremock-async-test-producer");
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000");
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            ProducerRecord<String, String> record1 =
                    new ProducerRecord<>("orders.events", "order-101", "{\"orderId\":\"101\",\"amount\":49.95}");
            ProducerRecord<String, String> record2 =
                    new ProducerRecord<>("orders.events", "order-102", "{\"orderId\":\"102\",\"amount\":89.50}");

            Future<RecordMetadata> future1 = producer.send(record1);
            Future<RecordMetadata> future2 = producer.send(record2);

            RecordMetadata meta1 = future1.get(5, TimeUnit.SECONDS);
            RecordMetadata meta2 = future2.get(5, TimeUnit.SECONDS);

            assertThat(meta1.topic()).isEqualTo("orders.events");
            assertThat(meta1.offset()).isEqualTo(0L);

            assertThat(meta2.topic()).isEqualTo("orders.events");
            assertThat(meta2.offset()).isEqualTo(1L);

            // Verify sink received both records
            assertThat(received).hasSize(2);
            assertThat(received.get(0).topic()).isEqualTo("orders.events");
            assertThat(received.get(0).key()).isEqualTo("order-101");
            assertThat(received.get(0).value()).isEqualTo("{\"orderId\":\"101\",\"amount\":49.95}");

            assertThat(received.get(1).key()).isEqualTo("order-102");
            assertThat(received.get(1).value()).isEqualTo("{\"orderId\":\"102\",\"amount\":89.50}");
        }
    }
}
