package com.github.mattiasgreen.wiremock.async.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KafkaMessageBrokerTest {

    private KafkaWireSink wireSink;
    private final List<KafkaProduceRecord> received = new CopyOnWriteArrayList<>();
    private KafkaMessageBroker broker;

    @BeforeEach
    void setUp() throws Exception {
        wireSink = new KafkaWireSink(0, received::add);
        wireSink.start();

        broker = new KafkaMessageBroker(
                "remote-kafka", "127.0.0.1:" + wireSink.getPort(), Map.of("request.timeout.ms", "4000"));
    }

    @AfterEach
    void tearDown() {
        if (broker != null) {
            broker.close();
        }
        if (wireSink != null) {
            wireSink.close();
        }
    }

    @Test
    @DisplayName("Should send message synchronously and await acknowledgment from target cluster")
    void shouldSendSynchronouslyAwaitingRemoteAck() {
        AsyncMessage msg = AsyncMessage.builder()
                .id("k-101")
                .key("order-99")
                .destination("orders.events")
                .payload("{\"orderId\":\"ORD-99\",\"status\":\"SUBMITTED\"}")
                .headers(Map.of("x-correlation-id", "corr-555"))
                .timestamp(Instant.now())
                .build();

        broker.send("orders.events", msg);

        assertThat(received).hasSize(1);
        KafkaProduceRecord record = received.get(0);
        assertThat(record.topic()).isEqualTo("orders.events");
        assertThat(record.key()).isEqualTo("order-99");
        assertThat(record.value()).isEqualTo("{\"orderId\":\"ORD-99\",\"status\":\"SUBMITTED\"}");
        assertThat(record.headers()).containsEntry("x-correlation-id", "corr-555");
    }
}
