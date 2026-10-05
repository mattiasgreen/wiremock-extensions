package com.github.mattiasgreen.wiremock.logging.mdc;

import com.github.tomakehurst.wiremock.extension.requestfilter.RequestFilterAction;
import com.github.tomakehurst.wiremock.extension.requestfilter.RequestFilterV2;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.util.Objects;
import org.slf4j.MDC;

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

    private static final java.util.regex.Pattern TRACEPARENT_PATTERN =
            java.util.regex.Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$");

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

            HttpHeader traceparentHeader = request.header("traceparent");
            if (traceparentHeader != null && traceparentHeader.isPresent()) {
                String tp = traceparentHeader.firstValue();
                if (tp != null) {
                    java.util.regex.Matcher m = TRACEPARENT_PATTERN.matcher(tp.trim());
                    if (m.matches()) {
                        MDC.put("trace_id", m.group(1));
                        MDC.put("span_id", m.group(2));
                    }
                }
            }
        }
        return RequestFilterAction.continueWith(request);
    }
}
