package com.github.mattiasgreen.wiremock.casual;

import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pure in-memory {@link CasualStorePort} adapter for fast, zero-socket, zero-HTTP unit testing.
 */
public class InMemoryCasualStoreAdapter implements CasualStorePort {

    private final List<StubMapping> stubMappings = new CopyOnWriteArrayList<>();
    private final List<ServeEvent> serveEvents = new CopyOnWriteArrayList<>();
    private final Map<String, String> files = new ConcurrentHashMap<>();
    private final String baseUrl;

    public InMemoryCasualStoreAdapter() {
        this("http://localhost:8080");
    }

    public InMemoryCasualStoreAdapter(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void addStubMapping(StubMapping stubMapping) {
        this.stubMappings.add(stubMapping);
    }

    public void addStub(String url, int status, String body) {
        addStub(url, status, body, Collections.emptyMap());
    }

    public void addStub(String url, int status, String body, Map<String, String> headers) {
        com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder respBuilder =
                com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                        .withStatus(status)
                        .withBody(body);
        if (headers != null) {
            headers.forEach(respBuilder::withHeader);
        }
        StubMapping mapping = com.github.tomakehurst.wiremock.client.WireMock.post(
                        com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo(url))
                .willReturn(respBuilder)
                .build();
        addStubMapping(mapping);
    }

    public void addFile(String fileName, String content) {
        this.files.put(fileName, content);
    }

    public List<ServeEvent> getRecordedServeEvents() {
        return Collections.unmodifiableList(serveEvents);
    }

    public void clear() {
        this.stubMappings.clear();
        this.serveEvents.clear();
        this.files.clear();
    }

    @Override
    public Optional<StubMapping> findMatchingStub(Request request) {
        List<StubMapping> mappings = new ArrayList<>(stubMappings);
        Collections.reverse(mappings);
        mappings.sort((a, b) -> {
            int pa = a.getPriority() != null ? a.getPriority() : 5;
            int pb = b.getPriority() != null ? b.getPriority() : 5;
            return Integer.compare(pa, pb);
        });

        for (StubMapping mapping : mappings) {
            if (mapping.getRequest() != null
                    && mapping.getRequest().match(request).isExactMatch()) {
                return Optional.of(mapping);
            }
        }
        return Optional.empty();
    }

    @Override
    public List<StubMapping> getActiveStubMappings() {
        return Collections.unmodifiableList(stubMappings);
    }

    @Override
    public String readBodyFromFile(String bodyFileName) {
        return files.get(bodyFileName);
    }

    @Override
    public void recordServeEvent(ServeEvent serveEvent) {
        serveEvents.add(serveEvent);
    }

    @Override
    public String getBaseUrl() {
        return baseUrl;
    }
}
