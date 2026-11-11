package com.github.mattiasgreen.wiremock.async.bridge;

import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
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

    @Override
    public synchronized List<String> listChannels() {
        return Collections.unmodifiableList(new ArrayList<>(queues.keySet()));
    }

    @Override
    public synchronized List<AsyncMessage> peek(String channel) {
        if (channel == null) return Collections.emptyList();
        Deque<AsyncMessage> queue = queues.get(channel);
        return queue != null ? Collections.unmodifiableList(new ArrayList<>(queue)) : Collections.emptyList();
    }

    @Override
    public synchronized boolean delete(String channel, String messageId) {
        if (channel == null || messageId == null) return false;
        Deque<AsyncMessage> queue = queues.get(channel);
        if (queue == null) return false;
        return queue.removeIf(m -> messageId.equals(m.id()));
    }

    @Override
    public synchronized void purge(String channel) {
        if (channel == null) return;
        Deque<AsyncMessage> queue = queues.get(channel);
        if (queue != null) {
            queue.clear();
        }
    }

    @Override
    public synchronized boolean update(String channel, String messageId, AsyncMessage updated) {
        if (channel == null || messageId == null || updated == null) return false;
        Deque<AsyncMessage> queue = queues.get(channel);
        if (queue == null) return false;
        List<AsyncMessage> list = new ArrayList<>(queue);
        boolean found = false;
        for (int i = 0; i < list.size(); i++) {
            if (messageId.equals(list.get(i).id())) {
                list.set(i, updated);
                found = true;
                break;
            }
        }
        if (found) {
            queue.clear();
            queue.addAll(list);
        }
        return found;
    }
}
