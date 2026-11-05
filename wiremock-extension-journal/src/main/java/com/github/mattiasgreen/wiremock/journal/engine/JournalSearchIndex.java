package com.github.mattiasgreen.wiremock.journal.engine;

import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * In-memory inverted index extracting and indexing identifiers, path tokens, headers,
 * and payload tokens during initial ingestion while raw bytes are in-flight.
 * Enables sub-millisecond search resolution with zero payload decompression.
 */
public class JournalSearchIndex {

    private static final Pattern TOKEN_SPLIT_PATTERN = Pattern.compile("[^a-zA-Z0-9_-]+");
    private static final int MAX_TOKENS_PER_EVENT = 256;
    private static final int MIN_TOKEN_LENGTH = 3;
    private static final int MAX_TOKEN_LENGTH = 64;

    private final ConcurrentHashMap<String, Set<UUID>> tokenToEvents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<String>> eventToTokens = new ConcurrentHashMap<>();

    private final AtomicLong totalSearches = new AtomicLong(0);
    private final AtomicLong indexHits = new AtomicLong(0);
    private final AtomicLong transientDecompressions = new AtomicLong(0);

    /**
     * Indexes search tokens for a newly ingested ServeEvent while raw bytes are in hand.
     */
    public void index(UUID id, ServeEvent event) {
        if (id == null || event == null) {
            return;
        }

        Set<String> tokens = extractTokens(event);
        if (tokens.isEmpty()) {
            return;
        }

        eventToTokens.put(id, tokens);
        for (String token : tokens) {
            tokenToEvents
                    .computeIfAbsent(token, k -> ConcurrentHashMap.newKeySet())
                    .add(id);
        }
    }

    /**
     * Removes an event from the inverted index upon FIFO eviction.
     */
    public void remove(UUID id) {
        if (id == null) {
            return;
        }

        Set<String> tokens = eventToTokens.remove(id);
        if (tokens != null) {
            for (String token : tokens) {
                Set<UUID> events = tokenToEvents.get(token);
                if (events != null) {
                    events.remove(id);
                    if (events.isEmpty()) {
                        tokenToEvents.remove(token, Collections.emptySet());
                    }
                }
            }
        }
    }

    /**
     * Clears all inverted index mappings.
     */
    public void clear() {
        tokenToEvents.clear();
        eventToTokens.clear();
    }

    /**
     * Queries the inverted token index for matching event UUIDs.
     * Returns Optional.of(matchingIds) if query is indexable, or Optional.empty() if fallback is required.
     */
    public Optional<Set<UUID>> search(String query) {
        totalSearches.incrementAndGet();
        if (query == null || query.isBlank()) {
            return Optional.of(Collections.emptySet());
        }

        String normalized = query.trim().toLowerCase(Locale.ROOT);
        String[] queryTokens = TOKEN_SPLIT_PATTERN.split(normalized);

        List<String> validTokens = new ArrayList<>();
        for (String t : queryTokens) {
            if (t.length() >= MIN_TOKEN_LENGTH && t.length() <= MAX_TOKEN_LENGTH) {
                validTokens.add(t);
            }
        }

        if (validTokens.isEmpty()) {
            // Cannot satisfy via token index (e.g. 1-2 char substring query); requires fallback
            return Optional.empty();
        }

        Set<UUID> intersection = null;
        for (String token : validTokens) {
            Set<UUID> matches = tokenToEvents.get(token);
            if (matches == null || matches.isEmpty()) {
                indexHits.incrementAndGet();
                return Optional.of(Collections.emptySet());
            }
            if (intersection == null) {
                intersection = new HashSet<>(matches);
            } else {
                intersection.retainAll(matches);
                if (intersection.isEmpty()) {
                    indexHits.incrementAndGet();
                    return Optional.of(Collections.emptySet());
                }
            }
        }

        indexHits.incrementAndGet();
        return Optional.of(intersection != null ? intersection : Collections.emptySet());
    }

    public void recordTransientDecompression() {
        transientDecompressions.incrementAndGet();
    }

    public long getTotalSearches() {
        return totalSearches.get();
    }

    public long getIndexHits() {
        return indexHits.get();
    }

    public long getTransientDecompressions() {
        return transientDecompressions.get();
    }

    public int getIndexedTokenCount() {
        return tokenToEvents.size();
    }

    public int getIndexedEventCount() {
        return eventToTokens.size();
    }

    private Set<String> extractTokens(ServeEvent event) {
        Set<String> tokens = new HashSet<>();

        // 1. URL path tokens and query
        if (event.getRequest() != null && event.getRequest().getUrl() != null) {
            tokenizeInto(event.getRequest().getUrl(), tokens);
        }

        // 2. Correlation and Tenant Headers
        if (event.getRequest() != null && event.getRequest().getHeaders() != null) {
            for (HttpHeader header : event.getRequest().getHeaders().all()) {
                String key = header.key().toLowerCase(Locale.ROOT);
                if (key.contains("correlation")
                        || key.contains("tenant")
                        || key.contains("id")
                        || key.contains("service")) {
                    for (String val : header.values()) {
                        tokenizeInto(val, tokens);
                    }
                }
            }
        }

        // 3. Request Body Tokens
        if (tokens.size() < MAX_TOKENS_PER_EVENT
                && event.getRequest() != null
                && event.getRequest().getBody() != null) {
            byte[] body = event.getRequest().getBody();
            if (body.length > 0 && body.length <= 1024 * 1024) {
                String text = new String(body, StandardCharsets.UTF_8);
                tokenizeInto(text, tokens);
            }
        }

        // 4. Response Body Tokens
        if (tokens.size() < MAX_TOKENS_PER_EVENT
                && event.getResponse() != null
                && event.getResponse().getBody() != null) {
            byte[] body = event.getResponse().getBody();
            if (body.length > 0 && body.length <= 1024 * 1024) {
                String text = new String(body, StandardCharsets.UTF_8);
                tokenizeInto(text, tokens);
            }
        }

        return tokens;
    }

    private void tokenizeInto(String text, Set<String> target) {
        if (text == null || text.isBlank() || target.size() >= MAX_TOKENS_PER_EVENT) {
            return;
        }

        String[] rawTokens = TOKEN_SPLIT_PATTERN.split(text);
        for (String raw : rawTokens) {
            if (raw.length() >= MIN_TOKEN_LENGTH && raw.length() <= MAX_TOKEN_LENGTH) {
                target.add(raw.toLowerCase(Locale.ROOT));
                if (target.size() >= MAX_TOKENS_PER_EVENT) {
                    break;
                }
            }
        }
    }
}
