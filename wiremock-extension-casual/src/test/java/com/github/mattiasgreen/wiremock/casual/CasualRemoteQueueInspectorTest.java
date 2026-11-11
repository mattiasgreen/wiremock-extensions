package com.github.mattiasgreen.wiremock.casual;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CasualRemoteQueueInspectorTest {

    private WireMockServer wireMockServer;
    private CasualWireMockGateway gateway;
    private CasualClient client;
    private CasualRemoteQueueInspector inspector;

    @BeforeEach
    void setUp() throws Exception {
        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        wireMockServer.start();

        gateway = new CasualWireMockGateway(0, wireMockServer);
        gateway.start();

        client = new CasualClient("127.0.0.1", gateway.getPort());
        inspector = new CasualRemoteQueueInspector(client);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (gateway != null) {
            gateway.close();
        }
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    @Test
    @DisplayName("Should peek remote Casual queue non-destructively via rollback")
    void shouldPeekMessagesNonDestructively() throws Exception {
        String queue = "remote.orders.q";
        gateway.getQueueStore().createQueue(queue, 5, 0);

        var r1 = client.enqueue(queue, "{\"orderId\":\"ORD-101\",\"amount\":99.50}");
        assertThat(r1.getId()).isNotNull();
        var r2 = client.enqueue(queue, "{\"orderId\":\"ORD-102\",\"amount\":150.00}");
        assertThat(r2.getId()).isNotNull();

        assertThat(gateway.getQueueStore().getQueueSize(queue)).isEqualTo(2);

        List<CasualRemoteMessage> peeked = inspector.peekMessages(queue, 10);
        assertThat(peeked).hasSize(2);
        assertThat(peeked.get(0).payload()).contains("ORD-101");
        assertThat(peeked.get(1).payload()).contains("ORD-102");

        // Verify messages still remain on the queue (rollback worked!)
        List<CasualRemoteMessage> peekedAgain = inspector.peekMessages(queue, 10);
        assertThat(peekedAgain).hasSize(2);

        // Purge queue
        int purged = inspector.purgeQueue(queue);
        assertThat(purged).isEqualTo(2);

        List<CasualRemoteMessage> afterPurge = inspector.peekMessages(queue, 10);
        assertThat(afterPurge).isEmpty();
    }
}
