package se.laz.casual.api.buffer;

import java.nio.charset.StandardCharsets;
import se.laz.casual.api.buffer.type.ServiceBuffer;

/**
 * Utility functions for Casual buffers.
 * <p>
 * Intended for upstream contribution to {@code se.laz.casual:casual-api}.
 */
public final class CasualBufferUtils {

    private CasualBufferUtils() {}

    /**
     * Extracts text payload from a {@link ServiceBuffer} using UTF-8 charset.
     */
    public static String extractPayload(ServiceBuffer buffer) {
        if (buffer == null || buffer.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (byte[] part : buffer.getBytes()) {
            sb.append(new String(part, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
