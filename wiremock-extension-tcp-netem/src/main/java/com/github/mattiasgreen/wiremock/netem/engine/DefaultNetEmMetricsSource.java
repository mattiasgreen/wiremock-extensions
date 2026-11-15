package com.github.mattiasgreen.wiremock.netem.engine;

import com.github.mattiasgreen.wiremock.support.NetEmMetricsSource;
import com.github.mattiasgreen.wiremock.support.NetEmProxyMetricsSnapshot;
import java.util.List;

/**
 * {@link NetEmMetricsSource} ServiceLoader provider delegating to the active {@link NetEmEngine}.
 */
public final class DefaultNetEmMetricsSource implements NetEmMetricsSource {

    @Override
    public List<NetEmProxyMetricsSnapshot> snapshotProxies() {
        return NetEmEngine.getDefault().snapshotProxies();
    }
}
