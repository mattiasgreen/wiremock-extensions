package se.laz.casual.server;

import java.util.List;
import se.laz.casual.network.protocol.messages.domain.Service;

/**
 * Functional interface for resolving service discovery queries.
 * <p>
 * Intended for upstream contribution to {@code se.laz.casual:casual-server}.
 */
@FunctionalInterface
public interface CasualDiscoveryHandler {

    /**
     * Resolves discoverable services for the requested service names.
     */
    List<Service> discover(List<String> serviceNames);
}
