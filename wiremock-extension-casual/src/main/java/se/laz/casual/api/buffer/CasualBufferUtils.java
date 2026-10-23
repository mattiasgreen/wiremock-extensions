package se.laz.casual.api.buffer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import se.laz.casual.api.buffer.type.JsonBuffer;
import se.laz.casual.api.buffer.type.ServiceBuffer;

/**
 * Utility functions for Casual buffers, including text/JSON extraction, Base64
 * binary encoding/decoding for non-JSON buffers (.FIELDED, .X_OCTET), and hex dumps.
 * <p>
 * Intended for upstream contribution to {@code se.laz.casual:casual-api}.
 */
public final class CasualBufferUtils {

    private CasualBufferUtils() {}

    /**
     * Determines whether the specified buffer type represents JSON.
     */
    public static boolean isJsonType(String bufferType) {
        if (bufferType == null || bufferType.isBlank()) {
            return true;
        }
        String normalized = bufferType.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("json");
    }

    /**
     * Combines all payload chunks in a {@link ServiceBuffer} into a single byte array.
     */
    public static byte[] toByteArray(ServiceBuffer buffer) {
        if (buffer == null || buffer.isEmpty()) {
            return new byte[0];
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        for (byte[] part : buffer.getBytes()) {
            if (part != null && part.length > 0) {
                baos.write(part, 0, part.length);
            }
        }
        return baos.toByteArray();
    }

    /**
     * Extracts payload from a {@link ServiceBuffer}.
     * Returns UTF-8 text for JSON buffers, or Base64 string for binary buffers (.FIELDED, .X_OCTET).
     */
    public static String extractPayload(ServiceBuffer buffer) {
        if (buffer == null || buffer.isEmpty()) {
            return "";
        }
        if (isJsonType(buffer.getType())) {
            StringBuilder sb = new StringBuilder();
            for (byte[] part : buffer.getBytes()) {
                if (part != null) {
                    sb.append(new String(part, StandardCharsets.UTF_8));
                }
            }
            return sb.toString();
        }
        return Base64.getEncoder().encodeToString(toByteArray(buffer));
    }

    /**
     * Wraps payload text back into a {@link ServiceBuffer} based on buffer type.
     * Decodes Base64 for non-JSON buffers, or creates a {@link JsonBuffer} for JSON buffers.
     */
    public static ServiceBuffer wrapPayload(String bufferType, String payload) {
        if (payload == null || payload.isBlank()) {
            return ServiceBuffer.empty();
        }
        String type = (bufferType != null && !bufferType.isBlank()) ? bufferType.trim() : "JSON";
        if (isJsonType(type)) {
            return ServiceBuffer.of(JsonBuffer.of(payload));
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(payload.trim());
            return ServiceBuffer.of(type, List.of(decoded));
        } catch (IllegalArgumentException e) {
            // Fall back to UTF-8 raw bytes if not valid Base64
            return ServiceBuffer.of(type, List.of(payload.getBytes(StandardCharsets.UTF_8)));
        }
    }

    /**
     * Formats a byte array as a concise hex dump for TRACE level diagnostic logging.
     */
    public static String toHexDump(byte[] bytes, int maxBytes) {
        if (bytes == null || bytes.length == 0) {
            return "<empty>";
        }
        int len = Math.min(bytes.length, maxBytes);
        StringBuilder sb = new StringBuilder(len * 3 + 32);
        for (int i = 0; i < len; i++) {
            sb.append(String.format(Locale.ROOT, "%02X ", bytes[i]));
            if ((i + 1) % 16 == 0 && i + 1 < len) {
                sb.append("\n");
            }
        }
        if (bytes.length > maxBytes) {
            sb.append("... [").append(bytes.length - maxBytes).append(" more bytes]");
        }
        return sb.toString();
    }
}
