package com.github.mattiasgreen.wiremock.async.destination;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable descriptor of an asynchronous queue, topic, or staged channel.
 */
public record AsyncDestination(
        String id, String protocol, String name, String brokerId, int messageCount, Map<String, Object> metadata) {

    public AsyncDestination {
        Objects.requireNonNull(name, "name must not be null");
        protocol = Objects.requireNonNullElse(protocol, "in-memory").toLowerCase(Locale.ROOT);
        id = Objects.requireNonNullElse(id, protocol + ":" + name);
        brokerId = Objects.requireNonNullElse(brokerId, "local");
        metadata = metadata != null ? Collections.unmodifiableMap(metadata) : Collections.emptyMap();
    }
}
