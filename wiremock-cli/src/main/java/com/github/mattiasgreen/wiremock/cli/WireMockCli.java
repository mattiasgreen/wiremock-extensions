package com.github.mattiasgreen.wiremock.cli;

import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Lightweight, zero-compile CLI for WireMock Standalone &amp; Extensions suite.
 *
 * <p>Can be run directly via JDK 21 JEP 330:
 * <pre>{@code
 * java WireMockCli.java [command] [options]
 * }</pre>
 * or via the companion shell wrapper {@code bin/wm}.
 */
public final class WireMockCli {

    public static final String DEFAULT_SERVER_URL = "http://localhost:8080";
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(ZoneId.systemDefault());

    public static void main(String[] args) {
        int exitCode = run(args, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || isHelpArg(args[0])) {
            printUsage(out);
            return 0;
        }

        String command = args[0].toLowerCase(Locale.ROOT);
        String serverUrl = resolveServerUrl(args);
        boolean noColor = hasFlag(args, "--no-color") || System.getenv("NO_COLOR") != null;
        ConsoleFormatter fmt = new ConsoleFormatter(!noColor);

        HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        try {
            return switch (command) {
                case "info", "status" -> executeInfo(client, serverUrl, fmt, out, err);
                case "stubs", "list", "ls" -> executeStubs(client, serverUrl, args, fmt, out, err);
                case "tail", "traffic", "journal" -> executeTail(client, serverUrl, args, fmt, out, err);
                case "toxics", "chaos", "netem" -> executeToxics(client, serverUrl, args, fmt, out, err);
                case "proxy", "ui" -> executeProxy(stripFirstArg(args), out, err);
                case "--version", "-v" -> {
                    out.println("wiremock-cli v0.1.0 (JDK 21)");
                    yield 0;
                }
                default -> {
                    err.println("Unknown command: '" + sanitize(command) + "'. Run 'wm --help' for usage.");
                    yield 1;
                }
            };
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.println("\nOperation cancelled.");
            return 130;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? sanitize(e.getMessage()) : "Unknown error";
            err.println("Error: " + msg);
            return 1;
        }
    }

    // =========================================================================
    // Subcommand: info
    // =========================================================================

    private static int executeInfo(
            HttpClient client, String serverUrl, ConsoleFormatter fmt, PrintStream out, PrintStream err)
            throws Exception {
        out.println(fmt.bold("WireMock Instance Probe: ") + serverUrl);
        out.println();

        // 1. Probe core server
        HttpResponse<String> coreResp = sendGet(client, serverUrl, "/__admin/mappings");
        if (coreResp.statusCode() != 200) {
            err.println(fmt.red(
                    "✗ Server unreachable or not a valid WireMock instance (status: " + coreResp.statusCode() + ")"));
            return 1;
        }

        out.println(fmt.bold("Core Capabilities:"));
        out.println("  " + fmt.green("✓") + " Stubs Catalog        " + fmt.dim("/__admin/mappings"));
        out.println("  " + fmt.green("✓") + " Request Journal      " + fmt.dim("/__admin/requests"));
        out.println("  " + fmt.green("✓") + " FSM Scenarios        " + fmt.dim("/__admin/scenarios"));
        out.println();

        // 2. Probe extension endpoints
        out.println(fmt.bold("Extensions Suite Matrix:"));
        probeExtension(client, serverUrl, "tcp-netem", "/__admin/tcp-netem/proxies", fmt, out);
        probeExtension(client, serverUrl, "stateful", "/__admin/stateful/models", fmt, out);
        probeExtension(client, serverUrl, "otel metrics", "/__admin/metrics/prometheus", fmt, out);
        probeExtension(client, serverUrl, "console ui", "/__admin/ui/", fmt, out);
        out.println();

        return 0;
    }

