package com.github.mattiasgreen.wiremock.otel;

import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ServeEventListener;
import com.github.tomakehurst.wiremock.http.LoggedResponse;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.util.Locale;
import java.util.Objects;

public class OtelMetricsListener implements ServeEventListener {

    public static final String LISTENER_NAME = "otel-metrics-listener";

    private final OtelMetricsRegistry metricsRegistry;

    public OtelMetricsListener() {
        this(OtelMetricsRegistry.getInstance());
    }

    public OtelMetricsListener(OtelMetricsRegistry metricsRegistry) {
        this.metricsRegistry = Objects.requireNonNull(metricsRegistry, "metricsRegistry must not be null");
    }

    @Override
    public String getName() {
        return LISTENER_NAME;
    }

    @Override
    public boolean applyGlobally() {
        return true;
    }

    @Override
    public void afterComplete(ServeEvent serveEvent, Parameters parameters) {
        if (serveEvent == null) {
            return;
        }

        Request request = serveEvent.getRequest();
        LoggedResponse response = serveEvent.getResponse();
        ResponseDefinition respDef = serveEvent.getResponseDefinition();
        StubMapping stubMapping = serveEvent.getStubMapping();
        boolean matched = serveEvent.getWasMatched();

        String method = "UNKNOWN";
        if (request != null && request.getMethod() != null) {
            method = request.getMethod().getName();
        }

        int statusCode = 0;
        if (response != null) {
            statusCode = response.getStatus();
        }

        String stubName = "none";
        if (stubMapping != null && stubMapping.getName() != null) {
            stubName = stubMapping.getName();
        }

        String protocol = "http";
        String rpcService = null;
        String rpcStatus = null;
        if (request != null) {
            if (request.header("X-WireMock-Protocol").isPresent()) {
                protocol = request.header("X-WireMock-Protocol").firstValue().toLowerCase(Locale.ROOT);
            } else if (request.header("X-Casual-Service").isPresent()
                    || (request.getUrl() != null && request.getUrl().startsWith("/__casual/"))) {
                protocol = "casual";
            } else if ((request.header("Content-Type").isPresent()
                            && request.header("Content-Type").firstValue().contains("application/grpc"))
                    || request.header("X-Grpc-Service").isPresent()) {
                protocol = "grpc";
            }

            if ("casual".equals(protocol)) {
                if (request.header("X-Casual-Service").isPresent()) {
                    rpcService = request.header("X-Casual-Service").firstValue();
                } else if (request.getUrl() != null && request.getUrl().startsWith("/__casual/services/")) {
                    rpcService = request.getUrl().substring("/__casual/services/".length());
                    int slashIdx = rpcService.indexOf('/');
                    int queryIdx = rpcService.indexOf('?');
                    int end = rpcService.length();
                    if (slashIdx >= 0) {
                        end = Math.min(end, slashIdx);
                    }
                    if (queryIdx >= 0) {
                        end = Math.min(end, queryIdx);
                    }
                    rpcService = rpcService.substring(0, end);
                }
                if (response != null
                        && response.getHeaders() != null
                        && response.getHeaders()
                                .getHeader("X-Casual-Error-State")
                                .isPresent()) {
                    rpcStatus = response.getHeaders()
                            .getHeader("X-Casual-Error-State")
                            .firstValue();
                } else {
                    rpcStatus = "OK";
                }
            }
        }

        boolean isFault =
                (respDef != null && respDef.getFault() != null) || (response != null && response.getFault() != null);

        boolean isProxied = (respDef != null && respDef.isProxyResponse())
                || (stubMapping != null
                        && stubMapping.getResponse() != null
                        && stubMapping.getResponse().isProxyResponse());

        String targetUpstream = "none";
        if (respDef != null
                && respDef.getProxyBaseUrl() != null
                && !respDef.getProxyBaseUrl().isBlank()) {
            targetUpstream = respDef.getProxyBaseUrl();
        } else if (stubMapping != null
                && stubMapping.getResponse() != null
                && stubMapping.getResponse().getProxyBaseUrl() != null
                && !stubMapping.getResponse().getProxyBaseUrl().isBlank()) {
            targetUpstream = stubMapping.getResponse().getProxyBaseUrl();
        }

        String trafficType;
        if (isFault) {
            trafficType = "fault";
        } else if (isProxied) {
            trafficType = "proxied";
        } else if (matched) {
            trafficType = "stubbed";
        } else {
            trafficType = "unmatched";
        }

        double durationMs = 0.0;
        double upstreamDurationMs = 0.0;
        if (serveEvent.getTiming() != null) {
            if (serveEvent.getTiming().getTotalTime() != null) {
                durationMs = serveEvent.getTiming().getTotalTime();
            }
            if (isProxied && serveEvent.getTiming().getServeTime() != null) {
                upstreamDurationMs = serveEvent.getTiming().getServeTime();
            } else if (isProxied) {
                upstreamDurationMs = durationMs;
            }
        }

        metricsRegistry.recordRequest(
                protocol,
                trafficType,
                method,
                statusCode,
                matched,
                stubName,
                targetUpstream,
                rpcService,
                rpcStatus,
                durationMs,
                upstreamDurationMs);
    }
}
