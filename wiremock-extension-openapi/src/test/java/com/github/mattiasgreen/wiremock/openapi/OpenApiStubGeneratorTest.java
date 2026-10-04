package com.github.mattiasgreen.wiremock.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpenApiStubGeneratorTest {

    private final OpenApiStubGenerator generator = new OpenApiStubGenerator();

    @Test
    @DisplayName("Should parse simple OpenAPI YAML spec and synthesize stubs")
    void shouldParseSimpleYamlSpec() {
        String yaml =
                """
                openapi: 3.0.3
                info:
                  title: Simple Petstore
                  version: 1.0.0
                paths:
                  /pets:
                    get:
                      summary: List all pets
                      operationId: listPets
                      tags:
                        - pets
                      responses:
                        '200':
                          description: A list of pets
                          content:
                            application/json:
                              schema:
                                type: array
                                items:
                                  type: object
                                  properties:
                                    id:
                                      type: integer
                                      example: 101
                                    name:
                                      type: string
                                      example: Fluffy
                        '500':
                          description: Unexpected error
                    post:
                      summary: Create a pet
                      responses:
                        '201':
                          description: Pet created
                """;

        List<StubMapping> stubs = generator.generateStubs(yaml);

        assertThat(stubs).hasSize(3);

        // Verify GET /pets 200
        StubMapping get200 = stubs.stream()
                .filter(s -> s.getRequest().getMethod().equals(RequestMethod.GET)
                        && s.getResponse().getStatus() == 200)
                .findFirst()
                .orElseThrow();

        assertThat(get200.getName()).isEqualTo("[GET 200] List all pets");
        assertThat(get200.getRequest().getUrl()).isEqualTo("/pets");
        assertThat(get200.getResponse().getHeaders().getHeader("Content-Type").firstValue())
                .isEqualTo("application/json");
        assertThat(get200.getResponse().getBody()).contains("\"id\" : 101");
        assertThat(get200.getResponse().getBody()).contains("\"name\" : \"Fluffy\"");
        assertThat(get200.getMetadata().getString("source")).isEqualTo("openapi");
        assertThat(get200.getMetadata().getString("operationId")).isEqualTo("listPets");
        assertThat(get200.getMetadata().getString("project")).isEqualTo("Simple Petstore");
        assertThat(get200.getMetadata().getString("api")).isEqualTo("pets");

        // Verify POST /pets 201
        StubMapping post201 = stubs.stream()
                .filter(s -> s.getRequest().getMethod().equals(RequestMethod.POST)
                        && s.getResponse().getStatus() == 201)
                .findFirst()
                .orElseThrow();
        assertThat(post201.getName()).isEqualTo("[POST 201] Create a pet");
    }

    @Test
    @DisplayName("Should use urlPathTemplate and required query and header parameters")
    void shouldHandlePathParametersAndRequiredHeaders() {
        String yaml =
                """
                openapi: 3.0.3
                info:
                  title: Users API
                  version: 1.0.0
                paths:
                  /users/{userId}:
                    get:
                      summary: Get user by ID
                      parameters:
                        - name: userId
                          in: path
                          required: true
                          schema:
                            type: string
                        - name: X-Tenant-Id
                          in: header
                          required: true
                          schema:
                            type: string
                        - name: includeDetails
                          in: query
                          required: true
                          schema:
                            type: boolean
                      responses:
                        '200':
                          description: User found
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  id:
                                    type: string
                                    format: uuid
                                  email:
                                    type: string
                                    format: email
                                  created:
                                    type: string
                                    format: date-time
                """;

        List<StubMapping> stubs = generator.generateStubs(yaml);

        assertThat(stubs).hasSize(1);
        StubMapping stub = stubs.getFirst();

        assertThat(stub.getRequest().getUrlPathTemplate()).isEqualTo("/users/{userId}");
        assertThat(stub.getRequest().getHeaders()).containsKey("X-Tenant-Id");
        assertThat(stub.getRequest().getQueryParameters()).containsKey("includeDetails");

        String body = stub.getResponse().getBody();
        assertThat(body).contains("3fa85f64-5717-4562-b3fc-2c963f66afa6"); // uuid format
        assertThat(body).contains("user@example.com"); // email format
        assertThat(body).contains("2026-10-04T12:00:00Z"); // date-time format
    }

    @Test
    @DisplayName("Should filter to only success responses when option is set")
    void shouldFilterToSuccessOnly() {
        String yaml =
                """
                openapi: 3.0.3
                info:
                  title: Sample API
                  version: 1.0.0
                paths:
                  /test:
                    get:
                      responses:
                        '200':
                          description: Success
                        '400':
                          description: Bad Request
                        '500':
                          description: Error
                """;

        OpenApiStubOptions options =
                OpenApiStubOptions.builder().includeOnlySuccessResponses(true).build();
        OpenApiStubGenerator successOnlyGenerator = new OpenApiStubGenerator(options);

        List<StubMapping> stubs = successOnlyGenerator.generateStubs(yaml);
        assertThat(stubs).hasSize(1);
        assertThat(stubs.getFirst().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Should resolve component schema $ref references")
    void shouldResolveComponentRefs() {
        String yaml =
                """
                openapi: 3.0.3
                info:
                  title: Ref API
                  version: 1.0.0
                paths:
                  /orders:
                    get:
                      responses:
                        '200':
                          description: Orders
                          content:
                            application/json:
                              schema:
                                $ref: '#/components/schemas/Order'
                components:
                  schemas:
                    Order:
                      type: object
                      properties:
                        orderId:
                          type: integer
                          default: 9999
                        status:
                          type: string
                          default: PENDING
                """;

        List<StubMapping> stubs = generator.generateStubs(yaml);
        assertThat(stubs).hasSize(1);
        String body = stubs.getFirst().getResponse().getBody();
        assertThat(body).contains("\"orderId\" : 9999");
        assertThat(body).contains("\"status\" : \"PENDING\"");
    }

    @Test
    @DisplayName(
            "Should synthesize example request with substituted path params, required headers and body for POST endpoint")
    void shouldSynthesizeExampleRequestForPostEndpoint() {
        String yaml =
                """
                openapi: 3.0.3
                info:
                  title: Coffee API
                  version: 1.0.0
                paths:
                  /coffees/{roastType}:
                    post:
                      summary: Create a new coffee
                      parameters:
                        - name: roastType
                          in: path
                          required: true
                          example: espresso
                          schema:
                            type: string
                        - name: X-Store-Id
                          in: header
                          required: true
                          example: store-42
                          schema:
                            type: string
                        - name: notify
                          in: query
                          required: true
                          example: true
                          schema:
                            type: boolean
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema:
                              type: object
                              properties:
                                name:
                                  type: string
                                  example: Mocha Supreme
                                price:
                                  type: number
                                  example: 5.25
                      responses:
                        '201':
                          description: Coffee created
                """;

        List<StubMapping> stubs = generator.generateStubs(yaml);
        assertThat(stubs).hasSize(1);

        StubMapping stub = stubs.getFirst();
        assertThat(stub.getMetadata()).isNotNull();

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> exampleRequest =
                (java.util.Map<String, Object>) stub.getMetadata().get("exampleRequest");

        assertThat(exampleRequest).isNotNull();
        assertThat(exampleRequest.get("path")).isEqualTo("/coffees/espresso?notify=true");

        @SuppressWarnings("unchecked")
        java.util.Map<String, String> headers = (java.util.Map<String, String>) exampleRequest.get("headers");
        assertThat(headers).containsEntry("Content-Type", "application/json");
        assertThat(headers).containsEntry("X-Store-Id", "store-42");

        String reqBody = (String) exampleRequest.get("body");
        assertThat(reqBody).contains("\"name\" : \"Mocha Supreme\"");
        assertThat(reqBody).contains("\"price\" : 5.25");
    }
}