    private static void probeExtension(
            HttpClient client, String serverUrl, String name, String path, ConsoleFormatter fmt, PrintStream out) {
        try {
            HttpResponse<String> resp = sendGet(client, serverUrl, path);
            boolean available = resp.statusCode() == 200 || resp.statusCode() == 302;
            if (available) {
                out.printf("  %s %-20s %s%n", fmt.green("✓"), name, fmt.dim(path));
            } else {
                out.printf("  %s %-20s %s%n", fmt.dim("✗"), name, fmt.dim("(Not installed - returned 404)"));
            }
        } catch (Exception e) {
            out.printf("  %s %-20s %s%n", fmt.dim("✗"), name, fmt.dim("(Probe error)"));
        }
    }

    // =========================================================================
    // Subcommand: stubs
    // =========================================================================

    @SuppressWarnings("unchecked")
    private static int executeStubs(
            HttpClient client, String serverUrl, String[] args, ConsoleFormatter fmt, PrintStream out, PrintStream err)
            throws Exception {
        boolean jsonOutput = hasFlag(args, "--json");
        String filterExpr = extractFilterExpression(args, "stubs", "list", "ls");

        HttpResponse<String> resp = sendGet(client, serverUrl, "/__admin/mappings");
        if (resp.statusCode() != 200) {
            err.println(fmt.red("Failed to fetch stubs from " + serverUrl + " (HTTP " + resp.statusCode() + ")"));
            return 1;
        }

        Object parsed = MiniJson.parse(resp.body());
        List<Map<String, Object>> stubs = new ArrayList<>();

        if (parsed instanceof Map<?, ?> map) {
            Object mappingsObj = map.get("mappings");
            if (mappingsObj instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> itemMap) {
                        stubs.add((Map<String, Object>) itemMap);
                    }
                }
            }
        }

        // Also probe disabled stubs extension if available
        try {
            HttpResponse<String> disabledResp = sendGet(client, serverUrl, "/__admin/stubs/disabled");
            if (disabledResp.statusCode() == 200) {
                Object disabledParsed = MiniJson.parse(disabledResp.body());
                if (disabledParsed instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> itemMap) {
                            Map<String, Object> copy = new HashMap<>((Map<String, Object>) itemMap);
                            copy.put("_disabled", Boolean.TRUE);
                            stubs.add(copy);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Disabled stubs endpoint is optional
        }

        // Filter stubs
        List<Map<String, Object>> matched = new ArrayList<>();
        for (Map<String, Object> stub : stubs) {
            if (filterExpr.isBlank() || QueryEvaluator.matchesStub(filterExpr, stub)) {
                matched.add(stub);
            }
        }

        if (jsonOutput) {
            out.println(MiniJson.toJson(matched));
            return 0;
        }

        if (matched.isEmpty()) {
            if (stubs.isEmpty()) {
                out.println(fmt.dim("No stubs registered at " + serverUrl));
            } else {
                out.println(fmt.dim(
                        "No stubs matched query: '" + filterExpr + "' (total registered: " + stubs.size() + ")"));
            }
            return 0;
        }

        // Print table header
        out.println(fmt.bold(
                String.format("%-10s %-7s %-6s %-40s %s", "STATUS", "METHOD", "RESP", "URL / PATTERN", "NAME / ID")));
        out.println(fmt.dim("─".repeat(85)));

        for (Map<String, Object> stub : matched) {
            boolean isDisabled = Boolean.TRUE.equals(stub.get("_disabled"));
            String statusBadge = isDisabled ? fmt.dim("[DISABLED]") : fmt.green("[ACTIVE]  ");

            Map<String, Object> req = (Map<String, Object>) stub.get("request");
            String method =
                    req != null && req.get("method") != null ? req.get("method").toString() : "ANY";
            String url = extractStubUrl(req);

            Map<String, Object> res = (Map<String, Object>) stub.get("response");
            String respStatus =
                    res != null && res.get("status") != null ? res.get("status").toString() : "200";

            String name = stub.get("name") != null
                    ? stub.get("name").toString()
                    : (stub.get("id") != null ? stub.get("id").toString().substring(0, 8) + "..." : "-");

            out.println(String.format(
                    "%s %s %-6s %-40s %s",
                    statusBadge,
                    fmt.methodBadge(method),
                    fmt.statusBadge(respStatus),
                    truncate(url, 40),
                    fmt.dim(truncate(name, 30))));
        }

        out.println();
        out.println(fmt.dim("Showing " + matched.size() + " of " + stubs.size() + " stubs."));
        return 0;
    }

    private static String extractStubUrl(Map<String, Object> req) {
        if (req == null) return "/";
        if (req.get("url") != null) return req.get("url").toString();
        if (req.get("urlPattern") != null) return req.get("urlPattern").toString();
        if (req.get("urlPath") != null) return req.get("urlPath").toString();
        if (req.get("urlPathPattern") != null) return req.get("urlPathPattern").toString();
        return "/";
    }

    // =========================================================================
    // Subcommand: tail
    // =========================================================================

    @SuppressWarnings("unchecked")
    private static int executeTail(
            HttpClient client, String serverUrl, String[] args, ConsoleFormatter fmt, PrintStream out, PrintStream err)
            throws Exception {
        boolean follow = hasFlag(args, "-f", "--follow");
        boolean unmatchedOnly = hasFlag(args, "-u", "--unmatched");
        boolean showDiff = hasFlag(args, "--diff");
        int limit = parseIntOption(args, "-n", "--limit", follow ? 10 : 20);
        long intervalMs = parseLongOption(args, "--interval", 1000L);
        String filterExpr = extractFilterExpression(args, "tail", "traffic", "journal");

        Set<String> seenIds = new HashSet<>();

        if (follow) {
            out.println(fmt.bold("Streaming traffic from: ") + serverUrl + fmt.dim(" (Ctrl+C to stop)"));
            if (unmatchedOnly) {
                out.println(fmt.yellow("Filtering: Unmatched (404) requests only"));
            }
            if (!filterExpr.isBlank()) {
                out.println(fmt.cyan("Filter: " + filterExpr));
            }
            out.println();
        }

        do {
            HttpResponse<String> resp = sendGet(client, serverUrl, "/__admin/requests?limit=" + limit);
            if (resp.statusCode() != 200) {
                err.println(fmt.red("Failed to fetch journal: HTTP " + resp.statusCode()));
                if (!follow) return 1;
            } else {
                Object parsed = MiniJson.parse(resp.body());
                List<Map<String, Object>> requests = new ArrayList<>();
                if (parsed instanceof Map<?, ?> map && map.get("requests") instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> itemMap) {
                            requests.add((Map<String, Object>) itemMap);
                        }
                    }
                }

                // Reverse so oldest arrivals print first
                Collections.reverse(requests);

                for (Map<String, Object> reqEntry : requests) {
                    String id = reqEntry.get("id") != null ? reqEntry.get("id").toString() : "";
                    if (follow && seenIds.contains(id)) {
                        continue;
                    }
                    seenIds.add(id);

                    boolean wasMatched = !Boolean.FALSE.equals(reqEntry.get("wasMatched"));
                    if (unmatchedOnly && wasMatched) {
                        continue;
                    }

                    if (!filterExpr.isBlank() && !QueryEvaluator.matchesRequest(filterExpr, reqEntry)) {
                        continue;
                    }

                    renderJournalEntry(reqEntry, wasMatched, showDiff, fmt, out);
                }
            }

            if (follow) {
                Thread.sleep(intervalMs);
            }
        } while (follow);

        return 0;
    }

    @SuppressWarnings("unchecked")
    private static void renderJournalEntry(
            Map<String, Object> entry, boolean wasMatched, boolean showDiff, ConsoleFormatter fmt, PrintStream out) {
        String timestamp = "";
        Object loggedDate = entry.get("request");
        if (loggedDate instanceof Map<?, ?> reqMap && reqMap.get("loggedDate") != null) {
            try {
                long epoch = Long.parseLong(reqMap.get("loggedDate").toString());
                timestamp = TIME_FORMATTER.format(Instant.ofEpochMilli(epoch));
            } catch (NumberFormatException ignored) {
                // Ignore timestamp parse errors
            }
        }
        if (timestamp.isEmpty()) {
            timestamp = TIME_FORMATTER.format(Instant.now());
        }

        Map<String, Object> req = (Map<String, Object>) entry.get("request");
        String method =
                req != null && req.get("method") != null ? req.get("method").toString() : "GET";
        String url = req != null && req.get("url") != null ? req.get("url").toString() : "/";

        Map<String, Object> res = (Map<String, Object>) entry.get("response");
        String status =
                res != null && res.get("status") != null ? res.get("status").toString() : "404";

        Map<String, Object> timing = (Map<String, Object>) entry.get("timing");
        String latency = timing != null && timing.get("totalTime") != null ? timing.get("totalTime") + "ms" : "-";

        String matchBadge = wasMatched ? "" : "  " + fmt.red(fmt.bold("⚠️ UNMATCHED"));

        out.printf(
                "[%s] %s %-6s %6s  %-35s%s%n",
                fmt.dim(timestamp),
                fmt.methodBadge(method),
                fmt.statusBadge(status),
                fmt.dim(latency),
                url,
                matchBadge);

        if (!wasMatched && showDiff) {
            out.println(fmt.dim("       └─ Nearest stub match candidate / diff available in Web UI"));
        }
    }

    // =========================================================================
    // Subcommand: toxics
    // =========================================================================

    @SuppressWarnings("unchecked")
    private static int executeToxics(
            HttpClient client, String serverUrl, String[] args, ConsoleFormatter fmt, PrintStream out, PrintStream err)
            throws Exception {
        boolean jsonOutput = hasFlag(args, "--json");

        HttpResponse<String> resp = sendGet(client, serverUrl, "/__admin/tcp-netem/proxies");
        if (resp.statusCode() == 404) {
            out.println(fmt.yellow("Notice: ")
                    + "wiremock-extension-tcp-netem is not installed on "
                    + serverUrl
                    + fmt.dim(" (/__admin/tcp-netem/proxies returned 404)."));
            return 0;
        }

        if (resp.statusCode() != 200) {
            err.println(fmt.red("Failed to fetch NetEm toxics: HTTP " + resp.statusCode()));
            return 1;
        }

        if (jsonOutput) {
            out.println(resp.body());
            return 0;
        }

        Object parsed = MiniJson.parse(resp.body());
        List<Map<String, Object>> proxies = new ArrayList<>();
        if (parsed instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> itemMap) {
                    proxies.add((Map<String, Object>) itemMap);
                }
            }
        } else if (parsed instanceof Map<?, ?> map && map.get("proxies") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> itemMap) {
                    proxies.add((Map<String, Object>) itemMap);
                }
            }
        }

        if (proxies.isEmpty()) {
            out.println(fmt.dim("No NetEm proxies configured at " + serverUrl));
            return 0;
        }

        for (Map<String, Object> proxy : proxies) {
            String name = proxy.get("name") != null ? proxy.get("name").toString() : "default";
            Object listen = proxy.get("listenPort");
            Object target = proxy.get("targetPort");

            out.printf(
                    "%s %s %s%n",
                    fmt.bold("Proxy:"),
                    fmt.cyan(name),
                    fmt.dim(String.format("[Listen: %s -> Target: %s]", listen, target)));

            List<Map<String, Object>> toxics = new ArrayList<>();
            if (proxy.get("toxics") instanceof List<?> tList) {
                for (Object t : tList) {
                    if (t instanceof Map<?, ?> tMap) {
                        toxics.add((Map<String, Object>) tMap);
                    }
                }
            }

            if (toxics.isEmpty()) {
                out.println("  " + fmt.dim("No active toxics (normal traffic flow)."));
            } else {
                out.println(fmt.bold(String.format("  %-12s %-16s %-12s %s", "TYPE", "NAME", "STREAM", "ATTRIBUTES")));
                for (Map<String, Object> toxic : toxics) {
                    String type = toxic.get("type") != null ? toxic.get("type").toString() : "-";
                    String tName = toxic.get("name") != null ? toxic.get("name").toString() : "-";
                    String stream =
                            toxic.get("stream") != null ? toxic.get("stream").toString() : "DOWNSTREAM";
                    Object attrs = toxic.get("attributes");
                    String attrStr = attrs != null ? MiniJson.toJson(attrs) : "-";

                    out.printf("  %-12s %-16s %-12s %s%n", fmt.yellow(type), tName, fmt.dim(stream), attrStr);
                }
            }
            out.println();
        }

        return 0;
    }

    // =========================================================================
    // Subcommand: proxy
    // =========================================================================

    private static int executeProxy(String[] proxyArgs, PrintStream out, PrintStream err) {
        // 1. Try invoking directly via classpath reflection if bundled
        try {
            Class<?> proxyClass = Class.forName("com.github.mattiasgreen.wiremock.ui.WireMockConsoleProxy");
            Method mainMethod = proxyClass.getMethod("main", String[].class);
            mainMethod.invoke(null, (Object) proxyArgs);
            return 0;
        } catch (ClassNotFoundException e) {
            // 2. Try launching WireMockConsoleProxy.java via ProcessBuilder (JEP 330 mode)
            try {
                List<String> cmd = new ArrayList<>();
                cmd.add("java");

                File candidate1 = new File("wiremock-cli/WireMockConsoleProxy.java");
                File candidate2 = new File("WireMockConsoleProxy.java");
                File candidate3 = new File("../wiremock-cli/WireMockConsoleProxy.java");

                if (candidate1.isFile()) {
                    cmd.add(candidate1.getPath());
                } else if (candidate2.isFile()) {
                    cmd.add(candidate2.getPath());
                } else if (candidate3.isFile()) {
                    cmd.add(candidate3.getPath());
                } else {
                    err.println("Could not locate WireMockConsoleProxy.java. Ensure it exists in wiremock-cli/.");
                    return 1;
                }

                Collections.addAll(cmd, proxyArgs);
                Process proc = new ProcessBuilder(cmd).inheritIO().start();
                return proc.waitFor();
            } catch (Exception ex) {
                err.println("Failed to start WireMockConsoleProxy: " + sanitize(ex.getMessage()));
                return 1;
            }
        } catch (Exception ex) {
            err.println("Failed to invoke WireMockConsoleProxy: " + sanitize(ex.getMessage()));
            return 1;
        }
    }

    // =========================================================================
    // QueryEvaluator (Supports AND / OR / Predicates)
    // =========================================================================

    public static final class QueryEvaluator {

        @SuppressWarnings("unchecked")
        public static boolean matchesStub(String query, Map<String, Object> stub) {
            if (query == null || query.isBlank()) return true;

            Map<String, Object> req = (Map<String, Object>) stub.get("request");
            String method =
                    req != null && req.get("method") != null ? req.get("method").toString() : "ANY";
            String url = extractStubUrl(req);
            String name = stub.get("name") != null ? stub.get("name").toString() : "";
            boolean isDisabled = Boolean.TRUE.equals(stub.get("_disabled"));

            Map<String, Object> res = (Map<String, Object>) stub.get("response");
            String status =
                    res != null && res.get("status") != null ? res.get("status").toString() : "200";

            return evaluateExpression(query, method, url, name, status, isDisabled);
        }

        @SuppressWarnings("unchecked")
        public static boolean matchesRequest(String query, Map<String, Object> reqEntry) {
            if (query == null || query.isBlank()) return true;

            Map<String, Object> req = (Map<String, Object>) reqEntry.get("request");
            String method =
                    req != null && req.get("method") != null ? req.get("method").toString() : "GET";
            String url = req != null && req.get("url") != null ? req.get("url").toString() : "/";

            Map<String, Object> res = (Map<String, Object>) reqEntry.get("response");
            String status =
                    res != null && res.get("status") != null ? res.get("status").toString() : "200";

            return evaluateExpression(query, method, url, "", status, false);
        }

        static boolean evaluateExpression(
                String expr, String method, String url, String name, String status, boolean isDisabled) {
            String clean = expr.trim();
            // Handle OR expressions
            String[] orParts = clean.split("(?i)\\s+(or|\\|\\|)\\s+");
            if (orParts.length > 1) {
                for (String part : orParts) {
                    if (evaluateExpression(part, method, url, name, status, isDisabled)) {
                        return true;
                    }
                }
                return false;
            }

            // Handle AND expressions (explicit 'and', '&&', or space-delimited terms)
            String[] andParts = clean.split("(?i)\\s+(and|&&)\\s+");
            for (String part : andParts) {
                if (!evaluateSingleTerm(part.trim(), method, url, name, status, isDisabled)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean evaluateSingleTerm(
                String term, String method, String url, String name, String status, boolean isDisabled) {
            if (term.isEmpty()) return true;

            // Handle NOT
            if (term.toLowerCase(Locale.ROOT).startsWith("not ")) {
                return !evaluateSingleTerm(term.substring(4).trim(), method, url, name, status, isDisabled);
            }
            if (term.startsWith("!")) {
                return !evaluateSingleTerm(term.substring(1).trim(), method, url, name, status, isDisabled);
            }

            // Key-value matcher (method=GET, status=200, status=disabled, path=/api)
            if (term.contains("=") || term.contains(":")) {
                String[] kv = term.split("[=:]", 2);
                String key = kv[0].trim().toLowerCase(Locale.ROOT);
                String val = kv[1].trim();

                return switch (key) {
                    case "method" -> method.equalsIgnoreCase(val);
                    case "status" -> {
                        if ("disabled".equalsIgnoreCase(val)) yield isDisabled;
                        if ("active".equalsIgnoreCase(val)) yield !isDisabled;
                        yield status.equalsIgnoreCase(val);
                    }
                    case "path", "url" -> url.toLowerCase(Locale.ROOT).contains(val.toLowerCase(Locale.ROOT));
                    case "name" -> name.toLowerCase(Locale.ROOT).contains(val.toLowerCase(Locale.ROOT));
                    default -> false;
                };
            }

            // Regex matcher (path~/regex/)
            if (term.contains("~")) {
                String[] kv = term.split("~", 2);
                try {
                    Pattern p = Pattern.compile(kv[1].trim(), Pattern.CASE_INSENSITIVE);
                    return p.matcher(url).find();
                } catch (Exception e) {
                    return false;
                }
            }

            // Standard HTTP method token (GET, POST, etc.)
            Set<String> methods = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD");
            if (methods.contains(term.toUpperCase(Locale.ROOT))) {
                return method.equalsIgnoreCase(term);
            }

            // Lifecycle status tokens
            if ("disabled".equalsIgnoreCase(term)) return isDisabled;
            if ("active".equalsIgnoreCase(term)) return !isDisabled;

            // Numeric status token (200, 404, etc.)
            if (term.matches("^\\d{3}$")) {
                return status.equals(term);
            }

            // Default: case-insensitive substring search on url, name, or method
            String lower = term.toLowerCase(Locale.ROOT);
            return url.toLowerCase(Locale.ROOT).contains(lower)
                    || name.toLowerCase(Locale.ROOT).contains(lower)
                    || method.toLowerCase(Locale.ROOT).contains(lower);
        }
    }

    // =========================================================================
    // MiniJson (Zero-Dependency JSON Scanner & AST)
    // =========================================================================

    public static final class MiniJson {
        private final String src;
        private int pos = 0;
        private final int len;

        private MiniJson(String src) {
            this.src = src;
            this.len = src.length();
        }

        public static Object parse(String json) {
            if (json == null || json.isBlank()) return null;
            MiniJson parser = new MiniJson(json);
            parser.skipWhitespace();
            return parser.parseValue();
        }

        private Object parseValue() {
            skipWhitespace();
            if (pos >= len) return null;
            char c = src.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') return parseNull();
            if (c == '-' || Character.isDigit(c)) return parseNumber();
            throw new IllegalArgumentException("Unexpected JSON char '" + c + "' at pos " + pos);
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // consume '{'
            skipWhitespace();
            if (pos < len && src.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (pos < len) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                if (pos < len && src.charAt(pos) == ':') {
                    pos++;
                }
                Object val = parseValue();
                map.put(key, val);
                skipWhitespace();
                if (pos < len && src.charAt(pos) == ',') {
                    pos++;
                } else if (pos < len && src.charAt(pos) == '}') {
                    pos++;
                    break;
                }
            }
            return map;
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++; // consume '['
            skipWhitespace();
            if (pos < len && src.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (pos < len) {
                list.add(parseValue());
                skipWhitespace();
                if (pos < len && src.charAt(pos) == ',') {
                    pos++;
                } else if (pos < len && src.charAt(pos) == ']') {
                    pos++;
                    break;
                }
            }
            return list;
        }

        private String parseString() {
            pos++; // consume initial '"'
            StringBuilder sb = new StringBuilder();
            while (pos < len) {
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= len) break;
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 <= len) {
                                String hex = src.substring(pos, pos + 4);
                                sb.append((char) Integer.parseInt(hex, 16));
                                pos += 4;
                            }
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Boolean parseBoolean() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Expected boolean at pos " + pos);
        }

        private Object parseNull() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("Expected null at pos " + pos);
        }

        private Number parseNumber() {
            int start = pos;
            if (src.charAt(pos) == '-') pos++;
            while (pos < len
                    && (Character.isDigit(src.charAt(pos))
                            || src.charAt(pos) == '.'
                            || src.charAt(pos) == 'e'
                            || src.charAt(pos) == 'E'
                            || src.charAt(pos) == '+'
                            || src.charAt(pos) == '-')) {
                pos++;
            }
            String numStr = src.substring(start, pos);
            if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
                return Double.parseDouble(numStr);
            }
            return Long.parseLong(numStr);
        }

        private void skipWhitespace() {
            while (pos < len) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        @SuppressWarnings("unchecked")
        public static String toJson(Object obj) {
            if (obj == null) return "null";
            if (obj instanceof String s) return "\"" + escapeJson(s) + "\"";
            if (obj instanceof Number || obj instanceof Boolean) return obj.toString();
            if (obj instanceof Map<?, ?> map) {
                StringBuilder sb = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) sb.append(", ");
                    sb.append("\"")
                            .append(escapeJson(entry.getKey().toString()))
                            .append("\": ");
                    sb.append(toJson(entry.getValue()));
                    first = false;
                }
                sb.append("}");
                return sb.toString();
            }
            if (obj instanceof List<?> list) {
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (Object item : list) {
                    if (!first) sb.append(", ");
                    sb.append(toJson(item));
                    first = false;
                }
                sb.append("]");
                return sb.toString();
            }
            return "\"" + escapeJson(obj.toString()) + "\"";
        }

        private static String escapeJson(String s) {
            return s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\b", "\\b")
                    .replace("\f", "\\f")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }
    }

    // =========================================================================
    // ConsoleFormatter (Colors, Badges, Tables)
    // =========================================================================

    public static final class ConsoleFormatter {
        private final boolean colorsEnabled;

        public ConsoleFormatter(boolean colorsEnabled) {
            this.colorsEnabled = colorsEnabled;
        }

        public String color(String code, String text) {
            return colorsEnabled ? code + text + "\u001B[0m" : text;
        }

        public String bold(String text) {
            return color("\u001B[1m", text);
        }

        public String dim(String text) {
            return color("\u001B[2m", text);
        }

        public String green(String text) {
            return color("\u001B[32m", text);
        }

        public String red(String text) {
            return color("\u001B[31m", text);
        }

        public String yellow(String text) {
            return color("\u001B[33m", text);
        }

        public String cyan(String text) {
            return color("\u001B[36m", text);
        }

        public String magenta(String text) {
            return color("\u001B[35m", text);
        }

        public String methodBadge(String method) {
            String m = String.format("%-7s", method.toUpperCase(Locale.ROOT));
            return switch (method.toUpperCase(Locale.ROOT)) {
                case "GET" -> cyan(m);
                case "POST" -> green(m);
                case "PUT" -> yellow(m);
                case "DELETE" -> red(m);
                case "PATCH" -> magenta(m);
                default -> bold(m);
            };
        }

        public String statusBadge(String status) {
            if (status.startsWith("2")) return green(status);
            if (status.startsWith("3")) return cyan(status);
            if (status.equals("404")) return red(bold(status));
            if (status.startsWith("4")) return yellow(status);
            if (status.startsWith("5")) return red(status);
            return status;
        }
    }

    // =========================================================================
    // Utilities & Helpers
    // =========================================================================

    private static HttpResponse<String> sendGet(HttpClient client, String serverUrl, String path) throws Exception {
        String base = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base + path))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String resolveServerUrl(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if (("-s".equals(args[i]) || "--server".equals(args[i])) && i + 1 < args.length) {
                return args[i + 1];
            }
        }
        String env = System.getenv("WIREMOCK_URL");
        return (env != null && !env.isBlank()) ? env : DEFAULT_SERVER_URL;
    }

    private static boolean hasFlag(String[] args, String... flags) {
        Set<String> flagSet = Set.of(flags);
        for (String arg : args) {
            if (flagSet.contains(arg)) return true;
        }
        return false;
    }

    private static int parseIntOption(String[] args, String shortFlag, String longFlag, int defaultVal) {
        for (int i = 0; i < args.length; i++) {
            if ((shortFlag.equals(args[i]) || longFlag.equals(args[i])) && i + 1 < args.length) {
                try {
                    return Integer.parseInt(args[i + 1]);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return defaultVal;
    }

    private static long parseLongOption(String[] args, String flag, long defaultVal) {
        for (int i = 0; i < args.length; i++) {
            if (flag.equals(args[i]) && i + 1 < args.length) {
                try {
                    return Long.parseLong(args[i + 1]);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return defaultVal;
    }

    private static String extractFilterExpression(String[] args, String... subcommands) {
        Set<String> subSet = Set.of(subcommands);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("-")) {
                if ("-s".equals(arg)
                        || "--server".equals(arg)
                        || "-n".equals(arg)
                        || "--limit".equals(arg)
                        || "--interval".equals(arg)) {
                    i++; // skip flag value
                }
                continue;
            }
            if (!subSet.contains(arg.toLowerCase(Locale.ROOT))) {
                if (!sb.isEmpty()) sb.append(" ");
                sb.append(arg);
            }
        }
        return sb.toString();
    }

    private static String[] stripFirstArg(String[] args) {
        if (args.length <= 1) return new String[0];
        return Arrays.copyOfRange(args, 1, args.length);
    }

    private static boolean isHelpArg(String arg) {
        return "-h".equals(arg) || "--help".equals(arg) || "help".equalsIgnoreCase(arg);
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        if (text.length() <= max) return text;
        return text.substring(0, max - 3) + "...";
    }

    private static String sanitize(String str) {
        return str.replace("\r", " ").replace("\n", " ");
    }

    private static void printUsage(PrintStream out) {
        out.println("wiremock-cli - Lightweight Terminal Observability for WireMock");
        out.println();
        out.println("Usage:");
        out.println("  wm <command> [options]");
        out.println();
        out.println("Commands:");
        out.println("  info                Probe server version and detected extension capabilities");
        out.println("  stubs [filter]      List registered stubs (supports AND/OR expressions)");
        out.println("  tail [options]      Stream or view recent request journal");
        out.println("  toxics              Inspect active TCP network impairments (NetEm extension)");
        out.println("  proxy [url] [port]  Launch standalone Web UI Console Proxy");
        out.println();
        out.println("Options:");
        out.println("  -s, --server <url>  Target WireMock URL (default: http://localhost:8080 or $WIREMOCK_URL)");
        out.println("  --no-color          Disable ANSI color styling");
        out.println("  -h, --help          Show this help message");
        out.println();
        out.println("Stubs Query Examples:");
        out.println("  wm stubs \"GET and /cases\"");
        out.println("  wm stubs \"POST or DELETE\"");
        out.println("  wm stubs \"status=disabled\"");
        out.println("  wm stubs --json");
        out.println();
        out.println("Tail Options:");
        out.println("  -f, --follow        Stream newly arriving requests continuously");
        out.println("  -u, --unmatched     Show unmatched (404) requests only");
        out.println("  -n, --limit <num>   Number of historical events to fetch (default: 10)");
        out.println("  --diff              Show candidate mismatch info for 404s");
        out.println();
    }
}
