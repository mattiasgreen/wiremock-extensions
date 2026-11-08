package com.github.mattiasgreen.wiremock.async.model;

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
        Instant timestamp) {

    public AsyncMessage {
        id = id != null && !id.isBlank() ? id : UUID.randomUUID().toString();
        destination = Objects.requireNonNullElse(destination, "");
        protocol = Objects.requireNonNullElse(protocol, "async");
        payload = Objects.requireNonNullElse(payload, "");
        headers = headers != null ? Map.copyOf(headers) : Collections.emptyMap();
        timestamp = Objects.requireNonNullElseGet(timestamp, Instant::now);
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

        public AsyncMessage build() {
            return new AsyncMessage(
                    id, correlationId, replyTo, destination, protocol, key, payload, headers, timestamp);
        }
    }
}
