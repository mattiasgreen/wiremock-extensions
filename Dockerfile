# WireMock Standalone with Extensions Suite
# Multi-protocol simulation runtime: HTTP REST, Casual/XATMI RPC, L4 TCP NetEm, OpenTelemetry, and UI

FROM eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.title="wiremock-extensions-standalone" \
      org.opencontainers.image.description="WireMock Standalone bundled with complete extension suite (JSON Logging, Otel, OpenAPI, Stateful, TCP NetEm, Casual Gateway, and UI)" \
      org.opencontainers.image.url="https://github.com/mattiasgreen/wiremock-extensions" \
      org.opencontainers.image.authors="Mattias Green"

# Create unprivileged system user and WireMock directories
RUN addgroup -S wiremock && adduser -S wiremock -G wiremock && \
    mkdir -p /home/wiremock/mappings /home/wiremock/__files && \
    chown -R wiremock:wiremock /home/wiremock

WORKDIR /home/wiremock

# Copy prebuilt fat jar artifact
ARG JAR_FILE=wiremock-extension-bundle/build/libs/wiremock-extension-bundle-*-standalone.jar
COPY ${JAR_FILE} /home/wiremock/wiremock-standalone.jar

USER wiremock

# Expose standard WireMock HTTP port, TCP NetEm ingress port, and Casual TCP gateway port
EXPOSE 8080 8081 7772

# Declare volume mount points for stub mappings and binary payload files
VOLUME ["/home/wiremock/mappings", "/home/wiremock/__files"]

ENTRYPOINT ["java", "-jar", "/home/wiremock/wiremock-standalone.jar"]
CMD ["--port", "8080"]
