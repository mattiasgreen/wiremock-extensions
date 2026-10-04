package com.github.mattiasgreen.wiremock.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpenApiAdminEndpointTest {

    private WireMockServer wireMockServer;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(
                WireMockConfiguration.options().dynamicPort().extensions(new OpenApiAdminEndpoint()));
        wireMockServer.start();
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @Test
    @DisplayName("Should import OpenAPI spec via POST /__admin/openapi/import raw YAML and create stubs")
    void shouldImportRawYamlSpec() throws Exception {
        String yaml =
                """
                openapi: 3.0.3
                info:
                  title: Products API
                  version: 1.0.0
                paths:
                  /products:
                    get:
                      summary: Get all products
                      responses:
                        '200':
                          description: List of products
                          content:
                            application/json:
                              schema:
                                type: array
                                items:
                                  type: object
                                  properties:
                                    name:
                                      type: string
                                      example: Widget
                """;

        HttpRequest importRequest = HttpRequest.newBuilder()
                .uri(URI.create(wireMockServer.baseUrl() + "/__admin/openapi/import"))
                .header("Content-Type", "application/x-yaml")
                .POST(HttpRequest.BodyPublishers.ofString(yaml))
                .build();

        HttpResponse<String> importResponse = httpClient.send(importRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(importResponse.statusCode()).isEqualTo(200);
        assertThat(importResponse.body()).contains("\"totalStubsCreated\" : 1");
        assertThat(importResponse.body()).contains("[GET 200] Get all products");

        // Now test the actual created stub through WireMock!
        HttpRequest testRequest = HttpRequest.newBuilder()
                .uri(URI.create(wireMockServer.baseUrl() + "/products"))
                .GET()
                .build();

        HttpResponse<String> testResponse = httpClient.send(testRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(testResponse.statusCode()).isEqualTo(200);
        assertThat(testResponse.body()).contains("\"name\" : \"Widget\"");
    }

    @Test
    @DisplayName("Should import wrapped JSON payload with options")
    void shouldImportWrappedJsonWithOptions() throws Exception {
        String payload =
                """
                {
                  "spec": "openapi: 3.0.3\\ninfo:\\n  title: Sample\\n  version: 1.0.0\\npaths:\\n  /items:\\n    get:\\n      responses:\\n        '200':\\n          description: Ok\\n        '404':\\n          description: Not Found\\n",
                  "options": {
                    "includeOnlySuccessResponses": true
                  }
                }
                """;

        HttpRequest importRequest = HttpRequest.newBuilder()
                .uri(URI.create(wireMockServer.baseUrl() + "/__admin/openapi/import"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> importResponse = httpClient.send(importRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(importResponse.statusCode()).isEqualTo(200);
        assertThat(importResponse.body()).contains("\"totalStubsCreated\" : 1");
    }

    @Test
    @DisplayName("Should return 400 when body is empty or invalid")
    void shouldReturnBadRequestOnEmpty() throws Exception {
        HttpRequest importRequest = HttpRequest.newBuilder()
                .uri(URI.create(wireMockServer.baseUrl() + "/__admin/openapi/import"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(""))
                .build();

        HttpResponse<String> importResponse = httpClient.send(importRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(importResponse.statusCode()).isEqualTo(400);
    }
}
