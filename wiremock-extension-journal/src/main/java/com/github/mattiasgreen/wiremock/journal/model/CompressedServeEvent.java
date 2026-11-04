package com.github.mattiasgreen.wiremock.journal.model;

import com.github.mattiasgreen.wiremock.journal.engine.DictionaryPool;
import com.github.mattiasgreen.wiremock.journal.engine.ZstdCodec;
import com.github.tomakehurst.wiremock.common.Limit;
import com.github.tomakehurst.wiremock.common.url.PathParams;
import com.github.tomakehurst.wiremock.http.*;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Memory-efficient ServeEvent that retains request and response payloads in Zstandard compressed form,
 * decompressing transiently on-the-fly when getters are accessed without re-caching raw bytes on the heap.
 */
public class CompressedServeEvent extends ServeEvent {

    private final ClusterSignature signature;
    private final boolean usedDictionary;
    private final int rawRequestSize;
    private final int rawResponseSize;
    private final byte[] compressedRequestBody;
    private final byte[] compressedResponseBody;

    private final Request requestMetadata;
    private final int responseStatus;
    private final HttpHeaders responseHeaders;
    private final Fault responseFault;

    private final ZstdCodec codec;
    private final DictionaryPool dictionaryPool;

    public CompressedServeEvent(
            ServeEvent original, ClusterSignature signature, ZstdCodec codec, DictionaryPool dictionaryPool) {
        super(
                original.getId(),
                createEmptyRequestShell(original.getRequest()),
                original.getStubMapping(),
                original.getResponseDefinition() != null
                        ? original.getResponseDefinition()
                        : ResponseDefinition.notConfigured(),
                createEmptyResponseShell(original.getResponse()),
                original.getResponseDefinition() != null ? original.getWasMatched() : false,
                original.getTiming(),
                original.getSubEvents() != null ? new LinkedList<>(original.getSubEvents()) : new LinkedList<>());

        this.signature = Objects.requireNonNull(signature, "signature must not be null");
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
        this.dictionaryPool = Objects.requireNonNull(dictionaryPool, "dictionaryPool must not be null");

        // Request compression
        byte[] rawReq = original.getRequest() != null ? original.getRequest().getBody() : new byte[0];
        this.rawRequestSize = rawReq != null ? rawReq.length : 0;
        this.requestMetadata = original.getRequest();

        // Response compression
        byte[] rawResp = original.getResponse() != null ? original.getResponse().getBody() : new byte[0];
        this.rawResponseSize = rawResp != null ? rawResp.length : 0;
        this.responseStatus =
                original.getResponse() != null ? original.getResponse().getStatus() : 200;
        this.responseHeaders =
                original.getResponse() != null ? original.getResponse().getHeaders() : new HttpHeaders();
        this.responseFault =
                original.getResponse() != null ? original.getResponse().getFault() : null;

        // Compress payloads
        DictionaryPool.CompiledDictionary dict = dictionaryPool.getDictionary(signature);
        if (dict != null) {
            this.usedDictionary = true;
            this.compressedRequestBody = codec.compressWithDict(rawReq, dict.compressor());
            this.compressedResponseBody = codec.compressWithDict(rawResp, dict.compressor());
        } else {
            this.usedDictionary = false;
            this.compressedRequestBody = codec.compressBaseline(rawReq);
            this.compressedResponseBody = codec.compressBaseline(rawResp);

            // Record samples for prospective dictionary training
            if (rawResp != null && rawResp.length > 0) {
                dictionaryPool.recordSample(signature, rawResp);
            }
        }
    }

    private static LoggedRequest createEmptyRequestShell(LoggedRequest req) {
        if (req == null) {
            return null;
        }
        return LoggedRequest.createFrom(new DelegatingRequest(req, new byte[0]));
    }

    private static LoggedResponse createEmptyResponseShell(LoggedResponse resp) {
        if (resp == null) {
            return null;
        }
        return new LoggedResponse(resp.getStatus(), resp.getHeaders(), null, resp.getFault(), "");
    }

    @Override
    public LoggedRequest getRequest() {
        if (requestMetadata == null) {
            return null;
        }
        byte[] decompressed = decompressPayload(compressedRequestBody, rawRequestSize);
        return LoggedRequest.createFrom(new DelegatingRequest(requestMetadata, decompressed));
    }

    @Override
    public LoggedResponse getResponse() {
        byte[] decompressed = decompressPayload(compressedResponseBody, rawResponseSize);
        Response response = Response.response()
                .status(responseStatus)
                .headers(responseHeaders)
                .body(decompressed)
                .fault(responseFault)
                .build();
        return LoggedResponse.from(response, Limit.UNLIMITED);
    }

    private byte[] decompressPayload(byte[] compressed, int originalSize) {
        if (compressed == null || compressed.length == 0 || originalSize <= 0) {
            return new byte[0];
        }
        if (usedDictionary) {
            DictionaryPool.CompiledDictionary dict = dictionaryPool.getDictionary(signature);
            if (dict != null) {
                return codec.decompressWithDict(compressed, dict.decompressor(), originalSize);
            }
        }
        return codec.decompressBaseline(compressed, originalSize);
    }

    public ClusterSignature getSignature() {
        return signature;
    }

    public boolean isUsedDictionary() {
        return usedDictionary;
    }

    public int getRawRequestSize() {
        return rawRequestSize;
    }

    public int getRawResponseSize() {
        return rawResponseSize;
    }

