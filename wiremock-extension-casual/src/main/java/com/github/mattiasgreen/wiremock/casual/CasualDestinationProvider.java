package com.github.mattiasgreen.wiremock.casual;

import com.github.mattiasgreen.wiremock.async.connection.AsyncBrokerRegistry;
import com.github.mattiasgreen.wiremock.async.connection.BrokerConnection;
import com.github.mattiasgreen.wiremock.async.destination.AsyncDestination;
import com.github.mattiasgreen.wiremock.async.destination.AsyncDestinationMessage;
import com.github.mattiasgreen.wiremock.async.destination.DestinationProvider;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Typed {@link DestinationProvider} for Casual XATMI queues, unifying local {@link CasualQueueStore}
 * inspection with remote {@link CasualRemoteQueueInspector} lookups behind the explicit port seam.
 */
public class CasualDestinationProvider implements DestinationProvider {

    private final Supplier<Optional<CasualQueueStore>> localStoreSupplier;
    private final AsyncBrokerRegistry brokerRegistry;

    public CasualDestinationProvider() {
        this(
                () -> CasualRegistry.getInstance().getGateway().map(CasualWireMockGateway::getQueueStore),
                AsyncBrokerRegistry.getInstance());
    }

    public CasualDestinationProvider(
            Supplier<Optional<CasualQueueStore>> localStoreSupplier, AsyncBrokerRegistry brokerRegistry) {
        this.localStoreSupplier = Objects.requireNonNull(localStoreSupplier, "localStoreSupplier must not be null");
        this.brokerRegistry = Objects.requireNonNull(brokerRegistry, "brokerRegistry must not be null");
    }

    @Override
    public String getProtocol() {
        return "casual";
    }

    @Override
    public List<AsyncDestination> listDestinations() {
        List<AsyncDestination> list = new ArrayList<>();
        localStoreSupplier.get().ifPresent(store -> {
            for (String q : store.listQueues()) {
                int count = store.getQueueSize(q);
                list.add(new AsyncDestination(
                        "casual:" + q,
                        "casual",
                        q,
                        "local-casual-gateway",
                        count,
                        Map.of("dlq", q.endsWith(".error"))));
            }
        });
        for (BrokerConnection conn : listRemoteCasualConnections()) {
            String rawQueues = conn.properties().getOrDefault("queues", "");
            if (!rawQueues.isBlank()) {
                for (String token : rawQueues.split(",")) {
                    String q = token.trim();
                    if (!q.isEmpty()) {
                        list.add(new AsyncDestination(
                                "casual:" + conn.id() + ":" + q,
                                "casual",
                                q,
                                conn.id(),
                                0,
                                Map.of("discovery", "CONFIGURED", "dlq", q.endsWith(".error"))));
                    }
                }
            }
        }
        return Collections.unmodifiableList(list);
    }

