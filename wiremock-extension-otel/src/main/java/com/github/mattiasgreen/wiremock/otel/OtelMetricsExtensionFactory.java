package com.github.mattiasgreen.wiremock.otel;

import com.github.mattiasgreen.wiremock.otel.tracing.OtelTracingListener;
import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.extension.ExtensionFactory;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import java.util.List;

/**
 * SPI ExtensionFactory creating instance-scoped OpenTelemetry extensions for a WireMockServer instance.
 */
public class OtelMetricsExtensionFactory implements ExtensionFactory {

    @Override
    public List<Extension> create(WireMockServices services) {
        WireMockTelemetryEngine engine = WireMockTelemetryEngine.create(services);
        return List.of(
                new OtelMetricsListener(engine), new PrometheusAdminEndpoint(engine), new OtelTracingListener(engine));
    }
}
