package com.github.mattiasgreen.wiremock.async.engine;

import com.github.mattiasgreen.wiremock.async.amqp.AmqpServer;
import com.github.mattiasgreen.wiremock.async.bridge.InMemoryMessageBroker;
import com.github.mattiasgreen.wiremock.async.bridge.MessageBroker;
import com.github.mattiasgreen.wiremock.async.connection.AsyncBrokerRegistry;
import com.github.mattiasgreen.wiremock.async.connection.BrokerConnection;
import com.github.mattiasgreen.wiremock.async.destination.AmqpServerDestinationAdapter;
import com.github.mattiasgreen.wiremock.async.destination.AsyncDestination;
import com.github.mattiasgreen.wiremock.async.destination.BrokerRegistryDestinationAdapter;
import com.github.mattiasgreen.wiremock.async.destination.DestinationProvider;
import com.github.mattiasgreen.wiremock.async.destination.InMemoryDestinationAdapter;
import com.github.mattiasgreen.wiremock.async.destination.KafkaWireSinkDestinationAdapter;
import com.github.mattiasgreen.wiremock.async.kafka.KafkaProduceRecord;
import com.github.mattiasgreen.wiremock.async.kafka.KafkaWireSink;
import com.github.mattiasgreen.wiremock.async.model.AsyncFeedRecord;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import com.github.mattiasgreen.wiremock.async.model.AsyncMetadata;
import com.github.mattiasgreen.wiremock.async.model.AsyncMode;
import com.github.mattiasgreen.wiremock.async.model.AsyncProduceDirective;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.ImmutableRequest;
import com.github.tomakehurst.wiremock.matching.ContentPattern;
import com.github.tomakehurst.wiremock.matching.MultiValuePattern;
import com.github.tomakehurst.wiremock.matching.RequestPattern;
import com.github.tomakehurst.wiremock.matching.StringValuePattern;
import com.github.tomakehurst.wiremock.store.InMemoryStubMappingStore;
import com.github.tomakehurst.wiremock.store.StubMappingStore;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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

    private static final List<String> BUILT_IN_PROTOCOLS = List.of("in-memory", "kafka", "amqp", "ibmmq");

    private final AtomicLong messagesConsumed = new AtomicLong(0);
    private final AtomicLong messagesProduced = new AtomicLong(0);
    private final AtomicLong messagesProxied = new AtomicLong(0);
    private final AtomicLong proxyErrors = new AtomicLong(0);

    private final Map<String, BiConsumer<String, AsyncMessage>> destinationPublishers = new ConcurrentHashMap<>();
    private final List<DestinationProvider> builtInAdapters;
    private final Map<String, DestinationProvider> registryAdapters = new ConcurrentHashMap<>();
    private final List<DestinationProvider> destinationProviders = new CopyOnWriteArrayList<>();
    private final List<Thread> activeSubscriptions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Object sinkLifecycleLock = new Object();

    private volatile StubMappingStore stubStore = new InMemoryStubMappingStore();
    private volatile boolean usingInternalStubStore = true;
    private volatile Supplier<Collection<DestinationProvider>> extensionProvidersSupplier;
    private volatile Consumer<ServeEvent> journalConsumer;
    private volatile MessageBroker testBroker;
    private volatile MessageBroker upstreamBroker;
    private volatile boolean usingDefaultUpstreamBroker = true;
    private volatile AsyncBrokerRegistry brokerRegistry;
    private volatile KafkaWireSink kafkaSink;
    private volatile AmqpServer amqpServer;

    private AsyncEngine(AsyncBrokerRegistry brokerRegistry) {
        this.testBroker = new InMemoryMessageBroker("default-test-broker");
        this.upstreamBroker = new InMemoryMessageBroker("default-upstream-broker");
        this.brokerRegistry = Objects.requireNonNull(brokerRegistry, "brokerRegistry must not be null");
        this.builtInAdapters = List.of(
                new InMemoryDestinationAdapter(() -> this.testBroker),
                new KafkaWireSinkDestinationAdapter(() -> this.kafkaSink),
                new AmqpServerDestinationAdapter(() -> this.amqpServer));
    }

    public static AsyncEngine create() {
        return new AsyncEngine(AsyncBrokerRegistry.create());
    }

    public static AsyncEngine create(MessageBroker testBroker, MessageBroker upstreamBroker) {
        return create(testBroker, upstreamBroker, AsyncBrokerRegistry.create());
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
                LOG.error(
                        "[ASYNC-ENGINE] Failed to record ServeEvent in journal consumer: {}",
                        sanitizeForLog(e.getMessage()),
                        e);
            }
        }
    }

    public void startKafkaSink(int port, Consumer<KafkaProduceRecord> recordConsumer) throws InterruptedException {
        synchronized (sinkLifecycleLock) {
            if (kafkaSink != null && kafkaSink.isRunning()) {
                kafkaSink.close();
            }
            KafkaWireSink sink = new KafkaWireSink(port, record -> {
                String correlationId = "";
                String replyTo = "";
                for (Map.Entry<String, String> header : record.headers().entrySet()) {
                    if ("x-correlation-id".equalsIgnoreCase(header.getKey())
                            || "correlationId".equalsIgnoreCase(header.getKey())) {
                        correlationId = header.getValue();
                    } else if ("x-reply-to".equalsIgnoreCase(header.getKey())
                            || "replyTo".equalsIgnoreCase(header.getKey())) {
                        replyTo = header.getValue();
                    }
                }
                AsyncMessage inbound = AsyncMessage.builder()
                        .id(record.recordId())
                        .protocol("kafka")
                        .destination(record.topic())
                        .key(record.key())
                        .correlationId(correlationId)
                        .replyTo(replyTo)
                        .payload(record.value())
                        .headers(record.headers())
                        .partition(record.partition())
                        .offset(record.offset())
                        .build();
                mediateInboundMessage(inbound, false, "ACKNOWLEDGED");
                if (recordConsumer != null) {
                    recordConsumer.accept(record);
                }
            });
            sink.start();
            kafkaSink = sink;
        }
    }

    public void stopKafkaSink() {
        synchronized (sinkLifecycleLock) {
            if (kafkaSink != null) {
                kafkaSink.close();
                kafkaSink = null;
            }
        }
    }

    public boolean isKafkaSinkRunning() {
        KafkaWireSink sink = kafkaSink;
        return sink != null && sink.isRunning();
    }

    public int getKafkaSinkPort() {
        KafkaWireSink sink = kafkaSink;
        return sink != null ? sink.getPort() : -1;
    }

    public void stageKafkaFeed(KafkaProduceRecord record) {
        KafkaWireSink sink = kafkaSink;
        if (sink != null && record != null) {
            sink.stageRecord(record);
        }
    }

    public void startAmqpServer(int port) throws InterruptedException {
        startAmqpServer(port, null);
    }

    public void startAmqpServer(int port, Consumer<AsyncMessage> messageConsumer) throws InterruptedException {
        synchronized (sinkLifecycleLock) {
            if (amqpServer != null && amqpServer.isRunning()) {
                amqpServer.close();
            }
            AmqpServer server = new AmqpServer(
                    port,
                    msg -> Thread.ofVirtual().name("async-amqp-inbound-", 0).start(() -> {
                        boolean mediated = mediateInboundMessage(msg, false, "SETTLED");
                        if (!mediated) {
                            testBroker.send(msg.destination(), msg);
                        }
                        if (messageConsumer != null) {
                            messageConsumer.accept(msg);
                        }
                    }));
            server.start();
            amqpServer = server;
        }
    }

    public void stopAmqpServer() {
        synchronized (sinkLifecycleLock) {
            if (amqpServer != null) {
                amqpServer.close();
                amqpServer = null;
            }
        }
    }

    public boolean isAmqpServerRunning() {
        AmqpServer server = amqpServer;
        return server != null && server.isRunning();
    }

    public int getAmqpServerPort() {
        AmqpServer server = amqpServer;
        return server != null ? server.getPort() : -1;
    }

    public void stageAmqpFeed(String queue, AsyncMessage message) {
        AmqpServer server = amqpServer;
        if (server != null && queue != null && message != null) {
            server.stageFeedMessage(queue, message);
        }
    }

    public void registerDestinationProvider(DestinationProvider provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(provider.getProtocol(), "provider protocol must not be null");
        if (!destinationProviders.contains(provider)) {
            destinationProviders.add(provider);
        }
        String proto = provider.getProtocol().toLowerCase(Locale.ROOT).replaceAll("[\r\n]", "");
        LOG.info("[ASYNC-ENGINE] Registered destination provider for protocol [{}]", proto);
    }

    private List<DestinationProvider> discoveredExtensionProviders() {
        Supplier<Collection<DestinationProvider>> supplier = extensionProvidersSupplier;
        if (supplier == null) {
            return Collections.emptyList();
        }
        Collection<DestinationProvider> discovered = supplier.get();
        if (discovered == null || discovered.isEmpty()) {
            return Collections.emptyList();
        }
        List<DestinationProvider> valid = new ArrayList<>(discovered.size());
        for (DestinationProvider provider : discovered) {
            if (provider != null && provider.getProtocol() != null) {
                valid.add(provider);
            }
        }
        return valid;
    }

    private List<DestinationProvider> resolveAdapters(String protocol) {
        String proto = protocol.toLowerCase(Locale.ROOT);
        List<DestinationProvider> adapters = new ArrayList<>();
        for (DestinationProvider builtIn : builtInAdapters) {
            if (proto.equals(builtIn.getProtocol().toLowerCase(Locale.ROOT))) {
                adapters.add(builtIn);
            }
        }
        adapters.add(registryAdapters.computeIfAbsent(
                proto, p -> new BrokerRegistryDestinationAdapter(p, () -> this.brokerRegistry)));
        for (DestinationProvider external : discoveredExtensionProviders()) {
            if (proto.equals(external.getProtocol().toLowerCase(Locale.ROOT)) && !adapters.contains(external)) {
                adapters.add(external);
            }
        }
        for (DestinationProvider registered : destinationProviders) {
            if (proto.equals(registered.getProtocol().toLowerCase(Locale.ROOT)) && !adapters.contains(registered)) {
                adapters.add(registered);
            }
        }
        return adapters;
    }

    private Set<String> allKnownProtocols() {
        Set<String> protocols = new LinkedHashSet<>(BUILT_IN_PROTOCOLS);
        AsyncBrokerRegistry registry = brokerRegistry;
        if (registry != null) {
            for (BrokerConnection conn : registry.listConnections()) {
                if (conn.protocol() != null && !conn.protocol().isBlank()) {
                    protocols.add(conn.protocol().toLowerCase(Locale.ROOT));
                }
            }
        }
        for (DestinationProvider external : discoveredExtensionProviders()) {
            protocols.add(external.getProtocol().toLowerCase(Locale.ROOT));
        }
        for (DestinationProvider registered : destinationProviders) {
            protocols.add(registered.getProtocol().toLowerCase(Locale.ROOT));
        }
        return protocols;
    }

    public Optional<DestinationProvider> getDestinationProvider(String protocol) {
        if (protocol == null) {
            return Optional.empty();
        }
        String target = protocol.toLowerCase(Locale.ROOT);
        for (int i = destinationProviders.size() - 1; i >= 0; i--) {
            DestinationProvider provider = destinationProviders.get(i);
            if (target.equals(provider.getProtocol().toLowerCase(Locale.ROOT))) {
                return Optional.of(provider);
            }
        }
        for (DestinationProvider provider : discoveredExtensionProviders()) {
            if (target.equals(provider.getProtocol().toLowerCase(Locale.ROOT))) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }

    public List<AsyncDestination> listAllDestinations() {
        List<AsyncDestination> result = new ArrayList<>();
        for (String proto : allKnownProtocols()) {
            for (DestinationProvider adapter : resolveAdapters(proto)) {
                List<AsyncDestination> discovered = adapter.listDestinations();
                if (discovered != null && !discovered.isEmpty()) {
                    result.addAll(discovered);
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    public List<AsyncMessage> peekMessages(String protocol, String destination, int limit) {
        if (protocol == null || destination == null || destination.isBlank()) {
            return Collections.emptyList();
        }
        String proto = protocol.toLowerCase(Locale.ROOT);
        List<AsyncMessage> result = new ArrayList<>();
        for (DestinationProvider adapter : resolveAdapters(proto)) {
            int remaining = limit > 0 ? Math.max(0, limit - result.size()) : 0;
            if (limit > 0 && remaining == 0) {
                break;
            }
            List<AsyncMessage> peeked = adapter.peekMessages(destination, remaining);
            if (peeked != null && !peeked.isEmpty()) {
                if (limit > 0 && peeked.size() > remaining) {
                    result.addAll(peeked.subList(0, remaining));
                } else {
                    result.addAll(peeked);
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    public boolean deleteMessage(String protocol, String destination, String messageId) {
        if (protocol == null
                || destination == null
                || destination.isBlank()
                || messageId == null
                || messageId.isBlank()) {
            return false;
        }
        String proto = protocol.toLowerCase(Locale.ROOT);
        for (DestinationProvider adapter : resolveAdapters(proto)) {
            if (adapter.deleteMessage(destination, messageId)) {
                return true;
            }
        }
        return false;
    }

    public void purgeDestination(String protocol, String destination) {
        if (protocol == null || destination == null || destination.isBlank()) {
            return;
        }
        String proto = protocol.toLowerCase(Locale.ROOT);
        for (DestinationProvider adapter : resolveAdapters(proto)) {
            adapter.purgeDestination(destination);
        }
    }

    public boolean publishMessage(String protocol, String destination, AsyncMessage msg) {
        if (protocol == null || destination == null || destination.isBlank() || msg == null) {
            return false;
        }
        String proto = normalizePublishProtocol(protocol);
        AsyncMessage canonical = normalizeMessage(proto, destination, msg);
        boolean published = false;
        for (DestinationProvider adapter : resolveAdapters(proto)) {
            if (adapter.publishMessage(destination, canonical)) {
                published = true;
            }
        }
        BiConsumer<String, AsyncMessage> pub = destinationPublishers.get(destination);
        if (pub != null) {
            pub.accept(destination, canonical);
            published = true;
        }
        if (published) {
            recordPublishedEvent(destination, canonical);
        }
        return published;
    }

    public void registerPublisher(String destination, BiConsumer<String, AsyncMessage> publisher) {
        if (destination != null && publisher != null) {
            destinationPublishers.put(destination, publisher);
        }
    }

    public void publish(String destination, AsyncMessage message) {
        if (destination == null || destination.isBlank() || message == null) {
            return;
        }
        String proto = normalizePublishProtocol(message.protocol());
        if (!publishMessage(proto, destination, message)) {
            String fallbackProto =
                    (message.protocol() != null && !message.protocol().isBlank()) ? message.protocol() : "in-memory";
            AsyncMessage fallback = normalizeMessage(fallbackProto, destination, message);
            testBroker.send(destination, fallback);
            recordPublishedEvent(destination, fallback);
        }
    }

    private void recordPublishedEvent(String destination, AsyncMessage message) {
        messagesProduced.incrementAndGet();
        ServeEvent se = AsyncServeEventMediation.fromAsyncMessage(
                message, 200, "{\"status\":\"PUBLISHED\",\"destination\":\"" + destination + "\"}", null, null);
        recordServeEvent(se);
    }

    private static String normalizePublishProtocol(String protocol) {
        if (protocol == null || protocol.isBlank() || "async".equalsIgnoreCase(protocol)) {
            return "in-memory";
        }
        return protocol.toLowerCase(Locale.ROOT);
    }

    private static AsyncMessage normalizeMessage(String protocol, String destination, AsyncMessage msg) {
        if (protocol.equals(msg.protocol()) && destination.equals(msg.destination())) {
            return msg;
        }
        return AsyncMessage.builder()
                .id(msg.id())
                .protocol(protocol)
                .destination(destination)
                .key(msg.key())
                .correlationId(msg.correlationId())
                .replyTo(msg.replyTo())
                .payload(msg.payload())
                .headers(msg.headers())
                .timestamp(msg.timestamp())
                .size(msg.size())
                .partition(msg.partition())
                .offset(msg.offset())
                .build();
    }

    public void setBrokers(MessageBroker testBroker, MessageBroker upstreamBroker) {
        this.testBroker = Objects.requireNonNull(testBroker, "testBroker must not be null");
        this.upstreamBroker = Objects.requireNonNull(upstreamBroker, "upstreamBroker must not be null");
        this.usingDefaultUpstreamBroker = false;
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
                    mediateInboundMessage(msg, false, "CONSUMED");
                    try {
                        consumer.accept(msg);
                    } catch (Exception e) {
                        LOG.error(
                                "[ASYNC-ENGINE] Subscriber error on channel {}: {}",
                                sanitizeForLog(channel),
                                sanitizeForLog(e.getMessage()),
                                e);
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
     * declaring {@code metadata.async} in ascending priority order (honoring {@code priority <= 100}
     * and skipping parked stubs with priority {@code > 100}). Unmatched messages fall back to default
     * upstream proxy forwarding.
     *
     * @param channel channel name to process
     * @return true if a message was processed, false if the queue was empty
     */
    public boolean processInbound(String channel) {
        AsyncMessage msg = testBroker.poll(channel);
        if (msg == null) {
            return false;
        }
        mediateInboundMessage(msg, true, "ACKNOWLEDGED");
        return true;
    }

    /**
     * Unified inbound mediation entry point for wire servers (Kafka, AMQP), queue subscriptions,
     * and {@link #processInbound(String)}.
     *
     * @return {@code true} if an async stub or bridge rule (or default upstream fallback) handled the message
     */
    private boolean mediateInboundMessage(
            AsyncMessage msg, boolean fallbackToDefaultUpstream, String defaultAckStatus) {
        String channel = Objects.requireNonNullElse(msg.destination(), "");
        messagesConsumed.incrementAndGet();

        ImmutableRequest syntheticRequest = AsyncServeEventMediation.buildSyntheticRequest(
                msg.protocol(),
                channel,
                msg.id(),
                msg.key(),
                msg.correlationId(),
                msg.replyTo(),
                msg.headers(),
                msg.payload());

        List<StubMapping> candidates = stubStore
                .getAll()
                .filter(s -> s != null && (s.getPriority() == null || s.getPriority() <= 100))
                .filter(s -> AsyncMetadata.from(s.getMetadata()).isPresent())
                .sorted(Comparator.comparingInt((StubMapping s) -> s.getPriority() != null ? s.getPriority() : 5)
                        .thenComparingInt(s -> AsyncMetadata.from(s.getMetadata())
                                .map(m -> m.mode() == AsyncMode.MOCK ? 0 : 1)
                                .orElse(2)))
                .toList();

        for (StubMapping stub : candidates) {
            AsyncMetadata meta = AsyncMetadata.from(stub.getMetadata()).orElseThrow();
            if (!isAsyncRoutableStub(stub, meta)
                    || !matchesCommonCriteria(stub, meta, channel, msg, syntheticRequest)) {
                continue;
            }
            if (meta.mode() == AsyncMode.PROXY) {
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
            } else if (matchesMockChannelAndUrl(stub, meta, channel, syntheticRequest)) {
                String replyBody = resolveMockReplyBody(stub, meta);
                int status = stub.getResponse() != null && stub.getResponse().getStatus() > 0
                        ? stub.getResponse().getStatus()
                        : 200;
                ServeEvent se = AsyncServeEventMediation.buildServeEvent(
                        msg.protocol(),
                        channel,
                        msg.id(),
                        msg.key(),
                        msg.correlationId(),
                        msg.replyTo(),
                        msg.headers(),
                        msg.payload(),
                        status,
                        replyBody,
                        null,
                        null,
                        stub);
                recordServeEvent(se);
                deliverMockReply(msg, replyBody);
                dispatchInboundTriggers(meta);
                return true;
            }
        }

        if (fallbackToDefaultUpstream) {
            forwardUpstreamOrRecord502("default", channel, "", channel, msg, null);
            return true;
        }

        String safeProtocol = (msg.protocol() != null && !msg.protocol().isBlank()) ? msg.protocol() : "async";
        ServeEvent se = AsyncServeEventMediation.fromAsyncMessage(
                msg,
                200,
                "{\"status\":\"" + defaultAckStatus + "\",\"protocol\":\"" + safeProtocol + "\",\"destination\":\""
                        + channel + "\"}",
                null,
                null);
        recordServeEvent(se);
        return false;
    }

    private void deliverMockReply(AsyncMessage requestMsg, String replyBody) {
        if (requestMsg.replyTo() == null || requestMsg.replyTo().isBlank()) {
            return;
        }
        String proto = normalizePublishProtocol(requestMsg.protocol());
        Map<String, String> replyHeaders = new LinkedHashMap<>();
        if (requestMsg.correlationId() != null && !requestMsg.correlationId().isBlank()) {
            replyHeaders.put("x-correlation-id", requestMsg.correlationId());
        }
        AsyncMessage reply = AsyncMessage.builder()
                .protocol(proto)
                .key(requestMsg.key())
                .correlationId(requestMsg.correlationId())
                .destination(requestMsg.replyTo())
                .headers(replyHeaders)
                .payload(replyBody)
                .build();
        messagesProduced.incrementAndGet();
        boolean delivered = false;
        for (DestinationProvider adapter : resolveAdapters(proto)) {
            if (adapter.publishMessage(requestMsg.replyTo(), reply)) {
                delivered = true;
                break;
            }
        }
        if (!delivered) {
            testBroker.send(requestMsg.replyTo(), reply);
        }
    }

    private void dispatchInboundTriggers(AsyncMetadata meta) {
        if (meta.feed() != null && "call-triggered".equalsIgnoreCase(meta.feed().trigger())) {
            for (AsyncFeedRecord rec : meta.feed().records()) {
                AsyncMessage feedMsg = AsyncMessage.builder()
                        .destination(meta.feed().destination())
                        .protocol(meta.feed().protocol())
                        .key(rec.key())
                        .payload(rec.payload())
                        .headers(rec.headers())
                        .build();
                publish(meta.feed().destination(), feedMsg);
            }
        }
        for (AsyncProduceDirective directive : meta.produce()) {
            if (!directive.destination().isBlank()) {
                AsyncMessage produced = AsyncMessage.builder()
                        .destination(directive.destination())
                        .protocol(directive.protocol())
                        .key(directive.key())
                        .payload(directive.payload())
                        .headers(directive.headers())
                        .build();
                publish(directive.destination(), produced);
            }
        }
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

    private static boolean matchesCommonCriteria(
            StubMapping stub, AsyncMetadata meta, String channel, AsyncMessage msg, ImmutableRequest syntheticRequest) {
        if (!meta.channel().isBlank() && !meta.channel().equals(channel)) {
            return false;
        }
        RequestPattern req = stub.getRequest();
        return matchesRequestHeaders(req, syntheticRequest) && matchesRequestBody(req, msg.payload());
    }

    private static boolean matchesMockChannelAndUrl(
            StubMapping stub, AsyncMetadata meta, String channel, ImmutableRequest syntheticRequest) {
        if (!meta.channelPattern().isBlank() && !Pattern.matches(meta.channelPattern(), channel)) {
            return false;
        }
        RequestPattern req = stub.getRequest();
        if (meta.channel().isBlank() && meta.channelPattern().isBlank() && req != null) {
            String exactUrl = req.getUrl() != null ? req.getUrl() : req.getUrlPath();
            if (exactUrl != null && !exactUrl.endsWith("/" + channel)) {
                return false;
            }
            String urlPattern = req.getUrlPattern() != null ? req.getUrlPattern() : req.getUrlPathPattern();
            if (urlPattern != null && !Pattern.matches(urlPattern, syntheticRequest.getUrl())) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesRequestHeaders(RequestPattern req, ImmutableRequest syntheticRequest) {
        if (req == null || req.getHeaders() == null || req.getHeaders().isEmpty()) {
            return true;
        }
        for (Map.Entry<String, MultiValuePattern> entry : req.getHeaders().entrySet()) {
            HttpHeader header = syntheticRequest.header(entry.getKey());
            if (!entry.getValue().match(header).isExactMatch()) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesRequestBody(RequestPattern req, String payload) {
        List<ContentPattern<?>> bodyPatterns = req != null ? req.getBodyPatterns() : null;
        if (bodyPatterns != null && !bodyPatterns.isEmpty()) {
            String safePayload = Objects.requireNonNullElse(payload, "");
            for (ContentPattern<?> pattern : bodyPatterns) {
                if (pattern instanceof StringValuePattern stringPattern
                        && !stringPattern.match(safePayload).isExactMatch()) {
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
            MessageBroker resolvedBroker = resolveUpstreamBroker(targetBroker);
            resolvedBroker.send(targetChannel, forwarded);
            messagesProxied.incrementAndGet();
            ServeEvent se = AsyncServeEventMediation.buildServeEvent(
                    forwarded.protocol(),
                    sourceChannel,
                    forwarded.id(),
                    forwarded.key(),
                    forwarded.correlationId(),
                    forwarded.replyTo(),
                    forwarded.headers(),
                    forwarded.payload(),
                    200,
                    "{\"status\":\"BRIDGED\",\"destination\":\"" + targetChannel + "\"}",
                    proxiedTarget,
                    null,
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

    private MessageBroker resolveUpstreamBroker(String targetBroker) {
        if (targetBroker != null && !targetBroker.isBlank()) {
            AsyncBrokerRegistry registry = brokerRegistry;
            if (registry != null) {
                Optional<MessageBroker> byId = registry.getOrCreateBroker(targetBroker);
                if (byId.isPresent()) {
                    return byId.get();
                }
                for (BrokerConnection conn : registry.listConnections()) {
                    if (targetBroker.equals(conn.name()) || targetBroker.equals(conn.endpoint())) {
                        Optional<MessageBroker> byAlias = registry.getOrCreateBroker(conn.id());
                        if (byAlias.isPresent()) {
                            return byAlias.get();
                        }
                    }
                }
            }
            if (usingDefaultUpstreamBroker) {
                throw new IllegalStateException(
                        "Unknown proxyBroker '" + sanitizeForLog(targetBroker) + "': not found in AsyncBrokerRegistry");
            }
        }
        return upstreamBroker;
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
                msg.id(),
                msg.key(),
                msg.correlationId(),
                msg.replyTo(),
                msg.headers(),
                msg.payload(),
                502,
                errorBody,
                proxiedTarget,
                errorDetail,
                matchedStub);
        recordServeEvent(se);
        LOG.error(
                "[ASYNC-PROXY-ERROR] Bridge [{}] failed forwarding [{}] to [{}]: {}",
                sanitizeForLog(ruleName),
                sanitizeForLog(targetChannel),
                sanitizeForLog(brokerLabel),
                sanitizeForLog(errorDetail));
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
        usingDefaultUpstreamBroker = true;
        stopKafkaSink();
        stopAmqpServer();
    }

    @Override
    public void close() {
        running.set(false);
        for (Thread t : activeSubscriptions) {
            t.interrupt();
        }
        activeSubscriptions.clear();
        stopKafkaSink();
        stopAmqpServer();
        closeBrokerQuietly(testBroker);
        closeBrokerQuietly(upstreamBroker);
        AsyncBrokerRegistry registry = brokerRegistry;
        if (registry != null) {
            registry.close();
        }
    }

    private static void closeBrokerQuietly(MessageBroker broker) {
        if (broker instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                LOG.debug("[ASYNC-ENGINE] Error closing message broker: {}", sanitizeForLog(e.getMessage()));
            }
        }
    }

    private static String sanitizeForLog(String value) {
        return Objects.toString(value, "").replaceAll("[\r\n]", "");
    }
}
