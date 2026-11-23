package com.github.mattiasgreen.wiremock.async.amqp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mattiasgreen.wiremock.async.admin.AsyncExtensionFactory;
import com.github.mattiasgreen.wiremock.async.engine.AsyncEngine;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.qpid.protonj2.client.Client;
import org.apache.qpid.protonj2.client.Connection;
import org.apache.qpid.protonj2.client.Delivery;
import org.apache.qpid.protonj2.client.Message;
import org.apache.qpid.protonj2.client.Receiver;
import org.apache.qpid.protonj2.client.ReceiverOptions;
import org.apache.qpid.protonj2.client.Sender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AmqpFlowControlTest {

    private AmqpServer server;
    private Client client;

    @BeforeEach
    void setUp() throws InterruptedException {
        server = new AmqpServer(0, null);
        server.start();
        client = Client.create();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    @DisplayName("Should enforce credit-based flow control and track consumer disposition")
    void shouldEnforceCreditFlowAndTrackDisposition() throws Exception {
        String queue = "orders.credit.test";

        // Stage 2 messages in mock server
        server.stageFeedMessage(
                queue,
                AsyncMessage.builder()
                        .destination(queue)
                        .payload("{\"orderId\":\"ORD-1\"}")
                        .headers(Map.of("x-priority", "high"))
                        .build());
        server.stageFeedMessage(
                queue,
                AsyncMessage.builder()
                        .destination(queue)
                        .payload("{\"orderId\":\"ORD-2\"}")
                        .headers(Map.of("x-priority", "normal"))
                        .build());

        try (Connection conn = client.connect("127.0.0.1", server.getPort())) {
            ReceiverOptions options = new ReceiverOptions();
            options.creditWindow(1); // Link credit of 1 only
            Receiver receiver = conn.openReceiver(queue, options);

            Delivery delivery1 = receiver.receive(2, TimeUnit.SECONDS);
            assertThat(delivery1).isNotNull();
            Message<?> msg1 = delivery1.message();
            byte[] bodyBytes1 = (byte[]) msg1.body();
            assertThat(new String(bodyBytes1, StandardCharsets.UTF_8)).isEqualTo("{\"orderId\":\"ORD-1\"}");
            assertThat(msg1.property("x-priority")).isEqualTo("high");

            // Accept and settle delivery 1
            delivery1.accept();

            // Settle delivery 1 opens room for delivery 2
            Delivery delivery2 = receiver.receive(2, TimeUnit.SECONDS);
            assertThat(delivery2).isNotNull();
            Message<?> msg2 = delivery2.message();
            byte[] bodyBytes2 = (byte[]) msg2.body();
            assertThat(new String(bodyBytes2, StandardCharsets.UTF_8)).isEqualTo("{\"orderId\":\"ORD-2\"}");
            delivery2.accept();

            receiver.close();
        }

        // Verify settlements captured in audit log
        assertThat(server.getSettlementAudit()).anyMatch(audit -> audit.contains("DISPOSITION:" + queue));
    }

    @Test
    @DisplayName("Should receive inbound produce from client and notify consumer")
    void shouldReceiveInboundProduceFromClient() throws Exception {
        AtomicReference<AsyncMessage> received = new AtomicReference<>();
        server.close();
        server = new AmqpServer(0, received::set);
        server.start();

        String queue = "orders.publish.test";
        try (Connection conn = client.connect("127.0.0.1", server.getPort())) {
            Sender sender = conn.openSender(queue);
            Message<byte[]> msg = Message.create("{\"paymentId\":\"PAY-99\"}".getBytes(StandardCharsets.UTF_8));
            msg.to(queue);
            msg.correlationId("corr-1234");
            msg.property("x-tenant", "tenant-alpha");

            sender.send(msg);
            sender.close();
        }

        // Wait up to 2 seconds for server handler to process message
        for (int i = 0; i < 20 && received.get() == null; i++) {
            Thread.sleep(100);
        }

        assertThat(received.get()).isNotNull();
        assertThat(received.get().destination()).isEqualTo(queue);
        assertThat(received.get().payload()).isEqualTo("{\"paymentId\":\"PAY-99\"}");
        assertThat(received.get().correlationId()).isEqualTo("corr-1234");
        assertThat(received.get().headers()).containsEntry("x-tenant", "tenant-alpha");
    }

    @Test
    @DisplayName("Should record REJECTED and RELEASED dispositions in settlement audit")
    void shouldRecordRejectedAndReleasedDispositionsInSettlementAudit() throws Exception {
        String queue = "orders.disposition.test";

        server.stageFeedMessage(
                queue,
                AsyncMessage.builder()
                        .destination(queue)
                        .payload("{\"orderId\":\"ORD-BAD\"}")
                        .build());
        server.stageFeedMessage(
                queue,
                AsyncMessage.builder()
                        .destination(queue)
                        .payload("{\"orderId\":\"ORD-RELEASE\"}")
                        .build());

        try (Connection conn = client.connect("127.0.0.1", server.getPort())) {
            ReceiverOptions options = new ReceiverOptions();
            options.autoAccept(false);
            Receiver receiver = conn.openReceiver(queue, options);

            Delivery delivery1 = receiver.receive(2, TimeUnit.SECONDS);
            assertThat(delivery1).isNotNull();
            delivery1.reject("bad-payload", "invalid");

            Delivery delivery2 = receiver.receive(2, TimeUnit.SECONDS);
            assertThat(delivery2).isNotNull();
            delivery2.release();

            receiver.close();
        }

        long deadline = System.currentTimeMillis() + 3_000;
        while (server.getSettlementAudit().size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }

        assertThat(server.getSettlementAudit())
                .contains("DISPOSITION:" + queue + ":REJECTED", "DISPOSITION:" + queue + ":RELEASED");
    }

    @Test
    @DisplayName(
            "HTTP-triggered metadata.async.produce and call-triggered feed with protocol amqp deliver interpolated JSON to real Proton-J2 Receiver")
    void shouldDeliverHttpTriggeredAmqpProduceAndFeedToRealProtonReceiver() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AsyncEngine engine = AsyncEngine.create();
        WireMockServer wm = new WireMockServer(
                WireMockConfiguration.options().dynamicPort().extensions(new AsyncExtensionFactory(engine)));
        wm.start();
        try {
            engine.startAmqpServer(0);
            int amqpPort = engine.getAmqpServerPort();

            wm.stubFor(post(urlEqualTo("/api/v1/amqp/produce"))
                    .willReturn(aResponse()
                            .withStatus(202)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"orderId\":\"AMQP-PROD-1\"}"))
                    .withMetadata(Map.of(
                            "async",
                            Map.of(
                                    "produce",
                                    List.of(Map.of(
                                            "protocol",
                                            "amqp",
                                            "destination",
                                            "orders.amqp.produce",
                                            "headers",
                                            Map.of("x-trigger", "produce"),
                                            "jsonBody",
                                            Map.of(
                                                    "orderId", "{{jsonPath response.body '$.orderId'}}",
                                                    "customer", "{{jsonPath request.body '$.customer'}}")))))));

            wm.stubFor(
                    post(urlEqualTo("/api/v1/amqp/feed"))
                            .willReturn(aResponse()
                                    .withStatus(200)
                                    .withHeader("Content-Type", "application/json")
                                    .withBody("{\"batchId\":\"AMQP-FEED-2\"}"))
                            .withMetadata(
                                    Map.of(
                                            "async",
                                            Map.of(
                                                    "feed",
                                                    Map.of(
                                                            "protocol", "amqp",
                                                            "destination", "orders.amqp.feed",
                                                            "trigger", "call-triggered",
                                                            "records",
                                                                    List.of(
                                                                            Map.of(
                                                                                    "headers",
                                                                                            Map.of("x-trigger", "feed"),
                                                                                    "jsonBody",
                                                                                            Map.of(
                                                                                                    "batchId",
                                                                                                            "{{jsonPath response.body '$.batchId'}}",
                                                                                                    "region",
                                                                                                            "{{jsonPath request.body '$.region'}}"))))))));

            HttpClient httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpResponse<String> produceResp = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(wm.baseUrl() + "/api/v1/amqp/produce"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"customer\":\"Freja\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(produceResp.statusCode()).isEqualTo(202);

            HttpResponse<String> feedResp = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(wm.baseUrl() + "/api/v1/amqp/feed"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"region\":\"nordics\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(feedResp.statusCode()).isEqualTo(200);

            try (Connection conn = client.connect("127.0.0.1", amqpPort)) {
                Receiver produceReceiver = conn.openReceiver("orders.amqp.produce");
                Delivery produceDelivery = produceReceiver.receive(5, TimeUnit.SECONDS);
                assertThat(produceDelivery).isNotNull();
                JsonNode produceJson = mapper.readTree(
                        new String((byte[]) produceDelivery.message().body(), StandardCharsets.UTF_8));
                assertThat(produceJson.get("orderId").asText()).isEqualTo("AMQP-PROD-1");
                assertThat(produceJson.get("customer").asText()).isEqualTo("Freja");
                assertThat(produceDelivery.message().property("x-trigger")).isEqualTo("produce");
                produceDelivery.accept();
                produceReceiver.close();

                Receiver feedReceiver = conn.openReceiver("orders.amqp.feed");
                Delivery feedDelivery = feedReceiver.receive(5, TimeUnit.SECONDS);
                assertThat(feedDelivery).isNotNull();
                JsonNode feedJson = mapper.readTree(
                        new String((byte[]) feedDelivery.message().body(), StandardCharsets.UTF_8));
                assertThat(feedJson.get("batchId").asText()).isEqualTo("AMQP-FEED-2");
                assertThat(feedJson.get("region").asText()).isEqualTo("nordics");
                assertThat(feedDelivery.message().property("x-trigger")).isEqualTo("feed");
                feedDelivery.accept();
                feedReceiver.close();
            }
        } finally {
            wm.stop();
        }
    }
}
