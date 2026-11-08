package com.github.mattiasgreen.wiremock.async.bridge;

import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory message broker implementation for zero-daemon testing and local virtual-host simulation.
 *
 * <p><b>Note:</b> This is Beta functionality subject to evolution.
 */
public class InMemoryMessageBroker implements MessageBroker {

    private final String name;
    private final Map<String, Deque<AsyncMessage>> queues = new ConcurrentHashMap<>();

    public InMemoryMessageBroker(String name) {
        this.name = Objects.requireNonNullElse(name, "in-memory-broker");
    }

    public String getName() {
        return name;
    }

    @Override
    public synchronized void send(String channel, AsyncMessage message) {
        if (channel == null || message == null) return;
        queues.computeIfAbsent(channel, k -> new ArrayDeque<>()).add(message);
    }

    @Override
    public synchronized AsyncMessage poll(String channel) {
        if (channel == null) return null;
        Deque<AsyncMessage> queue = queues.get(channel);
        return (queue != null && !queue.isEmpty()) ? queue.poll() : null;
    }

    @Override
    public synchronized int size(String channel) {
        if (channel == null) return 0;
        Deque<AsyncMessage> queue = queues.get(channel);
        return queue != null ? queue.size() : 0;
    }
}
