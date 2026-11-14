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
    @DisplayName(
            "Should switch project mode using priority tiers while skipping offline-guard and preserving custom priorities")
    void shouldSwitchProjectModeUsingPriorityTiers() throws Exception {
        WireMockServer upstream =
                new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstream.start();
        try {
            upstream.stubFor(WireMock.get(urlEqualTo("/api/billing/invoice"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"upstream\":true}")));

            UUID stubId = UUID.randomUUID();
            UUID proxyId = UUID.randomUUID();
            UUID guardId = UUID.randomUUID();

            // Parked custom-priority static stub (previousPriority: 2)
            server.stubFor(WireMock.get(urlEqualTo("/api/billing/invoice"))
                    .withId(stubId)
                    .atPriority(StubLifecycleService.PRIORITY_PARKED)
                    .withName("Invoice Parked")
                    .withMetadata(Map.of("project", "Billing", "previousPriority", 2))
                    .willReturn(aResponse().withStatus(200).withBody("{\"invoice\":1}")));

            // Active proxy stub
            server.stubFor(WireMock.get(urlMatching("/api/billing/.*"))
                    .withId(proxyId)
                    .atPriority(StubLifecycleService.PRIORITY_ACTIVE_PROXY)
                    .withName("Billing Proxy")
                    .withMetadata(Map.of("project", "Billing", "mode", "proxy"))
                    .willReturn(aResponse().proxiedFrom(upstream.baseUrl())));

            // Priority-50 offline catch-all guard belonging to same project (must never be parked/altered)
            server.stubFor(WireMock.any(urlMatching(".*"))
                    .withId(guardId)
                    .atPriority(StubLifecycleService.PRIORITY_OFFLINE_GUARD)
                    .withName("Offline Guard 501")
                    .withMetadata(Map.of("project", "Billing", "role", "offline-guard"))
                    .willReturn(aResponse().withStatus(501)));

            HttpClient client = HttpClient.newHttpClient();
            StubLifecycleService.ProjectModeResult toStubs = service.switchProjectMode("Billing", "stubs");
            assertThat(toStubs.activeMode()).isEqualTo("stubs");
            assertThat(toStubs.stubsEnabled()).isEqualTo(1);
            assertThat(toStubs.stubsDisabled()).isEqualTo(1);

            StubMapping activeStub = server.getSingleStubMapping(stubId);
            StubMapping parkedProxy = server.getSingleStubMapping(proxyId);
            StubMapping intactGuard = server.getSingleStubMapping(guardId);

            assertThat(activeStub.getPriority()).isEqualTo(2);
            assertThat(activeStub.getMetadata()).doesNotContainKey("previousPriority");
            assertThat(parkedProxy.getPriority()).isEqualTo(StubLifecycleService.PRIORITY_PARKED);
            assertThat(StubLifecycleService.isOfflineGuard(intactGuard)).isTrue();
            assertThat(intactGuard.getPriority()).isEqualTo(StubLifecycleService.PRIORITY_OFFLINE_GUARD);

            // Live HTTP routing must reflect re-ordered priorities immediately
            HttpResponse<String> stubbedInvoice = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(server.baseUrl() + "/api/billing/invoice"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(stubbedInvoice.statusCode()).isEqualTo(200);
            assertThat(stubbedInvoice.body()).isEqualTo("{\"invoice\":1}");

            HttpResponse<String> guardHit = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(server.baseUrl() + "/api/billing/unrecorded"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(guardHit.statusCode()).isEqualTo(501);

            StubLifecycleService.ProjectModeResult toProxy = service.switchProjectMode("Billing", "proxy");
            assertThat(toProxy.activeMode()).isEqualTo("proxy");
            assertThat(toProxy.stubsEnabled()).isEqualTo(1);
            assertThat(toProxy.stubsDisabled()).isEqualTo(1);

            StubMapping reParkedStub = server.getSingleStubMapping(stubId);
            StubMapping reActivatedProxy = server.getSingleStubMapping(proxyId);
            assertThat(reParkedStub.getPriority()).isEqualTo(StubLifecycleService.PRIORITY_PARKED);
            assertThat(reParkedStub.getMetadata().getInt("previousPriority")).isEqualTo(2);
            assertThat(reActivatedProxy.getPriority()).isEqualTo(StubLifecycleService.PRIORITY_ACTIVE_PROXY);
            assertThat(server.getSingleStubMapping(guardId).getPriority())
                    .isEqualTo(StubLifecycleService.PRIORITY_OFFLINE_GUARD);

            HttpResponse<String> proxiedInvoice = client.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(server.baseUrl() + "/api/billing/invoice"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(proxiedInvoice.statusCode()).isEqualTo(200);
            assertThat(proxiedInvoice.body()).isEqualTo("{\"upstream\":true}");
        } finally {
            upstream.stop();
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
