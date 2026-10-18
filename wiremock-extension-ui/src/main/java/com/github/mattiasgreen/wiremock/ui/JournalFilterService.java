package com.github.mattiasgreen.wiremock.ui;

import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Server-side filtering and projection service for WireMock request journal events.
 */
public class JournalFilterService {

    public record JournalFilterResult(List<ServeEvent> requests, int total, int filtered) {}

    /**
     * Filters a collection of serve events according to query string, verdict, unmatched flag, and limit.
     *
     * @param events all recorded serve events
     * @param query raw search query supporting boolean AND / OR
     * @param verdict verdict filter ({@code "all"}, {@code "proxied"}, {@code "stateful"}, {@code "stub"}, {@code "unmatched"})
     * @param unmatchedOnly if true, restricts exclusively to unmatched requests (404s)
     * @param status optional HTTP status code filter
     * @param limit maximum number of results to return
     * @return filtered result envelope
     */
    public JournalFilterResult filter(
            List<ServeEvent> events, String query, String verdict, boolean unmatchedOnly, Integer status, int limit) {
        if (events == null || events.isEmpty()) {
            return new JournalFilterResult(List.of(), 0, 0);
        }

        int totalCount = events.size();
        String targetVerdict =
                (verdict != null && !verdict.isBlank()) ? verdict.trim().toLowerCase(Locale.ROOT) : "all";

        List<ServeEvent> matched = new ArrayList<>();
        for (ServeEvent event : events) {
            String eventVerdict = classifyVerdict(event);

            // Unmatched filter
            if (unmatchedOnly && !"unmatched".equals(eventVerdict)) {
                continue;
            }

            // Specific verdict filter
            if (!"all".equals(targetVerdict) && !targetVerdict.equals(eventVerdict)) {
                continue;
            }

            // HTTP status filter
            if (status != null) {
                int eventStatus = event.getResponse() != null
                        ? event.getResponse().getStatus()
                        : (event.getResponseDefinition() != null
                                ? event.getResponseDefinition().getStatus()
                                : (event.getWasMatched() ? 200 : 404));
                if (eventStatus != status) {
                    continue;
                }
            }

            // Boolean text query filter
            if (!JournalQueryMatcher.matches(event, query, eventVerdict)) {
                continue;
            }

            matched.add(event);
        }

        int filteredCount = matched.size();
        int safeLimit = Math.max(1, Math.min(1000, limit > 0 ? limit : 100));
        List<ServeEvent> paged = matched.size() > safeLimit ? matched.subList(0, safeLimit) : matched;

        return new JournalFilterResult(paged, totalCount, filteredCount);
    }

    /**
     * Classifies a {@link ServeEvent} into one of the 4 core verdicts:
     * {@code "stateful"}, {@code "unmatched"}, {@code "proxied"}, or {@code "stub"}.
     */
    public static String classifyVerdict(ServeEvent event) {
        if (event == null) return "unmatched";

        HttpHeaders resHeaders =
                event.getResponse() != null ? event.getResponse().getHeaders() : null;
        if (resHeaders == null && event.getResponseDefinition() != null) {
            resHeaders = event.getResponseDefinition().getHeaders();
        }
        if (resHeaders != null) {
            HttpHeader stateful = resHeaders.getHeader("X-WireMock-Stateful");
            if (stateful != null && stateful.isPresent() && "true".equalsIgnoreCase(stateful.firstValue())) {
                return "stateful";
            }
        }

        if (!event.getWasMatched()) {
            return "unmatched";
        }

        boolean isProxy = (event.getResponseDefinition() != null
                        && event.getResponseDefinition().isProxyResponse())
                || (event.getResponseDefinition() != null
                        && event.getResponseDefinition().getProxyBaseUrl() != null)
                || (event.getStubMapping() != null
                        && event.getStubMapping().getResponse() != null
                        && event.getStubMapping().getResponse().isProxyResponse());

        if (isProxy) {
            return "proxied";
        }

        return "stub";
    }
}
