package com.github.mattiasgreen.wiremock.otel.tracing;

import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.Request;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * TextMapGetter implementation for extracting W3C trace context headers from WireMock requests.
 */
public enum WireMockRequestTextMapGetter implements TextMapGetter<Request> {
    INSTANCE;

    @Override
    public Iterable<String> keys(Request carrier) {
        if (carrier == null || carrier.getHeaders() == null) {
            return Collections.emptyList();
        }
        List<String> keys = new ArrayList<>();
        for (HttpHeader header : carrier.getHeaders().all()) {
            keys.add(header.key());
        }
        return keys;
    }

    @Override
    public String get(Request carrier, String key) {
        if (carrier == null || key == null) {
            return null;
        }
        HttpHeader header = carrier.header(key);
        return (header != null && header.isPresent()) ? header.firstValue() : null;
    }
}
