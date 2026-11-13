package com.github.mattiasgreen.wiremock.async.destination;

import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.util.List;

/**
 * Protocol-specific provider interface for querying and mutating destination queues and topics.
 */
public interface DestinationProvider {

    /**
     * Protocol identifier handled by this provider (e.g. {@code "kafka"}, {@code "amqp"}, {@code "casual"}, {@code "in-memory"}).
     */
    String getProtocol();

    /**
     * Lists active destinations known by this provider from the live catalog.
     */
    List<AsyncDestination> listDestinations();

    /**
     * Peeks unconsumed messages from the specified destination without mutating queue state.
     */
    List<AsyncMessage> peekMessages(String destination, int limit);

    /**
     * Deletes a specific message by ID from the destination.
     */
    boolean deleteMessage(String destination, String messageId);

    /**
     * Purges all unconsumed messages from the destination.
     */
    void purgeDestination(String destination);

    /**
     * Injects or publishes a message onto the destination (e.g. compensating edit).
     */
    boolean publishMessage(String destination, AsyncMessage message);
}
