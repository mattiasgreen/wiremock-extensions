package com.github.mattiasgreen.wiremock.bundle;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import com.github.mattiasgreen.wiremock.async.amqp.AmqpMessageBroker;
import com.github.mattiasgreen.wiremock.async.connection.AsyncBrokerRegistry;
import com.github.mattiasgreen.wiremock.async.connection.BrokerConnection;
import com.github.mattiasgreen.wiremock.async.destination.AsyncDestination;
import com.github.mattiasgreen.wiremock.async.destination.AsyncDestinationMessage;
import com.github.mattiasgreen.wiremock.async.destination.AsyncDestinationService;
import com.github.mattiasgreen.wiremock.async.destination.DestinationProvider;
import com.github.mattiasgreen.wiremock.async.engine.AsyncEngine;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.core.config.Configuration;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.ActiveMQServers;

public class StandaloneDevServerWithArtemis {

    public static final int ARTEMIS_AMQP_PORT = 61616;

    public static void main(String[] args) throws Exception {
        int port = StandaloneDevServer.parseMainPort(args, 8080);
        String rootDir = StandaloneDevServer.parseRootDir(args, ".");

        System.out.println("=================================================================");
        System.out.println("🚀 Starting Embedded ActiveMQ Artemis AMQP 1.0 Broker...");

        File tempDir = Files.createTempDirectory("artemis-embedded-dev").toFile();
        tempDir.deleteOnExit();

        Configuration artemisConfig = new ConfigurationImpl()
                .setPersistenceEnabled(false)
                .setSecurityEnabled(false)
                .setJournalDirectory(new File(tempDir, "journal").getAbsolutePath())
                .setBindingsDirectory(new File(tempDir, "bindings").getAbsolutePath())
                .setLargeMessagesDirectory(new File(tempDir, "largemessages").getAbsolutePath())
                .setPagingDirectory(new File(tempDir, "paging").getAbsolutePath())
                .addAcceptorConfiguration("amqp", "tcp://127.0.0.1:" + ARTEMIS_AMQP_PORT + "?protocols=AMQP");

        ActiveMQServer artemisServer = ActiveMQServers.newActiveMQServer(artemisConfig);
        artemisServer.start();

        System.out.println("✅ ActiveMQ Artemis AMQP 1.0 running on amqp://127.0.0.1:" + ARTEMIS_AMQP_PORT);

        // Create standard queues
        artemisServer.createQueue(QueueConfiguration.of("orders.inbound")
                .setRoutingType(RoutingType.ANYCAST)
                .setDurable(false));
        artemisServer.createQueue(QueueConfiguration.of("payments.requests")
                .setRoutingType(RoutingType.ANYCAST)
                .setDurable(false));
        artemisServer.createQueue(QueueConfiguration.of("notifications.shipping")
                .setRoutingType(RoutingType.ANYCAST)
                .setDurable(false));

        // Start WireMock Standalone
        WireMockServer wireMockServer = StandaloneDevServer.startServer(port, rootDir);

        // Register Broker Connection in Registry
        BrokerConnection artemisConn = new BrokerConnection(
                "embedded-artemis",
                "amqp",
                "ActiveMQ Artemis (Embedded)",
                "127.0.0.1:" + ARTEMIS_AMQP_PORT,
                Map.of(
                        "amqp.idleTimeout", "30000",
                        "client.id", "wm-artemis-client",
                        "amqp.protocol", "1.0"));
        AsyncBrokerRegistry.getInstance().register(artemisConn);

        // In-memory Artemis destination provider
        ArtemisDestinationProvider artemisProvider = new ArtemisDestinationProvider(artemisServer);
        AsyncDestinationService.getInstance().registerProvider(artemisProvider);

        // Configure Upstream broker in AsyncEngine to bridge to Artemis
        AmqpMessageBroker upstreamBroker =
                new AmqpMessageBroker("artemis-upstream", "amqp://127.0.0.1:" + ARTEMIS_AMQP_PORT);
        AsyncEngine.getInstance().setBrokers(AsyncEngine.getInstance().getTestBroker(), upstreamBroker);

        // Seed initial sample messages with nested polyglot payloads
        String xmlInvoice =
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <invoice id="INV-ARTEMIS-9001">
                  <header>
                    <supplier>Acme Industrial Supply</supplier>
                    <customer>Global Logistics Inc</customer>
                    <date>2026-11-10</date>
                  </header>
                  <items>
                    <item sku="SKU-MOTOR-500" quantity="2">
                      <description>High-Torque Virtual Electric Motor</description>
                      <price currency="EUR">1725.00</price>
                    </item>
                  </items>
                  <total currency="EUR">3450.00</total>
                </invoice>
                """;

        String jsonOrder =
                """
                {
                  "orderId": "ORD-ARTEMIS-9001",
                  "customer": "Global Logistics Inc",
                  "status": "AWAITING_PAYMENT",
                  "itemCount": 2,
                  "totalAmount": 3450.00,
                  "embeddedXmlInvoice": "%s"
                }
                """
                        .formatted(xmlInvoice.replace("\"", "\\\"").replace("\n", "\\n"));

        artemisProvider.seedMessage(
                "orders.inbound",
                new AsyncDestinationMessage(
                        "msg-artemis-101",
                        "amqp",
                        "orders.inbound",
                        "ORD-ARTEMIS-9001",
                        "corr-artemis-9001",
                        "orders.replies",
                        Instant.now().minusSeconds(120),
                        jsonOrder.length(),
                        Map.of(
                                "x-tenant-id", "emea-retail",
                                "x-priority", "high",
                                "content-type", "application/json"),
                        jsonOrder,
                        null,
                        null));

        String jsonPayment =
                """
                {
                  "paymentId": "PAY-EUR-7721",
                  "orderId": "ORD-ARTEMIS-9001",
                  "amount": 3450.00,
                  "currency": "EUR",
                  "method": "SEPA_INSTANT",
                  "routingCode": "SEPA-DE-88214"
                }
                """;

        artemisProvider.seedMessage(
                "payments.requests",
                new AsyncDestinationMessage(
                        "msg-artemis-102",
                        "amqp",
                        "payments.requests",
                        "PAY-EUR-7721",
                        "corr-pay-7721",
                        "payments.confirmations",
                        Instant.now().minusSeconds(45),
                        jsonPayment.length(),
                        Map.of(
                                "content-type", "application/json",
                                "x-idempotency-key", "idem-99214"),
                        jsonPayment,
                        null,
                        null));

        // Create interactive HTTP Stubs for testing in HTTP Tester tab
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/orders/publish"))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                                """
                                {
                                  "status": "ACCEPTED",
                                  "targetQueue": "orders.inbound",
                                  "broker": "artemis-amqp",
                                  "receivedAt": "%s"
                                }
                                """
                                        .formatted(Instant.now().toString()))));

        wireMockServer.stubFor(
                post(urlEqualTo("/api/v1/payments/publish"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                                {
                                  "paymentStatus": "PROCESSED",
                                  "transactionId": "TX-ARTEMIS-9921",
                                  "queue": "payments.requests"
                                }
                                """)));

        wireMockServer.stubFor(get(urlEqualTo("/api/v1/broker/status"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                                """
                                {
                                  "broker": "ActiveMQ Artemis",
                                  "protocol": "AMQP 1.0",
                                  "port": %d,
                                  "status": "ONLINE",
                                  "queues": ["orders.inbound", "payments.requests", "notifications.shipping"]
                                }
                                """
                                        .formatted(ARTEMIS_AMQP_PORT))));

        System.out.println("=================================================================");
        System.out.println("🚀 WireMock Console UI: http://localhost:" + wireMockServer.port() + "/__admin/ui");
        System.out.println("📬 Queues & Topics Tab: http://localhost:" + wireMockServer.port() + "/__admin/ui/#queues");
        System.out.println("🚀 HTTP Tester Tab:    http://localhost:" + wireMockServer.port() + "/__admin/ui/#tester");
        System.out.println("🔌 ActiveMQ Artemis:    amqp://127.0.0.1:" + ARTEMIS_AMQP_PORT);
        System.out.println("📡 Broker Connection:  registered [embedded-artemis] -> 127.0.0.1:" + ARTEMIS_AMQP_PORT);
        System.out.println("📦 Sample Queues:      orders.inbound, payments.requests, notifications.shipping");
        System.out.println("=================================================================");
        System.out.println("Press Ctrl+C to stop the servers.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                wireMockServer.stop();
                artemisServer.stop();
            } catch (Exception ignored) {
            }
        }));

        Thread.currentThread().join();
    }

    /**
     * In-memory DestinationProvider bridging between ActiveMQ Artemis and the Web UI.
     */
    private static class ArtemisDestinationProvider implements DestinationProvider {

        private final ActiveMQServer artemisServer;
        private final Map<String, List<AsyncDestinationMessage>> messagesByQueue = new ConcurrentHashMap<>();

        ArtemisDestinationProvider(ActiveMQServer artemisServer) {
            this.artemisServer = artemisServer;
        }

        void seedMessage(String queue, AsyncDestinationMessage msg) {
            messagesByQueue
                    .computeIfAbsent(queue, k -> new CopyOnWriteArrayList<>())
                    .add(msg);
        }

        @Override
        public String getProtocol() {
            return "amqp";
        }

        @Override
        public List<AsyncDestination> listDestinations() {
            List<AsyncDestination> list = new ArrayList<>();
            List<String> queues = List.of("orders.inbound", "payments.requests", "notifications.shipping");
            for (String q : queues) {
                int count =
                        messagesByQueue.getOrDefault(q, Collections.emptyList()).size();
                list.add(new AsyncDestination(
                        "artemis:" + q,
                        "amqp",
                        q,
                        "embedded-artemis",
                        count,
                        Map.of(
                                "broker", "ActiveMQ Artemis 2.39.0",
                                "protocol", "AMQP 1.0",
                                "port", String.valueOf(ARTEMIS_AMQP_PORT),
                                "routing", "ANYCAST")));
            }
            return list;
        }

        @Override
        public List<AsyncDestinationMessage> peekMessages(String destination, int limit) {
            List<AsyncDestinationMessage> msgs = messagesByQueue.getOrDefault(destination, Collections.emptyList());
            if (limit > 0 && msgs.size() > limit) {
                return new ArrayList<>(msgs.subList(0, limit));
            }
            return new ArrayList<>(msgs);
        }

        @Override
        public boolean deleteMessage(String destination, String messageId) {
            List<AsyncDestinationMessage> msgs = messagesByQueue.get(destination);
            if (msgs != null) {
                return msgs.removeIf(m -> m.id().equals(messageId));
            }
            return false;
        }

        @Override
        public void purgeDestination(String destination) {
            List<AsyncDestinationMessage> msgs = messagesByQueue.get(destination);
            if (msgs != null) {
                msgs.clear();
            }
        }

        @Override
        public boolean publishMessage(String destination, AsyncDestinationMessage message) {
            List<AsyncDestinationMessage> msgs =
                    messagesByQueue.computeIfAbsent(destination, k -> new CopyOnWriteArrayList<>());
            int existingIdx = -1;
            for (int i = 0; i < msgs.size(); i++) {
                if (msgs.get(i).id().equals(message.id())) {
                    existingIdx = i;
                    break;
                }
            }
            if (existingIdx != -1) {
                msgs.set(existingIdx, message);
            } else {
                msgs.add(0, message);
            }
            return true;
        }
    }
}
