package com.github.mattiasgreen.wiremock.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Opt-in Chromium/Edge DevTools Protocol (CDP) profiler for Playwright E2E suites.
 * Enabled via {@code -Dui.profile=true}. Captures V8 CPU profiles ({@code .cpuprofile}),
 * CDP performance metrics (Layout, RecalcStyle, Script, Task, DOM Nodes, JSEventListeners, Heap),
 * Long Tasks (&gt;50ms), and Cumulative Layout Shift (CLS), and writes an aggregated summary report.
 */
final class UiDevToolsProfiler {

    static final String PROP_ENABLED = "ui.profile";

    private static final String OBSERVER_INIT_SCRIPT =
            """
            window.__cdpPerf = { longTasks: [], cls: 0 };
            if (window.PerformanceObserver) {
                try {
                    new PerformanceObserver(list => {
                        for (const entry of list.getEntries()) {
                            window.__cdpPerf.longTasks.push({
                                durationMs: Math.round(entry.duration),
                                startTimeMs: Math.round(entry.startTime)
                            });
                        }
                    }).observe({ entryTypes: ['longtask'] });
                } catch (e) {}
                try {
                    new PerformanceObserver(list => {
                        for (const entry of list.getEntries()) {
                            if (!entry.hadRecentInput) {
                                window.__cdpPerf.cls += entry.value;
                            }
                        }
                    }).observe({ type: 'layout-shift', buffered: true });
                } catch (e) {}
            }
            """;

    private static final List<TestProfileRecord> RECORDS = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, FunctionStats> AGGREGATED_FUNCTIONS = Collections.synchronizedMap(new HashMap<>());
    private static volatile boolean shutdownHookRegistered = false;

    private final String testId;
    private final CDPSession cdp;
    private final Page page;

    private UiDevToolsProfiler(String testId, CDPSession cdp, Page page) {
        this.testId = testId;
        this.cdp = cdp;
        this.page = page;
    }

    static boolean isEnabled() {
        return Boolean.getBoolean(PROP_ENABLED);
    }

    static UiDevToolsProfiler startIfEnabled(BrowserContext context, Page page, String testId) {
        if (!isEnabled() || context == null || page == null) {
            return null;
        }
        ensureShutdownHook();
        page.addInitScript(OBSERVER_INIT_SCRIPT);
        CDPSession cdp = context.newCDPSession(page);
        cdp.send("Performance.enable");
        cdp.send("Profiler.enable");
        JsonObject samplingParams = new JsonObject();
        samplingParams.addProperty("interval", 100);
        cdp.send("Profiler.setSamplingInterval", samplingParams);
        cdp.send("Profiler.start");
        return new UiDevToolsProfiler(testId, cdp, page);
    }

