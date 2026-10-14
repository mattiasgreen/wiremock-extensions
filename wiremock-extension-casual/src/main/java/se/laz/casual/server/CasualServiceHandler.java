package se.laz.casual.server;

import se.laz.casual.network.protocol.messages.service.CasualServiceCallReplyMessage;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallRequestMessage;

/**
 * Functional interface for handling inbound Casual service calls.
 * <p>
 * Intended for upstream contribution to {@code se.laz.casual:casual-server}.
 */
@FunctionalInterface
public interface CasualServiceHandler {

    /**
     * Handles an incoming service invocation and produces a reply message.
     */
    CasualServiceCallReplyMessage handle(CasualServiceCallRequestMessage request);
}
