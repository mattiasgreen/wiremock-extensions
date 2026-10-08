package com.github.mattiasgreen.wiremock.logging.mdc;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Normalizes corporate HTTP headers into snake_case MDC keys and determines
 * which headers should be extracted for logging and tracing tags.
 */
public class HeaderNormalizer {

    private static final Set<String> DEFAULT_HEADERS = Set.of(
            "x-correlation-id", "x-request-id", "x-tenant-id", "x-user-id", "x-client-id", "traceparent", "tracestate");

    private final Set<String> configuredHeaders;

    private static final java.util.regex.Pattern NON_ALPHANUMERIC = java.util.regex.Pattern.compile("(?i)[^a-z0-9]+");
    private static final java.util.regex.Pattern CAMEL_CASE = java.util.regex.Pattern.compile("([a-z])([A-Z])");
    private static final java.util.regex.Pattern SURROUNDING_UNDERSCORES = java.util.regex.Pattern.compile("^_+|_+$");

    public HeaderNormalizer() {
        this(resolveConfiguredHeaders());
    }

    public HeaderNormalizer(Set<String> headers) {
        this.configuredHeaders =
                headers.stream().map(h -> h.toLowerCase(Locale.ROOT).trim()).collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> resolveConfiguredHeaders() {
        String sysProp = System.getProperty("wiremock.mdc.headers");
        if (sysProp != null && !sysProp.isBlank()) {
            return parseHeaders(sysProp);
        }
        String envVar = System.getenv("WIREMOCK_MDC_HEADERS");
        if (envVar != null && !envVar.isBlank()) {
            return parseHeaders(envVar);
        }
        return DEFAULT_HEADERS;
    }

    private static Set<String> parseHeaders(String raw) {
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    public boolean shouldExtract(String headerName) {
        if (headerName == null || headerName.isBlank()) {
            return false;
        }
        String lower = headerName.toLowerCase(Locale.ROOT).trim();
        return configuredHeaders.contains(lower) || lower.startsWith("x-corp-");
    }

    public String normalize(String headerName) {
        if (headerName == null) {
            return "";
        }
        String trimmed = headerName.trim();
        String snake = NON_ALPHANUMERIC.matcher(trimmed).replaceAll("_");
        snake = CAMEL_CASE.matcher(snake).replaceAll("$1_$2");
        return SURROUNDING_UNDERSCORES.matcher(snake).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
