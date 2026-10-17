package com.github.mattiasgreen.wiremock.casual;

import se.laz.casual.client.CasualTcpClient;

/**
 * Super-lightweight client for testing Casual wire protocol interactions.
 * Delegates to {@link CasualTcpClient}.
 */
public class SimpleCasualTestClient extends CasualTcpClient {

    public SimpleCasualTestClient(String host, int port) {
        super(host, port);
    }
}
