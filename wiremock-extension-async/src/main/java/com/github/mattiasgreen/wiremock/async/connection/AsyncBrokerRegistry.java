package com.github.mattiasgreen.wiremock.async.connection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe centralized registry for asynchronous broker connections.
 * Ingests single connection descriptors or batch JSON arrays with upsert semantics,
 * and masks plaintext secrets while preserving declarative ${VAR_NAME} tokens on export.
 */
public class AsyncBrokerRegistry {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncBrokerRegistry.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SENSITIVE_KEYS =
            Set.of("password", "secret", "sasl.jaas.config", "key", "token", "credential");

    private static final AsyncBrokerRegistry INSTANCE = new AsyncBrokerRegistry();

    private final Map<String, BrokerConnection> connections = new ConcurrentHashMap<>();

    private AsyncBrokerRegistry() {}

    public static AsyncBrokerRegistry getInstance() {
        return INSTANCE;
    }

    public static AsyncBrokerRegistry createForTest() {
        return new AsyncBrokerRegistry();
    }

    public void reset() {
        connections.clear();
    }

    public void register(BrokerConnection connection) {
        Objects.requireNonNull(connection, "connection must not be null");
        connections.put(connection.id(), connection);
        String safeId = connection.id().replaceAll("[\r\n]", "");
        LOG.info("[ASYNC-REGISTRY] Registered broker connection [{}] ({})", safeId, connection.protocol());
    }

    public Optional<BrokerConnection> getConnection(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(connections.get(id));
    }

    public List<BrokerConnection> listConnections() {
        return Collections.unmodifiableList(new ArrayList<>(connections.values()));
    }

    public boolean remove(String id) {
        if (id == null) {
            return false;
        }
        return connections.remove(id) != null;
    }

    public void clear() {
        connections.clear();
    }

    /**
     * Parses a single connection node or a collection of nodes and registers them with upsert semantics.
     *
     * @param root JSON node containing a single connection or an array/envelope
     * @return list of registered/updated connections
     */
    public List<BrokerConnection> registerFromJson(JsonNode root) {
        if (root == null || root.isNull()) {
            return Collections.emptyList();
        }

        List<JsonNode> nodesToProcess = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(nodesToProcess::add);
        } else if (root.hasNonNull("connections") && root.get("connections").isArray()) {
            root.get("connections").forEach(nodesToProcess::add);
        } else if (root.isObject() && root.hasNonNull("id")) {
            nodesToProcess.add(root);
        }

        List<BrokerConnection> registered = new ArrayList<>();
        for (JsonNode node : nodesToProcess) {
            String id = node.hasNonNull("id") ? node.get("id").asText().trim() : "";
            if (id.isEmpty()) continue;

            String protocol =
                    node.hasNonNull("protocol") ? node.get("protocol").asText().toLowerCase(Locale.ROOT) : "kafka";
            String name = node.hasNonNull("name") ? node.get("name").asText() : id;
            String endpoint = node.hasNonNull("endpoint") ? node.get("endpoint").asText() : "";

            Map<String, String> properties = new LinkedHashMap<>();
            if (node.hasNonNull("properties") && node.get("properties").isObject()) {
                node.get("properties")
                        .fields()
                        .forEachRemaining(entry ->
                                properties.put(entry.getKey(), entry.getValue().asText()));
            }

            BrokerConnection connection = new BrokerConnection(id, protocol, name, endpoint, properties);
            register(connection);
            registered.add(connection);
        }
        return Collections.unmodifiableList(registered);
    }

    /**
     * Loads declarative broker connections from a JSON input stream.
     * Operates purely in-memory with zero file writes.
     */
    public void loadDeclarative(InputStream inputStream) {
        if (inputStream == null) {
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(inputStream);
            registerFromJson(root);
        } catch (Exception e) {
            String sanitized = Objects.toString(e.getMessage(), "").replaceAll("[\r\n]", "");
            LOG.error("[ASYNC-REGISTRY] Failed to parse declarative connections: {}", sanitized);
            throw new IllegalArgumentException("Invalid declarative connections JSON: " + sanitized, e);
        }
    }

    /**
     * Exports connection details with sensitive secrets masked for Web Console UI presentation.
     * Declarative environment variable references (${VAR_NAME}) are preserved verbatim.
     */
    public Map<String, Object> exportSanitized(String id) {
        BrokerConnection conn = connections.get(id);
        if (conn == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", conn.id());
        map.put("protocol", conn.protocol());
        map.put("name", conn.name());
        map.put("endpoint", conn.endpoint());

        Map<String, String> sanitizedProps = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : conn.properties().entrySet()) {
            String keyLower = entry.getKey().toLowerCase(Locale.ROOT);
            boolean isSecretKey = SENSITIVE_KEYS.stream().anyMatch(keyLower::contains);
            String val = entry.getValue();
            boolean isEnvToken = val != null && val.startsWith("${") && val.endsWith("}");
            sanitizedProps.put(entry.getKey(), (isSecretKey && !isEnvToken) ? "******" : val);
        }
        map.put("properties", sanitizedProps);
        map.put("status", "HEALTHY");
        return map;
    }

    public List<Map<String, Object>> listSanitized() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (BrokerConnection conn : connections.values()) {
            result.add(exportSanitized(conn.id()));
        }
        return Collections.unmodifiableList(result);
    }
}
