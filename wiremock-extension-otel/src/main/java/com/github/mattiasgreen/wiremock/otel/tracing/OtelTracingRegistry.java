package com.github.mattiasgreen.wiremock.otel.tracing;

import com.github.mattiasgreen.wiremock.otel.WireMockTelemetryEngine;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.io.Closeable;
import java.util.Objects;

/**
 * Backward-compatible facade delegating to {@link WireMockTelemetryEngine}.
 * For modern usage, prefer injecting or configuring {@link WireMockTelemetryEngine} directly.
 */
public class OtelTracingRegistry implements Closeable {

    public static final String INSTRUMENTATION_NAME = WireMockTelemetryEngine.INSTRUMENTATION_NAME;
    private static final OtelTracingRegistry INSTANCE = new OtelTracingRegistry(WireMockTelemetryEngine.getInstance());

    private final WireMockTelemetryEngine telemetryEngine;

    public static OtelTracingRegistry getInstance() {
        return INSTANCE;
    }

    public static OtelTracingRegistry create() {
        return new OtelTracingRegistry(WireMockTelemetryEngine.create());
    }

    public static OtelTracingRegistry create(boolean enabled, String otlpEndpoint, SpanExporter customExporter) {
        return new OtelTracingRegistry(WireMockTelemetryEngine.create(enabled, otlpEndpoint, customExporter, null));
    }

    public OtelTracingRegistry() {
        this(WireMockTelemetryEngine.getInstance());
    }

    public OtelTracingRegistry(WireMockTelemetryEngine telemetryEngine) {
        this.telemetryEngine = Objects.requireNonNull(telemetryEngine, "telemetryEngine must not be null");
    }

    public WireMockTelemetryEngine getTelemetryEngine() {
        return telemetryEngine;
    }

    public OpenTelemetry getOpenTelemetry() {
        return telemetryEngine.getOpenTelemetry();
    }

    public Tracer getTracer() {
        return telemetryEngine.getTracer();
    }

    public boolean isTracingEnabled() {
        return telemetryEngine.isTracingEnabled();
    }

    @Override
    public void close() {
        telemetryEngine.close();
    }
}
