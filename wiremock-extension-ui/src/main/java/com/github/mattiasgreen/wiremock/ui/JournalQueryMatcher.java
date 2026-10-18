package com.github.mattiasgreen.wiremock.ui;

import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Evaluates search queries against WireMock {@link ServeEvent} instances with boolean AND / OR support.
 * <p>
 * Supported query grammar (matching client-side {@code filter.js}):
 * <ul>
 *   <li>Top-level {@code OR} clauses: {@code A OR B} evaluates to {@code A || B}</li>
 *   <li>{@code AND} clauses within an OR branch: {@code A AND B} evaluates to {@code A && B}</li>
 *   <li>Space-separated tokens within an AND clause default to implicit AND: {@code foo bar}</li>
 *   <li>Case-insensitive attribute checks across method, URL, status, stub name, correlation ID,
 *       Casual protocol metadata, verdict keys, and deep request/response payloads.</li>
 * </ul>
 */
public final class JournalQueryMatcher {

    private static final Pattern OR_SPLIT = Pattern.compile("\\bOR\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AND_SPLIT = Pattern.compile("\\bAND\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE_SPLIT = Pattern.compile("\\s+");

    private JournalQueryMatcher() {}

    /**
     * Evaluates whether a {@link ServeEvent} satisfies the specified boolean query.
     *
     * @param event serve event to inspect
     * @param query raw filter string with optional AND / OR operators
     * @param verdict resolution verdict key (e.g. {@code "proxied"}, {@code "stateful"}, {@code "stub"}, {@code "unmatched"})
     * @return {@code true} if matching, or if query is null/blank
     */
    public static boolean matches(ServeEvent event, String query, String verdict) {
        if (query == null || query.isBlank()) {
            return true;
        }

        // Lazy deep text representation for multi-token queries
        String[] deepCache = new String[1];

        return evaluateExpression(query, token -> matchesToken(event, token, verdict, deepCache));
    }

    /**
     * Core boolean expression evaluator.
     */
    public static boolean evaluateExpression(String query, Predicate<String> matcherFn) {
        if (query == null || query.isBlank()) {
            return true;
        }

        String[] orClauses = OR_SPLIT.split(query);
        for (String orClause : orClauses) {
            String trimmedOr = orClause.trim();
            if (trimmedOr.isEmpty()) {
                continue;
            }

            String[] andClauses = AND_SPLIT.split(trimmedOr);
            boolean allAndMatch = true;
            for (String andPart : andClauses) {
                String trimmedAnd = andPart.trim();
                if (trimmedAnd.isEmpty()) {
                    continue;
                }

                String[] tokens = WHITESPACE_SPLIT.split(trimmedAnd);
                for (String token : tokens) {
                    if (!token.isBlank()) {
                        if (!matcherFn.test(token.toLowerCase(Locale.ROOT))) {
                            allAndMatch = false;
                            break;
                        }
                    }
                }
                if (!allAndMatch) {
                    break;
                }
            }

            if (allAndMatch) {
                return true;
            }
        }

        return false;
    }

    private static boolean matchesToken(ServeEvent event, String token, String verdict, String[] deepCache) {
        if (event == null || event.getRequest() == null) {
            return false;
        }

        // 1. Fast shallow check: method, url, status
        String method = event.getRequest().getMethod().getName().toLowerCase(Locale.ROOT);
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

        // 4. Correlation ID & Casual metadata
        HttpHeaders reqHeaders = event.getRequest().getHeaders();
        if (reqHeaders != null) {
            HttpHeader corrHeader = reqHeaders.getHeader("X-Correlation-Id");
            if (corrHeader != null
                    && corrHeader.isPresent()
                    && corrHeader.firstValue().toLowerCase(Locale.ROOT).contains(token)) {
                return true;
            }
            HttpHeader casualHeader = reqHeaders.getHeader("X-Casual-Service");
            if (casualHeader != null
                    && casualHeader.isPresent()
                    && casualHeader.firstValue().toLowerCase(Locale.ROOT).contains(token)) {
                return true;
            }
        }

        // Casual protocol URL and status
        if (url.startsWith("/__casual/services/")) {
            if ("casual".contains(token)) return true;
            HttpHeaders resHeaders =
                    event.getResponse() != null ? event.getResponse().getHeaders() : null;
            if (resHeaders != null) {
                HttpHeader errHeader = resHeaders.getHeader("X-Casual-Error-State");
                if (errHeader != null
                        && errHeader.isPresent()
                        && errHeader.firstValue().toLowerCase(Locale.ROOT).contains(token)) {
                    return true;
                }
            }
        }

        // 5. Stateful entity headers
        HttpHeaders resHeaders =
                event.getResponse() != null ? event.getResponse().getHeaders() : null;
        if (resHeaders != null) {
            HttpHeader entityHeader = resHeaders.getHeader("X-WireMock-Stateful-Entity");
            if (entityHeader != null
                    && entityHeader.isPresent()
                    && entityHeader.firstValue().toLowerCase(Locale.ROOT).contains(token)) {
                return true;
            }
        }

        // 6. Deep search fallback: bodies and full headers
        if (deepCache[0] == null) {
            deepCache[0] = buildDeepSearchableText(event);
        }
        return deepCache[0].contains(token);
    }

    private static String buildDeepSearchableText(ServeEvent event) {
        StringBuilder sb = new StringBuilder();
        if (event.getRequest() != null) {
            if (event.getRequest().getBodyAsString() != null) {
                sb.append(event.getRequest().getBodyAsString().toLowerCase(Locale.ROOT))
                        .append(' ');
            }
            if (event.getRequest().getHeaders() != null) {
                for (HttpHeader h : event.getRequest().getHeaders().all()) {
                    sb.append(h.key().toLowerCase(Locale.ROOT))
                            .append(':')
                            .append(h.values().toString().toLowerCase(Locale.ROOT))
                            .append(' ');
                }
            }
        }
        if (event.getResponse() != null) {
            if (event.getResponse().getBodyAsString() != null) {
                sb.append(event.getResponse().getBodyAsString().toLowerCase(Locale.ROOT))
                        .append(' ');
            }
            if (event.getResponse().getHeaders() != null) {
                for (HttpHeader h : event.getResponse().getHeaders().all()) {
                    sb.append(h.key().toLowerCase(Locale.ROOT))
                            .append(':')
                            .append(h.values().toString().toLowerCase(Locale.ROOT))
                            .append(' ');
                }
            }
        }
        return sb.toString();
    }
}
