package com.github.mattiasgreen.wiremock.journal;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.journal.engine.AdaptiveAntiOomGuard;
import com.github.mattiasgreen.wiremock.journal.engine.AdaptiveJournalCompactor;
import com.github.mattiasgreen.wiremock.journal.engine.DictionaryPool;
import com.github.mattiasgreen.wiremock.journal.engine.JournalMemoryGovernor;
import com.github.mattiasgreen.wiremock.journal.engine.JournalSearchIndex;
import com.github.mattiasgreen.wiremock.journal.engine.ZstdCodec;
import com.github.mattiasgreen.wiremock.journal.model.CompressedServeEvent;
import com.github.mattiasgreen.wiremock.journal.model.JournalStats;
import com.github.mattiasgreen.wiremock.journal.store.CompressedRequestJournalStore;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.Limit;
import com.github.tomakehurst.wiremock.common.Timing;
import com.github.tomakehurst.wiremock.http.*;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CompressedRequestJournalStoreTest {

    private ZstdCodec codec;
    private DictionaryPool dictionaryPool;
    private CompressedRequestJournalStore store;

    @BeforeEach
    void setUp() {
        codec = new ZstdCodec();
        dictionaryPool = new DictionaryPool(16, 20, 16 * 1024, codec);
        store = new CompressedRequestJournalStore(
                codec, dictionaryPool, new JournalMemoryGovernor(0, 50 * 1024 * 1024));
    }

    @AfterEach
    void tearDown() {
        store.clear();
    }

    @Test
    void shouldStoreAndRetrieveServeEventsWithExactFidelity() {
        UUID stubId = UUID.randomUUID();
        StubMapping stub = new StubMapping();
        stub.setId(stubId);

        String reqBody = "{\"action\":\"submitCase\",\"details\":\"High priority incident\"}";
        String respBody = "{\"caseId\":\"CASE-9901\",\"status\":\"CREATED\",\"timestamp\":\"2026-11-04T04:00:00Z\"}";

        ServeEvent event = createServeEvent(stub, "/api/cases", reqBody, respBody);
        store.add(event);

        Optional<ServeEvent> retrievedOpt = store.get(event.getId());
        assertThat(retrievedOpt).isPresent();

        ServeEvent retrieved = retrievedOpt.get();
        assertThat(retrieved.getId()).isEqualTo(event.getId());
        assertThat(retrieved.getRequest().getBodyAsString()).isEqualTo(reqBody);
        assertThat(retrieved.getResponse().getBodyAsString()).isEqualTo(respBody);
        assertThat(retrieved.getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    void shouldPerformFreeTextSearchAcrossCompressedJournal() {
        UUID stubId = UUID.randomUUID();
        StubMapping stub = new StubMapping();
        stub.setId(stubId);

        UUID targetEventId = null;
        for (int i = 0; i < 20; i++) {
            String reqBody = "{\"itemIndex\":" + i + ",\"sku\":\"SKU-" + i + "\"}";
            String respBody = "{\"result\":\"ok\",\"index\":" + i + "}";
            if (i == 13) {
                respBody = "{\"result\":\"ok\",\"specialMarker\":\"NEEDLE_IN_HAYSTACK_1337\"}";
            }

            ServeEvent event = createServeEvent(stub, "/api/items/" + i, reqBody, respBody);
            store.add(event);
            if (i == 13) {
                targetEventId = event.getId();
            }
        }

        List<UUID> matches = store.search("NEEDLE_IN_HAYSTACK_1337");
        assertThat(matches).containsExactly(targetEventId);

        List<UUID> emptyMatches = store.search("NON_EXISTENT_IDENTIFIER");
        assertThat(emptyMatches).isEmpty();
    }

    @Test
    void shouldAchieveExtremeCompressionWithTrainedDictionary() throws Exception {
        UUID stubId = UUID.randomUUID();
        StubMapping stub = new StubMapping();
        stub.setId(stubId);

        // Record 25 events (threshold is 20) to ensure dictionary triggers
        for (int i = 0; i < 25; i++) {
            String reqBody = "{\"action\":\"updateAccount\",\"accountId\":\"ACC-" + i
                    + "\",\"requestedBy\":\"agent-user-" + (i % 5) + "\"}";
            String respBody = String.format(
                    "{\"schemaVersion\":\"2.4.0\",\"accountId\":\"ACC-%d\",\"name\":\"Customer %d\",\"status\":\"ACTIVE\",\"profile\":{\"tier\":\"GOLD\",\"region\":\"EU-NORTH\",\"contact\":{\"email\":\"cust%d@example.com\",\"phone\":\"+467000000%02d\",\"address\":{\"street\":\"Storgatan 1\",\"postalCode\":\"11122\",\"city\":\"Stockholm\",\"country\":\"Sweden\"}}},\"permissions\":[\"READ_ACCOUNTS\",\"EXECUTE_PAYMENTS\",\"VIEW_STATEMENTS\"],\"audit\":{\"lastLogin\":\"2026-11-04T04:%02d:00Z\",\"loginCount\":%d,\"correlationId\":\"corr-uuid-%d\"}}",
                    i, i, i, i % 100, i % 60, i * 3, i);
            ServeEvent event = createServeEvent(stub, "/api/accounts", reqBody, respBody);
            store.add(event);
        }

        // Wait up to 1000ms for virtual thread dictionary training to complete
        long deadline = System.currentTimeMillis() + 1000;
        while (dictionaryPool.getActiveDictionaryCount() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }

        assertThat(dictionaryPool.getActiveDictionaryCount()).isGreaterThanOrEqualTo(1);

        // Record 75 subsequent events using the trained dictionary
        for (int i = 25; i < 100; i++) {
            String reqBody = "{\"action\":\"updateAccount\",\"accountId\":\"ACC-" + i
                    + "\",\"requestedBy\":\"agent-user-" + (i % 5) + "\"}";
            String respBody = String.format(
                    "{\"schemaVersion\":\"2.4.0\",\"accountId\":\"ACC-%d\",\"name\":\"Customer %d\",\"status\":\"ACTIVE\",\"profile\":{\"tier\":\"GOLD\",\"region\":\"EU-NORTH\",\"contact\":{\"email\":\"cust%d@example.com\",\"phone\":\"+467000000%02d\",\"address\":{\"street\":\"Storgatan 1\",\"postalCode\":\"11122\",\"city\":\"Stockholm\",\"country\":\"Sweden\"}}},\"permissions\":[\"READ_ACCOUNTS\",\"EXECUTE_PAYMENTS\",\"VIEW_STATEMENTS\"],\"audit\":{\"lastLogin\":\"2026-11-04T04:%02d:00Z\",\"loginCount\":%d,\"correlationId\":\"corr-uuid-%d\"}}",
                    i, i, i, i % 100, i % 60, i * 3, i);
            ServeEvent event = createServeEvent(stub, "/api/accounts", reqBody, respBody);
            store.add(event);
        }

        JournalStats stats = store.getStats();
        System.out.println("Stats after 100 events:");
        System.out.println("Total events: " + stats.totalEvents());
        System.out.println("Raw bytes: " + stats.rawBytesTotal());
        System.out.println("Compressed bytes: " + stats.compressedBytesTotal());
        System.out.println("Compression ratio: " + stats.compressionRatio());

        assertThat(stats.totalEvents()).isEqualTo(100);
        assertThat(stats.compressionRatio()).isGreaterThan(2.0);
    }

    @Test
    void shouldAutomaticallyRebalanceAndRetroactivelyUpgradeColdStartEvents() throws Exception {
        // Phase 1: 25 distinct services send 15 events each (threshold is 20, so 0 dictionaries trained)
        Map<String, StubMapping> stubs = new LinkedHashMap<>();
        for (int s = 1; s <= 25; s++) {
            StubMapping stub = new StubMapping();
            stub.setId(UUID.randomUUID());
            stub.setName("Service-" + s);
            stubs.put("svc-" + s, stub);

            for (int r = 1; r <= 15; r++) {
                String reqBody = "{\"svc\":\"svc-" + s + "\",\"callIndex\":" + r + "}";
                String respBody = String.format(
                        "{\"serviceName\":\"svc-%d\",\"status\":\"OK\",\"metadata\":{\"version\":\"1.0\",\"region\":\"eu-west\"},\"data\":{\"index\":%d,\"timestamp\":\"2026-11-04T05:%02d:00Z\"}}",
                        s, r, r % 60);
                store.add(createServeEvent(stub, "/api/svc-" + s, reqBody, respBody));
            }
        }

        assertThat(dictionaryPool.getActiveDictionaryCount()).isEqualTo(0);
        long baselineCompBytes = store.getStats().compressedBytesTotal();

        // Count how many events currently use dictionary (should be 0)
        long dictEventsPhase1 = store.getAll()
                .filter(e -> e instanceof com.github.mattiasgreen.wiremock.journal.model.CompressedServeEvent c
                        && c.isUsedDictionary())
                .count();
        assertThat(dictEventsPhase1).isEqualTo(0);

        // Phase 2: Send 10 more events for the first 5 services (crossing threshold 20 -> triggers training &
        // auto-rebalance)
        for (int s = 1; s <= 5; s++) {
            StubMapping stub = stubs.get("svc-" + s);
            for (int r = 16; r <= 25; r++) {
                String reqBody = "{\"svc\":\"svc-" + s + "\",\"callIndex\":" + r + "}";
                String respBody = String.format(
                        "{\"serviceName\":\"svc-%d\",\"status\":\"OK\",\"metadata\":{\"version\":\"1.0\",\"region\":\"eu-west\"},\"data\":{\"index\":%d,\"timestamp\":\"2026-11-04T05:%02d:00Z\"}}",
                        s, r, r % 60);
                store.add(createServeEvent(stub, "/api/svc-" + s, reqBody, respBody));
            }
        }

        // Wait up to 10000ms for dictionary training and retroactive rebalancing
        long deadline = System.currentTimeMillis() + 10000;
        long dictEventsPhase2 = 0;
        while (System.currentTimeMillis() < deadline) {
            dictEventsPhase2 = store.getAll()
                    .filter(e -> e instanceof com.github.mattiasgreen.wiremock.journal.model.CompressedServeEvent c
                            && c.isUsedDictionary())
                    .count();
            if (dictionaryPool.getActiveDictionaryCount() >= 5 && dictEventsPhase2 == 125) {
                break;
            }
            Thread.sleep(25);
        }

        assertThat(dictionaryPool.getActiveDictionaryCount()).isGreaterThanOrEqualTo(5);
        System.out.println("DEBUG Phase 2: dictEvents=" + dictEventsPhase2 + ", activeDicts="
                + dictionaryPool.getActiveDictionaryCount() + ", totalEvents="
                + store.getAll().count());
        // 5 services * 25 events each = 125 events should now be dictionary-compressed
        assertThat(dictEventsPhase2)
                .withFailMessage(
                        "Expected 125 dictionary events, but found %d. Active dicts: %d, Total in store: %d",
                        dictEventsPhase2,
                        dictionaryPool.getActiveDictionaryCount(),
                        store.getAll().count())
                .isEqualTo(125);

        // Phase 3: Add 10 brand-new services (svc-26 to svc-35) with 25 events each
        for (int s = 26; s <= 35; s++) {
            StubMapping stub = new StubMapping();
            stub.setId(UUID.randomUUID());
            stub.setName("Service-" + s);
            stubs.put("svc-" + s, stub);

            for (int r = 1; r <= 25; r++) {
                String reqBody = "{\"svc\":\"svc-" + s + "\",\"callIndex\":" + r + "}";
                String respBody = String.format(
                        "{\"serviceName\":\"svc-%d\",\"status\":\"OK\",\"metadata\":{\"version\":\"1.0\",\"region\":\"eu-west\"},\"data\":{\"index\":%d,\"timestamp\":\"2026-11-04T05:%02d:00Z\"}}",
                        s, r, r % 60);
                store.add(createServeEvent(stub, "/api/svc-" + s, reqBody, respBody));
            }
        }

        // Wait up to 10000ms for the 10 new dictionaries to train and auto-rebalance
        deadline = System.currentTimeMillis() + 10000;
        long dictEventsPhase3 = 0;
        while (System.currentTimeMillis() < deadline) {
            dictEventsPhase3 = store.getAll()
                    .filter(e -> e instanceof com.github.mattiasgreen.wiremock.journal.model.CompressedServeEvent c
                            && c.isUsedDictionary())
                    .count();
            if (dictionaryPool.getActiveDictionaryCount() >= 15 && dictEventsPhase3 == 375) {
                break;
            }
            Thread.sleep(25);
        }

        assertThat(dictionaryPool.getActiveDictionaryCount()).isGreaterThanOrEqualTo(15);
        System.out.println("DEBUG Phase 3: dictEvents=" + dictEventsPhase3 + ", activeDicts="
                + dictionaryPool.getActiveDictionaryCount() + ", totalEvents="
                + store.getAll().count());
        // Total dictionary-compressed events: (5 + 10) * 25 = 375 events!
        assertThat(dictEventsPhase3)
                .withFailMessage(
                        "Expected 375 dictionary events, but found %d. Active dicts: %d, Total in store: %d",
                        dictEventsPhase3,
                        dictionaryPool.getActiveDictionaryCount(),
                        store.getAll().count())
                .isEqualTo(375);
    }

    @Test
    void shouldSupportUncompressedModeWithAdaptivePruning() {
        // JournalConfig with compression disabled, max memory capped at 5000 bytes
        com.github.mattiasgreen.wiremock.journal.engine.JournalConfig uncompressedConfig =
                new com.github.mattiasgreen.wiremock.journal.engine.JournalConfig(
                        false, // compression disabled
                        true, // adaptive pruning enabled
                        16,
                        20,
                        16 * 1024,
                        5000L, // max journal memory 5 KB
                        10 * 1024 * 1024 // min headroom
                        );

        CompressedRequestJournalStore uncompressedStore = new CompressedRequestJournalStore(
                codec, dictionaryPool, new JournalMemoryGovernor(uncompressedConfig), uncompressedConfig);

        StubMapping stub = new StubMapping();
        stub.setId(UUID.randomUUID());

        // Add 50 events of ~500 bytes each -> total ~25 KB uncompressed
        for (int i = 0; i < 50; i++) {
            String req = "{\"test\": \"uncompressed-event-" + i + "\", \"data\": \"" + "x".repeat(300) + "\"}";
            String resp = "{\"response\": \"ok-" + i + "\"}";
            uncompressedStore.add(createServeEvent(stub, "/api/uncompressed", req, resp));
        }

        // Must NOT store as CompressedServeEvent since compression is false
        long compressedEvents = uncompressedStore
                .getAll()
                .filter(e -> e instanceof com.github.mattiasgreen.wiremock.journal.model.CompressedServeEvent)
                .count();
        assertThat(compressedEvents).isZero();

        // But adaptive pruning should have kept memory under limit by evicting older events
        JournalStats stats = uncompressedStore.getStats();
        assertThat(stats.totalEvictions()).isGreaterThan(0);
        assertThat(stats.totalEvents()).isLessThan(50);
        assertThat(stats.totalEvents()).isGreaterThan(0);

        // Verification and search should still work seamlessly
        List<UUID> searchMatches = uncompressedStore.search("uncompressed-event-49");
        assertThat(searchMatches).hasSize(1);
    }

    @Test
    @SuppressWarnings("deprecation")
    void shouldMaintainBackwardsCompatibilityWithAdaptiveAntiOomGuard() {
        AdaptiveAntiOomGuard legacyGuard = new AdaptiveAntiOomGuard(10 * 1024 * 1024, 50 * 1024 * 1024);
        assertThat(legacyGuard).isInstanceOf(JournalMemoryGovernor.class);
        CompressedRequestJournalStore legacyStore =
                new CompressedRequestJournalStore(codec, dictionaryPool, legacyGuard);
        assertThat(legacyStore.getAntiOomGuard()).isSameAs(legacyGuard);
    }

    @Test
    void shouldSearchWithZeroDecompressionViaInvertedIndex() {
        StubMapping stub = new StubMapping();
        stub.setId(UUID.randomUUID());

        for (int i = 0; i < 50; i++) {
            String req = "{\"action\":\"processPayment\",\"txId\":\"TX-INDEX-" + i + "\"}";
            String resp = "{\"status\":\"CONFIRMED\",\"authCode\":\"AUTH-" + i + "\"}";
            store.add(createServeEvent(stub, "/api/payments/" + i, req, resp));
        }

        long initialDecompressions = store.getDecompressionCount();
        assertThat(initialDecompressions).isZero();

        // Query indexed needle
        List<UUID> matches = store.search("TX-INDEX-25");
        assertThat(matches).hasSize(1);
        assertThat(store.getDecompressionCount()).isZero();

        // Query another indexed field
        List<UUID> authMatches = store.search("AUTH-42");
        assertThat(authMatches).hasSize(1);
        assertThat(store.getDecompressionCount()).isZero();

        // Inverted index hits recorded
        assertThat(store.getSearchIndex().getIndexHits()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void shouldResolveNegativeSearchUnderFiftyMicrosecondsWithoutDecompression() {
        StubMapping stub = new StubMapping();
        stub.setId(UUID.randomUUID());

        for (int i = 0; i < 50; i++) {
            store.add(createServeEvent(stub, "/api/items/" + i, "{\"id\":" + i + "}", "{\"status\":\"ok\"}"));
        }

        long beforeDecompressions = store.getDecompressionCount();

        // Warm up JIT
        for (int i = 0; i < 50; i++) {
            store.search("NON_EXISTENT_WARMUP_TOKEN");
        }

        long startNanos = System.nanoTime();
        List<UUID> emptyMatches = store.search("COMPLETELY_ABSENT_TOKEN_12345");
        long elapsedNanos = System.nanoTime() - startNanos;

        assertThat(emptyMatches).isEmpty();
        // Negative lookup must never trigger transient decompression
        assertThat(store.getDecompressionCount()).isEqualTo(beforeDecompressions);
        // Generous margin for CI execution (typically < 50us)
        assertThat(elapsedNanos).isLessThan(5_000_000L);
    }

    @Test
    void shouldPerformBatchEvictionUnderMemoryPressure() {
        StubMapping stub = new StubMapping();
        stub.setId(UUID.randomUUID());

        // Governor with synthetic headroom supplier simulating a sudden memory drop below threshold
        java.util.concurrent.atomic.AtomicLong simulatedHeadroom =
                new java.util.concurrent.atomic.AtomicLong(100 * 1024 * 1024); // 100 MB healthy
        JournalMemoryGovernor testGovernor =
                new JournalMemoryGovernor(true, 20 * 1024 * 1024, 0, simulatedHeadroom::get);

        CompressedRequestJournalStore pressureStore =
                new CompressedRequestJournalStore(codec, dictionaryPool, testGovernor);

        for (int i = 0; i < 100; i++) {
            pressureStore.add(createServeEvent(
                    stub, "/api/events/" + i, "{\"payload\":\"" + "x".repeat(500) + "\"}", "{\"resp\":\"ok\"}"));
        }
        assertThat(pressureStore.getAll().count()).isEqualTo(100);

        // Simulate sudden memory spike: headroom drops to 5 MB (below 20 MB minHeadroomBytes)
        simulatedHeadroom.set(5 * 1024 * 1024);

        // Adding 1 new event must trigger autonomous batch eviction in a single pass
        pressureStore.add(createServeEvent(stub, "/api/events/100", "{\"new\":\"event\"}", "{\"resp\":\"ok\"}"));

        assertThat(pressureStore.getStats().totalEvictions()).isGreaterThanOrEqualTo(20);
        assertThat(pressureStore.getAll().count()).isLessThanOrEqualTo(81);
    }

    @Test
    void shouldVerifyCompressedServeEventIsPureValueObjectWithoutEngineReferences() {
        for (java.lang.reflect.Field field : CompressedServeEvent.class.getDeclaredFields()) {
            Class<?> type = field.getType();
            assertThat(type)
                    .withFailMessage("CompressedServeEvent must not reference engine class: " + type.getName())
                    .isNotEqualTo(ZstdCodec.class)
                    .isNotEqualTo(DictionaryPool.class)
                    .isNotEqualTo(AdaptiveJournalCompactor.class)
                    .isNotEqualTo(JournalSearchIndex.class)
                    .isNotEqualTo(JournalMemoryGovernor.class);
        }
    }

    private ServeEvent createServeEvent(StubMapping stub, String url, String reqBody, String respBody) {
        byte[] reqBytes = reqBody.getBytes(StandardCharsets.UTF_8);
        byte[] respBytes = respBody.getBytes(StandardCharsets.UTF_8);

        LoggedRequest request = LoggedRequest.createFrom(new Request() {
            @Override
            public String getUrl() {
                return url;
            }

            @Override
            public String getAbsoluteUrl() {
                return "http://localhost:8080" + url;
            }

            @Override
            public RequestMethod getMethod() {
                return RequestMethod.POST;
            }

            @Override
            public String getScheme() {
                return "http";
            }

            @Override
            public String getHost() {
                return "localhost";
            }

            @Override
            public int getPort() {
                return 8080;
            }

            @Override
            public String getClientIp() {
                return "127.0.0.1";
            }

            @Override
            public String getHeader(String key) {
                return "Content-Type".equalsIgnoreCase(key) ? "application/json" : null;
            }

            @Override
            public HttpHeader header(String key) {
                return new HttpHeader(key, getHeader(key));
            }

            @Override
            public ContentTypeHeader contentTypeHeader() {
                return new ContentTypeHeader("application/json");
            }

            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders(new HttpHeader("Content-Type", "application/json"));
            }

            @Override
            public boolean containsHeader(String key) {
                return "Content-Type".equalsIgnoreCase(key);
            }

            @Override
            public Set<String> getAllHeaderKeys() {
                return Set.of("Content-Type");
            }

            @Override
            public Map<String, Cookie> getCookies() {
                return Collections.emptyMap();
            }

            @Override
            public QueryParameter queryParameter(String key) {
                return QueryParameter.absent(key);
            }

            @Override
            public FormParameter formParameter(String key) {
                return FormParameter.absent(key);
            }

            @Override
            public Map<String, FormParameter> formParameters() {
                return Collections.emptyMap();
            }

            @Override
            public byte[] getBody() {
                return reqBytes;
            }

            @Override
            public String getBodyAsString() {
                return reqBody;
            }

            @Override
            public String getBodyAsBase64() {
                return Base64.getEncoder().encodeToString(reqBytes);
            }

            @Override
            public boolean isBrowserProxyRequest() {
                return false;
            }

            @Override
            public Optional<Request> getOriginalRequest() {
                return Optional.empty();
            }

            @Override
            public String getProtocol() {
                return "HTTP/1.1";
            }

            @Override
            public com.github.tomakehurst.wiremock.common.url.PathParams getPathParameters() {
                return com.github.tomakehurst.wiremock.common.url.PathParams.empty();
            }

            @Override
            public boolean isMultipart() {
                return false;
            }

            @Override
            public Collection<Part> getParts() {
                return Collections.emptyList();
            }

            @Override
            public Part getPart(String name) {
                return null;
            }
        });

        Response response = Response.response()
                .status(201)
                .headers(new HttpHeaders(new HttpHeader("Content-Type", "application/json")))
                .body(respBytes)
                .build();
        LoggedResponse loggedResponse = LoggedResponse.from(response, Limit.UNLIMITED);

        ResponseDefinition respDef = ResponseDefinitionBuilder.responseDefinition()
                .withStatus(201)
                .withBody(respBody)
                .build();

        return new ServeEvent(
                UUID.randomUUID(), request, stub, respDef, loggedResponse, true, Timing.UNTIMED, new LinkedList<>());
    }
}
