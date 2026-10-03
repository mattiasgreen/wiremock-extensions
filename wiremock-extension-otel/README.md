# WireMock OpenTelemetry & Prometheus Metrics Extension

In-process OpenTelemetry metrics recording and Prometheus scrape endpoint for WireMock.

## Problem Statement

When using WireMock as a mock server in performance tests, integration environments, or staging clusters, visibility into server throughput, error rates, and response latency is critical. WireMock core does not include native Prometheus metrics or OpenTelemetry instrumentations out-of-the-box, making it difficult to monitor mock traffic or detect when stubs fail to match.

## How It Addresses the Problem

This extension registers a WireMock `PostServeAction` listener backed by an in-process OpenTelemetry SDK meter. Every served request increments request counters and records latency distributions in real time.

An admin endpoint is mounted at `GET /__admin/metrics/prometheus` via WireMock's `AdminApiExtension` SPI, allowing Prometheus agents or OpenTelemetry Collectors to scrape metrics directly without requiring sidecars or external metric collectors.

## Features

- **Standard Prometheus Scrape Endpoint**: Accessible at `GET /__admin/metrics/prometheus` in standard Prometheus/OpenMetrics text exposition format.
- **Request Counters (`wiremock_requests_total`)**: Labeled by:
  - `http_request_method`: HTTP method (`GET`, `POST`, etc.)
  - `http_response_status_code`: HTTP status code (`200`, `404`, `500`, etc.)
  - `wiremock_matched`: Match status (`true` or `false`)
  - `wiremock_stub_name`: Associated stub name (if defined)
- **Latency Histogram (`wiremock_request_duration_ms`)**: Standard Prometheus bucket distribution for request durations in milliseconds.
- **Unmatched Request Counter (`wiremock_requests_unmatched_total`)**: Dedicated counter to alert on unexpected or unmatched requests.
- **Zero External Infrastructure**: Uses an in-process OpenTelemetry registry and embedded Prometheus exporter with minimal overhead.

## Sample Prometheus Output

```text
# HELP wiremock_requests_total Total number of HTTP requests processed by WireMock
# TYPE wiremock_requests_total counter
wiremock_requests_total{http_request_method="GET",http_response_status_code="200",wiremock_matched="true",wiremock_stub_name="List Users"} 42.0

# HELP wiremock_requests_unmatched_total Total number of unmatched HTTP requests (404)
# TYPE wiremock_requests_unmatched_total counter
wiremock_requests_unmatched_total 3.0

# HELP wiremock_request_duration_ms HTTP request execution duration in milliseconds
# TYPE wiremock_request_duration_ms histogram
wiremock_request_duration_ms_bucket{le="5.0"} 12.0
wiremock_request_duration_ms_bucket{le="10.0"} 35.0
wiremock_request_duration_ms_bucket{le="25.0"} 42.0
wiremock_request_duration_ms_count 42.0
wiremock_request_duration_ms_sum 328.5
```

## Usage

### Dependency Coordinates

**Gradle**:
```groovy
implementation 'io.github.mattiasgreen:wiremock-extension-otel:0.1.0'
```

**Maven**:
```xml
<dependency>
    <groupId>io.github.mattiasgreen</groupId>
    <artifactId>wiremock-extension-otel</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Standalone CLI

Place the extension JAR on the classpath alongside WireMock Standalone:

```bash
java -cp "wiremock-extension-otel-0.1.0.jar:wiremock-standalone-3.12.1.jar" \
  com.github.tomakehurst.wiremock.standalone.WireMockServerRunner \
  --port 8080
```

Scrape metrics at:
```bash
curl http://localhost:8080/__admin/metrics/prometheus
```

### Embedded Java

```java
import com.github.mattiasgreen.wiremock.otel.OtelMetricsListener;
import com.github.mattiasgreen.wiremock.otel.PrometheusAdminEndpoint;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

WireMockServer wm = new WireMockServer(WireMockConfiguration.options()
    .port(8080)
    .extensions(new OtelMetricsListener(), new PrometheusAdminEndpoint()));
wm.start();
```

When the JAR is on the classpath, the extension is discovered and registered automatically via Java SPI.

### Prometheus Scrape Configuration

```yaml
scrape_configs:
  - job_name: 'wiremock'
    metrics_path: '/__admin/metrics/prometheus'
    static_configs:
      - targets: ['localhost:8080']
```

## Build

```bash
./gradlew :wiremock-extension-otel:build
```
