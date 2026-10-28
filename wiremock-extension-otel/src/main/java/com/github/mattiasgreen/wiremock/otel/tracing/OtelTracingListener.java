package com.github.mattiasgreen.wiremock.otel.tracing;

import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ServeEventListener;
import com.github.tomakehurst.wiremock.http.LoggedResponse;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.MDC;

/**
 * ServeEventListener that manages the OpenTelemetry SERVER span lifecycle for every served request.
 * Extracts W3C trace context, applies HTTP semantic attributes, and records errors.
 */
public class OtelTracingListener implements ServeEventListener {

    public static final String LISTENER_NAME = "otel-tracing-listener";

    private static final AttributeKey<String> HTTP_REQUEST_METHOD = AttributeKey.stringKey("http.request.method");
    private static final AttributeKey<Long> HTTP_RESPONSE_STATUS_CODE =
            AttributeKey.longKey("http.response.status_code");
    private static final AttributeKey<String> URL_PATH = AttributeKey.stringKey("url.path");
    private static final AttributeKey<String> URL_QUERY = AttributeKey.stringKey("url.query");
    private static final AttributeKey<String> URL_FULL = AttributeKey.stringKey("url.full");
    private static final AttributeKey<String> CLIENT_ADDRESS = AttributeKey.stringKey("client.address");
    private static final AttributeKey<Boolean> WIREMOCK_MATCHED = AttributeKey.booleanKey("wiremock.matched");
    private static final AttributeKey<String> WIREMOCK_STUB_ID = AttributeKey.stringKey("wiremock.stub_id");
    private static final AttributeKey<String> WIREMOCK_STUB_NAME = AttributeKey.stringKey("wiremock.stub_name");
    private static final AttributeKey<String> WIREMOCK_SCENARIO_NAME = AttributeKey.stringKey("wiremock.scenario_name");
    private static final AttributeKey<String> WIREMOCK_SCENARIO_STATE =
            AttributeKey.stringKey("wiremock.scenario_state");
    private static final AttributeKey<String> WIREMOCK_TRAFFIC_TYPE = AttributeKey.stringKey("wiremock.traffic_type");
    private static final AttributeKey<String> WIREMOCK_PROTOCOL = AttributeKey.stringKey("wiremock.protocol");
    private static final AttributeKey<String> WIREMOCK_TARGET_UPSTREAM =
            AttributeKey.stringKey("wiremock.target_upstream");
    private static final AttributeKey<String> RPC_SYSTEM = AttributeKey.stringKey("rpc.system");
    private static final AttributeKey<String> RPC_SERVICE = AttributeKey.stringKey("rpc.service");
    private static final AttributeKey<String> RPC_METHOD = AttributeKey.stringKey("rpc.method");
    private static final AttributeKey<String> CASUAL_ERROR_STATE = AttributeKey.stringKey("casual.error_state");
    private static final AttributeKey<String> CASUAL_XID = AttributeKey.stringKey("casual.xid");
    private static final AttributeKey<String> CASUAL_XID_GTRID = AttributeKey.stringKey("casual.xid.gtrid");
    private static final AttributeKey<String> CASUAL_XID_BQUAL = AttributeKey.stringKey("casual.xid.bqual");
    private static final AttributeKey<Long> CASUAL_XID_FORMAT_ID = AttributeKey.longKey("casual.xid.format_id");

    private final OtelTracingRegistry tracingRegistry;

    public OtelTracingListener() {
        this(OtelTracingRegistry.getInstance());
    }

    public OtelTracingListener(OtelTracingRegistry tracingRegistry) {
        this.tracingRegistry = Objects.requireNonNull(tracingRegistry, "tracingRegistry must not be null");
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
        if (!tracingRegistry.isTracingEnabled() || serveEvent == null) {
            return;
        }

        Request request = serveEvent.getRequest();
        if (request == null) {
            return;
        }

        String protocol = detectProtocol(request);
        String rpcService = "casual".equals(protocol) ? detectCasualRpcService(request) : null;

        Context extractedContext = tracingRegistry
                .getOpenTelemetry()
                .getPropagators()
                .getTextMapPropagator()
                .extract(Context.current(), request, WireMockRequestTextMapGetter.INSTANCE);

        String spanName;
        if ("casual".equals(protocol) && rpcService != null) {
            spanName = "casual." + rpcService;
        } else {
            String method = request.getMethod() != null ? request.getMethod().getName() : "HTTP";
            spanName = method + " " + sanitizeSpanPath(request.getUrl());
        }

        Span span = tracingRegistry
                .getTracer()
                .spanBuilder(spanName)
                .setParent(extractedContext)
                .setSpanKind(SpanKind.SERVER)
                .startSpan();

        try {
            String traceId = span.getSpanContext().getTraceId();
            String spanId = span.getSpanContext().getSpanId();
            if (traceId != null && !traceId.isBlank()) {
                MDC.put("trace_id", traceId);
            }
            if (spanId != null && !spanId.isBlank()) {
                MDC.put("span_id", spanId);
            }

            if (!"casual".equals(protocol)) {
                String method =
                        request.getMethod() != null ? request.getMethod().getName() : "HTTP";
                span.setAttribute(HTTP_REQUEST_METHOD, method);
                span.setAttribute(URL_FULL, request.getAbsoluteUrl());
                span.setAttribute(URL_PATH, sanitizeSpanPath(request.getUrl()));

                String query = extractQuery(request.getUrl());
                if (query != null && !query.isEmpty()) {
                    span.setAttribute(URL_QUERY, query);
                }
            }

            if (request.getClientIp() != null) {
                span.setAttribute(CLIENT_ADDRESS, request.getClientIp());
            }

            enrichSpanWithCompletion(span, serveEvent, protocol, rpcService);
        } finally {
            span.end();
            MDC.remove("trace_id");
            MDC.remove("span_id");
        }
    }

