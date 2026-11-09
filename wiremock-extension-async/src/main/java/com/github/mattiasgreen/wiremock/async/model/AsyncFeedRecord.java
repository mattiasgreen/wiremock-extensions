package com.github.mattiasgreen.wiremock.async.model;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Record within an asynchronous consumer feed stub (metadata.async.feed.records).
 */
public record AsyncFeedRecord(String key, String payload, Map<String, String> headers) {

    public AsyncFeedRecord {
        key = Objects.requireNonNullElse(key, "");
        payload = Objects.requireNonNullElse(payload, "");
        headers = headers != null ? Map.copyOf(headers) : Collections.emptyMap();
    }

    public static AsyncFeedRecord fromMap(Map<String, Object> map) {
        if (map == null) {
            return new AsyncFeedRecord("", "", Collections.emptyMap());
        }
        String key = (String) map.getOrDefault("key", "");
        String payload = (String) map.getOrDefault("payload", map.get("body"));
        if (payload == null && map.containsKey("jsonBody")) {
            Object jsonBody = map.get("jsonBody");
            payload = jsonBody != null ? jsonBody.toString() : "";
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
        return new AsyncFeedRecord(key, payload, headers);
    }
}
