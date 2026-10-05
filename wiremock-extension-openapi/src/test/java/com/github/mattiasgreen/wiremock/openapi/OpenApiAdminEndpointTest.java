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

    @Test
    @DisplayName("Should inspect OpenAPI spec via POST /__admin/openapi/inspect and extract servers and metadata")
    void shouldInspectOpenApiSpec() throws Exception {
        String yaml =
                """
                openapi: 3.0.3
                info:
                  title: Warehouse Service
                  version: 3.0.0
                servers:
                  - url: https://staging.warehouse.acme.com
                    description: Staging
                  - url: https://prod.warehouse.acme.com
                    description: Production
                paths:
                  /inventory:
                    get:
                      responses:
                        '200':
                          description: Ok
                """;

        HttpRequest inspectReq = HttpRequest.newBuilder()
                .uri(URI.create(wireMockServer.baseUrl() + "/__admin/openapi/inspect"))
                .header("Content-Type", "application/x-yaml")
                .POST(HttpRequest.BodyPublishers.ofString(yaml))
                .build();

        HttpResponse<String> resp = httpClient.send(inspectReq, HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(200);
        assertThat(resp.body()).contains("\"title\" : \"Warehouse Service\"");
        assertThat(resp.body()).contains("https://staging.warehouse.acme.com");
        assertThat(resp.body()).contains("\"totalOperations\" : 1");
    }

    @Test
    @DisplayName("Dual WireMock Integration: Primary proxies OpenAPI routes to upstream with auto-propagated headers")
    void shouldProxyOpenApiToUpstreamWithHeaderPropagation() throws Exception {
        WireMockServer upstreamServer =
                new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstreamServer.start();

        try {
            // Configure upstream real service behavior
            upstreamServer.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                            com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/items/42"))
                    .willReturn(com.github.tomakehurst.wiremock.client.WireMock.okJson(
                            "{\"id\": 42, \"name\": \"Authentic Live Item\", \"env\": \"upstream-prod\"}")));

            String spec =
                    """
                    openapi: 3.0.3
                    info:
                      title: Store Catalog
                      version: 1.0.0
                    paths:
                      /items/{id}:
                        get:
                          summary: Fetch Item
                          parameters:
                            - name: id
                              in: path
                              required: true
                              schema:
                                type: integer
                          responses:
                            '200':
                              description: Success
                    """;

            String payload = String.format(
                    """
                    {
                      "spec": %s,
                      "options": {
                        "generationMode": "PROXY",
                        "proxyBaseUrl": "%s",
                        "targetProject": "Store Catalog"
                      }
                    }
                    """,
                    com.github.tomakehurst.wiremock.common.Json.write(spec), upstreamServer.baseUrl());

            // Import as proxy
            HttpRequest importReq = HttpRequest.newBuilder()
                    .uri(URI.create(wireMockServer.baseUrl() + "/__admin/openapi/import"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> importResp = httpClient.send(importReq, HttpResponse.BodyHandlers.ofString());
            assertThat(importResp.statusCode()).isEqualTo(200);
            assertThat(importResp.body()).contains("\"totalStubsCreated\" : 1");

            // Client invokes primary WireMock with custom corporate headers
            HttpRequest clientReq = HttpRequest.newBuilder()
                    .uri(URI.create(wireMockServer.baseUrl() + "/items/42"))
                    .header("Authorization", "Bearer token-xyz-123")
                    .header("X-Correlation-Id", "corr-9988")
                    .GET()
                    .build();

            HttpResponse<String> clientResp = httpClient.send(clientReq, HttpResponse.BodyHandlers.ofString());

            // Primary transparently proxied to upstream and returned upstream response!
            assertThat(clientResp.statusCode()).isEqualTo(200);
            assertThat(clientResp.body()).contains("Authentic Live Item");
            assertThat(clientResp.body()).contains("upstream-prod");

            // Verify upstream received client's auto-propagated headers!
            upstreamServer.verify(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                            com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/items/42"))
                    .withHeader(
                            "Authorization",
                            com.github.tomakehurst.wiremock.client.WireMock.equalTo("Bearer token-xyz-123"))
                    .withHeader(
                            "X-Correlation-Id", com.github.tomakehurst.wiremock.client.WireMock.equalTo("corr-9988")));

        } finally {
            upstreamServer.stop();
        }
    }
}
