package com.github.mattiasgreen.wiremock.ui;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.microsoft.playwright.CDPSession;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class UiPerformancePlaywrightTest extends AbstractUiPlaywrightTest {

    private static final String PERF_OBSERVER_INIT_SCRIPT =
            """
            window.__perfRecords = {
                longTasks: []
            };
            if (window.PerformanceObserver) {
                try {
                    const observer = new PerformanceObserver(list => {
                        for (const entry of list.getEntries()) {
                            window.__perfRecords.longTasks.push({
                                durationMs: Math.round(entry.duration),
                                startTimeMs: Math.round(entry.startTime)
                            });
                        }
                    });
                    observer.observe({ entryTypes: ['longtask'] });
                } catch (e) {}
            }
            """;

    @Test
    @DisplayName("PoC: Profile SPA Tab Switching & Navigation for DOM churn, Layout reflows, and Long Tasks")
    void profileTabNavigationPerformance() {
        page.addInitScript(PERF_OBSERVER_INIT_SCRIPT);
        CDPSession cdp = context.newCDPSession(page);
        cdp.send("Performance.enable");

        // 1. Initial page load
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#stubs");
        page.waitForSelector("#nav-tab-stubs.active");
        page.waitForSelector("#stub-list .stub-card");

        // Record initial page boot long tasks
        List<Map<String, Object>> bootLongTasks = getLongTasks();
        System.out.printf("[BOOT PERF] Initial SPA boot long tasks (>50ms): %d%n", bootLongTasks.size());
        for (Map<String, Object> task : bootLongTasks) {
            System.out.printf(
                    "  -> Boot Long Task: %s ms (start: %s ms)%n", task.get("durationMs"), task.get("startTimeMs"));
        }
        // Reset long tasks for interactive phase
        page.evaluate("() => { if (window.__perfRecords) window.__perfRecords.longTasks = []; }");

        Map<String, Double> initialMetrics = getCdpMetrics(cdp);

        // 2. Start V8 Profiler
        cdp.send("Profiler.enable");
        cdp.send("Profiler.start");

        // 3. Rapid tab cycling across all panels
        String[] tabs = {
            "#nav-tab-journal",
            "#nav-tab-tester",
            "#nav-tab-casual",
            "#nav-tab-scenarios",
            "#nav-tab-netem",
            "#nav-tab-stubs"
        };
        for (int cycle = 0; cycle < 3; cycle++) {
            for (String tabId : tabs) {
                page.locator(tabId).click();
                page.waitForTimeout(50);
            }
        }

        // 4. Stop V8 Profiler & gather metrics
        JsonObject cpuProfile = cdp.send("Profiler.stop");
        Map<String, Double> afterMetrics = getCdpMetrics(cdp);

        // 5. Gather long tasks
        List<Map<String, Object>> longTasks = getLongTasks();

        // 6. Log Performance Report
        printPerfReport(
                "Tab Navigation & Cycling (3 full cycles)", initialMetrics, afterMetrics, longTasks, cpuProfile);

        // Performance Assertions
        double deltaNodes = afterMetrics.getOrDefault("Nodes", 0.0) - initialMetrics.getOrDefault("Nodes", 0.0);
        double deltaLayouts =
                afterMetrics.getOrDefault("LayoutCount", 0.0) - initialMetrics.getOrDefault("LayoutCount", 0.0);
        double jsHeapUsedMb = afterMetrics.getOrDefault("JSHeapUsedSize", 0.0) / (1024 * 1024);

        System.out.printf(
                "[PERF STATS] Active DOM Nodes: %.0f (delta: +%.0f)%n", afterMetrics.get("Nodes"), deltaNodes);
        System.out.printf(
                "[PERF STATS] Total Layout Reflows: %.0f (delta: +%.0f)%n",
                afterMetrics.get("LayoutCount"), deltaLayouts);
        System.out.printf("[PERF STATS] JS Heap Used: %.2f MB%n", jsHeapUsedMb);
        System.out.printf("[PERF STATS] Long Tasks (>50ms): %d%n", longTasks.size());

        assertThat(longTasks).isEmpty();
        assertThat(jsHeapUsedMb).isLessThan(50.0);
    }

    @Test
    @DisplayName("PoC: Profile Payload Editor & Viewers (Formatted, Raw, Hex) under Large Payloads")
    void profilePayloadEditorAndViewerPerformance() {
        page.addInitScript(PERF_OBSERVER_INIT_SCRIPT);
        CDPSession cdp = context.newCDPSession(page);
        cdp.send("Performance.enable");

        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/#tester");
        page.waitForSelector("#tab-tester.active");

        // Reset long tasks for editor phase
        page.evaluate("() => { if (window.__perfRecords) window.__perfRecords.longTasks = []; }");

        // Generate ~30KB nested JSON payload
        StringBuilder sb = new StringBuilder("{\n  \"users\": [\n");
        for (int i = 0; i < 200; i++) {
            sb.append(String.format(
                    "    {\"id\": %d, \"name\": \"User_%d\", \"email\": \"user%d@example.com\", \"roles\": [\"admin\", \"user\"], \"active\": true}%s\n",
                    i, i, i, (i < 199 ? "," : "")));
        }
        sb.append("  ]\n}");
        String largeJson = sb.toString();

        Map<String, Double> baseline = getCdpMetrics(cdp);

        cdp.send("Profiler.enable");
        cdp.send("Profiler.start");

        // 1. Fill payload
        page.locator("#tester-method").selectOption("POST");
        page.locator("#tester-body").fill(largeJson);

        // 2. Format JSON
        page.locator("#btn-tester-format-json").click();

        // 3. Mock response
        wireMockServer.stubFor(any(urlEqualTo("/perf-test"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(largeJson)));

        page.locator("#tester-url").fill("/perf-test");

        // Clear long tasks before sending and viewing response to isolate viewer performance
        page.evaluate("() => { if (window.__perfRecords) window.__perfRecords.longTasks = []; }");

        page.locator("#btn-tester-send").click();
        page.waitForSelector("#tester-response-status:has-text('200')");

        // 4. Toggle view modes: Raw, Highlighted
        page.locator("#btn-view-raw").click();
        page.locator("#btn-view-highlighted").click();

        // 5. Toggle Inspector tabs: Response, Sent Request, Both
        page.locator("#btn-tester-tab-request").click();
        page.locator("#btn-tester-tab-both").click();
        page.locator("#btn-tester-tab-response").click();

        JsonObject cpuProfile = cdp.send("Profiler.stop");
        Map<String, Double> postMetrics = getCdpMetrics(cdp);
        List<Map<String, Object>> longTasks = getLongTasks();

        printPerfReport("Editor & Viewer under 30KB Payload", baseline, postMetrics, longTasks, cpuProfile);

        double deltaLayouts = postMetrics.getOrDefault("LayoutCount", 0.0) - baseline.getOrDefault("LayoutCount", 0.0);
        double layoutDurationSec =
                postMetrics.getOrDefault("LayoutDuration", 0.0) - baseline.getOrDefault("LayoutDuration", 0.0);
        double styleRecalcDurationSec = postMetrics.getOrDefault("RecalcStyleDuration", 0.0)
                - baseline.getOrDefault("RecalcStyleDuration", 0.0);

        System.out.printf("[PERF STATS] Total Reflows during Editor/Viewer ops: %.0f%n", deltaLayouts);
        System.out.printf("[PERF STATS] Time in Layout: %.2f ms%n", layoutDurationSec * 1000);
        System.out.printf("[PERF STATS] Time in Style Recalc: %.2f ms%n", styleRecalcDurationSec * 1000);
        System.out.printf("[PERF STATS] Long Tasks (>50ms): %d%n", longTasks.size());

        // Enforce hard performance budget in outer loop quality gate
        double finalNodes = postMetrics.getOrDefault("Nodes", 0.0);
        assertThat(finalNodes)
                .as("DOM node budget: must remain under 10,000 nodes even under large payloads")
                .isLessThan(10000.0);
        assertThat(longTasks)
                .as("Zero Long Tasks (>50ms) during response rendering and tab toggling")
                .isEmpty();
        assertThat(layoutDurationSec)
                .as("Cumulative layout duration must remain under 1.0 second")
                .isLessThan(1.0);
    }

    private Map<String, Double> getCdpMetrics(CDPSession cdp) {
        JsonObject result = cdp.send("Performance.getMetrics");
        Map<String, Double> map = new LinkedHashMap<>();
        if (result.has("metrics")) {
            for (JsonElement el : result.getAsJsonArray("metrics")) {
                JsonObject obj = el.getAsJsonObject();
                map.put(obj.get("name").getAsString(), obj.get("value").getAsDouble());
            }
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getLongTasks() {
        Object val = page.evaluate(
                "() => (window.__perfRecords && window.__perfRecords.longTasks) ? window.__perfRecords.longTasks : []");
        if (val instanceof List) {
            return (List<Map<String, Object>>) val;
        }
        return Collections.emptyList();
    }

    private void printPerfReport(
            String testName,
            Map<String, Double> before,
            Map<String, Double> after,
            List<Map<String, Object>> longTasks,
            JsonObject cpuProfile) {
        System.out.println("========================================================================");
        System.out.println("PERFORMANCE PROFILE REPORT: " + testName);
        System.out.println("========================================================================");
        System.out.printf("%-30s %15s %15s %15s%n", "Metric", "Before", "After", "Delta");
        System.out.println("------------------------------------------------------------------------");

        String[] trackedMetrics = {
            "Nodes",
            "Documents",
            "JSEventListeners",
            "LayoutCount",
            "RecalcStyleCount",
            "LayoutDuration",
            "RecalcStyleDuration",
            "ScriptDuration",
            "TaskDuration",
            "JSHeapUsedSize",
            "JSHeapTotalSize"
        };

        for (String m : trackedMetrics) {
            double b = before.getOrDefault(m, 0.0);
            double a = after.getOrDefault(m, 0.0);
            double delta = a - b;
            if (m.contains("Size")) {
                System.out.printf(
                        "%-30s %12.2f MB %12.2f MB %+12.2f MB%n",
                        m, b / (1024 * 1024), a / (1024 * 1024), delta / (1024 * 1024));
            } else if (m.contains("Duration")) {
                System.out.printf("%-30s %12.2f ms %12.2f ms %+12.2f ms%n", m, b * 1000, a * 1000, delta * 1000);
            } else {
                System.out.printf("%-30s %15.0f %15.0f %+15.0f%n", m, b, a, delta);
            }
        }

        System.out.println("------------------------------------------------------------------------");
        System.out.println("LONG TASKS (> 50ms): " + longTasks.size());
        for (Map<String, Object> task : longTasks) {
            System.out.printf("  -> Duration: %s ms (start: %s ms)%n", task.get("durationMs"), task.get("startTimeMs"));
        }

        // Extract top functions from CPU profile
        if (cpuProfile != null && cpuProfile.has("nodes")) {
            System.out.println("------------------------------------------------------------------------");
            System.out.println("TOP CPU FUNCTIONS (Samples Count):");
            Map<String, Integer> functionHitCounts = new HashMap<>();
            JsonArray nodes = cpuProfile.getAsJsonArray("nodes");
            for (JsonElement nodeEl : nodes) {
                JsonObject node = nodeEl.getAsJsonObject();
                if (node.has("callFrame")) {
                    JsonObject frame = node.getAsJsonObject("callFrame");
                    String fn = frame.get("functionName").getAsString();
                    String url = frame.get("url").getAsString();
                    if (!fn.isEmpty() && !fn.equals("(root)") && !fn.equals("(program)")) {
                        int hitCount =
                                node.has("hitCount") ? node.get("hitCount").getAsInt() : 0;
                        if (hitCount > 0) {
                            String key = fn + " (" + (url.contains("/") ? url.substring(url.lastIndexOf('/') + 1) : url)
                                    + ")";
                            functionHitCounts.merge(key, hitCount, Integer::sum);
                        }
                    }
                }
            }

            functionHitCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(8)
                    .forEach(e -> System.out.printf("  %-45s : %d hits%n", e.getKey(), e.getValue()));
        }
        System.out.println("========================================================================\n");
    }
}
