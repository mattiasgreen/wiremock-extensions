package com.github.mattiasgreen.wiremock.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpenApiSpecInspectorTest {

    private final OpenApiSpecInspector inspector = new OpenApiSpecInspector();

    @Test
    @DisplayName("Should extract metadata, servers list, and operation counts from OpenAPI YAML")
    void shouldExtractServersAndMetadata() {
        String yaml =
                """
                openapi: 3.0.3
                info:
                  title: Petstore Gateway
                  version: 2.1.0
                  description: Gateway API for all pets
                servers:
                  - url: https://api.petstore.io/v2
                    description: Production Server
                  - url: https://staging.petstore.io/v2
                    description: Staging Environment
                paths:
                  /pets:
                    get:
                      summary: List pets
                      responses:
                        '200':
                          description: Ok
                    post:
                      summary: Create pet
                      responses:
                        '201':
                          description: Created
                  /pets/{id}:
                    get:
                      summary: Get pet
                      responses:
                        '200':
                          description: Ok
                """;

        OpenApiSpecInfo info = inspector.inspect(yaml);

        assertThat(info.title()).isEqualTo("Petstore Gateway");
        assertThat(info.version()).isEqualTo("2.1.0");
        assertThat(info.description()).isEqualTo("Gateway API for all pets");
        assertThat(info.totalOperations()).isEqualTo(3);
        assertThat(info.servers()).hasSize(2);
        assertThat(info.servers().get(0).url()).isEqualTo("https://api.petstore.io/v2");
        assertThat(info.servers().get(0).description()).isEqualTo("Production Server");
        assertThat(info.servers().get(1).url()).isEqualTo("https://staging.petstore.io/v2");
        assertThat(info.servers().get(1).description()).isEqualTo("Staging Environment");
    }
}
