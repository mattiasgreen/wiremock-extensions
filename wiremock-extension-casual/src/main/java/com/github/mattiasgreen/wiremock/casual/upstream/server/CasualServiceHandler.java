package com.github.mattiasgreen.wiremock.casual.upstream.server;

import se.laz.casual.network.protocol.messages.service.CasualServiceCallReplyMessage;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallRequestMessage;

/**
 * Functional interface for handling inbound Casual service calls.
 * <p>
 * Candidate for upstream contribution to {@code casualcore/casual-java}.
 */
@FunctionalInterface
public interface CasualServiceHandler {

    /**
     * Handles an incoming service invocation and produces a reply message.
     */
    CasualServiceCallReplyMessage handle(CasualServiceCallRequestMessage request);
}
