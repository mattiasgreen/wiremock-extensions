package com.github.mattiasgreen.wiremock.netem.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum NetEmStreamDirection {
    UPSTREAM("upstream"),
    DOWNSTREAM("downstream"),
    BOTH("both");

    private final String value;

    NetEmStreamDirection(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static NetEmStreamDirection fromString(String value) {
        if (value == null) {
            return DOWNSTREAM;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (NetEmStreamDirection dir : values()) {
            if (dir.value.equals(normalized)) {
                return dir;
            }
        }
        return DOWNSTREAM;
    }
}
