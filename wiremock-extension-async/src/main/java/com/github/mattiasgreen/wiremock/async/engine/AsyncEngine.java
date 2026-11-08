package com.github.mattiasgreen.wiremock.async.engine;

import com.github.mattiasgreen.wiremock.async.bridge.InMemoryMessageBroker;
import com.github.mattiasgreen.wiremock.async.bridge.MessageBroker;
import com.github.mattiasgreen.wiremock.async.kafka.KafkaProduceRecord;
import com.github.mattiasgreen.wiremock.async.kafka.KafkaWireSink;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Deep asynchronous messaging engine unifying in-process Kafka wire sinks,
 * queue bridging, mock stubbing, offline stub harvesting, and telemetry journaling.
 *
 * <p><b>Note:</b> This is Beta functionality subject to evolution.
 */
public class AsyncEngine implements AutoCloseable {

    private static final AsyncEngine INSTANCE = new AsyncEngine();

    private final AtomicLong messagesConsumed = new AtomicLong(0);
    private final AtomicLong messagesProduced = new AtomicLong(0);
    private final AtomicLong messagesProxied = new AtomicLong(0);

    private final Map<String, BiConsumer<String, AsyncMessage>> destinationPublishers = new ConcurrentHashMap<>();
    private final Map<String, Function<AsyncMessage, AsyncMessage>> mockStubs = new ConcurrentHashMap<>();
    private final Map<String, AsyncMessage> harvestedStubs = new ConcurrentHashMap<>();
    private final List<String> journal = Collections.synchronizedList(new ArrayList<>());

    private volatile MessageBroker testBroker;
    private volatile MessageBroker upstreamBroker;
    private KafkaWireSink kafkaSink;

    private AsyncEngine() {
        this.testBroker = new InMemoryMessageBroker("default-test-broker");
        this.upstreamBroker = new InMemoryMessageBroker("default-upstream-broker");
    }

    public static AsyncEngine getInstance() {
        return INSTANCE;
    }

    public static AsyncEngine create(MessageBroker testBroker, MessageBroker upstreamBroker) {
        AsyncEngine engine = new AsyncEngine();
        engine.setBrokers(testBroker, upstreamBroker);
        return engine;
    }

    public synchronized void startKafkaSink(int port, Consumer<KafkaProduceRecord> recordConsumer)
            throws InterruptedException {
        if (kafkaSink != null && kafkaSink.isRunning()) {
            kafkaSink.close();
        }
        kafkaSink = new KafkaWireSink(port, record -> {
            messagesConsumed.incrementAndGet();
            journal.add("KAFKA_CONSUME:" + record.topic() + ":" + record.value());
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
        if (destination != null && publisher != null) {
            destinationPublishers.put(destination, publisher);
        }
    }

    public void publish(String destination, AsyncMessage message) {
        if (destination == null || message == null) {
            return;
        }
        messagesProduced.incrementAndGet();
        journal.add("PUBLISH:" + destination + ":" + message.payload());

        BiConsumer<String, AsyncMessage> pub = destinationPublishers.get(destination);
        if (pub != null) {
            pub.accept(destination, message);
        }
        testBroker.send(destination, message);
    }

    public void setBrokers(MessageBroker testBroker, MessageBroker upstreamBroker) {
        this.testBroker = Objects.requireNonNull(testBroker, "testBroker must not be null");
        this.upstreamBroker = Objects.requireNonNull(upstreamBroker, "upstreamBroker must not be null");
    }

    public MessageBroker getTestBroker() {
        return testBroker;
    }

    public MessageBroker getUpstreamBroker() {
        return upstreamBroker;
    }

    public void addMockStub(String payloadKeyword, Function<AsyncMessage, AsyncMessage> replyProducer) {
        if (payloadKeyword != null && replyProducer != null) {
            mockStubs.put(payloadKeyword, replyProducer);
        }
    }

    public Map<String, AsyncMessage> getHarvestedStubs() {
        return Collections.unmodifiableMap(harvestedStubs);
    }

    public List<String> getJournal() {
        return Collections.unmodifiableList(new ArrayList<>(journal));
    }

    /**
     * Pulls the next message from the test broker. If a configured mock matches, it produces an immediate
     * reply onto the test broker reply channel. If no mock matches, it proxies the message across to the
     * upstream broker with the identical channel name.
     *
     * @param channel channel name to process
     * @return true if a message was processed, false if the queue was empty
     */
    public boolean processInbound(String channel) {
        AsyncMessage msg = testBroker.poll(channel);
        if (msg == null) {
            return false;
        }
        messagesConsumed.incrementAndGet();

        // 1. Check active mock stubs
        for (Map.Entry<String, Function<AsyncMessage, AsyncMessage>> stub : mockStubs.entrySet()) {
            if (msg.payload().contains(stub.getKey())) {
                journal.add("MOCK_MATCH:" + msg.payload());
                AsyncMessage reply = stub.getValue().apply(msg);
                if (reply != null && msg.replyTo() != null && !msg.replyTo().isBlank()) {
                    messagesProduced.incrementAndGet();
                    testBroker.send(msg.replyTo(), reply);
                }
                return true; // 100% Mock isolation: Upstream receives 0 messages
            }
        }

        // 2. Check previously harvested stubs for offline replay
        for (Map.Entry<String, AsyncMessage> harvested : harvestedStubs.entrySet()) {
            if (msg.payload().contains(harvested.getKey())) {
                journal.add("HARVESTED_REPLAY:" + msg.payload());
                AsyncMessage template = harvested.getValue();
                AsyncMessage reply = AsyncMessage.builder()
                        .correlationId(msg.correlationId())
                        .destination(msg.replyTo())
                        .payload(template.payload())
                        .headers(template.headers())
                        .build();
                if (msg.replyTo() != null && !msg.replyTo().isBlank()) {
                    messagesProduced.incrementAndGet();
                    testBroker.send(msg.replyTo(), reply);
                }
                return true;
            }
        }

        // 3. Proxy Mode: Forward to upstream broker with identical channel name
        journal.add("PROXY_FORWARD:" + msg.payload());
        messagesProxied.incrementAndGet();
        upstreamBroker.send(channel, msg);
        return true;
    }

    /**
     * Intercepts a live reply emitted by the upstream system, forwards it to the test broker for System A,
     * and harvests the exchange into the engine catalog for offline replay.
     *
     * @param replyChannel reply queue or channel name
     * @param originalMatchKeyword keyword to correlate with subsequent inbound requests
     * @return true if a reply was processed, false if the reply queue was empty
     */
    public boolean processOutboundReply(String replyChannel, String originalMatchKeyword) {
        AsyncMessage reply = upstreamBroker.poll(replyChannel);
        if (reply == null) {
            return false;
        }

        journal.add("PROXY_REPLY_HARVESTED:" + reply.payload());
        if (originalMatchKeyword != null && !originalMatchKeyword.isBlank()) {
            harvestedStubs.put(originalMatchKeyword, reply);
        }

        messagesConsumed.incrementAndGet();
        // Forward to test broker for System A
        testBroker.send(replyChannel, reply);
        return true;
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
        mockStubs.clear();
        harvestedStubs.clear();
        journal.clear();
        testBroker = new InMemoryMessageBroker("default-test-broker");
        upstreamBroker = new InMemoryMessageBroker("default-upstream-broker");
        stopKafkaSink();
    }

    @Override
    public synchronized void close() {
        reset();
    }
}
