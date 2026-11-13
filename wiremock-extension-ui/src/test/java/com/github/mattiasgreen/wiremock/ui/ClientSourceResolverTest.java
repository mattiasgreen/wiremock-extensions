package com.github.mattiasgreen.wiremock.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.Request;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClientSourceResolverTest {

    @Test
    @DisplayName("Extracts effective source IP with X-Forwarded-For > X-Real-IP > clientIp precedence")
    void shouldExtractSourceIpWithPrecedence() {
        Request xffReq = stubRequest(
                "127.0.0.1", Map.of("X-Forwarded-For", "203.0.113.50, 10.0.0.1", "X-Real-IP", "198.51.100.2"));
        assertThat(ClientSourceResolver.extractSourceIp(xffReq)).isEqualTo("203.0.113.50");

        Request realIpReq = stubRequest("127.0.0.1", Map.of("X-Real-IP", "198.51.100.2"));
        assertThat(ClientSourceResolver.extractSourceIp(realIpReq)).isEqualTo("198.51.100.2");

        Request socketReq = stubRequest("10.24.8.15:54321", Map.of());
        assertThat(ClientSourceResolver.extractSourceIp(socketReq)).isEqualTo("10.24.8.15");

        assertThat(ClientSourceResolver.extractSourceIp(null)).isEmpty();
    }

    @Test
    @DisplayName("Normalizes IPv4/IPv6 tokens with ports or zone IDs and rejects unsafe strings")
    void shouldNormalizeAndRejectUnsafeSourceTokens() {
        assertThat(ClientSourceResolver.normalizeSourceToken("203.0.113.50:8080"))
                .isEqualTo("203.0.113.50");
        assertThat(ClientSourceResolver.normalizeSourceToken("[2001:db8::1]:443"))
                .isEqualTo("2001:db8::1");
        assertThat(ClientSourceResolver.normalizeSourceToken("[fe80::1%eth0]:9000"))
                .isEqualTo("fe80::1");
        assertThat(ClientSourceResolver.normalizeSourceToken("0:0:0:0:0:0:0:1")).isEqualTo("::1");
        assertThat(ClientSourceResolver.normalizeSourceToken(" 10.1.2.3 ")).isEqualTo("10.1.2.3");

        assertThat(ClientSourceResolver.normalizeSourceToken("<script>alert(1)</script>"))
                .isEmpty();
        assertThat(ClientSourceResolver.normalizeSourceToken("unknown")).isEmpty();
        assertThat(ClientSourceResolver.normalizeSourceToken("invalid space token"))
                .isEmpty();
        assertThat(ClientSourceResolver.normalizeSourceToken("   ")).isEmpty();
        assertThat(ClientSourceResolver.normalizeSourceToken(null)).isEmpty();
    }

    @Test
    @DisplayName("Fast-paths loopback addresses to localhost without invoking reverse DNS")
    void shouldFastPathLoopbackAddressesWithoutDnsLookup() {
        AtomicInteger calls = new AtomicInteger();
        ClientSourceResolver resolver = new ClientSourceResolver(
                ip -> {
                    calls.incrementAndGet();
                    return "unexpected.host";
                },
                50,
                Duration.ofMinutes(15));

        Map<String, String> resolved = resolver.resolveHosts(List.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1"));

        assertThat(resolved).containsEntry("127.0.0.1", "localhost").containsEntry("::1", "localhost");
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("Caches both positive and negative reverse DNS lookups within TTL")
    void shouldCachePositiveAndNegativeLookups() {
        AtomicInteger calls = new AtomicInteger();
        ClientSourceResolver resolver = new ClientSourceResolver(
                ip -> {
                    calls.incrementAndGet();
                    return "10.20.30.40".equals(ip) ? "app-01.corp.internal" : "";
                },
                200,
                Duration.ofMinutes(15));

        Map<String, String> first = resolver.resolveHosts(List.of("10.20.30.40", "10.20.30.99"));
        Map<String, String> second = resolver.resolveHosts(List.of("10.20.30.40", "10.20.30.99"));

        assertThat(first).containsEntry("10.20.30.40", "app-01.corp.internal").containsEntry("10.20.30.99", "");
        assertThat(second).containsEntry("10.20.30.40", "app-01.corp.internal").containsEntry("10.20.30.99", "");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Returns within batch timeout on slow reverse DNS and populates cache in background")
    void shouldReturnWithinBatchTimeoutAndPopulateCacheInBackground() throws Exception {
        CountDownLatch releaseLookup = new CountDownLatch(1);
        CountDownLatch lookupFinished = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();

        ClientSourceResolver resolver = new ClientSourceResolver(
                ip -> {
                    calls.incrementAndGet();
                    try {
                        releaseLookup.await(2, TimeUnit.SECONDS);
                        return "slow-worker-01.corp.internal";
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return "";
                    } finally {
                        lookupFinished.countDown();
                    }
                },
                25,
                Duration.ofMinutes(15));

        long startNanos = System.nanoTime();
        Map<String, String> initial = resolver.resolveHosts(List.of("10.99.0.1"));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertThat(initial).containsEntry("10.99.0.1", "");
        assertThat(elapsedMillis).isLessThan(500);

        releaseLookup.countDown();
        assertThat(lookupFinished.await(2, TimeUnit.SECONDS)).isTrue();

        Map<String, String> cached = resolver.resolveHosts(List.of("10.99.0.1"));
        assertThat(cached).containsEntry("10.99.0.1", "slow-worker-01.corp.internal");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Resolves many instant lookups without ConcurrentHashMap recursive update errors")
    void shouldResolveManyInstantLookupsWithoutRecursiveMapUpdate() {
        ClientSourceResolver resolver = new ClientSourceResolver(
                ip -> "host-" + ip.replace('.', '-') + ".internal", 2000, Duration.ofMinutes(15));
        List<String> ips = java.util.stream.IntStream.range(1, 65)
                .mapToObj(i -> "10.50.0." + i)
                .toList();

        Map<String, String> resolved = resolver.resolveHosts(ips);

        for (String ip : ips) {
            assertThat(resolved).containsEntry(ip, "host-" + ip.replace('.', '-') + ".internal");
        }
    }

    private static Request stubRequest(String clientIp, Map<String, String> headerMap) {
        List<HttpHeader> httpHeaders = headerMap.entrySet().stream()
                .map(entry -> HttpHeader.httpHeader(entry.getKey(), entry.getValue()))
                .toList();
        HttpHeaders headers = new HttpHeaders(httpHeaders);
        return (Request) Proxy.newProxyInstance(
                Request.class.getClassLoader(), new Class<?>[] {Request.class}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "getHeaders" -> headers;
                        case "getClientIp" -> clientIp;
                        default -> null;
                    };
                });
    }
}
