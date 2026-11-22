package com.github.mattiasgreen.wiremock.casual;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mattiasgreen.wiremock.async.connection.AsyncBrokerRegistry;
import com.github.mattiasgreen.wiremock.async.destination.AsyncDestination;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CasualDestinationProviderTest {

    @Test
    void shouldListPublishPeekDeleteAndPurgeLocalCasualQueuesDirectlyWithoutReflection() {
        CasualQueueStore store = new CasualQueueStore();
        AsyncBrokerRegistry registry = AsyncBrokerRegistry.createForTest();
        CasualDestinationProvider provider = new CasualDestinationProvider(() -> Optional.of(store), registry);

        assertThat(provider.getProtocol()).isEqualTo("casual");
        assertThat(provider.listDestinations()).isEmpty();

        AsyncMessage msg = AsyncMessage.builder()
                .id("msg-1")
                .protocol("casual")
                .destination("billing.q")
                .correlationId("corr-casual-10")
                .replyTo("billing.reply")
                .timestamp(Instant.now())
                .headers(Map.of("X-Casual-Buffer-Type", "JSON"))
                .payload("{\"invoiceId\":\"INV-10\"}")
                .build();

        assertThat(provider.publishMessage("billing.q", msg)).isTrue();

        List<AsyncDestination> destinations = provider.listDestinations();
        assertThat(destinations)
                .extracting(AsyncDestination::name)
                .containsExactlyInAnyOrder("billing.q", "billing.q.error");
        AsyncDestination primary = destinations.stream()
                .filter(d -> "billing.q".equals(d.name()))
                .findFirst()
                .orElseThrow();
        assertThat(primary.messageCount()).isEqualTo(1);

        List<AsyncMessage> peeked = provider.peekMessages("billing.q", 10);
        assertThat(peeked).hasSize(1);
        assertThat(peeked.get(0).correlationId()).isEqualTo("corr-casual-10");
        assertThat(peeked.get(0).replyTo()).isEqualTo("billing.reply");
        assertThat(peeked.get(0).payload()).contains("INV-10");

        String msgId = peeked.get(0).id();
        assertThat(provider.deleteMessage("billing.q", msgId)).isTrue();
        assertThat(provider.peekMessages("billing.q", 10)).isEmpty();

        provider.publishMessage("billing.q", msg);
        provider.purgeDestination("billing.q");
        assertThat(provider.peekMessages("billing.q", 10)).isEmpty();
    }

    @Test
    void shouldIsolateCasualBrokerRegistryAcrossConcurrentSpiWireMockServers() throws Exception {
        WireMockServer serverA =
                new WireMockServer(WireMockConfiguration.options().dynamicPort().extensionScanningEnabled(true));
        WireMockServer serverB =
                new WireMockServer(WireMockConfiguration.options().dynamicPort().extensionScanningEnabled(true));
        try {
            serverA.start();
            serverB.start();

            HttpClient client = HttpClient.newHttpClient();
            ObjectMapper mapper = new ObjectMapper();

            String connA =
                    """
                    {
                      "id": "casual-a",
                      "protocol": "casual",
                      "name": "Casual Server A",
                      "endpoint": "casual://127.0.0.1:7772",
                      "properties": { "queues": "alpha.q" }
                    }
                    """;
            String connB =
                    """
                    {
                      "id": "casual-b",
                      "protocol": "casual",
                      "name": "Casual Server B",
                      "endpoint": "casual://127.0.0.1:7773",
                      "properties": { "queues": "beta.q" }
                    }
                    """;

            assertThat(client.send(
                                    HttpRequest.newBuilder()
                                            .uri(URI.create(serverA.baseUrl() + "/__admin/async/connections"))
                                            .header("Content-Type", "application/json")
                                            .POST(HttpRequest.BodyPublishers.ofString(connA))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .statusCode())
                    .isEqualTo(201);
            assertThat(client.send(
                                    HttpRequest.newBuilder()
                                            .uri(URI.create(serverB.baseUrl() + "/__admin/async/connections"))
                                            .header("Content-Type", "application/json")
                                            .POST(HttpRequest.BodyPublishers.ofString(connB))
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .statusCode())
                    .isEqualTo(201);

            JsonNode destsA = mapper.readTree(client.send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create(serverA.baseUrl() + "/__admin/async/destinations"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString())
                    .body());
            JsonNode destsB = mapper.readTree(client.send(
                            HttpRequest.newBuilder()
                                    .uri(URI.create(serverB.baseUrl() + "/__admin/async/destinations"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString())
                    .body());

            List<String> namesA = destsA.get("destinations").findValuesAsText("name");
            List<String> namesB = destsB.get("destinations").findValuesAsText("name");

            assertThat(namesA).contains("alpha.q").doesNotContain("beta.q");
            assertThat(namesB).contains("beta.q").doesNotContain("alpha.q");
        } finally {
            if (serverA.isRunning()) {
                serverA.stop();
            }
            if (serverB.isRunning()) {
                serverB.stop();
            }
        }
    }
}