    @SuppressWarnings("unchecked")
    void stopAndRecord() {
        try {
            JsonObject stopResult = cdp.send("Profiler.stop");
            JsonObject profile = stopResult != null && stopResult.has("profile")
                    ? stopResult.getAsJsonObject("profile")
                    : stopResult;
            Map<String, Double> metrics = extractMetrics(cdp.send("Performance.getMetrics"));

            int longTaskCount = 0;
            double maxLongTaskMs = 0;
            double cls = 0;
            try {
                Object perfObj = page.evaluate(
                        "() => window.__cdpPerf ? { longTasks: window.__cdpPerf.longTasks, cls: window.__cdpPerf.cls } : null");
                if (perfObj instanceof Map<?, ?> map) {
                    Object ltObj = map.get("longTasks");
                    if (ltObj instanceof List<?> list) {
                        longTaskCount = list.size();
                        for (Object item : list) {
                            if (item instanceof Map<?, ?> m && m.get("durationMs") instanceof Number n) {
                                maxLongTaskMs = Math.max(maxLongTaskMs, n.doubleValue());
                            }
                        }
                    }
                    if (map.get("cls") instanceof Number n) {
                        cls = n.doubleValue();
                    }
                }
            } catch (Exception ignored) {
                // Page may already be closed
            }

            Map<String, FunctionStats> localFns = analyzeProfile(profile);
            synchronized (AGGREGATED_FUNCTIONS) {
                for (Map.Entry<String, FunctionStats> entry : localFns.entrySet()) {
                    AGGREGATED_FUNCTIONS.merge(
                            entry.getKey(),
                            entry.getValue(),
                            (a, b) -> new FunctionStats(a.selfMicros + b.selfMicros, a.hits + b.hits));
                }
            }

            List<Map.Entry<String, FunctionStats>> topLocal = localFns.entrySet().stream()
                    .sorted(Comparator.comparingLong((Map.Entry<String, FunctionStats> e) -> e.getValue().selfMicros)
                            .reversed())
                    .limit(3)
                    .toList();
            String topFnSummary = topLocal.isEmpty()
                    ? "-"
                    : String.join(
                            ", ",
                            topLocal.stream()
                                    .map(e -> String.format(
                                            Locale.ROOT, "%s (%.1fms)", e.getKey(), e.getValue().selfMicros / 1000.0))
                                    .toList());

            RECORDS.add(new TestProfileRecord(
                    testId,
                    metrics.getOrDefault("ScriptDuration", 0.0) * 1000.0,
                    metrics.getOrDefault("LayoutDuration", 0.0) * 1000.0,
                    metrics.getOrDefault("RecalcStyleDuration", 0.0) * 1000.0,
                    metrics.getOrDefault("TaskDuration", 0.0) * 1000.0,
                    metrics.getOrDefault("LayoutCount", 0.0),
                    metrics.getOrDefault("RecalcStyleCount", 0.0),
                    metrics.getOrDefault("Nodes", 0.0),
                    metrics.getOrDefault("JSEventListeners", 0.0),
                    metrics.getOrDefault("JSHeapUsedSize", 0.0) / (1024.0 * 1024.0),
                    longTaskCount,
                    maxLongTaskMs,
                    cls,
                    topFnSummary));

            Path outDir = resolveOutputDir();
            Files.createDirectories(outDir);
            if (profile != null) {
                String safeName = testId.replaceAll("[^a-zA-Z0-9._-]", "_");
                Files.writeString(outDir.resolve(safeName + ".cpuprofile"), profile.toString(), StandardCharsets.UTF_8);
            }
            writeSummaryReport(outDir);
        } catch (Exception e) {
            System.err.println("[UiDevToolsProfiler] Failed to capture profile for " + testId + ": " + e.getMessage());
        } finally {
            try {
                cdp.detach();
            } catch (Exception ignored) {
            }
        }
    }

    private static Map<String, Double> extractMetrics(JsonObject result) {
        Map<String, Double> map = new LinkedHashMap<>();
        if (result != null && result.has("metrics")) {
            for (JsonElement el : result.getAsJsonArray("metrics")) {
                JsonObject obj = el.getAsJsonObject();
                map.put(obj.get("name").getAsString(), obj.get("value").getAsDouble());
            }
        }
        return map;
    }

    static Map<String, FunctionStats> analyzeProfile(JsonObject profile) {
        Map<String, FunctionStats> stats = new HashMap<>();
        if (profile == null || !profile.has("nodes")) {
            return stats;
        }
        Map<Integer, String> nodeLabels = new HashMap<>();
        JsonArray nodes = profile.getAsJsonArray("nodes");
        for (JsonElement el : nodes) {
            JsonObject node = el.getAsJsonObject();
            int id = node.get("id").getAsInt();
            JsonObject frame = node.has("callFrame") ? node.getAsJsonObject("callFrame") : null;
            if (frame == null) {
                continue;
            }
            String fn = frame.has("functionName") ? frame.get("functionName").getAsString() : "";
            String url = frame.has("url") ? frame.get("url").getAsString() : "";
            int line = frame.has("lineNumber") ? frame.get("lineNumber").getAsInt() + 1 : 0;
            if ("(idle)".equals(fn) || "(root)".equals(fn) || "(program)".equals(fn)) {
                continue;
            }
            String shortFile =
                    url.isEmpty() ? "(native)" : (url.contains("/") ? url.substring(url.lastIndexOf('/') + 1) : url);
            String label = (fn.isEmpty() ? "(anonymous)" : fn) + " @ " + shortFile + (line > 0 ? ":" + line : "");
            nodeLabels.put(id, label);
        }

        if (profile.has("samples") && profile.has("timeDeltas")) {
            JsonArray samples = profile.getAsJsonArray("samples");
            JsonArray deltas = profile.getAsJsonArray("timeDeltas");
            int len = Math.min(samples.size(), deltas.size());
            for (int i = 0; i < len; i++) {
                int nodeId = samples.get(i).getAsInt();
                long deltaUs = deltas.get(i).getAsLong();
                String label = nodeLabels.get(nodeId);
                if (label != null) {
                    stats.merge(
                            label,
                            new FunctionStats(deltaUs, 1),
                            (a, b) -> new FunctionStats(a.selfMicros + b.selfMicros, a.hits + b.hits));
                }
            }
        } else {
            for (JsonElement el : nodes) {
                JsonObject node = el.getAsJsonObject();
                int id = node.get("id").getAsInt();
                int hits = node.has("hitCount") ? node.get("hitCount").getAsInt() : 0;
                String label = nodeLabels.get(id);
                if (label != null && hits > 0) {
                    stats.merge(
                            label,
                            new FunctionStats(hits * 100L, hits),
                            (a, b) -> new FunctionStats(a.selfMicros + b.selfMicros, a.hits + b.hits));
                }
            }
        }
        return stats;
    }