    private void enrichSpanWithCompletion(Span span, ServeEvent serveEvent, String protocol, String rpcService) {
        LoggedResponse response = serveEvent.getResponse();
        int statusCode = response != null ? response.getStatus() : 0;
        if (statusCode > 0) {
            span.setAttribute(HTTP_RESPONSE_STATUS_CODE, (long) statusCode);
            if (statusCode >= 500) {
                span.setStatus(StatusCode.ERROR, "HTTP " + statusCode);
            }
        }

        boolean matched = serveEvent.getWasMatched();
        span.setAttribute(WIREMOCK_MATCHED, matched);

        if (!matched && statusCode == 404) {
            span.setStatus(StatusCode.ERROR, "Request unmatched by any WireMock stub");
        }

        StubMapping stub = serveEvent.getStubMapping();
        if (stub != null) {
            if (stub.getId() != null) {
                span.setAttribute(WIREMOCK_STUB_ID, stub.getId().toString());
            }
            if (stub.getName() != null) {
                span.setAttribute(WIREMOCK_STUB_NAME, stub.getName());
            }
            if (stub.getScenarioName() != null) {
                span.setAttribute(WIREMOCK_SCENARIO_NAME, stub.getScenarioName());
            }
            if (stub.getRequiredScenarioState() != null) {
                span.setAttribute(WIREMOCK_SCENARIO_STATE, stub.getRequiredScenarioState());
            }
        }

        ResponseDefinition respDef = serveEvent.getResponseDefinition();
        boolean isFault =
                (respDef != null && respDef.getFault() != null) || (response != null && response.getFault() != null);

        boolean isProxied = (respDef != null && respDef.isProxyResponse())
                || (stub != null
                        && stub.getResponse() != null
                        && stub.getResponse().isProxyResponse());

        String targetUpstream = null;
        if (respDef != null
                && respDef.getProxyBaseUrl() != null
                && !respDef.getProxyBaseUrl().isBlank()) {
            targetUpstream = respDef.getProxyBaseUrl();
        } else if (stub != null
                && stub.getResponse() != null
                && stub.getResponse().getProxyBaseUrl() != null
                && !stub.getResponse().getProxyBaseUrl().isBlank()) {
            targetUpstream = stub.getResponse().getProxyBaseUrl();
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
        span.setAttribute(WIREMOCK_TRAFFIC_TYPE, trafficType);
        if (targetUpstream != null) {
            span.setAttribute(WIREMOCK_TARGET_UPSTREAM, targetUpstream);
        }

        Request request = serveEvent.getRequest();
        span.setAttribute(WIREMOCK_PROTOCOL, protocol);

        if ("casual".equals(protocol) && request != null) {
            span.setAttribute(RPC_SYSTEM, "casual");
            span.setAttribute(RPC_METHOD, "tpcall");
            if (rpcService != null) {
                span.setAttribute(RPC_SERVICE, rpcService);
            }
            if (response != null
                    && response.getHeaders() != null
                    && response.getHeaders().getHeader("X-Casual-Error-State").isPresent()) {
                String err =
                        response.getHeaders().getHeader("X-Casual-Error-State").firstValue();
                span.setAttribute(CASUAL_ERROR_STATE, err);
                if (!"OK".equalsIgnoreCase(err)) {
                    span.setStatus(StatusCode.ERROR, "Casual error state: " + err);
                }
            }
            if (request.header("X-Casual-Xid").isPresent()) {
                span.setAttribute(CASUAL_XID, request.header("X-Casual-Xid").firstValue());
            }
            if (request.header("X-Casual-Gtrid").isPresent()) {
                span.setAttribute(
                        CASUAL_XID_GTRID, request.header("X-Casual-Gtrid").firstValue());
            }
            if (request.header("X-Casual-Bqual").isPresent()) {
                span.setAttribute(
                        CASUAL_XID_BQUAL, request.header("X-Casual-Bqual").firstValue());
            }
            if (request.header("X-Casual-Format-Id").isPresent()) {
                try {
                    span.setAttribute(
                            CASUAL_XID_FORMAT_ID,
                            Long.parseLong(request.header("X-Casual-Format-Id").firstValue()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    private String detectProtocol(Request request) {
        if (request == null) {
            return "http";
        }
        if (request.header("X-WireMock-Protocol").isPresent()) {
            return request.header("X-WireMock-Protocol").firstValue().toLowerCase(Locale.ROOT);
        }
        if (request.header("X-Casual-Service").isPresent()
                || (request.getUrl() != null && request.getUrl().startsWith("/__casual/"))) {
            return "casual";
        }
        if ((request.header("Content-Type").isPresent()
                        && request.header("Content-Type").firstValue().contains("application/grpc"))
                || request.header("X-Grpc-Service").isPresent()) {
            return "grpc";
        }
        return "http";
    }

    private String detectCasualRpcService(Request request) {
        if (request == null) {
            return null;
        }
        if (request.header("X-Casual-Service").isPresent()) {
            return request.header("X-Casual-Service").firstValue();
        }
        if (request.getUrl() != null && request.getUrl().startsWith("/__casual/services/")) {
            String rpcService = request.getUrl().substring("/__casual/services/".length());
            int slashIdx = rpcService.indexOf('/');
            int queryIdx = rpcService.indexOf('?');
            int end = rpcService.length();
            if (slashIdx >= 0) {
                end = Math.min(end, slashIdx);
            }
            if (queryIdx >= 0) {
                end = Math.min(end, queryIdx);
            }
            return rpcService.substring(0, end);
        }
        return null;
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
