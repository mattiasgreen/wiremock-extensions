package com.github.mattiasgreen.wiremock.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
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

    public static final AttributeKey<String> ATTR_PROTOCOL = AttributeKey.stringKey("protocol");
    public static final AttributeKey<String> ATTR_TRAFFIC_TYPE = AttributeKey.stringKey("traffic_type");
    public static final AttributeKey<String> ATTR_WIREMOCK_MATCHED = AttributeKey.stringKey("wiremock_matched");
    public static final AttributeKey<String> ATTR_WIREMOCK_STUB_NAME = AttributeKey.stringKey("wiremock_stub_name");
    public static final AttributeKey<String> ATTR_HTTP_METHOD = AttributeKey.stringKey("http_request_method");
    public static final AttributeKey<String> ATTR_HTTP_STATUS = AttributeKey.stringKey("http_response_status_code");
    public static final AttributeKey<String> ATTR_TARGET_UPSTREAM = AttributeKey.stringKey("target_upstream");
    public static final AttributeKey<String> ATTR_RPC_SERVICE = AttributeKey.stringKey("rpc_service");
    public static final AttributeKey<String> ATTR_CASUAL_ERROR_STATE = AttributeKey.stringKey("casual_error_state");

    private final PrometheusMetricReader prometheusReader;
    private final Meter meter;

    private final LongCounter requestsTotal;
    private final DoubleHistogram requestDuration;
    private final DoubleHistogram upstreamDuration;
    private final LongCounter unmatchedTotal;

    public static OtelMetricsRegistry getInstance() {
        return INSTANCE;
    }

    public static OtelMetricsRegistry create() {
        return new OtelMetricsRegistry();
    }

    private OtelMetricsRegistry() {
        this.prometheusReader = new PrometheusMetricReader(false, name -> true);
        SdkMeterProvider meterProvider = SdkMeterProvider.builder()
                .registerMetricReader(prometheusReader)
                .build();

        OpenTelemetrySdk openTelemetry =
                OpenTelemetrySdk.builder().setMeterProvider(meterProvider).build();

        this.meter = openTelemetry.getMeter("wiremock-otel");

        this.requestsTotal = meter.counterBuilder("wiremock_requests_total")
                .setDescription("Total requests handled by WireMock")
                .build();

        this.requestDuration = meter.histogramBuilder("wiremock_request_duration_ms")
                .setDescription("WireMock request execution duration in milliseconds")
                .setUnit("ms")
                .build();

        this.upstreamDuration = meter.histogramBuilder("wiremock_upstream_duration_ms")
                .setDescription("Upstream target execution duration in milliseconds for proxied requests")
                .setUnit("ms")
                .build();

        this.unmatchedTotal = meter.counterBuilder("wiremock_requests_unmatched_total")
                .setDescription("Total requests that did not match any stub")
                .build();
    }

    public void recordRequest(String method, int statusCode, boolean matched, String stubName, double durationMs) {
        recordRequest(
                "http",
                matched ? "stubbed" : "unmatched",
                method,
                statusCode,
                matched,
                stubName,
                "none",
                null,
                null,
                durationMs,
                0.0);
    }

    public void recordRequest(
            String protocol,
            String trafficType,
            String method,
            int statusCode,
            boolean matched,
            String stubName,
            String targetUpstream,
            String rpcService,
            String rpcStatus,
            double durationMs,
            double upstreamDurationMs) {

        String safeProtocol = protocol != null && !protocol.isBlank() ? protocol : "http";
        String safeTrafficType = trafficType != null && !trafficType.isBlank() ? trafficType : "stubbed";

        AttributesBuilder builder = Attributes.builder()
                .put(ATTR_PROTOCOL, safeProtocol)
                .put(ATTR_TRAFFIC_TYPE, safeTrafficType)
                .put(ATTR_WIREMOCK_MATCHED, String.valueOf(matched))
                .put(ATTR_WIREMOCK_STUB_NAME, stubName != null ? stubName : "none");

        if ("casual".equalsIgnoreCase(safeProtocol)) {
            if (rpcService != null && !rpcService.isBlank()) {
                builder.put(ATTR_RPC_SERVICE, rpcService);
            }
            if (rpcStatus != null && !rpcStatus.isBlank()) {
                builder.put(ATTR_CASUAL_ERROR_STATE, rpcStatus);
            }
        } else {
            builder.put(ATTR_HTTP_METHOD, method != null ? method : "UNKNOWN");
            builder.put(ATTR_HTTP_STATUS, String.valueOf(statusCode));
        }

        if ("proxied".equalsIgnoreCase(safeTrafficType)
                && targetUpstream != null
                && !targetUpstream.isBlank()
                && !"none".equals(targetUpstream)) {
            builder.put(ATTR_TARGET_UPSTREAM, targetUpstream);
        }

        Attributes attributes = builder.build();

        requestsTotal.add(1, attributes);
        requestDuration.record(durationMs, attributes);

        if ("proxied".equalsIgnoreCase(safeTrafficType) && upstreamDurationMs > 0) {
            Attributes upstreamAttrs = Attributes.of(
                    ATTR_PROTOCOL,
                    safeProtocol,
                    ATTR_TARGET_UPSTREAM,
                    targetUpstream != null ? targetUpstream : "none",
                    ATTR_HTTP_STATUS,
                    String.valueOf(statusCode));
            upstreamDuration.record(upstreamDurationMs, upstreamAttrs);
        }

        if (!matched) {
            AttributesBuilder unmatchedAttrs = Attributes.builder().put(ATTR_PROTOCOL, safeProtocol);
            if ("casual".equalsIgnoreCase(safeProtocol) && rpcService != null) {
                unmatchedAttrs.put(ATTR_RPC_SERVICE, rpcService);
            } else {
                unmatchedAttrs.put(ATTR_HTTP_METHOD, method != null ? method : "UNKNOWN");
            }
            unmatchedTotal.add(1, unmatchedAttrs.build());
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
