package com.github.mattiasgreen.wiremock.casual;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable representation of a message inspected from a remote Casual queue.
 */
public record CasualRemoteMessage(
        UUID id,
        String queueName,
        String correlationInformation,
        String replyQueue,
        String bufferType,
        String payload,
        long redelivered,
        Instant timestamp) {}
