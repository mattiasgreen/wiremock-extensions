package com.github.mattiasgreen.wiremock.netem.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

public enum NetEmToxicType {
    LATENCY("latency"),
    BANDWIDTH("bandwidth"),
    SLOW_CLOSE("slow_close"),
    TIMEOUT("timeout"),
    RESET_PEER("reset_peer"),
    SLICER("slicer"),
    LIMIT_DATA("limit_data");

    private final String value;

    NetEmToxicType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static NetEmToxicType fromString(String value) {
        if (value == null) {
            return LATENCY;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (NetEmToxicType type : values()) {
            if (type.value.equals(normalized)
                    || type.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown toxic type: " + value);
    }
}
