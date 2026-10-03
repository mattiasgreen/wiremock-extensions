package com.github.mattiasgreen.wiremock.logging;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class WireMockLogEvent {

    @JsonProperty("timestamp")
    private String timestamp = Instant.now().toString();

    @JsonProperty("event")
    private String event = "wiremock_request_served";

    @JsonProperty("duration_ms")
    private double durationMs;

    @JsonProperty("matched")
    private boolean matched;

    @JsonProperty("stub_id")
    private String stubId;

    @JsonProperty("stub_name")
    private String stubName;

    @JsonProperty("request")
    private RequestDetails request;

    @JsonProperty("response")
    private ResponseDetails response;

    @JsonProperty("unmatched_reason")
    private String unmatchedReason;

    private Map<String, String> mdcTags = new HashMap<>();

    public WireMockLogEvent() {}

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public double getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(double durationMs) {
        this.durationMs = durationMs;
    }

    public boolean isMatched() {
        return matched;
    }

    public void setMatched(boolean matched) {
        this.matched = matched;
    }

    public String getStubId() {
        return stubId;
    }

    public void setStubId(String stubId) {
        this.stubId = stubId;
    }

    public String getStubName() {
        return stubName;
    }

    public void setStubName(String stubName) {
        this.stubName = stubName;
    }

    public RequestDetails getRequest() {
        return request;
    }

    public void setRequest(RequestDetails request) {
        this.request = request;
    }

    public ResponseDetails getResponse() {
        return response;
    }

    public void setResponse(ResponseDetails response) {
        this.response = response;
    }

    public String getUnmatchedReason() {
        return unmatchedReason;
    }

    public void setUnmatchedReason(String unmatchedReason) {
        this.unmatchedReason = unmatchedReason;
    }

    @JsonAnyGetter
    public Map<String, String> getMdcTags() {
        return mdcTags;
    }

    public void setMdcTags(Map<String, String> mdcTags) {
        this.mdcTags = mdcTags != null ? mdcTags : new HashMap<>();
    }

    public void addMdcTag(String key, String value) {
        this.mdcTags.put(key, value);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RequestDetails {
        private String method;
        private String url;
        private String clientIp;
        private Map<String, String> headers;
        private String body;

        public String getMethod() {
            return method;
        }

        public void setMethod(String method) {
            this.method = method;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getClientIp() {
            return clientIp;
        }

        public void setClientIp(String clientIp) {
            this.clientIp = clientIp;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }

        public String getBody() {
            return body;
        }

        public void setBody(String body) {
            this.body = body;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ResponseDetails {
        private int status;
        private Map<String, String> headers;
        private String body;

        public int getStatus() {
            return status;
        }

        public void setStatus(int status) {
            this.status = status;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }

        public String getBody() {
            return body;
        }

        public void setBody(String body) {
            this.body = body;
        }
    }
}
