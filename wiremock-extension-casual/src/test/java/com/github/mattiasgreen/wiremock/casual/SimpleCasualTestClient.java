package com.github.mattiasgreen.wiremock.casual;

/**
 * Super-lightweight client for testing Casual wire protocol interactions.
 * Delegates to {@link CasualTcpClient}.
 */
public class SimpleCasualTestClient extends CasualTcpClient {

    public SimpleCasualTestClient(String host, int port) {
        super(host, port);
    }
}
