# WireMock Adaptive Request Journal Extension (Beta)

> **Status: Beta**  
> An intelligent, memory-bounded, high-density Request Journal for WireMock Standalone (JDK 21) powered by dynamic Zstandard dictionary compression and real-time JVM headroom monitoring.

---

## 1. The Problem Statement

WireMock's default in-memory `RequestJournal` preserves all incoming requests and responses as raw Java objects (`LoggedRequest`, `LoggedResponse`, byte arrays, and headers) on the JVM heap. In production testing scenarios, long-running test suites, stress testing, and shared cluster environments (such as Kubernetes pods running multiple integration tests), this design leads to critical failure modes:

1. **Unbounded Memory Accumulation**: Under high throughput (hundreds of thousands of requests), retained HTTP/Casual traffic steadily consumes heap until an `OutOfMemoryError` (OOM) crashes the WireMock process.
2. **Variable Payload Spikes**: Real-world payloads vary drastically: small status checks (200 bytes) intermingle with massive XML/JSON payloads, PDF uploads, or Casual XATMI buffer dumps (tens to hundreds of kilobytes). A static limit on request count (`maxRequestJournalEntries`) cannot prevent OOM when a burst of large payloads arrives.
3. **Container Heap Blindness**: Static eviction limits do not account for container memory constraints (cgroups) or concurrent memory consumption by other extensions and mock scenarios. Even with 30% nominal headroom, unexpected heap pressure can cause immediate JVM death.

---

## 2. The Solution: Adaptive Zstandard Journal

The `wiremock-extension-journal` extension transforms the WireMock journal from a passive unbounded queue into a high-density, memory-aware, self-optimizing storage engine:

- **10x–20x Memory Density via Zstandard**: By clustering traffic and training specialized Zstandard dictionaries on repetitive JSON/XML/XATMI structures, payloads compress by up to 90–95%, allowing millions of events to reside in memory with minimal footprint.
- **Dynamic Structural Clustering**: Traffic is automatically segmented into cluster signatures (e.g., `stub:<id>`, `casual-proxy:<service>`, `proxy:<target>`, or `unmatched:<method>`). Once a cluster reaches 20 sample events, a dedicated 32 KB dictionary is trained asynchronously on virtual threads.
- **Transparent Microsecond Decompression**: The journal wraps events in `CompressedServeEvent`. Payloads remain compressed in memory. When WireMock verification (`verify(...)`), Admin APIs, or UI tools read the event, the request/response bodies are decompressed transiently in ~5–10 µs and immediately eligible for young-generation garbage collection without re-caching raw bytes on the heap.
- **Retroactive Compaction & Dynamic Rebalancing**: Early events received before a dictionary finishes training are initially compressed using baseline Zstd level 3. As soon as a dictionary graduates, or when new stubs/services are onboarded dynamically, background virtual threads retroactively re-compress older events with the new dictionary.
- **Adaptive Anti-OOM Guard**: Rather than relying solely on arbitrary thresholds, the journal monitors real-time JVM headroom (`Runtime.getRuntime().maxMemory() - usedMemory`) and registers low-memory notifications on the post-GC memory pool (`MemoryPoolMXBean`). If memory headroom drops below safety thresholds (default 15% or 32 MB), older journal events are evicted preemptively before an OOM can occur.
- **High-Performance Journal Search**: An in-memory text search engine scans compressed journal entries across requests and responses via direct dictionary matching or fast streaming decompression.

---

## 3. Architecture & Data Flow

```mermaid
flowchart TD
    subgraph Ingestion ["1. Traffic Ingestion"]
        A[Incoming HTTP / Casual Request] --> B[ServeEventListener / Stores]
        B --> C{Anti-OOM Headroom Check}
        C -- Pressure Detected --> D[Evict Oldest Events]
        C -- Headroom OK --> E[Resolve ClusterSignature]
    end

    subgraph Compression ["2. Dynamic Clustering & Compression"]
        E --> F{Dictionary Available?}
        F -- Yes --> G[Compress with Dictionary]
        F -- No --> H[Compress Baseline Zstd Lvl 3]
        H --> I[Collect Sample for Cluster]
        I --> J{Samples >= 20?}
        J -- Yes --> K[Train Zstd Dict on Virtual Thread]
        K --> L[Store in DictionaryPool LRU]
        L --> M[Async Retroactive Rebalance]
        M -. Re-compress .-> G
    end

    subgraph Storage ["3. High-Density Retention"]
        G --> N[CompressedServeEvent stored in Store Deque]
        H --> N
    end

    subgraph Retrieval ["4. Transparent Querying"]
        O[Admin API / Verify / UI Search] --> P{Query Type}
        P -- Free-Text Search --> Q[Scan Compressed Byte Streams]
        P -- Event Retrieval / Verify --> R[Transient Decompression 5-10 µs]
        R --> S[Raw LoggedRequest / LoggedResponse]
        S -. Immediate GC after use .-> T[Heap Remains Lean]
    end
```

