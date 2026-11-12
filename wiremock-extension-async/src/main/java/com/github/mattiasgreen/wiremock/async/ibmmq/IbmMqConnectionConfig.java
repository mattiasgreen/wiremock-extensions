package com.github.mattiasgreen.wiremock.async.ibmmq;

import com.github.mattiasgreen.wiremock.async.connection.BrokerConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Validated IBM MQ connection descriptor parsed from a {@link BrokerConnection}.
 * Resolves {@code ${VAR_NAME}} environment variable tokens dynamically at construction time.
 *
 * <p><b>Note:</b> Beta functionality subject to evolution.
 */
public record IbmMqConnectionConfig(
        String connectionId,
        String name,
        String host,
        int port,
        String queueManager,
        String channel,
        int ccsid,
        String username,
        String password,
        String sslCipherSuite,
        String sslPeerName,
        List<String> configuredQueues,
        boolean includeSystemQueues) {

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 1414;
    public static final String DEFAULT_QUEUE_MANAGER = "QM1";
    public static final String DEFAULT_CHANNEL = "DEV.APP.SVRCONN";
    public static final int DEFAULT_CCSID = 1208;

    public IbmMqConnectionConfig {
        Objects.requireNonNull(connectionId, "connectionId must not be null");
        name = Objects.requireNonNullElse(name, connectionId);
        host = (host == null || host.isBlank()) ? DEFAULT_HOST : host.trim();
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid IBM MQ port: " + port);
        }
        queueManager = (queueManager == null || queueManager.isBlank()) ? DEFAULT_QUEUE_MANAGER : queueManager.trim();
        channel = (channel == null || channel.isBlank()) ? DEFAULT_CHANNEL : channel.trim();
        if (ccsid <= 0) {
            ccsid = DEFAULT_CCSID;
        }
        configuredQueues = configuredQueues != null
                ? Collections.unmodifiableList(new ArrayList<>(configuredQueues))
                : Collections.emptyList();
    }

    /**
     * Parses a {@link BrokerConnection} and expands {@code ${VAR_NAME}} secret placeholders.
     */
    public static IbmMqConnectionConfig from(BrokerConnection rawConnection) {
        Objects.requireNonNull(rawConnection, "rawConnection must not be null");
        BrokerConnection resolved = rawConnection.resolveWithEnv();
        HostPort hp = parseEndpoint(resolved.endpoint());
        Map<String, String> props = resolved.properties();

        String qmgr = props.getOrDefault("queueManager", DEFAULT_QUEUE_MANAGER);
        String channel = props.getOrDefault("channel", DEFAULT_CHANNEL);
        int ccsid = parsePositiveInt(props.get("ccsid"), DEFAULT_CCSID);
        String username = trimToNull(props.get("username"));
        String password = props.get("password");
        if (password != null && password.isEmpty()) {
            password = null;
        }
        String sslCipherSuite = trimToNull(props.get("sslCipherSuite"));
        String sslPeerName = trimToNull(props.get("sslPeerName"));
        List<String> queues = parseQueueList(props.get("queues"));
        boolean includeSystem = Boolean.parseBoolean(props.getOrDefault("includeSystemQueues", "false"));

        return new IbmMqConnectionConfig(
                resolved.id(),
                resolved.name(),
                hp.host(),
                hp.port(),
                qmgr,
                channel,
                ccsid,
                username,
                password,
                sslCipherSuite,
                sslPeerName,
                queues,
                includeSystem);
    }

    /**
     * Returns whether a destination name represents an internal IBM MQ system or temporary queue.
     */
    public static boolean isSystemQueue(String queueName) {
        if (queueName == null || queueName.isBlank()) {
            return true;
        }
        String upper = queueName.trim().toUpperCase(Locale.ROOT);
        return upper.startsWith("SYSTEM.") || upper.startsWith("AMQ.");
    }

    private static HostPort parseEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return new HostPort(DEFAULT_HOST, DEFAULT_PORT);
        }
        String trimmed = endpoint.trim();
        String uriCandidate = trimmed.contains("://") ? trimmed : "ibmmq://" + trimmed;
        URI uri = URI.create(uriCandidate);
        String host = (uri.getHost() != null && !uri.getHost().isBlank()) ? uri.getHost() : DEFAULT_HOST;
        int port = uri.getPort() > 0 ? uri.getPort() : DEFAULT_PORT;
        return new HostPort(host, port);
    }

    private static int parsePositiveInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static List<String> parseQueueList(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptyList();
        }
        List<String> list = new ArrayList<>();
        for (String token : raw.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return list;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record HostPort(String host, int port) {}
}
