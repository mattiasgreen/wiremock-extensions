package com.github.mattiasgreen.wiremock.examples.async;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mattiasgreen.wiremock.async.engine.AsyncEngine;
import com.github.mattiasgreen.wiremock.async.kafka.KafkaProduceRecord;
import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration test demonstrating {@code wiremock-extension-async} (Beta)
 * operating within the complete WireMock extension bundle.
 */
class AsyncMessagingBundleIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WireMockServer server;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        AsyncEngine.getInstance().reset();
        server =
                new WireMockServer(WireMockConfiguration.options().dynamicPort().extensionScanningEnabled(true));
        server.start();
        httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    @AfterEach
    void tearDown() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
        AsyncEngine.getInstance().reset();
    }

    @Test
    @DisplayName("Should auto-discover AsyncAdminEndpoint via SPI and report Beta status")
    void shouldAutoDiscoverAsyncAdminEndpointViaSpi() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/__admin/async/status"))
                .GET()
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);

        JsonNode json = MAPPER.readTree(resp.body());
        assertThat(json.get("status").asText()).isEqualTo("UP");
        assertThat(json.get("beta").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Should ingest records from real KafkaProducer into In-Process Kafka Wire Sink")
    void shouldIngestKafkaRecordsViaEngineSink() throws Exception {
        List<KafkaProduceRecord> records = Collections.synchronizedList(new ArrayList<>());
        AsyncEngine.getInstance().startKafkaSink(0, records::add);
        int sinkPort = AsyncEngine.getInstance().getKafkaSinkPort();

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:" + sinkPort);
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>("orders.events", "key-1", "{\"order\":\"ORD-1\"}"))
                    .get(5, TimeUnit.SECONDS);
            producer.send(new ProducerRecord<>("orders.events", "key-2", "{\"order\":\"ORD-2\"}"))
                    .get(5, TimeUnit.SECONDS);

            assertThat(records).hasSize(2);
            assertThat(records.get(0).value()).isEqualTo("{\"order\":\"ORD-1\"}");
            assertThat(records.get(1).value()).isEqualTo("{\"order\":\"ORD-2\"}");
            assertThat(AsyncEngine.getInstance().getMessagesConsumed()).isEqualTo(2L);

            // Verify authentic ServeEvents ingested into WireMock core RequestJournal
            List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> serveEvents = server.getAllServeEvents();
            assertThat(serveEvents)
                    .filteredOn(e -> e.getRequest().getUrl().equals("/__async/kafka/orders.events"))
                    .hasSize(2);
        }
    }

    @Test
    @DisplayName("Should trigger asynchronous callback message on HTTP POST 202 completion")
    void shouldTriggerAsyncCallbackOnHttpCompletion() throws Exception {
        BlockingQueue<AsyncMessage> queue = new LinkedBlockingQueue<>();
        AsyncEngine.getInstance().registerPublisher("payments.callbacks", (dest, msg) -> queue.offer(msg));

        server.stubFor(
                post(urlEqualTo("/api/v1/payments"))
                        .willReturn(aResponse()
                                .withStatus(202)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"paymentId\":\"PAY-4040\"}"))
                        .withMetadata(
                                Map.of(
                                        "async",
                                        Map.of(
                                                "produce",
                                                List.of(
                                                        Map.of(
                                                                "destination", "payments.callbacks",
                                                                "protocol", "kafka",
                                                                "key", "{{jsonPath response.body '$.paymentId'}}",
                                                                "payload",
                                                                        "{\"paymentId\":\"{{jsonPath response.body '$.paymentId'}}\",\"status\":\"CONFIRMED\"}"))))));

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/api/v1/payments"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(202);

        AsyncMessage callback = queue.poll(5, TimeUnit.SECONDS);
        assertThat(callback).isNotNull();
        assertThat(callback.destination()).isEqualTo("payments.callbacks");
        assertThat(callback.key()).isEqualTo("PAY-4040");
        assertThat(callback.payload()).isEqualTo("{\"paymentId\":\"PAY-4040\",\"status\":\"CONFIRMED\"}");
    }
}
