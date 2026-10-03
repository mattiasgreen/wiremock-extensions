package com.github.mattiasgreen.wiremock.otel;

import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ServeEventListener;
import com.github.tomakehurst.wiremock.http.LoggedResponse;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.util.Objects;

public class OtelMetricsListener implements ServeEventListener {

    public static final String LISTENER_NAME = "otel-metrics-listener";

    private final OtelMetricsRegistry metricsRegistry;

    public OtelMetricsListener() {
        this(OtelMetricsRegistry.getInstance());
    }

    public OtelMetricsListener(OtelMetricsRegistry metricsRegistry) {
        this.metricsRegistry = Objects.requireNonNull(metricsRegistry, "metricsRegistry must not be null");
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

        String method = "UNKNOWN";
        Request request = serveEvent.getRequest();
        if (request != null && request.getMethod() != null) {
            method = request.getMethod().getName();
        }

        int statusCode = 0;
        LoggedResponse response = serveEvent.getResponse();
        if (response != null) {
            statusCode = response.getStatus();
        }

        boolean matched = serveEvent.getWasMatched();
        String stubName = "none";
        StubMapping stubMapping = serveEvent.getStubMapping();
        if (stubMapping != null && stubMapping.getName() != null) {
            stubName = stubMapping.getName();
        }

        double durationMs = 0.0;
        if (serveEvent.getTiming() != null) {
            durationMs = serveEvent.getTiming().getTotalTime();
        }

        metricsRegistry.recordRequest(method, statusCode, matched, stubName, durationMs);
    }
}
