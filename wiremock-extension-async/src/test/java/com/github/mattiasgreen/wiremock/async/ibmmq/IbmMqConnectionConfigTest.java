package com.github.mattiasgreen.wiremock.async.ibmmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.mattiasgreen.wiremock.async.connection.AsyncBrokerRegistry;
import com.github.mattiasgreen.wiremock.async.connection.BrokerConnection;
import com.github.mattiasgreen.wiremock.async.destination.AsyncDestinationService;
import com.github.mattiasgreen.wiremock.async.engine.AsyncEngine;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IbmMqConnectionConfigTest {

    @Test
    void shouldParseEndpointPropertiesAndDefaults() {
        BrokerConnection conn = new BrokerConnection(
                "corp-ibmmq",
                "ibmmq",
                "Corporate IBM MQ",
                "mq.internal:1415",
                Map.of(
                        "queueManager", "QM_CORE",
                        "channel", "APP.SVRCONN",
                        "ccsid", "819",
                        "username", "app",
                        "password", "secret",
                        "queues", "DEV.QUEUE.1, DEV.QUEUE.2"));

        IbmMqConnectionConfig cfg = IbmMqConnectionConfig.from(conn);

        assertThat(cfg.connectionId()).isEqualTo("corp-ibmmq");
        assertThat(cfg.host()).isEqualTo("mq.internal");
        assertThat(cfg.port()).isEqualTo(1415);
        assertThat(cfg.queueManager()).isEqualTo("QM_CORE");
        assertThat(cfg.channel()).isEqualTo("APP.SVRCONN");
        assertThat(cfg.ccsid()).isEqualTo(819);
        assertThat(cfg.username()).isEqualTo("app");
        assertThat(cfg.password()).isEqualTo("secret");
        assertThat(cfg.configuredQueues()).containsExactly("DEV.QUEUE.1", "DEV.QUEUE.2");
        assertThat(cfg.includeSystemQueues()).isFalse();
    }

    @Test
    void shouldDefaultPortAndResolveUriPrefixAndEnvVars() {
        String existingEnvVar = System.getenv().keySet().stream().findFirst().orElse("PATH");
        BrokerConnection conn = new BrokerConnection(
                "mq-env",
                "ibmmq",
                "Env MQ",
                "ibmmq://mq-host",
                Map.of("username", "${" + existingEnvVar + "}", "password", "${UNSET_TEST_SECRET}"));

        IbmMqConnectionConfig cfg = IbmMqConnectionConfig.from(conn);

        assertThat(cfg.host()).isEqualTo("mq-host");
        assertThat(cfg.port()).isEqualTo(IbmMqConnectionConfig.DEFAULT_PORT);
        assertThat(cfg.queueManager()).isEqualTo(IbmMqConnectionConfig.DEFAULT_QUEUE_MANAGER);
        assertThat(cfg.channel()).isEqualTo(IbmMqConnectionConfig.DEFAULT_CHANNEL);
        assertThat(cfg.ccsid()).isEqualTo(IbmMqConnectionConfig.DEFAULT_CCSID);
        assertThat(cfg.username()).isEqualTo(System.getenv(existingEnvVar));
        assertThat(cfg.password()).isNull();
    }

    @Test
    void shouldRejectInvalidPorts() {
        assertThatThrownBy(() -> new IbmMqConnectionConfig(
                        "id", "name", "localhost", 70000, "QM1", "CH1", 1208, null, null, null, null, null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid IBM MQ port");
    }

    @Test
    void shouldIdentifySystemQueuesAndNormalizeNames() {
        assertThat(IbmMqConnectionConfig.isSystemQueue("SYSTEM.DEFAULT.LOCAL.QUEUE"))
                .isTrue();
        assertThat(IbmMqConnectionConfig.isSystemQueue("AMQ.5D9A001")).isTrue();
        assertThat(IbmMqConnectionConfig.isSystemQueue("DEV.QUEUE.1")).isFalse();

        assertThat(IbmMqMessageMapper.normalizeQueueName("queue:///DEV.QUEUE.1 "))
                .isEqualTo("DEV.QUEUE.1");
        assertThat(IbmMqMessageMapper.normalizeQueueName("queue://QM1/DEV.QUEUE.2?targetClient=1"))
                .isEqualTo("DEV.QUEUE.2");
        assertThat(IbmMqMessageMapper.escapeSelectorLiteral("ID:123'abc")).isEqualTo("ID:123''abc");
        assertThat(IbmMqMessageMapper.toValidJmsPropertyName("x-tenant-id")).isEqualTo("x_tenant_id");
        assertThat(IbmMqMessageMapper.toValidJmsPropertyName("1-numeric")).isEqualTo("_1_numeric");
    }

    @Test
    void shouldAutoRegisterIbmMqProviderWhenClientIsOnClasspath() {
        AsyncDestinationService service = AsyncDestinationService.createForTest(AsyncEngine.getInstance());
        assertThat(service.getProvider("ibmmq")).isPresent();

        IbmMqDestinationProvider provider = new IbmMqDestinationProvider(AsyncBrokerRegistry.createForTest());
        assertThat(provider.listDestinations()).isEmpty();
        assertThat(provider.peekMessages("DEV.QUEUE.1", 10)).isEmpty();
    }
}
