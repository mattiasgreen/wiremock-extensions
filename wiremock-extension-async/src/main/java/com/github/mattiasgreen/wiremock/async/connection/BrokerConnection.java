package com.github.mattiasgreen.wiremock.async.connection;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable descriptor of an asynchronous broker cluster or gateway endpoint.
 *
 * @param id unique connection identifier (e.g. {@code "corp-kafka"})
 * @param protocol messaging protocol: {@code "kafka"}, {@code "amqp"}, or {@code "casual"}
 * @param name human-friendly display label
 * @param endpoint network coordinate (e.g. {@code "localhost:9092"} or {@code "amqp://host:5672"})
 * @param properties protocol-specific options, authentication details, and TLS settings
 */
public record BrokerConnection(
        String id, String protocol, String name, String endpoint, Map<String, String> properties) {

    public BrokerConnection {
        Objects.requireNonNull(id, "id must not be null");
        if (id.trim().isEmpty()) {
            throw new IllegalArgumentException("Connection ID must not be blank");
        }
        protocol = Objects.requireNonNullElse(protocol, "kafka").toLowerCase(Locale.ROOT);
        name = Objects.requireNonNullElse(name, id);
        endpoint = Objects.requireNonNullElse(endpoint, "");
        properties = properties != null ? Collections.unmodifiableMap(properties) : Collections.emptyMap();
    }
}
