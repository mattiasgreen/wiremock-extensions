package com.github.mattiasgreen.wiremock.async.kafka;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Encapsulates an individual Kafka record ingested by {@link KafkaWireSink}.
 *
 * <p><b>Note:</b> This is Beta functionality subject to evolution.
 *
 * @param topic destination topic name
 * @param partition target partition index
 * @param key message key string or identifier
 * @param value record payload string
 * @param headers record headers
 * @param offset monotonically increasing partition offset
 * @param timestamp epoch timestamp in milliseconds
 */
public record KafkaProduceRecord(
        String topic,
        int partition,
        String key,
        String value,
        Map<String, String> headers,
        long offset,
        long timestamp) {

    public KafkaProduceRecord {
        topic = Objects.requireNonNullElse(topic, "");
        key = Objects.requireNonNullElse(key, "");
        value = Objects.requireNonNullElse(value, "");
        headers = headers != null ? Map.copyOf(headers) : Collections.emptyMap();
    }
}
