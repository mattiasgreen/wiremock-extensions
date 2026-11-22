package com.github.mattiasgreen.wiremock.async.amqp;

import com.github.mattiasgreen.wiremock.async.bridge.MessageBroker;
import com.github.mattiasgreen.wiremock.async.destination.AsyncDestination;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.apache.qpid.protonj2.client.Client;
import org.apache.qpid.protonj2.client.Connection;
import org.apache.qpid.protonj2.client.ConnectionOptions;
import org.apache.qpid.protonj2.client.Delivery;
import org.apache.qpid.protonj2.client.Message;
import org.apache.qpid.protonj2.client.Receiver;
import org.apache.qpid.protonj2.client.ReceiverOptions;
import org.apache.qpid.protonj2.client.Sender;
import org.apache.qpid.protonj2.client.exceptions.ClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AMQP 1.0 MessageBroker implementation supporting both in-memory local queuing
 * and live outbound AMQP 1.0 proxying (e.g. to IBM MQ AMQP channels, ActiveMQ Artemis).
 */
public class AmqpMessageBroker implements MessageBroker, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AmqpMessageBroker.class);

    private final String name;
    private final String brokerUri;
    private final List<String> configuredQueues;
    private final Map<String, Deque<AsyncMessage>> localQueues = new ConcurrentHashMap<>();

    private volatile Client protonClient;
    private volatile Connection outboundConnection;

    public AmqpMessageBroker(String name) {
        this(name, null, Collections.emptyMap());
    }

    public AmqpMessageBroker(String name, String brokerUri) {
        this(name, brokerUri, Collections.emptyMap());
    }

    public AmqpMessageBroker(String name, String brokerUri, Map<String, String> properties) {
        this.name = Objects.requireNonNullElse(name, "amqp-broker");
        this.brokerUri = brokerUri;
        List<String> parsed = new ArrayList<>();
        if (properties != null) {
            String raw = properties.getOrDefault("queues", "");
            if (!raw.isBlank()) {
                for (String token : raw.split(",")) {
                    String q = token.trim();
                    if (!q.isEmpty()) {
                        parsed.add(q);
                    }
                }
            }
        }
        this.configuredQueues = Collections.unmodifiableList(parsed);
    }

    public String getName() {
        return name;
    }

    public String getBrokerUri() {
        return brokerUri;
    }

    private synchronized Connection getOrCreateConnection() throws ClientException {
        if (brokerUri == null || brokerUri.isBlank()) {
            return null;
        }
        if (outboundConnection == null) {
            try {
                URI uri = URI.create(brokerUri);
                String host = uri.getHost() != null ? uri.getHost() : "127.0.0.1";
                int port = uri.getPort() > 0 ? uri.getPort() : 5672;

                if (protonClient == null) {
                    protonClient = Client.create();
                }
                ConnectionOptions options = new ConnectionOptions();
                if (uri.getUserInfo() != null) {
                    String[] parts = uri.getUserInfo().split(":", 2);
                    options.user(parts[0]);
                    if (parts.length > 1) {
                        options.password(parts[1]);
                    }
                }
                outboundConnection = protonClient.connect(host, port, options);
                LOG.info("[AMQP-BROKER] Connected outbound AMQP client to {}:{}", host, port);
            } catch (Exception e) {
                String safeUri = sanitizeBrokerUri(brokerUri);
                String sanitized = sanitizeForLog(e.getMessage());
                LOG.error("[AMQP-BROKER] Failed to connect outbound AMQP client to {}: {}", safeUri, sanitized);
                throw new ClientException("Cannot connect to AMQP broker: " + safeUri, e);
            }
        }
        return outboundConnection;
    }

    @Override
    public void send(String channel, AsyncMessage message) {
        if (channel == null || message == null) {
            return;
        }

        if (brokerUri != null && !brokerUri.isBlank()) {
            try {
                Connection conn = getOrCreateConnection();
                if (conn != null) {
                    Sender sender = conn.openSender(channel);
                    Message<byte[]> protonMsg = Message.create(
                            message.payload() != null
                                    ? message.payload().getBytes(StandardCharsets.UTF_8)
                                    : new byte[0]);

                    if (message.id() != null && !message.id().isBlank()) {
                        protonMsg.messageId(message.id());
                    }
                    if (message.correlationId() != null
                            && !message.correlationId().isBlank()) {
                        protonMsg.correlationId(message.correlationId());
                    }
                    if (message.replyTo() != null && !message.replyTo().isBlank()) {
                        protonMsg.replyTo(message.replyTo());
                    }
                    protonMsg.to(channel);

                    if (message.headers() != null) {
                        for (Map.Entry<String, String> entry : message.headers().entrySet()) {
                            try {
                                protonMsg.property(entry.getKey(), entry.getValue());
                            } catch (ClientException ignored) {
                            }
                        }
                    }

                    sender.send(protonMsg);
                    sender.close();
                    return;
                }
            } catch (ClientException | RuntimeException e) {
                resetOutboundConnectionQuietly();
                String safeUri = sanitizeBrokerUri(brokerUri);
                String safeChannel = sanitizeForLog(channel);
                String sanitized = sanitizeForLog(e.getMessage());
                LOG.error("[AMQP-BROKER] Live send failed to {} for channel {}: {}", safeUri, safeChannel, sanitized);
                throw new IllegalStateException(
                        "AMQP delivery failed to " + safeUri + " [" + safeChannel + "]: " + sanitized, e);
            }
        }

        // Local in-process queuing when no remote brokerUri is configured
        localQueues.computeIfAbsent(channel, k -> new ArrayDeque<>()).add(message);
    }

    private synchronized void resetOutboundConnectionQuietly() {
        if (outboundConnection != null) {
            try {
                outboundConnection.close();
            } catch (Exception e) {
                LOG.debug("[AMQP-BROKER] Error resetting outbound connection: {}", sanitizeForLog(e.getMessage()));
            }
            outboundConnection = null;
        }
    }

    @Override
    public AsyncMessage poll(String channel) {
        if (channel == null) {
            return null;
        }

        if (brokerUri != null && !brokerUri.isBlank()) {
            try {
                Connection conn = getOrCreateConnection();
                if (conn != null) {
                    ReceiverOptions rOpts = new ReceiverOptions();
                    rOpts.creditWindow(1);
                    Receiver receiver = conn.openReceiver(channel, rOpts);
                    Delivery delivery = receiver.receive(200, TimeUnit.MILLISECONDS);
                    if (delivery != null) {
                        Message<?> msg = delivery.message();
                        delivery.accept();
                        receiver.close();

                        String payloadStr = "";
                        Object body = msg.body();
                        if (body instanceof byte[] b) {
                            payloadStr = new String(b, StandardCharsets.UTF_8);
                        } else if (body != null) {
                            payloadStr = String.valueOf(body);
                        }

                        Map<String, String> headers = new ConcurrentHashMap<>();
                        msg.forEachProperty((k, v) -> headers.put(k, String.valueOf(v)));

                        return AsyncMessage.builder()
                                .id(msg.messageId() != null ? String.valueOf(msg.messageId()) : null)
                                .correlationId(msg.correlationId() != null ? String.valueOf(msg.correlationId()) : null)
                                .replyTo(msg.replyTo())
                                .destination(channel)
                                .protocol("amqp")
                                .payload(payloadStr)
                                .headers(headers)
                                .build();
                    }
                    receiver.close();
                }
            } catch (Exception e) {
                LOG.debug(
                        "[AMQP-BROKER] Live poll timeout or error on channel {}: {}",
                        sanitizeForLog(channel),
                        sanitizeForLog(e.getMessage()));
            }
        }

        Deque<AsyncMessage> queue = localQueues.get(channel);
        return (queue != null && !queue.isEmpty()) ? queue.poll() : null;
    }

    @Override
    public int size(String channel) {
        if (channel == null) {
            return 0;
        }
        Deque<AsyncMessage> queue = localQueues.get(channel);
        return queue != null ? queue.size() : 0;
    }

    @Override
    public List<String> listChannels() {
        Set<String> all = new LinkedHashSet<>(localQueues.keySet());
        all.addAll(configuredQueues);
        return Collections.unmodifiableList(new ArrayList<>(all));
    }

    @Override
    public List<AsyncDestination> discoverDestinations() {
        List<AsyncDestination> list = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String q : localQueues.keySet()) {
            seen.add(q);
            list.add(new AsyncDestination("amqp:" + name + ":" + q, "amqp", q, name, size(q), Map.of("type", "QUEUE")));
        }
        for (String q : configuredQueues) {
            if (seen.add(q)) {
                list.add(new AsyncDestination(
                        "amqp:" + name + ":" + q,
                        "amqp",
                        q,
                        name,
                        0,
                        Map.of("type", "QUEUE", "discovery", "CONFIGURED")));
            }
        }
        return Collections.unmodifiableList(list);
    }

    @Override
    public boolean ownsDestination(String destination) {
        return destination != null && (configuredQueues.contains(destination) || localQueues.containsKey(destination));
    }

    @Override
    public List<AsyncMessage> peek(String channel) {
        if (channel == null) {
            return Collections.emptyList();
        }
        Deque<AsyncMessage> queue = localQueues.get(channel);
        return queue != null ? Collections.unmodifiableList(new ArrayList<>(queue)) : Collections.emptyList();
    }

    @Override
    public void purge(String channel) {
        if (channel == null) {
            return;
        }
        Deque<AsyncMessage> queue = localQueues.get(channel);
        if (queue != null) {
            queue.clear();
        }
    }

    @Override
    public synchronized void close() {
        localQueues.clear();
        if (outboundConnection != null) {
            try {
                outboundConnection.close();
            } catch (Exception e) {
                LOG.debug("[AMQP-BROKER] Error closing outbound connection: {}", sanitizeForLog(e.getMessage()));
            }
            outboundConnection = null;
        }
        if (protonClient != null) {
            try {
                protonClient.close();
            } catch (Exception e) {
                LOG.debug("[AMQP-BROKER] Error closing client: {}", sanitizeForLog(e.getMessage()));
            }
            protonClient = null;
        }
    }

    private static String sanitizeForLog(String value) {
        return Objects.toString(value, "").replaceAll("[\r\n]", "");
    }

    private static String sanitizeBrokerUri(String rawUri) {
        if (rawUri == null || rawUri.isBlank()) {
            return "";
        }
        String noCrlf = sanitizeForLog(rawUri);
        try {
            URI parsed = URI.create(noCrlf);
            if (parsed.getRawUserInfo() == null) {
                return noCrlf;
            }
            String scheme = parsed.getScheme() != null ? parsed.getScheme() + "://" : "";
            String host = parsed.getHost() != null ? parsed.getHost() : "";
            String port = parsed.getPort() > 0 ? ":" + parsed.getPort() : "";
            String path = parsed.getRawPath() != null ? parsed.getRawPath() : "";
            return scheme + "***@" + host + port + path;
        } catch (IllegalArgumentException e) {
            return noCrlf.replaceAll("(?<=://)[^/@]+@", "***@");
        }
    }
}
