package com.github.mattiasgreen.wiremock.async.model;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Universal canonical representation of an asynchronous message across transports (Kafka, AMQP, Casual, IBM MQ).
 *
 * <p><b>Note:</b> This is Beta functionality subject to evolution.
 *
 * @param id unique message identifier
 * @param correlationId correlation identifier for Request-Reply MEP
 * @param replyTo destination channel for response message
 * @param destination target topic or queue name
 * @param protocol transport protocol identifier
 * @param key message key or routing key
 * @param payload string payload or serialized JSON/XML/text
 * @param headers wire properties and user headers
 * @param timestamp message creation or arrival timestamp
 * @param size payload size in bytes
 * @param partition optional Kafka partition number
 * @param offset optional Kafka record offset
 */
public record AsyncMessage(
        String id,
        String correlationId,
        String replyTo,
        String destination,
        String protocol,
        String key,
        String payload,
        Map<String, String> headers,
        Instant timestamp,
        long size,
        Integer partition,
        Long offset) {

    public AsyncMessage {
        id = id != null && !id.isBlank() ? id : UUID.randomUUID().toString();
        correlationId = Objects.requireNonNullElse(correlationId, "");
        replyTo = Objects.requireNonNullElse(replyTo, "");
        destination = Objects.requireNonNullElse(destination, "");
        protocol = Objects.requireNonNullElse(protocol, "async");
        key = Objects.requireNonNullElse(key, "");
        payload = Objects.requireNonNullElse(payload, "");
        headers = headers != null ? Map.copyOf(headers) : Collections.emptyMap();
        timestamp = Objects.requireNonNullElseGet(timestamp, Instant::now);
        size = size > 0 ? size : payload.getBytes(StandardCharsets.UTF_8).length;
    }

    public AsyncMessage(
            String id,
            String correlationId,
            String replyTo,
            String destination,
            String protocol,
            String key,
            String payload,
            Map<String, String> headers,
            Instant timestamp) {
        this(id, correlationId, replyTo, destination, protocol, key, payload, headers, timestamp, 0L, null, null);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String id;
        private String correlationId;
        private String replyTo;
        private String destination;
        private String protocol = "async";
        private String key;
        private String payload;
        private Map<String, String> headers;
        private Instant timestamp;
        private long size;
        private Integer partition;
        private Long offset;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
            return this;
        }

        public Builder replyTo(String replyTo) {
            this.replyTo = replyTo;
            return this;
        }

        public Builder destination(String destination) {
            this.destination = destination;
            return this;
        }

        public Builder protocol(String protocol) {
            this.protocol = protocol;
            return this;
        }

        public Builder key(String key) {
            this.key = key;
            return this;
        }

        public Builder payload(String payload) {
            this.payload = payload;
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            this.headers = headers;
            return this;
        }

        public Builder timestamp(Instant timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder size(long size) {
            this.size = size;
            return this;
        }

        public Builder partition(Integer partition) {
            this.partition = partition;
            return this;
        }

        public Builder offset(Long offset) {
            this.offset = offset;
            return this;
        }

        public AsyncMessage build() {
            return new AsyncMessage(
                    id,
                    correlationId,
                    replyTo,
                    destination,
                    protocol,
                    key,
                    payload,
                    headers,
                    timestamp,
                    size,
                    partition,
                    offset);
        }
    }
}
