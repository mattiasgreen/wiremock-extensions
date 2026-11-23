package com.github.mattiasgreen.wiremock.async.destination;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.async.bridge.InMemoryMessageBroker;
import com.github.mattiasgreen.wiremock.async.connection.AsyncBrokerRegistry;
import com.github.mattiasgreen.wiremock.async.connection.BrokerConnection;
import com.github.mattiasgreen.wiremock.async.engine.AsyncEngine;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AsyncDestinationServiceTest {

    private AsyncEngine engine;

    @BeforeEach
    void setUp() {
        InMemoryMessageBroker testBroker = new InMemoryMessageBroker("test-broker");
        InMemoryMessageBroker upstreamBroker = new InMemoryMessageBroker("upstream-broker");
        engine = AsyncEngine.create(testBroker, upstreamBroker, AsyncBrokerRegistry.createForTest());
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    @DisplayName("Should list in-memory destinations, peek messages, delete single message, and purge")
    void shouldManageInMemoryDestinations() {
        String channel = "orders.inbound";
        AsyncMessage m1 = AsyncMessage.builder()
                .id("msg-1")
                .destination(channel)
                .payload("{\"orderId\": 101}")
                .timestamp(Instant.now())
                .build();
        AsyncMessage m2 = AsyncMessage.builder()
                .id("msg-2")
                .destination(channel)
                .payload("{\"orderId\": 102}")
                .timestamp(Instant.now())
                .build();

        engine.publish(channel, m1);
        engine.publish(channel, m2);

        List<AsyncDestination> destinations = engine.listAllDestinations();
        assertThat(destinations).hasSize(1);
        assertThat(destinations.get(0).name()).isEqualTo(channel);
        assertThat(destinations.get(0).messageCount()).isEqualTo(2);

        // Peek
        List<AsyncMessage> peeked = engine.peekMessages("in-memory", channel, 10);
        assertThat(peeked).hasSize(2);
        assertThat(peeked.get(0).id()).isEqualTo("msg-1");
        assertThat(peeked.get(1).id()).isEqualTo("msg-2");

        // Delete single message
        boolean deleted = engine.deleteMessage("in-memory", channel, "msg-1");
        assertThat(deleted).isTrue();
        assertThat(engine.peekMessages("in-memory", channel, 10)).hasSize(1);

        // Purge
        engine.purgeDestination("in-memory", channel);
        assertThat(engine.peekMessages("in-memory", channel, 10)).isEmpty();
    }

    @Test
    @DisplayName("Should peek Kafka wire-server record with stable recordId and headers, and delete by returned id")
    void shouldPeekKafkaRecordWithStableIdAndDeleteByReturnedId() throws Exception {
        engine.startKafkaSink(0, r -> {});

        AsyncMessage published = AsyncMessage.builder()
                .protocol("kafka")
                .destination("orders.events")
                .key("order-42")
                .correlationId("corr-42")
                .replyTo("orders.replies")
                .headers(Map.of("x-tenant", "acme"))
                .payload("{\"orderId\":42}")
                .build();

        assertThat(engine.publishMessage("kafka", "orders.events", published)).isTrue();

        List<AsyncMessage> peeked = engine.peekMessages("kafka", "orders.events", 10);
        assertThat(peeked).hasSize(1);
        AsyncMessage record = peeked.get(0);
        assertThat(record.id()).isEqualTo("orders.events:0:0");
        assertThat(record.offset()).isEqualTo(0L);
        assertThat(record.partition()).isEqualTo(0);
        assertThat(record.headers()).containsEntry("x-tenant", "acme").containsEntry("x-correlation-id", "corr-42");

        // Deleting by the exact ID returned from peek must succeed
        assertThat(engine.deleteMessage("kafka", "orders.events", record.id())).isTrue();
        assertThat(engine.peekMessages("kafka", "orders.events", 10)).isEmpty();
    }

    @Test
    @DisplayName(
            "Should compose external DestinationProvider with built-in wire server and BrokerRegistry without shadowing")
    void shouldComposeExternalDestinationProviderWithBuiltInWireServerAndBrokerRegistry() throws Exception {
        // 1. Local AMQP wire server
        engine.startAmqpServer(0);
        engine.stageAmqpFeed(
                "wire.queue",
                AsyncMessage.builder()
                        .id("wire-1")
                        .protocol("amqp")
                        .destination("wire.queue")
                        .payload("from-wire")
                        .build());

        // 2. Pooled remote AMQP broker in AsyncBrokerRegistry
        InMemoryMessageBroker remoteAmqpBroker = new InMemoryMessageBroker("remote-amqp");
        remoteAmqpBroker.send(
                "remote.queue",
                AsyncMessage.builder()
                        .id("remote-1")
                        .protocol("amqp")
                        .destination("remote.queue")
                        .payload("from-remote")
                        .build());
        engine.getBrokerRegistry().registerBrokerFactory("amqp", conn -> remoteAmqpBroker);
        engine.getBrokerRegistry()
                .register(new BrokerConnection("remote-amqp", "amqp", "Remote AMQP", "amqp://remote:5672", Map.of()));

        // 3. External DestinationProvider for "amqp" (e.g., Artemis)
        InMemoryExternalProvider artemisProvider = new InMemoryExternalProvider("amqp", "artemis-broker");
        artemisProvider.publishMessage(
                "artemis.queue",
                AsyncMessage.builder()
                        .id("artemis-1")
                        .protocol("amqp")
                        .destination("artemis.queue")
                        .payload("from-artemis")
                        .build());
        engine.registerDestinationProvider(artemisProvider);

        // Listing destinations must compose all 3 sources without shadowing
        List<AsyncDestination> destinations = engine.listAllDestinations();
        assertThat(destinations)
                .extracting(AsyncDestination::name)
                .contains("wire.queue", "remote.queue", "artemis.queue");

        // Peeking each destination routes to its owning adapter
        assertThat(engine.peekMessages("amqp", "wire.queue", 10))
                .extracting(AsyncMessage::id)
                .containsExactly("wire-1");
        assertThat(engine.peekMessages("amqp", "remote.queue", 10))
                .extracting(AsyncMessage::id)
                .containsExactly("remote-1");
        assertThat(engine.peekMessages("amqp", "artemis.queue", 10))
                .extracting(AsyncMessage::id)
                .containsExactly("artemis-1");

        // Publishing to a shared AMQP queue composes across all 3 adapters and increments messagesProduced once
        long producedBefore = engine.getMessagesProduced();
        AsyncMessage shared = AsyncMessage.builder()
                .id("shared-1")
                .protocol("amqp")
                .destination("shared.queue")
                .payload("hello-all")
                .build();
        assertThat(engine.publishMessage("amqp", "shared.queue", shared)).isTrue();
        assertThat(engine.getMessagesProduced()).isEqualTo(producedBefore + 1);
        assertThat(engine.peekMessages("amqp", "shared.queue", 10)).hasSize(3);
        assertThat(engine.peekMessages("amqp", "shared.queue", 2)).hasSize(2);

        // Deleting from individual sources works without shadowing
        assertThat(engine.deleteMessage("amqp", "wire.queue", "wire-1")).isTrue();
        assertThat(engine.deleteMessage("amqp", "remote.queue", "remote-1")).isTrue();
        assertThat(engine.deleteMessage("amqp", "artemis.queue", "artemis-1")).isTrue();

        // Purging shared queue clears all 3 composed adapters
        engine.purgeDestination("amqp", "shared.queue");
        assertThat(engine.peekMessages("amqp", "shared.queue", 10)).isEmpty();
    }

    @Test
    @DisplayName("Should not hold engine-wide synchronized monitor across blocking remote broker I/O")
    void shouldNotHoldEngineMonitorAcrossBlockingRemoteBrokerIo() throws Exception {
        CountDownLatch enteredBlockingIo = new CountDownLatch(1);
        CountDownLatch releaseBlockingIo = new CountDownLatch(1);

        engine.registerDestinationProvider(new DestinationProvider() {
            @Override
            public String getProtocol() {
                return "ibmmq";
            }

            @Override
            public List<AsyncDestination> listDestinations() {
                return Collections.emptyList();
            }

            @Override
            public List<AsyncMessage> peekMessages(String destination, int limit) {
                enteredBlockingIo.countDown();
                try {
                    releaseBlockingIo.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Collections.emptyList();
            }

            @Override
            public boolean deleteMessage(String destination, String messageId) {
                return false;
            }

            @Override
            public void purgeDestination(String destination) {}

            @Override
            public boolean publishMessage(String destination, AsyncMessage message) {
                return false;
            }
        });

        Thread slowIoThread = Thread.ofVirtual().start(() -> engine.peekMessages("ibmmq", "SLOW.Q", 10));
        assertThat(enteredBlockingIo.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            // While slowIoThread is blocked in remote I/O, fast operations on other protocols must NOT block
            AsyncMessage fastMsg = AsyncMessage.builder()
                    .id("fast-1")
                    .protocol("in-memory")
                    .destination("fast.q")
                    .payload("fast-payload")
                    .build();
            assertThat(engine.publishMessage("in-memory", "fast.q", fastMsg)).isTrue();
            assertThat(engine.peekMessages("in-memory", "fast.q", 10))
                    .extracting(AsyncMessage::id)
                    .containsExactly("fast-1");
            assertThat(engine.deleteMessage("in-memory", "fast.q", "fast-1")).isTrue();
            engine.purgeDestination("in-memory", "fast.q");
        } finally {
            releaseBlockingIo.countDown();
            slowIoThread.join(5_000);
        }
        assertThat(slowIoThread.isAlive()).isFalse();
    }

    private static final class InMemoryExternalProvider implements DestinationProvider {
        private final String protocol;
        private final String brokerId;
        private final Map<String, List<AsyncMessage>> store = new ConcurrentHashMap<>();

        private InMemoryExternalProvider(String protocol, String brokerId) {
            this.protocol = protocol;
            this.brokerId = brokerId;
        }

        @Override
        public String getProtocol() {
            return protocol;
        }

        @Override
        public List<AsyncDestination> listDestinations() {
            List<AsyncDestination> list = new ArrayList<>();
            for (Map.Entry<String, List<AsyncMessage>> entry : store.entrySet()) {
                list.add(new AsyncDestination(
                        brokerId + ":" + entry.getKey(),
                        protocol,
                        entry.getKey(),
                        brokerId,
                        entry.getValue().size(),
                        Map.of()));
            }
            return list;
        }

        @Override
        public List<AsyncMessage> peekMessages(String destination, int limit) {
            List<AsyncMessage> msgs = store.getOrDefault(destination, Collections.emptyList());
            if (limit > 0 && msgs.size() > limit) {
                return new ArrayList<>(msgs.subList(0, limit));
            }
            return new ArrayList<>(msgs);
        }

        @Override
        public boolean deleteMessage(String destination, String messageId) {
            List<AsyncMessage> msgs = store.get(destination);
            return msgs != null && msgs.removeIf(m -> messageId.equals(m.id()));
        }

        @Override
        public void purgeDestination(String destination) {
            List<AsyncMessage> msgs = store.get(destination);
            if (msgs != null) {
                msgs.clear();
            }
        }

        @Override
        public boolean publishMessage(String destination, AsyncMessage message) {
            store.computeIfAbsent(destination, k -> new CopyOnWriteArrayList<>())
                    .add(message);
            return true;
        }
    }
}
