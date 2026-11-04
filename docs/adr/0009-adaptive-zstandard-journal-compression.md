# 0009. Adaptive Zstandard Journal Compression

## Context

In vanilla WireMock, request journal entries (`ServeEvent`) are stored uncompressed in heap memory (`InMemoryRequestJournalStore`). While users can configure an integer limit (`maxRequestJournalEntries`), payload sizes vary by orders of magnitude (from small 200-byte JSON responses to 500 KB+ SOAP/Casual payloads). In long-running shared mock environments and central team clusters, high-volume repetitive traffic routinely causes sudden `OutOfMemoryError` crashes, halting mock services. Standard compression algorithms like Gzip or Snappy achieve only 4×–6× compression on individual payloads because small documents lack enough internal repetition to build an effective compression model.

## Decision

We introduce an in-process, adaptive journal compression engine (`wiremock-extension-journal`) powered by Zstandard (`zstd-jni`):

1. **Immediate Baseline Compression**: Every inbound `ServeEvent` has its request and response bodies compressed immediately with standard Zstandard upon arrival (~4×–8× base reduction in ~20–30 µs).
2. **Progressive Dictionary Training**: Payloads are classified by **Cluster Signature** (`stub:<id>` for stubs, `proxy:<targetHostOrService>` for proxied calls, and `unmatched:<method>` for unmatched traffic). Once a cluster collects 20 sample payloads, a dedicated 16–32 KB Zstandard dictionary is trained in <20 ms on a background virtual thread, unlocking **50× to 100×+** compression density for subsequent events.
3. **Bounded Dictionary Pool**: The dictionary pool is capped at an LRU of 64 active dictionaries (~2 MB heap), falling back safely to baseline Zstd when evicted.
4. **Transient Decompression**: Journal reads (for `verify(...)`, Admin API, Web UI, and free-text searches) decompress bodies strictly on-the-fly without permanently caching decompressed bytes back onto the heap.
5. **Adaptive Anti-OOM Guard**: Real-time JVM available headroom (`Runtime.getRuntime().maxMemory() - usedMemory`) and post-GC `MemoryPoolMXBean` threshold notifications trigger FIFO eviction of the oldest events before memory exhaustion can occur.
6. **Dynamic Rebalancing & Retroactive Compaction**:
   - **Trigger Mechanics**: Automatically executed without background cron jobs, triggered when a new cluster dictionary finishes training, after every 5,000 inbound events, or when 1,000 evictions occur under memory pressure.
   - **Retroactive Upgrading**: Older events that were stored using baseline compression during a service's initial 20-sample cold-start window are retroactively upgraded to dictionary compression on background virtual threads (<20 ms), ensuring maximum compression density across the entire journal history.
   - **Full Re-Clustering**: Available ad-hoc via `POST /__admin/journal/rebalance?full=true` to re-evaluate cluster signatures if stubs are registered after traffic has already been served.
7. **Lean Execution Model**: No background cron jobs and no complex warm/cold tier queues; compaction is purely traffic-driven with an ad-hoc Admin API trigger (`POST /__admin/journal/compact`) and metrics endpoint (`GET /__admin/journal/stats`).

## Considered Options

- **Vanilla Count-Based Bounding (`maxRequestJournalEntries`)**: Rejected. Fails to account for payload size variability; a single spike of large payloads crashes the JVM.
- **Gzip / Snappy Without Dictionaries**: Rejected. Reaches only 4×–6× compression because individual REST/RPC payloads are too small to exploit repeated schema vocabulary.
- **Pre-Trained AOT Dictionaries**: Rejected. Centralized WireMock instances mock hundreds of unpredictable third-party APIs whose schemas cannot be known at compile time.
- **Scheduled Compaction (Cron Timer)**: Rejected. Introduces unnecessary idle thread wakeups and latency spikes; Zstd is fast enough (~20 µs) to compress on-demand as traffic arrives.
- **Warm Head / Cold Tail Dual Queue**: Rejected. Decompressing 100 events in a unit test takes <1 ms total, so dual-queue complexity offers negligible benefit while introducing concurrency bugs.
- **Payload Body Stripping (Degradation Mode)**: Considered but deferred. Stripping bodies while keeping metadata risks confusing test assertions and UI displays; clean FIFO eviction of whole events is more predictable.

## Consequences

- 50× to 100×+ heap reduction under sustained multi-endpoint traffic.
- Zero risk of JVM OOM crashes caused by journal growth.
- Transparent byte-for-byte exact payload fidelity across all WireMock verification and UI inspections.
- OpenTelemetry observability via observable gauges in `wiremock-extension-otel`.
