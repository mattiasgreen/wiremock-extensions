package com.github.mattiasgreen.wiremock.support;

import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.LoggedResponse;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Shared stateless utility for classifying WireMock {@link ServeEvent}s and normalizing caller
 * source IP tokens across extensions ({@code otel}, {@code json-logging}, and {@code ui}).
 */
public final class ServeEventTrafficSupport {

    private static final Pattern SAFE_SOURCE_PATTERN = Pattern.compile("^[a-zA-Z0-9.:_-]{1,253}$");

    private ServeEventTrafficSupport() {}

    /**
     * Extracts the normalized caller IP/token from a WireMock {@link Request}.
     * Precedence: {@code X-Forwarded-For} (first hop) -&gt; {@code X-Real-IP} -&gt; {@code request.getClientIp()}.
     *
     * @param request incoming WireMock request
     * @return normalized caller IP/token, or {@code ""} if unavailable or unsafe
     */
    public static String extractClientIp(Request request) {
        if (request == null) {
            return "";
        }

        HttpHeader xff = findHeader(request, "X-Forwarded-For");
        if (xff != null && xff.isPresent() && xff.firstValue() != null) {
            String rawFirstHop = xff.firstValue().split(",", 2)[0];
            String normalized = normalizeSourceToken(rawFirstHop);
            if (!normalized.isEmpty()) {
                return normalized;
            }
        }

        HttpHeader xRealIp = findHeader(request, "X-Real-IP");
        if (xRealIp != null && xRealIp.isPresent() && xRealIp.firstValue() != null) {
            String normalized = normalizeSourceToken(xRealIp.firstValue());
            if (!normalized.isEmpty()) {
                return normalized;
            }
        }

        return normalizeSourceToken(request.getClientIp());
    }

    /**
     * Returns the normalized proxy/socket caller IP when valid, or falls back to
     * {@code request.getClientIp()} (or {@code null} when unavailable).
     *
     * @param request incoming WireMock request
     * @return caller IP or {@code null}
     */
    public static String extractClientIpOrRaw(Request request) {
        if (request == null) {
            return null;
        }
        String normalized = extractClientIp(request);
        if (!normalized.isEmpty()) {
            return normalized;
        }
        return request.getClientIp();
    }

    /**
     * Normalizes a raw IP or host token by stripping ports, IPv6 brackets/zones, canonicalizing
     * extended loopback notation, and rejecting unsafe characters.
     *
     * @param raw raw IP/host string
     * @return normalized token, or {@code ""} if blank/unknown/invalid
     */
    public static String normalizeSourceToken(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "unknown".equalsIgnoreCase(trimmed)) {
            return "";
        }

        if (trimmed.startsWith("[")) {
            int closeIdx = trimmed.indexOf(']');
            if (closeIdx > 1) {
                trimmed = trimmed.substring(1, closeIdx);
            } else {
                return "";
            }
        } else if (trimmed.indexOf('.') >= 0) {
            int firstColon = trimmed.indexOf(':');
            if (firstColon > 0 && trimmed.indexOf(':', firstColon + 1) < 0) {
                trimmed = trimmed.substring(0, firstColon);
            }
        }

        int zoneIdx = trimmed.indexOf('%');
        if (zoneIdx > 0) {
            trimmed = trimmed.substring(0, zoneIdx);
        }

        if ("0:0:0:0:0:0:0:1".equals(trimmed)) {
            trimmed = "::1";
        }

