package com.github.mattiasgreen.wiremock.async.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.async.bridge.InMemoryMessageBroker;
import com.github.mattiasgreen.wiremock.async.connection.AsyncBrokerRegistry;
import com.github.mattiasgreen.wiremock.async.connection.BrokerConnection;
import com.github.mattiasgreen.wiremock.async.destination.AsyncDestination;
import com.github.mattiasgreen.wiremock.async.destination.AsyncDestinationMessage;
import com.github.mattiasgreen.wiremock.async.destination.AsyncDestinationService;
import com.github.mattiasgreen.wiremock.async.engine.AsyncEngine;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("kafka-integration")
@Testcontainers(disabledWithoutDocker = true)
class KafkaIntegrationTest {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));

    private static String bootstrapServers;

    @BeforeAll
    static void captureBootstrap() {
        bootstrapServers = KAFKA.getBootstrapServers();
    }

    @Test
    void shouldProduceAndPeekAcrossMultiplePartitionsWithoutCommittingOffsets() throws Exception {
        String topic = "orders.events";
        createTopic(topic, 3);

        try (KafkaMessageBroker broker = new KafkaMessageBroker("it-multi-part", bootstrapServers)) {
            for (int i = 0; i < 12; i++) {
                broker.send(
                        topic,
                        AsyncMessage.builder()
                                .destination(topic)
                                .key("partition-key-" + i)
                                .payload("{\"seq\":" + i + ",\"status\":\"CREATED\"}")
                                .headers(Map.of("x-correlation-id", "corr-" + i, "x-tenant-id", "emea-retail"))
                                .build());
            }

            assertThat(broker.size(topic)).isEqualTo(12);

            List<AsyncDestinationMessage> peeked = broker.peekDestinationMessages(topic, 20);
            assertThat(peeked).hasSize(12);

            Set<Integer> seenPartitions = new HashSet<>();
            for (AsyncDestinationMessage msg : peeked) {
                seenPartitions.add(msg.partition());
                assertThat(msg.protocol()).isEqualTo("kafka");
                assertThat(msg.offset()).isNotNull().isGreaterThanOrEqualTo(0L);
                assertThat(msg.headers()).containsEntry("x-tenant-id", "emea-retail");
                assertThat(msg.correlationId()).startsWith("corr-");
            }
            assertThat(seenPartitions.size()).isGreaterThanOrEqualTo(2);

            // Detached browse must leave zero consumer groups committed
            try (AdminClient admin =
                    AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
                assertThat(admin.listConsumerGroups().all().get(5, TimeUnit.SECONDS))
                        .isEmpty();
            }

            // Bounded limit returns most recent N records
            assertThat(broker.peekDestinationMessages(topic, 3)).hasSize(3);
        }
    }

    @Test
    void shouldDiscoverTopicsPeekPublishAndPurgeViaAsyncDestinationService() throws Exception {
        String topic = "payments.events";
        createTopic(topic, 2);

        AsyncBrokerRegistry registry = AsyncBrokerRegistry.createForTest();
        registry.register(new BrokerConnection(
                "it-kafka-cluster",
                "kafka",
                "Testcontainers Kafka",
                bootstrapServers,
                Map.of("client.id", "wm-it-client")));

        try (AsyncEngine engine = AsyncEngine.create(
                new InMemoryMessageBroker("local-test"), new InMemoryMessageBroker("local-upstream"))) {
            AsyncDestinationService service = AsyncDestinationService.createForTest(engine, registry);

            List<AsyncDestination> discovered = service.listAllDestinations();
            assertThat(discovered).anySatisfy(dest -> {
                assertThat(dest.name()).isEqualTo(topic);
                assertThat(dest.protocol()).isEqualTo("kafka");
                assertThat(dest.brokerId()).isEqualTo("it-kafka-cluster");
                assertThat(dest.metadata()).containsEntry("discovery", "ADMIN_CLIENT");
            });

            AsyncDestinationMessage compensating = new AsyncDestinationMessage(
                    "edit-1",
                    "kafka",
                    topic,
                    "PAY-8801",
                    "corr-pay-8801",
                    null,
                    Instant.now(),
                    42,
                    Map.of("x-compensated", "true"),
                    "{\"paymentId\":\"PAY-8801\",\"status\":\"SETTLED\"}",
                    null,
                    null);

            assertThat(service.publishMessage("kafka", topic, compensating)).isTrue();

            List<AsyncDestinationMessage> messages = service.peekMessages("kafka", topic, 10);
            assertThat(messages).hasSize(1);
            assertThat(messages.get(0).key()).isEqualTo("PAY-8801");
            assertThat(messages.get(0).correlationId()).isEqualTo("corr-pay-8801");
            assertThat(messages.get(0).headers()).containsEntry("x-compensated", "true");
            assertThat(messages.get(0).payload()).contains("SETTLED");

            service.purgeDestination("kafka", topic);
            assertThat(service.peekMessages("kafka", topic, 10)).isEmpty();
        }
    }

    @Test
    void shouldForwardUnhandledTrafficThroughPriorityBridgeToRealKafka() throws Exception {
        String upstreamTopic = "upstream.orders.processed";
        createTopic(upstreamTopic, 1);

        InMemoryMessageBroker testBroker = new InMemoryMessageBroker("local-test");
        try (KafkaMessageBroker upstreamKafka = new KafkaMessageBroker("upstream-kafka", bootstrapServers);
                AsyncEngine engine = AsyncEngine.create(testBroker, upstreamKafka)) {
            engine.addBridgeRule(
                    "kafka-upstream-bridge", 100, "orders\\.outbound\\.(.*)", "upstream-kafka", "upstream.orders.$1");

            AsyncMessage outbound = AsyncMessage.builder()
                    .destination("orders.outbound.processed")
                    .key("ORD-BRIDGE-77")
                    .payload("{\"orderId\":\"ORD-BRIDGE-77\",\"bridged\":true}")
                    .headers(Map.of("x-trace-id", "trace-77"))
                    .build();

            testBroker.send("orders.outbound.processed", outbound);
            engine.processInbound("orders.outbound.processed");

            List<AsyncMessage> upstreamPeek = upstreamKafka.peek(upstreamTopic);
            assertThat(upstreamPeek).hasSize(1);
            assertThat(upstreamPeek.get(0).key()).isEqualTo("ORD-BRIDGE-77");
            assertThat(upstreamPeek.get(0).payload()).contains("ORD-BRIDGE-77");
            assertThat(upstreamPeek.get(0).headers()).containsEntry("x-trace-id", "trace-77");
        }
    }

    private static void createTopic(String topic, int partitions) throws Exception {
        try (AdminClient admin =
                AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1)))
                    .all()
                    .get(10, TimeUnit.SECONDS);
        }
    }
}
