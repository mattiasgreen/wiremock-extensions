package com.github.mattiasgreen.wiremock.async.destination;

import com.github.mattiasgreen.wiremock.async.bridge.MessageBroker;
import com.github.mattiasgreen.wiremock.async.connection.AsyncBrokerRegistry;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link DestinationProvider} adapter backed by pooled remote {@link MessageBroker} instances
 * registered in an {@link AsyncBrokerRegistry} for a given protocol.
 *
 * <p><b>Note:</b> This is Beta functionality subject to evolution.
 */
public final class BrokerRegistryDestinationAdapter implements DestinationProvider {

    private static final Logger LOG = LoggerFactory.getLogger(BrokerRegistryDestinationAdapter.class);

    private final String protocol;
    private final Supplier<AsyncBrokerRegistry> registrySupplier;

    public BrokerRegistryDestinationAdapter(String protocol, Supplier<AsyncBrokerRegistry> registrySupplier) {
        Objects.requireNonNull(protocol, "protocol must not be null");
        this.protocol = protocol.toLowerCase(Locale.ROOT);
        this.registrySupplier = Objects.requireNonNull(registrySupplier, "registrySupplier must not be null");
    }

    @Override
    public String getProtocol() {
        return protocol;
    }

    @Override
    public List<AsyncDestination> listDestinations() {
        AsyncBrokerRegistry registry = registrySupplier.get();
        if (registry == null) {
            return Collections.emptyList();
        }
        return registry.discoverDestinations(protocol);
    }

    @Override
    public List<AsyncMessage> peekMessages(String destination, int limit) {
        AsyncBrokerRegistry registry = registrySupplier.get();
        if (registry == null || destination == null || destination.isBlank()) {
            return Collections.emptyList();
        }
        List<AsyncMessage> result = new ArrayList<>();
        for (MessageBroker broker : registry.resolveTargetBrokers(protocol, destination)) {
            int remaining = limit > 0 ? Math.max(0, limit - result.size()) : 0;
            if (limit > 0 && remaining == 0) {
                break;
            }
            List<AsyncMessage> peeked = broker.peek(destination, remaining);
            if (peeked != null && !peeked.isEmpty()) {
                result.addAll(peeked);
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public boolean deleteMessage(String destination, String messageId) {
        AsyncBrokerRegistry registry = registrySupplier.get();
        if (registry == null
                || destination == null
                || destination.isBlank()
                || messageId == null
                || messageId.isBlank()) {
            return false;
        }
        for (MessageBroker broker : registry.resolveTargetBrokers(protocol, destination)) {
            if (broker.delete(destination, messageId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void purgeDestination(String destination) {
        AsyncBrokerRegistry registry = registrySupplier.get();
        if (registry == null || destination == null || destination.isBlank()) {
            return;
        }
        for (MessageBroker broker : registry.resolveTargetBrokers(protocol, destination)) {
            broker.purge(destination);
        }
    }

    @Override
    public boolean publishMessage(String destination, AsyncMessage message) {
        AsyncBrokerRegistry registry = registrySupplier.get();
        if (registry == null || destination == null || destination.isBlank() || message == null) {
            return false;
        }
        List<MessageBroker> targets = registry.resolveTargetBrokers(protocol, destination);
        if (targets.isEmpty()) {
            return false;
        }
        Map<String, String> headers = new LinkedHashMap<>(message.headers());
        if (message.correlationId() != null && !message.correlationId().isBlank()) {
            headers.putIfAbsent("x-correlation-id", message.correlationId());
        }
        AsyncMessage outbound = AsyncMessage.builder()
                .id(message.id())
                .protocol(protocol)
                .key(message.key())
                .destination(destination)
                .correlationId(message.correlationId())
                .replyTo(message.replyTo())
                .payload(message.payload())
                .headers(headers)
                .timestamp(message.timestamp())
                .partition(message.partition())
                .offset(message.offset())
                .build();
        boolean published = false;
        for (MessageBroker broker : targets) {
            try {
                broker.send(destination, outbound);
                published = true;
            } catch (RuntimeException e) {
                String safeDest = destination.replaceAll("[\r\n]", "");
                LOG.warn("[ASYNC-ENGINE] Failed publishing to {} destination [{}]", protocol, safeDest);
            }
        }
        return published;
    }
}
