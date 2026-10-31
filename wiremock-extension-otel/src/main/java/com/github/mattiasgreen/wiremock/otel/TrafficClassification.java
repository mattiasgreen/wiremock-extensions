package com.github.mattiasgreen.wiremock.otel;

import com.github.tomakehurst.wiremock.http.LoggedResponse;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;

/**
 * Immutable domain representation of classified traffic derived from a WireMock {@link ServeEvent}.
 * Centralizes protocol sniffing, proxy target resolution, RPC service extraction, and timings.
 */
public record TrafficClassification(
        String protocol,
        String trafficType,
        String method,
        int statusCode,
        boolean matched,
        String stubId,
        String stubName,
        String scenarioName,
        String scenarioState,
        String targetUpstream,
        String rpcSystem,
        String rpcService,
        String rpcStatus,
        String urlPath,
        String urlQuery,
        String urlFull,
        String clientIp,
        double durationMs,
        double upstreamDurationMs,
        Instant startTimestamp,
        Instant endTimestamp) {

    public static TrafficClassification from(ServeEvent serveEvent) {
        if (serveEvent == null) {
            Instant now = Instant.now();
            return new TrafficClassification(
                    "http",
                    "unmatched",
                    "UNKNOWN",
                    0,
                    false,
                    null,
                    "none",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "/",
                    null,
                    "/",
                    null,
                    0.0,
                    0.0,
                    now,
                    now);
        }

        LoggedRequest request = serveEvent.getRequest();
        LoggedResponse response = serveEvent.getResponse();
        ResponseDefinition respDef = serveEvent.getResponseDefinition();
        StubMapping stubMapping = serveEvent.getStubMapping();
        boolean matched = serveEvent.getWasMatched();

        String method = "UNKNOWN";
        String urlFull = "/";
        String urlPath = "/";
        String urlQuery = null;
        String clientIp = null;

        if (request != null) {
            if (request.getMethod() != null) {
                method = request.getMethod().getName();
            }
            if (request.getAbsoluteUrl() != null) {
                urlFull = request.getAbsoluteUrl();
            }
            if (request.getUrl() != null) {
                urlPath = sanitizeSpanPath(request.getUrl());
                urlQuery = extractQuery(request.getUrl());
            }
            clientIp = request.getClientIp();
        }

        int statusCode = 0;
        if (response != null) {
            statusCode = response.getStatus();
        }

        String stubId = null;
        String stubName = "none";
        String scenarioName = null;
        String scenarioState = null;

        if (stubMapping != null) {
            if (stubMapping.getId() != null) {
                stubId = stubMapping.getId().toString();
            }
            if (stubMapping.getName() != null) {
                stubName = stubMapping.getName();
            }
            scenarioName = stubMapping.getScenarioName();
            scenarioState = stubMapping.getRequiredScenarioState();
        }

        String protocol = "http";
        String rpcSystem = null;
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
                rpcSystem = "casual";
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

        String targetUpstream = null;
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

        Date loggedDate = request != null ? request.getLoggedDate() : null;
        Instant start = loggedDate != null ? loggedDate.toInstant() : Instant.now();
        long durationNanos = Math.max(0, (long) (durationMs * 1_000_000.0));
        Instant end = start.plusNanos(durationNanos);

        return new TrafficClassification(
                protocol,
                trafficType,
                method,
                statusCode,
                matched,
                stubId,
                stubName,
                scenarioName,
                scenarioState,
                targetUpstream,
                rpcSystem,
                rpcService,
                rpcStatus,
                urlPath,
                urlQuery,
                urlFull,
                clientIp,
                durationMs,
                upstreamDurationMs,
                start,
                end);
    }

    private static String sanitizeSpanPath(String rawUrl) {
        if (rawUrl == null || rawUrl.isEmpty()) {
            return "/";
        }
        int queryIndex = rawUrl.indexOf('?');
        return queryIndex >= 0 ? rawUrl.substring(0, queryIndex) : rawUrl;
    }

    private static String extractQuery(String rawUrl) {
        if (rawUrl == null) {
            return null;
        }
        int queryIndex = rawUrl.indexOf('?');
        return (queryIndex >= 0 && queryIndex + 1 < rawUrl.length()) ? rawUrl.substring(queryIndex + 1) : null;
    }
}
