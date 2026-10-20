package com.github.mattiasgreen.wiremock.netem.matcher;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * Protocol-agnostic zero-copy byte stream matcher using Knuth-Morris-Pratt (KMP) search.
 * Supports matching ASCII/UTF-8 substrings and raw hexadecimal byte sequences (e.g., Casual frames)
 * seamlessly across TCP chunk and packet boundaries.
 */
public final class ByteStreamMatcher {

    public enum PatternType {
        ASCII,
        HEX
    }

    private final byte[] patternBytes;
    private final int[] failureTable;
    private final PatternType patternType;
    private final String originalPattern;

    public ByteStreamMatcher(String pattern, String typeStr) {
        this(pattern, parseType(typeStr));
    }

    public ByteStreamMatcher(String pattern, PatternType type) {
        this.originalPattern = Objects.requireNonNull(pattern, "pattern must not be null");
        this.patternType = type != null ? type : PatternType.ASCII;
        this.patternBytes = parsePattern(pattern, this.patternType);
        this.failureTable = computeFailureTable(this.patternBytes);
    }

    public ByteStreamMatcher(byte[] rawBytes) {
        this.patternBytes =
                Objects.requireNonNull(rawBytes, "rawBytes must not be null").clone();
        this.patternType = PatternType.HEX;
        this.originalPattern = HexFormat.of().formatHex(this.patternBytes);
        this.failureTable = computeFailureTable(this.patternBytes);
    }

    public static PatternType parseType(String typeStr) {
        if (typeStr == null || typeStr.isBlank()) {
            return PatternType.ASCII;
        }
        String clean = typeStr.trim().toUpperCase(Locale.ROOT);
        if ("HEX".equals(clean) || "BYTES".equals(clean)) {
            return PatternType.HEX;
        }
        return PatternType.ASCII;
    }

    private static byte[] parsePattern(String pattern, PatternType type) {
        if (type == PatternType.HEX) {
            String sanitized = pattern.replaceAll("[^0-9a-fA-F]", "");
            if (sanitized.length() % 2 != 0) {
                throw new IllegalArgumentException("Hex pattern must contain an even number of hex digits: " + pattern);
            }
            if (sanitized.isEmpty()) {
                return new byte[0];
            }
            return HexFormat.of().parseHex(sanitized);
        }
        return pattern.getBytes(StandardCharsets.UTF_8);
    }

    private static int[] computeFailureTable(byte[] pattern) {
        int[] table = new int[pattern.length];
        int j = 0;
        for (int i = 1; i < pattern.length; i++) {
            while (j > 0 && pattern[i] != pattern[j]) {
                j = table[j - 1];
            }
            if (pattern[i] == pattern[j]) {
                j++;
            }
            table[i] = j;
        }
        return table;
    }

    public byte[] getPatternBytes() {
        return patternBytes.clone();
    }

    public PatternType getPatternType() {
        return patternType;
    }

    public String getOriginalPattern() {
        return originalPattern;
    }

    public boolean matches(byte[] buffer, int offset, int length) {
        if (patternBytes.length == 0) {
            return true;
        }
        if (buffer == null || length < patternBytes.length) {
            return false;
        }
        StreamScanner scanner = createScanner();
        return scanner.update(buffer, offset, length);
    }

    public StreamScanner createScanner() {
        return new StreamScanner();
    }

    /**
     * Stateful streaming scanner maintaining KMP state across continuous TCP chunk boundaries.
     */
    public class StreamScanner {
        private int matchedPrefix = 0;
        private boolean matched = false;

        public boolean update(byte[] buffer, int offset, int length) {
            if (matched) {
                return true;
            }
            if (patternBytes.length == 0) {
                matched = true;
                return true;
            }
            if (buffer == null || length <= 0) {
                return false;
            }

            int end = offset + length;
            for (int i = offset; i < end; i++) {
                byte b = buffer[i];
                while (matchedPrefix > 0 && patternBytes[matchedPrefix] != b) {
                    matchedPrefix = failureTable[matchedPrefix - 1];
                }
                if (patternBytes[matchedPrefix] == b) {
                    matchedPrefix++;
                }
                if (matchedPrefix == patternBytes.length) {
                    matched = true;
                    return true;
                }
            }
            return false;
        }

        public boolean isMatched() {
            return matched;
        }

        public void reset() {
            matchedPrefix = 0;
            matched = false;
        }
    }
}
