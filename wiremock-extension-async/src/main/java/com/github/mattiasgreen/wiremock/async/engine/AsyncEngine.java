package com.github.mattiasgreen.wiremock.async.engine;

import com.github.mattiasgreen.wiremock.async.kafka.KafkaWireSink;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * In-memory coordination engine for asynchronous messaging, tracking sink lifecycles and message counters.
 *
 * <p><b>Note:</b> This is Beta functionality subject to evolution.
 */
public class AsyncEngine implements AutoCloseable {

    private static final AsyncEngine INSTANCE = new AsyncEngine();

    private final AtomicLong messagesConsumed = new AtomicLong(0);
    private final AtomicLong messagesProduced = new AtomicLong(0);
    private final AtomicLong messagesProxied = new AtomicLong(0);
    private final Map<String, BiConsumer<String, AsyncMessage>> destinationPublishers = new ConcurrentHashMap<>();

    private KafkaWireSink kafkaSink;

    private AsyncEngine() {}

    public static AsyncEngine getInstance() {
        return INSTANCE;
    }

    public synchronized void startKafkaSink(
            int port,
            java.util.function.Consumer<com.github.mattiasgreen.wiremock.async.kafka.KafkaProduceRecord> recordConsumer)
            throws InterruptedException {
        if (kafkaSink != null && kafkaSink.isRunning()) {
            kafkaSink.close();
        }
        kafkaSink = new KafkaWireSink(port, record -> {
            messagesConsumed.incrementAndGet();
            if (recordConsumer != null) {
                recordConsumer.accept(record);
            }
        });
        kafkaSink.start();
    }

    public synchronized void stopKafkaSink() {
        if (kafkaSink != null) {
            kafkaSink.close();
            kafkaSink = null;
        }
    }

    public synchronized boolean isKafkaSinkRunning() {
        return kafkaSink != null && kafkaSink.isRunning();
    }

    public synchronized int getKafkaSinkPort() {
        return kafkaSink != null ? kafkaSink.getPort() : -1;
    }

    public void registerPublisher(String destination, BiConsumer<String, AsyncMessage> publisher) {
        destinationPublishers.put(destination, publisher);
    }

    public void publish(String destination, AsyncMessage message) {
        messagesProduced.incrementAndGet();
        BiConsumer<String, AsyncMessage> pub = destinationPublishers.get(destination);
        if (pub != null) {
            pub.accept(destination, message);
        }
    }

    public long getMessagesConsumed() {
        return messagesConsumed.get();
    }

    public long getMessagesProduced() {
        return messagesProduced.get();
    }

    public long getMessagesProxied() {
        return messagesProxied.get();
    }

    public void incrementConsumed() {
        messagesConsumed.incrementAndGet();
    }

    public void incrementProduced() {
        messagesProduced.incrementAndGet();
    }

    public void incrementProxied() {
        messagesProxied.incrementAndGet();
    }

    public void reset() {
        messagesConsumed.set(0);
        messagesProduced.set(0);
        messagesProxied.set(0);
        destinationPublishers.clear();
        stopKafkaSink();
    }

    @Override
    public synchronized void close() {
        reset();
    }
}
