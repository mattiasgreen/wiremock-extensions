package com.github.mattiasgreen.wiremock.async.destination;

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
        this(AsyncEngine.getInstance());
    }

    private AsyncDestinationService(AsyncEngine engine) {
        Objects.requireNonNull(engine, "engine must not be null");
        DestinationProvider inMem = new InMemoryDestinationProvider(engine);
        providers.put(inMem.getProtocol().toLowerCase(Locale.ROOT), inMem);
        DestinationProvider kafka = new KafkaDestinationProvider(engine);
        providers.put(kafka.getProtocol().toLowerCase(Locale.ROOT), kafka);
        DestinationProvider amqp = new AmqpDestinationProvider(engine);
        providers.put(amqp.getProtocol().toLowerCase(Locale.ROOT), amqp);

        try {
            Class.forName("com.github.mattiasgreen.wiremock.casual.CasualRegistry");
            DestinationProvider casual = new CasualReflectionDestinationProvider();
            providers.put(casual.getProtocol().toLowerCase(Locale.ROOT), casual);
            LOG.info("[DESTINATION-SERVICE] Auto-registered Casual destination provider via reflection");
        } catch (ClassNotFoundException ignored) {
            // Casual extension not in classpath
        }

        try {
            Class.forName("com.ibm.mq.jms.MQConnectionFactory");
            Class<?> ibmMqProviderCls =
                    Class.forName("com.github.mattiasgreen.wiremock.async.ibmmq.IbmMqDestinationProvider");
            DestinationProvider ibmMq = (DestinationProvider)
                    ibmMqProviderCls.getDeclaredConstructor().newInstance();
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
        return new AsyncDestinationService(engine);
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
     * Provider managing Kafka topics and staged feeds.
     */
    private static class KafkaDestinationProvider implements DestinationProvider {

        private final AsyncEngine engine;

        KafkaDestinationProvider(AsyncEngine engine) {
            this.engine = engine;
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
        }

        @Override
        public boolean publishMessage(String destination, AsyncDestinationMessage msg) {
            if (engine.getKafkaSink() != null) {
                engine.stageKafkaFeed(com.github.mattiasgreen.wiremock.async.kafka.KafkaProduceRecord.of(
                        destination, msg.key(), msg.payload()));
                return true;
            }
            return false;
        }
    }

    /**
     * Provider managing AMQP queues and staged feeds.
     */
    private static class AmqpDestinationProvider implements DestinationProvider {

        private final AsyncEngine engine;

        AmqpDestinationProvider(AsyncEngine engine) {
            this.engine = engine;
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
        }

        @Override
        public boolean publishMessage(String destination, AsyncDestinationMessage msg) {
            if (engine.getAmqpServer() != null) {
                AsyncMessage m = AsyncMessage.builder()
                        .id(msg.id())
                        .destination(destination)
                        .correlationId(msg.correlationId())
                        .replyTo(msg.replyTo())
                        .payload(msg.payload())
                        .headers(msg.headers())
                        .timestamp(Instant.now())
                        .build();
                engine.stageAmqpFeed(destination, m);
                return true;
            }
            return false;
        }
    }

    /**
     * Provider managing Casual XATMI queues via reflection when Casual extension is present.
     */
    private static class CasualReflectionDestinationProvider implements DestinationProvider {

        CasualReflectionDestinationProvider() {}

        @Override
        public String getProtocol() {
            return "casual";
        }

        private Object getQueueStore() {
            try {
                Class<?> registryCls = Class.forName("com.github.mattiasgreen.wiremock.casual.CasualRegistry");
                Object reg = registryCls.getMethod("getInstance").invoke(null);
                Optional<?> gwOpt =
                        (Optional<?>) registryCls.getMethod("getGateway").invoke(reg);
                if (gwOpt.isPresent()) {
                    Object gw = gwOpt.get();
                    return gw.getClass().getMethod("getQueueStore").invoke(gw);
                }
            } catch (ReflectiveOperationException e) {
                LOG.debug("[CASUAL-PROVIDER] Queue store unavailable via reflection: {}", e.getMessage());
            }
            return null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<AsyncDestination> listDestinations() {
            List<AsyncDestination> list = new ArrayList<>();
            Object store = getQueueStore();
            if (store != null) {
                try {
                    List<String> queues = (List<String>)
                            store.getClass().getMethod("listQueues").invoke(store);
                    for (String q : queues) {
                        int count = (int) store.getClass()
                                .getMethod("getQueueSize", String.class)
                                .invoke(store, q);
                        list.add(new AsyncDestination(
                                "casual:" + q,
                                "casual",
                                q,
                                "local-casual-gateway",
                                count,
                                Map.of("dlq", q.endsWith(".error"))));
                    }
                } catch (ReflectiveOperationException e) {
                    LOG.warn("[CASUAL-PROVIDER] Failed to list Casual queues: {}", e.getMessage());
                }
            }
            return list;
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<AsyncDestinationMessage> peekMessages(String destination, int limit) {
            List<AsyncDestinationMessage> result = new ArrayList<>();
            Object store = getQueueStore();
            if (store != null) {
                try {
                    List<?> messages = (List<?>) store.getClass()
                            .getMethod("peekMessages", String.class)
                            .invoke(store, destination);
                    int max = limit > 0 ? Math.min(limit, messages.size()) : messages.size();
                    for (int i = 0; i < max; i++) {
                        Object sm = messages.get(i);
                        UUID id = (UUID) sm.getClass().getMethod("getId").invoke(sm);
                        String corr = (String) sm.getClass()
                                .getMethod("getCorrelationInformation")
                                .invoke(sm);
                        String reply = (String)
                                sm.getClass().getMethod("getReplyQueue").invoke(sm);
                        Instant ts = (Instant)
                                sm.getClass().getMethod("getTimestamp").invoke(sm);
                        Object payloadObj =
                                sm.getClass().getMethod("getPayload").invoke(sm);
                        String payloadStr = "";
                        if (payloadObj != null) {
                            Class<?> utilsCls =
                                    Class.forName("com.github.mattiasgreen.wiremock.casual.CasualBufferUtils");
                            payloadStr = (String) utilsCls.getMethod("extractPayload", payloadObj.getClass())
                                    .invoke(null, payloadObj);
                        }
                        result.add(new AsyncDestinationMessage(
                                id.toString(),
                                "casual",
                                destination,
                                "",
                                corr,
                                reply,
                                ts,
                                payloadStr.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                                Map.of(),
                                payloadStr,
                                null,
                                null));
                    }
                } catch (ReflectiveOperationException e) {
                    LOG.warn("[CASUAL-PROVIDER] Failed to peek Casual messages: {}", e.getMessage());
                }
            }
            return result;
        }

        @Override
        public boolean deleteMessage(String destination, String messageId) {
            Object store = getQueueStore();
            if (store != null) {
                try {
                    return (boolean) store.getClass()
                            .getMethod("deleteMessage", String.class, UUID.class)
                            .invoke(store, destination, UUID.fromString(messageId));
                } catch (ReflectiveOperationException | IllegalArgumentException e) {
                    LOG.warn("[CASUAL-PROVIDER] Failed to delete Casual message: {}", e.getMessage());
                }
            }
            return false;
        }

        @Override
        public void purgeDestination(String destination) {
            Object store = getQueueStore();
            if (store != null) {
                try {
                    store.getClass().getMethod("purgeQueue", String.class).invoke(store, destination);
                } catch (ReflectiveOperationException e) {
                    LOG.warn("[CASUAL-PROVIDER] Failed to purge Casual queue: {}", e.getMessage());
                }
            }
        }

        @Override
        public boolean publishMessage(String destination, AsyncDestinationMessage msg) {
            return false;
        }
    }
}
