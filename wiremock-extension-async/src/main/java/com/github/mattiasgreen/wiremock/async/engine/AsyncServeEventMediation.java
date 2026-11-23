package com.github.mattiasgreen.wiremock.async.engine;

import com.github.mattiasgreen.wiremock.async.model.AsyncMessage;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.DataTruncationSettings;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.ImmutableRequest;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.Response;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Encapsulates bidirectional synthesis of authentic WireMock {@link ServeEvent} instances
 * for asynchronous Kafka, AMQP, and bridged queue messages.
 */
public final class AsyncServeEventMediation {

    public static final String HEADER_ASYNC_PROTOCOL = "X-Async-Protocol";
    public static final String HEADER_ASYNC_CHANNEL = "X-Async-Channel";
    public static final String HEADER_ASYNC_MESSAGE_ID = "X-Async-Message-Id";
    public static final String HEADER_ASYNC_ERROR = "X-Async-Error";
    public static final String HEADER_LEGACY_PROTOCOL = "X-WireMock-Async-Protocol";
    public static final String HEADER_LEGACY_DESTINATION = "X-Async-Destination";

    private AsyncServeEventMediation() {}

    public static ImmutableRequest buildSyntheticRequest(
            String protocol,
            String destination,
            String messageId,
            String key,
            String correlationId,
            String replyTo,
            Map<String, String> customHeaders,
            String payload) {
        String safeProtocol = (protocol != null && !protocol.isBlank()) ? protocol.toLowerCase(Locale.ROOT) : "async";
        String safeDest = (destination != null && !destination.isBlank()) ? destination : "unknown";
        String url = "/__async/" + safeProtocol + "/" + safeDest;
        String absoluteUrl = "http://localhost:8080" + url;

        String safePayload = (payload != null) ? payload : "";
        boolean isJson =
                safePayload.trim().startsWith("{") || safePayload.trim().startsWith("[");

        List<HttpHeader> reqHeadersList = new ArrayList<>();
        reqHeadersList.add(new HttpHeader("Content-Type", isJson ? "application/json" : "text/plain; charset=UTF-8"));
        reqHeadersList.add(new HttpHeader(HEADER_ASYNC_PROTOCOL, safeProtocol));
        reqHeadersList.add(new HttpHeader(HEADER_ASYNC_CHANNEL, safeDest));
        reqHeadersList.add(new HttpHeader(HEADER_LEGACY_PROTOCOL, safeProtocol));
        reqHeadersList.add(new HttpHeader(HEADER_LEGACY_DESTINATION, safeDest));

        if (messageId != null && !messageId.isBlank()) {
            reqHeadersList.add(new HttpHeader(HEADER_ASYNC_MESSAGE_ID, messageId));
        }
        if (key != null && !key.isBlank()) {
            reqHeadersList.add(new HttpHeader("X-Async-Key", key));
        }
        if (correlationId != null && !correlationId.isBlank()) {
            reqHeadersList.add(new HttpHeader("X-Async-Correlation-Id", correlationId));
        }
        if (replyTo != null && !replyTo.isBlank()) {
            reqHeadersList.add(new HttpHeader("X-Async-Reply-To", replyTo));
        }
        if (customHeaders != null) {
            for (Map.Entry<String, String> entry : customHeaders.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    reqHeadersList.add(new HttpHeader(entry.getKey(), entry.getValue()));
                }
            }
        }

        HttpHeaders requestHeaders = new HttpHeaders(reqHeadersList);

