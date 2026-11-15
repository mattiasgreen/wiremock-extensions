package com.github.mattiasgreen.wiremock.ui;

import com.github.mattiasgreen.wiremock.support.BooleanQueryEvaluator;
import com.github.mattiasgreen.wiremock.support.IndexedBodyLookup;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Evaluates search queries against WireMock {@link ServeEvent} instances with boolean AND / OR support.
 * <p>
 * Supported query grammar (matching client-side {@code filter.js}):
 * <ul>
 *   <li>Top-level {@code OR} clauses: {@code A OR B} evaluates to {@code A || B}</li>
 *   <li>{@code AND} clauses within an OR branch: {@code A AND B} evaluates to {@code A && B}</li>
 *   <li>Space-separated tokens within an AND clause default to implicit AND: {@code foo bar}</li>
 *   <li>Case-insensitive attribute checks across method, URL, status, stub name, correlation ID,
 *       Casual protocol metadata, verdict keys, headers, and deep request/response payloads.</li>
 * </ul>
 */
public final class JournalQueryMatcher {

    private JournalQueryMatcher() {}

    /**
     * Evaluates whether a {@link ServeEvent} satisfies the specified boolean query.
     *
     * @param event serve event to inspect
     * @param query raw filter string with optional AND / OR operators
     * @param verdict resolution verdict key (e.g. {@code "proxied"}, {@code "stub"}, {@code "unmatched"})
     * @return {@code true} if matching, or if query is null/blank
     */
    public static boolean matches(ServeEvent event, String query, String verdict) {
        String sourceIp = event != null ? ClientSourceResolver.extractSourceIp(event.getRequest()) : "";
        return matches(event, query, verdict, sourceIp, "", null);
    }

    /**
     * Evaluates whether a {@link ServeEvent} satisfies the specified boolean query, including
     * resolved caller source IP and hostname.
     *
     * @param event serve event to inspect
     * @param query raw filter string with optional AND / OR operators
     * @param verdict resolution verdict key
     * @param sourceIp extracted caller source IP
     * @param sourceHost resolved caller hostname (may be empty)
     * @return {@code true} if matching, or if query is null/blank
     */
    public static boolean matches(ServeEvent event, String query, String verdict, String sourceIp, String sourceHost) {
        return matches(event, query, verdict, sourceIp, sourceHost, null);
    }

    /**
     * Evaluates whether a {@link ServeEvent} satisfies the specified boolean query, using an optional
     * {@link IndexedBodyLookup} to avoid decompressing or scanning message bodies when indexed.
     *
     * @param event serve event to inspect
     * @param query raw filter string with optional AND / OR operators
     * @param verdict resolution verdict key
     * @param sourceIp extracted caller source IP
     * @param sourceHost resolved caller hostname (may be empty)
     * @param indexedBodyLookup optional indexed token lookup backed by compressed journal stores
     * @return {@code true} if matching, or if query is null/blank
     */
    public static boolean matches(
            ServeEvent event,
            String query,
            String verdict,
            String sourceIp,
            String sourceHost,
            IndexedBodyLookup indexedBodyLookup) {
        if (query == null || query.isBlank()) {
            return true;
        }

        // Lazy deep text representation for multi-token queries
        String[] deepCache = new String[1];

        return evaluateExpression(
                query,
                token -> matchesToken(event, token, verdict, sourceIp, sourceHost, indexedBodyLookup, deepCache));
    }

    /**
     * Core boolean expression evaluator.
     *
     * @param query raw boolean query string
     * @param matcherFn token predicate receiving lowercased query tokens
     * @return {@code true} when satisfied
     */
    public static boolean evaluateExpression(String query, Predicate<String> matcherFn) {
        return BooleanQueryEvaluator.evaluate(query, matcherFn);
    }

