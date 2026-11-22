package com.github.mattiasgreen.wiremock.async.engine;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.async.bridge.InMemoryMessageBroker;
import com.github.mattiasgreen.wiremock.async.connection.BrokerConnection;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.qpid.protonj2.client.Client;
import org.apache.qpid.protonj2.client.Connection;
import org.apache.qpid.protonj2.client.Delivery;
import org.apache.qpid.protonj2.client.Message;
import org.apache.qpid.protonj2.client.Receiver;
import org.apache.qpid.protonj2.client.Sender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AsyncInboundWireMediationTest {

    private AsyncEngine engine;
    private List<ServeEvent> capturedEvents;

    @BeforeEach
    void setUp() {
        engine = AsyncEngine.create();
        capturedEvents = new CopyOnWriteArrayList<>();
        engine.setJournalConsumer(capturedEvents::add);
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    @DisplayName(
            "Real KafkaProducer messages flow through unified inbound mediation honoring headers, priority 100 bridge, and proxyBroker registry lookup")
    void shouldMediateRealKafkaProducerMessagesWithHeadersAndPriority100ProxyBroker() throws Exception {
        InMemoryMessageBroker registeredUpstream = new InMemoryMessageBroker("registered-kafka-upstream");
        engine.getBrokerRegistry().registerBrokerFactory("in-memory-upstream", conn -> registeredUpstream);
        engine.getBrokerRegistry()
                .register(new BrokerConnection(
                        "upstream-reg", "in-memory-upstream", "Upstream Registry Broker", "mem://upstream", Map.of()));

        // High-priority mock stub requiring both X-Tenant header and body match
        StubMapping vipStub = post(urlMatching("/__async/.*"))
                .withName("vip-kafka-mock")
                .atPriority(5)
                .withHeader("X-Tenant", equalTo("vip"))
                .withRequestBody(containing("VIP"))
                .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"VIP_MOCKED\"}"))
                .withMetadata(Map.of("async", Map.of("mode", "mock", "protocol", "kafka", "channel", "orders.inbound")))
                .build();
        engine.getStubStore().add(vipStub);

        // Documented priority 100 bridge rule targeting registered proxyBroker
        engine.addBridgeRule("Priority 100 Kafka Bridge", 100, "orders\\.(.*)", "upstream-reg", "bridged.$1");

        // Parked stub at priority 999 must be ignored
        engine.addBridgeRule("Parked Bridge", 999, "orders\\.(.*)", "unreachable-broker", "MISSING.PARKED");

        engine.startKafkaSink(0, null);
        int port = engine.getKafkaSinkPort();

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:" + port);
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            // 1. VIP message with matching header -> intercepted by mock stub, not forwarded upstream
            ProducerRecord<String, String> vipRecord =
                    new ProducerRecord<>("orders.inbound", "k-vip", "{\"tier\":\"VIP\"}");
            vipRecord.headers().add("X-Tenant", "vip".getBytes(StandardCharsets.UTF_8));
            producer.send(vipRecord).get(5, TimeUnit.SECONDS);

            // 2. Non-VIP message (wrong header) -> misses mock stub, bridged by priority-100 rule to upstream-reg
            ProducerRecord<String, String> stdRecord =
                    new ProducerRecord<>("orders.inbound", "k-std", "{\"tier\":\"VIP\",\"note\":\"wrong-tenant\"}");
            stdRecord.headers().add("X-Tenant", "standard".getBytes(StandardCharsets.UTF_8));
            producer.send(stdRecord).get(5, TimeUnit.SECONDS);
        }

        // Verify VIP message was mocked and NOT forwarded
        assertThat(registeredUpstream.size("bridged.inbound")).isEqualTo(1);
        AsyncMessage bridged = registeredUpstream.poll("bridged.inbound");
        assertThat(bridged).isNotNull();
        assertThat(bridged.key()).isEqualTo("k-std");
        assertThat(bridged.payload()).contains("wrong-tenant");

        // Verify canonical ServeEvent headers on both events
        assertThat(capturedEvents).hasSize(2);
        assertThat(capturedEvents).anySatisfy(se -> {
            assertThat(se.getWasMatched()).isTrue();
            assertThat(se.getStubMapping().getName()).isEqualTo("vip-kafka-mock");
            assertThat(se.getRequest().header("X-Async-Protocol").firstValue()).isEqualTo("kafka");
            assertThat(se.getRequest().header("X-Async-Channel").firstValue()).isEqualTo("orders.inbound");
            assertThat(se.getRequest().header("X-Async-Message-Id").isPresent()).isTrue();
            assertThat(se.getResponse().getBodyAsString()).isEqualTo("{\"status\":\"VIP_MOCKED\"}");
        });
        assertThat(capturedEvents).anySatisfy(se -> {
            assertThat(se.getResponseDefinition().isProxyResponse()).isTrue();
            assertThat(se.getResponseDefinition().getProxyBaseUrl()).contains("upstream-reg/bridged.inbound");
            assertThat(se.getRequest().header("X-Async-Protocol").firstValue()).isEqualTo("kafka");
            assertThat(se.getRequest().header("X-Async-Channel").firstValue()).isEqualTo("orders.inbound");
        });
    }

    @Test
    @DisplayName(
            "Real Proton-J2 AMQP client messages flow through unified inbound mediation with reply staging and 502 error on remote failure")
    void shouldMediateRealAmqpMessagesAndEmit502OnRemoteBridgeFailure() throws Exception {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }

        engine.getBrokerRegistry()
                .register(new BrokerConnection(
                        "remote-dead-amqp", "amqp", "Dead AMQP", "amqp://127.0.0.1:" + unusedPort, Map.of()));

        // High-priority mock stub for AMQP with header matcher
        StubMapping mockStub = post(urlMatching("/__async/.*"))
                .withName("amqp-premium-mock")
                .atPriority(5)
                .withHeader("X-Tenant", equalTo("acme"))
                .withRequestBody(containing("PREMIUM"))
                .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"AMQP_MOCK_OK\"}"))
                .withMetadata(Map.of("async", Map.of("mode", "mock", "protocol", "amqp", "channel", "orders.amqp.in")))
                .build();
        engine.getStubStore().add(mockStub);

        // Priority 100 bridge rule pointing to unreachable remote AMQP broker in registry
        engine.addBridgeRule("Dead AMQP Bridge", 100, "orders\\.amqp\\.(.*)", "remote-dead-amqp", "upstream.$1");

        engine.startAmqpServer(0);
        int amqpPort = engine.getAmqpServerPort();

        try (Client client = Client.create();
                Connection conn = client.connect("127.0.0.1", amqpPort)) {
            // 1. Matching message with replyTo -> mock reply staged on AmqpServer
            Sender sender = conn.openSender("orders.amqp.in");
            Message<byte[]> mockReq = Message.create("{\"tier\":\"PREMIUM\"}".getBytes(StandardCharsets.UTF_8));
            mockReq.messageId("amqp-msg-1");
            mockReq.correlationId("corr-amqp-1");
            mockReq.replyTo("orders.amqp.replies");
            mockReq.property("X-Tenant", "acme");
            sender.send(mockReq);

            Receiver replyReceiver = conn.openReceiver("orders.amqp.replies");
            Delivery replyDelivery = replyReceiver.receive(5, TimeUnit.SECONDS);
            assertThat(replyDelivery).isNotNull();
            String replyBody = new String((byte[]) replyDelivery.message().body(), StandardCharsets.UTF_8);
            assertThat(replyBody).isEqualTo("{\"status\":\"AMQP_MOCK_OK\"}");
            assertThat(replyDelivery.message().correlationId()).isEqualTo("corr-amqp-1");
            replyDelivery.accept();
            replyReceiver.close();

            // 2. Non-matching message -> hits priority-100 bridge -> remote AmqpMessageBroker fails -> 502 with
            // X-Async-Error
            Message<byte[]> failReq = Message.create("{\"tier\":\"STANDARD\"}".getBytes(StandardCharsets.UTF_8));
            failReq.messageId("amqp-msg-2");
            failReq.property("X-Tenant", "other");
            sender.send(failReq);
            sender.close();
        }

        // Wait briefly for the 502 event to be recorded
        long deadline = System.currentTimeMillis() + 5000;
        while (engine.getProxyErrors() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        assertThat(engine.getProxyErrors()).isEqualTo(1);
        assertThat(capturedEvents).anySatisfy(se -> {
            assertThat(se.getResponseDefinition().getStatus()).isEqualTo(502);
            assertThat(se.getResponse().getHeaders().getHeader("X-Async-Error").isPresent())
                    .isTrue();
            assertThat(se.getResponse().getHeaders().getHeader("X-Async-Error").firstValue())
                    .contains("AMQP");
            assertThat(se.getRequest().header("X-Async-Protocol").firstValue()).isEqualTo("amqp");
            assertThat(se.getRequest().header("X-Async-Channel").firstValue()).isEqualTo("orders.amqp.in");
            assertThat(se.getRequest().header("X-Async-Message-Id").firstValue())
                    .isEqualTo("amqp-msg-2");
        });
    }

    @Test
    @DisplayName("Kafka mock reply routed via DestinationProvider preserves correlationId and key")
    void shouldPreserveCorrelationIdOnKafkaMockReply() throws Exception {
        StubMapping mockStub = post(urlMatching("/__async/.*"))
                .withName("kafka-reply-mock")
                .atPriority(5)
                .withRequestBody(containing("PING"))
                .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"PONG\"}"))
                .withMetadata(Map.of("async", Map.of("mode", "mock", "protocol", "kafka", "channel", "orders.req")))
                .build();
        engine.getStubStore().add(mockStub);

        engine.startKafkaSink(0, null);
        int port = engine.getKafkaSinkPort();

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:" + port);
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            ProducerRecord<String, String> req = new ProducerRecord<>("orders.req", "key-42", "{\"op\":\"PING\"}");
            req.headers().add("x-correlation-id", "corr-kafka-99".getBytes(StandardCharsets.UTF_8));
            req.headers().add("x-reply-to", "orders.replies".getBytes(StandardCharsets.UTF_8));
            producer.send(req).get(5, TimeUnit.SECONDS);
        }

        List<AsyncMessage> replies = engine.peekMessages("kafka", "orders.replies", 10);
        assertThat(replies).hasSize(1);
        AsyncMessage reply = replies.get(0);
        assertThat(reply.payload()).isEqualTo("{\"status\":\"PONG\"}");
        assertThat(reply.key()).isEqualTo("key-42");
        assertThat(reply.correlationId()).isEqualTo("corr-kafka-99");
        assertThat(reply.headers()).containsEntry("x-correlation-id", "corr-kafka-99");
    }

    @Test
    @DisplayName("Unknown explicit proxyBroker on default engine records 502 with X-Async-Error")
    void shouldRecord502WhenExplicitProxyBrokerIsUnknown() {
        engine.addBridgeRule("Unknown Broker Rule", 10, "orders\\.(.*)", "missing-corp-broker", "upstream.$1");

        engine.getTestBroker()
                .send(
                        "orders.eu",
                        AsyncMessage.builder()
                                .protocol("in-memory")
                                .destination("orders.eu")
                                .payload("{\"id\":1}")
                                .build());

        assertThat(engine.processInbound("orders.eu")).isTrue();
        assertThat(engine.getProxyErrors()).isEqualTo(1);
        assertThat(capturedEvents).hasSize(1);
        ServeEvent errorEvent = capturedEvents.get(0);
        assertThat(errorEvent.getResponseDefinition().getStatus()).isEqualTo(502);
        assertThat(errorEvent
                        .getResponse()
                        .getHeaders()
                        .getHeader("X-Async-Error")
                        .firstValue())
                .contains("Unknown proxyBroker")
                .contains("missing-corp-broker");
    }
}
