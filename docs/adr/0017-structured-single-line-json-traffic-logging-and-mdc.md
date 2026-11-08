# 17. Structured Single-Line JSON Traffic Logging and MDC Propagation

Date: 2026-11-07

## Status

Accepted

## Context

Production Kubernetes and cloud observability systems (ELK, Datadog, Splunk, CloudWatch) require structured, single-line JSON log events to index, filter, and trace requests across microservice boundaries.

Standard WireMock console logging emits multi-line plain text blocks that fragment across container log shippers, making automated ingestion difficult. Furthermore, distributed tracing headers (`x-correlation-id`, `x-request-id`, `x-tenant-id`) received by WireMock are lost from thread context, breaking end-to-end trace correlation in shared enterprise environments.

## Decision

We introduce `wiremock-extension-json-logging`, a high-throughput, structured single-line JSON traffic logging extension with corporate header MDC propagation:

### 1. Ingress Header Normalization and MDC Context (`MdcRequestFilter`)
- Hooks into `RequestFilterV2` at the very front of the request pipeline.
- Normalizes corporate and standard tracing headers (case-insensitively):
  - `x-correlation-id` / `correlation-id`
  - `x-request-id` / `request-id`
  - `x-tenant-id` / `tenant-id`
  - `x-session-id`
- Populates SLF4J Mapped Diagnostic Context (MDC) keys automatically before stub evaluation or downstream forwarding begins.

### 2. Single-Line Structured JSON Emission (`JsonLoggingListener`)
- Implements `ServeEventListener` to capture every served request and response event.
- Serializes complete HTTP metadata into a single-line JSON structure (`WireMockLogEvent`):
  - Request: `method`, `url`, `path`, `headers`, `queryParams`, `clientIp`, `body` (with configurable size truncation).
  - Response: `status`, `durationMs`, `headers`, `body`.
  - WireMock Metadata: `matchedStubId`, `stubName`, `wasMatched`.
  - Contextual MDC: `correlationId`, `tenantId`.
- Guarantees zero log splitting and zero multi-line corruption during ingestion by log daemons (Fluentbit, Logstash, Vector).

### 3. Log Injection and CRLF Sanitization
- All dynamic inputs (headers, paths, parameters) are sanitized before logging to prevent CRLF log-injection attacks (`\r`, `\n` normalization).

## Consequences

### Positive
- **Cloud-Native Observability**: 100% compliant with standard container logging parsers.
- **Trace Continuity**: Correlation and tenant IDs persist through all log messages emitted during request processing.
- **Deterministic Field Indexing**: Log queries in Datadog/Elasticsearch filter directly on `status`, `durationMs`, and `matchedStubId`.

### Negative / Trade-offs
- High request volumes generate large log streams; requires log rotation or sampling in high-load benchmark environments.
- Request/response bodies require truncation thresholds to avoid exceeding container stdout pipe buffers.
