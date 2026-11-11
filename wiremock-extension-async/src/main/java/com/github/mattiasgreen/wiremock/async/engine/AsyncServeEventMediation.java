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

    private AsyncServeEventMediation() {}

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

        String safeProtocol = (protocol != null && !protocol.isBlank()) ? protocol.toLowerCase(Locale.ROOT) : "async";
        String safeDest = (destination != null) ? destination : "unknown";
        String url = "/__async/" + safeProtocol + "/" + safeDest;
        String absoluteUrl = "http://localhost:8080" + url;

        String safePayload = (payload != null) ? payload : "";
        boolean isJson =
                safePayload.trim().startsWith("{") || safePayload.trim().startsWith("[");

        List<HttpHeader> reqHeadersList = new ArrayList<>();
        reqHeadersList.add(new HttpHeader("Content-Type", isJson ? "application/json" : "text/plain; charset=UTF-8"));
        reqHeadersList.add(new HttpHeader("X-WireMock-Async-Protocol", safeProtocol));
        reqHeadersList.add(new HttpHeader("X-Async-Destination", safeDest));

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

        ImmutableRequest.Builder reqBuilder = ImmutableRequest.create()
                .withMethod(RequestMethod.POST)
                .withAbsoluteUrl(absoluteUrl)
                .withHeaders(requestHeaders)
                .withBody(safePayload.getBytes(StandardCharsets.UTF_8));

        String safeRespBody = (responseBody != null) ? responseBody : "";
        boolean isRespJson =
                safeRespBody.trim().startsWith("{") || safeRespBody.trim().startsWith("[");

        List<HttpHeader> respHeadersList = new ArrayList<>();
        respHeadersList.add(
                new HttpHeader("Content-Type", isRespJson ? "application/json" : "text/plain; charset=UTF-8"));
        respHeadersList.add(new HttpHeader("X-WireMock-Async-Protocol", safeProtocol));
        respHeadersList.add(new HttpHeader("X-Async-Destination", safeDest));
        if (proxiedFrom != null && !proxiedFrom.isBlank()) {
            respHeadersList.add(new HttpHeader("X-Async-Bridged-To", proxiedFrom));
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

        ServeEvent serveEvent = ServeEvent.of(reqBuilder.build()).withResponseDefinition(responseDef);
        if (stubMapping != null) {
            serveEvent = serveEvent.withStubMapping(stubMapping);
        }

        serveEvent.beforeSend();
        serveEvent.afterSend();
        return serveEvent.complete(respBuilder.build(), DataTruncationSettings.DEFAULTS);
    }

    public static ServeEvent fromAsyncMessage(
            AsyncMessage msg, int statusCode, String responseBody, String proxiedFrom, StubMapping stub) {
        if (msg == null) {
            return null;
        }
        return buildServeEvent(
                msg.protocol(),
                msg.destination(),
                msg.key(),
                msg.correlationId(),
                msg.replyTo(),
                msg.headers(),
                msg.payload(),
                statusCode,
                responseBody,
                proxiedFrom,
                stub);
    }
}
