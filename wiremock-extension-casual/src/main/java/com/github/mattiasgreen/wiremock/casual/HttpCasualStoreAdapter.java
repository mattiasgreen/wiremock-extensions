package com.github.mattiasgreen.wiremock.casual;

import com.github.tomakehurst.wiremock.admin.model.ListStubMappingsResult;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Remote {@link CasualStorePort} adapter backed exclusively by WireMock's HTTP Admin API
 * ({@code /__admin/mappings} and {@code /__admin/files/**}). Used when the Casual gateway connects
 * out-of-process over HTTP rather than directly to an in-process {@code WireMockServer} or
 * {@code WireMockServices} instance.
 */
public final class HttpCasualStoreAdapter implements CasualStorePort {

    private static final Logger LOG = LoggerFactory.getLogger(HttpCasualStoreAdapter.class);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(2);

    private final String baseUrl;
    private final HttpClient httpClient;

    public HttpCasualStoreAdapter(String baseUrl) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.httpClient = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://localhost:8080";
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? "http://localhost:8080" : trimmed;
    }

    @Override
    public Optional<StubMapping> findMatchingStub(Request request) {
        if (request == null) {
            return Optional.empty();
        }
        List<StubMapping> mappings = new ArrayList<>(getActiveStubMappings());
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
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/__admin/mappings"))
                    .GET()
                    .timeout(HTTP_TIMEOUT)
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                ListStubMappingsResult result = Json.read(response.body(), ListStubMappingsResult.class);
                if (result != null && result.getMappings() != null) {
                    return result.getMappings().stream()
                            .filter(s -> s != null && (s.getPriority() == null || s.getPriority() < 100))
                            .toList();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.debug("Interrupted while querying WireMock mappings over HTTP: {}", sanitize(e.getMessage()));
        } catch (IOException | RuntimeException e) {
            LOG.debug("Failed to query WireMock mappings over HTTP: {}", sanitize(e.getMessage()));
        }
        return Collections.emptyList();
    }

    @Override
    public String readBodyFromFile(String bodyFileName) {
        if (bodyFileName == null || bodyFileName.isBlank()) {
            return null;
        }
        String encodedPath = encodeFilePath(bodyFileName);
        if (encodedPath.isEmpty()) {
            return null;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/__admin/files/" + encodedPath))
                    .GET()
                    .timeout(HTTP_TIMEOUT)
                    .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() >= 200
                    && response.statusCode() < 300
                    && response.body() != null
                    && !response.body().isEmpty()) {
                return response.body();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.debug("Interrupted while reading body file over HTTP: {}", sanitize(e.getMessage()));
        } catch (IOException | RuntimeException e) {
            LOG.debug("Failed to read body file over HTTP: {}", sanitize(e.getMessage()));
        }
        return null;
    }

    @Override
    public void recordServeEvent(ServeEvent serveEvent) {
        LOG.debug("Skipping ServeEvent recording in remote HTTP adapter mode");
    }

    @Override
    public String getBaseUrl() {
        return baseUrl;
    }

    private static String encodeFilePath(String bodyFileName) {
        String clean = bodyFileName.trim();
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        if (clean.isEmpty()) {
            return "";
        }
        String[] segments = clean.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(URLEncoder.encode(segments[i], StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }

    private static String sanitize(String message) {
        return message != null ? message.replace('\r', ' ').replace('\n', ' ') : "";
    }
}
