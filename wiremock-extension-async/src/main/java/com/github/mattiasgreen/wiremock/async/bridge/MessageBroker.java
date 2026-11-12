package com.github.mattiasgreen.wiremock.async.bridge;

import com.github.mattiasgreen.wiremock.async.destination.AsyncDestinationMessage;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Common abstraction representing an asynchronous message broker or virtual host destination store.
 *
 * <p><b>Note:</b> This is Beta functionality subject to evolution.
 */
public interface MessageBroker {

    /**
     * Publishes a message to the specified queue or channel.
     *
     * @param channel destination channel name
     * @param message async message to deliver
     */
    void send(String channel, AsyncMessage message);

    /**
     * Polls the next available message from the specified queue, or returns {@code null} if empty.
     *
     * @param channel source channel name
     * @return polled message or {@code null}
     */
    AsyncMessage poll(String channel);

    /**
     * Returns the number of unconsumed messages in the specified channel.
     *
     * @param channel source channel name
     * @return current queue size
     */
    int size(String channel);

    /**
     * Returns the list of active channel names managed by this broker.
     */
    default List<String> listChannels() {
        return Collections.emptyList();
    }

    /**
     * Returns a non-destructive read-only view of unconsumed messages in the channel.
     */
    default List<AsyncMessage> peek(String channel) {
        return Collections.emptyList();
    }

    /**
     * Returns a bounded, non-destructive view of messages formatted for the Async Destination Explorer.
     */
    default List<AsyncDestinationMessage> peekDestinationMessages(String channel, String protocol, int limit) {
        List<AsyncMessage> raw = peek(channel);
        List<AsyncDestinationMessage> result = new ArrayList<>();
        int max = limit > 0 ? Math.min(limit, raw.size()) : raw.size();
        for (int i = 0; i < max; i++) {
            AsyncMessage m = raw.get(i);
            String payload = Objects.requireNonNullElse(m.payload(), "");
            result.add(new AsyncDestinationMessage(
                    m.id() != null ? m.id() : UUID.randomUUID().toString(),
                    protocol,
                    channel,
                    m.key(),
                    m.correlationId(),
                    m.replyTo(),
                    m.timestamp() != null ? m.timestamp() : Instant.now(),
                    payload.getBytes(StandardCharsets.UTF_8).length,
                    m.headers(),
                    payload,
                    null,
                    null));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Removes an unconsumed message by ID from the specified channel.
     */
    default boolean delete(String channel, String messageId) {
        return false;
    }

    /**
     * Clears all unconsumed messages from the specified channel.
     */
    default void purge(String channel) {}

    /**
     * Updates an unconsumed message in-place by ID.
     */
    default boolean update(String channel, String messageId, AsyncMessage updated) {
        return false;
    }
}
