package com.github.mattiasgreen.wiremock.async.model;

import java.util.Locale;

/**
 * Operating mode for async stubs and bridges: local mock simulation or live proxy forwarding.
 *
 * <p><b>Note:</b> This is Beta functionality subject to evolution.
 */
public enum AsyncMode {
    MOCK,
    PROXY;

    public static AsyncMode fromString(String val) {
        if (val == null || val.isBlank()) {
            return MOCK;
        }
        return switch (val.trim().toUpperCase(Locale.ROOT)) {
            case "PROXY" -> PROXY;
            default -> MOCK;
        };
    }
}
