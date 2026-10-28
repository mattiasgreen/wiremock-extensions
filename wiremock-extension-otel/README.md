# WireMock OpenTelemetry & Prometheus Metrics Extension

In-process OpenTelemetry metrics recording and Prometheus scrape endpoint for WireMock.

## Problem Statement

When using WireMock as a mock server in performance tests, integration environments, or staging clusters, visibility into server throughput, error rates, and response latency is critical. WireMock core does not include native Prometheus metrics or OpenTelemetry instrumentations out-of-the-box, making it difficult to monitor mock traffic or detect when stubs fail to match.

## How It Addresses the Problem

This extension registers a WireMock `PostServeAction` listener backed by an in-process OpenTelemetry SDK meter. Every served request increments request counters and records latency distributions in real time.

An admin endpoint is mounted at `GET /__admin/metrics/prometheus` via WireMock's `AdminApiExtension` SPI, allowing Prometheus agents or OpenTelemetry Collectors to scrape metrics directly without requiring sidecars or external metric collectors.

## Traffic Routing & Observability Model

Every request served through WireMock is classified across protocol and execution outcome:

```text
                          ┌──► Stubbed (In-memory mock served)
                          │
Incoming Request ─────────┼──► Proxied (Forwarded to upstream baseUrl)
                          │         ├── Proxied Passthrough
                          │         └── Proxied + Recorded (Traffic Snapshot)
                          │
                          ├──► Unmatched (404 diff / rejection)
                          │
                          └──► Fault / NetEm Injected (Dropped / Reset / Toxic delay)
```

## Features

- **Standard Prometheus Scrape Endpoint**: Accessible at `GET /__admin/metrics/prometheus` (and alias `GET /__admin/metrics`) in standard Prometheus/OpenMetrics text exposition format.
- **Protocol & Traffic Breakdown**:
  - `protocol`: Protocol discriminator (`http`, `casual`, `grpc`).
  - `traffic_type`: Traffic routing mode (`stubbed`, `proxied`, `unmatched`, `fault`).
- **Request Counters (`wiremock_requests_total`)**: Labeled by:
  - `protocol`: Protocol name (`http`, `casual`, `grpc`)
  - `traffic_type`: Execution outcome (`stubbed`, `proxied`, `unmatched`, `fault`)
  - `wiremock_matched`: Match status (`true` or `false`)
  - `wiremock_stub_name`: Associated stub name (if defined)
  - `http_request_method`: HTTP method for HTTP protocol (`GET`, `POST`, etc.)
  - `http_response_status_code`: HTTP status code for HTTP protocol (`200`, `404`, `500`, etc.)
  - `target_upstream`: Proxied backend base URL (for `proxied` traffic)
  - `rpc_service` & `casual_error_state`: Service name and XATMI error state for Casual protocol
- **Latency Histograms**:
  - `wiremock_request_duration_ms`: Total execution duration in milliseconds.
  - `wiremock_upstream_duration_ms`: Dedicated histogram for upstream wait time on proxied traffic.
- **Unmatched Request Counter (`wiremock_requests_unmatched_total`)**: Dedicated counter to alert on unexpected or unmatched requests.
- **Runtime Capacity & Health Gauges**:
  - `wiremock_journal_size_requests`: Active in-memory request journal count.
  - `wiremock_journal_capacity`: Configured journal limit (`-1` if unbounded).
  - `wiremock_stubs_total{status="active"}`: Total registered active stub mappings.
- **Distributed Tracing (W3C, Casual RPC & OTLP)**:
  - Automatically extracts incoming W3C `traceparent` and `tracestate` headers to preserve parent-child trace context across both HTTP and Casual JSON requests.
  - Generates OpenTelemetry `SERVER` spans:
    - **HTTP Spans**: Standard HTTP semantic conventions (`http.request.method`, `http.response.status_code`, `url.path`, `url.query`, `client.address`).
    - **Casual RPC Spans**: Standard RPC semantic conventions with span name `casual.<service>` (e.g. `casual.order_service`), `rpc.system="casual"`, `rpc.service="<service>"`, `rpc.method="tpcall"`, `casual.error_state="<status>"`, and XA transaction correlation (`casual.xid`, `casual.xid.gtrid`, `casual.xid.bqual`, `casual.xid.format_id`).
  - Enriches spans with `wiremock.traffic_type`, `wiremock.protocol`, `wiremock.target_upstream`, `wiremock.matched`, `wiremock.stub_id`, `wiremock.stub_name`, `wiremock.scenario_name`, `wiremock.scenario_state`.
  - Automatically mirrors active `trace_id` and `span_id` into SLF4J MDC for unified structured JSON log correlation, with scoped cleanup preventing Netty worker/virtual thread leaks.
  - Marks spans as `ERROR` for HTTP 5xx responses, Casual error states (`!OK`), fault injections, or unmatched requests.
  - Optional OTLP gRPC export (`OTEL_EXPORTER_OTLP_ENDPOINT` or `-Dwiremock.otel.exporter.otlp.endpoint=http://localhost:4317`).
