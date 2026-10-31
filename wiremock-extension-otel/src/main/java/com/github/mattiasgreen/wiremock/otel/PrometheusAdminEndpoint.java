package com.github.mattiasgreen.wiremock.otel;

import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import java.util.Objects;

/**
 * AdminApiExtension contributing standard Prometheus scrape routes (/metrics/prometheus and /metrics).
 * Delegates exposition directly to {@link WireMockTelemetryEngine}.
 */
public class PrometheusAdminEndpoint implements AdminApiExtension {

    public static final String EXTENSION_NAME = "prometheus-admin-endpoint";

    private final WireMockTelemetryEngine telemetryEngine;

    public PrometheusAdminEndpoint() {
        this(WireMockTelemetryEngine.getInstance());
    }

    public PrometheusAdminEndpoint(WireMockTelemetryEngine telemetryEngine) {
        this.telemetryEngine = Objects.requireNonNull(telemetryEngine, "telemetryEngine must not be null");
    }

    public PrometheusAdminEndpoint(OtelMetricsRegistry metricsRegistry) {
        this(Objects.requireNonNull(metricsRegistry, "metricsRegistry must not be null")
                .getTelemetryEngine());
    }

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public void contributeAdminApiRoutes(Router router) {
        router.add(RequestMethod.GET, "/metrics/prometheus", (admin, serveEvent, pathParams) -> {
            if (admin != null) {
                telemetryEngine.setAdmin(admin);
            }
            return ResponseDefinitionBuilder.responseDefinition()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                    .withBody(telemetryEngine.scrapePrometheusText())
                    .build();
        });

        router.add(RequestMethod.GET, "/metrics", (admin, serveEvent, pathParams) -> {
            if (admin != null) {
                telemetryEngine.setAdmin(admin);
            }
            return ResponseDefinitionBuilder.responseDefinition()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                    .withBody(telemetryEngine.scrapePrometheusText())
                    .build();
        });
    }
}
