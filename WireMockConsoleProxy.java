package com.github.mattiasgreen.wiremock.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Headless, zero-dependency reverse proxy &amp; asset server for WireMock Console UI.
 *
 * <p>Serves the official Vanilla JS Web UI at {@code /__admin/ui/}, and transparently forwards
 * all other administrative and application requests to the target WireMock instance.
 *
 * <p>Supports flexible static asset resolution:
 * <ol>
 *   <li>Local directory specified via {@code --assets <dir>} or {@code wiremock.ui.dev.staticDir}</li>
 *   <li>Development workspace filesystem hot-reload (if running inside git repo)</li>
 *   <li>Embedded classpath resources (if bundled in JAR)</li>
 *   <li>Local disk cache ({@code ~/.cache/wiremock-console/ui/})</li>
 *   <li>Remote fetch from GitHub Pages CDN ({@code https://mattiasgreen.github.io/wiremock-extensions})</li>
 * </ol>
 *
 * <p>Supports enterprise forward proxies via {@code HTTPS_PROXY}/{@code HTTP_PROXY}/{@code NO_PROXY}
 * environment variables, and optional TLS certificate bypass via {@code --insecure}.
 *
 * <p>Usage:
 * <pre>{@code
 * java WireMockConsoleProxy.java [target-url] [port] [options]
 * }</pre>
 */
public final class WireMockConsoleProxy {

    public static final String DEFAULT_TARGET_URL = "http://localhost:8080";
    public static final int DEFAULT_LOCAL_PORT = 8888;
    public static final String DEFAULT_REMOTE_ASSETS_URL = "https://mattiasgreen.github.io/wiremock-extensions";

    private static final List<String> DEV_ROOT_CANDIDATES = List.of(
            System.getProperty("wiremock.ui.dev.staticDir", ""),
            "wiremock-extension-ui/src/main/resources",
            "../wiremock-extension-ui/src/main/resources",
            "src/main/resources");

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection",
            "content-length",
            "host",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade");

    private final HttpClient httpClient;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final String defaultTargetUrl;
    private final Path customAssetsPath;
    private final String customRemoteAssetsUrl;
    private final Path cacheDirectory;
    private final boolean useCache;
    private final Map<String, byte[]> memoryCache = new ConcurrentHashMap<>();

    private HttpServer server;
    private int localPort;

    public WireMockConsoleProxy() {
        this(DEFAULT_TARGET_URL);
    }

    public WireMockConsoleProxy(String defaultTargetUrl) {
        this(defaultTargetUrl, 0);
    }

    public WireMockConsoleProxy(String defaultTargetUrl, int preferredPort) {
        this(defaultTargetUrl, preferredPort, null, false);
    }

    public WireMockConsoleProxy(
            String defaultTargetUrl, int preferredPort, String assetsLocation, boolean insecureTls) {
        this(defaultTargetUrl, preferredPort, assetsLocation, insecureTls, true);
    }

    public WireMockConsoleProxy(
            String defaultTargetUrl, int preferredPort, String assetsLocation, boolean insecureTls, boolean useCache) {
        this.defaultTargetUrl = normalizeUrl(defaultTargetUrl != null ? defaultTargetUrl : DEFAULT_TARGET_URL);
        this.useCache = useCache;
        this.cacheDirectory = resolveDefaultCacheDirectory();

        if (assetsLocation != null && !assetsLocation.isBlank()) {
            String trimmed = assetsLocation.trim();
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                this.customRemoteAssetsUrl = normalizeUrl(trimmed);
                this.customAssetsPath = null;
            } else {
                this.customAssetsPath = Path.of(trimmed);
                this.customRemoteAssetsUrl = null;
            }
        } else {
            this.customAssetsPath = null;
            this.customRemoteAssetsUrl = null;
        }

        this.httpClient = buildHttpClient(insecureTls);
        startServer(preferredPort);
    }

    public synchronized void startServer(int preferredPort) {
        if (running.get()) return;

        int port = preferredPort > 0 ? preferredPort : DEFAULT_LOCAL_PORT;
        HttpServer s;
        try {
            s = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        } catch (IOException ex) {
            try {
                s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to bind local HTTP server: " + e.getMessage(), e);
            }
        }

        this.server = s;
        this.localPort = server.getAddress().getPort();
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        this.server.createContext("/", new GatewayHandler());
        this.server.start();
        this.running.set(true);
    }

    public synchronized void stop() {
        if (!running.get()) return;
        running.set(false);
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getLocalPort() {
        return localPort;
    }

    public String getLocalUiUrl() {
        return "http://localhost:" + localPort + "/__admin/ui/";
    }

    public String getDefaultTargetUrl() {
        return defaultTargetUrl;
    }

    public Path getCustomAssetsPath() {
        return customAssetsPath;
    }

    public String getCustomRemoteAssetsUrl() {
        return customRemoteAssetsUrl;
    }

    public Path getCacheDirectory() {
        return cacheDirectory;
    }

    public boolean isUseCache() {
        return useCache;
    }

    // =========================================================================
    // Request Handling & Dynamic Proxying
    // =========================================================================

    private class GatewayHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();

            // 1. Root redirect to /__admin/ui/
            if (path.isEmpty()
                    || "/".equals(path)
                    || "/ui".equals(path)
                    || "/ui/".equals(path)
                    || "/__admin/ui".equals(path)) {
                exchange.getResponseHeaders().set("Location", "/__admin/ui/");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
                return;
            }

            // 2. Static Web Assets: /__admin/ui/*
            if (path.startsWith("/__admin/ui/")) {
                handleStaticAsset(exchange, path.substring("/__admin/ui".length()));
                return;
            }

            // 3. Proxy all other requests
            handleProxyRequest(exchange);
        }
    }

    private void handleStaticAsset(HttpExchange exchange, String subPath) throws IOException {
        addCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        String assetPath = (subPath == null || subPath.isEmpty() || "/".equals(subPath)) ? "/index.html" : subPath;

        if (assetPath.contains("..")) {
            sendError(exchange, 400, "Invalid asset path");
            return;
        }

        if ("/version".equals(assetPath)) {
            byte[] json = "{\"version\":\"0.1.0-SNAPSHOT\",\"commit\":\"HEAD\",\"branch\":\"standalone\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
            exchange.sendResponseHeaders(200, json.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(json);
            }
            return;
        }

        Optional<byte[]> contentOpt = loadAssetBytes("/ui" + assetPath);
        if (contentOpt.isEmpty()) {
            sendError(exchange, 404, "Asset not found: " + assetPath);
            return;
        }

        byte[] content = contentOpt.get();
        String contentType = resolveContentType(assetPath);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(content);
        }
    }

    private void handleProxyRequest(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }

        String targetBase = resolveTargetBase(exchange);
        String rawQuery = exchange.getRequestURI().getRawQuery();
        String fullPath = exchange.getRequestURI().getRawPath() + (rawQuery != null ? "?" + rawQuery : "");
        URI targetUri = URI.create(targetBase + fullPath);

        byte[] requestBody = exchange.getRequestBody().readAllBytes();

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder().uri(targetUri).timeout(Duration.ofSeconds(30));

        // Copy inbound headers (excluding hop-by-hop and proxy routing headers)
        for (Map.Entry<String, List<String>> header :
                exchange.getRequestHeaders().entrySet()) {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            if (!HOP_BY_HOP_HEADERS.contains(name) && !name.startsWith("x-forwarded-")) {
                for (String val : header.getValue()) {
                    try {
                        reqBuilder.header(header.getKey(), val);
                    } catch (IllegalArgumentException ignored) {
                        // Ignore restricted headers
                    }
                }
            }
        }

        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        HttpRequest.BodyPublisher publisher = (requestBody.length > 0)
                ? HttpRequest.BodyPublishers.ofByteArray(requestBody)
                : HttpRequest.BodyPublishers.noBody();

        switch (method) {
            case "GET" -> reqBuilder.GET();
            case "POST" -> reqBuilder.POST(publisher);
            case "PUT" -> reqBuilder.PUT(publisher);
            case "DELETE" -> reqBuilder.DELETE();
            default -> reqBuilder.method(method, publisher);
        }

        try {
            HttpResponse<byte[]> response =
                    httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());

            // Copy outbound headers
            for (Map.Entry<String, List<String>> header :
                    response.headers().map().entrySet()) {
                String name = header.getKey().toLowerCase(Locale.ROOT);
                if (!HOP_BY_HOP_HEADERS.contains(name)) {
                    for (String val : header.getValue()) {
                        exchange.getResponseHeaders().add(header.getKey(), val);
                    }
                }
            }

            byte[] body = response.body();
            exchange.sendResponseHeaders(response.statusCode(), body != null && body.length > 0 ? body.length : -1);
            if (body != null && body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendError(exchange, 502, "Proxy request interrupted");
        } catch (Exception e) {
            String sanitized =
                    e.getMessage() != null ? e.getMessage().replace("\r", " ").replace("\n", " ") : "Unknown error";
            sendError(
                    exchange, 502, "Bad Gateway: Failed to connect to upstream " + targetBase + " (" + sanitized + ")");
        }
    }

    private String resolveTargetBase(HttpExchange exchange) {
        String forwardedHost = exchange.getRequestHeaders().getFirst("X-Forwarded-Host");
        if (forwardedHost != null && !forwardedHost.isBlank()) {
            String proto = exchange.getRequestHeaders().getFirst("X-Forwarded-Proto");
            proto = (proto != null && !proto.isBlank()) ? proto.trim().toLowerCase(Locale.ROOT) : "http";

            String prefix = exchange.getRequestHeaders().getFirst("X-Forwarded-Prefix");
            prefix = (prefix != null) ? prefix.trim() : "";
            while (prefix.endsWith("/")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            if (!prefix.isEmpty() && !prefix.startsWith("/")) {
                prefix = "/" + prefix;
            }

            String host = forwardedHost.trim();
            while (host.endsWith("/")) {
                host = host.substring(0, host.length() - 1);
            }
            // Strip any accidental scheme if passed in host
            if (host.startsWith("http://")) host = host.substring("http://".length());
            else if (host.startsWith("https://")) host = host.substring("https://".length());

            return proto + "://" + host + prefix;
        }

        return defaultTargetUrl;
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders()
                .set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS, HEAD");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
        exchange.getResponseHeaders().set("Access-Control-Expose-Headers", "*");
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = String.format(Locale.ROOT, "{\"error\":\"%s\"}", message).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private Optional<byte[]> loadAssetBytes(String resourcePath) {
        String relativePath = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
        String uiSubPath = relativePath.startsWith("ui/") ? relativePath.substring(3) : relativePath;

        // 1. Explicit custom local assets directory (--assets <dir>)
        if (customAssetsPath != null && Files.isDirectory(customAssetsPath)) {
            Path directFile = customAssetsPath.resolve(uiSubPath);
            if (Files.isRegularFile(directFile)) {
                try {
                    return Optional.of(Files.readAllBytes(directFile));
                } catch (IOException ignored) {
                }
            }
            Path subFile = customAssetsPath.resolve(relativePath);
            if (Files.isRegularFile(subFile)) {
                try {
                    return Optional.of(Files.readAllBytes(subFile));
                } catch (IOException ignored) {
                }
            }
        }

        // 2. Dev workspace filesystem hot-reload (if running inside git repo)
        for (String candidate : DEV_ROOT_CANDIDATES) {
            if (candidate != null && !candidate.isBlank()) {
                Path filePath = Path.of(candidate, relativePath);
                if (Files.isRegularFile(filePath)) {
                    try {
                        return Optional.of(Files.readAllBytes(filePath));
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        // 3. Classpath fallback (if packaged inside JAR)
        try (InputStream in = WireMockConsoleProxy.class.getResourceAsStream(resourcePath)) {
            if (in != null) {
                return Optional.of(in.readAllBytes());
            }
        } catch (IOException ignored) {
        }

        // 4. In-memory cache
        if (useCache) {
            byte[] inMem = memoryCache.get(uiSubPath);
            if (inMem != null) {
                return Optional.of(inMem);
            }
        }

        // 5. Local disk cache (e.g. ~/.cache/wiremock-console/ui/app.js)
        if (useCache && cacheDirectory != null) {
            Path cachedFile = cacheDirectory.resolve(uiSubPath);
            if (Files.isRegularFile(cachedFile)) {
                try {
                    byte[] bytes = Files.readAllBytes(cachedFile);
                    memoryCache.put(uiSubPath, bytes);
                    return Optional.of(bytes);
                } catch (IOException ignored) {
                }
            }
        }

        // 6. Remote CDN / GitHub Pages fetch
        String remoteBase = customRemoteAssetsUrl != null ? customRemoteAssetsUrl : DEFAULT_REMOTE_ASSETS_URL;
        if (!remoteBase.isBlank()) {
            Optional<byte[]> fetched = fetchRemoteAsset(remoteBase, uiSubPath);
            if (fetched.isPresent()) {
                byte[] bytes = fetched.get();
                if (useCache) {
                    memoryCache.put(uiSubPath, bytes);
                    if (cacheDirectory != null) {
                        saveToDiskCache(cacheDirectory.resolve(uiSubPath), bytes);
                    }
                }
                return Optional.of(bytes);
            }
        }

        return Optional.empty();
    }

    private Optional<byte[]> fetchRemoteAsset(String remoteBase, String subPath) {
        String clean = subPath.startsWith("/") ? subPath.substring(1) : subPath;
        String base = remoteBase.endsWith("/") ? remoteBase.substring(0, remoteBase.length() - 1) : remoteBase;
        try {
            URI uri = URI.create(base + "/" + clean);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200 && resp.body() != null && resp.body().length > 0) {
                return Optional.of(resp.body());
            }
        } catch (IOException | InterruptedException ignored) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            }
        }
        return Optional.empty();
    }

    private static void saveToDiskCache(Path targetFile, byte[] bytes) {
        try {
            Path parent = targetFile.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.write(targetFile, bytes);
        } catch (IOException ignored) {
        }
    }

    private static Path resolveDefaultCacheDirectory() {
        try {
            String xdgCache = System.getenv("XDG_CACHE_HOME");
            if (xdgCache != null && !xdgCache.isBlank()) {
                return Path.of(xdgCache, "wiremock-console", "ui");
            }
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                return Path.of(localAppData, "wiremock-console", "ui");
            }
            String userHome = System.getProperty("user.home");
            if (userHome != null && !userHome.isBlank()) {
                return Path.of(userHome, ".cache", "wiremock-console", "ui");
            }
        } catch (SecurityException ignored) {
        }
        return Path.of(System.getProperty("java.io.tmpdir", "."), "wiremock-console-cache", "ui");
    }

    private static HttpClient buildHttpClient(boolean insecureTls) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL);

        ProxySelector proxySelector = resolveProxySelector();
        if (proxySelector != null) {
            builder.proxy(proxySelector);
        }

        if (insecureTls || Boolean.getBoolean("wiremock.insecure")) {
            builder.sslContext(createInsecureSslContext());
        }

        return builder.build();
    }

    private static ProxySelector resolveProxySelector() {
        if (System.getProperty("http.proxyHost") != null || System.getProperty("https.proxyHost") != null) {
            return ProxySelector.getDefault();
        }

        String envProxy = getEnvAny("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy");
        if (envProxy == null || envProxy.isBlank()) {
            return ProxySelector.getDefault();
        }

        try {
            String clean = envProxy.trim();
            if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
                clean = "http://" + clean;
            }
            URI proxyUri = URI.create(clean);
            String host = proxyUri.getHost();
            int port = proxyUri.getPort() > 0 ? proxyUri.getPort() : 8080;
            if (host == null || host.isBlank()) {
                return ProxySelector.getDefault();
            }

            String noProxyEnv = getEnvAny("NO_PROXY", "no_proxy");
            Set<String> noProxyHosts = parseNoProxy(noProxyEnv);

            return new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    if (isNoProxyHost(uri, noProxyHosts)) {
                        return List.of(Proxy.NO_PROXY);
                    }
                    return List.of(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port)));
                }

                @Override
                public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                    // Ignored
                }
            };
        } catch (IllegalArgumentException e) {
            return ProxySelector.getDefault();
        }
    }

    private static String getEnvAny(String... names) {
        for (String name : names) {
            String val = System.getenv(name);
            if (val != null && !val.isBlank()) {
                return val;
            }
        }
        return null;
    }

    private static Set<String> parseNoProxy(String noProxy) {
        Set<String> set = new HashSet<>();
        set.add("localhost");
        set.add("127.0.0.1");
        set.add("::1");
        if (noProxy != null && !noProxy.isBlank()) {
            for (String part : noProxy.split(",")) {
                String trimmed = part.trim().toLowerCase(Locale.ROOT);
                if (!trimmed.isEmpty()) {
                    if (trimmed.startsWith(".")) {
                        trimmed = trimmed.substring(1);
                    }
                    set.add(trimmed);
                }
            }
        }
        return set;
    }

    private static boolean isNoProxyHost(URI uri, Set<String> noProxyHosts) {
        if (uri == null || uri.getHost() == null) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        for (String np : noProxyHosts) {
            if (host.equals(np) || host.endsWith("." + np)) {
                return true;
            }
        }
        return false;
    }

    private static SSLContext createInsecureSslContext() {
        try {
            TrustManager[] trustAll = new TrustManager[] {
                new X509TrustManager() {
                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }

                    @Override
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}

                    @Override
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new SecureRandom());
            return sc;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Failed to initialize insecure TLS context: " + e.getMessage(), e);
        }
    }

    private static String resolveContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".js") || path.endsWith(".mjs")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".gif")) return "image/gif";
        if (path.endsWith(".ico")) return "image/x-icon";
        return "text/plain; charset=utf-8";
    }

    private static String normalizeUrl(String url) {
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "http://" + trimmed;
        }
        return trimmed;
    }

    // =========================================================================
    // Entry Point (JDK 21 Single-File Launcher)
    // =========================================================================

    public static void main(String[] args) {
        String target = DEFAULT_TARGET_URL;
        int port = DEFAULT_LOCAL_PORT;
        String assets = null;
        boolean insecure = false;
        boolean useCache = true;

        List<String> positional = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if ("--help".equalsIgnoreCase(arg) || "-h".equalsIgnoreCase(arg)) {
                printHelp();
                return;
            } else if ("--insecure".equalsIgnoreCase(arg) || "-k".equalsIgnoreCase(arg)) {
                insecure = true;
            } else if ("--no-cache".equalsIgnoreCase(arg)) {
                useCache = false;
            } else if (("--target".equalsIgnoreCase(arg) || "-t".equalsIgnoreCase(arg)) && i + 1 < args.length) {
                target = args[++i];
            } else if (("--port".equalsIgnoreCase(arg) || "-p".equalsIgnoreCase(arg)) && i + 1 < args.length) {
                port = Integer.parseInt(args[++i]);
            } else if (("--assets".equalsIgnoreCase(arg) || "-a".equalsIgnoreCase(arg)) && i + 1 < args.length) {
                assets = args[++i];
            } else if (arg.startsWith("-")) {
                System.err.println("Unknown option: " + arg);
                printHelp();
                System.exit(1);
            } else {
                positional.add(arg);
            }
        }

        // Positional fallback: [target] [port]
        if (!positional.isEmpty()) {
            target = positional.get(0);
        }
        if (positional.size() > 1) {
            try {
                port = Integer.parseInt(positional.get(1));
            } catch (NumberFormatException ignored) {
            }
        }

        WireMockConsoleProxy proxy = new WireMockConsoleProxy(target, port, assets, insecure, useCache);
        System.out.println(proxy.getLocalUiUrl());

        Runtime.getRuntime().addShutdownHook(new Thread(proxy::stop));
    }

    private static void printHelp() {
        System.out.println("WireMock Console Proxy - Standalone headless web console for WireMock (JDK 21)");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java WireMockConsoleProxy.java [target-url] [port] [options]");
        System.out.println("  java WireMockConsoleProxy.java [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --target, -t <url>       Target WireMock base URL (default: http://localhost:8080)");
        System.out.println("  --port, -p <port>        Local proxy HTTP port (default: 8888)");
        System.out.println("  --assets, -a <path|url>  Custom static assets directory or remote URL");
        System.out.println(
                "                           (default: remote fetch from https://mattiasgreen.github.io/wiremock-extensions/)");
        System.out.println("  --insecure, -k           Disable TLS certificate verification for corporate proxies");
        System.out.println("  --no-cache               Disable local disk caching for downloaded assets");
        System.out.println("  --help, -h               Show this help message");
    }
}
