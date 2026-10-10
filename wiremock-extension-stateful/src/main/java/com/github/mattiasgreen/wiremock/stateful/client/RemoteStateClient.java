package com.github.mattiasgreen.wiremock.stateful.client;

import com.github.mattiasgreen.wiremock.stateful.ast.AstJson;
import com.github.mattiasgreen.wiremock.stateful.ast.AstModelDefinition;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

public final class RemoteStateClient {

    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final String wireMockBaseUrl;

    public RemoteStateClient(String wireMockBaseUrl) {
        this.wireMockBaseUrl = sanitizeBaseUrl(wireMockBaseUrl);
    }

    private static String sanitizeBaseUrl(String url) {
        Objects.requireNonNull(url, "wireMockBaseUrl must not be null");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public void pushModel(String correlationId, AstModelDefinition model) {
        String json = AstJson.toJson(model);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(wireMockBaseUrl + "/__admin/stateful/rules"))
                .header("Content-Type", "application/json")
                .header("x-correlation-id", correlationId != null ? correlationId : "DEFAULT")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(10))
                .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Failed to push stateful model to WireMock. Status: "
                        + response.statusCode() + ", Body: " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Error communicating with WireMock Admin API", e);
        }
    }

    public void clearSession(String correlationId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(wireMockBaseUrl + "/__admin/stateful/sessions/" + correlationId))
                .DELETE()
                .timeout(Duration.ofSeconds(10))
                .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "Failed to clear session in WireMock. Status: " + response.statusCode());
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Error communicating with WireMock Admin API", e);
        }
    }

    public String getEntity(String correlationId, String entityType, String entityId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(wireMockBaseUrl + "/__admin/stateful/entities/" + correlationId + "/" + entityType + "/"
                        + entityId))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            }
            if (response.statusCode() == 404) {
                return null;
            }
            throw new IllegalStateException("Failed to get entity from WireMock. Status: " + response.statusCode());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Error communicating with WireMock Admin API", e);
        }
    }
}
