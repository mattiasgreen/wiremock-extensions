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
    private final Set<UUID> completeEvents = ConcurrentHashMap.newKeySet();

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

        Set<String> tokens = new HashSet<>();
        boolean complete = extractTokens(event, tokens);
        if (complete) {
            completeEvents.add(id);
        } else {
            completeEvents.remove(id);
        }

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

        completeEvents.remove(id);
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
        completeEvents.clear();
    }

    /**
     * Checks whether the indexed content for {@code eventId} contains {@code rawToken}.
     *
     * @param eventId unique ServeEvent identifier
     * @param rawToken search token candidate
     * @return {@code Optional.of(true)} on token match, {@code Optional.of(false)} when the event
     *     was completely indexed and the token is absent, or {@link Optional#empty()} when the
     *     index cannot decide and the caller should fall back to inspecting the event directly
     */
    public Optional<Boolean> containsEventToken(UUID eventId, String rawToken) {
        if (eventId == null || rawToken == null) {
            return Optional.empty();
        }
        String token = rawToken.trim().toLowerCase(Locale.ROOT);
        if (token.length() < MIN_TOKEN_LENGTH
                || token.length() > MAX_TOKEN_LENGTH
                || TOKEN_SPLIT_PATTERN.matcher(token).find()) {
            return Optional.empty();
        }

        Set<String> tokens = eventToTokens.get(eventId);
        if (tokens != null) {
            if (tokens.contains(token)) {
                indexHits.incrementAndGet();
                return Optional.of(true);
            }
            for (String candidate : tokens) {
                if (candidate.contains(token)) {
                    indexHits.incrementAndGet();
                    return Optional.of(true);
                }
            }
        }

        if (completeEvents.contains(eventId)) {
            indexHits.incrementAndGet();
            return Optional.of(false);
        }
        return Optional.empty();
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

    private boolean extractTokens(ServeEvent event, Set<String> tokens) {
        boolean complete = true;

        // 1. URL path tokens and query
        if (event.getRequest() != null && event.getRequest().getUrl() != null) {
            complete &= tokenizeInto(event.getRequest().getUrl(), tokens);
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
                        complete &= tokenizeInto(val, tokens);
                    }
                }
            }
        }

        // 3. Request Body Tokens
        if (event.getRequest() != null && event.getRequest().getBody() != null) {
            byte[] body = event.getRequest().getBody();
            if (body.length > 1024 * 1024) {
                complete = false;
            } else if (body.length > 0) {
                String text = new String(body, StandardCharsets.UTF_8);
                complete &= tokenizeInto(text, tokens);
            }
        }

        // 4. Response Body Tokens
        if (event.getResponse() != null && event.getResponse().getBody() != null) {
            byte[] body = event.getResponse().getBody();
            if (body.length > 1024 * 1024) {
                complete = false;
            } else if (body.length > 0) {
                String text = new String(body, StandardCharsets.UTF_8);
                complete &= tokenizeInto(text, tokens);
            }
        }

        return complete;
    }

    private boolean tokenizeInto(String text, Set<String> target) {
        if (text == null || text.isBlank()) {
            return true;
        }

        boolean complete = true;
        String[] rawTokens = TOKEN_SPLIT_PATTERN.split(text);
        for (String raw : rawTokens) {
            if (raw.length() > MAX_TOKEN_LENGTH) {
                complete = false;
                continue;
            }
            if (raw.length() >= MIN_TOKEN_LENGTH) {
                String normalized = raw.toLowerCase(Locale.ROOT);
                if (!target.contains(normalized)) {
                    if (target.size() >= MAX_TOKENS_PER_EVENT) {
                        return false;
                    }
                    target.add(normalized);
                }
            }
        }
        return complete;
    }
}
