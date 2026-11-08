package com.github.mattiasgreen.wiremock.journal.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.Limit;
import com.github.tomakehurst.wiremock.common.Timing;
import com.github.tomakehurst.wiremock.http.Cookie;
import com.github.tomakehurst.wiremock.http.FormParameter;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.QueryParameter;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.Response;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClusterSignatureTest {

    @Test
    @DisplayName("Should cluster async queue by URI path without schema cross-contamination")
    void shouldClusterAsyncQueueByUri() {
        ServeEvent event = createEvent(null, "/__async/kafka/orders.events", Collections.emptyMap(), false);
        ClusterSignature signature = ClusterSignature.of(event);
        assertThat(signature.key()).isEqualTo("async-queue:kafka:orders.events");
    }

    @Test
    @DisplayName("Should strip query parameters when clustering async queue URI")
    void shouldStripQueryParametersFromAsyncQueueUri() {
        ServeEvent event = createEvent(
                null, "/__async/amqp/payments.inbound?timeout=5000&ack=true", Collections.emptyMap(), false);
        ClusterSignature signature = ClusterSignature.of(event);
        assertThat(signature.key()).isEqualTo("async-queue:amqp:payments.inbound");
    }

    @Test
    @DisplayName("Should cluster async queue via corporate async headers")
    void shouldClusterAsyncQueueViaHeaders() {
        Map<String, String> headers = Map.of(
                "X-Async-Channel", "notifications.broadcast",
                "X-Async-Protocol", "kafka");
        ServeEvent event = createEvent(null, "/api/messages", headers, false);
        ClusterSignature signature = ClusterSignature.of(event);
        assertThat(signature.key()).isEqualTo("async-queue:kafka:notifications.broadcast");
    }

    @Test
    @DisplayName("Should cluster proxied async bridge call")
    void shouldClusterProxiedAsyncBridgeCall() {
        ServeEvent event = createEvent(null, "/__async/kafka/orders.events", Collections.emptyMap(), true);
        ClusterSignature signature = ClusterSignature.of(event);
        assertThat(signature.key()).isEqualTo("async-proxy:kafka:orders.events");
    }

    @Test
    @DisplayName("Should cluster concrete stub mapping by stub ID")
    void shouldClusterStubMappingByStubId() {
        UUID stubId = UUID.randomUUID();
        StubMapping stub = new StubMapping();
        stub.setId(stubId);

        ServeEvent event = createEvent(stub, "/api/v1/orders", Collections.emptyMap(), false);
        ClusterSignature signature = ClusterSignature.of(event);
        assertThat(signature.key()).isEqualTo("stub:" + stubId);
    }

    @Test
    @DisplayName("Should fallback to unmatched method when no signature matches")
    void shouldFallbackToUnmatchedMethod() {
        ServeEvent event = createEvent(null, "/api/unknown", Collections.emptyMap(), false);
        ClusterSignature signature = ClusterSignature.of(event);
        assertThat(signature.key()).isEqualTo("unmatched:POST");
    }

    private ServeEvent createEvent(StubMapping stub, String url, Map<String, String> headersMap, boolean isProxy) {
        LoggedRequest request = LoggedRequest.createFrom(new Request() {
            @Override
            public String getUrl() {
                return url;
            }

            @Override
            public String getAbsoluteUrl() {
                return "http://localhost:8080" + url;
            }

            @Override
            public RequestMethod getMethod() {
                return RequestMethod.POST;
            }

            @Override
            public String getScheme() {
                return "http";
            }

            @Override
            public String getHost() {
                return "localhost";
            }

            @Override
            public int getPort() {
                return 8080;
            }

            @Override
            public String getClientIp() {
                return "127.0.0.1";
            }

            @Override
            public String getHeader(String key) {
                return headersMap.get(key);
            }

            @Override
            public HttpHeader header(String key) {
                String val = getHeader(key);
                return val != null ? new HttpHeader(key, val) : HttpHeader.absent(key);
            }

            @Override
            public com.github.tomakehurst.wiremock.http.ContentTypeHeader contentTypeHeader() {
                return new com.github.tomakehurst.wiremock.http.ContentTypeHeader("application/json");
            }

            @Override
            public HttpHeaders getHeaders() {
                java.util.List<HttpHeader> list = new java.util.ArrayList<>();
                headersMap.forEach((k, v) -> list.add(new HttpHeader(k, v)));
                return new HttpHeaders(list);
            }

            @Override
            public boolean containsHeader(String key) {
                return headersMap.containsKey(key);
            }

            @Override
            public Set<String> getAllHeaderKeys() {
                return headersMap.keySet();
            }

            @Override
            public Map<String, Cookie> getCookies() {
                return Collections.emptyMap();
            }

            @Override
            public QueryParameter queryParameter(String key) {
                return QueryParameter.absent(key);
            }

            @Override
            public FormParameter formParameter(String key) {
                return FormParameter.absent(key);
            }

            @Override
            public Map<String, FormParameter> formParameters() {
                return Collections.emptyMap();
            }

            @Override
            public byte[] getBody() {
                return "{}".getBytes(StandardCharsets.UTF_8);
            }

            @Override
            public String getBodyAsString() {
                return "{}";
            }

            @Override
            public String getBodyAsBase64() {
                return "";
            }

            @Override
            public boolean isBrowserProxyRequest() {
                return false;
            }

            @Override
            public Optional<Request> getOriginalRequest() {
                return Optional.empty();
            }

            @Override
            public String getProtocol() {
                return "HTTP/1.1";
            }

            @Override
            public com.github.tomakehurst.wiremock.common.url.PathParams getPathParameters() {
                return com.github.tomakehurst.wiremock.common.url.PathParams.empty();
            }

            @Override
            public boolean isMultipart() {
                return false;
            }

            @Override
            public Collection<Request.Part> getParts() {
                return Collections.emptyList();
            }

            @Override
            public Request.Part getPart(String name) {
                return null;
            }
        });

        ResponseDefinitionBuilder respBuilder =
                ResponseDefinitionBuilder.responseDefinition().withStatus(200);
        if (isProxy) {
            respBuilder.proxiedFrom("http://upstream-broker:9092");
        }
        ResponseDefinition respDef = respBuilder.build();

        Response response = Response.response()
                .status(200)
                .body("{}".getBytes(StandardCharsets.UTF_8))
                .build();
        com.github.tomakehurst.wiremock.http.LoggedResponse loggedResp =
                com.github.tomakehurst.wiremock.http.LoggedResponse.from(response, Limit.UNLIMITED);

        return new ServeEvent(
                UUID.randomUUID(), request, stub, respDef, loggedResp, true, Timing.UNTIMED, new LinkedList<>());
    }
}
