package com.github.mattiasgreen.wiremock.casual;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.DataTruncationSettings;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.ImmutableRequest;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.Response;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import se.laz.casual.api.buffer.type.ServiceBuffer;
import se.laz.casual.api.flags.ErrorState;
import se.laz.casual.api.flags.TransactionState;
import se.laz.casual.api.xa.XAReturnCode;
import se.laz.casual.network.ProtocolVersion;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallReplyMessage;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallRequestMessage;

/**
 * Deep module encapsulating all translation and synthesis between binary Casual XATMI protocol
 * structures and WireMock HTTP requests, responses, headers, and {@link ServeEvent} instances.
 */
public final class CasualHttpMediation {

    private CasualHttpMediation() {}

    /**
     * Constructs an in-memory HTTP probe request for WireMock stub matching.
     */
    public static ImmutableRequest buildProbeRequest(String baseUrl, CasualServiceCallRequestMessage req) {
        String serviceName = req.getServiceName();
        ServiceBuffer requestBuffer = req.getServiceBuffer();
        String bufferType = (requestBuffer != null && !requestBuffer.isEmpty()) ? requestBuffer.getType() : "JSON";
        String requestPayload = CasualBufferUtils.extractPayload(requestBuffer);
        String executionId = (req.getExecution() != null ? req.getExecution() : UUID.randomUUID()).toString();

        String resolvedBase = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl : "http://localhost:8080";
        String absoluteUrl = resolvedBase + "/__casual/services/" + serviceName;

        return ImmutableRequest.create()
                .withMethod(RequestMethod.POST)
                .withAbsoluteUrl(absoluteUrl)
                .withHeader(
                        "Content-Type",
                        CasualBufferUtils.isJsonType(bufferType) ? "application/json" : "application/octet-stream")
                .withHeader("X-Casual-Service", serviceName)
                .withHeader("X-Casual-Buffer-Type", bufferType)
                .withHeader("X-Casual-Execution", executionId)
                .withBody(requestPayload.getBytes(StandardCharsets.UTF_8))
                .build();
    }

    public record ExecutionResult(
            CasualServiceCallReplyMessage reply,
            ServeEvent serveEvent,
            Optional<XAReturnCode> prepareVote,
            Optional<XAReturnCode> commitVote,
            Optional<XAReturnCode> rollbackVote) {}

