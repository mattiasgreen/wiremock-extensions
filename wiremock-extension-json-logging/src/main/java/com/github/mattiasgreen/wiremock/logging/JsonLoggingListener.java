package com.github.mattiasgreen.wiremock.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ServeEventListener;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.LoggedResponse;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Formats every served request/response into a clean, single-line structured JSON log event,
 * with verbose payload formatting and MDC integration.
 */
public class JsonLoggingListener implements ServeEventListener {

    public static final String LISTENER_NAME = "json-logging-listener";
    private static final Logger LOG = LoggerFactory.getLogger("wiremock.traffic");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final boolean verbose;

    public JsonLoggingListener() {
        this(resolveVerboseSetting());
    }

    public JsonLoggingListener(boolean verbose) {
        this.verbose = verbose;
    }

    private static boolean resolveVerboseSetting() {
        String prop = System.getProperty("wiremock.verbose.json");
        if (prop != null) {
            return Boolean.parseBoolean(prop);
        }
        String env = System.getenv("WIREMOCK_VERBOSE_JSON");
        if (env != null) {
            return Boolean.parseBoolean(env);
        }
        String genericProp = System.getProperty("wiremock.verbose");
        return genericProp != null && Boolean.parseBoolean(genericProp);
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
        try {
            WireMockLogEvent event = buildLogEvent(serveEvent);

            Map<String, String> mdcContext = MDC.getCopyOfContextMap();
            if (mdcContext != null) {
                event.setMdcTags(new HashMap<>(mdcContext));
            }

            try {
                String jsonOutput = OBJECT_MAPPER.writeValueAsString(event);
                LOG.info(jsonOutput);
            } catch (JsonProcessingException e) {
                LOG.error("Failed to serialize WireMockLogEvent to JSON", e);
            }
        } finally {
            MDC.clear();
        }
    }

    private WireMockLogEvent buildLogEvent(ServeEvent serveEvent) {
        WireMockLogEvent event = new WireMockLogEvent();

        if (serveEvent.getTiming() != null) {
            event.setDurationMs(serveEvent.getTiming().getTotalTime());
        }

        boolean matched = serveEvent.getWasMatched();
        event.setMatched(matched);

        StubMapping stubMapping = serveEvent.getStubMapping();
        if (stubMapping != null) {
            if (stubMapping.getId() != null) {
                event.setStubId(stubMapping.getId().toString());
            }
            event.setStubName(stubMapping.getName());
        }

        String mdcTraceId = MDC.get("trace_id");
        if (mdcTraceId != null && !mdcTraceId.isBlank()) {
            event.setTraceId(mdcTraceId);
        }
        String mdcSpanId = MDC.get("span_id");
        if (mdcSpanId != null && !mdcSpanId.isBlank()) {
            event.setSpanId(mdcSpanId);
        }

        Request request = serveEvent.getRequest();
        if (request != null) {
            WireMockLogEvent.RequestDetails reqDetails = new WireMockLogEvent.RequestDetails();
            reqDetails.setMethod(request.getMethod().getName());
            reqDetails.setUrl(request.getUrl());
            reqDetails.setClientIp(request.getClientIp());

            if (verbose) {
                reqDetails.setHeaders(extractHeadersMap(request.getHeaders()));
                reqDetails.setBody(request.getBodyAsString());
            }
            event.setRequest(reqDetails);
        }

        LoggedResponse response = serveEvent.getResponse();
        if (response != null) {
            WireMockLogEvent.ResponseDetails respDetails = new WireMockLogEvent.ResponseDetails();
            respDetails.setStatus(response.getStatus());

            if (verbose) {
                respDetails.setHeaders(extractHeadersMap(response.getHeaders()));
                respDetails.setBody(response.getBodyAsString());
            }
            event.setResponse(respDetails);
        }

        if (!matched) {
            event.setUnmatchedReason("No stub mapping matched the incoming request");
        }

        return event;
    }

    private Map<String, String> extractHeadersMap(HttpHeaders httpHeaders) {
        if (httpHeaders == null) {
            return Map.of();
        }
        Map<String, String> map = new HashMap<>();
        for (HttpHeader header : httpHeaders.all()) {
            map.put(header.key(), header.firstValue());
        }
        return map;
    }

    public boolean isVerbose() {
        return verbose;
    }
}
