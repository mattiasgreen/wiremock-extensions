package com.github.mattiasgreen.wiremock.otel;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mattiasgreen.wiremock.otel.tracing.OtelTracingListener;
import com.github.mattiasgreen.wiremock.otel.tracing.OtelTracingRegistry;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class OtelTracingTest {

    private InMemorySpanExporter spanExporter;
    private OtelTracingRegistry tracingRegistry;
    private WireMockServer server;
    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        spanExporter = InMemorySpanExporter.create();
        tracingRegistry = OtelTracingRegistry.create(true, null, spanExporter);

        server = new WireMockServer(
                WireMockConfiguration.options().dynamicPort().extensions(new OtelTracingListener(tracingRegistry)));
        server.start();

        httpClient = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
        if (tracingRegistry != null) {
            tracingRegistry.close();
        }
        if (spanExporter != null) {
            spanExporter.close();
        }
    }

    @Test
    void testSpanCreatedWithHttpAttributesOnMatchedRequest() throws Exception {
        server.stubFor(get(urlPathEqualTo("/api/v1/orders/123"))
                .withName("Get Order Stub")
                .willReturn(okJson("{\"id\":\"123\",\"status\":\"OPEN\"}")));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/api/v1/orders/123?include=items"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        List<SpanData> finishedSpans = awaitFinishedSpans(1);
        assertThat(finishedSpans).hasSize(1);

        SpanData span = finishedSpans.get(0);
        assertThat(span.getName()).isEqualTo("GET /api/v1/orders/123");
        assertThat(span.getKind()).isEqualTo(SpanKind.SERVER);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.UNSET);

        assertThat(span.getAttributes().get(AttributeKey.stringKey("http.request.method")))
                .isEqualTo("GET");
        assertThat(span.getAttributes().get(AttributeKey.longKey("http.response.status_code")))
                .isEqualTo(200L);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("url.path"))).isEqualTo("/api/v1/orders/123");
        assertThat(span.getAttributes().get(AttributeKey.stringKey("url.query")))
                .isEqualTo("include=items");
        assertThat(span.getAttributes().get(AttributeKey.booleanKey("wiremock.matched")))
                .isTrue();
        assertThat(span.getAttributes().get(AttributeKey.stringKey("wiremock.stub_name")))
                .isEqualTo("Get Order Stub");
    }

    @Test
    void testChildSpanInheritsIncomingW3CTraceParent() throws Exception {
        server.stubFor(post(urlEqualTo("/api/v1/payments")).willReturn(ok("{\"status\":\"SUCCESS\"}")));

        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String parentSpanId = "00f067aa0ba902b7";
        String traceparent = "00-" + traceId + "-" + parentSpanId + "-01";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/api/v1/payments"))
                .header("traceparent", traceparent)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        List<SpanData> finishedSpans = spanExporter.getFinishedSpanItems();
        assertThat(finishedSpans).hasSize(1);

        SpanData span = finishedSpans.get(0);
        assertThat(span.getTraceId()).isEqualTo(traceId);
        assertThat(span.getParentSpanId()).isEqualTo(parentSpanId);
    }

    @Test
    void testUnmatchedRequestMarksSpanAsError() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/api/v1/unknown-endpoint"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(404);

        List<SpanData> finishedSpans = spanExporter.getFinishedSpanItems();
        assertThat(finishedSpans).hasSize(1);

        SpanData span = finishedSpans.get(0);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().get(AttributeKey.booleanKey("wiremock.matched")))
                .isFalse();
        assertThat(span.getAttributes().get(AttributeKey.longKey("http.response.status_code")))
                .isEqualTo(404L);
    }

    @Test
    void testServerErrorMarksSpanAsError() throws Exception {
        server.stubFor(get(urlEqualTo("/api/v1/fail")).willReturn(serverError()));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(server.baseUrl() + "/api/v1/fail"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(500);

        List<SpanData> finishedSpans = awaitFinishedSpans(1);
        assertThat(finishedSpans).hasSize(1);

        SpanData span = finishedSpans.get(0);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().get(AttributeKey.longKey("http.response.status_code")))
                .isEqualTo(500L);
    }

    private List<SpanData> awaitFinishedSpans(int expectedCount) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            if (spans.size() >= expectedCount) {
                return spans;
            }
            Thread.sleep(50);
        }
        return spanExporter.getFinishedSpanItems();
    }
}
