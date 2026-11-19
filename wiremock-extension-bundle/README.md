# WireMock Extensions Bundle

An umbrella module that packages all extensions (`common`, `json-logging`, `otel`, `openapi`, `tcp-netem`, `casual`, `async` (beta), `journal` (beta), and `ui`) into a single artifact with zero-touch auto-discovery.

## Problem Statement

WireMock users who want structured JSON logging, Prometheus metrics, OpenAPI synthesis, TCP network emulation, Casual protocol simulation, asynchronous Kafka/queue mocking, Zstd request journaling, and the web UI typically need to manage separate dependencies or download separate JAR files for the CLI classpath.

## How It Addresses the Problem

The bundle module aggregates:
- `wiremock-extension-common`
- `wiremock-extension-json-logging`
- `wiremock-extension-otel`
- `wiremock-extension-openapi`
- `wiremock-extension-tcp-netem`
- `wiremock-extension-casual`
- `wiremock-extension-async` (Beta)
- `wiremock-extension-journal` (Beta)
- `wiremock-extension-ui`

It re-exports all extensions under a single dependency coordinate and includes a pre-configured standalone runner for instant local development.

## Features

- **Single Dependency**: Includes all extensions with a single artifact coordinate.
- **Unified Java SPI Auto-Registration**: All extensions register automatically via `ExtensionFactory` when the bundle JAR is on the classpath.
- **Built-in Standalone Dev Server**: Includes `StandaloneDevServer` which launches WireMock on port 8080 with all extensions active, TCP NetEm ingress, Casual TCP gateway, and a clean slate (no pre-populated stubs). Sample stubs can be loaded on demand from [`wiremock-examples/example-stubs.json`](../wiremock-examples/example-stubs.json).

## Usage

### Standalone Executable Fat-JAR

Build a self-contained, executable fat-jar combining WireMock Standalone and all extensions:

```bash
# Build the lean standalone fat-jar
./gradlew fatJar
# Or specifically for this module
./gradlew :wiremock-extension-bundle:fatJar

# Optional: build the opt-in fat-jar bundled with IBM MQ client libraries (ibm.mq.allclient)
./gradlew :wiremock-extension-bundle:fatJarIbmMq
# (alias for :wiremock-extension-bundle:shadowJarIbmMq)
```

Artifact outputs:
- Lean bundle: `wiremock-extension-bundle/build/libs/wiremock-extension-bundle-<version>-standalone.jar`
- IBM MQ bundle: `wiremock-extension-bundle/build/libs/wiremock-extension-bundle-<version>-standalone-ibmmq.jar`

#### Launch As-Is (`Main-Class: StandaloneDevServer`)

The fat-jar manifest sets `Main-Class: com.github.mattiasgreen.wiremock.bundle.StandaloneDevServer`, which parses standard WireMock CLI options (`--port`, `--root-dir`, `--global-response-templating`, `--enable-stub-cors`, etc.) and automatically wires `CompressedStores`, extension scanning, the TCP NetEm ingress proxy, and the Casual TCP gateway:

```bash
java -jar wiremock-extension-bundle/build/libs/wiremock-extension-bundle-*-standalone.jar --port 8080
```

To bypass `StandaloneDevServer` and invoke WireMock's stock CLI runner (`wiremock.Run`) directly with extensions loaded via SPI:

```bash
java -cp wiremock-extension-bundle/build/libs/wiremock-extension-bundle-*-standalone.jar wiremock.Run --port 8080 --verbose
```

#### Run in Docker

Build and run using the root `Dockerfile`:

```bash
# 1. Build the fat-jar
./gradlew fatJar

# 2. Build the Docker image
docker build -t wiremock-suite .

# 3. Run container exposing HTTP (8080), NetEm (8081), and Casual (7772)
docker run -it --rm -p 8080:8080 -p 8081:8081 -p 7772:7772 wiremock-suite

# 4. Or mount local mappings and __files volumes
docker run -it --rm -p 8080:8080 \
  -v $(pwd)/mappings:/home/wiremock/mappings \
  -v $(pwd)/__files:/home/wiremock/__files \
  wiremock-suite --port 8080 --verbose
```

