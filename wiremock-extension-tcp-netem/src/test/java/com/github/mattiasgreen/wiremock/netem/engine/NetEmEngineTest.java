package com.github.mattiasgreen.wiremock.netem.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.netem.model.NetEmProxyConfig;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicConfig;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicType;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NetEmEngineTest {

    private NetEmEngine engine;

    @BeforeEach
    void setUp() {
        engine = new NetEmEngine();
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    @DisplayName("Should register proxy and manage transient toxics via ToxicLease")
    void shouldManageTransientToxicsViaLease() throws IOException {
        NetEmProxyConfig config = new NetEmProxyConfig("test-proxy", 0, "127.0.0.1", 8080, true);
        TcpNetEmProxy proxy = engine.register(config);

        assertThat(proxy.getToxics()).isEmpty();

        NetEmToxicConfig toxic = new NetEmToxicConfig(
                "latency-1",
                NetEmToxicType.LATENCY,
                com.github.mattiasgreen.wiremock.netem.model.NetEmStreamDirection.DOWNSTREAM,
                1.0,
                null,
                "ASCII",
                null,
                Map.of("latency", 100));

        // 1. Acquire lease
        try (NetEmEngine.ToxicLease lease =
                engine.applyTransientToxics("corr-123", List.of(toxic), Duration.ofSeconds(10))) {
            assertThat(lease.getAppliedToxicNames()).containsExactly("latency-1");
            assertThat(proxy.getToxics()).hasSize(1);
        }

        // 2. Verify deterministic cleanup on lease close
        assertThat(proxy.getToxics()).isEmpty();
    }
}
