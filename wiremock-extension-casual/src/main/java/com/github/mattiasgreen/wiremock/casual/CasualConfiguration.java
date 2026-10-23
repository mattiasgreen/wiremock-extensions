package com.github.mattiasgreen.wiremock.casual;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configuration holder for Casual protocol simulation, gateway ports,
 * upstream forwarding targets, and stub recording policies.
 * <p>
 * Evaluates System Properties first, then Environment Variables, and finally
 * sensible defaults. Also supports dynamic updates at runtime via REST API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CasualConfiguration {

    public static final String PROP_ENABLED = "wiremock.casual.enabled";
    public static final String ENV_ENABLED = "WIREMOCK_CASUAL_ENABLED";

    public static final String PROP_PORT = "wiremock.casual.port";
    public static final String ENV_PORT = "WIREMOCK_CASUAL_PORT";

    public static final String PROP_UPSTREAM_HOST = "wiremock.casual.upstream.host";
    public static final String ENV_UPSTREAM_HOST = "WIREMOCK_CASUAL_UPSTREAM_HOST";

    public static final String PROP_UPSTREAM_PORT = "wiremock.casual.upstream.port";
    public static final String ENV_UPSTREAM_PORT = "WIREMOCK_CASUAL_UPSTREAM_PORT";

    public static final String PROP_UPSTREAM_ENABLED = "wiremock.casual.upstream.enabled";
    public static final String ENV_UPSTREAM_ENABLED = "WIREMOCK_CASUAL_UPSTREAM_ENABLED";

    public static final String PROP_RECORD_STUBS = "wiremock.casual.record.stubs";
    public static final String PROP_RECORD = "wiremock.casual.record";
    public static final String ENV_RECORD_STUBS = "WIREMOCK_CASUAL_RECORD_STUBS";
    public static final String ENV_RECORD = "WIREMOCK_CASUAL_RECORD";

    public static final String PROP_RECORD_FAULTS = "wiremock.casual.record.faults";
    public static final String ENV_RECORD_FAULTS = "WIREMOCK_CASUAL_RECORD_FAULTS";

    public static final String PROP_RECORD_NOT_FOUND = "wiremock.casual.record.notfound";
    public static final String ENV_RECORD_NOT_FOUND = "WIREMOCK_CASUAL_RECORD_NOT_FOUND";

    public static final int DEFAULT_PORT = 7772;

    private boolean enabled = true;
    private int port = DEFAULT_PORT;
    private String upstreamHost;
    private int upstreamPort = 0;
    private Boolean upstreamEnabled = null;
    private boolean recordStubs = false;
    private boolean recordFaults = true;
    private boolean recordNotFound = false;

    public CasualConfiguration() {}

    @JsonCreator
    public CasualConfiguration(
            @JsonProperty("enabled") Boolean enabled,
            @JsonProperty("port") Integer port,
            @JsonProperty("upstreamHost") String upstreamHost,
            @JsonProperty("upstreamPort") Integer upstreamPort,
            @JsonProperty("upstreamEnabled") Boolean upstreamEnabled,
            @JsonProperty("recordStubs") Boolean recordStubs,
            @JsonProperty("recordFaults") Boolean recordFaults,
            @JsonProperty("recordNotFound") Boolean recordNotFound) {
        if (enabled != null) this.enabled = enabled;
        if (port != null && port >= 0) this.port = port;
        if (upstreamHost != null) this.upstreamHost = upstreamHost;
        if (upstreamPort != null) this.upstreamPort = upstreamPort;
        this.upstreamEnabled = upstreamEnabled;
        if (recordStubs != null) this.recordStubs = recordStubs;
        if (recordFaults != null) this.recordFaults = recordFaults;
        if (recordNotFound != null) this.recordNotFound = recordNotFound;
    }

    public static CasualConfiguration fromEnvironment() {
        CasualConfiguration config = new CasualConfiguration();

        config.enabled = getBooleanPropertyOrEnv(PROP_ENABLED, ENV_ENABLED, true);
        config.port = getIntPropertyOrEnv(PROP_PORT, ENV_PORT, DEFAULT_PORT);
        config.upstreamHost = getStringPropertyOrEnv(PROP_UPSTREAM_HOST, ENV_UPSTREAM_HOST, null);
        config.upstreamPort = getIntPropertyOrEnv(PROP_UPSTREAM_PORT, ENV_UPSTREAM_PORT, 0);

        String upstreamEnabledRaw = getRawPropertyOrEnv(PROP_UPSTREAM_ENABLED, ENV_UPSTREAM_ENABLED);
        if (upstreamEnabledRaw != null && !upstreamEnabledRaw.isBlank()) {
            config.upstreamEnabled = Boolean.parseBoolean(upstreamEnabledRaw.trim());
        } else {
            config.upstreamEnabled = null; // Auto-determined from host & port
        }

        config.recordStubs = getBooleanPropertyOrEnv(
                PROP_RECORD_STUBS, ENV_RECORD_STUBS, getBooleanPropertyOrEnv(PROP_RECORD, ENV_RECORD, false));
        config.recordFaults = getBooleanPropertyOrEnv(PROP_RECORD_FAULTS, ENV_RECORD_FAULTS, true);
        config.recordNotFound = getBooleanPropertyOrEnv(PROP_RECORD_NOT_FOUND, ENV_RECORD_NOT_FOUND, false);

        return config;
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

    public String getUpstreamHost() {
        return upstreamHost;
    }

    public void setUpstreamHost(String upstreamHost) {
        this.upstreamHost = upstreamHost;
    }

    public int getUpstreamPort() {
        return upstreamPort;
    }

    public void setUpstreamPort(int upstreamPort) {
        this.upstreamPort = upstreamPort;
    }

    public Boolean getUpstreamEnabled() {
        return upstreamEnabled;
    }

    public void setUpstreamEnabled(Boolean upstreamEnabled) {
        this.upstreamEnabled = upstreamEnabled;
    }

    public boolean isUpstreamEnabled() {
        if (upstreamEnabled != null) {
            return upstreamEnabled && hasUpstreamConfigured();
        }
        return hasUpstreamConfigured();
    }

    public boolean hasUpstreamConfigured() {
        return upstreamHost != null && !upstreamHost.isBlank() && upstreamPort > 0;
    }

    public boolean isRecordStubs() {
        return recordStubs;
    }

    public void setRecordStubs(boolean recordStubs) {
        this.recordStubs = recordStubs;
    }

    public boolean isRecordFaults() {
        return recordFaults;
    }

    public void setRecordFaults(boolean recordFaults) {
        this.recordFaults = recordFaults;
    }

    public boolean isRecordNotFound() {
        return recordNotFound;
    }

    public void setRecordNotFound(boolean recordNotFound) {
        this.recordNotFound = recordNotFound;
    }

    public void updateFrom(CasualConfiguration other) {
        if (other == null) return;
        this.enabled = other.enabled;
        if (other.port >= 0) this.port = other.port;
        if (other.upstreamHost != null) this.upstreamHost = other.upstreamHost;
        if (other.upstreamPort > 0) this.upstreamPort = other.upstreamPort;
        if (other.upstreamEnabled != null) this.upstreamEnabled = other.upstreamEnabled;
        this.recordStubs = other.recordStubs;
        this.recordFaults = other.recordFaults;
        this.recordNotFound = other.recordNotFound;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", enabled);
        map.put("port", port);
        map.put("upstreamHost", upstreamHost);
        map.put("upstreamPort", upstreamPort);
        map.put("upstreamEnabled", isUpstreamEnabled());
        map.put("hasUpstreamConfigured", hasUpstreamConfigured());
        map.put("recordStubs", recordStubs);
        map.put("recordFaults", recordFaults);
        map.put("recordNotFound", recordNotFound);
        return map;
    }

    private static String getRawPropertyOrEnv(String propertyName, String envName) {
        String prop = System.getProperty(propertyName);
        if (prop != null && !prop.isBlank()) {
            return prop;
        }
        return System.getenv(envName);
    }

    private static String getStringPropertyOrEnv(String propertyName, String envName, String defaultValue) {
        String val = getRawPropertyOrEnv(propertyName, envName);
        return (val != null && !val.isBlank()) ? val.trim() : defaultValue;
    }

    private static boolean getBooleanPropertyOrEnv(String propertyName, String envName, boolean defaultValue) {
        String val = getRawPropertyOrEnv(propertyName, envName);
        return (val != null && !val.isBlank()) ? Boolean.parseBoolean(val.trim()) : defaultValue;
    }

    private static int getIntPropertyOrEnv(String propertyName, String envName, int defaultValue) {
        String val = getRawPropertyOrEnv(propertyName, envName);
        if (val != null && !val.isBlank()) {
            try {
                return Integer.parseInt(val.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }
}
