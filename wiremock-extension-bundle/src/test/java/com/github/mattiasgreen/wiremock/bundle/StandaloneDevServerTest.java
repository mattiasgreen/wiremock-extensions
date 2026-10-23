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
}
