package se.laz.casual.client;

import java.util.Objects;
import java.util.UUID;
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
}
