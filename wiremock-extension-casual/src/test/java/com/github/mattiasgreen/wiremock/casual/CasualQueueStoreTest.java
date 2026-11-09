package com.github.mattiasgreen.wiremock.casual;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import javax.transaction.xa.Xid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.laz.casual.api.buffer.type.JsonBuffer;
import se.laz.casual.api.buffer.type.ServiceBuffer;
import se.laz.casual.api.network.protocol.messages.CasualNWMessageType;
import se.laz.casual.api.queue.QueueErrorCode;
import se.laz.casual.api.queue.QueueMessage;
import se.laz.casual.api.xa.XID;
import se.laz.casual.network.ProtocolVersion;
import se.laz.casual.network.protocol.messages.queue.CasualDequeueReplyMessage;
import se.laz.casual.network.protocol.messages.queue.CasualDequeueRequestMessage;
import se.laz.casual.network.protocol.messages.queue.CasualEnqueueReplyMessage;
import se.laz.casual.network.protocol.messages.queue.CasualEnqueueRequestMessage;
import se.laz.casual.network.protocol.messages.queue.EnqueueMessage;

class CasualQueueStoreTest {

    private CasualQueueStore store;

    @BeforeEach
    void setUp() {
        store = new CasualQueueStore();
    }

    private CasualEnqueueRequestMessage createEnqueueReq(String queue, String json, Xid xid) {
        ServiceBuffer buffer = ServiceBuffer.of(JsonBuffer.of(json));
        QueueMessage qm = QueueMessage.createBuilder()
                .withId(UUID.randomUUID())
                .withPayload(buffer)
                .build();
        return CasualEnqueueRequestMessage.createBuilder()
                .withExecution(UUID.randomUUID())
                .withQueueName(queue)
                .withXid(xid != null ? xid : XID.NULL_XID)
                .withMessage(EnqueueMessage.of(qm))
                .build();
    }

    private CasualDequeueRequestMessage createDequeueReq(String queue, Xid xid) {
        return CasualDequeueRequestMessage.createBuilder()
                .withExecution(UUID.randomUUID())
                .withQueueName(queue)
                .withXid(xid != null ? xid : XID.NULL_XID)
                .withSelectorProperties("")
                .withSelectorUUID(new UUID(0, 0))
                .withBlock(false)
                .build();
    }

    @Test
    @DisplayName("Should enqueue and dequeue messages in FIFO order non-transactionally")
    void shouldEnqueueAndDequeueInFifoOrder() {
        String queue = "orders.q";
        store.createQueue(queue, 3, 500);

        store.enqueue(createEnqueueReq(queue, "{\"id\":1}", null), ProtocolVersion.VERSION_1_3);
        store.enqueue(createEnqueueReq(queue, "{\"id\":2}", null), ProtocolVersion.VERSION_1_3);

        assertThat(store.getQueueSize(queue)).isEqualTo(2);

        CasualDequeueReplyMessage reply1 = store.dequeue(createDequeueReq(queue, null), ProtocolVersion.VERSION_1_3);
        assertThat(reply1.getCode()).isEqualTo(QueueErrorCode.OK);
        assertThat(reply1.getMessages()).hasSize(1);
        String payload1 =
                CasualBufferUtils.extractPayload(reply1.getMessages().get(0).getPayload());
        assertThat(payload1).isEqualTo("{\"id\":1}");

        CasualDequeueReplyMessage reply2 = store.dequeue(createDequeueReq(queue, null), ProtocolVersion.VERSION_1_3);
        assertThat(reply2.getCode()).isEqualTo(QueueErrorCode.OK);
        assertThat(reply2.getMessages()).hasSize(1);
        String payload2 =
                CasualBufferUtils.extractPayload(reply2.getMessages().get(0).getPayload());
        assertThat(payload2).isEqualTo("{\"id\":2}");

        // Empty dequeue
        CasualDequeueReplyMessage emptyReply =
                store.dequeue(createDequeueReq(queue, null), ProtocolVersion.VERSION_1_3);
        assertThat(emptyReply.getCode()).isEqualTo(QueueErrorCode.NO_MESSAGE);
        assertThat(emptyReply.getMessages()).isEmpty();
    }