### Dependency Coordinates

**Gradle**:
```groovy
implementation 'io.github.mattiasgreen:wiremock-extension-bundle:1.14.0'
```

**Maven**:
```xml
<dependency>
    <groupId>io.github.mattiasgreen</groupId>
    <artifactId>wiremock-extension-bundle</artifactId>
    <version>1.14.0</version>
</dependency>
```

### Standalone CLI

Run WireMock Standalone with the bundle JAR on the classpath:

```bash
java -cp "wiremock-extension-bundle-1.14.0.jar:wiremock-standalone-3.12.1.jar" \
  com.github.tomakehurst.wiremock.standalone.WireMockServerRunner \
  --port 8080 \
  --verbose
```

Available bundled Admin API endpoints:
- **Web UI**: `http://localhost:8080/__admin/ui/`
- **Prometheus Metrics**: `http://localhost:8080/__admin/metrics/prometheus`
- **Runtime Log Levels**: `http://localhost:8080/__admin/logging/levels`
- **Zstd Journal Stats**: `http://localhost:8080/__admin/journal/stats`
- **Async Engine Status**: `http://localhost:8080/__admin/async/status`
- **OpenAPI Import**: `http://localhost:8080/__admin/openapi/import`
- **TCP NetEm Journal**: `http://localhost:8080/__admin/tcp-netem/journal`
- **Casual Config API**: `http://localhost:8080/__admin/casual/config`
- **JSON Admin API**: `http://localhost:8080/__admin/mappings`

### Local Development Runners

Run the interactive standalone dev servers directly from source:

```bash
# Standard dev server (WireMock + all extensions, TCP NetEm, Casual gateway, Web UI)
./gradlew :wiremock-extension-bundle:runStandalone

# Dev server paired with an embedded ActiveMQ Artemis AMQP 1.0 broker
./gradlew :wiremock-extension-bundle:runWithArtemis

# Simulated upstream mock service on port 8089 for live proxy & recording tests
./gradlew :wiremock-extension-bundle:runUpstream
```

The server starts clean with no pre-populated stubs. To populate sample stubs, import [`wiremock-examples/example-stubs.json`](../wiremock-examples/example-stubs.json) using cURL or the WireMock Console UI:

```bash
curl -X POST http://localhost:8080/__admin/mappings/import \
  -H "Content-Type: application/json" \
  -d @wiremock-examples/example-stubs.json
```

### Port & Extension Configuration

Extending WireMock Standalone, the **main WireMock HTTP port** is configured via standard program arguments (e.g. `--port 8080`), while extension listener ports (**NetEm** and **Casual**) are configured via environment variables or JVM system properties:

| Setting / Port | Configuration Mechanism | Env / System Property | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| **Main WireMock HTTP** | Program Argument (WireMock Standalone) | `--port <port>` | `8080` | Core WireMock HTTP listener, Admin API, and Web UI. |
| **TCP NetEm Ingress** | Env Var or JVM Property | `WIREMOCK_NETEM_PORT` / `-Dwiremock.netem.port` | `wireMockPort + 1` (`8081` for `8080`) | Dedicated Layer 4 TCP proxy port for fault injection. |
| **Casual TCP Gateway** | Environment Variable | `WIREMOCK_CASUAL_PORT` | `7772` | Dedicated raw TCP port for Casual / XATMI gateway. |
| **Casual Enabled** | Environment Variable | `WIREMOCK_CASUAL_ENABLED` | `true` | Enables or disables the Casual TCP gateway. |
| **Casual Upstream Timeout** | Environment Variable | `WIREMOCK_CASUAL_UPSTREAM_TIMEOUT` | `4` | Seconds before timing out slow upstream Casual services. |

Dynamic upstream proxying and recording are declared directly in standard WireMock stub mappings (`targetBaseUrl: "casual://host:port"` with `metadata.mode = "proxy"`), with multi-upstream 2PC XA stickiness and human-readable Request Journal logging. Settings can be inspected via:
- Inspect configuration: `GET http://localhost:8080/__admin/casual/config`

## Build

```bash
./gradlew :wiremock-extension-bundle:build
```
