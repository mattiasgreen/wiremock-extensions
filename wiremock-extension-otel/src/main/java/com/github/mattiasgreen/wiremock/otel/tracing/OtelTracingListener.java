package com.github.mattiasgreen.wiremock.otel.tracing;

import com.github.mattiasgreen.wiremock.otel.TrafficClassification;
import com.github.mattiasgreen.wiremock.otel.WireMockTelemetryEngine;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ServeEventListener;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.util.Objects;

/**
 * ServeEventListener that manages the OpenTelemetry SERVER span lifecycle for every served request
 * using the configured {@link WireMockTelemetryEngine}.
 */
public class OtelTracingListener implements ServeEventListener {

    public static final String LISTENER_NAME = "otel-tracing-listener";

    private final WireMockTelemetryEngine telemetryEngine;

    public OtelTracingListener() {
        this(WireMockTelemetryEngine.getInstance());
    }

    public OtelTracingListener(WireMockTelemetryEngine telemetryEngine) {
        this.telemetryEngine = Objects.requireNonNull(telemetryEngine, "telemetryEngine must not be null");
    }

    public OtelTracingListener(OtelTracingRegistry tracingRegistry) {
        this(Objects.requireNonNull(tracingRegistry, "tracingRegistry must not be null")
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
        telemetryEngine.recordSpan(tc, serveEvent);
    }
}
