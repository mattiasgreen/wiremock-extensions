package com.github.mattiasgreen.wiremock.otel;

import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmProxy;
import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmRegistry;
import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import java.util.Collection;
import java.util.Objects;

public class PrometheusAdminEndpoint implements AdminApiExtension {

    public static final String EXTENSION_NAME = "prometheus-admin-endpoint";

    private final OtelMetricsRegistry metricsRegistry;

    public PrometheusAdminEndpoint() {
        this(OtelMetricsRegistry.getInstance());
    }

    public PrometheusAdminEndpoint(OtelMetricsRegistry metricsRegistry) {
        this.metricsRegistry = Objects.requireNonNull(metricsRegistry, "metricsRegistry must not be null");
    }

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public void contributeAdminApiRoutes(Router router) {
        router.add(RequestMethod.GET, "/metrics/prometheus", (admin, serveEvent, pathParams) -> {
            String metricsText = scrapeAllMetrics();
            return ResponseDefinitionBuilder.responseDefinition()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                    .withBody(metricsText)
                    .build();
        });

        router.add(RequestMethod.GET, "/metrics", (admin, serveEvent, pathParams) -> {
            String metricsText = scrapeAllMetrics();
            return ResponseDefinitionBuilder.responseDefinition()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                    .withBody(metricsText)
                    .build();
        });
    }

    private String scrapeAllMetrics() {
        String baseMetrics = metricsRegistry.scrapePrometheusText();
        String netemMetrics = formatNetEmMetrics();
        return baseMetrics + netemMetrics;
    }

    private static String formatNetEmMetrics() {
        try {
            Collection<TcpNetEmProxy> proxies = TcpNetEmRegistry.getInstance().list();
            if (proxies == null || proxies.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("\n# HELP wiremock_netem_connections_active Active client connections on NetEm proxy\n");
            sb.append("# TYPE wiremock_netem_connections_active gauge\n");
            for (TcpNetEmProxy proxy : proxies) {
                String name = proxy.getConfig().getName();
                sb.append("wiremock_netem_connections_active{proxy=\"")
                        .append(name)
                        .append("\"} ")
                        .append(proxy.getStats().getActiveConnections())
                        .append("\n");
            }

            sb.append("\n# HELP wiremock_netem_transfers_total Total transfers processed through NetEm proxy\n");
            sb.append("# TYPE wiremock_netem_transfers_total counter\n");
            for (TcpNetEmProxy proxy : proxies) {
                String name = proxy.getConfig().getName();
                sb.append("wiremock_netem_transfers_total{proxy=\"")
                        .append(name)
                        .append("\",outcome=\"normal\",toxic=\"none\"} ")
                        .append(proxy.getStats().getNormalTransfers())
                        .append("\n");
                sb.append("wiremock_netem_transfers_total{proxy=\"")
                        .append(name)
                        .append("\",outcome=\"impaired\",toxic=\"any\"} ")
                        .append(proxy.getStats().getImpairedTransfers())
                        .append("\n");
                proxy.getStats().getToxicCounters().forEach((toxic, count) -> {
                    sb.append("wiremock_netem_transfers_total{proxy=\"")
                            .append(name)
                            .append("\",outcome=\"impaired\",toxic=\"")
                            .append(toxic)
                            .append("\"} ")
                            .append(count)
                            .append("\n");
                });
            }

            sb.append("\n# HELP wiremock_netem_bytes_transferred_total Total bytes transferred through NetEm proxy\n");
            sb.append("# TYPE wiremock_netem_bytes_transferred_total counter\n");
            for (TcpNetEmProxy proxy : proxies) {
                String name = proxy.getConfig().getName();
                sb.append("wiremock_netem_bytes_transferred_total{proxy=\"")
                        .append(name)
                        .append("\",direction=\"upstream\",outcome=\"normal\"} ")
                        .append(proxy.getStats().getBytesUpstreamNormal())
                        .append("\n");
                sb.append("wiremock_netem_bytes_transferred_total{proxy=\"")
                        .append(name)
                        .append("\",direction=\"downstream\",outcome=\"normal\"} ")
                        .append(proxy.getStats().getBytesDownstreamNormal())
                        .append("\n");
                sb.append("wiremock_netem_bytes_transferred_total{proxy=\"")
                        .append(name)
                        .append("\",direction=\"upstream\",outcome=\"impaired\"} ")
                        .append(proxy.getStats().getBytesUpstreamImpaired())
                        .append("\n");
                sb.append("wiremock_netem_bytes_transferred_total{proxy=\"")
                        .append(name)
                        .append("\",direction=\"downstream\",outcome=\"impaired\"} ")
                        .append(proxy.getStats().getBytesDownstreamImpaired())
                        .append("\n");
            }
            return sb.toString();
        } catch (LinkageError | RuntimeException ignored) {
            return "";
        }
    }
}
