# WireMock Extensions Bundle

An umbrella module that packages all extensions (`json-logging`, `otel`, and `ui`) into a single artifact with zero-touch auto-discovery.

## Problem Statement

WireMock users who want structured JSON logging, Prometheus metrics, and the web UI typically need to manage three separate dependencies or download three separate JAR files for the CLI classpath.

## How It Addresses the Problem

The bundle module aggregates:
- `wiremock-extension-json-logging`
- `wiremock-extension-otel`
- `wiremock-extension-ui`

It re-exports all three extensions under a single dependency coordinate and includes a pre-configured standalone runner for instant local development.

## Features

- **Single Dependency**: Includes all three extensions with a single artifact coordinate.
- **Unified Java SPI Auto-Registration**: All extensions register automatically when the bundle JAR is on the classpath.
- **Built-in Standalone Dev Server**: Includes `StandaloneDevServer` which launches WireMock on port 8080 with all extensions active and a clean slate (no pre-populated stubs). Sample stubs can be loaded on demand from [`wiremock-examples/example-stubs.json`](../wiremock-examples/example-stubs.json).

## Usage

### Dependency Coordinates

**Gradle**:
```groovy
implementation 'io.github.mattiasgreen:wiremock-extension-bundle:1.2.0'
```

**Maven**:
```xml
<dependency>
    <groupId>io.github.mattiasgreen</groupId>
    <artifactId>wiremock-extension-bundle</artifactId>
    <version>1.2.0</version>
</dependency>
```

### Standalone CLI

Run WireMock Standalone with the bundle JAR on the classpath:

```bash
java -cp "wiremock-extension-bundle-1.2.0.jar:wiremock-standalone-3.12.1.jar" \
  com.github.tomakehurst.wiremock.standalone.WireMockServerRunner \
  --port 8080 \
  --verbose
```

Available endpoints:
- **Web UI**: `http://localhost:8080/__admin/ui/`
- **Prometheus Metrics**: `http://localhost:8080/__admin/metrics/prometheus`
- **JSON Admin API**: `http://localhost:8080/__admin/mappings`

### Local Development Runner

Run the interactive standalone dev server directly from source:

```bash
./gradlew :wiremock-extension-bundle:runStandalone
```

The server starts clean with no pre-populated stubs. To populate sample stubs, import [`wiremock-examples/example-stubs.json`](../wiremock-examples/example-stubs.json) using cURL or the WireMock Console UI:

```bash
curl -X POST http://localhost:8080/__admin/mappings/import \
  -H "Content-Type: application/json" \
  -d @wiremock-examples/example-stubs.json
```

## Build

```bash
./gradlew :wiremock-extension-bundle:build
```
