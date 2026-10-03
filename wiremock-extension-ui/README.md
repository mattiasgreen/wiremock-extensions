# WireMock UI Extension

A lightweight, self-contained web user interface for exploring stubs, inspecting the request journal, and testing HTTP endpoints directly from WireMock.

## Problem Statement

WireMock standalone exposes administration capabilities exclusively through raw JSON REST endpoints (`/__admin/mappings`, `/__admin/requests`). While scriptable, developers and QA engineers frequently need to:
- Visually browse, search, and inspect configured stubs and scenario states.
- Diagnose why an incoming request matched or failed to match a stub.
- Test and debug live HTTP requests against WireMock without switching to external tools like Postman or terminal curl commands.
- Share direct links to specific stubs, journal records, or test queries with teammates.

Most third-party UIs require external Node.js runtimes, Docker containers, or public CDN scripts that violate air-gapped security policies.

## How It Addresses the Problem

This extension embeds a pure vanilla ES6 Single-Page Application directly inside the JAR. It mounts seamlessly under WireMock's admin endpoint at `/__admin/ui/`.
- Zero external runtime or build dependencies (no npm, webpack, or CDNs required).
- Completely self-contained and safe for restricted/air-gapped networks.
- Full hash-based URL addressability so views, filters, stubs, and tester queries can be bookmarked and shared.

## Features

- **Stub Explorer & Inspector**:
  - Filter stubs instantly by HTTP method, URL pattern, stub name, or scenario state.
  - View full JSON mapping definitions with syntax highlighting (keys, strings, numbers, booleans, null, punctuation).
  - One-click copy for JSON stubs and generated `curl` commands.
- **Request Journal Viewer**:
  - Search and filter recorded requests by method, URL, or matching status.
  - Expandable inline inspector showing request headers/body and corresponding response definition.
  - Highlights matched vs. unmatched status with visual badges.
- **Interactive HTTP Request Tester**:
  - Send requests directly to WireMock with custom methods, headers, and request bodies.
  - Dual sent/received inspection: toggle between Response, Sent Request, or Both (side-by-side split view).
  - Persistent header view with quick preset shortcuts (`+ JSON`, `+ Bearer`, `+ Accept`).
  - Response size indicator (bytes/KB) and formatted response body with JSON syntax highlighting.
  - Local execution history with timestamps, status badges, and restore-on-click capability.
- **URL Addressability & Deep Linking**:
  - All tabs, selected stubs, journal details, and tester inputs synchronize with the URL hash (e.g. `#stubs?stubId=...`, `#tester?method=POST&url=...`).
- **Swagger UI Integration**:
  - Relative neighbor link to `../swagger-ui/` for environments where Swagger UI is co-hosted.
- **Developer Hot-Reloading**:
  - During local development, the endpoint detects local resources on disk and reloads HTML/CSS/JS without restarting the server.

## Usage

### Dependency Coordinates

**Gradle**:
```groovy
implementation 'io.github.mattiasgreen:wiremock-extension-ui:0.1.0'
```

**Maven**:
```xml
<dependency>
    <groupId>io.github.mattiasgreen</groupId>
    <artifactId>wiremock-extension-ui</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Standalone CLI

Add the JAR to the classpath:

```bash
java -cp "wiremock-extension-ui-0.1.0.jar:wiremock-standalone-3.12.1.jar" \
  com.github.tomakehurst.wiremock.standalone.WireMockServerRunner \
  --port 8080
```

Open `http://localhost:8080/__admin/ui/` in your browser.

### Embedded Java

```java
import com.github.mattiasgreen.wiremock.ui.UiAdminApiEndpoint;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

WireMockServer wm = new WireMockServer(WireMockConfiguration.options()
    .port(8080)
    .extensions(new UiAdminApiEndpoint()));
wm.start();
```

When the JAR is on the classpath, the extension registers automatically via Java SPI.

## Build & Test

```bash
# Build JAR
./gradlew :wiremock-extension-ui:build

# Run Playwright UI integration tests (Chromium)
./gradlew :wiremock-extension-ui:test
```
