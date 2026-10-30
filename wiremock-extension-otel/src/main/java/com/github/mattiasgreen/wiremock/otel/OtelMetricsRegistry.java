package com.github.mattiasgreen.wiremock.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.exporter.prometheus.PrometheusMetricReader;
import java.util.Objects;

/**
 * Backward-compatible facade delegating to {@link WireMockTelemetryEngine}.
 * For modern usage, prefer injecting or configuring {@link WireMockTelemetryEngine} directly.
 */
public class OtelMetricsRegistry {

    private static final OtelMetricsRegistry INSTANCE = new OtelMetricsRegistry(WireMockTelemetryEngine.getInstance());

    public static final AttributeKey<String> ATTR_PROTOCOL = WireMockTelemetryEngine.ATTR_PROTOCOL;
    public static final AttributeKey<String> ATTR_TRAFFIC_TYPE = WireMockTelemetryEngine.ATTR_TRAFFIC_TYPE;
    public static final AttributeKey<String> ATTR_WIREMOCK_MATCHED = WireMockTelemetryEngine.ATTR_WIREMOCK_MATCHED;
    public static final AttributeKey<String> ATTR_WIREMOCK_STUB_NAME = WireMockTelemetryEngine.ATTR_WIREMOCK_STUB_NAME;
    public static final AttributeKey<String> ATTR_HTTP_METHOD = WireMockTelemetryEngine.ATTR_HTTP_METHOD;
    public static final AttributeKey<String> ATTR_HTTP_STATUS = WireMockTelemetryEngine.ATTR_HTTP_STATUS;
    public static final AttributeKey<String> ATTR_TARGET_UPSTREAM = WireMockTelemetryEngine.ATTR_TARGET_UPSTREAM;
    public static final AttributeKey<String> ATTR_RPC_SERVICE = WireMockTelemetryEngine.ATTR_RPC_SERVICE;
    public static final AttributeKey<String> ATTR_CASUAL_ERROR_STATE = WireMockTelemetryEngine.ATTR_CASUAL_ERROR_STATE;

    private final WireMockTelemetryEngine telemetryEngine;

    public static OtelMetricsRegistry getInstance() {
        return INSTANCE;
    }

    public static OtelMetricsRegistry create() {
        return new OtelMetricsRegistry(WireMockTelemetryEngine.create());
    }

    public OtelMetricsRegistry() {
        this(WireMockTelemetryEngine.getInstance());
    }

    public OtelMetricsRegistry(WireMockTelemetryEngine telemetryEngine) {
        this.telemetryEngine = Objects.requireNonNull(telemetryEngine, "telemetryEngine must not be null");
    }

    public WireMockTelemetryEngine getTelemetryEngine() {
        return telemetryEngine;
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

        TrafficClassification tc = new TrafficClassification(
                protocol,
                trafficType,
                method,
                statusCode,
                matched,
                null,
                stubName,
                null,
                null,
                targetUpstream,
                "casual".equalsIgnoreCase(protocol) ? "casual" : null,
                rpcService,
                rpcStatus,
                "/",
                null,
                "/",
                null,
                durationMs,
                upstreamDurationMs,
                java.time.Instant.now(),
                java.time.Instant.now());

        telemetryEngine.recordMetrics(tc);
    }

    public String scrapePrometheusText() {
        return telemetryEngine.scrapePrometheusText();
    }

    public PrometheusMetricReader getPrometheusReader() {
        return telemetryEngine.getPrometheusReader();
    }
}
