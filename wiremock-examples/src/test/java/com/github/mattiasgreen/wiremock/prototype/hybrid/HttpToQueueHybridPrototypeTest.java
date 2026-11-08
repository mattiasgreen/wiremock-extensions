package com.github.mattiasgreen.wiremock.prototype.hybrid;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.Metadata;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ServeEventListener;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Prototype 3: HTTP-to-Queue Hybrid Callback Trigger Spike.
 *
 * <p>Proves the hybrid synchronization model where an inbound HTTP invocation (e.g. REST POST 202 Accepted)
 * triggers one or more asynchronous outbound messages (Kafka, AMQP, IBM MQ, Casual) with response/request
 * template evaluation and OpenTelemetry synthetic observability metrics.
 */
class HttpToQueueHybridPrototypeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record OutboundMessage(String destination, String protocol, String key, String payload) {}

    public record SyntheticAsyncServeEvent(
            String id,
            String triggerProtocol,
            String triggerEndpoint,
            String targetProtocol,
            String destination,
            String payload,
            long durationNanos) {}

    /**
     * Minimal in-memory OpenTelemetry & synthetic journal metric recorder.
     */
    static class OtelAsyncMetricsCollector {
        private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
        private final List<SyntheticAsyncServeEvent> asyncJournal = Collections.synchronizedList(new ArrayList<>());

        public void incrementCounter(String name, String protocol, String destination, String status) {
            String metricKey = name + "{protocol=\"" + protocol + "\",destination=\"" + destination + "\",status=\""
                    + status + "\"}";
            counters.computeIfAbsent(metricKey, k -> new AtomicLong(0)).incrementAndGet();
        }

        public long getCounter(String name, String protocol, String destination, String status) {
            String metricKey = name + "{protocol=\"" + protocol + "\",destination=\"" + destination + "\",status=\""
                    + status + "\"}";
            AtomicLong val = counters.get(metricKey);
            return val != null ? val.get() : 0L;
        }

        public void recordSyntheticEvent(SyntheticAsyncServeEvent event) {
            asyncJournal.add(event);
        }

        public List<SyntheticAsyncServeEvent> getJournal() {
            return Collections.unmodifiableList(new ArrayList<>(asyncJournal));
        }
    }

    /**
     * WireMock ServeEventListener reacting to `metadata.async.produce` directives.
     */
    static class AsyncHttpToQueueTriggerListener implements ServeEventListener {
        private final Map<String, BlockingQueue<OutboundMessage>> messageBroker;
        private final OtelAsyncMetricsCollector otelCollector;

        public AsyncHttpToQueueTriggerListener(
                Map<String, BlockingQueue<OutboundMessage>> messageBroker, OtelAsyncMetricsCollector otelCollector) {
            this.messageBroker = messageBroker;
            this.otelCollector = otelCollector;
        }

        @Override
        public String getName() {
            return "async-http-to-queue-trigger";
        }

        @Override
        public boolean applyGlobally() {
            return true;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void afterComplete(ServeEvent serveEvent, Parameters parameters) {
            if (serveEvent == null || serveEvent.getStubMapping() == null) {
                return;
            }

            Metadata metadata = serveEvent.getStubMapping().getMetadata();
            if (metadata == null || !metadata.containsKey("async")) {
                return;
            }

            Map<String, Object> asyncConfig = (Map<String, Object>) metadata.get("async");
            List<Map<String, Object>> produceList = (List<Map<String, Object>>) asyncConfig.get("produce");
            if (produceList == null || produceList.isEmpty()) {
                return;
            }

            long start = System.nanoTime();
            String responseBody =
                    serveEvent.getResponse() != null ? serveEvent.getResponse().getBodyAsString() : "";
            String requestBody =
                    serveEvent.getRequest() != null ? serveEvent.getRequest().getBodyAsString() : "";

            for (Map<String, Object> directive : produceList) {
                String destination = (String) directive.get("destination");
                String protocol = (String) directive.getOrDefault("protocol", "kafka");
                String keyTemplate = (String) directive.getOrDefault("key", "");
                String payloadTemplate = (String) directive.get("payload");
                Number delayMs = (Number) directive.getOrDefault("delayMs", 0);

                // Simple template resolution: resolve jsonPath expressions against response or request
                String evaluatedKey = interpolate(keyTemplate, requestBody, responseBody);
                String evaluatedPayload = interpolate(payloadTemplate, requestBody, responseBody);

                // Asynchronous dispatch via virtual thread
                Thread.ofVirtual().start(() -> {
                    try {
                        if (delayMs != null && delayMs.longValue() > 0) {
                            Thread.sleep(delayMs.longValue());
                        }

                        // Record synthetic event & OTEL metrics
                        long durationNanos = System.nanoTime() - start;
                        otelCollector.incrementCounter(
                                "wiremock.async.produced.total", protocol, destination, "success");
                        otelCollector.recordSyntheticEvent(new SyntheticAsyncServeEvent(
                                serveEvent.getId().toString(),
                                "http",
                                serveEvent.getRequest().getUrl(),
                                protocol,
                                destination,
                                evaluatedPayload,
                                durationNanos));

                        // Send to target broker destination queue
                        BlockingQueue<OutboundMessage> queue =
                                messageBroker.computeIfAbsent(destination, k -> new LinkedBlockingQueue<>());
                        OutboundMessage message =
                                new OutboundMessage(destination, protocol, evaluatedKey, evaluatedPayload);
                        queue.put(message);
                    } catch (Exception e) {
                        otelCollector.incrementCounter("wiremock.async.produced.total", protocol, destination, "error");
                    }
                });
            }
        }

        private String interpolate(String template, String requestBody, String responseBody) {
            if (template == null) return "";
            String result = template;
            try {
                if (responseBody != null && !responseBody.isBlank()) {
                    JsonNode resNode = MAPPER.readTree(responseBody);
                    result = replaceJsonPlaceholders(result, "response.body", resNode);
                }
                if (requestBody != null && !requestBody.isBlank()) {
                    JsonNode reqNode = MAPPER.readTree(requestBody);
                    result = replaceJsonPlaceholders(result, "request.body", reqNode);
                }
            } catch (Exception ignored) {
            }
            return result;
        }

        private String replaceJsonPlaceholders(String text, String prefix, JsonNode root) {
            String placeholderPrefix = "{{jsonPath " + prefix + " '$.";
            int idx = text.indexOf(placeholderPrefix);
            while (idx != -1) {
                int endQuote = text.indexOf("'", idx + placeholderPrefix.length());
                if (endQuote != -1) {
                    int endBrace = text.indexOf("}}", endQuote);
                    if (endBrace != -1) {
                        String fieldName = text.substring(idx + placeholderPrefix.length(), endQuote);
                        JsonNode val = root.get(fieldName);
                        String replacement = val != null ? (val.isValueNode() ? val.asText() : val.toString()) : "";
                        text = text.substring(0, idx) + replacement + text.substring(endBrace + 2);
                        idx = text.indexOf(placeholderPrefix);
                        continue;
                    }
                }
                break;
            }
            return text;
        }
    }

    private WireMockServer wireMockServer;
    private HttpClient httpClient;
    private Map<String, BlockingQueue<OutboundMessage>> messageBroker;
    private OtelAsyncMetricsCollector otelCollector;

    @BeforeEach
    void setUp() {
        messageBroker = new ConcurrentHashMap<>();
        otelCollector = new OtelAsyncMetricsCollector();
        AsyncHttpToQueueTriggerListener listener = new AsyncHttpToQueueTriggerListener(messageBroker, otelCollector);

        wireMockServer =
                new WireMockServer(WireMockConfiguration.options().dynamicPort().extensions(listener));
        wireMockServer.start();
        httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    @Test
    @DisplayName("HTTP POST 202 Accepted triggers outbound Kafka event and records synthetic OTEL metrics")
    void shouldTriggerOutboundMessageOnHttpCall() throws Exception {
        // Register HTTP stub with metadata.async.produce directive
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/payments/submit"))
                .withHeader("Content-Type", equalTo("application/json"))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"paymentId\":\"PAY-98172\",\"status\":\"ACCEPTED\"}"))
                .withMetadata(Map.of(
                        "async",
                        Map.of(
                                "produce",
                                List.of(Map.of(
                                        "destination", "payments.settled.events",
                                        "protocol", "kafka",
                                        "key", "{{jsonPath response.body '$.paymentId'}}",
                                        "payload",
                                                "{\"paymentId\":\"{{jsonPath response.body '$.paymentId'}}\",\"status\":\"SETTLED\",\"amount\":129.99}",
                                        "delayMs", 10))))));

        // 1. Client calls HTTP POST endpoint
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(wireMockServer.baseUrl() + "/api/v1/payments/submit"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"amount\":129.99,\"currency\":\"USD\"}"))
                .build();

        HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Assert HTTP response
        assertThat(httpResponse.statusCode()).isEqualTo(202);
        assertThat(httpResponse.body()).contains("\"PAY-98172\"");

        // 2. Client / Consumer polls the async queue
        BlockingQueue<OutboundMessage> queue =
                messageBroker.computeIfAbsent("payments.settled.events", k -> new LinkedBlockingQueue<>());
        OutboundMessage message = queue.poll(3, TimeUnit.SECONDS);
        assertThat(message).isNotNull();
        assertThat(message.destination()).isEqualTo("payments.settled.events");
        assertThat(message.protocol()).isEqualTo("kafka");
        assertThat(message.key()).isEqualTo("PAY-98172");
        assertThat(message.payload())
                .isEqualTo("{\"paymentId\":\"PAY-98172\",\"status\":\"SETTLED\",\"amount\":129.99}");

        // 3. Verify OpenTelemetry counter & synthetic Journal
        assertThat(otelCollector.getCounter(
                        "wiremock.async.produced.total", "kafka", "payments.settled.events", "success"))
                .isEqualTo(1L);

        List<SyntheticAsyncServeEvent> journal = otelCollector.getJournal();
        assertThat(journal).hasSize(1);
        SyntheticAsyncServeEvent event = journal.get(0);
        assertThat(event.triggerProtocol()).isEqualTo("http");
        assertThat(event.triggerEndpoint()).isEqualTo("/api/v1/payments/submit");
        assertThat(event.targetProtocol()).isEqualTo("kafka");
        assertThat(event.destination()).isEqualTo("payments.settled.events");
        assertThat(event.payload()).contains("PAY-98172");
    }

    @Test
    @DisplayName("HTTP POST should support fan-out producing to multiple async destinations (AMQP and Casual)")
    void shouldFanOutToMultipleAsyncDestinations() throws Exception {
        wireMockServer.stubFor(
                post(urlEqualTo("/api/v1/orders/dispatch"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"orderNumber\":\"ORD-5501\"}"))
                        .withMetadata(
                                Map.of(
                                        "async",
                                        Map.of(
                                                "produce",
                                                List.of(
                                                        Map.of(
                                                                "destination", "orders.notifications",
                                                                "protocol", "amqp",
                                                                "payload",
                                                                        "{\"order\":\"{{jsonPath response.body '$.orderNumber'}}\",\"channel\":\"SMS\"}"),
                                                        Map.of(
                                                                "destination", "orders.casual.queue",
                                                                "protocol", "casual",
                                                                "payload",
                                                                        "{\"order\":\"{{jsonPath response.body '$.orderNumber'}}\",\"action\":\"ARCHIVE\"}"))))));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(wireMockServer.baseUrl() + "/api/v1/orders/dispatch"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(httpResponse.statusCode()).isEqualTo(200);

        BlockingQueue<OutboundMessage> amqpQueue =
                messageBroker.computeIfAbsent("orders.notifications", k -> new LinkedBlockingQueue<>());
        BlockingQueue<OutboundMessage> casualQueue =
                messageBroker.computeIfAbsent("orders.casual.queue", k -> new LinkedBlockingQueue<>());

        OutboundMessage amqpMsg = amqpQueue.poll(3, TimeUnit.SECONDS);
        OutboundMessage casualMsg = casualQueue.poll(3, TimeUnit.SECONDS);

        assertThat(amqpMsg).isNotNull();
        assertThat(amqpMsg.protocol()).isEqualTo("amqp");
        assertThat(amqpMsg.payload()).isEqualTo("{\"order\":\"ORD-5501\",\"channel\":\"SMS\"}");

        assertThat(casualMsg).isNotNull();
        assertThat(casualMsg.protocol()).isEqualTo("casual");
        assertThat(casualMsg.payload()).isEqualTo("{\"order\":\"ORD-5501\",\"action\":\"ARCHIVE\"}");

        assertThat(otelCollector.getCounter("wiremock.async.produced.total", "amqp", "orders.notifications", "success"))
                .isEqualTo(1L);
        assertThat(otelCollector.getCounter(
                        "wiremock.async.produced.total", "casual", "orders.casual.queue", "success"))
                .isEqualTo(1L);
    }
}
