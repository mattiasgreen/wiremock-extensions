package com.github.mattiasgreen.wiremock.ui;

import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.Request;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Extracts effective caller source IPs from WireMock requests and performs non-blocking
 * reverse DNS ({@code PTR}) resolution on Java 21 virtual threads with an in-memory TTL cache.
 */
public final class ClientSourceResolver {

    static final long DEFAULT_BATCH_TIMEOUT_MS = 50L;
    static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(15);
    static final int MAX_CACHE_SIZE = 2048;

    private static final Pattern SAFE_SOURCE_PATTERN = Pattern.compile("^[a-zA-Z0-9.:_-]{1,253}$");
    private static final Pattern HOSTNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9.-]{0,252}$");
    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final Executor VIRTUAL_THREAD_EXECUTOR =
            task -> Thread.ofVirtual().name("wiremock-dns-", 0).start(task);

    private record CacheEntry(String hostname, long expiresAtNanos) {}

    private final Function<String, String> reverseDnsLookup;
    private final long batchTimeoutMillis;
    private final long cacheTtlNanos;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();

    public ClientSourceResolver() {
        this(ClientSourceResolver::jdkReverseLookup, DEFAULT_BATCH_TIMEOUT_MS, DEFAULT_CACHE_TTL);
    }

    public ClientSourceResolver(Function<String, String> reverseDnsLookup, long batchTimeoutMillis, Duration cacheTtl) {
        this.reverseDnsLookup = Objects.requireNonNull(reverseDnsLookup, "reverseDnsLookup must not be null");
        this.batchTimeoutMillis = Math.max(0L, batchTimeoutMillis);
        Duration effectiveTtl = cacheTtl != null ? cacheTtl : DEFAULT_CACHE_TTL;
        this.cacheTtlNanos = Math.max(0L, effectiveTtl.toNanos());
    }

    /**
     * Extracts the effective caller IP from a WireMock {@link Request}.
     * Precedence: {@code X-Forwarded-For} (first hop) -&gt; {@code X-Real-IP} -&gt; {@code request.getClientIp()}.
     *
     * @param request incoming WireMock request
     * @return normalized caller IP/token, or {@code ""} if unavailable
     */
    public static String extractSourceIp(Request request) {
        if (request == null) {
            return "";
        }

        HttpHeaders headers = request.getHeaders();
        if (headers != null) {
            HttpHeader xff = headers.getHeader("X-Forwarded-For");
            if (xff != null && xff.isPresent() && xff.firstValue() != null) {
                String rawFirstHop = xff.firstValue().split(",", 2)[0];
                String normalized = normalizeSourceToken(rawFirstHop);
                if (!normalized.isEmpty()) {
                    return normalized;
                }
            }

            HttpHeader xRealIp = headers.getHeader("X-Real-IP");
            if (xRealIp != null && xRealIp.isPresent() && xRealIp.firstValue() != null) {
                String normalized = normalizeSourceToken(xRealIp.firstValue());
                if (!normalized.isEmpty()) {
                    return normalized;
                }
            }
        }

        return normalizeSourceToken(request.getClientIp());
    }

    /**
     * Normalizes a raw IP or host token by stripping ports, IPv6 brackets/zones, and rejecting unsafe characters.
     *
     * @param raw raw IP/host string
     * @return normalized token, or {@code ""} if blank/unknown/invalid
     */
    public static String normalizeSourceToken(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "unknown".equalsIgnoreCase(trimmed)) {
            return "";
        }

        // Handle bracketed IPv6 e.g. [::1]:8443 or [2001:db8::1]
        if (trimmed.startsWith("[")) {
            int closeIdx = trimmed.indexOf(']');
            if (closeIdx > 1) {
                trimmed = trimmed.substring(1, closeIdx);
            } else {
                return "";
            }
        } else if (trimmed.indexOf('.') >= 0) {
            // Strip IPv4 :port when exactly one colon is present
            int firstColon = trimmed.indexOf(':');
            if (firstColon > 0 && trimmed.indexOf(':', firstColon + 1) < 0) {
                trimmed = trimmed.substring(0, firstColon);
            }
        }

        // Strip IPv6 zone index (e.g. fe80::1%eth0)
        int zoneIdx = trimmed.indexOf('%');
        if (zoneIdx > 0) {
            trimmed = trimmed.substring(0, zoneIdx);
        }

        if ("0:0:0:0:0:0:0:1".equals(trimmed)) {
            trimmed = "::1";
        }

        if (!SAFE_SOURCE_PATTERN.matcher(trimmed).matches()) {
            return "";
        }
        return trimmed;
    }

    /**
     * Resolves hostnames for the given collection of normalized source IPs within the bounded batch timeout.
     * Cache misses are resolved asynchronously on virtual threads and cached (including negative results).
     *
     * @param sourceIps source IP tokens to resolve
     * @return map of source IP to resolved hostname (empty string if unresolved or timed out)
     */
    public Map<String, String> resolveHosts(Collection<String> sourceIps) {
        if (sourceIps == null || sourceIps.isEmpty()) {
            return Map.of();
        }

        Set<String> uniqueTokens = new LinkedHashSet<>();
        for (String raw : sourceIps) {
            String normalized = normalizeSourceToken(raw);
            if (!normalized.isEmpty()) {
                uniqueTokens.add(normalized);
            }
        }
        if (uniqueTokens.isEmpty()) {
            return Map.of();
        }

        Map<String, String> resolved = new HashMap<>();
        List<Map.Entry<String, CompletableFuture<String>>> pending = new ArrayList<>();
        long now = System.nanoTime();

        for (String token : uniqueTokens) {
            if (isLoopbackToken(token)) {
                resolved.put(token, "localhost");
                continue;
            }

            CacheEntry cached = cache.get(token);
            if (cached != null && now < cached.expiresAtNanos()) {
                resolved.put(token, cached.hostname());
                continue;
            }

            CompletableFuture<String> future = getOrStartLookup(token);
            pending.add(Map.entry(token, future));
        }

        if (!pending.isEmpty()) {
            if (batchTimeoutMillis > 0L) {
                CompletableFuture<?>[] all =
                        pending.stream().map(Map.Entry::getValue).toArray(CompletableFuture[]::new);
                try {
                    CompletableFuture.allOf(all).get(batchTimeoutMillis, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException | TimeoutException ignored) {
                    // Slow lookups finish asynchronously on their virtual thread and warm the cache
                }
            }

            for (Map.Entry<String, CompletableFuture<String>> entry : pending) {
                String token = entry.getKey();
                CompletableFuture<String> future = entry.getValue();
                if (future.isDone() && !future.isCompletedExceptionally()) {
                    resolved.put(token, sanitizeHostResult(future.getNow("")));
                } else {
                    resolved.put(token, "");
                }
            }
        }

        return resolved;
    }

    private CompletableFuture<String> getOrStartLookup(String token) {
        return inFlight.computeIfAbsent(token, key -> CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return sanitizeHostResult(reverseDnsLookup.apply(key));
                            } catch (RuntimeException e) {
                                return "";
                            }
                        },
                        VIRTUAL_THREAD_EXECUTOR)
                .whenComplete((host, err) -> {
                    String safeHost = err == null ? sanitizeHostResult(host) : "";
                    putInCache(key, safeHost);
                    inFlight.remove(key);
                }));
    }

    private void putInCache(String token, String host) {
        if (cacheTtlNanos <= 0L) {
            return;
        }
        long now = System.nanoTime();
        if (cache.size() >= MAX_CACHE_SIZE) {
            cache.entrySet().removeIf(entry -> now >= entry.getValue().expiresAtNanos());
            if (cache.size() >= MAX_CACHE_SIZE) {
                cache.clear();
            }
        }
        cache.put(token, new CacheEntry(host, now + cacheTtlNanos));
    }

    private static String sanitizeHostResult(String host) {
        if (host == null) {
            return "";
        }
        String trimmed = host.trim();
        if (trimmed.isEmpty() || !SAFE_SOURCE_PATTERN.matcher(trimmed).matches()) {
            return "";
        }
        return trimmed;
    }

    static boolean isLoopbackToken(String token) {
        return "127.0.0.1".equals(token)
                || "::1".equals(token)
                || "0:0:0:0:0:0:0:1".equals(token)
                || "localhost".equalsIgnoreCase(token);
    }

    static String jdkReverseLookup(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        if (isLoopbackToken(token)) {
            return "localhost";
        }

        byte[] ipBytes = parseIpLiteral(token);
        if (ipBytes.length > 0) {
            try {
                InetAddress addr = InetAddress.getByAddress(ipBytes);
                if (addr.isLoopbackAddress()) {
                    return "localhost";
                }
                String host = addr.getHostName();
                if (host == null || host.isBlank()) {
                    return "";
                }
                String normalizedHost = normalizeSourceToken(host);
                if (normalizedHost.isEmpty()
                        || normalizedHost.equalsIgnoreCase(normalizeSourceToken(token))
                        || normalizedHost.equalsIgnoreCase(addr.getHostAddress())) {
                    return "";
                }
                return normalizedHost;
            } catch (UnknownHostException e) {
                return "";
            }
        }

        // If proxy forwarded a hostname token directly, accept valid hostnames with zero DNS lookup
        if (HOSTNAME_PATTERN.matcher(token).matches() && containsAsciiLetter(token)) {
            return token.toLowerCase(Locale.ROOT);
        }
        return "";
    }

    private static boolean containsAsciiLetter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                return true;
            }
        }
        return false;
    }

    static byte[] parseIpLiteral(String token) {
        if (token == null || token.isEmpty()) {
            return EMPTY_BYTES;
        }
        if (token.indexOf(':') >= 0) {
            return parseIpv6Literal(token);
        }
        return parseIpv4Literal(token);
    }

    private static byte[] parseIpv4Literal(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 4) {
            return EMPTY_BYTES;
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3) {
                return EMPTY_BYTES;
            }
            for (int j = 0; j < part.length(); j++) {
                char c = part.charAt(j);
                if (c < '0' || c > '9') {
                    return EMPTY_BYTES;
                }
            }
            int val = Integer.parseInt(part);
            if (val < 0 || val > 255) {
                return EMPTY_BYTES;
            }
            bytes[i] = (byte) val;
        }
        return bytes;
    }

    private static byte[] parseIpv6Literal(String token) {
        // Reject any non-IPv6 characters before parsing so no DNS lookup ever occurs
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean valid =
                    (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == ':' || c == '.';
            if (!valid) {
                return EMPTY_BYTES;
            }
        }

        int doubleColonIdx = token.indexOf("::");
        if (doubleColonIdx >= 0 && token.indexOf("::", doubleColonIdx + 2) >= 0) {
            return EMPTY_BYTES;
        }

        String[] halves = doubleColonIdx >= 0
                ? new String[] {token.substring(0, doubleColonIdx), token.substring(doubleColonIdx + 2)}
                : new String[] {token};

        List<Integer> headGroups = parseIpv6Groups(halves[0], halves.length == 1);
        if (headGroups == null) {
            return EMPTY_BYTES;
        }

        List<Integer> tailGroups = List.of();
        if (halves.length == 2 && !halves[1].isEmpty()) {
            tailGroups = parseIpv6Groups(halves[1], true);
            if (tailGroups == null) {
                return EMPTY_BYTES;
            }
        }

        int totalGroups = headGroups.size() + tailGroups.size();
        if (doubleColonIdx >= 0) {
            if (totalGroups >= 8) {
                return EMPTY_BYTES;
            }
        } else if (totalGroups != 8) {
            return EMPTY_BYTES;
        }

        byte[] result = new byte[16];
        int outIdx = 0;
        for (int group : headGroups) {
            result[outIdx++] = (byte) ((group >>> 8) & 0xFF);
            result[outIdx++] = (byte) (group & 0xFF);
        }
        int zeroGroups = doubleColonIdx >= 0 ? (8 - totalGroups) : 0;
        outIdx += zeroGroups * 2;
        for (int group : tailGroups) {
            result[outIdx++] = (byte) ((group >>> 8) & 0xFF);
            result[outIdx++] = (byte) (group & 0xFF);
        }
        return result;
    }

    private static List<Integer> parseIpv6Groups(String segment, boolean allowTrailingIpv4) {
        if (segment.isEmpty()) {
            return List.of();
        }
        String[] parts = segment.split(":", -1);
        List<Integer> groups = new ArrayList<>(parts.length);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) {
                return null;
            }
            if (part.indexOf('.') >= 0) {
                if (!allowTrailingIpv4 || i != parts.length - 1) {
                    return null;
                }
                byte[] ipv4 = parseIpv4Literal(part);
                if (ipv4 == null) {
                    return null;
                }
                groups.add(((ipv4[0] & 0xFF) << 8) | (ipv4[1] & 0xFF));
                groups.add(((ipv4[2] & 0xFF) << 8) | (ipv4[3] & 0xFF));
            } else {
                if (part.length() > 4) {
                    return null;
                }
                groups.add(Integer.parseInt(part, 16));
            }
        }
        return groups;
    }
}
