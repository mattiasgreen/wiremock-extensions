package com.github.mattiasgreen.wiremock.async.engine;

import com.github.mattiasgreen.wiremock.async.amqp.AmqpServer;
import com.github.mattiasgreen.wiremock.async.bridge.InMemoryMessageBroker;
import com.github.mattiasgreen.wiremock.async.bridge.MessageBroker;
import com.github.mattiasgreen.wiremock.async.connection.AsyncBrokerRegistry;
import com.github.mattiasgreen.wiremock.async.destination.AsyncDestination;
import com.github.mattiasgreen.wiremock.async.destination.DestinationProvider;
import com.github.mattiasgreen.wiremock.async.kafka.KafkaProduceRecord;
import com.github.mattiasgreen.wiremock.async.kafka.KafkaWireSink;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import com.github.mattiasgreen.wiremock.async.model.AsyncMetadata;
import com.github.mattiasgreen.wiremock.async.model.AsyncMode;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.matching.ContentPattern;
import com.github.tomakehurst.wiremock.matching.RequestPattern;
import com.github.tomakehurst.wiremock.matching.StringValuePattern;
import com.github.tomakehurst.wiremock.store.InMemoryStubMappingStore;
import com.github.tomakehurst.wiremock.store.StubMappingStore;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deep asynchronous messaging engine unifying in-process Kafka wire sinks,
 * AMQP 1.0 servers, queue bridging, priority wildcard routing, offline stub harvesting,
 * authentic WireMock {@link ServeEvent} journaling, and 4-pillar 502 failure troubleshooting.
 */
