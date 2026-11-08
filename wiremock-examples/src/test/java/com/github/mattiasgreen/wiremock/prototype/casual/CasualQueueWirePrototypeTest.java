package com.github.mattiasgreen.wiremock.prototype.casual;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.laz.casual.api.buffer.type.ServiceBuffer;
import se.laz.casual.api.network.protocol.messages.CasualNWMessage;
import se.laz.casual.api.network.protocol.messages.CasualNWMessageType;
import se.laz.casual.api.queue.QueueErrorCode;
import se.laz.casual.api.queue.QueueMessage;
import se.laz.casual.api.xa.XID;
import se.laz.casual.network.ProtocolVersion;
import se.laz.casual.network.protocol.messages.CasualNWMessageImpl;
import se.laz.casual.network.protocol.messages.queue.CasualDequeueReplyMessage;
import se.laz.casual.network.protocol.messages.queue.CasualDequeueRequestMessage;
import se.laz.casual.network.protocol.messages.queue.CasualEnqueueReplyMessage;
import se.laz.casual.network.protocol.messages.queue.CasualEnqueueRequestMessage;
import se.laz.casual.network.protocol.messages.queue.DequeueMessage;
import se.laz.casual.network.protocol.messages.queue.EnqueueMessage;

/**
 * Prototype 1: Casual Queue Wire Interception Spike.
 *
 * <p>Proves that WireMock can intercept binary Casual XATMI queue wire messages:
 * ENQUEUE_REQUEST (6100) -> returns ENQUEUE_REPLY (6101) with QueueErrorCode.OK
 * DEQUEUE_REQUEST (6200) -> returns DEQUEUE_REPLY (6201) with matched messages
 */
class CasualQueueWirePrototypeTest {

    static class InProcessCasualQueueHandler extends io.netty.channel.SimpleChannelInboundHandler<CasualNWMessage<?>> {

        private final Map<String, Deque<QueueMessage>> queues = new HashMap<>();
        private final List<Map<String, Object>> syntheticEvents = new ArrayList<>();

        @Override
        protected void channelRead0(io.netty.channel.ChannelHandlerContext ctx, CasualNWMessage<?> msg) {
            CasualNWMessageType type = msg.getType();

            if (type == CasualNWMessageType.ENQUEUE_REQUEST) {
                CasualEnqueueRequestMessage req = (CasualEnqueueRequestMessage) msg.getMessage();
                String queueName = req.getQueueName();
                EnqueueMessage enqueueMsg = req.getMessage();

                // Translate into synthetic WireMock Journal event
                Map<String, Object> event = new HashMap<>();
                event.put("url", "/__async/casual/" + queueName);
                event.put("method", "CONSUME");
                event.put("queue", queueName);
                event.put("correlationId", enqueueMsg.getCorrelationInformation());
                event.put("replyQueue", enqueueMsg.getReplyQueue());
                event.put("payloadType", enqueueMsg.getPayload().getType());
                event.put("payloadBytes", enqueueMsg.getPayload().getBytes());
                syntheticEvents.add(event);

                // Enqueue in memory
                UUID messageId = UUID.randomUUID();
                QueueMessage stored = QueueMessage.createBuilder()
                        .withPayload(enqueueMsg.getPayload())
                        .withCorrelationInformation(enqueueMsg.getCorrelationInformation())
                        .withReplyQueue(enqueueMsg.getReplyQueue())
                        .build();
                queues.computeIfAbsent(queueName, k -> new ArrayDeque<>()).add(stored);

                // Reply: ENQUEUE_REPLY (6101)
                CasualEnqueueReplyMessage reply = CasualEnqueueReplyMessage.createBuilder()
                        .withExecution(req.getExecution())
                        .withId(messageId)
                        .withCode(QueueErrorCode.OK)
                        .withProtocolVersion(ProtocolVersion.VERSION_1_3)
                        .build();

                CasualNWMessage<CasualEnqueueReplyMessage> nwReply =
                        CasualNWMessageImpl.of(msg.getCorrelationId(), reply);
                ctx.writeAndFlush(nwReply);

            } else if (type == CasualNWMessageType.DEQUEUE_REQUEST) {
                CasualDequeueRequestMessage req = (CasualDequeueRequestMessage) msg.getMessage();
                String queueName = req.getQueueName();

                Deque<QueueMessage> queue = queues.getOrDefault(queueName, new ArrayDeque<>());
                List<DequeueMessage> messages = new ArrayList<>();
                if (!queue.isEmpty()) {
                    QueueMessage qm = queue.poll();
                    messages.add(DequeueMessage.of(qm));
                }

                // Reply: DEQUEUE_REPLY (6201)
                CasualDequeueReplyMessage reply = CasualDequeueReplyMessage.createBuilder()
                        .withExecution(req.getExecution())
                        .withCode(QueueErrorCode.OK)
                        .withMessages(messages)
                        .withProtocolVersion(ProtocolVersion.VERSION_1_3)
                        .build();

                CasualNWMessage<CasualDequeueReplyMessage> nwReply =
                        CasualNWMessageImpl.of(msg.getCorrelationId(), reply);
                ctx.writeAndFlush(nwReply);
            }
        }
    }

