package com.github.mattiasgreen.wiremock.journal.model;

import com.github.mattiasgreen.wiremock.journal.engine.AdaptiveJournalCompactor;
import com.github.mattiasgreen.wiremock.journal.engine.DictionaryPool;
import com.github.mattiasgreen.wiremock.journal.engine.ZstdCodec;
import com.github.tomakehurst.wiremock.common.Limit;
import com.github.tomakehurst.wiremock.http.*;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Memory-efficient ServeEvent that retains request and response payloads in Zstandard compressed form,
 * decompressing transiently on-the-fly when getters are accessed without re-caching raw bytes on the heap.
 * Designed as a pure value record containing zero heavy engine dependencies.
 */
public class CompressedServeEvent extends ServeEvent {

    @FunctionalInterface
    public interface PayloadDecompressor {
        byte[] decompress(byte[] compressed, int originalSize, ClusterSignature signature, boolean usedDictionary);
    }

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

    private final PayloadDecompressor decompressor;

    /**
     * Primary pure value constructor. Does not perform compression or side-effects.
     */
    public CompressedServeEvent(
            ServeEvent original,
            ClusterSignature signature,
            byte[] compressedRequestBody,
            byte[] compressedResponseBody,
            int rawRequestSize,
            int rawResponseSize,
            boolean usedDictionary,
            PayloadDecompressor decompressor) {
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
        this.compressedRequestBody = compressedRequestBody != null ? compressedRequestBody : new byte[0];
        this.compressedResponseBody = compressedResponseBody != null ? compressedResponseBody : new byte[0];
        this.rawRequestSize = rawRequestSize;
        this.rawResponseSize = rawResponseSize;
        this.usedDictionary = usedDictionary;
        this.decompressor = decompressor != null ? decompressor : (bytes, origSize, sig, used) -> bytes;

        this.requestMetadata = original.getRequest();
        this.responseStatus =
                original.getResponse() != null ? original.getResponse().getStatus() : 200;
        this.responseHeaders =
                original.getResponse() != null ? original.getResponse().getHeaders() : new HttpHeaders();
        this.responseFault =
                original.getResponse() != null ? original.getResponse().getFault() : null;
    }

    /**
     * Legacy constructor preserved for backwards-compatibility.
     * Delegates compaction orchestration to {@link AdaptiveJournalCompactor}.
     */
    public CompressedServeEvent(
            ServeEvent original, ClusterSignature signature, ZstdCodec codec, DictionaryPool dictionaryPool) {
        this(original, signature, createCompacted(original, signature, codec, dictionaryPool));
    }

    private record CompactedData(
            byte[] req, byte[] resp, int reqLen, int respLen, boolean usedDict, PayloadDecompressor decompressor) {}

    private static CompactedData createCompacted(
            ServeEvent original, ClusterSignature signature, ZstdCodec codec, DictionaryPool dictionaryPool) {
        byte[] rawReq = original.getRequest() != null && original.getRequest().getBody() != null
                ? original.getRequest().getBody()
                : new byte[0];
        byte[] rawResp =
                original.getResponse() != null && original.getResponse().getBody() != null
                        ? original.getResponse().getBody()
                        : new byte[0];
        DictionaryPool.CompiledDictionary dict =
                dictionaryPool != null ? dictionaryPool.getDictionary(signature) : null;
        boolean usedDict = dict != null;
        byte[] compReq = usedDict ? codec.compressWithDict(rawReq, dict.compressor()) : codec.compressBaseline(rawReq);
        byte[] compResp =
                usedDict ? codec.compressWithDict(rawResp, dict.compressor()) : codec.compressBaseline(rawResp);
        return new CompactedData(
                compReq, compResp, rawReq.length, rawResp.length, usedDict, (bytes, origSize, sig, used) -> {
                    if (!used) {
                        return codec.decompressBaseline(bytes, origSize);
                    }
                    DictionaryPool.CompiledDictionary d =
                            dictionaryPool != null ? dictionaryPool.getDictionary(sig) : null;
                    return d != null
                            ? codec.decompressWithDict(bytes, d.decompressor(), origSize)
                            : codec.decompressBaseline(bytes, origSize);
                });
    }