    public int getCompressedRequestSize() {
        return compressedRequestBody != null ? compressedRequestBody.length : 0;
    }

    public int getCompressedResponseSize() {
        return compressedResponseBody != null ? compressedResponseBody.length : 0;
    }

    public long getStoredMemorySizeBytes() {
        return (compressedRequestBody != null ? compressedRequestBody.length : 0)
                + (compressedResponseBody != null ? compressedResponseBody.length : 0);
    }

    /**
     * Fast free-text matching that tests URL, headers, and transiently decompressed payload.
     */
    public boolean matchesText(String searchTerm) {
        if (searchTerm == null || searchTerm.isBlank()) {
            return true;
        }

        // 1. Check URL
        if (requestMetadata != null
                && requestMetadata.getUrl() != null
                && requestMetadata.getUrl().contains(searchTerm)) {
            return true;
        }

        // 2. Check headers
        if (requestMetadata != null && requestMetadata.getHeaders() != null) {
            for (HttpHeader header : requestMetadata.getHeaders().all()) {
                if (header.key().contains(searchTerm)
                        || header.values().stream().anyMatch(v -> v.contains(searchTerm))) {
                    return true;
                }
            }
        }

        // 3. Transiently decompress request body
        if (rawRequestSize > 0) {
            byte[] reqBytes = decompressPayload(compressedRequestBody, rawRequestSize);
            String reqStr = new String(reqBytes, StandardCharsets.UTF_8);
            if (reqStr.contains(searchTerm)) {
                return true;
            }
        }

        // 4. Transiently decompress response body
        if (rawResponseSize > 0) {
            byte[] respBytes = decompressPayload(compressedResponseBody, rawResponseSize);
            String respStr = new String(respBytes, StandardCharsets.UTF_8);
            if (respStr.contains(searchTerm)) {
                return true;
            }
        }

        return false;
    }

    private static class DelegatingRequest implements Request {
        private final Request delegate;
        private final byte[] body;

        DelegatingRequest(Request delegate, byte[] body) {
            this.delegate = delegate;
            this.body = body != null ? body : new byte[0];
        }

        @Override
        public String getUrl() {
            return delegate != null ? delegate.getUrl() : "";
        }

        @Override
        public String getAbsoluteUrl() {
            return delegate != null ? delegate.getAbsoluteUrl() : "";
        }

        @Override
        public RequestMethod getMethod() {
            return delegate != null ? delegate.getMethod() : RequestMethod.GET;
        }

        @Override
        public String getScheme() {
            return delegate != null ? delegate.getScheme() : "http";
        }

        @Override
        public String getHost() {
            return delegate != null ? delegate.getHost() : "localhost";
        }

        @Override
        public int getPort() {
            return delegate != null ? delegate.getPort() : 80;
        }

        @Override
        public String getClientIp() {
            return delegate != null ? delegate.getClientIp() : "127.0.0.1";
        }

        @Override
        public String getHeader(String key) {
            return delegate != null ? delegate.getHeader(key) : null;
        }

        @Override
        public HttpHeader header(String key) {
            return delegate != null ? delegate.header(key) : HttpHeader.absent(key);
        }

        @Override
        public ContentTypeHeader contentTypeHeader() {
            return delegate != null ? delegate.contentTypeHeader() : null;
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate != null ? delegate.getHeaders() : new HttpHeaders();
        }

        @Override
        public boolean containsHeader(String key) {
            return delegate != null && delegate.containsHeader(key);
        }

        @Override
        public Set<String> getAllHeaderKeys() {
            return delegate != null ? delegate.getAllHeaderKeys() : Collections.emptySet();
        }

        @Override
        public Map<String, Cookie> getCookies() {
            return delegate != null ? delegate.getCookies() : Collections.emptyMap();
        }

        @Override
        public QueryParameter queryParameter(String key) {
            return delegate != null ? delegate.queryParameter(key) : QueryParameter.absent(key);
        }

        @Override
        public FormParameter formParameter(String key) {
            return delegate != null ? delegate.formParameter(key) : FormParameter.absent(key);
        }

        @Override
        public Map<String, FormParameter> formParameters() {
            return delegate != null ? delegate.formParameters() : Collections.emptyMap();
        }

        @Override
        public byte[] getBody() {
            return body;
        }

        @Override
        public String getBodyAsString() {
            return new String(body, StandardCharsets.UTF_8);
        }

        @Override
        public String getBodyAsBase64() {
            return Base64.getEncoder().encodeToString(body);
        }

        @Override
        public boolean isBrowserProxyRequest() {
            return delegate != null && delegate.isBrowserProxyRequest();
        }

        @Override
        public Optional<Request> getOriginalRequest() {
            return delegate != null ? delegate.getOriginalRequest() : Optional.empty();
        }

        @Override
        public String getProtocol() {
            return delegate != null ? delegate.getProtocol() : "HTTP/1.1";
        }

        @Override
        public PathParams getPathParameters() {
            return delegate != null ? delegate.getPathParameters() : PathParams.empty();
        }

        @Override
        public boolean isMultipart() {
            return delegate != null && delegate.isMultipart();
        }

        @Override
        public Collection<Part> getParts() {
            return delegate != null ? delegate.getParts() : Collections.emptyList();
        }

        @Override
        public Part getPart(String name) {
            return delegate != null ? delegate.getPart(name) : null;
        }
    }
}
