package com.github.mattiasgreen.wiremock.examples.banking;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.mattiasgreen.wiremock.casual.CasualWireMockGateway;
import com.github.mattiasgreen.wiremock.ui.UiExtensionFactory;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.Map;

/**
 * Orchestrates the 3-node Banking Progressive Simulation topology:
 * <ul>
 *   <li><b>Node 1 ({@link ProductPortalServer})</b>: Customer Portal Web UI &amp; Product System SUT.</li>
 *   <li><b>Node 2 ({@link WireMockServer} + {@link CasualWireMockGateway})</b>: Man-in-the-Middle proxy,
 *       Embedded Console UI ({@code /__admin/ui/}), and synthetic 2PC XA voter.</li>
 *   <li><b>Node 3 ({@link UpstreamBackendServer})</b>: All-in-one upstream backend hosting HTTP Case
 *       Management, HTTP CRM, and Casual 2PC Billing.</li>
 * </ul>
 */
public final class BankingSimulationHarness implements AutoCloseable {

    private final UpstreamBackendServer upstreamBackend;
    private final WireMockServer wireMockServer;
    private final CasualWireMockGateway casualGateway;
    private final ProductPortalServer productPortal;

    /**
     * Starts all 3 nodes and seeds the initial Live Proxy stubs in WireMock for
     * {@code case-service} (HTTP), {@code crm-service} (HTTP), and {@code billing-service} (Casual).
     *
     * @throws Exception if any node fails to start
     */
    public BankingSimulationHarness() throws Exception {
        this.upstreamBackend = new UpstreamBackendServer();
        this.upstreamBackend.start();

        this.wireMockServer = new WireMockServer(wireMockConfig().dynamicPort().extensions(new UiExtensionFactory()));
        this.wireMockServer.start();

        this.casualGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(wireMockServer)
                .build();
        this.casualGateway.start();

        seedInitialProxyStubs();

        this.productPortal = new ProductPortalServer(wireMockServer.baseUrl(), "127.0.0.1", casualGateway.getPort());
        this.productPortal.start();
    }

    private void seedInitialProxyStubs() {
        String upstreamHttpUrl = upstreamBackend.getHttpBaseUrl();

        // 1. Case Management HTTP Proxy Route
        wireMockServer.stubFor(any(urlMatching("/api/cases.*"))
                .withName("Live Service Proxy (Case Management)")
                .atPriority(10)
                .withMetadata(Map.of(
                        "project", "case-service",
                        "mode", "proxy",
                        "targetBaseUrl", upstreamHttpUrl))
                .willReturn(aResponse().proxiedFrom(upstreamHttpUrl)));

        // 2. CRM System HTTP Proxy Route
        wireMockServer.stubFor(any(urlMatching("/api/crm/.*"))
                .withName("Live Service Proxy (CRM System)")
                .atPriority(10)
                .withMetadata(Map.of(
                        "project", "crm-service",
                        "mode", "proxy",
                        "targetBaseUrl", upstreamHttpUrl))
                .willReturn(aResponse().proxiedFrom(upstreamHttpUrl)));

        // 3. Billing Platform Casual 2PC Proxy Route
        wireMockServer.stubFor(post(urlMatching("/__casual/services/.*"))
                .withName("Live Service Proxy (Billing Casual 2PC)")
                .atPriority(10)
                .withMetadata(Map.of(
                        "project", "billing-service",
                        "mode", "proxy",
                        "protocol", "casual",
                        "targetBaseUrl", "casual://127.0.0.1:" + upstreamBackend.getCasualPort()))
                .willReturn(aResponse().withStatus(200)));
    }

    /**
     * Returns Node 1 (Customer Portal &amp; Product System SUT).
     *
     * @return the {@link ProductPortalServer}
     */
    public ProductPortalServer getProductPortal() {
        return productPortal;
    }

    /**
     * Returns Node 2 ({@link WireMockServer}).
     *
     * @return the {@link WireMockServer}
     */
    public WireMockServer getWireMockServer() {
        return wireMockServer;
    }

    /**
     * Returns Node 2's {@link CasualWireMockGateway}.
     *
     * @return the {@link CasualWireMockGateway}
     */
    public CasualWireMockGateway getCasualGateway() {
        return casualGateway;
    }

    /**
     * Returns Node 3 (All-in-One Upstream Backend Server B).
     *
     * @return the {@link UpstreamBackendServer}
     */
    public UpstreamBackendServer getUpstreamBackend() {
        return upstreamBackend;
    }

    /**
     * Stops Node 3 (Upstream Backend Server B) completely while keeping Node 1 and Node 2 online
     * to verify full offline simulation in Stubbed Mode.
     */
    public void stopUpstreamBackend() {
        upstreamBackend.close();
    }

    @Override
    public void close() {
        if (productPortal != null) {
            productPortal.close();
        }
        if (casualGateway != null) {
            casualGateway.close();
        }
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
        if (upstreamBackend != null) {
            upstreamBackend.close();
        }
    }
}
