# AGENTS.md: wiremock-extension-journal

Module-specific guidelines for autonomous coding agents working on `wiremock-extension-journal`.

---

## 1. Module Responsibility

`wiremock-extension-journal` provides an intelligent, high-density in-memory `RequestJournalStore` for WireMock Standalone (JDK 21) that prevents OutOfMemoryError crashes while maintaining byte-level assertion fidelity via:
- Dynamic structural traffic clustering (`stub:<id>`, `casual-proxy:<service>`, `proxy:<target>`, or `unmatched:<method>`).
- Virtual-thread Zstandard dictionary training (`ZstdDictTrainer`).
- Transparent microsecond on-the-fly decompression in `CompressedServeEvent`.
- Adaptive anti-OOM heap headroom monitoring and post-GC threshold eviction.
- Background asynchronous retroactive compaction and dynamic rebalancing.
- High-performance compressed journal search and stats REST endpoints.

---

## 2. Inviolable Constraints & Architecture Rules

1. **Exact Verification Fidelity**:
   - `ServeEvent` bodies decompressed transiently MUST produce exact byte arrays matching the original requests and responses. WireMock `verify(...)` calls rely on exact body equality and JSON/XML path matchers.
2. **Young-Gen Transient Decompression**:
   - Decompressed byte arrays MUST NEVER be re-cached into long-lived instance fields of `CompressedServeEvent`. They must be transiently constructed inside `getRequest()` and `getResponse()` and discarded immediately after use.
3. **Safe Dictionary Lifecycle**:
   - Never call native `.close()` on a Zstandard dictionary (`ZstdDictCompress`, `ZstdDictDecompress`) during LRU pool eviction (`removeEldestEntry`). Readers may concurrently be decompressing an event. Allow Java GC to safely finalize unreferenced dictionaries.
4. **Adaptive Pruning Standalone Mode**:
   - When `compressionEnabled` is `false`, the journal MUST function normally without native Zstd compression while continuing to enforce real-time JVM headroom pruning to prevent OOM.
5. **Static Defects Before Formatting**:
   - Run `./gradlew :wiremock-extension-journal:spotbugsMain :wiremock-extension-journal:test -q --console=plain` before running `spotlessApply`.

---

## 3. Fast Inner-Loop Workflow

```bash
# Targeted test execution
./gradlew :wiremock-extension-journal:test --tests "CompressedRequestJournalStoreTest" -q --console=plain

# Performance benchmark run
./gradlew :wiremock-extension-journal:test --tests "JournalPerformanceBenchmarkTest" -q --console=plain

# Static defects gate
./gradlew :wiremock-extension-journal:spotbugsMain -q --console=plain

# Pre-commit formatting & verification gate
./gradlew spotlessApply :wiremock-extension-journal:check -q --console=plain
```
