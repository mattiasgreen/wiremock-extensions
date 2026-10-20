package com.github.mattiasgreen.wiremock.netem.matcher;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SourceIpMatcherTest {

    @Test
    @DisplayName("Matches exact IPv4 address")
    void shouldMatchExactIp() throws Exception {
        InetAddress localhost = InetAddress.getByName("127.0.0.1");
        assertThat(SourceIpMatcher.matches(localhost, "127.0.0.1")).isTrue();
        assertThat(SourceIpMatcher.matches(localhost, "192.168.1.100")).isFalse();
    }

    @Test
    @DisplayName("Matches IPv4 CIDR blocks")
    void shouldMatchCidrBlocks() throws Exception {
        InetAddress clientInSubnet = InetAddress.getByName("192.168.1.42");
        assertThat(SourceIpMatcher.matches(clientInSubnet, "192.168.1.0/24")).isTrue();
        assertThat(SourceIpMatcher.matches(clientInSubnet, "192.168.0.0/16")).isTrue();
        assertThat(SourceIpMatcher.matches(clientInSubnet, "10.0.0.0/8")).isFalse();

        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        assertThat(SourceIpMatcher.matches(loopback, "127.0.0.0/8")).isTrue();
    }

    @Test
    @DisplayName("Null or blank configured IP matches all addresses")
    void shouldMatchAllWhenNullOrBlank() throws Exception {
        InetAddress addr = InetAddress.getByName("10.50.2.1");
        assertThat(SourceIpMatcher.matches(addr, null)).isTrue();
        assertThat(SourceIpMatcher.matches(addr, "")).isTrue();
        assertThat(SourceIpMatcher.matches(addr, "   ")).isTrue();
    }

    @Test
    @DisplayName("Handles invalid CIDR gracefully")
    void shouldHandleInvalidCidrGracefully() throws Exception {
        InetAddress addr = InetAddress.getByName("127.0.0.1");
        assertThat(SourceIpMatcher.matches(addr, "127.0.0.1/abc")).isFalse();
        assertThat(SourceIpMatcher.matches(addr, "not-an-ip")).isFalse();
    }
}
