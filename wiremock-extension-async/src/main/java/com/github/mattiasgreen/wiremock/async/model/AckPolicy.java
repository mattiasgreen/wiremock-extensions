package com.github.mattiasgreen.wiremock.async.model;

import java.util.Locale;

/**
 * Message acknowledgment policy for consumed asynchronous messages.
 *
 * <p><b>Note:</b> This is Beta functionality subject to evolution.
 */
public enum AckPolicy {
    ACK,
    NACK,
    REJECT;

    public static AckPolicy fromString(String val) {
        if (val == null || val.isBlank()) {
            return ACK;
        }
        return switch (val.trim().toUpperCase(Locale.ROOT)) {
            case "NACK" -> NACK;
            case "REJECT" -> REJECT;
            default -> ACK;
        };
    }
}