    private static Path resolveOutputDir() {
        Path root = Paths.get("").toAbsolutePath();
        return root.endsWith("wiremock-extension-ui")
                ? root.resolve("build/reports/profiles")
                : root.resolve("wiremock-extension-ui/build/reports/profiles");
    }

    private static synchronized void ensureShutdownHook() {
        if (shutdownHookRegistered) {
            return;
        }
        shutdownHookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                writeSummaryReport(resolveOutputDir());
            } catch (Exception ignored) {
            }
        }));
    }

    private static synchronized void writeSummaryReport(Path outDir) throws IOException {
        Files.createDirectories(outDir);
        StringBuilder sb = new StringBuilder();
        sb.append("# Chromium DevTools E2E Performance Profile Summary\n\n");
        sb.append("Captured ").append(RECORDS.size()).append(" Playwright E2E test(s). ");
        sb.append(
                "Individual `.cpuprofile` files are saved in this directory and can be opened directly in Edge/Chrome DevTools.\n\n");

        sb.append("## Top 25 JS Functions by Self-Time (Aggregated Across E2E Tests)\n\n");
        sb.append("| Rank | Function (`name @ file:line`) | Self Time (ms) | Samples |\n");
        sb.append("| :--- | :--- | ---: | ---: |\n");
        List<Map.Entry<String, FunctionStats>> topFns;
        synchronized (AGGREGATED_FUNCTIONS) {
            topFns = AGGREGATED_FUNCTIONS.entrySet().stream()
                    .sorted(Comparator.comparingLong((Map.Entry<String, FunctionStats> e) -> e.getValue().selfMicros)
                            .reversed())
                    .limit(25)
                    .toList();
        }
        int rank = 1;
        for (Map.Entry<String, FunctionStats> e : topFns) {
            sb.append(String.format(
                    Locale.ROOT,
                    "| %d | `%s` | %.2f | %d |\n",
                    rank++,
                    e.getKey(),
                    e.getValue().selfMicros / 1000.0,
                    e.getValue().hits));
        }

        sb.append("\n## Per-Test CDP Metrics (Sorted by Script + Layout + Style Duration)\n\n");
        sb.append(
                "| Test | Script (ms) | Layout (ms) | Style (ms) | Layouts | Styles | Nodes | Listeners | Heap (MB) | LongTasks (max ms) | CLS | Top Hotspots |\n");
        sb.append("| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | :--- |\n");

        List<TestProfileRecord> sorted;
        synchronized (RECORDS) {
            sorted = RECORDS.stream()
                    .sorted(Comparator.comparingDouble((TestProfileRecord r) -> r.scriptMs + r.layoutMs + r.styleMs)
                            .reversed())
                    .toList();
        }
        for (TestProfileRecord r : sorted) {
            sb.append(String.format(
                    Locale.ROOT,
                    "| `%s` | %.1f | %.1f | %.1f | %.0f | %.0f | %.0f | %.0f | %.2f | %d (%.0f) | %.3f | %s |\n",
                    r.testId,
                    r.scriptMs,
                    r.layoutMs,
                    r.styleMs,
                    r.layoutCount,
                    r.styleCount,
                    r.nodes,
                    r.listeners,
                    r.heapMb,
                    r.longTasks,
                    r.maxLongTaskMs,
                    r.cls,
                    r.topHotspots));
        }

        Files.writeString(outDir.resolve("profile-summary.md"), sb.toString(), StandardCharsets.UTF_8);
    }

    record FunctionStats(long selfMicros, int hits) {}

    private record TestProfileRecord(
            String testId,
            double scriptMs,
            double layoutMs,
            double styleMs,
            double taskMs,
            double layoutCount,
            double styleCount,
            double nodes,
            double listeners,
            double heapMb,
            int longTasks,
            double maxLongTaskMs,
            double cls,
            String topHotspots) {}
}
