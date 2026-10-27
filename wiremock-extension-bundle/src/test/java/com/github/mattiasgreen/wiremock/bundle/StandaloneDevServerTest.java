package com.github.mattiasgreen.wiremock.bundle;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.casual.CasualRegistry;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StandaloneDevServerTest {

    private WireMockServer server;

    @AfterEach
    void tearDown() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
        CasualRegistry.getInstance().reset();
        System.clearProperty("wiremock.casual.port");
        System.clearProperty("wiremock.netem.port");
    }

    @Test
    void testStandaloneDevServerStartsWithoutPrePopulatedStubs() {
        System.setProperty("wiremock.casual.port", "0");
        server = StandaloneDevServer.startServer(0);

        assertThat(server.isRunning()).isTrue();
        assertThat(server.listAllStubMappings().getMappings()).isEmpty();
        assertThat(CasualRegistry.getInstance().getConfig()).isNotNull();
        assertThat(CasualRegistry.getInstance().getGateway()).isPresent();
        assertThat(CasualRegistry.getInstance().getGateway().get().isRunning()).isTrue();
    }

    @Test
    void testParseMainPortProgramArguments() {
        assertThat(StandaloneDevServer.parseMainPort(null, 8080)).isEqualTo(8080);
        assertThat(StandaloneDevServer.parseMainPort(new String[0], 8080)).isEqualTo(8080);
        assertThat(StandaloneDevServer.parseMainPort(new String[] {"--port", "9090"}, 8080))
                .isEqualTo(9090);
        assertThat(StandaloneDevServer.parseMainPort(new String[] {"--port=9091"}, 8080))
                .isEqualTo(9091);
        assertThat(StandaloneDevServer.parseMainPort(new String[] {"-port", "9092"}, 8080))
                .isEqualTo(9092);
        assertThat(StandaloneDevServer.parseMainPort(new String[] {"-port=9093"}, 8080))
                .isEqualTo(9093);
        assertThat(StandaloneDevServer.parseMainPort(new String[] {"9094"}, 8080))
                .isEqualTo(9094);
        assertThat(StandaloneDevServer.parseMainPort(new String[] {"--verbose", "--port", "9095"}, 8080))
                .isEqualTo(9095);
        assertThat(StandaloneDevServer.parseMainPort(new String[] {"--verbose"}, 8080))
                .isEqualTo(8080);
    }

    @Test
    void testResolveNetEmPort() {
        // Default wiremock port 8080 -> default netem port 8081
        assertThat(StandaloneDevServer.resolveNetEmPort(8080)).isEqualTo(8081);

        // Dynamic wiremock port 0 -> dynamic netem port 0
        assertThat(StandaloneDevServer.resolveNetEmPort(0)).isEqualTo(0);

        // Custom wiremock port -> port + 1
        assertThat(StandaloneDevServer.resolveNetEmPort(9000)).isEqualTo(9001);

        // System property override
        try {
            System.setProperty(StandaloneDevServer.PROP_NETEM_PORT, "8888");
            assertThat(StandaloneDevServer.resolveNetEmPort(8080)).isEqualTo(8888);
        } finally {
            System.clearProperty(StandaloneDevServer.PROP_NETEM_PORT);
        }
    }
}
