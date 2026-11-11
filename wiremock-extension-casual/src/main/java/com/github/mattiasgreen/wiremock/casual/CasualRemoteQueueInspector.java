package com.github.mattiasgreen.wiremock.casual;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.transaction.xa.Xid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.laz.casual.api.xa.XID;
import se.laz.casual.network.protocol.messages.queue.CasualDequeueReplyMessage;
import se.laz.casual.network.protocol.messages.queue.DequeueMessage;

/**
 * Isolated protocol adapter seam encapsulating non-destructive queue message inspection
 * against remote Casual domains via transactional dequeue and rollback.
 *
 * <p><b>Note:</b> This implementation serves as an interim bridge until upstream Casual
 * protocol formally introduces a non-destructive browse frame (e.g. {@code CASUAL_QUEUE_BROWSE}).
 */
public class CasualRemoteQueueInspector {

    private static final Logger LOG = LoggerFactory.getLogger(CasualRemoteQueueInspector.class);

    private final CasualClient client;

    public CasualRemoteQueueInspector(CasualClient client) {
        this.client = Objects.requireNonNull(client, "client must not be null");
    }

    /**
     * Peeks up to {@code maxMessages} from a remote Casual queue by performing a transactional dequeue
     * followed immediately by a transaction rollback.
     */
    public List<CasualRemoteMessage> peekMessages(String queueName, int maxMessages) {
        if (queueName == null || queueName.isBlank() || maxMessages <= 0) {
            return Collections.emptyList();
        }

        byte[] gtrid = UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bqual = "peek".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Xid xid = XID.of(gtrid, bqual, 42);
        List<CasualRemoteMessage> peeked = new ArrayList<>();
        try {
            for (int i = 0; i < maxMessages; i++) {
                CasualDequeueReplyMessage reply = client.dequeue(queueName, xid);
                if (reply.getMessages() == null || reply.getMessages().isEmpty()) {
                    break;
                }
                for (DequeueMessage dm : reply.getMessages()) {
                    String payload = CasualBufferUtils.extractPayload(dm.getPayload());
                    String bufferType =
                            dm.getPayload() != null ? dm.getPayload().getType() : "JSON";
                    peeked.add(new CasualRemoteMessage(
                            dm.getId(),
                            queueName,
                            dm.getCorrelationInformation(),
                            dm.getReplyQueue(),
                            bufferType,
                            payload,
                            dm.getNumberOfRedelivered(),
                            dm.getTimestamp() != null ? dm.getTimestamp() : Instant.now()));
                }
            }
        } catch (Exception e) {
            String sanitized = Objects.toString(e.getMessage(), "").replaceAll("[\r\n]", "");
            LOG.warn("[CASUAL-INSPECTOR] Error during transactional peek on queue {}: {}", queueName, sanitized);
        } finally {
            try {
                // Rollback guarantees messages remain on the remote queue
                client.rollback(xid, 1);
                LOG.debug("[CASUAL-INSPECTOR] Rolled back inspect transaction for queue {}", queueName);
            } catch (Exception rollbackEx) {
                String sanitized = Objects.toString(rollbackEx.getMessage(), "").replaceAll("[\r\n]", "");
                LOG.error(
                        "[CASUAL-INSPECTOR] Failed to rollback inspect transaction for queue {}: {}",
                        queueName,
                        sanitized);
            }
        }
        return Collections.unmodifiableList(peeked);
    }

    /**
     * Purges all messages from the specified remote Casual queue by continuously dequeuing and committing.
     */
    public int purgeQueue(String queueName) {
        if (queueName == null || queueName.isBlank()) {
            return 0;
        }
        int count = 0;
        byte[] bqual = "purge".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            while (true) {
                byte[] gtrid = UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                Xid xid = XID.of(gtrid, bqual, 42);
                CasualDequeueReplyMessage reply = client.dequeue(queueName, xid);
                if (reply.getMessages() == null || reply.getMessages().isEmpty()) {
                    break;
                }
                client.commit(xid, 1, true);
                count += reply.getMessages().size();
            }
            LOG.info("[CASUAL-INSPECTOR] Purged {} messages from remote queue {}", count, queueName);
        } catch (Exception e) {
            String sanitized = Objects.toString(e.getMessage(), "").replaceAll("[\r\n]", "");
            LOG.error("[CASUAL-INSPECTOR] Error purging remote queue {}: {}", queueName, sanitized);
        }
        return count;
    }
}
