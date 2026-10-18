package com.github.mattiasgreen.wiremock.otel;

import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmProxy;
import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmRegistry;
import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.Admin;
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
            String metricsText = scrapeAllMetrics(admin);
            return ResponseDefinitionBuilder.responseDefinition()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                    .withBody(metricsText)
                    .build();
        });

        router.add(RequestMethod.GET, "/metrics", (admin, serveEvent, pathParams) -> {
            String metricsText = scrapeAllMetrics(admin);
            return ResponseDefinitionBuilder.responseDefinition()
                    .withStatus(200)
                    .withHeader("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
                    .withBody(metricsText)
                    .build();
        });
    }

    private String scrapeAllMetrics(Admin admin) {
        String baseMetrics = metricsRegistry.scrapePrometheusText();
        String netemMetrics = formatNetEmMetrics();
        String runtimeMetrics = formatRuntimeMetrics(admin);
        return baseMetrics + netemMetrics + runtimeMetrics;
    }

    private static String formatRuntimeMetrics(Admin admin) {
        if (admin == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try {
            int journalSize = admin.getServeEvents().getServeEvents().size();
            sb.append("\n# HELP wiremock_journal_size_requests Current count of requests in WireMock journal\n");
            sb.append("# TYPE wiremock_journal_size_requests gauge\n");
            sb.append("wiremock_journal_size_requests ").append(journalSize).append("\n");

            int journalCapacity = -1;
            if (admin.getOptions() != null && admin.getOptions().maxRequestJournalEntries() != null) {
                journalCapacity = admin.getOptions().maxRequestJournalEntries().orElse(-1);
            }
            sb.append(
                    "\n# HELP wiremock_journal_capacity Maximum configured capacity for WireMock request journal (-1 if unbounded)\n");
            sb.append("# TYPE wiremock_journal_capacity gauge\n");
            sb.append("wiremock_journal_capacity ").append(journalCapacity).append("\n");

            int activeStubs = admin.listAllStubMappings().getMappings().size();
            sb.append("\n# HELP wiremock_stubs_total Total number of stub mappings in WireMock\n");
            sb.append("# TYPE wiremock_stubs_total gauge\n");
            sb.append("wiremock_stubs_total{status=\"active\"} ")
                    .append(activeStubs)
                    .append("\n");
        } catch (RuntimeException ignored) {
            return "";
        }
        return sb.toString();
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
