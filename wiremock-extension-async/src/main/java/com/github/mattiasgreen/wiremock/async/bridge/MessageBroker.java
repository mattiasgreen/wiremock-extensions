package com.github.mattiasgreen.wiremock.async.bridge;

import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;

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
}
