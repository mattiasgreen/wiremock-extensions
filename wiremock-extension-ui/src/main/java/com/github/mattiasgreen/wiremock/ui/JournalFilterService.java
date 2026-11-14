package com.github.mattiasgreen.wiremock.ui;

import com.github.mattiasgreen.wiremock.support.ServeEventTrafficSupport;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Server-side filtering and projection service for WireMock request journal events.
 */
public class JournalFilterService {

    public record JournalFilterResult(List<ServeEvent> requests, int total, int filtered) {}

    public record EnrichedJournalFilterResult(
            List<ServeEvent> requests, Map<String, String> resolvedHosts, int total, int filtered) {}

    private final ClientSourceResolver sourceResolver;

    public JournalFilterService() {
        this(new ClientSourceResolver());
    }

    public JournalFilterService(ClientSourceResolver sourceResolver) {
        this.sourceResolver = Objects.requireNonNull(sourceResolver, "sourceResolver must not be null");
    }

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
        return filter(events, query, verdict, unmatchedOnly, status, null, null, limit);
    }

    /**
     * Filters a collection of serve events according to query string, verdict, unmatched flag, datetime range, and limit.
     *
     * @param events all recorded serve events
     * @param query raw search query supporting boolean AND / OR
     * @param verdict verdict filter ({@code "all"}, {@code "proxied"}, {@code "stateful"}, {@code "stub"}, {@code "unmatched"})
     * @param unmatchedOnly if true, restricts exclusively to unmatched requests (404s)
     * @param status optional HTTP status code filter
     * @param since optional lower datetime bound (inclusive)
     * @param until optional upper datetime bound (inclusive)
     * @param limit maximum number of results to return
     * @return filtered result envelope
     */
    public JournalFilterResult filter(
            List<ServeEvent> events,
            String query,
            String verdict,
            boolean unmatchedOnly,
            Integer status,
            Instant since,
            Instant until,
            int limit) {
        EnrichedJournalFilterResult enriched =
                filterEnriched(events, query, verdict, unmatchedOnly, status, since, until, limit);
        return new JournalFilterResult(enriched.requests(), enriched.total(), enriched.filtered());
    }

    /**
     * Filters serve events and resolves caller DNS hostnames for matching entries.
     *
     * @param events all recorded serve events
     * @param query raw search query supporting boolean AND / OR
     * @param verdict verdict filter ({@code "all"}, {@code "proxied"}, {@code "stateful"}, {@code "stub"}, {@code "unmatched"})
     * @param unmatchedOnly if true, restricts exclusively to unmatched requests (404s)
     * @param status optional HTTP status code filter
     * @param since optional lower datetime bound (inclusive)
     * @param until optional upper datetime bound (inclusive)
     * @param limit maximum number of results to return
     * @return enriched filter result including resolved source IP -&gt; hostname map
     */
    public EnrichedJournalFilterResult filterEnriched(
            List<ServeEvent> events,
            String query,
            String verdict,
            boolean unmatchedOnly,
            Integer status,
            Instant since,
            Instant until,
            int limit) {
        if (events == null || events.isEmpty()) {
            return new EnrichedJournalFilterResult(List.of(), Map.of(), 0, 0);
        }

        int totalCount = events.size();
        String targetVerdict =
                (verdict != null && !verdict.isBlank()) ? verdict.trim().toLowerCase(Locale.ROOT) : "all";

        List<ServeEvent> candidates = new ArrayList<>();
        List<String> candidateVerdicts = new ArrayList<>();
        for (ServeEvent event : events) {
            if (since != null || until != null) {
                Date loggedDate =
                        event.getRequest() != null ? event.getRequest().getLoggedDate() : null;
                if (loggedDate != null) {
                    Instant eventInstant = loggedDate.toInstant();
                    if (since != null && eventInstant.isBefore(since)) {
                        continue;
                    }
                    if (until != null && eventInstant.isAfter(until)) {
                        continue;
                    }
                }
            }

            String eventVerdict = classifyVerdict(event);

            if (unmatchedOnly && !"unmatched".equals(eventVerdict)) {
                continue;
            }

            if (!"all".equals(targetVerdict) && !targetVerdict.equals(eventVerdict)) {
                continue;
            }

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

            candidates.add(event);
            candidateVerdicts.add(eventVerdict);
        }

        int safeLimit = Math.max(1, Math.min(1000, limit > 0 ? limit : 100));

        if (query == null || query.isBlank()) {
            int filteredCount = candidates.size();
            List<ServeEvent> paged =
                    candidates.size() > safeLimit ? new ArrayList<>(candidates.subList(0, safeLimit)) : candidates;
            Map<String, String> hosts = resolveHostsForEvents(paged);
            return new EnrichedJournalFilterResult(paged, hosts, totalCount, filteredCount);
        }

        Map<String, String> hosts = resolveHostsForEvents(candidates);
        List<ServeEvent> matched = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            ServeEvent event = candidates.get(i);
            String eventVerdict = candidateVerdicts.get(i);
            String srcIp = ClientSourceResolver.extractSourceIp(event.getRequest());
            String srcHost = srcIp.isEmpty() ? "" : hosts.getOrDefault(srcIp, "");
            if (JournalQueryMatcher.matches(event, query, eventVerdict, srcIp, srcHost)) {
                matched.add(event);
            }
        }

        int filteredCount = matched.size();
        List<ServeEvent> paged = matched.size() > safeLimit ? new ArrayList<>(matched.subList(0, safeLimit)) : matched;
        return new EnrichedJournalFilterResult(paged, hosts, totalCount, filteredCount);
    }

    private Map<String, String> resolveHostsForEvents(List<ServeEvent> events) {
        if (events.isEmpty()) {
            return Map.of();
        }
        Set<String> sourceIps = new LinkedHashSet<>();
        for (ServeEvent event : events) {
            String srcIp = ClientSourceResolver.extractSourceIp(event.getRequest());
            if (!srcIp.isEmpty()) {
                sourceIps.add(srcIp);
            }
        }
        return sourceResolver.resolveHosts(sourceIps);
    }

    /**
     * Classifies a {@link ServeEvent} into one of the 4 core verdicts:
     * {@code "stateful"}, {@code "unmatched"}, {@code "proxied"}, or {@code "stub"}.
     *
     * @param event serve event to classify
     * @return verdict key
     */
    public static String classifyVerdict(ServeEvent event) {
        return ServeEventTrafficSupport.classifyJournalVerdict(event);
    }
}
