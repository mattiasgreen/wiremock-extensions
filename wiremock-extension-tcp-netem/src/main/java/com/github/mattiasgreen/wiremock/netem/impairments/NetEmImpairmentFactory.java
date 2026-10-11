package com.github.mattiasgreen.wiremock.netem.impairments;

import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicConfig;
import java.util.Objects;

public final class NetEmImpairmentFactory {

    private NetEmImpairmentFactory() {}

    public static NetEmImpairment create(NetEmToxicConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        return switch (config.getType()) {
            case LATENCY -> new LatencyImpairment(config);
            case BANDWIDTH -> new BandwidthImpairment(config);
            case SLOW_CLOSE -> new SlowCloseImpairment(config);
            case TIMEOUT -> new TimeoutImpairment(config);
            case RESET_PEER -> new ResetPeerImpairment(config);
            case SLICER -> new SlicerImpairment(config);
            case LIMIT_DATA -> new LimitDataImpairment(config);
        };
    }
}