    @Test
    @DisplayName("Should negotiate legacy protocol version 1.2 vs 1.3+")
    void shouldNegotiateProtocolVersions() {
        String queue = "protocol.test.q";
        CasualEnqueueRequestMessage req = createEnqueueReq(queue, "{\"test\":true}", null);

        // Version 1.2 generates legacy ENQUEUE_REPLY (6101)
        CasualEnqueueReplyMessage replyV12 = store.enqueue(req, ProtocolVersion.VERSION_1_2);
        assertThat(replyV12.getType()).isEqualTo(CasualNWMessageType.ENQUEUE_REPLY);

        // Version 1.3+ generates ENQUEUE_REPLY_V_1_3 (6102) with QueueErrorCode
        CasualEnqueueReplyMessage replyV13 = store.enqueue(req, ProtocolVersion.VERSION_1_3);
        assertThat(replyV13.getType()).isEqualTo(CasualNWMessageType.ENQUEUE_REPLY_V_1_3);
        assertThat(replyV13.getCode()).isEqualTo(QueueErrorCode.OK);

        // Dequeue version 1.2 generates DEQUEUE_REPLY (6201)
        CasualDequeueReplyMessage deqV12 = store.dequeue(createDequeueReq(queue, null), ProtocolVersion.VERSION_1_2);
        assertThat(deqV12.getType()).isEqualTo(CasualNWMessageType.DEQUEUE_REPLY);

        // Dequeue version 1.3 generates DEQUEUE_REPLY_V_1_3 (6202)
        CasualDequeueReplyMessage deqV13 = store.dequeue(createDequeueReq(queue, null), ProtocolVersion.VERSION_1_3);
        assertThat(deqV13.getType()).isEqualTo(CasualNWMessageType.DEQUEUE_REPLY_V_1_3);
        assertThat(deqV13.getCode()).isEqualTo(QueueErrorCode.OK);
    }

    @Test
    @DisplayName("Should delay redelivery on rollback and move to .error DLQ after max retries")
    void shouldHandleRollbackAndDeadLetterQueue() {
        String queue = "payments.dlq.test";
        long maxRetries = 2;
        long retryDelayMs = 200;
        store.createQueue(queue, maxRetries, retryDelayMs);

        // 1. Enqueue message
        store.enqueue(createEnqueueReq(queue, "{\"payment\":\"PAY-1\"}", null), ProtocolVersion.VERSION_1_3);

        Xid tx1 = XID.of(new byte[] {1, 2, 3}, new byte[] {4, 5, 6}, 100);

        // 2. Dequeue inside transaction 1
        CasualDequeueReplyMessage deq1 = store.dequeue(createDequeueReq(queue, tx1), ProtocolVersion.VERSION_1_3);
        assertThat(deq1.getCode()).isEqualTo(QueueErrorCode.OK);
        assertThat(deq1.getMessages().get(0).getNumberOfRedelivered()).isEqualTo(0);

        // 3. Rollback transaction 1 (retry 1 of 2) -> put back with delay
        store.rollback(tx1);

        // Immediate dequeue fails because of retry delay
        CasualDequeueReplyMessage earlyDeq = store.dequeue(createDequeueReq(queue, null), ProtocolVersion.VERSION_1_3);
        assertThat(earlyDeq.getCode()).isEqualTo(QueueErrorCode.NO_MESSAGE);

        // Fast forward availableSince
        store.getOrCreateConfig(queue);
        // Wait or update available time
        try {
            Thread.sleep(250);
        } catch (InterruptedException ignored) {
        }

        Xid tx2 = XID.of(new byte[] {7, 8, 9}, new byte[] {10, 11, 12}, 100);

        // 4. Dequeue inside transaction 2 (redelivered count is 1)
        CasualDequeueReplyMessage deq2 = store.dequeue(createDequeueReq(queue, tx2), ProtocolVersion.VERSION_1_3);
        assertThat(deq2.getCode()).isEqualTo(QueueErrorCode.OK);
        assertThat(deq2.getMessages().get(0).getNumberOfRedelivered()).isEqualTo(1);

        // 5. Rollback transaction 2 (retry 2 of 2) -> exceeds maxRetries (2 >= 2) -> routes to DLQ!
        store.rollback(tx2);

        // Main queue is now empty
        assertThat(store.getQueueSize(queue)).isZero();

        // DLQ queue has 1 message
        String dlqName = queue + ".error";
        assertThat(store.getQueueSize(dlqName)).isEqualTo(1);

        CasualDequeueReplyMessage dlqDeq = store.dequeue(createDequeueReq(dlqName, null), ProtocolVersion.VERSION_1_3);
        assertThat(dlqDeq.getCode()).isEqualTo(QueueErrorCode.OK);
        assertThat(dlqDeq.getMessages().get(0).getNumberOfRedelivered()).isEqualTo(2);
        String dlqPayload =
                CasualBufferUtils.extractPayload(dlqDeq.getMessages().get(0).getPayload());
        assertThat(dlqPayload).isEqualTo("{\"payment\":\"PAY-1\"}");
    }

    @Test
    @DisplayName("Should discard uncommitted enqueues when rolled back")
    void shouldDiscardUncommittedEnqueuesOnRollback() {
        String queue = "staging.q";
        store.createQueue(queue, 3, 500);

        Xid tx = XID.of(new byte[] {99}, new byte[] {88}, 42);
        store.enqueue(createEnqueueReq(queue, "{\"pending\":true}", tx), ProtocolVersion.VERSION_1_3);

        // Queue has size 0 before commit
        assertThat(store.getQueueSize(queue)).isZero();

        // Rollback
        store.rollback(tx);

        // Queue still size 0
        assertThat(store.getQueueSize(queue)).isZero();

        CasualDequeueReplyMessage deq = store.dequeue(createDequeueReq(queue, null), ProtocolVersion.VERSION_1_3);
        assertThat(deq.getCode()).isEqualTo(QueueErrorCode.NO_MESSAGE);
    }
}
