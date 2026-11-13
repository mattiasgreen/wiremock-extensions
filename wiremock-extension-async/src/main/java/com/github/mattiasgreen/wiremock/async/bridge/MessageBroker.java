package com.github.mattiasgreen.wiremock.async.bridge;

import com.github.mattiasgreen.wiremock.async.destination.AsyncDestination;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
     * Discovers the catalog of active destinations managed by this broker.
     */
    default List<AsyncDestination> discoverDestinations() {
        return Collections.emptyList();
    }

    /**
     * Returns whether this broker explicitly owns or configures the given destination.
     */
    default boolean ownsDestination(String destination) {
        return destination != null && listChannels().contains(destination);
    }

    /**
     * Returns a non-destructive read-only view of unconsumed messages in the channel.
     */
    default List<AsyncMessage> peek(String channel) {
        return Collections.emptyList();
    }

    /**
     * Returns a bounded, non-destructive view of messages in the channel.
     */
    default List<AsyncMessage> peek(String channel, int limit) {
        List<AsyncMessage> raw = peek(channel);
        if (limit <= 0 || raw.size() <= limit) {
            return raw;
        }
        return Collections.unmodifiableList(new ArrayList<>(raw.subList(0, limit)));
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
