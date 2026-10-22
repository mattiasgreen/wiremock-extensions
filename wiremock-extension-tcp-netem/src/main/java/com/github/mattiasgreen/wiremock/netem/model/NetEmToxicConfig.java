package com.github.mattiasgreen.wiremock.netem.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class NetEmToxicConfig {

    private final String name;
    private final NetEmToxicType type;
    private final NetEmStreamDirection stream;
    private final double toxicity;
    private final String pattern;
    private final String patternType;
    private final String clientIp;
    private final Map<String, Object> attributes;

    @JsonCreator
    public NetEmToxicConfig(
            @JsonProperty("name") String name,
            @JsonProperty("type") Object type,
            @JsonProperty("stream") NetEmStreamDirection stream,
            @JsonProperty("toxicity") Double toxicity,
            @JsonProperty("pattern") String pattern,
            @JsonProperty("patternType") String patternType,
            @JsonProperty("clientIp") String clientIp,
            @JsonProperty("correlationId") String legacyCorrelationId,
            @JsonProperty("attributes") Map<String, Object> attributes) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        if (type instanceof NetEmToxicType toxicType) {
            this.type = toxicType;
        } else if (type instanceof String typeStr) {
            this.type = NetEmToxicType.fromString(typeStr);
        } else {
            this.type = NetEmToxicType.LATENCY;
        }
        this.stream = stream != null ? stream : NetEmStreamDirection.DOWNSTREAM;
        this.toxicity = toxicity != null ? toxicity : 1.0;

        if (pattern != null && !pattern.isBlank()) {
            this.pattern = pattern;
            this.patternType =
                    (patternType != null && !patternType.isBlank()) ? patternType.toUpperCase(Locale.ROOT) : "ASCII";
        } else if (legacyCorrelationId != null && !legacyCorrelationId.isBlank()) {
            this.pattern = legacyCorrelationId;
            this.patternType = "ASCII";
        } else {
            this.pattern = null;
            this.patternType =
                    (patternType != null && !patternType.isBlank()) ? patternType.toUpperCase(Locale.ROOT) : "ASCII";
        }

        this.clientIp = (clientIp != null && !clientIp.isBlank()) ? clientIp.trim() : null;
        this.attributes = attributes != null ? new HashMap<>(attributes) : new HashMap<>();
    }

    public NetEmToxicConfig(
            String name,
            Object type,
            NetEmStreamDirection stream,
            Double toxicity,
            String pattern,
            String patternType,
            String clientIp,
            Map<String, Object> attributes) {
        this(name, type, stream, toxicity, pattern, patternType, clientIp, null, attributes);
    }

    public String getName() {
        return name;
    }

    public NetEmToxicType getType() {
        return type;
    }

    public NetEmStreamDirection getStream() {
        return stream;
    }

    public double getToxicity() {
        return toxicity;
    }

    public String getPattern() {
        return pattern;
    }

    public String getPatternType() {
        return patternType;
    }

    public String getClientIp() {
        return clientIp;
    }

    /**
     * @deprecated Use {@link #getPattern()} instead.
     */
    @Deprecated
    public String getCorrelationId() {
        return pattern;
    }

    public Map<String, Object> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    public long getLongAttribute(String key, long defaultValue) {
        Object val = attributes.get(key);
        if (val instanceof Number number) {
            return number.longValue();
        } else if (val instanceof String str) {
            try {
                return Long.parseLong(str.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public int getIntAttribute(String key, int defaultValue) {
        Object val = attributes.get(key);
        if (val instanceof Number number) {
            return number.intValue();
        } else if (val instanceof String str) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public static Builder builder(String name, NetEmToxicType type) {
        return new Builder(name, type);
    }

    public static class Builder {
        private final String name;
        private final NetEmToxicType type;
        private NetEmStreamDirection stream = NetEmStreamDirection.DOWNSTREAM;
        private double toxicity = 1.0;
        private String pattern;
        private String patternType = "ASCII";
        private String clientIp;
        private final Map<String, Object> attributes = new HashMap<>();

        public Builder(String name, NetEmToxicType type) {
            this.name = name;
            this.type = type;
        }

        public Builder stream(NetEmStreamDirection stream) {
            this.stream = stream;
            return this;
        }

        public Builder toxicity(double toxicity) {
            this.toxicity = toxicity;
            return this;
        }

        public Builder pattern(String pattern) {
            this.pattern = pattern;
            return this;
        }

        public Builder patternType(String patternType) {
            this.patternType = patternType;
            return this;
        }

        public Builder clientIp(String clientIp) {
            this.clientIp = clientIp;
            return this;
        }

        /**
         * @deprecated Use {@link #pattern(String)} instead.
         */
        @Deprecated
        public Builder correlationId(String correlationId) {
            this.pattern = correlationId;
            this.patternType = "ASCII";
            return this;
        }

        public Builder attribute(String key, Object value) {
            this.attributes.put(key, value);
            return this;
        }

        public Builder attributes(Map<String, Object> attributes) {
            if (attributes != null) {
                this.attributes.putAll(attributes);
            }
            return this;
        }

        public NetEmToxicConfig build() {
            return new NetEmToxicConfig(name, type, stream, toxicity, pattern, patternType, clientIp, null, attributes);
        }
    }
}
