package com.github.mattiasgreen.wiremock.async.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Declarative consumer feed directive for staging mock records to be consumed by the SUT
 * (metadata.async.feed).
 */
public record AsyncFeedDirective(
        String destination, String protocol, String trigger, long delayMs, List<AsyncFeedRecord> records) {

    public AsyncFeedDirective {
        destination = Objects.requireNonNullElse(destination, "");
        protocol = Objects.requireNonNullElse(protocol, "kafka");
        trigger = Objects.requireNonNullElse(trigger, "on-subscribe");
        records = records != null ? List.copyOf(records) : Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    public static AsyncFeedDirective fromMap(Map<String, Object> map) {
        if (map == null) {
            return new AsyncFeedDirective("", "kafka", "on-subscribe", 0L, Collections.emptyList());
        }

        String destination = (String) map.getOrDefault("destination", map.get("channel"));
        String protocol = (String) map.getOrDefault("protocol", "kafka");
        String trigger = (String) map.getOrDefault("trigger", "on-subscribe");

        long delayMs = 0L;
        Object delayVal = map.getOrDefault("delayMs", map.get("delayMilliseconds"));
        if (delayVal instanceof Number n) {
            delayMs = n.longValue();
        }

        List<AsyncFeedRecord> recordsList = new ArrayList<>();
        Object recordsObj = map.get("records");
        if (recordsObj instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> itemMap) {
                    recordsList.add(AsyncFeedRecord.fromMap((Map<String, Object>) itemMap));
                }
            }
        }

        return new AsyncFeedDirective(destination, protocol, trigger, delayMs, recordsList);
    }
}