    @Override
    public List<AsyncDestinationMessage> peekMessages(String destination, int limit) {
        if (destination == null || destination.isBlank()) {
            return Collections.emptyList();
        }
        List<AsyncDestinationMessage> result = new ArrayList<>();
        localStoreSupplier.get().ifPresent(store -> {
            List<CasualQueueStore.StoredMessage> messages = store.peekMessages(destination);
            int max = limit > 0 ? Math.min(limit, messages.size()) : messages.size();
            for (int i = 0; i < max; i++) {
                CasualQueueStore.StoredMessage sm = messages.get(i);
                String payload = sm.getPayload() != null ? CasualBufferUtils.extractPayload(sm.getPayload()) : "";
                String bufType = sm.getPayload() != null ? sm.getPayload().getType() : "JSON";
                result.add(new AsyncDestinationMessage(
                        sm.getId().toString(),
                        "casual",
                        destination,
                        null,
                        sm.getCorrelationInformation(),
                        sm.getReplyQueue(),
                        sm.getTimestamp() != null ? sm.getTimestamp() : Instant.now(),
                        payload.getBytes(StandardCharsets.UTF_8).length,
                        Map.of("X-Casual-Buffer-Type", bufType),
                        payload,
                        null,
                        null));
            }
        });

        int maxRemote = limit > 0 ? limit : 50;
        for (BrokerConnection conn : listRemoteCasualConnections()) {
            if (limit > 0 && result.size() >= limit) {
                break;
            }
            CasualClient client = createClient(conn);
            if (client == null) {
                continue;
            }
            try {
                CasualRemoteQueueInspector inspector = new CasualRemoteQueueInspector(client);
                for (CasualRemoteMessage rm : inspector.peekMessages(destination, maxRemote)) {
                    String payload = Objects.requireNonNullElse(rm.payload(), "");
                    result.add(new AsyncDestinationMessage(
                            rm.id() != null
                                    ? rm.id().toString()
                                    : UUID.randomUUID().toString(),
                            "casual",
                            destination,
                            null,
                            rm.correlationInformation(),
                            rm.replyQueue(),
                            rm.timestamp() != null ? rm.timestamp() : Instant.now(),
                            payload.getBytes(StandardCharsets.UTF_8).length,
                            Map.of("X-Casual-Buffer-Type", Objects.requireNonNullElse(rm.bufferType(), "JSON")),
                            payload,
                            null,
                            null));
                    if (limit > 0 && result.size() >= limit) {
                        break;
                    }
                }
            } finally {
                client.close();
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public boolean deleteMessage(String destination, String messageId) {
        if (destination == null || messageId == null) {
            return false;
        }
        Optional<CasualQueueStore> storeOpt = localStoreSupplier.get();
        if (storeOpt.isEmpty()) {
            return false;
        }
        try {
            return storeOpt.get().deleteMessage(destination, UUID.fromString(messageId));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    @Override
    public void purgeDestination(String destination) {
        if (destination == null || destination.isBlank()) {
            return;
        }
        localStoreSupplier.get().ifPresent(store -> store.purgeQueue(destination));
        for (BrokerConnection conn : listRemoteCasualConnections()) {
            CasualClient client = createClient(conn);
            if (client != null) {
                try {
                    new CasualRemoteQueueInspector(client).purgeQueue(destination);
                } finally {
                    client.close();
                }
            }
        }
    }

    @Override
    public boolean publishMessage(String destination, AsyncDestinationMessage message) {
        if (destination == null || destination.isBlank() || message == null) {
            return false;
        }
        Optional<CasualQueueStore> storeOpt = localStoreSupplier.get();
        if (storeOpt.isEmpty()) {
            return false;
        }
        UUID id = parseUuidOrNull(message.id());
        String bufferType =
                message.headers() != null ? message.headers().getOrDefault("X-Casual-Buffer-Type", "JSON") : "JSON";
        var buffer = CasualBufferUtils.wrapPayload(bufferType, Objects.requireNonNullElse(message.payload(), ""));
        storeOpt.get().enqueueDirect(destination, id, message.correlationId(), message.replyTo(), buffer);
        return true;
    }

    private List<BrokerConnection> listRemoteCasualConnections() {
        List<BrokerConnection> resolved = new ArrayList<>();
        for (BrokerConnection conn : brokerRegistry.listConnections()) {
            if ("casual".equals(conn.protocol().toLowerCase(Locale.ROOT))) {
                resolved.add(conn.resolveWithEnv());
            }
        }
        return resolved;
    }

    private static CasualClient createClient(BrokerConnection conn) {
        String endpoint = conn.endpoint().trim();
        if (endpoint.isEmpty()) {
            return null;
        }
        URI uri = URI.create(endpoint.contains("://") ? endpoint : "casual://" + endpoint);
        String host = uri.getHost() != null ? uri.getHost() : "127.0.0.1";
        int port = uri.getPort() > 0 ? uri.getPort() : 7772;
        return new CasualClient(host, port);
    }

    private static UUID parseUuidOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
