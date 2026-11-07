# 18. In-Process OpenTelemetry Metrics and W3C Tracing

Date: 2026-11-07

## Status

Accepted

## Context

When simulating complex microservice topologies in staging, test, or local development environments, WireMock acts as an upstream dependency for multiple communicating services. To evaluate service latency under realistic conditions, verify fault-tolerance mechanisms, and observe traffic distribution, developers need real-time operational metrics and distributed trace spans.

Traditional setups require attaching external Java agent attachments (`opentelemetry-javaagent.jar`) which introduce JVM startup latency, configuration complexity, and potential classloader conflicts. Furthermore, standard WireMock has no Prometheus scraping route for Kubernetes pods to scrape out of the box.

## Decision

We introduce `wiremock-extension-otel`, an in-process OpenTelemetry metrics and W3C distributed tracing extension:

### 1. In-Process Telemetry Engine (`WireMockTelemetryEngine`)
- Embeds standard OpenTelemetry SDK and Prometheus meters directly within the WireMock process.
- Implements `ServeEventListener` (`OtelMetricsListener` and `OtelTracingListener`) to instrument every served HTTP request without Java agent overhead.

### 2. High-Dimensional Traffic Classification (`TrafficClassification`)
- Classifies traffic across standardized dimensions:
  - `http.method`
  - `http.status_code`
  - `http.route` (normalized parameterized path)
  - `wiremock.stub_id` & `wiremock.stub_name`
  - `wiremock.matched` (`true` vs `false`)
  - `wiremock.project`
- Exposes Golden Signal counters and latency histograms:
  - `wiremock_requests_total`
  - `wiremock_request_duration_seconds`
  - `wiremock_unmatched_requests_total`

### 3. Native Prometheus Scrape Endpoint (`PrometheusAdminEndpoint`)
- Mounts `GET /__admin/metrics/prometheus` directly on the WireMock Admin API port.
- Emits Prometheus exposition text format 0.0.4.
- Enables Kubernetes annotations (`prometheus.io/scrape: "true"`, `prometheus.io/port: "8080"`, `prometheus.io/path: "/__admin/metrics/prometheus"`) to collect metrics instantly without sidecars.

### 4. W3C TraceContext Propagation (`WireMockRequestTextMapGetter`)
- Ingests incoming `traceparent` and `tracestate` headers via W3C TraceContext specifications.
- Creates in-process child spans for request matching and response synthesis, attributing parent client spans to mock execution.

## Consequences

### Positive
- **Zero Agent Friction**: Runs anywhere WireMock runs without extra JVM `-javaagent` command-line flags.
- **Drop-In Prometheus Scraping**: Standard Prometheus, Grafana, and Datadog agents scrape WireMock metrics directly.
- **Microsecond Precision**: In-process timers capture true internal serve latency.

### Negative / Trade-offs
- High cardinality metrics (e.g. unconstrained path parameters) can cause memory growth if routes are not normalized via `TrafficClassification`.
- Prometheus scraping endpoint is open by default on the Admin API port.
