package se.laz.casual.client;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import se.laz.casual.api.buffer.CasualBufferUtils;
import se.laz.casual.api.flags.ErrorState;

/**
 * Result of a Casual service call (tpcall), encapsulating execution metadata,
 * ATMI error state, user return code, and payload.
 * <p>
 * Intended for upstream contribution to {@code se.laz.casual:casual-client}.
 */
public record CasualCallResult(
        String serviceName,
        ErrorState errorState,
        long userCode,
        UUID execution,
        String bufferType,
        String payload,
        long durationMs) {

    public CasualCallResult {
        Objects.requireNonNull(serviceName, "serviceName must not be null");
        Objects.requireNonNull(errorState, "errorState must not be null");
    }

    /**
     * Returns true if the service call succeeded (ATMI error state is OK).
     */
    public boolean isSuccess() {
        return errorState == ErrorState.OK;
    }

    /**
     * Returns true if this reply is a JSON buffer.
     */
    public boolean isJson() {
        return CasualBufferUtils.isJsonType(bufferType);
    }

    /**
     * Returns the raw payload bytes, decoding Base64 if this is a binary buffer,
     * or UTF-8 bytes if JSON/text.
     */
    public byte[] getBytes() {
        if (payload == null || payload.isEmpty()) {
            return new byte[0];
        }
        if (isJson()) {
            return payload.getBytes(StandardCharsets.UTF_8);
        }
        try {
            return Base64.getDecoder().decode(payload.trim());
        } catch (IllegalArgumentException e) {
            return payload.getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * Returns a formatted canonical hex dump of the payload bytes.
     */
    public String toHexDump(int maxBytes) {
        return CasualBufferUtils.toCanonicalHexDump(getBytes(), maxBytes);
    }
}