        return ImmutableRequest.create()
                .withMethod(RequestMethod.POST)
                .withAbsoluteUrl(absoluteUrl)
                .withHeaders(requestHeaders)
                .withBody(safePayload.getBytes(StandardCharsets.UTF_8))
                .build();
    }

    public static ServeEvent buildServeEvent(
            String protocol,
            String destination,
            String key,
            String correlationId,
            String replyTo,
            Map<String, String> customHeaders,
            String payload,
            int statusCode,
            String responseBody,
            String proxiedFrom,
            StubMapping stubMapping) {
        return buildServeEvent(
                protocol,
                destination,
                null,
                key,
                correlationId,
                replyTo,
                customHeaders,
                payload,
                statusCode,
                responseBody,
                proxiedFrom,
                null,
                stubMapping);
    }

    public static ServeEvent buildServeEvent(
            String protocol,
            String destination,
            String messageId,
            String key,
            String correlationId,
            String replyTo,
            Map<String, String> customHeaders,
            String payload,
            int statusCode,
            String responseBody,
            String proxiedFrom,
            String asyncError,
            StubMapping stubMapping) {

        String safeProtocol = (protocol != null && !protocol.isBlank()) ? protocol.toLowerCase(Locale.ROOT) : "async";
        String safeDest = (destination != null && !destination.isBlank()) ? destination : "unknown";

        ImmutableRequest syntheticRequest = buildSyntheticRequest(
                safeProtocol, safeDest, messageId, key, correlationId, replyTo, customHeaders, payload);

        String safeRespBody = (responseBody != null) ? responseBody : "";
        boolean isRespJson =
                safeRespBody.trim().startsWith("{") || safeRespBody.trim().startsWith("[");

        List<HttpHeader> respHeadersList = new ArrayList<>();
        respHeadersList.add(
                new HttpHeader("Content-Type", isRespJson ? "application/json" : "text/plain; charset=UTF-8"));
        respHeadersList.add(new HttpHeader(HEADER_ASYNC_PROTOCOL, safeProtocol));
        respHeadersList.add(new HttpHeader(HEADER_ASYNC_CHANNEL, safeDest));
        respHeadersList.add(new HttpHeader(HEADER_LEGACY_PROTOCOL, safeProtocol));
        respHeadersList.add(new HttpHeader(HEADER_LEGACY_DESTINATION, safeDest));
        if (messageId != null && !messageId.isBlank()) {
            respHeadersList.add(new HttpHeader(HEADER_ASYNC_MESSAGE_ID, messageId));
        }
        if (proxiedFrom != null && !proxiedFrom.isBlank()) {
            respHeadersList.add(new HttpHeader("X-Async-Bridged-To", proxiedFrom));
        }
        if (asyncError != null && !asyncError.isBlank()) {
            respHeadersList.add(new HttpHeader(HEADER_ASYNC_ERROR, asyncError));
        }

        HttpHeaders responseHeaders = new HttpHeaders(respHeadersList);

        ResponseDefinitionBuilder respDefBuilder = ResponseDefinitionBuilder.responseDefinition()
                .withStatus(statusCode)
                .withHeaders(responseHeaders);

        if (proxiedFrom != null && !proxiedFrom.isBlank()) {
            respDefBuilder.proxiedFrom(proxiedFrom);
        }
        if (!safeRespBody.isBlank()) {
            respDefBuilder.withBody(safeRespBody);
        }
        ResponseDefinition responseDef = respDefBuilder.build();

        Response.Builder respBuilder = Response.response()
                .status(statusCode)
                .headers(responseHeaders)
                .body(safeRespBody.getBytes(StandardCharsets.UTF_8));

        ServeEvent serveEvent = ServeEvent.of(syntheticRequest).withResponseDefinition(responseDef);
        if (stubMapping != null) {
            serveEvent = serveEvent.withStubMapping(stubMapping);
        }

        serveEvent.beforeSend();
        serveEvent.afterSend();
        return serveEvent.complete(respBuilder.build(), DataTruncationSettings.DEFAULTS);
    }

    public static ServeEvent fromAsyncMessage(
            AsyncMessage msg, int statusCode, String responseBody, String proxiedFrom, StubMapping stub) {
        return fromAsyncMessage(msg, statusCode, responseBody, proxiedFrom, null, stub);
    }

    public static ServeEvent fromAsyncMessage(
            AsyncMessage msg,
            int statusCode,
            String responseBody,
            String proxiedFrom,
            String asyncError,
            StubMapping stub) {
        if (msg == null) {
            return null;
        }
        return buildServeEvent(
                msg.protocol(),
                msg.destination(),
                msg.id(),
                msg.key(),
                msg.correlationId(),
                msg.replyTo(),
                msg.headers(),
                msg.payload(),
                statusCode,
                responseBody,
                proxiedFrom,
                asyncError,
                stub);
    }
}
