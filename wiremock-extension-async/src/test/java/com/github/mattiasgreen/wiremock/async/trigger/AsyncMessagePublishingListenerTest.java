package com.github.mattiasgreen.wiremock.async.trigger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AsyncMessagePublishingListenerTest {

    private WireMockServer wireMockServer;
    private HttpClient httpClient;
    private BlockingQueue<AsyncMessage> publishedQueue;
    private AsyncMessagePublishingListener listener;

    @BeforeEach
    void setUp() {
        publishedQueue = new LinkedBlockingQueue<>();
        listener = new AsyncMessagePublishingListener((destination, msg) -> publishedQueue.offer(msg));

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
    @DisplayName("Should trigger asynchronous message publication with interpolated variables on HTTP stub match")
    void shouldPublishMessageOnHttpStubCompletion() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/api/v1/orders/submit"))
                .withHeader("Content-Type", equalTo("application/json"))
                .willReturn(aResponse()
                        .withStatus(202)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"orderId\":\"ORD-9912\",\"status\":\"ACCEPTED\"}"))
                .withMetadata(Map.of(
                        "async",
                        Map.of(
                                "produce",
                                List.of(Map.of(
                                        "destination", "orders.events",
                                        "protocol", "kafka",
                                        "key", "{{jsonPath response.body '$.orderId'}}",
                                        "payload",
                                                "{\"orderId\":\"{{jsonPath response.body '$.orderId'}}\",\"customer\":\"{{jsonPath request.body '$.customer'}}\",\"status\":\"PROCESSING\"}",
                                        "delayMs", 10,
                                        "headers", Map.of("x-event-type", "OrderSubmitted")))))));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(wireMockServer.baseUrl() + "/api/v1/orders/submit"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"customer\":\"Alice\",\"amount\":120.00}"))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).contains("ORD-9912");

        AsyncMessage msg = publishedQueue.poll(5, TimeUnit.SECONDS);
        assertThat(msg).isNotNull();
        assertThat(msg.destination()).isEqualTo("orders.events");
        assertThat(msg.protocol()).isEqualTo("kafka");
        assertThat(msg.key()).isEqualTo("ORD-9912");
        assertThat(msg.payload())
                .isEqualTo("{\"orderId\":\"ORD-9912\",\"customer\":\"Alice\",\"status\":\"PROCESSING\"}");
        assertThat(msg.headers()).containsEntry("x-event-type", "OrderSubmitted");
    }
}
