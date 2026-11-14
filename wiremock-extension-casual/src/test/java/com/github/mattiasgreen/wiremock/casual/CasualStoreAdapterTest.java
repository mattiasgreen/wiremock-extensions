package com.github.mattiasgreen.wiremock.casual;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ServeEventListener;
import com.github.tomakehurst.wiremock.http.ImmutableRequest;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CasualStoreAdapterTest {

    @Test
    @DisplayName("WireMockStoreAdapter reads mappings, files, records ServeEvents, and isolates per-server listeners")
    void wireMockStoreAdapterOperatesWithoutReflectionAndIsolatesListeners(@TempDir Path rootDir) throws Exception {
        Path filesDir = rootDir.resolve("__files");
        Files.createDirectories(filesDir);
        Files.writeString(filesDir.resolve("sample-reply.json"), "{\"source\":\"files-dir\"}", StandardCharsets.UTF_8);

        List<String> serverAEvents = new CopyOnWriteArrayList<>();
        List<String> serverBEvents = new CopyOnWriteArrayList<>();

        ServeEventListener listenerA = new NamedRecordingListener("listener-a", serverAEvents);
        ServeEventListener listenerB = new NamedRecordingListener("listener-b", serverBEvents);

        WireMockServer serverA = new WireMockServer(wireMockConfig()
                .dynamicPort()
                .withRootDirectory(rootDir.toString())
                .extensions(new CasualExtensionFactory())
                .extensions(listenerA));
        WireMockServer serverB = new WireMockServer(wireMockConfig()
                .dynamicPort()
                .extensions(new CasualExtensionFactory())
                .extensions(listenerB));

        serverA.start();
        serverB.start();
        try {
            serverA.stubFor(post(urlEqualTo("/__casual/services/account.info"))
                    .atPriority(10)
                    .withName("Fallback Proxy")
                    .willReturn(okJson("{\"tier\":10}")));
            serverA.stubFor(post(urlEqualTo("/__casual/services/account.info"))
                    .atPriority(5)
                    .withName("Active Override")
                    .willReturn(okJson("{\"tier\":5}")));
            serverA.stubFor(post(urlEqualTo("/__casual/services/parked.service"))
                    .atPriority(100)
                    .withName("Parked Stub")
                    .willReturn(okJson("{\"parked\":true}")));

            WireMockStoreAdapter adapterA = new WireMockStoreAdapter(serverA);

            List<StubMapping> active = adapterA.getActiveStubMappings();
            assertThat(active)
                    .extracting(StubMapping::getName)
                    .containsExactlyInAnyOrder("Fallback Proxy", "Active Override");

            ImmutableRequest matchReq = ImmutableRequest.create()
                    .withMethod(RequestMethod.POST)
                    .withAbsoluteUrl(adapterA.getBaseUrl() + "/__casual/services/account.info")
                    .build();
            Optional<StubMapping> matched = adapterA.findMatchingStub(matchReq);
            assertThat(matched).isPresent();
            assertThat(matched.get().getName()).isEqualTo("Active Override");

            assertThat(adapterA.readBodyFromFile("sample-reply.json")).isEqualTo("{\"source\":\"files-dir\"}");

            ImmutableRequest probeReq = ImmutableRequest.create()
                    .withMethod(RequestMethod.POST)
                    .withAbsoluteUrl(adapterA.getBaseUrl() + "/__casual/queues/billing/enqueue")
                    .withHeader("X-WireMock-Protocol", "casual")
                    .build();
            ServeEvent event =
                    CasualHttpMediation.buildQueueServeEvent(probeReq, 200, "{\"ok\":true}", "billing", "enqueue");
            adapterA.recordServeEvent(event);

            assertThat(serverA.getAllServeEvents())
                    .extracting(se -> se.getRequest().getUrl())
                    .contains("/__casual/queues/billing/enqueue");
            assertThat(serverAEvents).containsExactly("/__casual/queues/billing/enqueue");
            assertThat(serverBEvents).isEmpty();
        } finally {
            serverA.stop();
            serverB.stop();
        }
    }

    @Test
    @DisplayName("HttpCasualStoreAdapter queries mappings, excludes parked stubs, and reads files over HTTP")
    void httpCasualStoreAdapterQueriesRemoteWireMock(@TempDir Path rootDir) throws Exception {
        Path filesDir = rootDir.resolve("__files");
        Files.createDirectories(filesDir.resolve("nested"));
        Files.writeString(filesDir.resolve("nested/payload.json"), "{\"remote\":true}", StandardCharsets.UTF_8);

        WireMockServer remote =
                new WireMockServer(wireMockConfig().dynamicPort().withRootDirectory(rootDir.toString()));
        remote.start();
        try {
            remote.stubFor(post(urlEqualTo("/__casual/services/remote.svc"))
                    .atPriority(5)
                    .withName("Remote Active")
                    .willReturn(okJson("{\"ok\":true}")));
            remote.stubFor(post(urlEqualTo("/__casual/services/remote.parked"))
                    .atPriority(100)
                    .withName("Remote Parked")
                    .willReturn(okJson("{\"parked\":true}")));

            HttpCasualStoreAdapter httpAdapter = new HttpCasualStoreAdapter(remote.baseUrl() + "/");
            assertThat(httpAdapter.getBaseUrl()).isEqualTo(remote.baseUrl());

            List<StubMapping> active = httpAdapter.getActiveStubMappings();
            assertThat(active).extracting(StubMapping::getName).containsExactly("Remote Active");

            ImmutableRequest req = ImmutableRequest.create()
                    .withMethod(RequestMethod.POST)
                    .withAbsoluteUrl(remote.baseUrl() + "/__casual/services/remote.svc")
                    .build();
            assertThat(httpAdapter.findMatchingStub(req))
                    .map(StubMapping::getName)
                    .contains("Remote Active");

            assertThat(httpAdapter.readBodyFromFile("nested/payload.json")).isEqualTo("{\"remote\":true}");

            // HTTP mode ServeEvent recording is an explicit no-op
            ServeEvent serveEvent = ServeEvent.of(LoggedRequest.createFrom(req));
            httpAdapter.recordServeEvent(serveEvent);
            assertThat(remote.getAllServeEvents()).isEmpty();
        } finally {
            remote.stop();
        }
    }

    @Test
    @DisplayName("CasualWireMockGateway selects explicit store adapter based on construction parameters")
    void resolvesExplicitAdapterPerConstructionMode() {
        WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        try {
            assertThat(CasualWireMockGateway.resolveDefaultStorePort(server, null))
                    .isInstanceOf(WireMockStoreAdapter.class);
            assertThat(CasualWireMockGateway.resolveDefaultStorePort(null, server.baseUrl()))
                    .isInstanceOf(HttpCasualStoreAdapter.class);
        } finally {
            server.stop();
        }
    }

    private record NamedRecordingListener(String listenerName, List<String> seenUrls) implements ServeEventListener {
        @Override
        public String getName() {
            return listenerName;
        }

        @Override
        public void afterComplete(ServeEvent serveEvent, Parameters parameters) {
            seenUrls.add(serveEvent.getRequest().getUrl());
        }
    }
}
