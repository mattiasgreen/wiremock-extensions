# WireMock Extensions Suite

A collection of lightweight, production-grade extensions for [WireMock](https://wiremock.org/) and WireMock Standalone.

Each extension is **modular and standalone**: you can pick and choose only the extensions you need, or use the all-in-one bundle to include everything.

## Modules

| Module | Description | Documentation |
| :--- | :--- | :--- |
| **`wiremock-extension-json-logging`** | Single-line structured JSON logging for standard/verbose mode with automatic MDC header extraction (`x_correlation_id`, `x_tenant_id`). | [Module README](wiremock-extension-json-logging/README.md) |
| **`wiremock-extension-otel`** | In-process OpenTelemetry metrics recording (`wiremock_requests_total`, latency histogram) with a Prometheus scrape route (`/__admin/metrics/prometheus`). | [Module README](wiremock-extension-otel/README.md) |
| **`wiremock-extension-openapi`** | OpenAPI 3.0 / 3.1 specification parser and stub synthesizer with `POST /__admin/openapi/import` route and format-aware mock data generation. | [Module README](wiremock-extension-openapi/README.md) |
| **`wiremock-extension-ui`** | Embedded WireMock Console web UI at `/__admin/ui/` with stub explorer, OpenAPI spec importer, scenario DAG state machine visualizer, request journal, and interactive HTTP tester (zero external dependencies). | [Module README](wiremock-extension-ui/README.md) |
| **`wiremock-extension-bundle`** | Umbrella module aggregating all extensions into a single JAR with unified SPI auto-discovery and a local dev runner. | [Module README](wiremock-extension-bundle/README.md) |
| **`wiremock-examples`** | Complete Case Management System reference implementation demonstrating stateful scenario modeling, parallel case collision pitfalls, best-practice isolated scenarios, and Playwright verification. | [Examples Subproject](wiremock-examples/README.md) |

## Quick Preview

| Overview Tour | OpenAPI Spec Import & Testing Workflow |
| :--- | :--- |
| ![WireMock Console Overview](docs/images/demo-ui-tour.gif) | ![OpenAPI Spec Import Workflow](docs/images/demo-openapi-import.gif) |

## Building

Prerequisites: Java 21+ and Gradle (wrapper included).

```bash
# Build all modules and run all tests
./gradlew build

# Build a specific extension module
./gradlew :wiremock-extension-json-logging:build
./gradlew :wiremock-extension-otel:build
./gradlew :wiremock-extension-openapi:build
./gradlew :wiremock-extension-ui:build
./gradlew :wiremock-extension-bundle:build

# Run all tests across the suite
./gradlew test
```

## Usage Options

### 1. All-in-One Bundle

If you want all extensions together, add `wiremock-extension-bundle` to your project or classpath:

**Gradle**:
```groovy
implementation 'io.github.mattiasgreen:wiremock-extension-bundle:0.1.0'
```

**WireMock Standalone CLI**:
```bash
java -cp "wiremock-extension-bundle-0.1.0.jar:wiremock-standalone-3.12.1.jar" \
  com.github.tomakehurst.wiremock.standalone.WireMockServerRunner \
  --port 8080 \
  --verbose
```

All extensions auto-register via Java Service Provider Interface (SPI):
- Web UI: `http://localhost:8080/__admin/ui/`
- Prometheus Metrics: `http://localhost:8080/__admin/metrics/prometheus`
- OpenAPI Spec Importer: `http://localhost:8080/__admin/openapi/import`
- Admin API: `http://localhost:8080/__admin/mappings`

### 2. Individual Extensions

Each extension can be used independently without pulling in the others:

- **JSON Logging Only**:
  ```groovy
  implementation 'io.github.mattiasgreen:wiremock-extension-json-logging:0.1.0'
  ```
  See [wiremock-extension-json-logging/README.md](wiremock-extension-json-logging/README.md) for details.

- **OpenTelemetry & Prometheus Only**:
  ```groovy
  implementation 'io.github.mattiasgreen:wiremock-extension-otel:0.1.0'
  ```
  See [wiremock-extension-otel/README.md](wiremock-extension-otel/README.md) for details.

- **Web UI Only**:
  ```groovy
  implementation 'io.github.mattiasgreen:wiremock-extension-ui:0.1.0'
  ```
  See [wiremock-extension-ui/README.md](wiremock-extension-ui/README.md) for details.

### 3. Local Development Runner

To launch WireMock with all extensions and pre-loaded sample stubs for local testing:

```bash
./gradlew :wiremock-extension-bundle:runStandalone
```

The server starts on port `8080` with filesystem hot-reloading for UI development.

### 4. Zero-Build Consumer Runner

You can run WireMock Standalone with the complete extension bundle loaded in any empty folder using a single minimal `build.gradle` file, without cloning or building this repository:

```groovy
plugins {
    id 'application'
}

repositories {
    mavenCentral()
}

dependencies {
    runtimeOnly 'org.wiremock:wiremock-standalone:3.12.1'
    runtimeOnly 'io.github.mattiasgreen:wiremock-extension-bundle:0.1.0'
}

application {
    mainClass = 'com.github.tomakehurst.wiremock.standalone.WireMockServerRunner'
}
```

Run with:
```bash
gradle run --args="--port 8080 --verbose"
```

Because all extensions implement `ExtensionFactory` via Java SPI, WireMock automatically discovers and loads the Web UI, metrics, and OpenAPI endpoints on startup.

### 5. Docker Reference Architecture

To deploy a containerized mock server with the extension bundle, you can assemble an image directly using published artifacts:

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /wiremock

ARG WIREMOCK_VERSION=3.12.1
ARG BUNDLE_VERSION=0.1.0

RUN wget -q "https://repo1.maven.org/maven2/org/wiremock/wiremock-standalone/${WIREMOCK_VERSION}/wiremock-standalone-${WIREMOCK_VERSION}.jar" -O wiremock.jar \
 && wget -q "https://repo1.maven.org/maven2/io/github/mattiasgreen/wiremock-extension-bundle/${BUNDLE_VERSION}/wiremock-extension-bundle-${BUNDLE_VERSION}.jar" -O bundle.jar

EXPOSE 8080
ENTRYPOINT ["java", "-cp", "wiremock.jar:bundle.jar", "com.github.tomakehurst.wiremock.standalone.WireMockServerRunner", "--port", "8080"]
```

## License

[Apache License 2.0](LICENSE)
