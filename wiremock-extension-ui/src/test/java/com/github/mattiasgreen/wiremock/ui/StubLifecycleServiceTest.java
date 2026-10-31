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
import java.util.Optional;
import java.util.UUID;
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
    @DisplayName("Should toggle stub between active WireMock admin and parked store")
    void shouldToggleStubLifecycle() {
        UUID stubId = UUID.randomUUID();
        server.stubFor(WireMock.get(urlEqualTo("/test/toggle"))
                .withId(stubId)
                .withName("Toggle Stub")
                .willReturn(aResponse().withStatus(200).withBody("ok")));

        assertThat(server.getStubMappings()).hasSize(1);
        assertThat(service.getAllDisabled()).isEmpty();

        // 1. Toggle to disabled
        StubLifecycleService.TransitionResult disabledResult = service.toggle(stubId);
        assertThat(disabledResult.found()).isTrue();
        assertThat(disabledResult.status()).isEqualTo("disabled");
        assertThat(server.getStubMappings()).isEmpty();
        assertThat(service.getAllDisabled()).hasSize(1);

        // 2. Toggle back to enabled
        StubLifecycleService.TransitionResult enabledResult = service.toggle(stubId);
        assertThat(enabledResult.found()).isTrue();
        assertThat(enabledResult.status()).isEqualTo("enabled");
        assertThat(server.getStubMappings()).hasSize(1);
        assertThat(service.getAllDisabled()).isEmpty();
    }

    @Test
    @DisplayName("Should snapshot recordings and generate template through deep service interface")
    void shouldSnapshotRecordingsAndGenerateTemplate() throws Exception {
        // Direct request to generate a serve event in journal
        server.stubFor(WireMock.get(urlEqualTo("/recorded/item"))
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

        // Generate template
        Optional<StubMapping> template = service.generateSnapshotTemplate(
                requestId, "MyProject", RecordedStubConverter.SnapshotOptions.defaults());
        assertThat(template).isPresent();
        assertThat(template.get().getRequest().getUrl()).isEqualTo("/recorded/item");

        // Snapshot command for requests
        StubLifecycleService.SnapshotCommand command = StubLifecycleService.SnapshotCommand.forRequests(
                java.util.Set.of(requestId),
                "MyProject",
                false,
                false,
                RecordedStubConverter.SnapshotOptions.defaults());
        List<StubMapping> created = service.snapshotRecordings(command);
        assertThat(created).isNotEmpty();
    }
}
