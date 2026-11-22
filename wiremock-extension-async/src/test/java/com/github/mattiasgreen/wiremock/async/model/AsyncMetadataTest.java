package com.github.mattiasgreen.wiremock.async.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.common.Metadata;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AsyncMetadataTest {

    @Test
    @DisplayName("Should parse complete async metadata block with produce directives")
    void shouldParseCompleteAsyncMetadata() {
        Metadata wiremockMetadata = new Metadata(Map.of(
                "async",
                Map.of(
                        "protocol", "kafka",
                        "broker", "kafka://localhost:9092",
                        "channel", "orders.events",
                        "ackPolicy", "ack",
                        "mode", "mock",
                        "produce",
                                List.of(Map.of(
                                        "destination", "orders.replies",
                                        "protocol", "kafka",
                                        "key", "order-123",
                                        "payload", "{\"status\":\"CONFIRMED\"}",
                                        "delayMs", 50,
                                        "headers", Map.of("x-custom-trace", "tr-9988"))))));

        AsyncMetadata asyncMetadata = AsyncMetadata.from(wiremockMetadata).orElseThrow();

        assertThat(asyncMetadata.protocol()).isEqualTo("kafka");
        assertThat(asyncMetadata.broker()).isEqualTo("kafka://localhost:9092");
        assertThat(asyncMetadata.channel()).isEqualTo("orders.events");
        assertThat(asyncMetadata.ackPolicy()).isEqualTo(AckPolicy.ACK);
        assertThat(asyncMetadata.mode()).isEqualTo(AsyncMode.MOCK);
        assertThat(asyncMetadata.produce()).hasSize(1);

        AsyncProduceDirective directive = asyncMetadata.produce().get(0);
        assertThat(directive.destination()).isEqualTo("orders.replies");
        assertThat(directive.protocol()).isEqualTo("kafka");
        assertThat(directive.key()).isEqualTo("order-123");
        assertThat(directive.payload()).isEqualTo("{\"status\":\"CONFIRMED\"}");
        assertThat(directive.delayMs()).isEqualTo(50L);
        assertThat(directive.headers()).containsEntry("x-custom-trace", "tr-9988");
    }

    @Test
    @DisplayName("Should return empty optional when metadata is null or lacks async block")
    void shouldReturnEmptyWhenNoAsyncBlock() {
        assertThat(AsyncMetadata.from(null)).isEmpty();
        assertThat(AsyncMetadata.from(new Metadata(Map.of("project", "billing"))))
                .isEmpty();
    }

    @Test
    @DisplayName("Should support single produce map object as well as produce list")
    void shouldSupportSingleProduceObject() {
        Metadata wiremockMetadata = new Metadata(Map.of(
                "async",
                Map.of(
                        "produce",
                        Map.of(
                                "destination", "payments.events",
                                "protocol", "amqp",
                                "payload", "{\"paid\":true}"))));

        AsyncMetadata asyncMetadata = AsyncMetadata.from(wiremockMetadata).orElseThrow();
        assertThat(asyncMetadata.produce()).hasSize(1);
        assertThat(asyncMetadata.produce().get(0).destination()).isEqualTo("payments.events");
        assertThat(asyncMetadata.produce().get(0).protocol()).isEqualTo("amqp");
    }

    @Test
    @DisplayName("Should serialize object jsonBody as valid JSON in produce directives and feed records")
    void shouldSerializeObjectJsonBodyAsValidJson() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        Metadata wiremockMetadata = new Metadata(Map.of(
                "async",
                Map.of(
                        "produce",
                        List.of(Map.of(
                                "destination", "orders.created",
                                "protocol", "kafka",
                                "jsonBody", Map.of("orderId", "ORD-42", "amount", 99, "paid", true))),
                        "feed",
                        Map.of(
                                "protocol",
                                "kafka",
                                "destination",
                                "orders.feed",
                                "records",
                                List.of(Map.of(
                                        "key",
                                        "k-1",
                                        "jsonBody",
                                        Map.of("event", "CREATED", "items", List.of("A", "B"))))))));

        AsyncMetadata asyncMetadata = AsyncMetadata.from(wiremockMetadata).orElseThrow();

        String producePayload = asyncMetadata.produce().get(0).payload();
        var produceJson = mapper.readTree(producePayload);
        assertThat(produceJson.get("orderId").asText()).isEqualTo("ORD-42");
        assertThat(produceJson.get("amount").asInt()).isEqualTo(99);
        assertThat(produceJson.get("paid").asBoolean()).isTrue();

        String feedPayload = asyncMetadata.feed().records().get(0).payload();
        var feedJson = mapper.readTree(feedPayload);
        assertThat(feedJson.get("event").asText()).isEqualTo("CREATED");
        assertThat(feedJson.get("items").get(0).asText()).isEqualTo("A");
        assertThat(feedJson.get("items").get(1).asText()).isEqualTo("B");
    }
}
