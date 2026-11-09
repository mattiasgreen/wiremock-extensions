package com.github.mattiasgreen.wiremock.async.amqp;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.nio.charset.StandardCharsets;
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
}
