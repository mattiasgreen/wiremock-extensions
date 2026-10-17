package com.github.mattiasgreen.wiremock.casual;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformerV2;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;

/**
 * In-process WireMock {@link ResponseDefinitionTransformerV2} that proxies matched
 * {@code /__casual/services/*} proxy stubs (`metadata.mode = "proxy"`) through
 * {@link CasualWireMockGateway} to the upstream Casual TCP server.
 * <p>
 * This enables Casual service calls in Live Proxy mode to be logged as {@code PROXIED}
 * in the WireMock Request Journal with their full upstream payload and Casual headers,
 * and seamlessly snapshotted into offline static stubs via the WireMock Console UI.
 */
public class CasualProxyTransformer implements ResponseDefinitionTransformerV2 {

    public static final String NAME = "casual-proxy-transformer";

    private volatile CasualWireMockGateway gateway;

    public CasualProxyTransformer() {}

    public CasualProxyTransformer(CasualWireMockGateway gateway) {
        this.gateway = gateway;
    }

    public void setGateway(CasualWireMockGateway gateway) {
        this.gateway = gateway;
    }

    public CasualWireMockGateway getGateway() {
        return gateway;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean applyGlobally() {
        return false;
    }

    @Override
    public ResponseDefinition transform(ServeEvent serveEvent) {
        CasualWireMockGateway activeGateway = this.gateway;
        if (activeGateway == null || !activeGateway.hasUpstream()) {
            return ResponseDefinitionBuilder.responseDefinition()
                    .withStatus(502)
                    .withHeader("Content-Type", "application/json")
                    .withHeader("X-Casual-Error-State", "TPESVCERR")
                    .withBody("{\"error\":\"Casual upstream server is not configured or offline\"}")
                    .build();
        }
        return activeGateway.proxyServeEventToUpstream(serveEvent);
    }
}
