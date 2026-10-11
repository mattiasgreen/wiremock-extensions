package com.github.mattiasgreen.wiremock.netem.model;

import java.util.List;
import java.util.Locale;

public final class NetEmPreset {

    private NetEmPreset() {}

    public static List<NetEmToxicConfig> getPresetToxics(String presetName) {
        if (presetName == null) {
            return List.of();
        }
        String normalized = presetName.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "3G_MOBILE", "MOBILE_3G", "3G" -> List.of(
                    NetEmToxicConfig.builder("mobile-latency", NetEmToxicType.LATENCY).stream(
                                    NetEmStreamDirection.DOWNSTREAM)
                            .attribute("latency", 250L)
                            .attribute("jitter", 50L)
                            .build(),
                    NetEmToxicConfig.builder("mobile-bandwidth", NetEmToxicType.BANDWIDTH).stream(
                                    NetEmStreamDirection.DOWNSTREAM)
                            .attribute("rate", 64L) // 64 KB/s
                            .build());
            case "FLAKY_WIFI", "WIFI" -> List.of(
                    NetEmToxicConfig.builder("flaky-latency", NetEmToxicType.LATENCY).stream(
                                    NetEmStreamDirection.DOWNSTREAM)
                            .attribute("latency", 100L)
                            .attribute("jitter", 40L)
                            .build(),
                    NetEmToxicConfig.builder("flaky-resets", NetEmToxicType.RESET_PEER).stream(
                                    NetEmStreamDirection.DOWNSTREAM)
                            .toxicity(0.2) // 20% drops
                            .build());
            case "BLACK_HOLE", "TIMEOUT" -> List.of(
                    NetEmToxicConfig.builder("blackhole-timeout", NetEmToxicType.TIMEOUT).stream(
                                    NetEmStreamDirection.DOWNSTREAM)
                            .attribute("timeout", 30000L)
                            .build());
            case "CHAOS_MONKEY", "CHAOS" -> List.of(
                    NetEmToxicConfig.builder("chaos-latency", NetEmToxicType.LATENCY).stream(NetEmStreamDirection.BOTH)
                            .attribute("latency", 150L)
                            .attribute("jitter", 75L)
                            .build(),
                    NetEmToxicConfig.builder("chaos-slicer", NetEmToxicType.SLICER).stream(
                                    NetEmStreamDirection.DOWNSTREAM)
                            .attribute("average_size", 64)
                            .attribute("delay", 10L)
                            .build(),
                    NetEmToxicConfig.builder("chaos-resets", NetEmToxicType.RESET_PEER).stream(
                                    NetEmStreamDirection.DOWNSTREAM)
                            .toxicity(0.1)
                            .build());
            default -> throw new IllegalArgumentException("Unknown preset: " + presetName);
        };
    }
}
