package com.github.mattiasgreen.wiremock.async.ibmmq;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.async.bridge.InMemoryMessageBroker;
import com.github.mattiasgreen.wiremock.async.connection.AsyncBrokerRegistry;
import com.github.mattiasgreen.wiremock.async.connection.BrokerConnection;
import com.github.mattiasgreen.wiremock.async.destination.AsyncDestination;
import com.github.mattiasgreen.wiremock.async.engine.AsyncEngine;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("ibmmq-integration")
@Testcontainers(disabledWithoutDocker = true)
class IbmMqIntegrationTest {

    private static final String QMGR = "QM1";
    private static final String APP_USER = "app";
    private static final String ADMIN_USER = "admin";
    private static final String PASSWORD = "passw0rd";

    @Container
    static final GenericContainer<?> IBM_MQ = new GenericContainer<>(
                    DockerImageName.parse("icr.io/ibm-messaging/mq:latest"))
            .withEnv("LICENSE", "accept")
            .withEnv("MQ_QMGR_NAME", QMGR)
            .withEnv("MQ_APP_PASSWORD", PASSWORD)
            .withEnv("MQ_ADMIN_PASSWORD", PASSWORD)
            .withExposedPorts(1414)
            .waitingFor(Wait.forLogMessage(".*AMQ5806I.*", 1).withStartupTimeout(Duration.ofMinutes(2)));

    private static String endpoint;

    @BeforeAll
    static void captureEndpoint() {
        endpoint = IBM_MQ.getHost() + ":" + IBM_MQ.getMappedPort(1414);
    }

    @Test
    void shouldSendPeekPollDeleteAndPurgeMessagesViaAppChannel() {
        AsyncBrokerRegistry registry = AsyncBrokerRegistry.createForTest();
        BrokerConnection appConn = new BrokerConnection(
                "it-ibmmq-app",
                "ibmmq",
                "IBM MQ App Channel",
                endpoint,
                Map.of(
                        "queueManager", QMGR,
                        "channel", "DEV.APP.SVRCONN",
                        "username", APP_USER,
                        "password", PASSWORD,
                        "queues", "DEV.QUEUE.1,DEV.QUEUE.2"));
        registry.register(appConn);

        try (AsyncEngine engine = AsyncEngine.create(
                new InMemoryMessageBroker("test"), new InMemoryMessageBroker("upstream"), registry)) {
            engine.purgeDestination("ibmmq", "DEV.QUEUE.1");
            engine.purgeDestination("ibmmq", "DEV.QUEUE.2");

            try (IbmMqMessageBroker broker = new IbmMqMessageBroker(appConn)) {
                AsyncMessage outbound = AsyncMessage.builder()
                        .destination("DEV.QUEUE.1")
                        .correlationId("corr-ibmmq-9001")
                        .replyTo("DEV.QUEUE.2")
                        .payload("{\"orderId\":\"ORD-MQ-9001\",\"amount\":149.50}")
                        .headers(Map.of("x-tenant-id", "nordics-bank", "x-source", "wiremock-it"))
                        .build();

                broker.send("DEV.QUEUE.1", outbound);

                // Non-destructive browse via engine must see the message without consuming it
                List<AsyncMessage> peeked = engine.peekMessages("ibmmq", "DEV.QUEUE.1", 10);
                assertThat(peeked).hasSize(1);
                AsyncMessage msg = peeked.get(0);
                assertThat(msg.protocol()).isEqualTo("ibmmq");
                assertThat(msg.payload()).contains("ORD-MQ-9001");
                assertThat(msg.correlationId()).isEqualTo("corr-ibmmq-9001");
                assertThat(msg.replyTo()).isEqualTo("DEV.QUEUE.2");
                assertThat(msg.headers()).containsEntry("x_tenant_id", "nordics-bank");

                // Second peek must still see 1 message
                assertThat(engine.peekMessages("ibmmq", "DEV.QUEUE.1", 10)).hasSize(1);

                // Destructive poll consumes it
                AsyncMessage polled = broker.poll("DEV.QUEUE.1");
                assertThat(polled).isNotNull();
                assertThat(polled.payload()).contains("ORD-MQ-9001");
                assertThat(broker.poll("DEV.QUEUE.1")).isNull();
            }

            // Compensating publish + selective delete + purge via engine
            AsyncMessage staged1 = AsyncMessage.builder()
                    .id("custom-1")
                    .protocol("ibmmq")
                    .destination("DEV.QUEUE.1")
                    .correlationId("corr-edit-1")
                    .timestamp(Instant.now())
                    .headers(Map.of("x-edit", "true"))
                    .payload("{\"compensated\":true}")
                    .build();
            AsyncMessage staged2 = AsyncMessage.builder()
                    .id("custom-2")
                    .protocol("ibmmq")
                    .destination("DEV.QUEUE.1")
                    .correlationId("corr-edit-2")
                    .timestamp(Instant.now())
                    .payload("{\"compensated\":false}")
                    .build();

            assertThat(engine.publishMessage("ibmmq", "DEV.QUEUE.1", staged1)).isTrue();
            assertThat(engine.publishMessage("ibmmq", "DEV.QUEUE.1", staged2)).isTrue();

            List<AsyncMessage> afterPublish = engine.peekMessages("ibmmq", "DEV.QUEUE.1", 10);
            assertThat(afterPublish).hasSize(2);

            String targetJmsId = afterPublish.get(0).id();
            assertThat(engine.deleteMessage("ibmmq", "DEV.QUEUE.1", targetJmsId))
                    .isTrue();
            assertThat(engine.peekMessages("ibmmq", "DEV.QUEUE.1", 10)).hasSize(1);

            engine.purgeDestination("ibmmq", "DEV.QUEUE.1");
            assertThat(engine.peekMessages("ibmmq", "DEV.QUEUE.1", 10)).isEmpty();
        }
    }

