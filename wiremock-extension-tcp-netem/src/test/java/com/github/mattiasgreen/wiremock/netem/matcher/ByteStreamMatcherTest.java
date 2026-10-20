package com.github.mattiasgreen.wiremock.netem.matcher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ByteStreamMatcherTest {

    @Test
    @DisplayName("Matches exact ASCII substring in a single buffer")
    void shouldMatchAsciiSubstringInSingleBuffer() {
        ByteStreamMatcher matcher = new ByteStreamMatcher("order-service-42", ByteStreamMatcher.PatternType.ASCII);
        byte[] payload =
                "GET /api/v1/cases HTTP/1.1\r\nX-Service: order-service-42\r\n\r\n".getBytes(StandardCharsets.UTF_8);

        assertThat(matcher.matches(payload, 0, payload.length)).isTrue();
    }

    @Test
    @DisplayName("Matches raw hexadecimal byte sequences (e.g. Casual message header)")
    void shouldMatchHexSequence() {
        // Casual magic header: 0xCA, 0x50, 0x00, 0x01
        ByteStreamMatcher matcher = new ByteStreamMatcher("CA 50 00 01", ByteStreamMatcher.PatternType.HEX);

        byte[] casualPacket = new byte[] {0x00, 0x12, (byte) 0xCA, 0x50, 0x00, 0x01, 0x7F, 0x2A};
        assertThat(matcher.matches(casualPacket, 0, casualPacket.length)).isTrue();

        byte[] otherPacket = new byte[] {0x00, 0x12, (byte) 0xCA, 0x50, 0x00, 0x02, 0x7F, 0x2A};
        assertThat(matcher.matches(otherPacket, 0, otherPacket.length)).isFalse();
    }

    @Test
    @DisplayName("Chunk Boundary Test: Matches pattern split across multiple continuous TCP reads")
    void shouldMatchAcrossChunkBoundariesWithStreamScanner() {
        ByteStreamMatcher matcher = new ByteStreamMatcher("CHAOS_TARGET", ByteStreamMatcher.PatternType.ASCII);
        ByteStreamMatcher.StreamScanner scanner = matcher.createScanner();

        byte[] chunk1 = "Some initial payload before CH".getBytes(StandardCharsets.UTF_8);
        byte[] chunk2 = "AOS_TAR".getBytes(StandardCharsets.UTF_8);
        byte[] chunk3 = "GET and remaining data".getBytes(StandardCharsets.UTF_8);

        assertThat(scanner.update(chunk1, 0, chunk1.length)).isFalse();
        assertThat(scanner.update(chunk2, 0, chunk2.length)).isFalse();
        assertThat(scanner.update(chunk3, 0, chunk3.length)).isTrue();
        assertThat(scanner.isMatched()).isTrue();
    }

    @Test
    @DisplayName("Chunk Boundary Test: Hex sequence split byte-by-byte across TCP chunks")
    void shouldMatchHexAcrossSingleByteChunks() {
        ByteStreamMatcher matcher = new ByteStreamMatcher("DE AD BE EF", ByteStreamMatcher.PatternType.HEX);
        ByteStreamMatcher.StreamScanner scanner = matcher.createScanner();

        byte[][] bytes = new byte[][] {
            new byte[] {(byte) 0xDE}, new byte[] {(byte) 0xAD}, new byte[] {(byte) 0xBE}, new byte[] {(byte) 0xEF}
        };

        assertThat(scanner.update(bytes[0], 0, 1)).isFalse();
        assertThat(scanner.update(bytes[1], 0, 1)).isFalse();
        assertThat(scanner.update(bytes[2], 0, 1)).isFalse();
        assertThat(scanner.update(bytes[3], 0, 1)).isTrue();
        assertThat(scanner.isMatched()).isTrue();
    }

    @Test
    @DisplayName("Rejects odd-length invalid hex strings")
    void shouldRejectInvalidHex() {
        assertThatThrownBy(() -> new ByteStreamMatcher("CA500", ByteStreamMatcher.PatternType.HEX))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("even number");
    }

    @Test
    @DisplayName("Matches empty pattern unconditionally")
    void shouldMatchEmptyPattern() {
        ByteStreamMatcher matcher = new ByteStreamMatcher("", ByteStreamMatcher.PatternType.ASCII);
        byte[] data = "anything".getBytes(StandardCharsets.UTF_8);
        assertThat(matcher.matches(data, 0, data.length)).isTrue();
    }
}
