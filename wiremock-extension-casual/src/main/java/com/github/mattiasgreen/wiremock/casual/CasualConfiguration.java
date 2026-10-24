package com.github.mattiasgreen.wiremock.casual;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Configuration holder for Casual protocol simulation and gateway ports.
 * Upstream proxying is defined dynamically via WireMock proxy stubs.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CasualConfiguration {

    public static final String PROP_ENABLED = "wiremock.casual.enabled";
    public static final String ENV_ENABLED = "WIREMOCK_CASUAL_ENABLED";

    public static final String PROP_PORT = "wiremock.casual.port";
    public static final String ENV_PORT = "WIREMOCK_CASUAL_PORT";

    public static final int DEFAULT_PORT = 7772;

    private boolean enabled = true;
    private int port = DEFAULT_PORT;

    public CasualConfiguration() {}

    @JsonCreator
    public CasualConfiguration(@JsonProperty("enabled") Boolean enabled, @JsonProperty("port") Integer port) {
        if (enabled != null) this.enabled = enabled;
        if (port != null && port >= 0) this.port = port;
    }

    public static CasualConfiguration fromEnvironment() {
        CasualConfiguration config = new CasualConfiguration();
        config.enabled = getBooleanPropertyOrEnv(PROP_ENABLED, ENV_ENABLED, true);
        config.port = getIntPropertyOrEnv(PROP_PORT, ENV_PORT, DEFAULT_PORT);
        return config;
    }

    private static boolean getBooleanPropertyOrEnv(String propKey, String envKey, boolean defaultValue) {
        String val = getRawPropertyOrEnv(propKey, envKey);
        return val != null ? Boolean.parseBoolean(val.trim()) : defaultValue;
    }

    private static int getIntPropertyOrEnv(String propKey, String envKey, int defaultValue) {
        String val = getRawPropertyOrEnv(propKey, envKey);
        if (val != null) {
            try {
                return Integer.parseInt(val.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private static String getRawPropertyOrEnv(String propKey, String envKey) {
        String prop = System.getProperty(propKey);
        if (prop != null && !prop.isBlank()) {
            return prop;
        }
        String env = System.getenv(envKey);
        if (env != null && !env.isBlank()) {
            return env;
        }
        return null;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void updateFrom(CasualConfiguration other) {
        if (other == null) {
            return;
        }
        this.enabled = other.enabled;
        this.port = other.port;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", enabled);
        map.put("port", port);
        return map;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CasualConfiguration that = (CasualConfiguration) o;
        return enabled == that.enabled && port == that.port;
    }

    @Override
    public int hashCode() {
        return Objects.hash(enabled, port);
    }

    @Override
    public String toString() {
        return "CasualConfiguration{enabled=" + enabled + ", port=" + port + "}";
    }
}
