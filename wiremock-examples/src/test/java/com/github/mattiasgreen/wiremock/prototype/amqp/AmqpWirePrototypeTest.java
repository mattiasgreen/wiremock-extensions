package com.github.mattiasgreen.wiremock.prototype.amqp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.apache.qpid.protonj2.client.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Prototype Spike: Verifying AMQP 1.0 message mapping, correlation ID, reply-to,
 * and property translation using Apache Qpid ProtonJ2 on JDK 21.
 */
class AmqpWirePrototypeTest {

    @Test
    @DisplayName("Should translate AMQP 1.0 ProtonJ2 message into synthetic WireMock request properties")
    void shouldTranslateAmqp10MessageToSyntheticRequest() throws Exception {
        // Given: An AMQP 1.0 message with body, correlationId, replyTo, and custom properties
        String payload = "{\"orderId\":\"ORD-7721\",\"amount\":450.00,\"currency\":\"EUR\"}";
        Message<String> amqpMsg = Message.create(payload);
        amqpMsg.correlationId("corr-amqp-991");
        amqpMsg.replyTo("orders.replies");
        amqpMsg.messageId("msg-0001");
        amqpMsg.contentType("application/json");
        amqpMsg.property("x-tenant-id", "tenant-sweden");
        amqpMsg.property("x-priority", 1);

        // When: Synthesizing WireMock request metadata (the translation logic from docs/spec/async-mocking.md)
        String channel = "orders.inbound";
        String syntheticUrl = "/__async/amqp/" + channel;
        Map<String, String> syntheticHeaders = new HashMap<>();

        syntheticHeaders.put("content-type", amqpMsg.contentType());
        syntheticHeaders.put("X-Async-Protocol", "amqp");
        syntheticHeaders.put("X-Async-Channel", channel);
        syntheticHeaders.put("X-Async-Correlation-Id", String.valueOf(amqpMsg.correlationId()));
        syntheticHeaders.put("X-Async-Reply-To", amqpMsg.replyTo());
        syntheticHeaders.put("X-Async-Message-Id", String.valueOf(amqpMsg.messageId()));

        amqpMsg.forEachProperty((key, val) -> syntheticHeaders.put(key, String.valueOf(val)));

        // Then: All headers and correlation attributes mapped accurately
        assertThat(syntheticUrl).isEqualTo("/__async/amqp/orders.inbound");
        assertThat(syntheticHeaders.get("X-Async-Protocol")).isEqualTo("amqp");
        assertThat(syntheticHeaders.get("X-Async-Correlation-Id")).isEqualTo("corr-amqp-991");
        assertThat(syntheticHeaders.get("X-Async-Reply-To")).isEqualTo("orders.replies");
        assertThat(syntheticHeaders.get("x-tenant-id")).isEqualTo("tenant-sweden");
        assertThat(syntheticHeaders.get("x-priority")).isEqualTo("1");
        assertThat(amqpMsg.body()).isEqualTo(payload);
    }
}
