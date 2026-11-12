package com.github.mattiasgreen.wiremock.async.ibmmq;

import com.github.mattiasgreen.wiremock.async.bridge.MessageBroker;
import com.github.mattiasgreen.wiremock.async.connection.AsyncBrokerRegistry;
import com.github.mattiasgreen.wiremock.async.connection.BrokerConnection;
import com.github.mattiasgreen.wiremock.async.destination.AsyncDestination;
import com.github.mattiasgreen.wiremock.async.destination.AsyncDestinationMessage;
import com.github.mattiasgreen.wiremock.async.destination.DestinationProvider;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link DestinationProvider} for IBM MQ queues backing the Async Queues &amp; Topics Explorer.
 * Delegates connection pooling and client lifecycle to {@link AsyncBrokerRegistry}.
 *
 * <p><b>Note:</b> Beta functionality subject to evolution.
 */
public class IbmMqDestinationProvider implements DestinationProvider {

    private final AsyncBrokerRegistry registry;

    public IbmMqDestinationProvider() {
        this(AsyncBrokerRegistry.getInstance());
    }

    public IbmMqDestinationProvider(AsyncBrokerRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        this.registry.registerBrokerFactory("ibmmq", IbmMqMessageBroker::new);
    }

    @Override
    public String getProtocol() {
        return "ibmmq";
    }

    @Override
    public List<AsyncDestination> listDestinations() {
        List<AsyncDestination> all = new ArrayList<>();
        for (MessageBroker broker :
                registry.getOrCreateBrokersByProtocol("ibmmq").values()) {
            if (broker instanceof IbmMqMessageBroker mqBroker) {
                all.addAll(mqBroker.discoverDestinations());
            }
        }
        return Collections.unmodifiableList(all);
    }

    @Override
    public List<AsyncDestinationMessage> peekMessages(String destination, int limit) {
        if (destination == null || destination.isBlank()) {
            return Collections.emptyList();
        }
        IbmMqMessageBroker broker = resolveTargetBroker(IbmMqMessageMapper.normalizeQueueName(destination));
        return broker != null ? broker.peekDestinationMessages(destination, "ibmmq", limit) : Collections.emptyList();
    }

    @Override
    public boolean deleteMessage(String destination, String messageId) {
        if (destination == null || destination.isBlank() || messageId == null || messageId.isBlank()) {
            return false;
        }
        IbmMqMessageBroker broker = resolveTargetBroker(IbmMqMessageMapper.normalizeQueueName(destination));
        return broker != null && broker.delete(destination, messageId);
    }

    @Override
    public void purgeDestination(String destination) {
        if (destination == null || destination.isBlank()) {
            return;
        }
        IbmMqMessageBroker broker = resolveTargetBroker(IbmMqMessageMapper.normalizeQueueName(destination));
        if (broker != null) {
            broker.purge(destination);
        }
    }

    @Override
    public boolean publishMessage(String destination, AsyncDestinationMessage message) {
        if (destination == null || destination.isBlank() || message == null) {
            return false;
        }
        IbmMqMessageBroker broker = resolveTargetBroker(IbmMqMessageMapper.normalizeQueueName(destination));
        return broker != null && broker.sendDestinationMessage(destination, message);
    }

    private IbmMqMessageBroker resolveTargetBroker(String normalizedQueue) {
        Map<BrokerConnection, MessageBroker> brokers = registry.getOrCreateBrokersByProtocol("ibmmq");
        if (brokers.isEmpty()) {
            return null;
        }
        IbmMqMessageBroker first = null;
        for (Map.Entry<BrokerConnection, MessageBroker> entry : brokers.entrySet()) {
            if (entry.getValue() instanceof IbmMqMessageBroker mqBroker) {
                if (first == null) {
                    first = mqBroker;
                }
                if (mqBroker.getConfig().configuredQueues().contains(normalizedQueue)) {
                    return mqBroker;
                }
            }
        }
        if (brokers.size() == 1) {
            return first;
        }
        for (MessageBroker broker : brokers.values()) {
            if (broker instanceof IbmMqMessageBroker mqBroker) {
                for (AsyncDestination dest : mqBroker.discoverDestinations()) {
                    if (dest.name().equals(normalizedQueue)) {
                        return mqBroker;
                    }
                }
            }
        }
        return first;
    }
}
