package com.github.mattiasgreen.wiremock.ui;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StubLifecycleServiceTest {

    private WireMockServer server;
    private StubLifecycleService service;

    @BeforeEach
    void setUp() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        service = new StubLifecycleService(server);
    }

    @AfterEach
    void tearDown() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
    }

    @Test
    @DisplayName("Should snapshot recordings at parked priority 100 and generate parked template")
    void shouldSnapshotRecordingsAndGenerateTemplate() throws Exception {
        server.stubFor(WireMock.any(urlMatching("/recorded/.*"))
                .atPriority(StubLifecycleService.PRIORITY_ACTIVE_PROXY)
                .withMetadata(Map.of("project", "MyProject", "mode", "proxy"))
                .willReturn(aResponse().withStatus(200).withBody("{\"recorded\": true}")));

        HttpClient client = HttpClient.newHttpClient();
        client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(server.baseUrl() + "/recorded/item"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());

        assertThat(server.getAllServeEvents()).isNotEmpty();
        String requestId = server.getAllServeEvents().get(0).getId().toString();

        // Generate template (parked at priority 100 by default)
        Optional<StubMapping> template = service.generateSnapshotTemplate(
                requestId, "MyProject", RecordedStubConverter.SnapshotOptions.defaults());
        assertThat(template).isPresent();
        assertThat(template.get().getRequest().getUrl()).isEqualTo("/recorded/item");
        assertThat(template.get().getPriority()).isEqualTo(StubLifecycleService.PRIORITY_PARKED);

        // Snapshot command for requests (default parked priority 100)
        StubLifecycleService.SnapshotCommand command = StubLifecycleService.SnapshotCommand.forRequests(
                java.util.Set.of(requestId),
                "MyProject",
                false,
                false,
                RecordedStubConverter.SnapshotOptions.defaults());
        List<StubMapping> created = service.snapshotRecordings(command);
        assertThat(created).hasSize(1);
        assertThat(created.get(0).getPriority()).isEqualTo(StubLifecycleService.PRIORITY_PARKED);
    }
}
