package com.github.mattiasgreen.wiremock.async.destination;

import com.github.mattiasgreen.wiremock.async.amqp.AmqpServer;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@link DestinationProvider} adapter backed by an in-process {@link AmqpServer}.
 *
 * <p><b>Note:</b> This is Beta functionality subject to evolution.
 */
public final class AmqpServerDestinationAdapter implements DestinationProvider {

    private static final String PROTOCOL = "amqp";

    private final Supplier<AmqpServer> serverSupplier;

    public AmqpServerDestinationAdapter(Supplier<AmqpServer> serverSupplier) {
        this.serverSupplier = Objects.requireNonNull(serverSupplier, "serverSupplier must not be null");
    }

    @Override
    public String getProtocol() {
        return PROTOCOL;
    }

    @Override
    public List<AsyncDestination> listDestinations() {
        AmqpServer server = serverSupplier.get();
        if (server == null) {
            return Collections.emptyList();
        }
        List<String> queues = server.listQueues();
        List<AsyncDestination> result = new ArrayList<>(queues.size());
        for (String q : queues) {
            int size = server.peekQueue(q).size();
            result.add(
                    new AsyncDestination("amqp:" + q, PROTOCOL, q, "local-amqp-server", size, Map.of("type", "QUEUE")));
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public List<AsyncMessage> peekMessages(String destination, int limit) {
        AmqpServer server = serverSupplier.get();
        if (server == null || destination == null || destination.isBlank()) {
            return Collections.emptyList();
        }
        List<AsyncMessage> staged = server.peekQueue(destination);
        int max = limit > 0 ? Math.min(limit, staged.size()) : staged.size();
        List<AsyncMessage> result = new ArrayList<>(max);
        for (int i = 0; i < max; i++) {
            AsyncMessage m = staged.get(i);
            result.add(AsyncMessage.builder()
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
        AmqpServer server = serverSupplier.get();
        if (server == null
                || destination == null
                || destination.isBlank()
                || messageId == null
                || messageId.isBlank()) {
            return false;
        }
        return server.deleteMessage(destination, messageId);
    }

    @Override
    public void purgeDestination(String destination) {
        AmqpServer server = serverSupplier.get();
        if (server != null && destination != null && !destination.isBlank()) {
            server.purgeQueue(destination);
        }
    }

    @Override
    public boolean publishMessage(String destination, AsyncMessage message) {
        AmqpServer server = serverSupplier.get();
        if (server == null || destination == null || destination.isBlank() || message == null) {
            return false;
        }
        server.stageFeedMessage(destination, message);
        return true;
    }
}
