package com.github.mattiasgreen.wiremock.otel;

import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ServeEventListener;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.util.Objects;

/**
 * ServeEventListener that records OpenTelemetry request counters and duration histograms
 * using the configured {@link WireMockTelemetryEngine}.
 */
public class OtelMetricsListener implements ServeEventListener {

    public static final String LISTENER_NAME = "otel-metrics-listener";

    private final WireMockTelemetryEngine telemetryEngine;

    public OtelMetricsListener() {
        this(WireMockTelemetryEngine.getInstance());
    }

    public OtelMetricsListener(WireMockTelemetryEngine telemetryEngine) {
        this.telemetryEngine = Objects.requireNonNull(telemetryEngine, "telemetryEngine must not be null");
    }

    public OtelMetricsListener(OtelMetricsRegistry metricsRegistry) {
        this(Objects.requireNonNull(metricsRegistry, "metricsRegistry must not be null")
                .getTelemetryEngine());
    }

    @Override
    public String getName() {
        return LISTENER_NAME;
    }

    @Override
    public boolean applyGlobally() {
        return true;
    }

    @Override
    public void afterComplete(ServeEvent serveEvent, Parameters parameters) {
        if (serveEvent == null) {
            return;
        }
        TrafficClassification tc = TrafficClassification.from(serveEvent);
        telemetryEngine.recordMetrics(tc);
    }
}
