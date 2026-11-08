package com.github.mattiasgreen.wiremock.async.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Encapsulates an outbound message produce instruction triggered by an incoming HTTP or async event.
 *
 * <p><b>Note:</b> This is Beta functionality subject to evolution.
 *
 * @param destination target topic, queue, or exchange name
 * @param protocol target protocol transport (e.g. "kafka", "amqp", "casual")
 * @param key message key or partition key template
 * @param payload message payload template
 * @param delayMs optional artificial delivery latency in milliseconds
 * @param headers wire properties and user-defined headers
 */
public record AsyncProduceDirective(
        String destination, String protocol, String key, String payload, long delayMs, Map<String, String> headers) {

    public AsyncProduceDirective {
        destination = Objects.requireNonNullElse(destination, "");
        protocol = Objects.requireNonNullElse(protocol, "kafka");
        key = Objects.requireNonNullElse(key, "");
        payload = Objects.requireNonNullElse(payload, "");
        headers = headers != null ? Map.copyOf(headers) : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    public static AsyncProduceDirective fromMap(Map<String, Object> map) {
        if (map == null) {
            return new AsyncProduceDirective("", "kafka", "", "", 0L, Map.of());
        }

        String destination = (String) map.getOrDefault("destination", map.get("channel"));
        String protocol = (String) map.getOrDefault("protocol", "kafka");
        String key = (String) map.getOrDefault("key", "");
        String payload = (String) map.getOrDefault("payload", map.get("body"));
        if (payload == null && map.containsKey("jsonBody")) {
            Object jsonBody = map.get("jsonBody");
            payload = jsonBody != null ? jsonBody.toString() : "";
        }

        long delayMs = 0L;
        Object delayVal = map.getOrDefault("delayMs", map.get("delayMilliseconds"));
        if (delayVal instanceof Number n) {
            delayMs = n.longValue();
        }

        Map<String, String> headers = Collections.emptyMap();
        Object headersVal = map.get("headers");
        if (headersVal instanceof Map<?, ?> m) {
            headers = new java.util.HashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    headers.put(e.getKey().toString(), e.getValue().toString());
                }
            }
        }

        return new AsyncProduceDirective(destination, protocol, key, payload, delayMs, headers);
    }
}
