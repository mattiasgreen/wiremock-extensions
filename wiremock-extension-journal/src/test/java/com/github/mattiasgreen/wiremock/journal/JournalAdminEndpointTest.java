package com.github.mattiasgreen.wiremock.journal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mattiasgreen.wiremock.journal.admin.JournalAdminEndpoint;
import com.github.mattiasgreen.wiremock.journal.engine.DictionaryPool;
import com.github.mattiasgreen.wiremock.journal.engine.JournalMemoryGovernor;
import com.github.mattiasgreen.wiremock.journal.engine.ZstdCodec;
import com.github.mattiasgreen.wiremock.journal.store.CompressedRequestJournalStore;
import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.common.Limit;
import com.github.tomakehurst.wiremock.common.Timing;
import com.github.tomakehurst.wiremock.core.Admin;
import com.github.tomakehurst.wiremock.http.*;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JournalAdminEndpointTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ZstdCodec codec;
    private DictionaryPool dictionaryPool;
    private CompressedRequestJournalStore store;
    private JournalAdminEndpoint endpoint;

    @BeforeEach
    void setUp() {
        codec = new ZstdCodec();
        dictionaryPool = new DictionaryPool(16, 5, 16 * 1024, codec);
        store = new CompressedRequestJournalStore(codec, dictionaryPool, new JournalMemoryGovernor());
        endpoint = new JournalAdminEndpoint(store);
    }

    @Test
    void shouldContributeAdminApiRoutes() throws Exception {
        Map<String, com.github.tomakehurst.wiremock.admin.AdminTask> routes = new HashMap<>();
        Router mockRouter = new Router() {
            @Override
            public void add(
                    RequestMethod method,
                    String path,
                    Class<? extends com.github.tomakehurst.wiremock.admin.AdminTask> taskClass) {}

            @Override
            public void add(RequestMethod method, String path, com.github.tomakehurst.wiremock.admin.AdminTask task) {
                routes.put(method.getName() + " " + path, task);
            }
        };

        endpoint.contributeAdminApiRoutes(mockRouter);
        assertThat(routes).containsKey("GET /journal/stats");
        assertThat(routes).containsKey("POST /journal/compact");
        assertThat(routes).containsKey("POST /journal/rebalance");
        assertThat(routes).containsKey("GET /journal/search");

        // 1. Test GET /journal/stats
        ResponseDefinition statsResp = routes.get("GET /journal/stats")
                .execute(
                        createMockAdmin(),
                        createMockServeEvent(null),
                        com.github.tomakehurst.wiremock.common.url.PathParams.empty());
        assertThat(statsResp.getStatus()).isEqualTo(200);

        JsonNode statsNode = MAPPER.readTree(statsResp.getBody());
        assertThat(statsNode.has("totalEvents")).isTrue();
        assertThat(statsNode.has("rawBytesTotal")).isTrue();
        assertThat(statsNode.has("compressedBytesTotal")).isTrue();
        assertThat(statsNode.has("compressionRatio")).isTrue();

        // 2. Add an event and test GET /journal/search?query=CASE-1234
        ServeEvent event = createSampleServeEvent("{\"caseId\":\"CASE-1234\",\"title\":\"Urgent Claim\"}");
        store.add(event);

        ServeEvent searchReq = createMockServeEvent("CASE-1234");
        ResponseDefinition searchResp = routes.get("GET /journal/search")
                .execute(createMockAdmin(), searchReq, com.github.tomakehurst.wiremock.common.url.PathParams.empty());
        assertThat(searchResp.getStatus()).isEqualTo(200);

        JsonNode searchNode = MAPPER.readTree(searchResp.getBody());
        assertThat(searchNode.get("totalMatches").asInt()).isEqualTo(1);
        assertThat(searchNode.get("matchedEventIds").get(0).asText())
                .isEqualTo(event.getId().toString());

        // 3. Test POST /journal/compact
        ResponseDefinition compactResp = routes.get("POST /journal/compact")
                .execute(
                        createMockAdmin(),
                        createMockServeEvent(null),
                        com.github.tomakehurst.wiremock.common.url.PathParams.empty());
        assertThat(compactResp.getStatus()).isEqualTo(200);

        // 4. Test POST /journal/rebalance
        ResponseDefinition rebalanceResp = routes.get("POST /journal/rebalance")
                .execute(
                        createMockAdmin(),
                        createMockServeEvent(null),
                        com.github.tomakehurst.wiremock.common.url.PathParams.empty());
        assertThat(rebalanceResp.getStatus()).isEqualTo(200);
    }

    private Admin createMockAdmin() {
        return null;
    }

    private ServeEvent createMockServeEvent(String query) {
        LoggedRequest req = LoggedRequest.createFrom(new Request() {
            @Override
            public String getUrl() {
                return query != null ? "/__admin/journal/search?query=" + query : "/__admin/journal/stats";
            }

            @Override
            public String getAbsoluteUrl() {
                return "http://localhost:8080" + getUrl();
            }

            @Override
            public RequestMethod getMethod() {
                return RequestMethod.GET;
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
                return null;
            }

            @Override
            public HttpHeader header(String key) {
                return HttpHeader.absent(key);
            }

            @Override
            public ContentTypeHeader contentTypeHeader() {
                return null;
            }

            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders();
            }

            @Override
            public boolean containsHeader(String key) {
                return false;
            }

            @Override
            public Set<String> getAllHeaderKeys() {
                return Collections.emptySet();
            }

            @Override
            public Map<String, Cookie> getCookies() {
                return Collections.emptyMap();
            }

            @Override
            public QueryParameter queryParameter(String key) {
                if ("query".equals(key) && query != null) {
                    return new QueryParameter("query", List.of(query));
                }
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
                return new byte[0];
            }

            @Override
            public String getBodyAsString() {
                return "";
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
            public Collection<Part> getParts() {
                return Collections.emptyList();
            }

            @Override
            public Part getPart(String name) {
                return null;
            }
        });
        return new ServeEvent(UUID.randomUUID(), req, null, null, null, false, Timing.UNTIMED, new LinkedList<>());
    }

    private ServeEvent createSampleServeEvent(String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        LoggedRequest req = LoggedRequest.createFrom(new Request() {
            @Override
            public String getUrl() {
                return "/api/claims";
            }

            @Override
            public String getAbsoluteUrl() {
                return "http://localhost:8080/api/claims";
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
                return null;
            }

            @Override
            public HttpHeader header(String key) {
                return HttpHeader.absent(key);
            }

            @Override
            public ContentTypeHeader contentTypeHeader() {
                return null;
            }

            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders();
            }

            @Override
            public boolean containsHeader(String key) {
                return false;
            }

            @Override
            public Set<String> getAllHeaderKeys() {
                return Collections.emptySet();
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
                return bytes;
            }

            @Override
            public String getBodyAsString() {
                return body;
            }

            @Override
            public String getBodyAsBase64() {
                return Base64.getEncoder().encodeToString(bytes);
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
            public Collection<Part> getParts() {
                return Collections.emptyList();
            }

            @Override
            public Part getPart(String name) {
                return null;
            }
        });

        Response response = Response.response().status(200).body(bytes).build();
        LoggedResponse loggedResp = LoggedResponse.from(response, Limit.UNLIMITED);

        ResponseDefinition respDef = ResponseDefinition.ok();

        return new ServeEvent(
                UUID.randomUUID(), req, null, respDef, loggedResp, true, Timing.UNTIMED, new LinkedList<>());
    }
}
