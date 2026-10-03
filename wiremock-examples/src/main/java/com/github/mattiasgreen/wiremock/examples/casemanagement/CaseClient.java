package com.github.mattiasgreen.wiremock.examples.casemanagement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mattiasgreen.wiremock.examples.casemanagement.model.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

public class CaseClient {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public CaseClient(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    public CaseDto createCase(String title) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("title", title));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/cases"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertSuccessOrConflict(response);
            return objectMapper.readValue(response.body(), CaseDto.class);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to create case", e);
        }
    }

    public CaseDto getCase(String caseId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/cases/" + caseId))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertSuccessOrConflict(response);
            return objectMapper.readValue(response.body(), CaseDto.class);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to get case " + caseId, e);
        }
    }

    public CommentDto addComment(String caseId, String text) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("text", text));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/cases/" + caseId + "/comments"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertSuccessOrConflict(response);
            return objectMapper.readValue(response.body(), CommentDto.class);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to add comment to case " + caseId, e);
        }
    }

    public TaskDto addTask(String caseId, String description) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("description", description));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/cases/" + caseId + "/tasks"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertSuccessOrConflict(response);
            return objectMapper.readValue(response.body(), TaskDto.class);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to add task to case " + caseId, e);
        }
    }

    public CaseDto closeCase(String caseId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/cases/" + caseId + "/close"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertSuccessOrConflict(response);
            return objectMapper.readValue(response.body(), CaseDto.class);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to close case " + caseId, e);
        }
    }

    public CaseDto reopenCase(String caseId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/cases/" + caseId + "/reopen"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertSuccessOrConflict(response);
            return objectMapper.readValue(response.body(), CaseDto.class);
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to reopen case " + caseId, e);
        }
    }

    private void assertSuccessOrConflict(HttpResponse<String> response) {
        int code = response.statusCode();
        if (code >= 200 && code < 300) {
            return;
        }
        if (code == 409 || code == 400) {
            throw new CaseConflictException(code, response.body());
        }
        throw new RuntimeException("HTTP request failed with status " + code + ": " + response.body());
    }
}
