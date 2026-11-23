package com.github.mattiasgreen.wiremock.examples;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.async.admin.AsyncExtensionFactory;
import com.github.mattiasgreen.wiremock.async.amqp.AmqpMessageBroker;
import com.github.mattiasgreen.wiremock.async.amqp.AmqpServer;
import com.github.mattiasgreen.wiremock.async.bridge.InMemoryMessageBroker;
import com.github.mattiasgreen.wiremock.async.connection.BrokerConnection;
import com.github.mattiasgreen.wiremock.async.engine.AsyncEngine;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.ActiveMQServers;
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

class EmbeddedArtemisAmqpIntegrationTest {

    private int artemisPort;
    private ActiveMQServer artemisServer;
    private AmqpServer wiremockAmqpServer;
    private AmqpMessageBroker artemisOutboundBroker;
    private InMemoryMessageBroker localTestBroker;
    private AsyncEngine engine;
    private Client qpidClient;
    private List<ServeEvent> capturedServeEvents;

    @BeforeEach
    void setUp() throws Exception {
        artemisPort = findFreePort();

        // 1. Start embedded in-memory ActiveMQ Artemis broker
        ConfigurationImpl artemisConfig = new ConfigurationImpl();
        artemisConfig.setPersistenceEnabled(false);
        artemisConfig.setSecurityEnabled(false);
        artemisConfig.addAcceptorConfiguration("amqp", "tcp://127.0.0.1:" + artemisPort + "?protocols=AMQP");

        artemisServer = ActiveMQServers.newActiveMQServer(artemisConfig);
        artemisServer.start();
        artemisServer.createQueue(QueueConfiguration.of("upstream.orders.456").setRoutingType(RoutingType.ANYCAST));

        // 2. Start WireMock AMQP 1.0 Server & Engine
        AtomicReference<AsyncMessage> wiremockInbound = new AtomicReference<>();
        wiremockAmqpServer = new AmqpServer(0, wiremockInbound::set);
        wiremockAmqpServer.start();

        artemisOutboundBroker = new AmqpMessageBroker("artemis-upstream", "amqp://127.0.0.1:" + artemisPort);
        localTestBroker = new InMemoryMessageBroker("wiremock-test-broker");
        engine = AsyncEngine.create(localTestBroker, artemisOutboundBroker);
        capturedServeEvents = new CopyOnWriteArrayList<>();
        engine.setJournalConsumer(capturedServeEvents::add);

        qpidClient = Client.create();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (qpidClient != null) {
            qpidClient.close();
        }
        if (engine != null) {
            engine.close();
        }
        if (wiremockAmqpServer != null) {
            wiremockAmqpServer.close();
        }
        if (artemisOutboundBroker != null) {
            artemisOutboundBroker.close();
        }
        if (artemisServer != null && artemisServer.isStarted()) {
            artemisServer.stop();
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    @DisplayName("Should route standard messages to real Artemis broker with $1 rewriting and isolate VIP mocks")
    void shouldRouteToRealArtemisBrokerWithWildcardRewritingAndMockIsolation() throws Exception {
        // High priority rule (mock stub): Mock VIP orders completely
        engine.addMockStub("VIP-ORDER", "{\"orderId\":\"orders.vip.999\",\"status\":\"VIP_MOCKED\"}");

        // Active bridge fallback rule (priority < 100): Forward standard orders to Artemis queue upstream.orders.$1
        engine.addBridgeRule("Standard Orders Fallback", 20, "orders\\.standard\\.(.*)", "", "upstream.orders.$1");

        // 1. VIP Message -> Intercepted by WireMock Mock, 0 messages sent to Artemis
        AsyncMessage vipMsg = AsyncMessage.builder()
                .correlationId("corr-vip-999")
                .destination("orders.vip.999")
                .replyTo("orders.vip.replies")
                .payload("{\"type\":\"VIP-ORDER\",\"vipTier\":\"PLATINUM\",\"amount\":5000}")
                .headers(Map.of("x-tenant", "vip-club"))
                .build();

        localTestBroker.send("orders.vip.999", vipMsg);
        boolean vipProcessed = engine.processInbound("orders.vip.999");
        assertThat(vipProcessed).isTrue();

        // Local broker gets mock reply
        AsyncMessage vipReply = localTestBroker.poll("orders.vip.replies");
        assertThat(vipReply).isNotNull();
        assertThat(vipReply.payload()).contains("VIP_MOCKED");
        assertThat(vipReply.correlationId()).isEqualTo("corr-vip-999");

        // 2. Standard Message -> Captured, rewritten to upstream.orders.456, forwarded to real Artemis broker
        AsyncMessage stdMsg = AsyncMessage.builder()
                .correlationId("corr-std-456")
                .destination("orders.standard.456")
                .payload("{\"orderId\":\"456\",\"amount\":120}")
                .headers(Map.of("x-tenant", "retail", "x-channel", "web"))
                .build();

        localTestBroker.send("orders.standard.456", stdMsg);
        boolean stdProcessed = engine.processInbound("orders.standard.456");
        assertThat(stdProcessed).isTrue();

        // 3. Connect real Proton-J2 client directly to embedded Artemis broker to consume forwarded message
        try (Connection conn = qpidClient.connect("127.0.0.1", artemisPort)) {
            Receiver receiver = conn.openReceiver("upstream.orders.456");
            Delivery delivery = receiver.receive(5, TimeUnit.SECONDS);

            assertThat(delivery).isNotNull();
            Message<?> msg = delivery.message();
            byte[] bodyBytes = (byte[]) msg.body();
            String payload = new String(bodyBytes, StandardCharsets.UTF_8);

            assertThat(payload).isEqualTo("{\"orderId\":\"456\",\"amount\":120}");
            assertThat(msg.correlationId()).isEqualTo("corr-std-456");
            assertThat(msg.property("x-tenant")).isEqualTo("retail");
            assertThat(msg.property("x-channel")).isEqualTo("web");

            delivery.accept();
            receiver.close();
        }

        // Verify ServeEvent audit recorded the proxy forward
        assertThat(capturedServeEvents).anySatisfy(se -> {
            assertThat(se.getResponseDefinition().isProxyResponse()).isTrue();
            assertThat(se.getResponseDefinition().getProxyBaseUrl()).contains("upstream.orders.456");
        });
    }

    @Test
    @DisplayName("Should bridge polyglot payload (XML nested inside JSON) to Artemis broker intact")
    void shouldRouteAndBridgePolyglotXmlInsideJsonMessage() throws Exception {
        artemisServer.createQueue(QueueConfiguration.of("upstream.polyglot.789").setRoutingType(RoutingType.ANYCAST));

        // Rule to forward polyglot orders
        engine.addBridgeRule("Polyglot Orders", 50, "orders\\.polyglot\\.(.*)", "", "upstream.polyglot.$1");

        String nestedXml =
                "<trade xmlns=\"http://bank.example.com/trade\"><id>TRD-789</id><security>IBM</security><quantity>1000</quantity></trade>";
        String polyglotJson = "{\"tradeId\":\"TRD-789\",\"protocol\":\"FIX_XML\",\"xmlPayload\":\""
                + nestedXml.replace("\"", "\\\"") + "\"}";

        AsyncMessage polyglotMsg = AsyncMessage.builder()
                .correlationId("corr-poly-789")
                .destination("orders.polyglot.789")
                .payload(polyglotJson)
                .headers(Map.of("content-type", "application/json", "x-polyglot", "true"))
                .build();

        localTestBroker.send("orders.polyglot.789", polyglotMsg);
        boolean processed = engine.processInbound("orders.polyglot.789");
        assertThat(processed).isTrue();

        try (Connection conn = qpidClient.connect("127.0.0.1", artemisPort)) {
            Receiver receiver = conn.openReceiver("upstream.polyglot.789");
            Delivery delivery = receiver.receive(5, TimeUnit.SECONDS);

            assertThat(delivery).isNotNull();
            Message<?> msg = delivery.message();
            byte[] bodyBytes = (byte[]) msg.body();
            String payload = new String(bodyBytes, StandardCharsets.UTF_8);

            assertThat(payload).isEqualTo(polyglotJson);
            assertThat(payload).contains(nestedXml.replace("\"", "\\\""));
            assertThat(msg.correlationId()).isEqualTo("corr-poly-789");
            assertThat(msg.property("x-polyglot")).isEqualTo("true");

            delivery.accept();
            receiver.close();
        }

        // Verify ServeEvent audit recorded the polyglot forward
        assertThat(capturedServeEvents).anySatisfy(se -> {
            assertThat(se.getResponseDefinition().isProxyResponse()).isTrue();
            assertThat(se.getResponseDefinition().getProxyBaseUrl()).contains("upstream.polyglot.789");
        });
    }

    @Test
    @DisplayName(
            "Should publish AMQP message directly to Artemis queue via message broker producer and verify settlement")
    void shouldPublishAmqpMessageDirectlyToArtemisQueue() throws Exception {
        String queueName = "upstream.direct.publish";
        artemisServer.createQueue(QueueConfiguration.of(queueName).setRoutingType(RoutingType.ANYCAST));

        AsyncMessage outboundMsg = AsyncMessage.builder()
                .correlationId("corr-direct-publish-001")
                .destination(queueName)
                .payload("{\"action\":\"DISPATCH\",\"orderId\":\"ORD-8821\",\"amount\":349.99}")
                .headers(Map.of("tenant", "acme-corp", "x-priority", "urgent"))
                .build();

        // Act: publish directly via artemisOutboundBroker
        artemisOutboundBroker.send(queueName, outboundMsg);

        // Assert: consume from Artemis using Proton-J2 client and verify payload and application properties
        try (Connection conn = qpidClient.connect("127.0.0.1", artemisPort)) {
            Receiver receiver = conn.openReceiver(queueName);
            Delivery delivery = receiver.receive(5, TimeUnit.SECONDS);

            assertThat(delivery).isNotNull();
            Message<?> msg = delivery.message();
            byte[] bodyBytes = (byte[]) msg.body();
            String payload = new String(bodyBytes, StandardCharsets.UTF_8);

            assertThat(payload).contains("\"orderId\":\"ORD-8821\"");
            assertThat(msg.correlationId()).isEqualTo("corr-direct-publish-001");
            assertThat(msg.property("tenant")).isEqualTo("acme-corp");
            assertThat(msg.property("x-priority")).isEqualTo("urgent");

            delivery.accept();
            receiver.close();
        }
    }

    @Test
    @DisplayName(
            "Full AMQP wire-to-Artemis E2E bridge: Proton Sender -> AmqpServer -> Artemis -> stop Artemis -> 502 ServeEvent")
    void shouldBridgeAmqpWireToEmbeddedArtemisAndRecord502WhenArtemisStops() throws Exception {
        artemisServer.createQueue(QueueConfiguration.of("orders.upstream").setRoutingType(RoutingType.ANYCAST));

        AsyncEngine wireEngine = AsyncEngine.create();
        WireMockServer wm = new WireMockServer(
                WireMockConfiguration.options().dynamicPort().extensions(new AsyncExtensionFactory(wireEngine)));
        wm.start();
        try {
            wireEngine.startAmqpServer(0);
            int amqpWirePort = wireEngine.getAmqpServerPort();

            // Register embedded Artemis in AsyncBrokerRegistry as live-artemis
            wireEngine
                    .getBrokerRegistry()
                    .register(new BrokerConnection(
                            "live-artemis",
                            "amqp",
                            "Live Embedded Artemis",
                            "amqp://127.0.0.1:" + artemisPort,
                            Map.of()));

            // Priority 100 bridge stub rewriting orders.in -> orders.upstream
            wm.stubFor(post(urlEqualTo("/__async/amqp/orders.in"))
                    .withName("orders-live-artemis-bridge")
                    .atPriority(100)
                    .willReturn(aResponse().proxiedFrom("async://live-artemis"))
                    .withMetadata(Map.of(
                            "async",
                            Map.of(
                                    "mode", "proxy",
                                    "protocol", "amqp",
                                    "channel", "orders.in",
                                    "proxyBroker", "live-artemis",
                                    "proxyChannel", "orders.upstream"))));

            // 1. Send message with real Proton-J2 Sender to WireMock's AmqpServer
            try (Connection wmConn = qpidClient.connect("127.0.0.1", amqpWirePort)) {
                Sender sender = wmConn.openSender("orders.in");
                Message<byte[]> msg =
                        Message.create("{\"orderId\":\"ART-E2E-1\",\"amount\":99.50}".getBytes(StandardCharsets.UTF_8));
                msg.messageId("artemis-e2e-msg-1");
                msg.correlationId("corr-artemis-e2e-1");
                msg.property("x-tenant", "nordic-retail");
                sender.send(msg);
                sender.close();
            }

            // 2. Consume bridged message from embedded Artemis with real Proton-J2 Receiver
            try (Connection artemisConn = qpidClient.connect("127.0.0.1", artemisPort)) {
                Receiver receiver = artemisConn.openReceiver("orders.upstream");
                Delivery delivery = receiver.receive(5, TimeUnit.SECONDS);
                assertThat(delivery).isNotNull();
                Message<?> forwarded = delivery.message();
                String payload = new String((byte[]) forwarded.body(), StandardCharsets.UTF_8);
                assertThat(payload).isEqualTo("{\"orderId\":\"ART-E2E-1\",\"amount\":99.50}");
                assertThat(forwarded.correlationId()).isEqualTo("corr-artemis-e2e-1");
                assertThat(forwarded.property("x-tenant")).isEqualTo("nordic-retail");
                delivery.accept();
                receiver.close();
            }

            assertThat(wm.getAllServeEvents()).anySatisfy(se -> {
                assertThat(se.getResponseDefinition().isProxyResponse()).isTrue();
                assertThat(se.getRequest().header("X-Async-Protocol").firstValue())
                        .isEqualTo("amqp");
                assertThat(se.getRequest().header("X-Async-Channel").firstValue())
                        .isEqualTo("orders.in");
                assertThat(se.getResponseDefinition().getProxyBaseUrl()).contains("live-artemis/orders.upstream");
            });

            // 3. Stop embedded Artemis completely
            artemisServer.stop();

            // 4. Send another message to WireMock's AmqpServer -> expect 502 Synthetic Async ServeEvent
            try (Connection wmConn = qpidClient.connect("127.0.0.1", amqpWirePort)) {
                Sender sender = wmConn.openSender("orders.in");
                Message<byte[]> failMsg =
                        Message.create("{\"orderId\":\"ART-E2E-2\",\"amount\":15.00}".getBytes(StandardCharsets.UTF_8));
                failMsg.messageId("artemis-e2e-msg-2");
                sender.send(failMsg);
                sender.close();
            }

            long deadline = System.currentTimeMillis() + 5_000;
            while (wireEngine.getProxyErrors() < 1 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }

            assertThat(wireEngine.getProxyErrors()).isEqualTo(1);
            assertThat(wm.getAllServeEvents()).anySatisfy(se -> {
                assertThat(se.getResponseDefinition().getStatus()).isEqualTo(502);
                assertThat(se.getResponse()
                                .getHeaders()
                                .getHeader("X-Async-Error")
                                .isPresent())
                        .isTrue();
                assertThat(se.getResponse()
                                .getHeaders()
                                .getHeader("X-Async-Error")
                                .firstValue())
                        .contains("AMQP");
                assertThat(se.getRequest().header("X-Async-Protocol").firstValue())
                        .isEqualTo("amqp");
                assertThat(se.getRequest().header("X-Async-Channel").firstValue())
                        .isEqualTo("orders.in");
                assertThat(se.getRequest().header("X-Async-Message-Id").firstValue())
                        .isEqualTo("artemis-e2e-msg-2");
            });
        } finally {
            wm.stop();
        }
    }
}