        if (!SAFE_SOURCE_PATTERN.matcher(trimmed).matches()) {
            return "";
        }
        return trimmed;
    }

    /**
     * Detects the normalized lowercase protocol tag for a {@link Request}
     * ({@code http}, {@code casual}, {@code grpc}, {@code kafka}, {@code amqp}, {@code async}, etc.).
     *
     * @param request incoming WireMock request
     * @return lowercase protocol identifier
     */
    public static String detectProtocol(Request request) {
        if (request == null) {
            return "http";
        }
        HttpHeader explicitProtocol = findHeader(request, "X-WireMock-Protocol");
        if (explicitProtocol != null && explicitProtocol.isPresent() && explicitProtocol.firstValue() != null) {
            String value = explicitProtocol.firstValue().trim().toLowerCase(Locale.ROOT);
            if (!value.isEmpty()) {
                return value;
            }
        }

        String url = request.getUrl();
        if (hasHeader(request, "X-Casual-Service")
                || hasHeader(request, "X-Casual-Queue")
                || (url != null && url.startsWith("/__casual/"))) {
            return "casual";
        }

        if (hasHeader(request, "X-Async-Channel") || (url != null && url.startsWith("/__async/"))) {
            HttpHeader asyncProto = findHeader(request, "X-Async-Protocol");
            if (asyncProto != null && asyncProto.isPresent() && asyncProto.firstValue() != null) {
                String value = asyncProto.firstValue().trim().toLowerCase(Locale.ROOT);
                if (!value.isEmpty()) {
                    return value;
                }
            }
            if (url != null && url.startsWith("/__async/")) {
                String sub = url.substring("/__async/".length());
                int slashIdx = sub.indexOf('/');
                if (slashIdx > 0) {
                    return sub.substring(0, slashIdx).toLowerCase(Locale.ROOT);
                }
            }
            return "async";
        }

        HttpHeader contentType = findHeader(request, "Content-Type");
        if ((contentType != null
                        && contentType.isPresent()
                        && contentType.firstValue() != null
                        && contentType.firstValue().toLowerCase(Locale.ROOT).contains("application/grpc"))
                || hasHeader(request, "X-Grpc-Service")) {
            return "grpc";
        }

        return "http";
    }

    /**
     * Returns whether a {@link ServeEvent} was handled by the stateful AST extension.
     *
     * @param event serve event
     * @return {@code true} if {@code X-WireMock-Stateful: true} is present on the response
     */
    public static boolean isStateful(ServeEvent event) {
        if (event == null) {
            return false;
        }
        HttpHeaders resHeaders =
                event.getResponse() != null ? event.getResponse().getHeaders() : null;
        if (resHeaders == null && event.getResponseDefinition() != null) {
            resHeaders = event.getResponseDefinition().getHeaders();
        }
        if (resHeaders == null) {
            return false;
        }
        HttpHeader stateful = resHeaders.getHeader("X-WireMock-Stateful");
        return stateful != null && stateful.isPresent() && "true".equalsIgnoreCase(stateful.firstValue());
    }

    /**
     * Returns whether a {@link ServeEvent} produced a WireMock simulated fault.
     *
     * @param event serve event
     * @return {@code true} if response definition or response carries a fault
     */
    public static boolean isFault(ServeEvent event) {
        if (event == null) {
            return false;
        }
        ResponseDefinition respDef = event.getResponseDefinition();
        LoggedResponse response = event.getResponse();
        return (respDef != null && respDef.getFault() != null) || (response != null && response.getFault() != null);
    }

    /**
     * Returns whether a {@link ServeEvent} was forwarded to a live upstream proxy target.
     *
     * @param event serve event
     * @return {@code true} if proxied
     */
    public static boolean isProxied(ServeEvent event) {
        if (event == null) {
            return false;
        }
        ResponseDefinition respDef = event.getResponseDefinition();
        StubMapping stubMapping = event.getStubMapping();
        return (respDef != null && respDef.isProxyResponse())
                || (respDef != null
                        && respDef.getProxyBaseUrl() != null
                        && !respDef.getProxyBaseUrl().isBlank())
                || (stubMapping != null
                        && stubMapping.getResponse() != null
                        && stubMapping.getResponse().isProxyResponse());
    }

    /**
     * Extracts the upstream proxy base URL for a proxied {@link ServeEvent}, or {@code null}.
     *
     * @param event serve event
     * @return proxy target base URL or {@code null}
     */
    public static String extractTargetUpstream(ServeEvent event) {
        if (event == null) {
            return null;
        }
        ResponseDefinition respDef = event.getResponseDefinition();
        if (respDef != null
                && respDef.getProxyBaseUrl() != null
                && !respDef.getProxyBaseUrl().isBlank()) {
            return respDef.getProxyBaseUrl();
        }
        StubMapping stubMapping = event.getStubMapping();
        if (stubMapping != null
                && stubMapping.getResponse() != null
                && stubMapping.getResponse().getProxyBaseUrl() != null
                && !stubMapping.getResponse().getProxyBaseUrl().isBlank()) {
            return stubMapping.getResponse().getProxyBaseUrl();
        }
        return null;
    }

    /**
     * Classifies a {@link ServeEvent} into OTel/JSON-log traffic type categories:
     * {@code "fault"}, {@code "proxied"}, {@code "stubbed"}, or {@code "unmatched"}.
     *
     * @param event serve event
     * @return traffic type classification
     */
    public static String classifyTrafficType(ServeEvent event) {
        if (event == null) {
            return "unmatched";
        }
        if (isFault(event)) {
            return "fault";
        }
        if (isProxied(event)) {
            return "proxied";
        }
        if (event.getWasMatched()) {
            return "stubbed";
        }
        return "unmatched";
    }

    /**
     * Classifies a {@link ServeEvent} into UI journal filter verdict categories:
     * {@code "stateful"}, {@code "unmatched"}, {@code "proxied"}, or {@code "stub"}.
     *
     * @param event serve event
     * @return journal verdict key
     */
    public static String classifyJournalVerdict(ServeEvent event) {
        if (event == null) {
            return "unmatched";
        }
        if (isStateful(event)) {
            return "stateful";
        }
        if (!event.getWasMatched()) {
            return "unmatched";
        }
        if (isProxied(event)) {
            return "proxied";
        }
        return "stub";
    }

    private static HttpHeader findHeader(Request request, String name) {
        HttpHeaders headers = request.getHeaders();
        if (headers != null) {
            return headers.getHeader(name);
        }
        return request.header(name);
    }

    private static boolean hasHeader(Request request, String name) {
        HttpHeader header = findHeader(request, name);
        return header != null && header.isPresent();
    }
}