- **Zero External Infrastructure**: Uses an in-process OpenTelemetry registry and embedded Prometheus exporter with minimal overhead.

## Sample Prometheus Output

```text
# HELP wiremock_requests_total Total requests handled by WireMock
# TYPE wiremock_requests_total counter
wiremock_requests_total{protocol="http",traffic_type="stubbed",http_request_method="GET",http_response_status_code="200",wiremock_matched="true",wiremock_stub_name="List Users"} 42.0
wiremock_requests_total{protocol="http",traffic_type="proxied",target_upstream="http://payment-api:8080",http_request_method="POST",http_response_status_code="200",wiremock_matched="true",wiremock_stub_name="Proxy Payments"} 18.0
wiremock_requests_total{protocol="casual",traffic_type="stubbed",rpc_service="order_service",casual_error_state="OK",wiremock_matched="true",wiremock_stub_name="Casual Order Stub"} 7.0

# HELP wiremock_requests_unmatched_total Total requests that did not match any stub
# TYPE wiremock_requests_unmatched_total counter
wiremock_requests_unmatched_total{protocol="http",http_request_method="GET"} 3.0

# HELP wiremock_request_duration_ms WireMock request execution duration in milliseconds
# TYPE wiremock_request_duration_ms histogram
wiremock_request_duration_ms_bucket{protocol="http",traffic_type="stubbed",le="5.0"} 12.0
wiremock_request_duration_ms_count{protocol="http",traffic_type="stubbed"} 42.0
wiremock_request_duration_ms_sum{protocol="http",traffic_type="stubbed"} 328.5

# HELP wiremock_upstream_duration_ms Upstream target execution duration in milliseconds for proxied requests
# TYPE wiremock_upstream_duration_ms histogram
wiremock_upstream_duration_ms_bucket{protocol="http",target_upstream="http://payment-api:8080",le="50.0"} 14.0
wiremock_upstream_duration_ms_count{protocol="http",target_upstream="http://payment-api:8080"} 18.0
wiremock_upstream_duration_ms_sum{protocol="http",target_upstream="http://payment-api:8080"} 840.2

# HELP wiremock_journal_size_requests Current count of requests in WireMock journal
# TYPE wiremock_journal_size_requests gauge
wiremock_journal_size_requests 67

# HELP wiremock_journal_capacity Maximum configured capacity for WireMock request journal (-1 if unbounded)
# TYPE wiremock_journal_capacity gauge
wiremock_journal_capacity -1

# HELP wiremock_stubs_total Total number of stub mappings in WireMock
# TYPE wiremock_stubs_total gauge
wiremock_stubs_total{status="active"} 12
```

## OTLP Exporter Configuration & Deployment Options

The extension supports dual deployment modes: **Pull (Prometheus scraping)** and **Push (OpenTelemetry OTLP)**.

### Configuration Properties

| Env Var | System Property | Default | Description |
| :--- | :--- | :--- | :--- |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `-Dwiremock.otel.exporter.otlp.endpoint` | *(none)* | e.g. `http://otel-collector:4317` (gRPC) or `http://otel-collector:4318` (HTTP) |
| `OTEL_SERVICE_NAME` | `-Dwiremock.otel.service.name` | `wiremock` | Logical service name for metrics and traces |
| `WIREMOCK_OTEL_TRACING_ENABLED` | `-Dwiremock.otel.tracing.enabled` | `true` | Enable or disable distributed server span creation |

### Deployment Topologies

1. **Local Dev / CI Scrape (Pull Mode)**:
   * Accessible out-of-the-box at `http://localhost:8080/__admin/metrics/prometheus`.
   * Requires zero sidecars or external collectors.
2. **Kubernetes DaemonSet / Sidecar (Push Mode)**:
   * Set `OTEL_EXPORTER_OTLP_ENDPOINT=http://${HOST_IP}:4317`.
   * WireMock streams W3C traces directly to OpenTelemetry Collector, Jaeger, or Datadog agent without exposing admin metrics ports.

## Usage

### Dependency Coordinates

**Gradle**:
```groovy
implementation 'io.github.mattiasgreen:wiremock-extension-otel:1.2.0'
```

**Maven**:
```xml
<dependency>
    <groupId>io.github.mattiasgreen</groupId>
    <artifactId>wiremock-extension-otel</artifactId>
    <version>1.2.0</version>
</dependency>
```

### Standalone CLI

Place the extension JAR on the classpath alongside WireMock Standalone:

```bash
java -cp "wiremock-extension-otel-1.2.0.jar:wiremock-standalone-3.12.1.jar" \
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