    private static boolean matchesToken(
            ServeEvent event,
            String token,
            String verdict,
            String sourceIp,
            String sourceHost,
            IndexedBodyLookup indexedBodyLookup,
            String[] deepCache) {
        if (event == null || event.getRequest() == null) {
            return false;
        }

        String safeIp = sourceIp != null ? sourceIp.toLowerCase(Locale.ROOT) : "";
        String safeHost = sourceHost != null ? sourceHost.toLowerCase(Locale.ROOT) : "";

        // 0. Key-prefixed source filters: src:, src=, source:, source=, ip:, ip=, host:, host=
        String prefixedVal = extractPrefixedValue(token, "src:", "src=", "source:", "source=");
        if (prefixedVal != null) {
            return !prefixedVal.isEmpty() && (safeIp.contains(prefixedVal) || safeHost.contains(prefixedVal));
        }
        String ipVal = extractPrefixedValue(token, "ip:", "ip=");
        if (ipVal != null) {
            return !ipVal.isEmpty() && safeIp.contains(ipVal);
        }
        String hostVal = extractPrefixedValue(token, "host:", "host=");
        if (hostVal != null) {
            return !hostVal.isEmpty() && safeHost.contains(hostVal);
        }

        // 1. Fast shallow check: source IP/host, method, url, status
        if (!safeIp.isEmpty() && safeIp.contains(token)) return true;
        if (!safeHost.isEmpty() && safeHost.contains(token)) return true;

        String method = event.getRequest().getMethod() != null
                ? event.getRequest().getMethod().getName().toLowerCase(Locale.ROOT)
                : "";
        if (method.contains(token)) return true;

        String url = event.getRequest().getUrl() != null
                ? event.getRequest().getUrl().toLowerCase(Locale.ROOT)
                : "";
        if (url.contains(token)) return true;

        int status = event.getResponse() != null
                ? event.getResponse().getStatus()
                : (event.getResponseDefinition() != null
                        ? event.getResponseDefinition().getStatus()
                        : (event.getWasMatched() ? 200 : 404));
        if (String.valueOf(status).contains(token)) return true;

        // 2. Verdict check
        if (verdict != null && verdict.toLowerCase(Locale.ROOT).contains(token)) {
            return true;
        }

        // 3. Stub name
        if (event.getStubMapping() != null && event.getStubMapping().getName() != null) {
            if (event.getStubMapping().getName().toLowerCase(Locale.ROOT).contains(token)) {
                return true;
            }
        }

        // 4. Casual protocol URL marker
        if (url.startsWith("/__casual/services/") && "casual".contains(token)) {
            return true;
        }

        // 5. Shallow metadata: request and response headers (uncompressed even in CompressedServeEvent)
        if (headersContainToken(event.getRequest().getHeaders(), token)) {
            return true;
        }
        HttpHeaders resHeaders =
                event.getResponse() != null ? event.getResponse().getHeaders() : null;
        if (headersContainToken(resHeaders, token)) {
            return true;
        }

        // 6. Indexed body lookup short-circuit (avoids decompressing stored bodies)
        if (indexedBodyLookup != null && event.getId() != null) {
            Optional<Boolean> indexed = indexedBodyLookup.containsEventToken(event.getId(), token);
            if (indexed.isPresent()) {
                return indexed.get();
            }
        }

        // 7. Deep body scan fallback
        if (deepCache[0] == null) {
            deepCache[0] = buildDeepBodyText(event);
        }
        return deepCache[0].contains(token);
    }

    private static boolean headersContainToken(HttpHeaders headers, String token) {
        if (headers == null) {
            return false;
        }
        for (HttpHeader h : headers.all()) {
            String key = h.key() != null ? h.key().toLowerCase(Locale.ROOT) : "";
            if (key.contains(token)) {
                return true;
            }
            String joinedValues = h.values() != null ? h.values().toString().toLowerCase(Locale.ROOT) : "";
            if (!key.isEmpty() && (key + ":" + joinedValues).contains(token)) {
                return true;
            }
            if (h.values() != null) {
                for (String value : h.values()) {
                    if (value != null) {
                        String lowerVal = value.toLowerCase(Locale.ROOT);
                        if (lowerVal.contains(token) || (!key.isEmpty() && (key + ":" + lowerVal).contains(token))) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static String buildDeepBodyText(ServeEvent event) {
        StringBuilder sb = new StringBuilder();
        if (event.getRequest() != null) {
            String reqBody = event.getRequest().getBodyAsString();
            if (reqBody != null) {
                sb.append(reqBody.toLowerCase(Locale.ROOT)).append(' ');
            }
        }
        if (event.getResponse() != null) {
            String resBody = event.getResponse().getBodyAsString();
            if (resBody != null) {
                sb.append(resBody.toLowerCase(Locale.ROOT)).append(' ');
            }
        }
        return sb.toString();
    }

    private static String extractPrefixedValue(String token, String... prefixes) {
        for (String prefix : prefixes) {
            if (token.startsWith(prefix)) {
                return token.substring(prefix.length()).trim();
            }
        }
        return null;
    }
}
