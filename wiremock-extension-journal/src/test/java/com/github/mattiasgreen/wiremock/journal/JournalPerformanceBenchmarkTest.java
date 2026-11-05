package com.github.mattiasgreen.wiremock.journal;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.journal.engine.DictionaryPool;
import com.github.mattiasgreen.wiremock.journal.engine.JournalMemoryGovernor;
import com.github.mattiasgreen.wiremock.journal.engine.ZstdCodec;
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
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class JournalPerformanceBenchmarkTest {

    private ZstdCodec codec;
    private DictionaryPool dictionaryPool;
    private CompressedRequestJournalStore store;

    @BeforeEach
    void setUp() {
        codec = new ZstdCodec();
        // 64 dictionaries pool, 20 sample training threshold, 32 KB target dict size
        dictionaryPool = new DictionaryPool(64, 20, 32 * 1024, codec);
        store = new CompressedRequestJournalStore(
                codec, dictionaryPool, new JournalMemoryGovernor(32 * 1024 * 1024, 256 * 1024 * 1024));
    }

    @AfterEach
    void tearDown() {
        store.clear();
    }

    @Test
    void benchmarkHighVolumeTrafficWithProfilingAndFreeTextSearch() throws Exception {
        final int TOTAL_REQUESTS = 5000;
        final int[] CHECKPOINTS = {10, 50, 100, 500, 1000, 5000};
        int checkpointIndex = 0;

        // Distinct stubs to simulate multiple realistic endpoints (e.g. Case Management, Payments, Casual Gateway)
        Map<String, StubMapping> stubs = new HashMap<>();
        String[] endpointTypes = {"cases", "payments", "casual-cust-service", "notifications"};
        for (String type : endpointTypes) {
            StubMapping stub = new StubMapping();
            stub.setId(UUID.randomUUID());
            stub.setName("Stub for " + type);
            stubs.put(type, stub);
        }

        // Special needle for free-text search verification
        String needleCaseId = "CASE-NEEDLE-998877-XYZ";
        int needleIndex = 3847;
        UUID needleEventId = null;

        System.out.println(
                "==========================================================================================");
        System.out.println("Starting Adaptive Zstandard Journal Compression Benchmark (5,000 requests)");
        System.out.println(String.format(
                "%-8s | %-12s | %-14s | %-10s | %-12s | %-10s",
                "Events", "Raw (KB)", "Compressed (KB)", "Ratio", "Active Dicts", "Elapsed (ms)"));
        System.out.println(
                "------------------------------------------------------------------------------------------");

        long startNanos = System.nanoTime();

        for (int i = 1; i <= TOTAL_REQUESTS; i++) {
            String endpointType = endpointTypes[i % endpointTypes.length];
            StubMapping stub = stubs.get(endpointType);

            String caseId = (i == needleIndex)
                    ? needleCaseId
                    : String.format(Locale.ROOT, "CASE-%s-%06d", endpointType.toUpperCase(Locale.ROOT), i);
            String timestamp = Instant.now().plusMillis(i * 17L).toString();
            String correlationId = UUID.randomUUID().toString();

            String reqPayload = generateRealisticRequestPayload(endpointType, caseId, correlationId, timestamp);
            String respPayload = generateRealisticResponsePayload(endpointType, caseId, correlationId, timestamp);

            ServeEvent event = createServeEvent(
                    stub,
                    "/api/" + endpointType + "/" + caseId,
                    reqPayload,
                    respPayload,
                    endpointType.startsWith("casual"));
            store.add(event);

            if (i == needleIndex) {
                needleEventId = event.getId();
            }

            // Checkpoint profiling
            if (checkpointIndex < CHECKPOINTS.length && i == CHECKPOINTS[checkpointIndex]) {
                // Allow a brief moment for background virtual-thread dictionary training
                if (i == 50 || i == 100) {
                    Thread.sleep(50);
                }

                JournalStats stats = store.getStats();
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
                System.out.println(String.format(
                        Locale.ROOT,
                        "%-8d | %-12.1f | %-14.1f | %-10.2fx | %-12d | %-10d",
                        stats.totalEvents(),
                        stats.rawBytesTotal() / 1024.0,
                        stats.compressedBytesTotal() / 1024.0,
                        stats.compressionRatio(),
                        stats.activeDictionaries(),
                        elapsedMs));

                checkpointIndex++;
            }
        }

        System.out.println(
                "==========================================================================================");

        JournalStats finalStats = store.getStats();
        assertThat(finalStats.totalEvents() + finalStats.totalEvictions()).isEqualTo(TOTAL_REQUESTS);
        assertThat(finalStats.activeDictionaries()).isGreaterThanOrEqualTo(4);
        assertThat(finalStats.compressionRatio()).isGreaterThanOrEqualTo(10.0);

        System.out.println(String.format(
                Locale.ROOT,
                "FINAL SUMMARY: %d events processed. Raw: %.2f MB -> Compressed: %.2f MB. Compression Ratio: %.2fx",
                finalStats.totalEvents(),
                finalStats.rawBytesTotal() / (1024.0 * 1024.0),
                finalStats.compressedBytesTotal() / (1024.0 * 1024.0),
                finalStats.compressionRatio()));

        // --- Free-Text Search Verification ---
        System.out.println("\nExecuting Free-Text Search across 5,000 compressed events for: " + needleCaseId);
        long decompressionsBefore = store.getDecompressionCount();
        long searchStartNanos = System.nanoTime();
        List<UUID> searchResults = store.search(needleCaseId);
        long searchDurationMs = (System.nanoTime() - searchStartNanos) / 1_000_000;

        System.out.println(
                String.format("Search completed in %d ms. Matches found: %d", searchDurationMs, searchResults.size()));
        assertThat(searchResults).containsExactly(needleEventId);
        assertThat(searchDurationMs).isLessThan(500); // Sub-half-second search across 5,000 in-memory items
        // Zero-decompression search assertion
        assertThat(store.getDecompressionCount()).isEqualTo(decompressionsBefore);
        assertThat(store.getSearchIndex().getIndexHits()).isGreaterThanOrEqualTo(1);

        // Negative search assertion across 5,000 items
        long negStart = System.nanoTime();
        List<UUID> negResults = store.search("COMPLETELY_ABSENT_CASE_ID_99999");
        long negDurationNanos = System.nanoTime() - negStart;
        assertThat(negResults).isEmpty();
        assertThat(store.getDecompressionCount()).isEqualTo(decompressionsBefore);
        assertThat(negDurationNanos).isLessThan(50_000_000L); // Fast sub-50ms index miss on cold run

        // --- Exact Fidelity Verification on Needle ---
        Optional<ServeEvent> needleEventOpt = store.get(needleEventId);
        assertThat(needleEventOpt).isPresent();
        ServeEvent needleEvent = needleEventOpt.get();
        assertThat(needleEvent.getRequest().getBodyAsString()).contains(needleCaseId);
        assertThat(needleEvent.getResponse().getBodyAsString()).contains(needleCaseId);
    }

    @Test
    void benchmarkConcurrentLoad_10k() throws Exception {
        executeConcurrentLoad(4, 2_500, "Concurrent Load Benchmark: 10,000 Requests across 4 Clients");
    }

    @Test
    @Tag("benchmark")
    void benchmarkExtremeConcurrentLoad_250k() throws Exception {
        executeConcurrentLoad(20, 12_500, "Extreme Concurrent Stress Benchmark: 250,000 Requests across 20 Clients");
    }

    private void executeConcurrentLoad(int concurrentClients, int requestsPerClient, String benchmarkTitle)
            throws Exception {
        final int TOTAL_REQUESTS = concurrentClients * requestsPerClient;

        String[] endpointTypes = {
            "cases", "payments", "accounts", "auth",
            "telemetry", "orders", "inventory", "casual-xatmi"
        };

        Map<String, StubMapping> stubs = new HashMap<>();
        for (String type : endpointTypes) {
            StubMapping stub = new StubMapping();
            stub.setId(UUID.randomUUID());
            stub.setName("Stub for " + type);
            stubs.put(type, stub);
        }

        String needleId = "CONCURRENT-NEEDLE-42-SECRET";
        int needleClient = concurrentClients / 2;
        int needleReqIndex = Math.max(1, requestsPerClient - 10);
        java.util.concurrent.atomic.AtomicReference<UUID> needleEventIdRef =
                new java.util.concurrent.atomic.AtomicReference<>();

        System.out.println(
                "\n==========================================================================================");
        System.out.println("Starting " + benchmarkTitle);
        System.out.println(String.format(
                "%-10s | %-12s | %-14s | %-10s | %-10s | %-12s | %-10s",
                "Events", "Raw (MB)", "Comp (MB)", "Ratio", "Evicted", "Active Dicts", "Elapsed (s)"));
        System.out.println(
                "------------------------------------------------------------------------------------------");

        long startNanos = System.nanoTime();
        java.util.concurrent.atomic.AtomicInteger totalSubmitted = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicBoolean benchmarkRunning =
                new java.util.concurrent.atomic.AtomicBoolean(true);

        // 1. Launch 2 Background Search/Read Workers to pound the journal concurrently with readers
        java.util.concurrent.atomic.AtomicLong backgroundSearchesExecuted =
                new java.util.concurrent.atomic.AtomicLong(0);
        Thread searchWorker1 = Thread.ofVirtual().name("background-search-1").start(() -> {
            while (benchmarkRunning.get()) {
                try {
                    store.search("CUST-9928174");
                    backgroundSearchesExecuted.incrementAndGet();
                    Thread.sleep(100);
                } catch (Exception ignored) {
                }
            }
        });
        Thread searchWorker2 = Thread.ofVirtual().name("background-search-2").start(() -> {
            while (benchmarkRunning.get()) {
                try {
                    store.search("ALLOCATED");
                    backgroundSearchesExecuted.incrementAndGet();
                    Thread.sleep(100);
                } catch (Exception ignored) {
                }
            }
        });

        // 2. Launch Progress Logger Thread
        int progressStep = Math.max(2_000, TOTAL_REQUESTS / 10);
        Thread progressLogger = Thread.ofVirtual().name("benchmark-logger").start(() -> {
            int lastLogged = 0;
            while (benchmarkRunning.get()) {
                try {
                    Thread.sleep(1000);
                    int current = totalSubmitted.get();
                    if (current - lastLogged >= progressStep || current >= TOTAL_REQUESTS) {
                        lastLogged = current;
                        JournalStats stats = store.getStats();
                        double elapsedSec = (System.nanoTime() - startNanos) / 1_000_000_000.0;
                        System.out.println(String.format(
                                Locale.ROOT,
                                "%-10d | %-12.1f | %-14.1f | %-10.2fx | %-10d | %-12d | %-10.1fs",
                                stats.totalEvents(),
                                stats.rawBytesTotal() / (1024.0 * 1024.0),
                                stats.compressedBytesTotal() / (1024.0 * 1024.0),
                                stats.compressionRatio(),
                                stats.totalEvictions(),
                                stats.activeDictionaries(),
                                elapsedSec));
                    }
                } catch (Exception ignored) {
                }
            }
        });

        // 3. Execute Concurrent Client Workers
        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(concurrentClients);
        java.util.List<java.util.concurrent.Future<?>> futures = new ArrayList<>();

        for (int c = 0; c < concurrentClients; c++) {
            final int clientId = c;
            futures.add(executor.submit(() -> {
                for (int r = 1; r <= requestsPerClient; r++) {
                    String endpoint = endpointTypes[(clientId + r) % endpointTypes.length];
                    StubMapping stub = stubs.get(endpoint);

                    boolean isNeedle = (clientId == needleClient && r == needleReqIndex);
                    String caseId =
                            isNeedle ? needleId : String.format(Locale.ROOT, "C%02d-R%05d-%s", clientId, r, endpoint);
                    String timestamp = Instant.now().toString();
                    String corrId = UUID.randomUUID().toString();

                    String reqPayload = generateRealisticRequestPayload(endpoint, caseId, corrId, timestamp);
                    String respPayload = generateRealisticResponsePayload(endpoint, caseId, corrId, timestamp);

                    ServeEvent event = createServeEvent(
                            stub,
                            "/api/" + endpoint + "/" + caseId,
                            reqPayload,
                            respPayload,
                            endpoint.startsWith("casual"));
                    store.add(event);

                    if (isNeedle) {
                        needleEventIdRef.set(event.getId());
                    }

                    totalSubmitted.incrementAndGet();
                }
            }));
        }

        // Wait for all clients to finish
        for (java.util.concurrent.Future<?> f : futures) {
            f.get();
        }

        benchmarkRunning.set(false);
        searchWorker1.interrupt();
        searchWorker2.interrupt();
        progressLogger.interrupt();
        executor.shutdown();
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        searchWorker1.join(1000);
        searchWorker2.join(1000);
        progressLogger.join(1000);

        long totalNanos = System.nanoTime() - startNanos;
        double totalSec = totalNanos / 1_000_000_000.0;
        JournalStats finalStats = store.getStats();

        System.out.println(
                "==========================================================================================");
        System.out.println(String.format(
                Locale.ROOT,
                "%s COMPLETED in %.2f seconds! (%.0f req/sec)",
                benchmarkTitle,
                totalSec,
                TOTAL_REQUESTS / Math.max(0.001, totalSec)));
        System.out.println(String.format(
                Locale.ROOT,
                "Total Processed: %d | Retained: %d | Evicted: %d | Background Searches: %d",
                finalStats.totalEvents() + finalStats.totalEvictions(),
                finalStats.totalEvents(),
                finalStats.totalEvictions(),
                backgroundSearchesExecuted.get()));
        System.out.println(String.format(
                Locale.ROOT,
                "Raw Ingested: %.2f MB | Physical Heap Stored: %.2f MB | Global Ratio: %.2fx",
                finalStats.rawBytesTotal() / (1024.0 * 1024.0),
                finalStats.compressedBytesTotal() / (1024.0 * 1024.0),
                finalStats.compressionRatio()));
        System.out.println("Active Dictionaries Trained: " + finalStats.activeDictionaries());
        System.out.println(
                "==========================================================================================");

        assertThat(finalStats.totalEvents() + finalStats.totalEvictions()).isEqualTo(TOTAL_REQUESTS);
        assertThat(finalStats.activeDictionaries()).isEqualTo(8);
        assertThat(finalStats.compressionRatio()).isGreaterThanOrEqualTo(10.0);

        // Verify needle retrieval via inverted index and direct lookup
        UUID needleIdFound = needleEventIdRef.get();
        assertThat(needleIdFound).isNotNull();
        List<UUID> needleSearchHits = store.search(needleId);
        assertThat(needleSearchHits).contains(needleIdFound);
        Optional<ServeEvent> needleEventOpt = store.get(needleIdFound);
        assertThat(needleEventOpt).isPresent();
        assertThat(needleEventOpt.get().getRequest().getBodyAsString()).contains(needleId);
    }

    private static final String PRECOMPUTED_LINE_ITEMS = buildLineItems();

    private static String buildLineItems() {
        StringBuilder items = new StringBuilder();
        for (int j = 1; j <= 15; j++) {
            if (j > 1) items.append(",");
            items.append(String.format(
                    Locale.ROOT,
                    "{\"lineId\":\"LINE-%04d\",\"sku\":\"SKU-PART-%03d\",\"name\":\"Standard Component Part %d\",\"category\":\"HARDWARE_ELECTRONICS\",\"unitPrice\":149.50,\"quantity\":%d,\"currency\":\"SEK\",\"vatRate\":0.25,\"status\":\"ALLOCATED\",\"warehouse\":{\"code\":\"WH-SE-01\",\"shelf\":\"B-12-%02d\",\"zone\":\"NORDIC_MAIN\"},\"specifications\":{\"weightGrams\":350,\"dimensions\":{\"length\":12.5,\"width\":8.0,\"height\":2.2},\"rohsCompliant\":true,\"refrigerationRequired\":false}}",
                    j,
                    j % 20,
                    j,
                    (j % 5) + 1,
                    j));
        }
        return items.toString();
    }

    private String generateRealisticRequestPayload(
            String endpoint, String caseId, String correlationId, String timestamp) {
        return String.format(
                Locale.ROOT,
                "{"
                        + "\"header\":{\"correlationId\":\"%s\",\"channel\":\"WEB-PORTAL\",\"clientIp\":\"192.168.1.100\",\"timestamp\":\"%s\"},"
                        + "\"request\":{\"endpoint\":\"%s\",\"targetId\":\"%s\",\"operation\":\"MUTATE_RECORD\",\"parameters\":{\"priority\":\"HIGH\",\"notify\":true,\"tags\":[\"production\",\"audit\",\"case-flow\"]}}"
                        + "}",
                correlationId,
                timestamp,
                endpoint,
                caseId);
    }

    private String generateRealisticResponsePayload(
            String endpoint, String caseId, String correlationId, String timestamp) {
        return String.format(
                Locale.ROOT,
                "{"
                        + "\"schema\":{\"version\":\"3.2.1\",\"namespace\":\"com.github.mattiasgreen.wiremock\",\"model\":\"EnterpriseCaseRecord\"},"
                        + "\"meta\":{\"correlationId\":\"%s\",\"statusCode\":200,\"executionTimeMs\":12,\"serverNode\":\"srv-mock-node-04\",\"timestamp\":\"%s\"},"
                        + "\"data\":{"
                        + "\"caseId\":\"%s\","
                        + "\"type\":\"%s\","
                        + "\"lifecycleState\":\"UNDER_INVESTIGATION\","
                        + "\"assignedTeam\":{\"teamId\":\"TEAM-NORDIC-OPS\",\"lead\":\"Erika Lind\",\"location\":\"Stockholm HQ\"},"
                        + "\"customer\":{\"customerId\":\"CUST-9928174\",\"company\":\"Nordic Industrial AB\",\"orgNumber\":\"556012-3456\",\"address\":{\"street\":\"Drottninggatan 12\",\"postalCode\":\"11151\",\"city\":\"Stockholm\",\"country\":\"Sweden\"}},"
                        + "\"lineItems\":[%s],"
                        + "\"timeline\":["
                        + "{\"event\":\"CASE_INITIALIZED\",\"author\":\"SYSTEM\",\"date\":\"%s\",\"note\":\"Auto-triaged by routing engine\"},"
                        + "{\"event\":\"IDENTITY_VERIFIED\",\"author\":\"AUTH_GATEWAY\",\"date\":\"%s\",\"note\":\"BankID e-ID signature confirmed\"},"
                        + "{\"event\":\"DOCUMENTS_ATTACHED\",\"author\":\"CASE_WORKER_01\",\"date\":\"%s\",\"note\":\"Attached invoices and shipping slips\"}"
                        + "],"
                        + "\"auditLog\":["
                        + "{\"action\":\"READ_PERMISSIONS\",\"actor\":\"srv-account\",\"ip\":\"10.0.12.44\"},"
                        + "{\"action\":\"WRITE_MUTATION\",\"actor\":\"srv-core\",\"ip\":\"10.0.12.48\"}"
                        + "]"
                        + "}"
                        + "}",
                correlationId,
                timestamp,
                caseId,
                endpoint,
                PRECOMPUTED_LINE_ITEMS,
                timestamp,
                timestamp,
                timestamp);
    }

    private ServeEvent createServeEvent(
            StubMapping stub, String url, String reqBody, String respBody, boolean isCasual) {
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
                if ("Content-Type".equalsIgnoreCase(key)) return "application/json";
                if (isCasual && "X-Casual-Service".equalsIgnoreCase(key)) return "CUST_SERVICE_XATMI";
                return null;
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
                List<HttpHeader> list = new ArrayList<>();
                list.add(new HttpHeader("Content-Type", "application/json"));
                if (isCasual) list.add(new HttpHeader("X-Casual-Service", "CUST_SERVICE_XATMI"));
                return new HttpHeaders(list);
            }

            @Override
            public boolean containsHeader(String key) {
                return getHeader(key) != null;
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
                .status(200)
                .headers(new HttpHeaders(new HttpHeader("Content-Type", "application/json")))
                .body(respBytes)
                .build();
        LoggedResponse loggedResponse = LoggedResponse.from(response, Limit.UNLIMITED);

        ResponseDefinition respDef = ResponseDefinitionBuilder.responseDefinition()
                .withStatus(200)
                .withBody(respBody)
                .build();

        return new ServeEvent(
                UUID.randomUUID(), request, stub, respDef, loggedResponse, true, Timing.UNTIMED, new LinkedList<>());
    }
}
