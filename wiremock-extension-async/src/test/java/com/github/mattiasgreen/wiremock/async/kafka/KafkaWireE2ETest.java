package com.github.mattiasgreen.wiremock.async.kafka;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mattiasgreen.wiremock.async.admin.AsyncExtensionFactory;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KafkaWireE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WireMockServer wireMockServer;
    private HttpClient httpClient;
    private int kafkaSinkPort;

    @BeforeEach
    void setUp() throws Exception {
        wireMockServer = new WireMockServer(
                WireMockConfiguration.options().dynamicPort().extensions(new AsyncExtensionFactory()));
        wireMockServer.start();
        httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        kafkaSinkPort = startKafkaSink();
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    @Test
    @DisplayName("Inbound Kafka stubbing delivers correlated reply to real KafkaConsumer and records ServeEvent")
    void shouldStubInboundKafkaAndDeliverCorrelatedReplyToRealConsumer() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__async/kafka/orders.in"))
                .withName("orders-inbound-mock")
                .atPriority(5)
                .withHeader("x-tenant", equalTo("nordic-bank"))
                .withRequestBody(containing("ORD-E2E-1"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"orderId\":\"ORD-E2E-1\",\"status\":\"CONFIRMED\"}"))
                .withMetadata(Map.of(
                        "async",
                        Map.of(
                                "mode", "mock",
                                "protocol", "kafka",
                                "channel", "orders.in"))));

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps(kafkaSinkPort))) {
            ProducerRecord<String, String> record =
                    new ProducerRecord<>("orders.in", "key-ord-1", "{\"orderId\":\"ORD-E2E-1\",\"amount\":250}");
            record.headers().add("x-tenant", "nordic-bank".getBytes(StandardCharsets.UTF_8));
            record.headers().add("x-reply-to", "orders.out".getBytes(StandardCharsets.UTF_8));
            record.headers().add("x-correlation-id", "corr-e2e-1".getBytes(StandardCharsets.UTF_8));
            producer.send(record).get(5, TimeUnit.SECONDS);
        }

        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(consumerProps(kafkaSinkPort, "e2e-reply-consumer"))) {
            consumer.subscribe(Collections.singletonList("orders.out"));
            List<ConsumerRecord<String, String>> replies = pollUntilCount(consumer, 1, Duration.ofSeconds(5));

            assertThat(replies).hasSize(1);
            ConsumerRecord<String, String> reply = replies.get(0);
            assertThat(reply.key()).isEqualTo("key-ord-1");
            JsonNode replyJson = MAPPER.readTree(reply.value());
            assertThat(replyJson.get("orderId").asText()).isEqualTo("ORD-E2E-1");
            assertThat(replyJson.get("status").asText()).isEqualTo("CONFIRMED");

            Header corrHeader = reply.headers().lastHeader("x-correlation-id");
            assertThat(corrHeader).isNotNull();
            assertThat(new String(corrHeader.value(), StandardCharsets.UTF_8)).isEqualTo("corr-e2e-1");
        }

        List<ServeEvent> events = wireMockServer.getAllServeEvents();
        assertThat(events).anySatisfy(se -> {
            assertThat(se.getWasMatched()).isTrue();
            assertThat(se.getStubMapping().getName()).isEqualTo("orders-inbound-mock");
            assertThat(se.getRequest().header("X-Async-Protocol").firstValue()).isEqualTo("kafka");
            assertThat(se.getRequest().header("X-Async-Channel").firstValue()).isEqualTo("orders.in");
            assertThat(se.getRequest().header("X-Correlation-Id").firstValue()).isEqualTo("corr-e2e-1");
            assertThat(se.getResponse().getBodyAsString()).contains("\"status\":\"CONFIRMED\"");
        });
    }

    @Test
    @DisplayName(
            "Progressive simulation: live Kafka bridge -> harvest offline stub -> stop upstream -> 502 & offline replay")
    void shouldBridgeLiveKafkaHarvestOfflineStubThenEmit502AndReplayOfflineWhenUpstreamStops() throws Exception {
        KafkaWireSink upstreamSink = new KafkaWireSink(0, null);
        upstreamSink.start();
        try {
            int upstreamPort = upstreamSink.getPort();

            // Register upstream Kafka broker via Admin API
            String connPayload = MAPPER.writeValueAsString(Map.of(
                    "id", "upstream-kafka",
                    "protocol", "kafka",
                    "name", "Upstream Kafka Broker",
                    "endpoint", "127.0.0.1:" + upstreamPort));
            HttpResponse<String> connResp = httpClient.send(
                    HttpRequest.newBuilder()
                            .uri(URI.create(wireMockServer.baseUrl() + "/__admin/async/connections"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(connPayload))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(connResp.statusCode()).isEqualTo(201);

            // Register priority 100 bridge stub on /__async/kafka/payments.in -> payments.upstream
            wireMockServer.stubFor(post(urlEqualTo("/__async/kafka/payments.in"))
                    .withName("payments-upstream-bridge")
                    .atPriority(100)
                    .willReturn(aResponse().proxiedFrom("async://upstream-kafka"))
                    .withMetadata(Map.of(
                            "async",
                            Map.of(
                                    "mode", "proxy",
                                    "protocol", "kafka",
                                    "channel", "payments.in",
                                    "proxyBroker", "upstream-kafka",
                                    "targetChannel", "payments.upstream"))));

            // 1. Live wire-to-wire bridge: send to WireMock's sink, consume from upstream sink
            try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps(kafkaSinkPort))) {
                ProducerRecord<String, String> liveRecord =
                        new ProducerRecord<>("payments.in", "pay-100", "{\"paymentId\":\"PAY-100\",\"amount\":499.00}");
                liveRecord.headers().add("x-correlation-id", "corr-pay-100".getBytes(StandardCharsets.UTF_8));
                producer.send(liveRecord).get(5, TimeUnit.SECONDS);
            }

            try (KafkaConsumer<String, String> upstreamConsumer =
                    new KafkaConsumer<>(consumerProps(upstreamPort, "upstream-verifier"))) {
                upstreamConsumer.subscribe(Collections.singletonList("payments.upstream"));
                List<ConsumerRecord<String, String>> forwarded =
                        pollUntilCount(upstreamConsumer, 1, Duration.ofSeconds(5));
                assertThat(forwarded).hasSize(1);
                assertThat(forwarded.get(0).key()).isEqualTo("pay-100");
                assertThat(forwarded.get(0).value()).contains("\"paymentId\":\"PAY-100\"");
            }

            assertThat(wireMockServer.getAllServeEvents()).anySatisfy(se -> {
                assertThat(se.getResponseDefinition().isProxyResponse()).isTrue();
                assertThat(se.getRequest().header("X-Async-Protocol").firstValue())
                        .isEqualTo("kafka");
                assertThat(se.getRequest().header("X-Async-Channel").firstValue())
                        .isEqualTo("payments.in");
                assertThat(se.getResponseDefinition().getProxyBaseUrl()).contains("upstream-kafka/payments.upstream");
            });

            // 2. Harvest offline reply stub at priority 5 from the captured exchange
            wireMockServer.stubFor(post(urlEqualTo("/__async/kafka/payments.in"))
                    .withName("payments-harvested-offline")
                    .atPriority(5)
                    .withRequestBody(containing("PAY-100"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"paymentId\":\"PAY-100\",\"status\":\"SETTLED_OFFLINE\"}"))
                    .withMetadata(Map.of(
                            "async",
                            Map.of(
                                    "mode", "mock",
                                    "protocol", "kafka",
                                    "channel", "payments.in",
                                    "harvested", true))));

            // 3. Stop upstream Kafka broker completely
            upstreamSink.close();

            // 4a. Unstubbed bridged message -> real 502 Synthetic Async ServeEvent with X-Async-Error
            try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps(kafkaSinkPort))) {
                ProducerRecord<String, String> unstubbed =
                        new ProducerRecord<>("payments.in", "pay-999", "{\"paymentId\":\"PAY-999\",\"amount\":10.00}");
                producer.send(unstubbed).get(10, TimeUnit.SECONDS);
            }

            assertThat(wireMockServer.getAllServeEvents()).anySatisfy(se -> {
                assertThat(se.getResponseDefinition().getStatus()).isEqualTo(502);
                assertThat(se.getResponse()
                                .getHeaders()
                                .getHeader("X-Async-Error")
                                .isPresent())
                        .isTrue();
                assertThat(se.getRequest().header("X-Async-Protocol").firstValue())
                        .isEqualTo("kafka");
                assertThat(se.getRequest().header("X-Async-Channel").firstValue())
                        .isEqualTo("payments.in");
            });

            // 4b. Harvested offline stub -> real KafkaConsumer receives offline stubbed reply with zero upstream
            try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps(kafkaSinkPort))) {
                ProducerRecord<String, String> offlineReq =
                        new ProducerRecord<>("payments.in", "pay-100", "{\"paymentId\":\"PAY-100\",\"amount\":499.00}");
                offlineReq.headers().add("x-reply-to", "payments.out".getBytes(StandardCharsets.UTF_8));
                offlineReq.headers().add("x-correlation-id", "corr-offline-100".getBytes(StandardCharsets.UTF_8));
                producer.send(offlineReq).get(5, TimeUnit.SECONDS);
            }

            try (KafkaConsumer<String, String> offlineConsumer =
                    new KafkaConsumer<>(consumerProps(kafkaSinkPort, "offline-reply-verifier"))) {
                offlineConsumer.subscribe(Collections.singletonList("payments.out"));
                List<ConsumerRecord<String, String>> offlineReplies =
                        pollUntilCount(offlineConsumer, 1, Duration.ofSeconds(5));
                assertThat(offlineReplies).hasSize(1);
                JsonNode replyJson = MAPPER.readTree(offlineReplies.get(0).value());
                assertThat(replyJson.get("paymentId").asText()).isEqualTo("PAY-100");
                assertThat(replyJson.get("status").asText()).isEqualTo("SETTLED_OFFLINE");
                Header corr = offlineReplies.get(0).headers().lastHeader("x-correlation-id");
                assertThat(corr).isNotNull();
                assertThat(new String(corr.value(), StandardCharsets.UTF_8)).isEqualTo("corr-offline-100");
            }
        } finally {
            upstreamSink.close();
        }
    }

    @Test
    @DisplayName(
            "HTTP-triggered metadata.async.produce and call-triggered metadata.async.feed deliver interpolated JSON to real KafkaConsumer")
    void shouldDeliverHttpTriggeredProduceAndCallTriggeredFeedToRealKafkaConsumer() throws Exception {
        // Stub 1: metadata.async.produce with jsonBody + jsonPath interpolation
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/kafka/produce-trigger"))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"orderId\":\"ORD-PROD-42\",\"accepted\":true}"))
                .withMetadata(Map.of(
                        "async",
                        Map.of(
                                "produce",
                                List.of(Map.of(
                                        "protocol", "kafka",
                                        "destination", "http.triggered.produce",
                                        "key", "{{jsonPath response.body '$.orderId'}}",
                                        "headers", Map.of("x-trigger-type", "produce"),
                                        "jsonBody",
                                                Map.of(
                                                        "orderId", "{{jsonPath response.body '$.orderId'}}",
                                                        "customer", "{{jsonPath request.body '$.customer'}}",
                                                        "source", "http-produce")))))));

        // Stub 2: call-triggered metadata.async.feed with jsonBody + jsonPath interpolation
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/kafka/feed-trigger"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"batchId\":\"BATCH-77\",\"queued\":true}"))
                .withMetadata(Map.of(
                        "async",
                        Map.of(
                                "feed",
                                Map.of(
                                        "protocol", "kafka",
                                        "destination", "http.triggered.feed",
                                        "trigger", "call-triggered",
                                        "records",
                                                List.of(Map.of(
                                                        "key", "{{jsonPath response.body '$.batchId'}}",
                                                        "headers", Map.of("x-trigger-type", "feed"),
                                                        "jsonBody",
                                                                Map.of(
                                                                        "batchId",
                                                                                "{{jsonPath response.body '$.batchId'}}",
                                                                        "region",
                                                                                "{{jsonPath request.body '$.region'}}",
                                                                        "source", "http-feed"))))))));

        // Trigger both via real HTTP POST requests
        HttpResponse<String> produceResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(wireMockServer.baseUrl() + "/api/v1/kafka/produce-trigger"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"customer\":\"Astrid\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(produceResp.statusCode()).isEqualTo(202);

        HttpResponse<String> feedResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(wireMockServer.baseUrl() + "/api/v1/kafka/feed-trigger"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"region\":\"eu-north\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(feedResp.statusCode()).isEqualTo(200);

        // Consume both topics via real KafkaConsumer over TCP
        try (KafkaConsumer<String, String> produceConsumer =
                new KafkaConsumer<>(consumerProps(kafkaSinkPort, "http-produce-group"))) {
            produceConsumer.subscribe(Collections.singletonList("http.triggered.produce"));
            List<ConsumerRecord<String, String>> records = pollUntilCount(produceConsumer, 1, Duration.ofSeconds(10));
            assertThat(records).hasSize(1);
            ConsumerRecord<String, String> r = records.get(0);
            assertThat(r.key()).isEqualTo("ORD-PROD-42");
            JsonNode json = MAPPER.readTree(r.value());
            assertThat(json.get("orderId").asText()).isEqualTo("ORD-PROD-42");
            assertThat(json.get("customer").asText()).isEqualTo("Astrid");
            assertThat(json.get("source").asText()).isEqualTo("http-produce");
            assertThat(new String(r.headers().lastHeader("x-trigger-type").value(), StandardCharsets.UTF_8))
                    .isEqualTo("produce");
        }

        try (KafkaConsumer<String, String> feedConsumer =
                new KafkaConsumer<>(consumerProps(kafkaSinkPort, "http-feed-group"))) {
            feedConsumer.subscribe(Collections.singletonList("http.triggered.feed"));
            List<ConsumerRecord<String, String>> records = pollUntilCount(feedConsumer, 1, Duration.ofSeconds(10));
            assertThat(records).hasSize(1);
            ConsumerRecord<String, String> r = records.get(0);
            assertThat(r.key()).isEqualTo("BATCH-77");
            JsonNode json = MAPPER.readTree(r.value());
            assertThat(json.get("batchId").asText()).isEqualTo("BATCH-77");
            assertThat(json.get("region").asText()).isEqualTo("eu-north");
            assertThat(json.get("source").asText()).isEqualTo("http-feed");
            assertThat(new String(r.headers().lastHeader("x-trigger-type").value(), StandardCharsets.UTF_8))
                    .isEqualTo("feed");
        }
    }

    private int startKafkaSink() throws Exception {
        HttpResponse<String> resp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(wireMockServer.baseUrl() + "/__admin/async/sinks/kafka"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"action\":\"start\",\"port\":0}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(resp.body());
        assertThat(body.get("kafkaSinkRunning").asBoolean()).isTrue();
        return body.get("kafkaSinkPort").asInt();
    }

    private static Properties producerProps(int port) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:" + port);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "kafka-wire-e2e-producer");
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "5000");
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        return props;
    }

    private static Properties consumerProps(int port, String groupId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:" + port);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "6000");
        props.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "2000");
        return props;
    }

    private static List<ConsumerRecord<String, String>> pollUntilCount(
            KafkaConsumer<String, String> consumer, int expectedCount, Duration timeout) {
        List<ConsumerRecord<String, String>> collected = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (collected.size() < expectedCount && System.nanoTime() < deadline) {
            ConsumerRecords<String, String> batch = consumer.poll(Duration.ofMillis(200));
            for (ConsumerRecord<String, String> r : batch) {
                collected.add(r);
            }
        }
        return collected;
    }
}
