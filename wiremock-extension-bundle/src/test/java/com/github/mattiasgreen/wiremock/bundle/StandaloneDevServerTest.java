package com.github.mattiasgreen.wiremock.bundle;

import static org.assertj.core.api.Assertions.assertThat;

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
    }

    @Test
    void testStandaloneDevServerStartsWithoutPrePopulatedStubs() {
        server = StandaloneDevServer.startServer(0);

        assertThat(server.isRunning()).isTrue();
        assertThat(server.listAllStubMappings().getMappings()).isEmpty();
    }
}
