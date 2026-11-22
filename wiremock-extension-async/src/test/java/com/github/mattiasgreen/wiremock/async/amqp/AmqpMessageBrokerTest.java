package com.github.mattiasgreen.wiremock.async.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.io.IOException;
import java.net.ServerSocket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AmqpMessageBrokerTest {

    @Test
    @DisplayName("Should throw instead of silently falling back to local queue when remote AMQP delivery fails")
    void shouldThrowOnRemoteAmqpSendFailureInsteadOfLocalFallback() throws IOException {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }

        try (AmqpMessageBroker broker = new AmqpMessageBroker("remote-amqp", "amqp://127.0.0.1:" + unusedPort)) {
            AsyncMessage msg = AsyncMessage.builder()
                    .destination("orders.outbound")
                    .protocol("amqp")
                    .payload("{\"orderId\":\"ORD-502\"}")
                    .build();

            assertThatThrownBy(() -> broker.send("orders.outbound", msg))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("AMQP");
            assertThat(broker.size("orders.outbound")).isZero();
        }
    }

    @Test
    @DisplayName("Should mask credentials and strip CRLF from AMQP broker URI in error messages")
    void shouldMaskCredentialsAndSanitizeCrlfInErrorMessages() throws IOException {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }

        String uriWithCreds = "amqp://admin:super-secret-pass@127.0.0.1:" + unusedPort;
        try (AmqpMessageBroker broker = new AmqpMessageBroker("remote-amqp-creds", uriWithCreds)) {
            AsyncMessage msg = AsyncMessage.builder()
                    .destination("orders.outbound\r\ninjected")
                    .protocol("amqp")
                    .payload("{\"orderId\":\"ORD-503\"}")
                    .build();

            assertThatThrownBy(() -> broker.send("orders.outbound\r\ninjected", msg))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("***@127.0.0.1:" + unusedPort)
                    .hasMessageNotContaining("super-secret-pass")
                    .hasMessageNotContaining("\r")
                    .hasMessageNotContaining("\n");
        }
    }
}