    /**
     * Translates a matched WireMock mock response into a {@link CasualServiceCallReplyMessage} and {@link ServeEvent}.
     */
    public static ExecutionResult translateOfflineStub(
            CasualServiceCallRequestMessage req, StubMapping stub, Request wiremockRequest, CasualStorePort storePort) {
        ResponseDefinition responseDef = stub.getResponse();
        int statusCode = responseDef.getStatus();
        ErrorState errorState = ErrorState.OK;
        long userSuppliedError = 0;
        TransactionState transactionState = TransactionState.TX_ACTIVE;
        ServiceBuffer replyBuffer = ServiceBuffer.empty();

        HttpHeaders headers = responseDef.getHeaders();
        if (headers != null
                && headers.getHeader("X-Casual-Error-State") != null
                && headers.getHeader("X-Casual-Error-State").isPresent()) {
            try {
                errorState = ErrorState.valueOf(headers.getHeader("X-Casual-Error-State")
                        .firstValue()
                        .trim()
                        .toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errorState = (statusCode >= 200 && statusCode < 300) ? ErrorState.OK : ErrorState.TPESVCFAIL;
            }
        } else if (statusCode < 200 || statusCode >= 300) {
            if (statusCode == 400) errorState = ErrorState.TPEINVAL;
            else if (statusCode == 404) errorState = ErrorState.TPENOENT;
            else if (statusCode == 408 || statusCode == 504) errorState = ErrorState.TPETIME;
            else if (statusCode == 429) errorState = ErrorState.TPELIMIT;
            else if (statusCode == 502 || statusCode == 503) errorState = ErrorState.TPESVCERR;
            else errorState = ErrorState.TPESVCFAIL;
        }

        if (headers != null) {
            HttpHeader userHeader = headers.getHeader("X-Casual-User-Supplied-Error");
            if (userHeader == null || !userHeader.isPresent()) {
                userHeader = headers.getHeader("X-Casual-User-Code");
            }
            if (userHeader == null || !userHeader.isPresent()) {
                userHeader = headers.getHeader("X-Casual-User-Defined-Code");
            }
            if (userHeader != null && userHeader.isPresent()) {
                try {
                    userSuppliedError = Long.parseLong(userHeader.firstValue().trim());
                } catch (NumberFormatException ignored) {
                }
            }

            HttpHeader txHeader = headers.getHeader("X-Casual-Transaction-State");
            if (txHeader != null && txHeader.isPresent()) {
                try {
                    transactionState = TransactionState.valueOf(
                            txHeader.firstValue().trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ignored) {
                }
            } else if (errorState != ErrorState.OK) {
                transactionState = TransactionState.ROLLBACK_ONLY;
            }
        } else if (errorState != ErrorState.OK) {
            transactionState = TransactionState.ROLLBACK_ONLY;
        }

        Optional<XAReturnCode> prepareVote = extractVoteOverride(headers, "X-Casual-XA-Prepare-Vote");
        Optional<XAReturnCode> commitVote = extractVoteOverride(headers, "X-Casual-XA-Commit-Vote");
        Optional<XAReturnCode> rollbackVote = extractVoteOverride(headers, "X-Casual-XA-Rollback-Vote");

        byte[] rawByteBody = responseDef.getByteBody();
        String respBody = responseDef.getBody();
        if (respBody == null && responseDef.getBase64Body() != null) {
            respBody = responseDef.getBase64Body();
        }
        if (respBody == null && responseDef.getReponseBody() != null) {
            respBody = responseDef.getReponseBody().asString();
        }
        if (respBody == null && responseDef.getBodyFileName() != null && storePort != null) {
            respBody = storePort.readBodyFromFile(responseDef.getBodyFileName());
        }

        String requestBufferType =
                (req.getServiceBuffer() != null && !req.getServiceBuffer().isEmpty())
                        ? req.getServiceBuffer().getType()
                        : "JSON";
        String replyBufferType = (headers != null
                        && headers.getHeader("X-Casual-Buffer-Type") != null
                        && headers.getHeader("X-Casual-Buffer-Type").isPresent())
                ? headers.getHeader("X-Casual-Buffer-Type").firstValue()
                : requestBufferType;

        if (respBody != null && !respBody.isBlank()) {
            replyBuffer = CasualBufferUtils.wrapPayload(replyBufferType, respBody);
        } else if (rawByteBody != null && rawByteBody.length > 0) {
            replyBuffer = CasualBufferUtils.wrapPayload(replyBufferType, rawByteBody);
        }

        ServeEvent serveEvent = buildOfflineServeEvent(stub, wiremockRequest, responseDef, respBody);

        ProtocolVersion version = ProtocolVersion.VERSION_1_0;
        CasualServiceCallReplyMessage.Builder replyBuilder = CasualServiceCallReplyMessage.createBuilder()
                .setProtocolVersion(version)
                .setExecution(req.getExecution())
                .setError(errorState)
                .setUserSuppliedError(userSuppliedError)
                .setTransactionState(transactionState)
                .setServiceBuffer(replyBuffer);
        if (req.getXid() != null) {
            replyBuilder.setXid(req.getXid());
        }
        return new ExecutionResult(replyBuilder.build(), serveEvent, prepareVote, commitVote, rollbackVote);
    }

    public static ServeEvent buildOfflineServeEvent(
            StubMapping stub, Request wiremockRequest, ResponseDefinition responseDef, String body) {
        Response.Builder responseBuilder =
                Response.response().status(responseDef.getStatus()).headers(responseDef.getHeaders());
        if (responseDef.getBody() != null) {
            responseBuilder.body(responseDef.getBody());
        } else if (responseDef.getByteBody() != null) {
            responseBuilder.body(responseDef.getByteBody());
        } else {
            responseBuilder.body(body != null ? body : "");
        }
        Response response = responseBuilder.build();

        return ServeEvent.of(wiremockRequest)
                .withStubMapping(stub)
                .withResponseDefinition(responseDef)
                .complete(response, DataTruncationSettings.DEFAULTS);
    }

    public static ServeEvent buildProxiedServeEvent(
            StubMapping stub, Request wiremockRequest, String targetUrl, CasualServiceCallReplyMessage upstreamReply) {
        ErrorState errorState = upstreamReply.getError();
        int statusCode = 200;
        if (errorState == ErrorState.TPENOENT) statusCode = 404;
        else if (errorState == ErrorState.TPEINVAL) statusCode = 400;
        else if (errorState == ErrorState.TPETIME) statusCode = 504;
        else if (errorState == ErrorState.TPESVCERR) statusCode = 502;
        else if (errorState != ErrorState.OK) statusCode = 500;

        byte[] rawBytes = CasualBufferUtils.toByteArray(upstreamReply.getServiceBuffer());
        String replyPayload = CasualBufferUtils.extractPayload(upstreamReply.getServiceBuffer());
        String replyBufferType = (upstreamReply.getServiceBuffer() != null
                        && !upstreamReply.getServiceBuffer().isEmpty())
                ? upstreamReply.getServiceBuffer().getType()
                : "JSON";

        String contentType;
        if (CasualBufferUtils.isJsonType(replyBufferType)
                || (CasualBufferUtils.isFieldedType(replyBufferType)
                        && replyPayload.trim().startsWith("{"))) {
            contentType = "application/json";
        } else if (CasualBufferUtils.isCStringType(replyBufferType)) {
            contentType = "text/plain; charset=UTF-8";
        } else {
            contentType = "application/octet-stream";
        }

        HttpHeaders headers = new HttpHeaders(
                new HttpHeader("Content-Type", contentType),
                new HttpHeader("X-Casual-Error-State", errorState.name()),
                new HttpHeader(
                        "X-Casual-Transaction-State",
                        upstreamReply.getTransactionState().name()),
                new HttpHeader("X-Casual-Buffer-Type", replyBufferType));

        ResponseDefinition responseDef = ResponseDefinitionBuilder.responseDefinition()
                .proxiedFrom(targetUrl)
                .withStatus(statusCode)
                .withHeaders(headers)
                .withBody(replyPayload)
                .build();

        byte[] bodyForResponse = (CasualBufferUtils.isJsonType(replyBufferType)
                        || CasualBufferUtils.isCStringType(replyBufferType)
                        || (CasualBufferUtils.isFieldedType(replyBufferType)
                                && replyPayload.trim().startsWith("{")))
                ? replyPayload.getBytes(StandardCharsets.UTF_8)
                : rawBytes;

        Response response = Response.response()
                .status(statusCode)
                .headers(headers)
                .body(bodyForResponse)
                .build();

        return ServeEvent.of(wiremockRequest)
                .withStubMapping(stub)
                .withResponseDefinition(responseDef)
                .complete(response, DataTruncationSettings.DEFAULTS);
    }

    public static ServeEvent buildUnmatchedServeEvent(Request wiremockRequest, ErrorState errorState) {
        ResponseDefinition responseDef = ResponseDefinition.notConfigured();
        String errName = errorState != null ? errorState.name() : "TPENOENT";
        int status = errorState == ErrorState.TPEITYPE ? 415 : 404;
        Response response = Response.response()
                .status(status)
                .headers(new HttpHeaders(
                        new HttpHeader("Content-Type", "application/json"),
                        new HttpHeader("X-Casual-Error-State", errName),
                        new HttpHeader("X-Casual-Service", wiremockRequest.getHeader("X-Casual-Service"))))
                .body(String.format(
                        Locale.ROOT,
                        "{\"error\": \"%s\", \"message\": \"Service unmatched in WireMock stubs\"}",
                        errName))
                .build();
        return ServeEvent.of(wiremockRequest)
                .withResponseDefinition(responseDef)
                .complete(response, DataTruncationSettings.DEFAULTS);
    }

    public static CasualServiceCallReplyMessage buildErrorReply(
            CasualServiceCallRequestMessage req, ErrorState errorState) {
        CasualServiceCallReplyMessage.Builder replyBuilder = CasualServiceCallReplyMessage.createBuilder()
                .setProtocolVersion(ProtocolVersion.VERSION_1_0)
                .setExecution(req != null ? req.getExecution() : UUID.randomUUID())
                .setError(errorState)
                .setTransactionState(TransactionState.TX_ACTIVE)
                .setServiceBuffer(ServiceBuffer.empty());
        if (req != null && req.getXid() != null) {
            replyBuilder.setXid(req.getXid());
        }
        return replyBuilder.build();
    }

    private static Optional<XAReturnCode> extractVoteOverride(HttpHeaders headers, String headerName) {
        if (headers == null) return Optional.empty();
        HttpHeader header = headers.getHeader(headerName);
        if (header != null && header.isPresent()) {
            try {
                return Optional.of(
                        XAReturnCode.valueOf(header.firstValue().trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return Optional.empty();
    }
}
