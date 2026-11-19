# 32. Caller Source IP Extraction and Bounded Async Reverse-DNS Resolution

Date: 2026-11-19

## Status

Accepted

## Context

In shared team WireMock environments (Kubernetes namespaces, shared dev/staging clusters), multiple microservices and developer workstations hit the same WireMock Standalone instance simultaneously. When triaging unmatched requests or inspecting live traffic in the Request Journal or CLI:

1. **Missing Caller Attribution**: Identifying *which* pod, service, or developer machine issued a request required digging through raw headers or correlating timestamps externally.
2. **Casual TCP Parity**: Binary XATMI calls arriving over Netty TCP sockets (`wiremock-extension-casual`) bypassed HTTP reverse proxies, leaving synthetic `ServeEvent` records without caller network identity unless explicitly captured from the TCP channel peer address.
3. **Blocking DNS Latency Risk**: Performing synchronous `InetAddress.getCanonicalHostName()` calls on the HTTP request thread or during journal pagination can stall responses for seconds when DNS servers are slow or unreachable.

## Decision

We implement end-to-end caller source IP attribution with non-blocking, bounded reverse-DNS (`PTR`) resolution across HTTP, Casual, the Web UI, and the CLI:

1. **Canonical Source IP Extraction (`ServeEventTrafficSupport.extractClientIp`)**:
   - Precedence: `X-Forwarded-For` (first hop) → `X-Real-IP` → `request.getClientIp()`.
   - `normalizeSourceToken` strips ports, IPv6 brackets/zones, and enforces a strict allowlist pattern (`^[a-zA-Z0-9.:_-]{1,253}$`) to prevent header spoofing and log/UI injection.
   - **Casual TCP Peer Extraction**: `CasualWireMockGateway` extracts the remote TCP socket address on inbound XATMI connections and propagates it onto synthetic `ServeEvent` records for uniform attribution.

2. **Bounded Async Reverse-DNS Resolver (`ClientSourceResolver`)**:
   - Dispatches `PTR` lookups asynchronously on Java 21 virtual threads (`wiremock-dns-*`).
   - Enforces a bounded batch wait timeout (`50 ms` default) so `/__admin/ui/api/requests` never stalls on slow DNS; unresolved lookups complete in the background and populate the cache for subsequent polls.
   - Maintains a bounded in-memory TTL cache (`15 min` default TTL, `2048` max entries) with negative caching for unresolved IPs and in-flight `CompletableFuture` coalescing to prevent duplicate lookups.

3. **Enriched Journal API & Unified Filter Qualifiers**:
   - `JournalFilterService` returns `EnrichedJournalFilterResult` (`requests`, `resolvedHosts`, `total`, `filtered`) via `/__admin/ui/api/requests`.
   - `JournalQueryMatcher`, the Web UI (`filter.js`), and `wiremock-cli` (`wm tail`) all support `src:`, `ip:`, and `host:` filter qualifiers (e.g., `src:10.0.1.42`, `host:checkout-svc`, `ip:127.0.0.1`) alongside standard boolean `AND` / `OR` expressions.

## Consequences

- **Instant Multi-Tenant Traffic Attribution**: Engineers immediately see which client IP or hostname generated each HTTP or Casual journal entry.
- **Zero Request-Path Latency Impact**: Reverse-DNS never runs on the hot mock-serving path and is strictly bounded (`50 ms`) on journal reads.
- **Consistent Filtering Across UI & CLI**: Identical `src:` / `ip:` / `host:` qualifiers work in both the Web Console and `wm tail`.
