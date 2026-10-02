package com.github.mattiasgreen.wiremock.otel;

import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.RequestMethod;

import java.util.Objects;

public class PrometheusAdminEndpoint implements AdminApiExtension {

    public static final String EXTENSION_NAME = "prometheus-admin-endpoint";

    private final OtelMetricsRegistry metricsRegistry;

    public PrometheusAdminEndpoint() {
        this(OtelMetricsRegistry.getInstance());
    }

    public PrometheusAdminEndpoint(OtelMetricsRegistry metricsRegistry) {
        this.metricsRegistry = Objects.requireNonNull(metricsRegistry, "metricsRegistry must not be null");
    }

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public void contributeAdminApiRoutes(Router router) {
        router.add(RequestMethod.GET, "/metrics/prometheus", (admin, serveEvent, pathParams) -> {
            String metricsText = metricsRegistry.scrapePrometheusText();
            return ResponseDefinitionBuilder.responseDefinition()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                    .withBody(metricsText)
                    .build();
        });

        router.add(RequestMethod.GET, "/metrics", (admin, serveEvent, pathParams) -> {
            String metricsText = metricsRegistry.scrapePrometheusText();
            return ResponseDefinitionBuilder.responseDefinition()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                    .withBody(metricsText)
                    .build();
        });
    }
}