    @Test
    @DisplayName("Should intercept ENQUEUE_REQUEST and return valid ENQUEUE_REPLY")
    void shouldInterceptEnqueueRequest() {
        InProcessCasualQueueHandler handler = new InProcessCasualQueueHandler();
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // Given: A Casual Enqueue Request with JSON payload, correlationId, and replyQueue
        String payloadJson = "{\"orderId\":\"CASUAL-901\",\"amount\":3500.00}";
        ServiceBuffer payload = ServiceBuffer.of("JSON", List.of(payloadJson.getBytes(StandardCharsets.UTF_8)));
        QueueMessage qm = QueueMessage.createBuilder()
                .withPayload(payload)
                .withCorrelationInformation("corr-casual-1")
                .withReplyQueue("orders.replies.casual")
                .build();

        CasualEnqueueRequestMessage req = CasualEnqueueRequestMessage.createBuilder()
                .withExecution(UUID.randomUUID())
                .withQueueName("orders.inbound.casual")
                .withXid(XID.NULL_XID)
                .withMessage(EnqueueMessage.of(qm))
                .build();

        UUID correlationId = UUID.randomUUID();
        CasualNWMessage<CasualEnqueueRequestMessage> nwMsg = CasualNWMessageImpl.of(correlationId, req);

        // When: Message arrives on Netty channel
        channel.writeInbound(nwMsg);

        // Then: Handler generated synthetic WireMock Journal event
        assertThat(handler.syntheticEvents).hasSize(1);
        Map<String, Object> event = handler.syntheticEvents.get(0);
        assertThat(event.get("url")).isEqualTo("/__async/casual/orders.inbound.casual");
        assertThat(event.get("correlationId")).isEqualTo("corr-casual-1");
        assertThat(event.get("replyQueue")).isEqualTo("orders.replies.casual");
        assertThat(event.get("payloadType")).isEqualTo("JSON");

        // Then: Channel responded with CasualEnqueueReplyMessage
        CasualNWMessage<?> responseMsg = channel.readOutbound();
        assertThat(responseMsg).isNotNull();
        assertThat(responseMsg.getType()).isEqualTo(CasualNWMessageType.ENQUEUE_REPLY_V_1_3);
        CasualEnqueueReplyMessage reply = (CasualEnqueueReplyMessage) responseMsg.getMessage();
        assertThat(reply.getCode()).isEqualTo(QueueErrorCode.OK);
        assertThat(reply.getId()).isNotNull();
    }

    @Test
    @DisplayName("Should intercept DEQUEUE_REQUEST and serve enqueued message")
    void shouldInterceptDequeueRequestAfterEnqueue() {
        InProcessCasualQueueHandler handler = new InProcessCasualQueueHandler();
        EmbeddedChannel channel = new EmbeddedChannel(handler);

        // Given: Enqueue a message first
        ServiceBuffer payload =
                ServiceBuffer.of("JSON", List.of("{\"status\":\"READY\"}".getBytes(StandardCharsets.UTF_8)));
        QueueMessage qm = QueueMessage.createBuilder()
                .withPayload(payload)
                .withCorrelationInformation("corr-deq-test")
                .build();
        CasualEnqueueRequestMessage enq = CasualEnqueueRequestMessage.createBuilder()
                .withExecution(UUID.randomUUID())
                .withQueueName("tasks.queue")
                .withXid(XID.NULL_XID)
                .withMessage(EnqueueMessage.of(qm))
                .build();
        channel.writeInbound(CasualNWMessageImpl.of(UUID.randomUUID(), enq));
        channel.readOutbound(); // consume enqueue reply

        // When: Client sends CasualDequeueRequestMessage
        CasualDequeueRequestMessage deq = CasualDequeueRequestMessage.createBuilder()
                .withExecution(UUID.randomUUID())
                .withQueueName("tasks.queue")
                .withSelectorProperties("")
                .withSelectorUUID(new UUID(0L, 0L))
                .withBlock(false)
                .withXid(XID.NULL_XID)
                .build();
        channel.writeInbound(CasualNWMessageImpl.of(UUID.randomUUID(), deq));

        // Then: Channel responded with CasualDequeueReplyMessage containing the message
        CasualNWMessage<?> responseMsg = channel.readOutbound();
        assertThat(responseMsg).isNotNull();
        assertThat(responseMsg.getType()).isEqualTo(CasualNWMessageType.DEQUEUE_REPLY_V_1_3);
        CasualDequeueReplyMessage deqReply = (CasualDequeueReplyMessage) responseMsg.getMessage();
        assertThat(deqReply.getCode()).isEqualTo(QueueErrorCode.OK);
        assertThat(deqReply.getMessages()).hasSize(1);
        DequeueMessage dequeued = deqReply.getMessages().get(0);
        assertThat(dequeued.getCorrelationInformation()).isEqualTo("corr-deq-test");
        assertThat(new String(dequeued.getPayload().getBytes().get(0), StandardCharsets.UTF_8))
                .isEqualTo("{\"status\":\"READY\"}");
    }
}
