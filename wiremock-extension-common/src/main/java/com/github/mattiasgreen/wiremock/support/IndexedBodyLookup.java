package com.github.mattiasgreen.wiremock.support;

import java.util.Optional;
import java.util.UUID;

/**
 * Optional capability interface for request journal stores that maintain an inverted token index
 * over captured event bodies, enabling fast pre-filtering without decompressing full payloads.
 */
public interface IndexedBodyLookup {

    /**
     * Checks whether the indexed body content for the specified serve event contains the given
     * search token.
     *
     * @param eventId unique WireMock {@code ServeEvent} identifier
     * @param token normalized search token to check in the body index
     * @return {@code Optional.of(true)} or {@code Optional.of(false)} when the index provides an
     *     authoritative answer, or {@link Optional#empty()} when the index misses or cannot decide
     *     (signaling the caller to fall back to scanning the event directly)
     */
    Optional<Boolean> containsEventToken(UUID eventId, String token);
}
