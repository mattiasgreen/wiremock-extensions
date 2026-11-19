# 33. Zero-Compile CLI and Standalone Console Proxy

Date: 2026-11-19

## Status

Accepted

## Context

Developers frequently interact with remote or containerized WireMock Standalone instances (including vanilla WireMock servers that do not bundle `wiremock-extension-ui`) from terminals and local browsers:

1. **CLI Friction**: Requiring a Gradle build, shaded fat-JAR distribution, or Node/Python runtime to query stubs, tail live traffic, or inspect NetEm toxics slows down local debugging and CI troubleshooting.
2. **Remote & Vanilla WireMock UI Access**: Opening the embedded Vanilla ES6 Web Console against a remote WireMock server or a vanilla WireMock container directly from a local file or another origin fails due to browser CORS restrictions, or is impossible when `wiremock-extension-ui` is not installed on the target server.

## Decision

We provide two zero-dependency, JDK 21 JEP 330–compatible developer tools:

1. **Zero-Compile Developer CLI (`wiremock-cli`, `WireMockCli.java` / `bin/wm`)**:
   - Authored as a single-file, zero-third-party-dependency Java 21 program using `java.net.http.HttpClient`, runnable directly via `java wiremock-cli/src/main/java/com/github/mattiasgreen/wiremock/cli/WireMockCli.java` or `./wiremock-cli/bin/wm` with zero build step.
   - Provides terminal-native observability and filtering commands:
     - `info` / `status`: Server health, loaded extensions, and summary counts.
     - `stubs` / `list` / `ls`: Stub catalog listing with boolean query expressions and native priority-tier classification (`priority >= 100` parked).
     - `tail` / `traffic` / `journal`: Live/recent request journal inspection with `src:`/`ip:`/`host:` and verdict filtering.
     - `toxics` / `chaos` / `netem`: L4 TCP NetEm proxy and toxic inspection.
     - `proxy` / `ui`: Launches `WireMockConsoleProxy` directly from the CLI.

2. **Standalone CORS-Free Console Proxy (`WireMockConsoleProxy.java`)**:
   - Zero-dependency reverse proxy built on JDK's `com.sun.net.httpserver.HttpServer` (default local port `19840`) and `java.net.http.HttpClient`, also directly runnable via JEP 330 (`java WireMockConsoleProxy.java [target-url] [port]`).
   - Serves the embedded Vanilla ES6 Web UI at `/__admin/ui/` (with live disk hot-reloading when run from the repository) and transparently proxies all `/__admin/*` and application requests to the target WireMock server on the same origin—eliminating browser CORS errors and bringing the full Web Console to vanilla WireMock instances without server-side installation.
   - Supports dynamic per-request upstream routing via `X-Forwarded-Host`, `X-Forwarded-Proto`, and `X-Forwarded-Prefix` while stripping hop-by-hop headers.

## Consequences

- **Zero-Build Instant Tooling**: Any machine with JDK 21 runs `wm` and `WireMockConsoleProxy` immediately without compiling, installing packages, or downloading runtimes.
- **Full Web Console for Vanilla WireMock**: Teams can inspect and manage remote or stock WireMock containers locally with zero CORS friction and zero server-side plugin changes.
- **Graceful Degradation**: Both tools rely strictly on standard WireMock Admin APIs by default and detect optional extension endpoints (`/__admin/lifecycle/status`, `/__admin/netem`, `/__admin/ui/api/requests`) dynamically.
