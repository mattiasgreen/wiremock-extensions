# WireMock Extensions Bundle

An umbrella module that packages all extensions (`json-logging`, `otel`, `openapi`, `stateful` (experimental), `tcp-netem`, `casual`, and `ui`) into a single artifact with zero-touch auto-discovery.

## Problem Statement

WireMock users who want structured JSON logging, Prometheus metrics, OpenAPI synthesis, dynamic stateful simulation, TCP network emulation, Casual protocol simulation, and the web UI typically need to manage separate dependencies or download separate JAR files for the CLI classpath.

## How It Addresses the Problem

The bundle module aggregates:
- `wiremock-extension-json-logging`
- `wiremock-extension-otel`
- `wiremock-extension-openapi`
- `wiremock-extension-stateful` (Experimental / Alpha Prototype)
- `wiremock-extension-tcp-netem`
- `wiremock-extension-casual`
- `wiremock-extension-ui`

It re-exports all extensions under a single dependency coordinate and includes a pre-configured standalone runner for instant local development.

## Features

- **Single Dependency**: Includes all extensions with a single artifact coordinate.
- **Unified Java SPI Auto-Registration**: All extensions register automatically via `ExtensionFactory` when the bundle JAR is on the classpath.
- **Built-in Standalone Dev Server**: Includes `StandaloneDevServer` which launches WireMock on port 8080 with all extensions active, TCP NetEm ingress, Casual TCP gateway, and a clean slate (no pre-populated stubs). Sample stubs can be loaded on demand from [`wiremock-examples/example-stubs.json`](../wiremock-examples/example-stubs.json).

## Usage

### Dependency Coordinates

**Gradle**:
```groovy
implementation 'io.github.mattiasgreen:wiremock-extension-bundle:1.7.0'
```

**Maven**:
```xml
<dependency>
    <groupId>io.github.mattiasgreen</groupId>
    <artifactId>wiremock-extension-bundle</artifactId>
    <version>1.7.0</version>
</dependency>
```

### Standalone CLI

Run WireMock Standalone with the bundle JAR on the classpath:

```bash
java -cp "wiremock-extension-bundle-1.7.0.jar:wiremock-standalone-3.12.1.jar" \
  com.github.tomakehurst.wiremock.standalone.WireMockServerRunner \
  --port 8080 \
  --verbose
```

Available endpoints:
- **Web UI**: `http://localhost:8080/__admin/ui/`
- **Prometheus Metrics**: `http://localhost:8080/__admin/metrics/prometheus`
- **TCP NetEm Journal**: `http://localhost:8080/__admin/tcp-netem/journal`
- **Casual Config API**: `http://localhost:8080/__admin/casual/config`
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

### Port & Extension Configuration

Extending WireMock Standalone, the **main WireMock HTTP port** is configured via standard program arguments (e.g. `--port 8080`), while extension listener ports (**NetEm** and **Casual**) are simplified to configure strictly via **environment properties**:

| Setting / Port | Configuration Mechanism | Environment Property | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| **Main WireMock HTTP** | Program Argument (WireMock Standalone) | `--port <port>` | `8080` | Core WireMock HTTP listener, Admin API, and Web UI. |
| **TCP NetEm Ingress** | Environment Property | `WIREMOCK_NETEM_PORT` | `8081` | Dedicated Layer 4 TCP proxy port for fault injection. |
| **Casual TCP Gateway** | Environment Property | `WIREMOCK_CASUAL_PORT` | `7772` | Dedicated raw TCP port for Casual / XATMI gateway. |
| **Casual Enabled** | Environment Property | `WIREMOCK_CASUAL_ENABLED` | `true` | Enables or disables the Casual TCP gateway. |
| **Casual Upstream Timeout** | Environment Property | `WIREMOCK_CASUAL_UPSTREAM_TIMEOUT` | `4` | Seconds before timing out slow upstream Casual services. |

Dynamic upstream proxying and recording are declared directly in standard WireMock stub mappings (`targetBaseUrl: "casual://host:port"` with `metadata.mode = "proxy"`), with multi-upstream 2PC XA stickiness and human-readable Request Journal logging. Settings can be inspected via:
- Inspect configuration: `GET http://localhost:8080/__admin/casual/config`

## Build

```bash
./gradlew :wiremock-extension-bundle:build
```
