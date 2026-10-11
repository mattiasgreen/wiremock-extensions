package com.github.mattiasgreen.wiremock.netem.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

public class NetEmProxyConfig {

    private final String name;
    private final int listenPort;
    private final String targetHost;
    private final int targetPort;
    private final boolean enabled;

    @JsonCreator
    public NetEmProxyConfig(
            @JsonProperty("name") String name,
            @JsonProperty("listenPort") int listenPort,
            @JsonProperty("targetHost") String targetHost,
            @JsonProperty("targetPort") int targetPort,
            @JsonProperty("enabled") Boolean enabled) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.listenPort = listenPort;
        this.targetHost = (targetHost != null && !targetHost.isBlank()) ? targetHost.trim() : "127.0.0.1";
        this.targetPort = targetPort;
        this.enabled = enabled == null || enabled;
    }

    public String getName() {
        return name;
    }

    public int getListenPort() {
        return listenPort;
    }

    public String getTargetHost() {
        return targetHost;
    }

    public int getTargetPort() {
        return targetPort;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public NetEmProxyConfig withListenPort(int newListenPort) {
        return new NetEmProxyConfig(name, newListenPort, targetHost, targetPort, enabled);
    }

    public NetEmProxyConfig withEnabled(boolean newEnabled) {
        return new NetEmProxyConfig(name, listenPort, targetHost, targetPort, newEnabled);
    }
}
