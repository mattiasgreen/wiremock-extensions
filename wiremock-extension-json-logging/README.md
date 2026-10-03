# WireMock JSON Logging Extension

Structured, single-line JSON logging for WireMock with automatic Mapped Diagnostic Context (MDC) request header extraction.

## Problem Statement

Standard WireMock server logs output either human-readable plain text or multi-line text dumps. In modern containerized and distributed environments, log aggregators (such as Elasticsearch, Datadog, AWS CloudWatch, and Grafana Loki) require structured single-line JSON events per HTTP transaction.

Furthermore, tracking distributed requests across microservices requires propagating tracing headers (such as `X-Correlation-Id`, `X-Tenant-Id`, or W3C `traceparent`) into log entries and thread contexts without leaving residual state in Jetty's pooled worker threads.

## How It Addresses the Problem

The extension listens to request completion events via WireMock's `RequestFilterV2` and `PostServeAction` SPI:
- Normalizes incoming HTTP request headers into snake_case attributes.
- Injects normalized headers into the SLF4J Mapped Diagnostic Context (MDC) during request execution.
- Emits a single structured JSON event per request containing timestamps, duration, match status, stub name, and sanitized request/response summaries.
- Clears the MDC immediately after the request finishes to avoid thread context pollution in Jetty's thread pool.

## Features

- **Single-Line JSON Output**: Structured JSON log format optimized for automated log ingestion.
- **Header Extraction & Normalization**: Automatically extracts headers like `X-Correlation-Id`, `X-Request-Id`, `X-Tenant-Id`, and `traceparent`, converting them to clean keys (`x_correlation_id`, `x_tenant_id`).
- **Standard vs. Verbose Mode**:
  - *Standard mode* (default): Emits request method, URL, headers, status code, duration, and match status.
  - *Verbose mode* (`--verbose` flag or `-Dwiremock.verbose.json=true`): Includes full request and response bodies directly in the JSON object.
- **Zero Thread Leakage**: MDC entries are guaranteed to be cleared on completion.

## Sample Log Event

```json
{
  "timestamp": "2026-10-02T05:23:52.432Z",
  "event": "wiremock_request_served",
  "duration_ms": 14.0,
  "matched": true,
  "stub_name": "Get User Profile",
  "request": {
    "method": "GET",
    "url": "/api/v1/users/123",
    "headers": {
      "X-Correlation-Id": "corr-9921",
      "X-Tenant-Id": "acme"
    }
  },
  "response": {
    "status": 200
  },
  "x_correlation_id": "corr-9921",
  "x_tenant_id": "acme"
}
```

## Usage

### Dependency Coordinates

**Gradle**:
```groovy
implementation 'io.github.mattiasgreen:wiremock-extension-json-logging:0.1.0'
```

**Maven**:
```xml
<dependency>
    <groupId>io.github.mattiasgreen</groupId>
    <artifactId>wiremock-extension-json-logging</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Standalone CLI

Add the extension JAR to the classpath alongside WireMock Standalone. The extension will automatically be discovered via Java SPI:

```bash
java -cp "wiremock-extension-json-logging-0.1.0.jar:wiremock-standalone-3.12.1.jar" \
  com.github.tomakehurst.wiremock.standalone.WireMockServerRunner \
  --port 8080 \
  --verbose
```

### Embedded Java

When using embedded WireMock in tests or applications:

```java
import com.github.mattiasgreen.wiremock.logging.JsonLoggingListener;
import com.github.mattiasgreen.wiremock.logging.mdc.MdcRequestFilter;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

WireMockServer wm = new WireMockServer(WireMockConfiguration.options()
    .port(8080)
    .extensions(new MdcRequestFilter(), new JsonLoggingListener(true)));
wm.start();
```

If the JAR is present on the classpath, Java SPI will register the extension automatically without explicit configuration.

## Build

```bash
./gradlew :wiremock-extension-json-logging:build
```
