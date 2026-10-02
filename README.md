# WireMock Extensions Suite

A collection of lightweight, production-grade quality of life (QoL) extensions for **WireMock** and **WireMock Standalone**.

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Java](https://img.shields.io/badge/Java-21+-orange.svg)](https://adoptium.net/)

---

## Modules

| Module | Description | Artifact Coordinates |
| :--- | :--- | :--- |
| **`wiremock-extension-json-logging`** | Single-line structured JSON logging for verbose/standard mode + corporate MDC request header extraction (`x_correlation_id`, `x_tenant_id`). | `io.github.mattiasgreen:wiremock-extension-json-logging:0.1.0` |
| **`wiremock-extension-otel`** | In-process OpenTelemetry metrics recording (`wiremock_requests_total`, latency histograms) + Prometheus scrape route (`/__admin/metrics/prometheus`). | `io.github.mattiasgreen:wiremock-extension-otel:0.1.0` |
| **`wiremock-extension-ui`** | Pure vanilla JavaScript Single-Page App embedded directly in the JAR at `/__admin/ui` (zero external CDN or Node dependencies). | `io.github.mattiasgreen:wiremock-extension-ui:0.1.0` |
| **`wiremock-extension-bundle`** | All-in-one umbrella JAR combining all three extensions with zero-touch SPI auto-discovery. | `io.github.mattiasgreen:wiremock-extension-bundle:0.1.0` |

---

## 🚀 Quick Start with WireMock Standalone

### Option 1: Drop-in Classpath (All-in-One Bundle)

Run the official WireMock standalone JAR with the extension bundle on the classpath:

```bash
java -cp "wiremock-extension-bundle-0.1.0.jar:wiremock-standalone-3.12.1.jar" \
  com.github.tomakehurst.wiremock.standalone.WireMockServerRunner \
  --port 8080 \
  --verbose
```

All extensions auto-register via Java Service Provider Interface (SPI):
- **Stub Viewer UI**: `http://localhost:8080/__admin/ui`
- **Prometheus Metrics**: `http://localhost:8080/__admin/metrics/prometheus`
- **Admin API**: `http://localhost:8080/__admin/mappings`

---

## 🛠️ Feature Deep Dive

### 1. JSON Logging & MDC Header Tags (`wiremock-extension-json-logging`)
Intercepts incoming HTTP headers (e.g. `X-Correlation-Id`, `X-Tenant-Id`, `traceparent`), normalizes them to snake_case (`x_correlation_id`), and logs single-line JSON events per transaction:

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
    "headers": { "X-Correlation-Id": "corr-9921", "X-Tenant-Id": "acme" }
  },
  "response": { "status": 200 },
  "x_correlation_id": "corr-9921",
  "x_tenant_id": "acme"
}
```

- In **verbose mode** (`--verbose` or `-Dwiremock.verbose.json=true`), full request/response bodies are embedded cleanly within the JSON object.
- Automatically clears MDC post-serve to prevent thread pollution in Jetty's thread pool.

### 2. OpenTelemetry & Prometheus Metrics (`wiremock-extension-otel`)
Exposes Prometheus/OpenMetrics formatted metrics at `GET /__admin/metrics/prometheus`:
- `wiremock_requests_total{http_request_method="GET",http_response_status_code="200",wiremock_matched="true",wiremock_stub_name="List Users"}`
- `wiremock_request_duration_ms` (latency histogram with standard Prometheus bucket distributions)
- `wiremock_requests_unmatched_total` (counter for 404 unmatched requests)

### 3. Embedded Vanilla JS UI (`wiremock-extension-ui`)
- Fast, air-gapped safe (no external CDNs, completely self-contained in JAR, < 30 KB).
- Modern dark-theme responsive interface.
- Instant search filter by method, URL path, or stub name.
- JSON mapping inspector with one-click clipboard copy.
- Real-time request journal with matched vs unmatched badges and response timings.

---

## 🧪 Building & Running Tests

```bash
# Run tests across all modules
./gradlew test

# Run interactive standalone server with demo mocks
./gradlew :wiremock-extension-bundle:runStandalone
```

---

## 📦 Publishing to Maven Central

1. Ensure your namespace (`io.github.mattiasgreen`) is claimed at [central.sonatype.com](https://central.sonatype.com/).
2. Set your environment variables:
   ```bash
   export ORG_GRADLE_PROJECT_sonatypeUsername="your-portal-token-username"
   export ORG_GRADLE_PROJECT_sonatypePassword="your-portal-token-password"
   export ORG_GRADLE_PROJECT_signingKey="your-ascii-armored-gpg-key"
   export ORG_GRADLE_PROJECT_signingPassword="your-gpg-passphrase"
   ```
3. Publish to local maven or Central:
   ```bash
   ./gradlew publishToMavenLocal
   ```

---

## License

[Apache License 2.0](LICENSE)
