package com.github.mattiasgreen.wiremock.async.destination;

import com.github.mattiasgreen.wiremock.async.bridge.MessageBroker;
import com.github.mattiasgreen.wiremock.async.connection.AsyncBrokerRegistry;
import com.github.mattiasgreen.wiremock.async.connection.BrokerConnection;
import com.github.mattiasgreen.wiremock.async.engine.AsyncEngine;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Domain service aggregating queue and topic operations across asynchronous protocols.
 * Decoupled via {@link DestinationProvider} adapters for in-memory, Kafka, AMQP, and Casual.
 */
public final class AsyncDestinationService {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncDestinationService.class);
    private static final AsyncDestinationService INSTANCE = new AsyncDestinationService();

    private final Map<String, DestinationProvider> providers = new ConcurrentHashMap<>();

    private AsyncDestinationService() {
        this(AsyncEngine.getInstance(), AsyncBrokerRegistry.getInstance());
    }

    private AsyncDestinationService(AsyncEngine engine, AsyncBrokerRegistry registry) {
        Objects.requireNonNull(engine, "engine must not be null");
        Objects.requireNonNull(registry, "registry must not be null");
        DestinationProvider inMem = new InMemoryDestinationProvider(engine);
        providers.put(inMem.getProtocol().toLowerCase(Locale.ROOT), inMem);
        DestinationProvider kafka = new KafkaDestinationProvider(engine, registry);
        providers.put(kafka.getProtocol().toLowerCase(Locale.ROOT), kafka);
        DestinationProvider amqp = new AmqpDestinationProvider(engine, registry);
        providers.put(amqp.getProtocol().toLowerCase(Locale.ROOT), amqp);

        try {
            Class.forName("com.ibm.mq.jms.MQConnectionFactory");
            Class<?> ibmMqProviderCls =
                    Class.forName("com.github.mattiasgreen.wiremock.async.ibmmq.IbmMqDestinationProvider");
            DestinationProvider ibmMq = (DestinationProvider) ibmMqProviderCls
                    .getDeclaredConstructor(AsyncBrokerRegistry.class)
                    .newInstance(registry);
            providers.put(ibmMq.getProtocol().toLowerCase(Locale.ROOT), ibmMq);
            LOG.info("[DESTINATION-SERVICE] Auto-registered IBM MQ destination provider via reflection");
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Optional IBM MQ client library not in classpath
        }
    }

    public static AsyncDestinationService getInstance() {
        return INSTANCE;
    }

    public static AsyncDestinationService createForTest(AsyncEngine engine) {
        return new AsyncDestinationService(engine, AsyncBrokerRegistry.getInstance());
    }

    public static AsyncDestinationService createForTest(AsyncEngine engine, AsyncBrokerRegistry registry) {
        return new AsyncDestinationService(engine, registry);
    }

    public final void registerProvider(DestinationProvider provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        String proto = provider.getProtocol().toLowerCase(Locale.ROOT);
        providers.put(proto, provider);
        LOG.info("[DESTINATION-SERVICE] Registered destination provider for protocol [{}]", proto);
    }

    public Optional<DestinationProvider> getProvider(String protocol) {
        if (protocol == null) return Optional.empty();
        return Optional.ofNullable(providers.get(protocol.toLowerCase(Locale.ROOT)));
    }

    public List<AsyncDestination> listAllDestinations() {
        List<AsyncDestination> result = new ArrayList<>();
        for (DestinationProvider p : providers.values()) {
            result.addAll(p.listDestinations());
        }
        return Collections.unmodifiableList(result);
    }

    public List<AsyncDestinationMessage> peekMessages(String protocol, String name, int limit) {
        DestinationProvider p = providers.get(protocol.toLowerCase(Locale.ROOT));
        if (p == null) {
            return Collections.emptyList();
        }
        return p.peekMessages(name, limit);
    }

    public boolean deleteMessage(String protocol, String name, String messageId) {
        DestinationProvider p = providers.get(protocol.toLowerCase(Locale.ROOT));
        if (p == null) {
            return false;
        }
        return p.deleteMessage(name, messageId);
    }

    public void purgeDestination(String protocol, String name) {
        DestinationProvider p = providers.get(protocol.toLowerCase(Locale.ROOT));
        if (p != null) {
            p.purgeDestination(name);
        }
    }

    public boolean publishMessage(String protocol, String name, AsyncDestinationMessage message) {
        DestinationProvider p = providers.get(protocol.toLowerCase(Locale.ROOT));
        if (p == null) {
            return false;
        }
        return p.publishMessage(name, message);
    }

    /**
     * Default provider managing in-memory queues in {@link AsyncEngine}.
     */
    private static class InMemoryDestinationProvider implements DestinationProvider {

        private final AsyncEngine engine;

        InMemoryDestinationProvider(AsyncEngine engine) {
            this.engine = engine;
        }

        @Override
        public String getProtocol() {
            return "in-memory";
        }

        @Override
        public List<AsyncDestination> listDestinations() {
            List<AsyncDestination> list = new ArrayList<>();
            List<String> channels = engine.getTestBroker().listChannels();
            for (String ch : channels) {
                int count = engine.getTestBroker().size(ch);
                list.add(new AsyncDestination(
                        "in-memory:" + ch, "in-memory", ch, "local-broker", count, Map.of("type", "FIFO")));
            }
            return list;
        }

        @Override
        public List<AsyncDestinationMessage> peekMessages(String destination, int limit) {
            List<AsyncMessage> raw = engine.getTestBroker().peek(destination);
            List<AsyncDestinationMessage> result = new ArrayList<>();
            int max = limit > 0 ? Math.min(limit, raw.size()) : raw.size();
            for (int i = 0; i < max; i++) {
                AsyncMessage msg = raw.get(i);
                result.add(new AsyncDestinationMessage(
                        msg.id() != null ? msg.id() : UUID.randomUUID().toString(),
                        "in-memory",
                        destination,
                        msg.key(),
                        msg.correlationId(),
                        msg.replyTo(),
                        msg.timestamp(),
                        msg.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                        msg.headers(),
                        msg.payload(),
                        null,
                        null));
            }
            return result;
        }

        @Override
        public boolean deleteMessage(String destination, String messageId) {
            return engine.getTestBroker().delete(destination, messageId);
        }

        @Override
        public void purgeDestination(String destination) {
            engine.getTestBroker().purge(destination);
        }

        @Override
        public boolean publishMessage(String destination, AsyncDestinationMessage msg) {
            AsyncMessage m = AsyncMessage.builder()
                    .id(msg.id())
                    .correlationId(msg.correlationId())
                    .replyTo(msg.replyTo())
                    .destination(destination)
                    .protocol("in-memory")
                    .key(msg.key())
                    .payload(msg.payload())
                    .headers(msg.headers())
                    .timestamp(Instant.now())
                    .build();
            engine.publish(destination, m);
            return true;
        }
    }

    /**
     * Provider managing Kafka topics across both the local wire sink and registered remote Kafka clusters.
     */
    private static class KafkaDestinationProvider implements DestinationProvider {

        private final AsyncEngine engine;
        private final AsyncBrokerRegistry registry;

        KafkaDestinationProvider(AsyncEngine engine, AsyncBrokerRegistry registry) {
            this.engine = engine;
            this.registry = registry;
        }

        @Override
        public String getProtocol() {
            return "kafka";
        }

        @Override
        public List<AsyncDestination> listDestinations() {
            List<AsyncDestination> list = new ArrayList<>();
            if (engine.getKafkaSink() != null) {
                Map<String, Integer> topicCounts = new HashMap<>();
                for (com.github.mattiasgreen.wiremock.async.kafka.KafkaProduceRecord r :
                        engine.getKafkaSink().getStagedRecords()) {
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
            for (Map.Entry<BrokerConnection, MessageBroker> entry :
                    registry.getOrCreateBrokersByProtocol("kafka").entrySet()) {
                BrokerConnection conn = entry.getKey();
                MessageBroker broker = entry.getValue();
                for (String topic : broker.listChannels()) {
                    int count = broker.size(topic);
                    list.add(new AsyncDestination(
                            "kafka:" + conn.id() + ":" + topic,
                            "kafka",
                            topic,
                            conn.id(),
                            count,
                            Map.of("type", "TOPIC", "discovery", "ADMIN_CLIENT")));
                }
            }
            return list;
        }

        @Override
        public List<AsyncDestinationMessage> peekMessages(String destination, int limit) {
            List<AsyncDestinationMessage> result = new ArrayList<>();
            if (engine.getKafkaSink() != null) {
                int count = 0;
                for (com.github.mattiasgreen.wiremock.async.kafka.KafkaProduceRecord r :
                        engine.getKafkaSink().getStagedRecords()) {
                    if (r.topic().equals(destination)) {
                        result.add(new AsyncDestinationMessage(
                                UUID.randomUUID().toString(),
                                "kafka",
                                destination,
                                r.key() != null ? r.key() : "",
                                "",
                                "",
                                Instant.now(),
                                r.value().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                                Map.of(),
                                r.value(),
                                0,
                                (long) count));
                        count++;
                        if (limit > 0 && count >= limit) break;
                    }
                }
            }
            for (MessageBroker broker :
                    registry.getOrCreateBrokersByProtocol("kafka").values()) {
                int remaining = limit > 0 ? Math.max(0, limit - result.size()) : 0;
                if (limit > 0 && remaining == 0) {
                    break;
                }
                result.addAll(broker.peekDestinationMessages(destination, "kafka", remaining));
            }
            return result;
        }

        @Override
        public boolean deleteMessage(String destination, String messageId) {
            return engine.getKafkaSink() != null && engine.getKafkaSink().deleteStagedRecord(destination, messageId);
        }

        @Override
        public void purgeDestination(String destination) {
            if (engine.getKafkaSink() != null) {
                engine.getKafkaSink().purgeTopic(destination);
            }
            for (MessageBroker broker :
                    registry.getOrCreateBrokersByProtocol("kafka").values()) {
                broker.purge(destination);
            }
        }

        @Override
        public boolean publishMessage(String destination, AsyncDestinationMessage msg) {
            boolean published = false;
            if (engine.getKafkaSink() != null) {
                engine.stageKafkaFeed(com.github.mattiasgreen.wiremock.async.kafka.KafkaProduceRecord.of(
                        destination, msg.key(), msg.payload()));
                published = true;
            }
            for (MessageBroker broker :
                    registry.getOrCreateBrokersByProtocol("kafka").values()) {
                Map<String, String> headers = new HashMap<>();
                if (msg.headers() != null) {
                    headers.putAll(msg.headers());
                }
                if (msg.correlationId() != null && !msg.correlationId().isBlank()) {
                    headers.putIfAbsent("x-correlation-id", msg.correlationId());
                }
                AsyncMessage outbound = AsyncMessage.builder()
                        .id(msg.id())
                        .key(msg.key())
                        .destination(destination)
                        .correlationId(msg.correlationId())
                        .replyTo(msg.replyTo())
                        .payload(msg.payload())
                        .headers(headers)
                        .timestamp(msg.timestamp() != null ? msg.timestamp() : Instant.now())
                        .build();
                broker.send(destination, outbound);
                published = true;
            }
            return published;
        }
    }

    /**
     * Provider managing AMQP queues across both the local AMQP server and pooled remote AMQP connections.
     */
    private static class AmqpDestinationProvider implements DestinationProvider {

        private final AsyncEngine engine;
        private final AsyncBrokerRegistry registry;

        AmqpDestinationProvider(AsyncEngine engine, AsyncBrokerRegistry registry) {
            this.engine = engine;
            this.registry = registry;
        }

        @Override
        public String getProtocol() {
            return "amqp";
        }

        @Override
        public List<AsyncDestination> listDestinations() {
            List<AsyncDestination> list = new ArrayList<>();
            if (engine.getAmqpServer() != null) {
                for (String q : engine.getAmqpServer().listQueues()) {
                    int size = engine.getAmqpServer().peekQueue(q).size();
                    list.add(new AsyncDestination(
                            "amqp:" + q, "amqp", q, "local-amqp-server", size, Map.of("type", "QUEUE")));
                }
            }
            for (Map.Entry<BrokerConnection, MessageBroker> entry :
                    registry.getOrCreateBrokersByProtocol("amqp").entrySet()) {
                BrokerConnection conn = entry.getKey();
                MessageBroker broker = entry.getValue();
                for (String q : broker.listChannels()) {
                    list.add(new AsyncDestination(
                            "amqp:" + conn.id() + ":" + q,
                            "amqp",
                            q,
                            conn.id(),
                            broker.size(q),
                            Map.of("type", "QUEUE")));
                }
                String configuredQueues = conn.properties().getOrDefault("queues", "");
                if (!configuredQueues.isBlank()) {
                    for (String token : configuredQueues.split(",")) {
                        String q = token.trim();
                        if (!q.isEmpty()) {
                            list.add(new AsyncDestination(
                                    "amqp:" + conn.id() + ":" + q,
                                    "amqp",
                                    q,
                                    conn.id(),
                                    0,
                                    Map.of("type", "QUEUE", "discovery", "CONFIGURED")));
                        }
                    }
                }
            }
            return list;
        }

        @Override
        public List<AsyncDestinationMessage> peekMessages(String destination, int limit) {
            List<AsyncDestinationMessage> result = new ArrayList<>();
            if (engine.getAmqpServer() != null) {
                List<AsyncMessage> messages = engine.getAmqpServer().peekQueue(destination);
                int max = limit > 0 ? Math.min(limit, messages.size()) : messages.size();
                for (int i = 0; i < max; i++) {
                    AsyncMessage m = messages.get(i);
                    result.add(new AsyncDestinationMessage(
                            m.id(),
                            "amqp",
                            destination,
                            m.key(),
                            m.correlationId(),
                            m.replyTo(),
                            m.timestamp(),
                            m.payload().getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                            m.headers(),
                            m.payload(),
                            null,
                            null));
                }
            }
            for (MessageBroker broker :
                    registry.getOrCreateBrokersByProtocol("amqp").values()) {
                int remaining = limit > 0 ? Math.max(0, limit - result.size()) : 0;
                if (limit > 0 && remaining == 0) {
                    break;
                }
                result.addAll(broker.peekDestinationMessages(destination, "amqp", remaining));
            }
            return result;
        }

        @Override
        public boolean deleteMessage(String destination, String messageId) {
            return engine.getAmqpServer() != null && engine.getAmqpServer().deleteMessage(destination, messageId);
        }

        @Override
        public void purgeDestination(String destination) {
            if (engine.getAmqpServer() != null) {
                engine.getAmqpServer().purgeQueue(destination);
            }
            for (MessageBroker broker :
                    registry.getOrCreateBrokersByProtocol("amqp").values()) {
                broker.purge(destination);
            }
        }

        @Override
        public boolean publishMessage(String destination, AsyncDestinationMessage msg) {
            boolean published = false;
            AsyncMessage m = AsyncMessage.builder()
                    .id(msg.id())
                    .destination(destination)
                    .correlationId(msg.correlationId())
                    .replyTo(msg.replyTo())
                    .payload(msg.payload())
                    .headers(msg.headers())
                    .timestamp(Instant.now())
                    .build();
            if (engine.getAmqpServer() != null) {
                engine.stageAmqpFeed(destination, m);
                published = true;
            }
            for (MessageBroker broker :
                    registry.getOrCreateBrokersByProtocol("amqp").values()) {
                broker.send(destination, m);
                published = true;
            }
            return published;
        }
    }
}