    private CompressedServeEvent(ServeEvent original, ClusterSignature signature, CompactedData data) {
        this(
                original,
                signature,
                data.req(),
                data.resp(),
                data.reqLen(),
                data.respLen(),
                data.usedDict(),
                data.decompressor());
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
        byte[] decompressed = decompressor.decompress(compressedRequestBody, rawRequestSize, signature, usedDictionary);
        return LoggedRequest.createFrom(new DelegatingRequest(requestMetadata, decompressed));
    }

    @Override
    public LoggedResponse getResponse() {
        byte[] decompressed =
                decompressor.decompress(compressedResponseBody, rawResponseSize, signature, usedDictionary);
        Response response = Response.response()
                .status(responseStatus)
                .headers(responseHeaders)
                .body(decompressed)
                .fault(responseFault)
                .build();
        return LoggedResponse.from(response, Limit.UNLIMITED);
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

    public byte[] getCompressedRequestBody() {
        return compressedRequestBody.clone();
    }

    public byte[] getCompressedResponseBody() {
        return compressedResponseBody.clone();
    }

    public long getStoredMemorySizeBytes() {
        return (compressedRequestBody != null ? compressedRequestBody.length : 0)
                + (compressedResponseBody != null ? compressedResponseBody.length : 0);
    }

    /**
     * Fast free-text matching testing URL, headers, and transiently decompressed payload.
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
            byte[] reqBytes = decompressor.decompress(compressedRequestBody, rawRequestSize, signature, usedDictionary);
            String reqStr = new String(reqBytes, StandardCharsets.UTF_8);
            if (reqStr.contains(searchTerm)) {
                return true;
            }
        }

        // 4. Transiently decompress response body
        if (rawResponseSize > 0) {
            byte[] respBytes =
                    decompressor.decompress(compressedResponseBody, rawResponseSize, signature, usedDictionary);
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
            return delegate.getUrl();
        }

        @Override
        public String getAbsoluteUrl() {
            return delegate.getAbsoluteUrl();
        }

        @Override
        public RequestMethod getMethod() {
            return delegate.getMethod();
        }

        @Override
        public String getScheme() {
            return delegate.getScheme();
        }

        @Override
        public String getHost() {
            return delegate.getHost();
        }

        @Override
        public int getPort() {
            return delegate.getPort();
        }

        @Override
        public String getClientIp() {
            return delegate.getClientIp();
        }

        @Override
        public String getHeader(String key) {
            return delegate.getHeader(key);
        }

        @Override
        public HttpHeader header(String key) {
            return delegate.header(key);
        }

        @Override
        public ContentTypeHeader contentTypeHeader() {
            return delegate.contentTypeHeader();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public boolean containsHeader(String key) {
            return delegate.containsHeader(key);
        }

        @Override
        public Set<String> getAllHeaderKeys() {
            return delegate.getAllHeaderKeys();
        }

        @Override
        public Map<String, Cookie> getCookies() {
            return delegate.getCookies();
        }

        @Override
        public QueryParameter queryParameter(String key) {
            return delegate.queryParameter(key);
        }

        @Override
        public FormParameter formParameter(String key) {
            return delegate.formParameter(key);
        }

        @Override
        public Map<String, FormParameter> formParameters() {
            return delegate.formParameters();
        }

        @Override
        public byte[] getBody() {
            return body.clone();
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
            return delegate.isBrowserProxyRequest();
        }

        @Override
        public Optional<Request> getOriginalRequest() {
            return Optional.ofNullable(delegate);
        }

        @Override
        public String getProtocol() {
            return delegate.getProtocol();
        }

        @Override
        public com.github.tomakehurst.wiremock.common.url.PathParams getPathParameters() {
            return delegate.getPathParameters();
        }

        @Override
        public boolean isMultipart() {
            return delegate.isMultipart();
        }

        @Override
        public Collection<Part> getParts() {
            return delegate.getParts();
        }

        @Override
        public Part getPart(String name) {
            return delegate.getPart(name);
        }
    }
}