---

## 4. Architectural Trade-offs & Decisions

| Decision | Approach Chosen | Rejected Alternative | Justification |
| :--- | :--- | :--- | :--- |
| **Storage Tiering** | Single-tier high-density compressed in-memory store | Warm (raw) + Cold (compressed) dual-queue architecture | Avoids non-deterministic latency spikes during warm-to-cold migration, eliminating race conditions and lock contention. |
| **Dictionary Training** | Virtual threads triggered by sample count thresholds | Periodic cron-job schedulers | Scheduled cron jobs cause unneeded CPU churn when idle and latency lag under sudden traffic bursts. Threshold triggers adapt immediately to actual traffic. |
| **Payload Preservation** | Full payload retention compressed via Zstd | Truncating / dropping request & response bodies | WireMock verification (`verify(postRequestedFor(...).withRequestBody(...))`) and debugging require byte-level fidelity. Dropping bodies breaks assertions. |
| **Dictionary Cleanup** | Java GC reclamation of unreferenced dictionaries | Native JNI `.close()` in LRU `removeEldestEntry` | Calling native JNI free during concurrent reader access risks segmentation faults. Letting GC finalize unreferenced dictionaries ensures safe memory reclamation. |

For detailed architectural background, see [ADR 0009: Adaptive Zstandard Request Journal Compression](../../docs/adr/0009-adaptive-zstandard-journal-compression.md).

---

## 5. Configuration Reference

The extension is fully configurable via environment variables or Java system properties.

| Environment Variable | System Property | Default | Description |
| :--- | :--- | :--- | :--- |
| `WIREMOCK_JOURNAL_COMPRESSION_ENABLED` | `wiremock.journal.compression.enabled` | `true` | When set to `false`, compression is disabled. The journal retains events uncompressed while **still actively applying adaptive heap headroom pruning**. |
| `WIREMOCK_JOURNAL_ADAPTIVE_PRUNING_ENABLED` | `wiremock.journal.adaptive.pruning.enabled` | `true` | Enables or disables adaptive anti-OOM eviction based on JVM heap headroom and post-GC notifications. |
| `WIREMOCK_JOURNAL_MAX_DICTIONARIES` | `wiremock.journal.max.dictionaries` | `64` | Maximum number of concurrent trained Zstd dictionaries kept in the LRU pool. |
| `WIREMOCK_JOURNAL_MAX_MEMORY_MB` | `wiremock.journal.max.memory.mb` | `256` | Maximum memory limit (in MB) allocated for the compressed journal before proactive eviction begins. |

### Configuration Use Cases

#### 1. Adaptive Pruning Without Compression (Zero JNI Overhead)
If native JNI or Zstandard is not desired, but you want to protect your WireMock container from OOM:
```bash
export WIREMOCK_JOURNAL_COMPRESSION_ENABLED=false
export WIREMOCK_JOURNAL_ADAPTIVE_PRUNING_ENABLED=true
export WIREMOCK_JOURNAL_MAX_MEMORY_MB=128
```

#### 2. High-Throughput Microservice Simulation
To retain millions of requests across dozens of distinct services in a constrained 512 MB container:
```bash
export WIREMOCK_JOURNAL_COMPRESSION_ENABLED=true
export WIREMOCK_JOURNAL_MAX_DICTIONARIES=128
export WIREMOCK_JOURNAL_MAX_MEMORY_MB=384
```

---

## 6. Admin API Endpoints

The extension registers custom endpoints under `/__admin/journal`:

### `GET /__admin/journal/stats`
Returns live memory and compression statistics:
```json
{
  "totalEvents": 250000,
  "rawBytes": 1985420100,
  "compressedBytes": 163680410,
  "compressionRatio": 12.13,
  "activeDictionaries": 24,
  "totalEvictions": 1420,
  "usedHeapBytes": 184549376,
  "maxHeapBytes": 536870912
}
```

### `POST /__admin/journal/compact`
Forces an immediate compaction pass over all uncompressed or baseline events.

### `POST /__admin/journal/rebalance`
Forces a re-clustering and re-training pass across all active signatures.

### `GET /__admin/journal/search?query=CASE-9901`
Executes a fast free-text search across all stored request and response bodies, returning matching event UUIDs.

---

## 7. Performance Benchmarks

Tested on JDK 21 (Temurin) on a 16-core machine:

- **Ingestion Throughput**: ~8,700–10,000 requests/sec with active compression, dictionary training, and concurrent clients.
- **Decompression Overhead**: ~5–10 µs per event during `verify(...)` or Admin API retrieval.
- **Search Speed**: Scanned 5,000 events across ~25 MB of payload data in 131 ms.
- **Compression Ratio**: 10x–20x on structured JSON, XML, and XATMI payloads (typical 1.85 GB payload stored in ~21 MB compressed heap).
