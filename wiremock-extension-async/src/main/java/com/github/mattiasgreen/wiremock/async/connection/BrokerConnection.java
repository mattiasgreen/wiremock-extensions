package com.github.mattiasgreen.wiremock.async.connection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable descriptor of an asynchronous broker cluster or gateway endpoint.
 * Supports ${VAR_NAME} environment variable tokens resolved at connection time.
 *
 * @param id unique connection identifier (e.g. {@code "corp-kafka"})
 * @param protocol messaging protocol: {@code "kafka"}, {@code "amqp"}, or {@code "casual"}
 * @param name human-friendly display label
 * @param endpoint network coordinate (e.g. {@code "localhost:9092"} or {@code "amqp://host:5672"})
 * @param properties protocol-specific options, authentication details, and TLS settings
 */
public record BrokerConnection(
        String id, String protocol, String name, String endpoint, Map<String, String> properties) {

    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([A-Za-z0-9_]+)\\}");

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

    /**
     * Replaces ${VAR_NAME} placeholders with values from System.getenv().
     */
    public static String expandEnvVars(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = ENV_VAR_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String val = System.getenv(varName);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(val != null ? val : ""));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Returns a copy of this connection descriptor with all ${VAR_NAME} tokens
     * resolved against System.getenv().
     */
    public BrokerConnection resolveWithEnv() {
        String resolvedEndpoint = expandEnvVars(endpoint);
        Map<String, String> resolvedProps = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            resolvedProps.put(entry.getKey(), expandEnvVars(entry.getValue()));
        }
        return new BrokerConnection(id, protocol, name, resolvedEndpoint, resolvedProps);
    }
}
