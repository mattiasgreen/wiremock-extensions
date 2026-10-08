package com.github.mattiasgreen.wiremock.otel;

import com.github.mattiasgreen.wiremock.otel.tracing.OtelTracingListener;
import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.extension.ExtensionFactory;
import com.github.tomakehurst.wiremock.extension.WireMockServices;
import java.util.List;

public class OtelMetricsExtensionFactory implements ExtensionFactory {

    @Override
    public List<Extension> create(WireMockServices services) {
        return List.of(new OtelMetricsListener(), new PrometheusAdminEndpoint(), new OtelTracingListener());
    }
}