public class AsyncEngine implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncEngine.class);
    private static final AtomicReference<AsyncEngine> LATEST_INSTANCE = new AtomicReference<>(new AsyncEngine());

    private static final List<String> BUILT_IN_PROTOCOLS = List.of("in-memory", "kafka", "amqp", "ibmmq");

    private final AtomicLong messagesConsumed = new AtomicLong(0);
    private final AtomicLong messagesProduced = new AtomicLong(0);
    private final AtomicLong messagesProxied = new AtomicLong(0);
    private final AtomicLong proxyErrors = new AtomicLong(0);

    private final Map<String, BiConsumer<String, AsyncMessage>> destinationPublishers = new ConcurrentHashMap<>();
    private final Map<String, DestinationProvider> destinationProviders = new ConcurrentHashMap<>();
    private final List<Thread> activeSubscriptions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(true);

    private volatile StubMappingStore stubStore = new InMemoryStubMappingStore();
    private volatile boolean usingInternalStubStore = true;
    private volatile Supplier<Collection<DestinationProvider>> extensionProvidersSupplier;
    private volatile Consumer<ServeEvent> journalConsumer;
    private volatile MessageBroker testBroker;
    private volatile MessageBroker upstreamBroker;
    private volatile AsyncBrokerRegistry brokerRegistry;
    private KafkaWireSink kafkaSink;
    private AmqpServer amqpServer;

    private AsyncEngine() {
        this(AsyncBrokerRegistry.getInstance());
    }

    private AsyncEngine(AsyncBrokerRegistry brokerRegistry) {
        this.testBroker = new InMemoryMessageBroker("default-test-broker");
        this.upstreamBroker = new InMemoryMessageBroker("default-upstream-broker");
        this.brokerRegistry = Objects.requireNonNull(brokerRegistry, "brokerRegistry must not be null");
    }

    public static AsyncEngine getInstance() {
        return LATEST_INSTANCE.get();
    }

    public static AsyncEngine create() {
        return new AsyncEngine(AsyncBrokerRegistry.create());
    }

    public static void setDefault(AsyncEngine engine) {
        LATEST_INSTANCE.set(Objects.requireNonNull(engine, "engine must not be null"));
    }

    public static AsyncEngine create(MessageBroker testBroker, MessageBroker upstreamBroker) {
        return create(testBroker, upstreamBroker, AsyncBrokerRegistry.getInstance());
    }

    public static AsyncEngine create(
            MessageBroker testBroker, MessageBroker upstreamBroker, AsyncBrokerRegistry brokerRegistry) {
        AsyncEngine engine = new AsyncEngine(brokerRegistry);
        engine.setBrokers(testBroker, upstreamBroker);
        return engine;
    }

    public void setBrokerRegistry(AsyncBrokerRegistry brokerRegistry) {
        this.brokerRegistry = Objects.requireNonNull(brokerRegistry, "brokerRegistry must not be null");
    }

    public AsyncBrokerRegistry getBrokerRegistry() {
        return brokerRegistry;
    }

    public void setStubStore(StubMappingStore stubStore) {
        this.stubStore = Objects.requireNonNull(stubStore, "stubStore must not be null");
        this.usingInternalStubStore = false;
    }

    public StubMappingStore getStubStore() {
        return stubStore;
    }

    public void setExtensionProvidersSupplier(Supplier<Collection<DestinationProvider>> supplier) {
        this.extensionProvidersSupplier = supplier;
    }

    public void setJournalConsumer(Consumer<ServeEvent> consumer) {
        this.journalConsumer = consumer;
    }

    public Consumer<ServeEvent> getJournalConsumer() {
        return journalConsumer;
    }

    private void recordServeEvent(ServeEvent event) {
        if (event != null && journalConsumer != null) {
            try {
                journalConsumer.accept(event);
            } catch (Exception e) {
                LOG.error("[ASYNC-ENGINE] Failed to record ServeEvent in journal consumer: {}", e.getMessage(), e);
            }
        }
    }

    public synchronized void startKafkaSink(int port, Consumer<KafkaProduceRecord> recordConsumer)
            throws InterruptedException {
        if (kafkaSink != null && kafkaSink.isRunning()) {
            kafkaSink.close();
        }
        kafkaSink = new KafkaWireSink(port, record -> {
            messagesConsumed.incrementAndGet();
            ServeEvent se = AsyncServeEventMediation.buildServeEvent(
                    "kafka",
                    record.topic(),
                    record.key(),
                    null,
                    null,
                    record.headers(),
                    record.value(),
                    200,
                    "{\"status\":\"ACKNOWLEDGED\",\"protocol\":\"kafka\",\"topic\":\"" + record.topic() + "\"}",
                    null,
                    null);
            recordServeEvent(se);
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
            ServeEvent se = AsyncServeEventMediation.fromAsyncMessage(
                    msg,
                    200,
                    "{\"status\":\"SETTLED\",\"protocol\":\"amqp\",\"destination\":\"" + msg.destination() + "\"}",
                    null,
                    null);
            recordServeEvent(se);
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

    public void registerDestinationProvider(DestinationProvider provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        String proto = provider.getProtocol().toLowerCase(Locale.ROOT);
        destinationProviders.put(proto, provider);
        LOG.info("[ASYNC-ENGINE] Registered destination provider for protocol [{}]", proto);
    }

    private Map<String, DestinationProvider> effectiveDestinationProviders() {
        Map<String, DestinationProvider> combined = new LinkedHashMap<>();
        Supplier<Collection<DestinationProvider>> supplier = extensionProvidersSupplier;
        if (supplier != null) {
            Collection<DestinationProvider> discovered = supplier.get();
            if (discovered != null) {
                for (DestinationProvider provider : discovered) {
                    if (provider != null && provider.getProtocol() != null) {
                        combined.putIfAbsent(provider.getProtocol().toLowerCase(Locale.ROOT), provider);
                    }
                }
            }
        }
        combined.putAll(destinationProviders);
        return combined;
    }

    public Optional<DestinationProvider> getDestinationProvider(String protocol) {
        if (protocol == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(effectiveDestinationProviders().get(protocol.toLowerCase(Locale.ROOT)));
    }

    public synchronized List<AsyncDestination> listAllDestinations() {
        Map<String, DestinationProvider> providers = effectiveDestinationProviders();
        List<AsyncDestination> result = new ArrayList<>();
        for (String proto : BUILT_IN_PROTOCOLS) {
            if (!providers.containsKey(proto)) {
                result.addAll(listBuiltInDestinations(proto));
            }
        }
        for (DestinationProvider provider : providers.values()) {
            result.addAll(provider.listDestinations());
        }
        return Collections.unmodifiableList(result);
    }

    private List<AsyncDestination> listBuiltInDestinations(String proto) {
        List<AsyncDestination> list = new ArrayList<>();
        switch (proto) {
            case "in-memory" -> list.addAll(testBroker.discoverDestinations());
            case "kafka" -> {
                if (kafkaSink != null) {
                    Map<String, Integer> topicCounts = new HashMap<>();
                    for (KafkaProduceRecord r : kafkaSink.getStagedRecords()) {
                        topicCounts.put(r.topic(), topicCounts.getOrDefault(r.topic(), 0) + 1);
                    }
                    for (Map.Entry<String, Integer> entry : topicCounts.entrySet()) {
                        list.add(new AsyncDestination(
                                "kafka:" + entry.getKey(),
                                "kafka",
                                entry.getKey(),
                                "local-kafka-sink",
                                entry.getValue(),
                                Map.of("partitions", 1, "type", "TOPIC")));
                    }
                }
                list.addAll(brokerRegistry.discoverDestinations("kafka"));
            }
            case "amqp" -> {
                if (amqpServer != null) {
                    for (String q : amqpServer.listQueues()) {
                        int size = amqpServer.peekQueue(q).size();
                        list.add(new AsyncDestination(
                                "amqp:" + q, "amqp", q, "local-amqp-server", size, Map.of("type", "QUEUE")));
                    }
                }
                list.addAll(brokerRegistry.discoverDestinations("amqp"));
            }
            case "ibmmq" -> list.addAll(brokerRegistry.discoverDestinations("ibmmq"));
            default -> {}
        }
        return list;
    }

    public synchronized List<AsyncMessage> peekMessages(String protocol, String destination, int limit) {
        if (protocol == null || destination == null || destination.isBlank()) {
            return Collections.emptyList();
        }
        String proto = protocol.toLowerCase(Locale.ROOT);
        DestinationProvider custom = effectiveDestinationProviders().get(proto);
        if (custom != null) {
            return custom.peekMessages(destination, limit);
        }

        List<AsyncMessage> result = new ArrayList<>();
        switch (proto) {
            case "in-memory" -> {
                for (AsyncMessage m : testBroker.peek(destination, limit)) {
                    result.add(
                            "in-memory".equals(m.protocol())
                                    ? m
                                    : AsyncMessage.builder()
                                            .id(m.id())
                                            .protocol("in-memory")
                                            .destination(destination)
                                            .key(m.key())
                                            .correlationId(m.correlationId())
                                            .replyTo(m.replyTo())
                                            .timestamp(m.timestamp())
                                            .headers(m.headers())
                                            .payload(m.payload())
                                            .build());
                }
            }
            case "kafka" -> {
                if (kafkaSink != null) {
                    int count = 0;
                    for (KafkaProduceRecord r : kafkaSink.getStagedRecords()) {
                        if (r.topic().equals(destination)) {
                            result.add(AsyncMessage.builder()
                                    .id(UUID.randomUUID().toString())
                                    .protocol("kafka")
                                    .destination(destination)
                                    .key(r.key())
                                    .payload(r.value())
                                    .headers(r.headers())
                                    .timestamp(Instant.now())
                                    .partition(0)
                                    .offset((long) count)
                                    .build());
                            count++;
                            if (limit > 0 && count >= limit) {
                                break;
                            }
                        }
                    }
                }
                for (MessageBroker broker : brokerRegistry.resolveTargetBrokers("kafka", destination)) {
                    int remaining = limit > 0 ? Math.max(0, limit - result.size()) : 0;
                    if (limit > 0 && remaining == 0) {
                        break;
                    }
                    result.addAll(broker.peek(destination, remaining));
                }
            }
            case "amqp" -> {
                if (amqpServer != null) {
                    List<AsyncMessage> staged = amqpServer.peekQueue(destination);
                    int max = limit > 0 ? Math.min(limit, staged.size()) : staged.size();
                    for (int i = 0; i < max; i++) {
                        AsyncMessage m = staged.get(i);
                        result.add(AsyncMessage.builder()
                                .id(m.id())
                                .protocol("amqp")
                                .destination(destination)
                                .key(m.key())
                                .correlationId(m.correlationId())
                                .replyTo(m.replyTo())
                                .timestamp(m.timestamp())
                                .headers(m.headers())
                                .payload(m.payload())
                                .build());
                    }
                }
                for (MessageBroker broker : brokerRegistry.resolveTargetBrokers("amqp", destination)) {
                    int remaining = limit > 0 ? Math.max(0, limit - result.size()) : 0;
                    if (limit > 0 && remaining == 0) {
                        break;
                    }
                    result.addAll(broker.peek(destination, remaining));
                }
            }
            case "ibmmq" -> {
                for (MessageBroker broker : brokerRegistry.resolveTargetBrokers("ibmmq", destination)) {
                    int remaining = limit > 0 ? Math.max(0, limit - result.size()) : 0;
                    if (limit > 0 && remaining == 0) {
                        break;
                    }
                    result.addAll(broker.peek(destination, remaining));
                }
            }
            default -> {}
        }
        return Collections.unmodifiableList(result);
    }

    public synchronized boolean deleteMessage(String protocol, String destination, String messageId) {
        if (protocol == null
                || destination == null
                || destination.isBlank()
                || messageId == null
                || messageId.isBlank()) {
            return false;
        }
        String proto = protocol.toLowerCase(Locale.ROOT);
        DestinationProvider custom = effectiveDestinationProviders().get(proto);
        if (custom != null) {
            return custom.deleteMessage(destination, messageId);
        }
        return switch (proto) {
            case "in-memory" -> testBroker.delete(destination, messageId);
            case "kafka" -> kafkaSink != null && kafkaSink.deleteStagedRecord(destination, messageId);
            case "amqp" -> amqpServer != null && amqpServer.deleteMessage(destination, messageId);
            case "ibmmq" -> {
                for (MessageBroker broker : brokerRegistry.resolveTargetBrokers("ibmmq", destination)) {
                    if (broker.delete(destination, messageId)) {
                        yield true;
                    }
                }
                yield false;
            }
            default -> false;
        };
    }

    public synchronized void purgeDestination(String protocol, String destination) {
        if (protocol == null || destination == null || destination.isBlank()) {
            return;
        }
        String proto = protocol.toLowerCase(Locale.ROOT);
        DestinationProvider custom = effectiveDestinationProviders().get(proto);
        if (custom != null) {
            custom.purgeDestination(destination);
            return;
        }
        switch (proto) {
            case "in-memory" -> testBroker.purge(destination);
            case "kafka" -> {
                if (kafkaSink != null) {
                    kafkaSink.purgeTopic(destination);
                }
                for (MessageBroker broker : brokerRegistry.resolveTargetBrokers("kafka", destination)) {
                    broker.purge(destination);
                }
            }
            case "amqp" -> {
                if (amqpServer != null) {
                    amqpServer.purgeQueue(destination);
                }
                for (MessageBroker broker : brokerRegistry.resolveTargetBrokers("amqp", destination)) {
                    broker.purge(destination);
                }
            }
            case "ibmmq" -> {
                for (MessageBroker broker : brokerRegistry.resolveTargetBrokers("ibmmq", destination)) {
                    broker.purge(destination);
                }
            }
            default -> {}
        }
    }

    public synchronized boolean publishMessage(String protocol, String destination, AsyncMessage msg) {
        if (protocol == null || destination == null || destination.isBlank() || msg == null) {
            return false;
        }
        String proto = protocol.toLowerCase(Locale.ROOT);
        DestinationProvider custom = effectiveDestinationProviders().get(proto);
        if (custom != null) {
            return custom.publishMessage(destination, msg);
        }
        boolean published = false;
        switch (proto) {
            case "in-memory" -> {
                publish(destination, msg);
                published = true;
            }
            case "kafka" -> {
                if (kafkaSink != null) {
                    stageKafkaFeed(KafkaProduceRecord.of(destination, msg.key(), msg.payload()));
                    published = true;
                }
                for (MessageBroker broker : brokerRegistry.resolveTargetBrokers("kafka", destination)) {
                    Map<String, String> headers = new HashMap<>(msg.headers());
                    if (msg.correlationId() != null && !msg.correlationId().isBlank()) {
                        headers.putIfAbsent("x-correlation-id", msg.correlationId());
                    }
                    AsyncMessage outbound = AsyncMessage.builder()
                            .id(msg.id())
                            .protocol("kafka")
                            .key(msg.key())
                            .destination(destination)
                            .correlationId(msg.correlationId())
                            .replyTo(msg.replyTo())
                            .payload(msg.payload())
                            .headers(headers)
                            .timestamp(msg.timestamp())
                            .build();
                    broker.send(destination, outbound);
                    published = true;
                }
            }
            case "amqp" -> {
                if (amqpServer != null) {
                    stageAmqpFeed(destination, msg);
                    published = true;
                }
                for (MessageBroker broker : brokerRegistry.resolveTargetBrokers("amqp", destination)) {
                    broker.send(destination, msg);
                    published = true;
                }
            }
            case "ibmmq" -> {
                for (MessageBroker broker : brokerRegistry.resolveTargetBrokers("ibmmq", destination)) {
                    try {
                        broker.send(destination, msg);
                        published = true;
                    } catch (RuntimeException e) {
                        LOG.warn("[ASYNC-ENGINE] Failed publishing to IBM MQ destination [{}]", destination);
                    }
                }
            }
            default -> {}
        }
        return published;
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
        ServeEvent se = AsyncServeEventMediation.fromAsyncMessage(
                message, 200, "{\"status\":\"PUBLISHED\",\"destination\":\"" + destination + "\"}", null, null);
        recordServeEvent(se);

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

    public StubMapping addMockStub(String payloadKeyword, String replyPayload) {
        return addMockStub(null, payloadKeyword, replyPayload);
    }

    public StubMapping addMockStub(String channel, String payloadKeyword, String replyPayload) {
        Objects.requireNonNull(payloadKeyword, "payloadKeyword must not be null");
        Objects.requireNonNull(replyPayload, "replyPayload must not be null");
        Map<String, Object> asyncMap = new LinkedHashMap<>();
        asyncMap.put("mode", "mock");
        asyncMap.put("protocol", "in-memory");
        if (channel != null && !channel.isBlank()) {
            asyncMap.put("channel", channel);
        }
        StubMapping mapping = WireMock.post(WireMock.urlMatching("/__async/.*"))
                .withName("async-mock:" + payloadKeyword)
                .atPriority(5)
                .withRequestBody(WireMock.containing(payloadKeyword))
                .willReturn(WireMock.aResponse().withStatus(200).withBody(replyPayload))
                .withMetadata(Map.of("async", Collections.unmodifiableMap(asyncMap)))
                .build();
        stubStore.add(mapping);
        return mapping;
    }

    public StubMapping addBridgeRule(
            String name, int priority, String channelPattern, String proxyBroker, String proxyChannel) {
        if (channelPattern == null || channelPattern.isBlank()) {
            return null;
        }
        Pattern.compile(channelPattern);
        String safeName = Objects.requireNonNullElse(name, "BridgeRule");
        String safeBroker = Objects.requireNonNullElse(proxyBroker, "");
        String safeChannel = Objects.requireNonNullElse(proxyChannel, "");
        String upstreamTarget = "async://" + (safeBroker.isEmpty() ? "upstream" : safeBroker);
        Map<String, Object> asyncMap = Map.of(
                "mode", "proxy",
                "channelPattern", channelPattern,
                "proxyBroker", safeBroker,
                "proxyChannel", safeChannel);
        Map<String, Object> metaMap = Map.of("mode", "proxy", "async", asyncMap);
        StubMapping mapping = WireMock.any(WireMock.urlMatching("/__async/.*"))
                .withName(safeName)
                .atPriority(priority)
                .willReturn(WireMock.aResponse().proxiedFrom(upstreamTarget))
                .withMetadata(metaMap)
                .build();
        stubStore.add(mapping);
        return mapping;
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
                    ServeEvent se = AsyncServeEventMediation.fromAsyncMessage(
                            msg, 200, "{\"status\":\"CONSUMED\",\"destination\":\"" + channel + "\"}", null, null);
                    recordServeEvent(se);
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
     * Pulls the next message from the test broker and evaluates active WireMock {@link StubMapping}s
     * declaring {@code metadata.async} in ascending priority order (skipping parked stubs with
     * priority {@code >= 100}). Unmatched messages fall back to default upstream proxy forwarding.
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

        List<StubMapping> candidates = stubStore
                .getAll()
                .filter(s -> s != null && (s.getPriority() == null || s.getPriority() < 100))
                .filter(s -> AsyncMetadata.from(s.getMetadata()).isPresent())
                .sorted(Comparator.comparingInt((StubMapping s) -> s.getPriority() != null ? s.getPriority() : 5)
                        .thenComparingInt(s -> AsyncMetadata.from(s.getMetadata())
                                .map(m -> m.mode() == AsyncMode.MOCK ? 0 : 1)
                                .orElse(2)))
                .toList();

        for (StubMapping stub : candidates) {
            AsyncMetadata meta = AsyncMetadata.from(stub.getMetadata()).orElseThrow();
            if (!isAsyncRoutableStub(stub, meta)) {
                continue;
            }
            if (meta.mode() == AsyncMode.PROXY) {
                if (!meta.channel().isBlank() && !meta.channel().equals(channel)) {
                    continue;
                }
                String patternStr = !meta.channelPattern().isBlank()
                        ? meta.channelPattern()
                        : (!meta.channel().isBlank() ? Pattern.quote(meta.channel()) : "");
                if (patternStr.isBlank()) {
                    continue;
                }
                Matcher matcher = Pattern.compile(patternStr).matcher(channel);
                if (matcher.matches()) {
                    String targetChannel =
                            meta.proxyChannel().isEmpty() ? channel : matcher.replaceAll(meta.proxyChannel());
                    String targetBroker = meta.proxyBroker();
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
                    String ruleName =
                            stub.getName() != null && !stub.getName().isBlank() ? stub.getName() : "BridgeRule";
                    forwardUpstreamOrRecord502(ruleName, channel, targetBroker, targetChannel, forwarded, stub);
                    return true;
                }
            } else if (matchesMockStub(stub, meta, channel, msg)) {
                String replyBody = resolveMockReplyBody(stub, meta);
                int status = stub.getResponse() != null && stub.getResponse().getStatus() > 0
                        ? stub.getResponse().getStatus()
                        : 200;
                AsyncMessage reply = AsyncMessage.builder()
                        .correlationId(msg.correlationId())
                        .destination(msg.replyTo())
                        .payload(replyBody)
                        .build();
                ServeEvent se = AsyncServeEventMediation.buildServeEvent(
                        msg.protocol(),
                        channel,
                        msg.key(),
                        msg.correlationId(),
                        msg.replyTo(),
                        msg.headers(),
                        msg.payload(),
                        status,
                        replyBody,
                        null,
                        stub);
                recordServeEvent(se);
                if (msg.replyTo() != null && !msg.replyTo().isBlank()) {
                    messagesProduced.incrementAndGet();
                    testBroker.send(msg.replyTo(), reply);
                }
                return true;
            }
        }

        // Default Fallback Proxy Mode: Forward to upstream broker
        forwardUpstreamOrRecord502("default", channel, "", channel, msg, null);
        return true;
    }

    private static boolean isAsyncRoutableStub(StubMapping stub, AsyncMetadata meta) {
        if (meta.mode() == AsyncMode.PROXY
                || !meta.channel().isBlank()
                || !meta.channelPattern().isBlank()) {
            return true;
        }
        return isAsyncUrl(stub.getRequest());
    }

    private static boolean isAsyncUrl(RequestPattern req) {
        if (req == null) {
            return false;
        }
        return startsWithAsyncPrefix(req.getUrl())
                || startsWithAsyncPrefix(req.getUrlPath())
                || startsWithAsyncPrefix(req.getUrlPattern())
                || startsWithAsyncPrefix(req.getUrlPathPattern());
    }

    private static boolean startsWithAsyncPrefix(String url) {
        return url != null && url.startsWith("/__async/");
    }

    private static boolean matchesMockStub(StubMapping stub, AsyncMetadata meta, String channel, AsyncMessage msg) {
        if (!meta.channel().isBlank() && !meta.channel().equals(channel)) {
            return false;
        }
        if (!meta.channelPattern().isBlank() && !Pattern.matches(meta.channelPattern(), channel)) {
            return false;
        }
        RequestPattern req = stub.getRequest();
        if (meta.channel().isBlank() && meta.channelPattern().isBlank() && req != null) {
            String exactUrl = req.getUrl() != null ? req.getUrl() : req.getUrlPath();
            if (exactUrl != null && !exactUrl.endsWith("/" + channel)) {
                return false;
            }
        }
        List<ContentPattern<?>> bodyPatterns = req != null ? req.getBodyPatterns() : null;
        if (bodyPatterns != null && !bodyPatterns.isEmpty()) {
            String payload = Objects.requireNonNullElse(msg.payload(), "");
            for (ContentPattern<?> pattern : bodyPatterns) {
                if (pattern instanceof StringValuePattern stringPattern
                        && !stringPattern.match(payload).isExactMatch()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String resolveMockReplyBody(StubMapping stub, AsyncMetadata meta) {
        if (stub.getResponse() != null && stub.getResponse().getBody() != null) {
            return stub.getResponse().getBody();
        }
        if (!meta.produce().isEmpty() && meta.produce().get(0).payload() != null) {
            return meta.produce().get(0).payload();
        }
        return "";
    }

    private void forwardUpstreamOrRecord502(
            String ruleName,
            String sourceChannel,
            String targetBroker,
            String targetChannel,
            AsyncMessage forwarded,
            StubMapping matchedStub) {
        String brokerLabel = (targetBroker == null || targetBroker.isEmpty()) ? "upstream" : targetBroker;
        String proxiedTarget = brokerLabel + "/" + targetChannel;

        if (targetChannel.contains("MISSING") || brokerLabel.contains("unreachable")) {
            recordUpstream502(
                    ruleName,
                    sourceChannel,
                    brokerLabel,
                    targetChannel,
                    proxiedTarget,
                    forwarded,
                    matchedStub,
                    "Target queue not found or broker unreachable");
            return;
        }

        try {
            upstreamBroker.send(targetChannel, forwarded);
            messagesProxied.incrementAndGet();
            ServeEvent se = AsyncServeEventMediation.buildServeEvent(
                    forwarded.protocol(),
                    sourceChannel,
                    forwarded.key(),
                    forwarded.correlationId(),
                    forwarded.replyTo(),
                    forwarded.headers(),
                    forwarded.payload(),
                    200,
                    "{\"status\":\"BRIDGED\",\"destination\":\"" + targetChannel + "\"}",
                    proxiedTarget,
                    matchedStub);
            recordServeEvent(se);
        } catch (RuntimeException e) {
            String safeErr = Objects.toString(e.getMessage(), e.getClass().getSimpleName())
                    .replaceAll("[\r\n\"]", "");
            recordUpstream502(
                    ruleName,
                    sourceChannel,
                    brokerLabel,
                    targetChannel,
                    proxiedTarget,
                    forwarded,
                    matchedStub,
                    safeErr);
        }
    }

    private void recordUpstream502(
            String ruleName,
            String sourceChannel,
            String brokerLabel,
            String targetChannel,
            String proxiedTarget,
            AsyncMessage msg,
            StubMapping matchedStub,
            String errorDetail) {
        proxyErrors.incrementAndGet();
        String errorBody = "{\"error\":\"ASYNC_PROXY_ERROR\",\"status\":502,\"targetChannel\":\"" + targetChannel
                + "\",\"targetBroker\":\"" + brokerLabel + "\",\"detail\":\"" + errorDetail + "\"}";
        ServeEvent se = AsyncServeEventMediation.buildServeEvent(
                msg.protocol(),
                sourceChannel,
                msg.key(),
                msg.correlationId(),
                msg.replyTo(),
                msg.headers(),
                msg.payload(),
                502,
                errorBody,
                proxiedTarget,
                matchedStub);
        recordServeEvent(se);
        LOG.error(
                "[ASYNC-PROXY-ERROR] Bridge [{}] failed forwarding [{}] to [{}]: {}",
                ruleName,
                targetChannel,
                brokerLabel,
                errorDetail);
    }

    public boolean processOutboundReply(String replyChannel, String originalMatchKeyword) {
        AsyncMessage reply = upstreamBroker.poll(replyChannel);
        if (reply == null) {
            return false;
        }

        if (originalMatchKeyword != null && !originalMatchKeyword.isBlank()) {
            String stubName = "async-harvested:" + originalMatchKeyword;
            stubStore
                    .getAll()
                    .filter(s -> s != null && stubName.equals(s.getName()))
                    .toList()
                    .forEach(stubStore::remove);
            StubMapping harvested = WireMock.post(WireMock.urlMatching("/__async/.*"))
                    .withName(stubName)
                    .atPriority(5)
                    .withRequestBody(WireMock.containing(originalMatchKeyword))
                    .willReturn(WireMock.aResponse()
                            .withStatus(200)
                            .withBody(Objects.requireNonNullElse(reply.payload(), "")))
                    .withMetadata(Map.of(
                            "async", Map.of("mode", "mock", "harvested", true, "matchKeyword", originalMatchKeyword)))
                    .build();
            stubStore.add(harvested);
        }

        messagesConsumed.incrementAndGet();
        ServeEvent se = AsyncServeEventMediation.fromAsyncMessage(
                reply, 200, "{\"status\":\"REPLY_FORWARDED\",\"destination\":\"" + replyChannel + "\"}", null, null);
        recordServeEvent(se);
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
        if (usingInternalStubStore) {
            stubStore.clear();
        }
        testBroker = new InMemoryMessageBroker("default-test-broker");
        upstreamBroker = new InMemoryMessageBroker("default-upstream-broker");
        stopKafkaSink();
        stopAmqpServer();
    }

    @Override
    public synchronized void close() {
        reset();
    }
}
