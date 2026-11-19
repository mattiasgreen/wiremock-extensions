# WireMock Extensions Common (`wiremock-extension-common`)

Shared stateless utilities, traffic classification helpers, indexed journal lookup contracts, and cross-extension telemetry SPIs used across the `wiremock-extensions` suite.

## Problem Statement

Multiple extensions (`otel`, `json-logging`, `ui`, `journal`, and `tcp-netem`) share common concerns—normalizing caller IPs, classifying serve events, evaluating boolean search queries, querying compressed journal body indexes, and collecting TCP NetEm metrics—without introducing circular or tight compile-time dependencies between modules.

## Public API & SPI Reference (`com.github.mattiasgreen.wiremock.support`)

| Type | Kind | Purpose |
| :--- | :--- | :--- |
| **`BooleanQueryEvaluator`** | Utility | Evaluates boolean search queries supporting `OR`, `AND`, and whitespace-delimited implicit-AND semantics with `Locale.ROOT` lowercase token normalization. |
| **`IndexedBodyLookup`** | SPI Interface | Optional capability interface for request journal stores (`containsEventToken(UUID, String)`) exposing an inverted token index over captured event bodies so callers can pre-filter without decompressing full payloads. |
| **`ServeEventTrafficSupport`** | Utility | Stateless helper shared across `otel`, `json-logging`, and `ui` for caller IP extraction (`extractClientIp`, `extractClientIpOrRaw`, `normalizeSourceToken` with `X-Forwarded-For` -> `X-Real-IP` -> `clientIp` precedence), protocol detection (`detectProtocol`), proxy/fault inspection (`isProxied`, `isFault`, `extractTargetUpstream`), traffic type classification (`classifyTrafficType`: `fault`, `proxied`, `stubbed`, `unmatched`), and UI journal verdict classification (`classifyJournalVerdict`: `unmatched`, `proxied`, `stub`). |
| **`NetEmMetricsSource`** | Extension SPI | Cross-extension WireMock `Extension` (`"netem-metrics-source"`) implemented by `wiremock-extension-tcp-netem` so observability extensions (`wiremock-extension-otel`) can snapshot proxy metrics without compile-time coupling. |
| **`NetEmProxyMetricsSnapshot`** | Immutable Record | Point-in-time snapshot of a single TCP NetEm proxy's counters (`name`, `activeConnections`, `normalTransfers`, `impairedTransfers`, `toxicCounters`, and upstream/downstream normal & impaired byte counts). |

## Usage

### Dependency Coordinates

**Gradle**:
```groovy
implementation 'io.github.mattiasgreen:wiremock-extension-common:1.14.0'
```

**Maven**:
```xml
<dependency>
    <groupId>io.github.mattiasgreen</groupId>
    <artifactId>wiremock-extension-common</artifactId>
    <version>1.14.0</version>
</dependency>
```

> Most users do not need to declare `wiremock-extension-common` directly—it is pulled in transitively by individual extensions and by `wiremock-extension-bundle`.
