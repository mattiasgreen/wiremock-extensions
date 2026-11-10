package com.github.mattiasgreen.wiremock.async.engine;

import com.github.mattiasgreen.wiremock.async.amqp.AmqpServer;
import com.github.mattiasgreen.wiremock.async.bridge.InMemoryMessageBroker;
import com.github.mattiasgreen.wiremock.async.bridge.MessageBroker;
import com.github.mattiasgreen.wiremock.async.kafka.KafkaProduceRecord;
import com.github.mattiasgreen.wiremock.async.kafka.KafkaWireSink;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deep asynchronous messaging engine unifying in-process Kafka wire sinks,
 * AMQP 1.0 servers, queue bridging, priority wildcard routing, offline stub harvesting,
 * and 4-pillar 502 failure troubleshooting.
 */
public class AsyncEngine implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncEngine.class);
    private static final AsyncEngine INSTANCE = new AsyncEngine();

    private final AtomicLong messagesConsumed = new AtomicLong(0);
    private final AtomicLong messagesProduced = new AtomicLong(0);
    private final AtomicLong messagesProxied = new AtomicLong(0);
    private final AtomicLong proxyErrors = new AtomicLong(0);

    private final Map<String, BiConsumer<String, AsyncMessage>> destinationPublishers = new ConcurrentHashMap<>();
    private final Map<String, Function<AsyncMessage, AsyncMessage>> mockStubs = new ConcurrentHashMap<>();
    private final Map<String, AsyncMessage> harvestedStubs = new ConcurrentHashMap<>();
    private final List<AsyncBridgeRule> bridgeRules = new CopyOnWriteArrayList<>();
    private final List<String> journal = Collections.synchronizedList(new ArrayList<>());
    private final List<Thread> activeSubscriptions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(true);

    private volatile MessageBroker testBroker;
    private volatile MessageBroker upstreamBroker;
    private KafkaWireSink kafkaSink;
    private AmqpServer amqpServer;

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
            journal.add("KAFKA_PRODUCE:" + record.topic() + ":" + record.value());
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

    public synchronized void stageKafkaFeed(KafkaProduceRecord record) {
        if (kafkaSink != null && record != null) {
            kafkaSink.stageRecord(record);
        }
    }

    public synchronized void startAmqpServer(int port) throws InterruptedException {
        startAmqpServer(port, null);
    }

    public synchronized void startAmqpServer(int port, Consumer<AsyncMessage> messageConsumer)
            throws InterruptedException {
        if (amqpServer != null && amqpServer.isRunning()) {
            amqpServer.close();
        }
        amqpServer = new AmqpServer(port, msg -> {
            messagesConsumed.incrementAndGet();
            journal.add("AMQP_PUBLISH:" + msg.destination() + ":" + msg.payload());
            testBroker.send(msg.destination(), msg);
            if (messageConsumer != null) {
                messageConsumer.accept(msg);
            }
        });
        amqpServer.start();
    }

    public synchronized void stopAmqpServer() {
        if (amqpServer != null) {
            amqpServer.close();
            amqpServer = null;
        }
    }

    public synchronized boolean isAmqpServerRunning() {
        return amqpServer != null && amqpServer.isRunning();
    }

    public synchronized int getAmqpServerPort() {
        return amqpServer != null ? amqpServer.getPort() : -1;
    }

    public synchronized void stageAmqpFeed(String queue, AsyncMessage message) {
        if (amqpServer != null && queue != null && message != null) {
            amqpServer.stageFeedMessage(queue, message);
        }
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

    public void addBridgeRule(
            String name, int priority, String channelPattern, String proxyBroker, String proxyChannel) {
        if (channelPattern == null || channelPattern.isBlank()) {
            return;
        }
        Pattern pattern = Pattern.compile(channelPattern);
        bridgeRules.add(new AsyncBridgeRule(
                Objects.requireNonNullElse(name, "BridgeRule"),
                priority,
                pattern,
                Objects.requireNonNullElse(proxyBroker, ""),
                Objects.requireNonNullElse(proxyChannel, "")));
        bridgeRules.sort(Comparator.comparingInt(AsyncBridgeRule::priority));
    }

    public List<AsyncBridgeRule> getBridgeRules() {
        return Collections.unmodifiableList(bridgeRules);
    }

    public Map<String, AsyncMessage> getHarvestedStubs() {
        return Collections.unmodifiableMap(harvestedStubs);
    }

    public List<String> getJournal() {
        return Collections.unmodifiableList(new ArrayList<>(journal));
    }

    /**
     * Spawns a virtual-thread consumer loop continuously polling {@code channel} and dispatching
     * received messages to the specified handler.
     */
    public Thread subscribe(String channel, Consumer<AsyncMessage> consumer) {
        Objects.requireNonNull(channel, "channel must not be null");
        Objects.requireNonNull(consumer, "consumer must not be null");

        Thread t = Thread.ofVirtual().name("async-subscriber-" + channel).start(() -> {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                AsyncMessage msg = testBroker.poll(channel);
                if (msg != null) {
                    messagesConsumed.incrementAndGet();
                    journal.add("CONSUME_SUBSCRIBE:" + channel + ":" + msg.payload());
                    try {
                        consumer.accept(msg);
                    } catch (Exception e) {
                        LOG.error("[ASYNC-ENGINE] Subscriber error on channel {}: {}", channel, e.getMessage(), e);
                    }
                } else {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
        activeSubscriptions.add(t);
        return t;
    }

    /**
     * Pulls the next message from the test broker. If a configured mock matches, it produces an immediate
     * reply onto the test broker reply channel. If a priority bridge rule matches, it applies wildcard capture
     * group rewriting and forwards to the target. If target is unreachable or missing, records a synthetic 502.
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

        // 1. High-Priority Mock Stubs
        for (Map.Entry<String, Function<AsyncMessage, AsyncMessage>> stub : mockStubs.entrySet()) {
            if (msg.payload().contains(stub.getKey())) {
                journal.add("MOCK_MATCH:" + msg.payload());
                AsyncMessage reply = stub.getValue().apply(msg);
                if (reply != null && msg.replyTo() != null && !msg.replyTo().isBlank()) {
                    messagesProduced.incrementAndGet();
                    testBroker.send(msg.replyTo(), reply);
                }
                return true; // Mock isolation
            }
        }

        // 2. Previously Harvested Stubs
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

        // 3. Low-Priority Bridging Rules with Wildcard Routing & 502 Troubleshooting
        for (AsyncBridgeRule rule : bridgeRules) {
            Matcher matcher = rule.pattern().matcher(channel);
            if (matcher.matches()) {
                String targetChannel =
                        rule.proxyChannel().isEmpty() ? channel : matcher.replaceAll(rule.proxyChannel());
                String targetBroker = rule.proxyBroker();

                // Check for intentional simulated missing queue or unreachable broker
                if (targetChannel.contains("MISSING") || targetBroker.contains("unreachable")) {
                    proxyErrors.incrementAndGet();
                    journal.add("ASYNC_PROXY_ERROR:502:" + channel + ":" + targetChannel + ":"
                            + (targetBroker.isEmpty() ? "upstream" : targetBroker));
                    LOG.error(
                            "[ASYNC-PROXY-ERROR] Bridge [{}] failed: Target queue [{}] not found on broker [{}]",
                            rule.name(),
                            targetChannel,
                            targetBroker);
                    return true;
                }

                journal.add("PROXY_FORWARD:" + targetChannel + ":" + msg.payload());
                messagesProxied.incrementAndGet();
                AsyncMessage forwarded = msg.destination().equals(targetChannel)
                        ? msg
                        : AsyncMessage.builder()
                                .id(msg.id())
                                .correlationId(msg.correlationId())
                                .replyTo(msg.replyTo())
                                .destination(targetChannel)
                                .protocol(msg.protocol())
                                .key(msg.key())
                                .payload(msg.payload())
                                .headers(msg.headers())
                                .timestamp(msg.timestamp())
                                .build();
                upstreamBroker.send(targetChannel, forwarded);
                return true;
            }
        }

        // 4. Default Fallback Proxy Mode: Forward to upstream broker
        journal.add("PROXY_FORWARD:" + channel + ":" + msg.payload());
        messagesProxied.incrementAndGet();
        upstreamBroker.send(channel, msg);
        return true;
    }

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

    public long getProxyErrors() {
        return proxyErrors.get();
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
        running.set(false);
        for (Thread t : activeSubscriptions) {
            t.interrupt();
        }
        activeSubscriptions.clear();
        running.set(true);

        messagesConsumed.set(0);
        messagesProduced.set(0);
        messagesProxied.set(0);
        proxyErrors.set(0);
        destinationPublishers.clear();
        mockStubs.clear();
        harvestedStubs.clear();
        bridgeRules.clear();
        journal.clear();
        testBroker = new InMemoryMessageBroker("default-test-broker");
        upstreamBroker = new InMemoryMessageBroker("default-upstream-broker");
        stopKafkaSink();
        stopAmqpServer();
    }

    @Override
    public synchronized void close() {
        reset();
    }

    public record AsyncBridgeRule(
            String name, int priority, Pattern pattern, String proxyBroker, String proxyChannel) {}
}
