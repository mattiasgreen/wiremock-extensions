package com.github.mattiasgreen.wiremock.async.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AsyncBrokerRegistryTest {

    private AsyncBrokerRegistry registry;

    @BeforeEach
    void setUp() {
        registry = AsyncBrokerRegistry.createForTest();
    }

    @Test
    @DisplayName("Should register, lookup, and delete broker connection in-memory")
    void shouldRegisterAndManageConnections() {
        BrokerConnection connection = new BrokerConnection(
                "dev-kafka",
                "kafka",
                "Kafka Dev Cluster",
                "localhost:9092",
                Map.of("security.protocol", "PLAINTEXT", "client.id", "wm-test"));

        registry.register(connection);

        assertThat(registry.getConnection("dev-kafka")).isPresent().contains(connection);
        assertThat(registry.listConnections()).hasSize(1);

        boolean removed = registry.remove("dev-kafka");
        assertThat(removed).isTrue();
        assertThat(registry.getConnection("dev-kafka")).isEmpty();
    }

    @Test
    @DisplayName("Should load declarative connections from JSON input stream without persisting to disk")
    void shouldLoadDeclarativeConnectionsFromJson() {
        String json =
                """
        {
          "connections": [
            {
              "id": "corp-amqp",
              "protocol": "amqp",
              "name": "Corporate ActiveMQ Artemis",
              "endpoint": "amqp://mq.corp.internal:5672",
              "properties": {
                "user": "artemis-admin",
                "password": "secret-password"
              }
            },
            {
              "id": "prod-kafka",
              "protocol": "kafka",
              "name": "Production Kafka Cluster",
              "endpoint": "kafka.prod.internal:9094",
              "properties": {
                "security.protocol": "SASL_SSL",
                "sasl.mechanism": "SCRAM-SHA-512"
              }
            }
          ]
        }
        """;

        registry.loadDeclarative(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertThat(registry.listConnections()).hasSize(2);
        BrokerConnection amqp = registry.getConnection("corp-amqp").orElseThrow();
        assertThat(amqp.protocol()).isEqualTo("amqp");
        assertThat(amqp.endpoint()).isEqualTo("amqp://mq.corp.internal:5672");
        assertThat(amqp.properties()).containsEntry("user", "artemis-admin");

        // Verify secret masking for UI/API exposition
        Map<String, Object> sanitized = registry.exportSanitized("corp-amqp");
        assertThat(sanitized).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, String> sanitizedProps = (Map<String, String>) sanitized.get("properties");
        assertThat(sanitizedProps.get("password")).isEqualTo("******");
    }

    @Test
    @DisplayName("Should reject invalid or missing connection IDs")
    void shouldRejectInvalidConnection() {
        assertThatThrownBy(() -> registry.register(null)).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new BrokerConnection("", "kafka", "Invalid", "localhost:9092", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
