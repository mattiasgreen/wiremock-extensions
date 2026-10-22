package com.github.mattiasgreen.wiremock.netem;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mattiasgreen.wiremock.netem.admin.TcpNetEmExtensionFactory;
import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmProxy;
import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmRegistry;
import com.github.mattiasgreen.wiremock.netem.journal.NetEmCircularJournal;
import com.github.mattiasgreen.wiremock.netem.journal.NetEmJournalEntry;
import com.github.mattiasgreen.wiremock.netem.model.NetEmProxyConfig;
import com.github.mattiasgreen.wiremock.netem.model.NetEmStreamDirection;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicConfig;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicType;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NetEmJournalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private WireMockServer wireMockServer;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        TcpNetEmRegistry.getInstance().closeAll();
        TcpNetEmRegistry.getInstance().getJournal().clear();

        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort().extensions(new TcpNetEmExtensionFactory()));
        wireMockServer.start();

        wireMockServer.stubFor(get(urlEqualTo("/api/data"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":\"hello\"}")));

        httpClient =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
        TcpNetEmRegistry.getInstance().closeAll();
    }

    @Test
    @DisplayName("Circular journal wraps cleanly at capacity and provides reverse-chronological filtered queries")
    void shouldWrapCleanlyAtCapacity() {
        NetEmCircularJournal journal = new NetEmCircularJournal(100);

        for (int i = 0; i < 150; i++) {
            boolean impaired = (i % 2 == 0);
            journal.record(new NetEmJournalEntry(
                    i,
                    System.currentTimeMillis(),
                    "proxy-" + (i % 3),
                    "127.0.0.1:" + (10000 + i),
                    "corr-" + (i % 5),
                    i * 10L,
                    100,
                    200,
                    impaired,
                    impaired ? "latency" : "",
                    "NORMAL_EOF"));
        }

        assertThat(journal.getTotalRecordedCount()).isEqualTo(150);
        assertThat(journal.getCapacity()).isEqualTo(100);

        // Query all (up to limit 100) - should get the last 100 entries (seq 149 down to 50)
        List<NetEmJournalEntry> latest = journal.query(100, null, null, null);
        assertThat(latest).hasSize(100);
        assertThat(latest.get(0).id()).isEqualTo(149);
        assertThat(latest.get(99).id()).isEqualTo(50);

        // Filter by scope
        List<NetEmJournalEntry> filteredCorr = journal.query(50, null, "corr-0", null);
        assertThat(filteredCorr).isNotEmpty();
        assertThat(filteredCorr).allMatch(e -> e.scope().equals("corr-0"));

        // Filter by impaired
        List<NetEmJournalEntry> impairedOnly = journal.query(50, null, null, true);
        assertThat(impairedOnly).isNotEmpty();
        assertThat(impairedOnly).allMatch(NetEmJournalEntry::impaired);

        // Clear
        journal.clear();
        assertThat(journal.query(100, null, null, null)).isEmpty();
    }

    @Test
    @DisplayName("End-to-end: NetEm journal records normal and impaired requests with patterns and stats")
    void shouldRecordNormalAndImpairedRequestsInJournal() throws Exception {
        // Register proxy on dynamic port forwarding to wireMockServer port
        TcpNetEmProxy proxy = TcpNetEmRegistry.getInstance()
                .register(new NetEmProxyConfig("test-journal-proxy", 0, "127.0.0.1", wireMockServer.port(), true));
        int netemPort = proxy.getPort();

        // 1. Send normal request
        HttpRequest normalReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + netemPort + "/api/data"))
                .header("X-Scope", "session-normal-100")
                .GET()
                .build();
        HttpResponse<String> normalResp = httpClient.send(normalReq, HttpResponse.BodyHandlers.ofString());
        assertThat(normalResp.statusCode()).isEqualTo(200);

        // 2. Add latency toxic matching pattern "session-chaos-200"
        NetEmToxicConfig toxic = NetEmToxicConfig.builder("latency-chaos", NetEmToxicType.LATENCY).stream(
                        NetEmStreamDirection.DOWNSTREAM)
                .toxicity(1.0)
                .attribute("latency", 50L)
                .pattern("session-chaos-200")
                .build();
        proxy.addToxic(toxic);

        // 3. Send chaos request
        HttpRequest chaosReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + netemPort + "/api/data"))
                .header("X-Scope", "session-chaos-200")
                .GET()
                .build();
        HttpResponse<String> chaosResp = httpClient.send(chaosReq, HttpResponse.BodyHandlers.ofString());
        assertThat(chaosResp.statusCode()).isEqualTo(200);

        // 4. Query journal via Admin API
        HttpRequest journalReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + wireMockServer.port() + "/__admin/tcp-netem/journal"))
                .GET()
                .build();
        HttpResponse<String> journalResp = httpClient.send(journalReq, HttpResponse.BodyHandlers.ofString());
        assertThat(journalResp.statusCode()).isEqualTo(200);

        List<NetEmJournalEntry> entries = MAPPER.readValue(journalResp.body(), new TypeReference<>() {});
        assertThat(entries).hasSize(2);

        // Newest entry first: chaos request
        NetEmJournalEntry latestChaos = entries.get(0);
        assertThat(latestChaos.scope()).isEqualTo("session-chaos-200");
        assertThat(latestChaos.impaired()).isTrue();
        assertThat(latestChaos.appliedToxics()).contains("latency-chaos");
        assertThat(latestChaos.durationMs()).isGreaterThanOrEqualTo(40L);

        // Older entry: normal request
        NetEmJournalEntry olderNormal = entries.get(1);
        assertThat(olderNormal.impaired()).isFalse();

        // Verify differentiated stats
        assertThat(proxy.getStats().getNormalTransfers()).isEqualTo(1);
        assertThat(proxy.getStats().getImpairedTransfers()).isEqualTo(1);
        assertThat(proxy.getStats().getBytesDownstreamNormal()).isGreaterThan(0);
        assertThat(proxy.getStats().getBytesDownstreamImpaired()).isGreaterThan(0);

        // 5. Test DELETE journal
        HttpRequest deleteReq = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + wireMockServer.port() + "/__admin/tcp-netem/journal"))
                .DELETE()
                .build();
        HttpResponse<String> deleteResp = httpClient.send(deleteReq, HttpResponse.BodyHandlers.ofString());
        assertThat(deleteResp.statusCode()).isEqualTo(200);

        HttpResponse<String> journalAfterClear = httpClient.send(journalReq, HttpResponse.BodyHandlers.ofString());
        List<NetEmJournalEntry> emptyEntries = MAPPER.readValue(journalAfterClear.body(), new TypeReference<>() {});
        assertThat(emptyEntries).isEmpty();
    }
}
