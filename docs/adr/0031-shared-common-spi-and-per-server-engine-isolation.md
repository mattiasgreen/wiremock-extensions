# 31. Shared Common SPI and Per-Server Engine Isolation

Date: 2026-11-19

## Status

Accepted

## Context

As the extension suite grew to nine modules (`common`, `json-logging`, `otel`, `openapi`, `tcp-netem`, `casual`, `async`, `journal`, `ui`), two structural problems emerged:

1. **Cross-Module Duplication & Coupling**:
   - Traffic verdict classification (`proxied`, `stub`, `unmatched`, `bridged`), caller IP extraction, and boolean search query parsing were duplicated or subtly inconsistent across `ui`, `otel`, `json-logging`, and `journal`.
   - `wiremock-extension-otel` needed to export L4 TCP NetEm proxy gauges without taking a hard compile dependency on `wiremock-extension-tcp-netem`.
   - `wiremock-extension-ui` needed to leverage `wiremock-extension-journal`'s inverted token index during journal search without coupling `ui` to Zstandard compression internals.
2. **Static Singleton Leaks Across Server Instances**:
   - Static engine singletons caused state bleed between concurrent `WireMockServer` instances in parallel JUnit 5 test suites and prevented clean multi-instance JVM embeddings.

## Decision

We establish `wiremock-extension-common` as the thin, zero-bloat shared SPI and stateless utility layer, and enforce per-server engine isolation across all extension factories:

1. **Shared Stateless Support & SPI Contracts (`wiremock-extension-common`)**:
   - **`BooleanQueryEvaluator`**: Canonical boolean search evaluator (`OR`, `AND`, whitespace implicit-AND, `Locale.ROOT` lowercasing) shared across server-side journal filtering and query matching.
   - **`IndexedBodyLookup`**: Optional capability interface (`Optional<Boolean> containsEventToken(UUID eventId, String token)`) implemented by indexed `RequestJournalStore` backends (such as `wiremock-extension-journal`) so `JournalFilterService` can pre-filter without decompressing payloads, falling back transparently to full scans on vanilla WireMock stores.
   - **`ServeEventTrafficSupport`**: Single stateless source of truth for classifying `ServeEvent` traffic verdicts, protocol metadata, and extracting/normalizing caller source IPs (`extractClientIp`, `normalizeSourceToken`) across `otel`, `json-logging`, and `ui`.
   - **`NetEmMetricsSource` & `NetEmProxyMetricsSnapshot`**: Telemetry SPI interface allowing `wiremock-extension-tcp-netem` to expose L4 proxy metrics snapshots to `wiremock-extension-otel` without cross-module coupling.

2. **Per-Server Engine Isolation**:
   - Eliminate static mutable engine singletons across extension modules.
   - Each `ExtensionFactory.create(Services)` constructs and wires server-scoped instances (`AsyncEngine`, `TcpNetEmEngine`, `JournalFilterService`, `ClientSourceResolver`, `StubLifecycleService`) bound strictly to that server's `Services` / `Stores` lifecycle.

## Consequences

- **Consistent Semantics Across Extensions**: Verdict classification, caller IP normalization, and boolean query grammar never drift between UI, OTel metrics, JSON logs, and journal search.
- **Clean Module Boundaries**: Optional capabilities (`IndexedBodyLookup`, `NetEmMetricsSource`) compose dynamically via SPI without circular or heavy transitive dependencies.
- **Parallel Test & Multi-Server Safety**: Multiple `WireMockServer` instances run concurrently in the same JVM with complete engine, journal, and metric isolation.
