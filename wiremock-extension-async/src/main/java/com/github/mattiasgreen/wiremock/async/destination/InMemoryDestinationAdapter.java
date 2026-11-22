package com.github.mattiasgreen.wiremock.async.destination;

import com.github.mattiasgreen.wiremock.async.bridge.MessageBroker;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@link DestinationProvider} adapter backed by an in-memory {@link MessageBroker}.
 *
 * <p><b>Note:</b> This is Beta functionality subject to evolution.
 */
public final class InMemoryDestinationAdapter implements DestinationProvider {

    private static final String PROTOCOL = "in-memory";

    private final Supplier<MessageBroker> brokerSupplier;

    public InMemoryDestinationAdapter(Supplier<MessageBroker> brokerSupplier) {
        this.brokerSupplier = Objects.requireNonNull(brokerSupplier, "brokerSupplier must not be null");
    }

    @Override
    public String getProtocol() {
        return PROTOCOL;
    }

    @Override
    public List<AsyncDestination> listDestinations() {
        MessageBroker broker = brokerSupplier.get();
        if (broker == null) {
            return Collections.emptyList();
        }
        return broker.discoverDestinations();
    }

    @Override
    public List<AsyncMessage> peekMessages(String destination, int limit) {
        MessageBroker broker = brokerSupplier.get();
        if (broker == null || destination == null || destination.isBlank()) {
            return Collections.emptyList();
        }
        List<AsyncMessage> peeked = broker.peek(destination, limit);
        List<AsyncMessage> result = new ArrayList<>(peeked.size());
        for (AsyncMessage m : peeked) {
            result.add(
                    PROTOCOL.equals(m.protocol()) && destination.equals(m.destination())
                            ? m
                            : AsyncMessage.builder()
                                    .id(m.id())
                                    .protocol(PROTOCOL)
                                    .destination(destination)
                                    .key(m.key())
                                    .correlationId(m.correlationId())
                                    .replyTo(m.replyTo())
                                    .timestamp(m.timestamp())
                                    .headers(m.headers())
                                    .payload(m.payload())
                                    .build());
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public boolean deleteMessage(String destination, String messageId) {
        MessageBroker broker = brokerSupplier.get();
        if (broker == null
                || destination == null
                || destination.isBlank()
                || messageId == null
                || messageId.isBlank()) {
            return false;
        }
        return broker.delete(destination, messageId);
    }

    @Override
    public void purgeDestination(String destination) {
        MessageBroker broker = brokerSupplier.get();
        if (broker != null && destination != null && !destination.isBlank()) {
            broker.purge(destination);
        }
    }

    @Override
    public boolean publishMessage(String destination, AsyncMessage message) {
        MessageBroker broker = brokerSupplier.get();
        if (broker == null || destination == null || destination.isBlank() || message == null) {
            return false;
        }
        broker.send(destination, message);
        return true;
    }
}
