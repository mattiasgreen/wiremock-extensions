package com.github.mattiasgreen.wiremock.async.destination;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Common representation of an unconsumed or inspected message across asynchronous protocols.
 */
public record AsyncDestinationMessage(
        String id,
        String protocol,
        String destination,
        String key,
        String correlationId,
        String replyTo,
        Instant timestamp,
        long size,
        Map<String, String> headers,
        String payload,
        Integer partition,
        Long offset) {

    public AsyncDestinationMessage {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(destination, "destination must not be null");
        protocol = Objects.requireNonNullElse(protocol, "async");
        key = Objects.requireNonNullElse(key, "");
        correlationId = Objects.requireNonNullElse(correlationId, "");
        replyTo = Objects.requireNonNullElse(replyTo, "");
        timestamp = timestamp != null ? timestamp : Instant.now();
        headers = headers != null ? Collections.unmodifiableMap(headers) : Collections.emptyMap();
        payload = Objects.requireNonNullElse(payload, "");
        size = size > 0 ? size : payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
}
