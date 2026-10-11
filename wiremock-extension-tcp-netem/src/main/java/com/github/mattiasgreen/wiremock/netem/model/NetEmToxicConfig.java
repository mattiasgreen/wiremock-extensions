package com.github.mattiasgreen.wiremock.netem.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class NetEmToxicConfig {

    private final String name;
    private final NetEmToxicType type;
    private final NetEmStreamDirection stream;
    private final double toxicity;
    private final String correlationId;
    private final Map<String, Object> attributes;

    @JsonCreator
    public NetEmToxicConfig(
            @JsonProperty("name") String name,
            @JsonProperty("type") Object type,
            @JsonProperty("stream") NetEmStreamDirection stream,
            @JsonProperty("toxicity") Double toxicity,
            @JsonProperty("correlationId") String correlationId,
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
        this.correlationId = correlationId;
        this.attributes = attributes != null ? new HashMap<>(attributes) : new HashMap<>();
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

    public String getCorrelationId() {
        return correlationId;
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
        private String correlationId;
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

        public Builder correlationId(String correlationId) {
            this.correlationId = correlationId;
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
            return new NetEmToxicConfig(name, type, stream, toxicity, correlationId, attributes);
        }
    }
}
