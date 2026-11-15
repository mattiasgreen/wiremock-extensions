package com.github.mattiasgreen.wiremock.support;

import com.github.tomakehurst.wiremock.extension.Extension;
import java.util.List;

/**
 * Cross-extension SPI implemented by TCP NetEm providers so observability extensions (such as
 * {@code wiremock-extension-otel}) can collect proxy metrics without compile-time coupling.
 */
public interface NetEmMetricsSource extends Extension {

    /**
     * Captures a point-in-time snapshot of traffic counters for all registered TCP NetEm proxies.
     *
     * @return immutable list of proxy metric snapshots
     */
    List<NetEmProxyMetricsSnapshot> snapshotProxies();

    /**
     * Returns the WireMock extension registration name for this metrics source.
     *
     * @return extension name {@code "netem-metrics-source"}
     */
    @Override
    default String getName() {
        return "netem-metrics-source";
    }
}
