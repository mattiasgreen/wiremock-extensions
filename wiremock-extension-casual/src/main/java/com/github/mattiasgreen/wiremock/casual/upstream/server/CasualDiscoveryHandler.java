package com.github.mattiasgreen.wiremock.casual.upstream.server;

import java.util.List;
import se.laz.casual.network.protocol.messages.domain.Service;

/**
 * Functional interface for resolving service discovery queries.
 * <p>
 * Candidate for upstream contribution to {@code casualcore/casual-java}.
 */
@FunctionalInterface
public interface CasualDiscoveryHandler {

    /**
     * Resolves discoverable services for the requested service names.
     */
    List<Service> discover(List<String> serviceNames);
}
