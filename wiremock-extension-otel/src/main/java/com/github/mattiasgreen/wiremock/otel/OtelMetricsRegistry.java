package com.github.mattiasgreen.wiremock.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.exporter.prometheus.PrometheusMetricReader;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class OtelMetricsRegistry {

    private static final OtelMetricsRegistry INSTANCE = new OtelMetricsRegistry();

    private final PrometheusMetricReader prometheusReader;
    private final Meter meter;

    private final LongCounter requestsTotal;
    private final DoubleHistogram requestDuration;
    private final LongCounter unmatchedTotal;

    public static OtelMetricsRegistry getInstance() {
        return INSTANCE;
    }

    public OtelMetricsRegistry() {
        this.prometheusReader = new PrometheusMetricReader(false, name -> true);
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(prometheusReader)
                .build();

        OpenTelemetrySdk openTelemetry =
                OpenTelemetrySdk.builder().setMeterProvider(meterProvider).build();

        this.meter = openTelemetry.getMeter("wiremock-otel");

        this.requestsTotal = meter.counterBuilder("wiremock_requests_total")
                .setDescription("Total HTTP requests handled by WireMock")
                .build();

        this.requestDuration = meter.histogramBuilder("wiremock_request_duration_ms")
                .setDescription("WireMock request execution duration in milliseconds")
                .setUnit("ms")
                .build();

        this.unmatchedTotal = meter.counterBuilder("wiremock_requests_unmatched_total")
                .setDescription("Total requests that did not match any stub")
                .build();
    }

    public void recordRequest(String method, int statusCode, boolean matched, String stubName, double durationMs) {
        Attributes attributes = Attributes.of(
                AttributeKey.stringKey("http_request_method"),
                method != null ? method : "UNKNOWN",
                AttributeKey.stringKey("http_response_status_code"),
                String.valueOf(statusCode),
                AttributeKey.stringKey("wiremock_matched"),
                String.valueOf(matched),
                AttributeKey.stringKey("wiremock_stub_name"),
                stubName != null ? stubName : "none");

        requestsTotal.add(1, attributes);
        requestDuration.record(durationMs, attributes);

        if (!matched) {
            unmatchedTotal.add(
                    1,
                    Attributes.of(AttributeKey.stringKey("http_request_method"), method != null ? method : "UNKNOWN"));
        }
    }

    public String scrapePrometheusText() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrometheusTextFormatWriter writer = new PrometheusTextFormatWriter(false);
        try {
            writer.write(baos, prometheusReader.collect());
            return baos.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to format Prometheus metrics", e);
        }
    }

    public PrometheusMetricReader getPrometheusReader() {
        return prometheusReader;
    }
}