    @Test
    void shouldDiscoverQueuesAndLiveDepthsViaPcfOnAdminChannel() {
        AsyncBrokerRegistry registry = AsyncBrokerRegistry.createForTest();
        BrokerConnection adminConn = new BrokerConnection(
                "it-ibmmq-admin",
                "ibmmq",
                "IBM MQ Admin Channel",
                endpoint,
                Map.of(
                        "queueManager", QMGR,
                        "channel", "DEV.ADMIN.SVRCONN",
                        "username", ADMIN_USER,
                        "password", PASSWORD));
        registry.register(adminConn);

        try (AsyncEngine engine = AsyncEngine.create(
                new InMemoryMessageBroker("test"), new InMemoryMessageBroker("upstream"), registry)) {
            engine.purgeDestination("ibmmq", "DEV.QUEUE.3");

            try (IbmMqMessageBroker broker = new IbmMqMessageBroker(adminConn)) {
                broker.send(
                        "DEV.QUEUE.3",
                        AsyncMessage.builder()
                                .destination("DEV.QUEUE.3")
                                .payload("<payment id=\"PCF-1\"/>")
                                .build());

                List<AsyncDestination> discovered = engine.listAllDestinations();
                assertThat(discovered)
                        .extracting(AsyncDestination::name)
                        .contains("DEV.QUEUE.1", "DEV.QUEUE.2", "DEV.QUEUE.3")
                        .noneMatch(IbmMqConnectionConfig::isSystemQueue);

                AsyncDestination q3 = discovered.stream()
                        .filter(d -> "DEV.QUEUE.3".equals(d.name()))
                        .findFirst()
                        .orElseThrow();
                assertThat(q3.messageCount()).isEqualTo(1);
                assertThat(q3.protocol()).isEqualTo("ibmmq");
                assertThat(q3.brokerId()).isEqualTo("it-ibmmq-admin");
                assertThat(q3.metadata()).containsEntry("discovery", "PCF");

                engine.purgeDestination("ibmmq", "DEV.QUEUE.3");
                assertThat(broker.size("DEV.QUEUE.3")).isZero();
            }
        }
    }
}
