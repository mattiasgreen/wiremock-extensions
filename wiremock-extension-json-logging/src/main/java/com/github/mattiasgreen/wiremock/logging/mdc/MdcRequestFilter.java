package com.github.mattiasgreen.wiremock.logging.mdc;

import com.github.tomakehurst.wiremock.extension.requestfilter.RequestFilterAction;
import com.github.tomakehurst.wiremock.extension.requestfilter.RequestFilterV2;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import org.slf4j.MDC;

import java.util.Objects;

/**
 * Intercepts incoming requests, extracts corporate headers,
 * normalizes keys into snake_case, and stores them in SLF4J MDC.
 */
public class MdcRequestFilter implements RequestFilterV2 {

    public static final String FILTER_NAME = "mdc-request-filter";

    private final HeaderNormalizer headerNormalizer;

    public MdcRequestFilter() {
        this(new HeaderNormalizer());
    }

    public MdcRequestFilter(HeaderNormalizer headerNormalizer) {
        this.headerNormalizer = Objects.requireNonNull(headerNormalizer, "headerNormalizer must not be null");
    }

    @Override
    public String getName() {
        return FILTER_NAME;
    }

    @Override
    public boolean applyToStubs() {
        return true;
    }

    @Override
    public boolean applyToAdmin() {
        return true;
    }

    @Override
    public RequestFilterAction filter(Request request, ServeEvent serveEvent) {
        if (request != null && request.getHeaders() != null) {
            for (HttpHeader header : request.getHeaders().all()) {
                if (headerNormalizer.shouldExtract(header.key())) {
                    String normalizedKey = headerNormalizer.normalize(header.key());
                    String value = header.firstValue();
                    if (value != null) {
                        MDC.put(normalizedKey, value);
                    }
                }
            }
        }
        return RequestFilterAction.continueWith(request);
    }
}
