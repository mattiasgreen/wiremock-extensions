package com.github.mattiasgreen.wiremock.ui;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Headless, zero-dependency reverse proxy &amp; asset server for WireMock Console UI.
 *
 * <p>Serves the official Vanilla JS Web UI at {@code /__admin/ui/}, and transparently forwards
 * all other administrative and application requests to the target WireMock instance.
 *
 * <p>Supports per-request dynamic routing via standard HTTP headers:
 * <ul>
 *   <li>{@code X-Forwarded-Host}: Remote WireMock host and optional port (e.g. {@code staging.corp:8443}).</li>
 *   <li>{@code X-Forwarded-Proto}: Remote scheme ({@code http} or {@code https}, defaults to {@code http}).</li>
 *   <li>{@code X-Forwarded-Prefix}: Optional base path prefix (e.g. {@code /test-envs/env123/wiremock}).</li>
 * </ul>
 *
 * <p>Outputs the local browser URL to stdout upon startup:
 * <pre>{@code
 * java WireMockConsoleProxy.java [default-target-url] [local-port]
 * }</pre>
 */
public final class WireMockConsoleProxy {

    public static final String DEFAULT_TARGET_URL = "http://localhost:8080";
    public static final int DEFAULT_LOCAL_PORT = 19840;

    private static final List<String> DEV_ROOT_CANDIDATES = List.of(
            System.getProperty("wiremock.ui.dev.staticDir", ""),
            "wiremock-extension-ui/src/main/resources",
            "../wiremock-extension-ui/src/main/resources",
            "src/main/resources");

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "content-length", "host", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade");

    private final HttpClient httpClient;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final String defaultTargetUrl;

    private HttpServer server;
    private int localPort;

    public WireMockConsoleProxy() {
        this(DEFAULT_TARGET_URL);
    }

    public WireMockConsoleProxy(String defaultTargetUrl) {
        this(defaultTargetUrl, 0);
    }

    public WireMockConsoleProxy(String defaultTargetUrl, int preferredPort) {
        this.defaultTargetUrl = normalizeUrl(defaultTargetUrl != null ? defaultTargetUrl : DEFAULT_TARGET_URL);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
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
        return getLocalUiUrl(true);
    }

    public String getLocalUiUrl(boolean includeTargetParams) {
        if (!includeTargetParams || defaultTargetUrl == null || defaultTargetUrl.isBlank()) {
            return "http://localhost:" + localPort + "/__admin/ui/";
        }
        return "http://localhost:" + localPort + "/__admin/ui/?wiremock="
                + java.net.URLEncoder.encode(defaultTargetUrl, StandardCharsets.UTF_8)
                + "&strategy=proxy";
    }

    public String getDefaultTargetUrl() {
        return defaultTargetUrl;
    }

    // =========================================================================
    // Request Handling & Dynamic Proxying
    // =========================================================================

    private class GatewayHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();

            // 1. Root redirect to /__admin/ui/
            if (path.isEmpty() || "/".equals(path) || "/ui".equals(path) || "/ui/".equals(path) || "/__admin/ui".equals(path)) {
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

        String assetPath = (subPath == null || subPath.isEmpty() || "/".equals(subPath))
                ? "/index.html"
                : subPath;

        if (assetPath.contains("..")) {
            sendError(exchange, 400, "Invalid asset path");
            return;
        }

        if ("/version".equals(assetPath)) {
            byte[] json = "{\"version\":\"1.2.0\",\"commit\":\"HEAD\",\"branch\":\"standalone\"}".getBytes(StandardCharsets.UTF_8);
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

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(targetUri)
                .timeout(Duration.ofSeconds(30));

        // Copy inbound headers (excluding hop-by-hop and proxy routing headers)
        for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
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
            HttpResponse<byte[]> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());

            // Copy outbound headers
            for (Map.Entry<String, List<String>> header : response.headers().map().entrySet()) {
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
            String sanitized = e.getMessage() != null
                    ? e.getMessage().replace("\r", " ").replace("\n", " ")
                    : "Unknown error";
            sendError(exchange, 502, "Bad Gateway: Failed to connect to upstream " + targetBase + " (" + sanitized + ")");
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
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS, HEAD");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
        exchange.getResponseHeaders().set("Access-Control-Expose-Headers", "*");
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = String.format("{\"error\":\"%s\"}", message).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private Optional<byte[]> loadAssetBytes(String resourcePath) {
        String relativePath = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;

        // Dev workspace filesystem hot-reload
        for (String candidate : DEV_ROOT_CANDIDATES) {
            if (candidate != null && !candidate.isBlank()) {
                Path filePath = Path.of(candidate, relativePath);
                if (Files.isRegularFile(filePath)) {
                    try {
                        return Optional.of(Files.readAllBytes(filePath));
                    } catch (IOException ignored) {}
                }
            }
        }

        // Production classpath fallback
        try (InputStream in = WireMockConsoleProxy.class.getResourceAsStream(resourcePath)) {
            if (in != null) {
                return Optional.of(in.readAllBytes());
            }
        } catch (IOException ignored) {}

        return Optional.empty();
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
        String target = (args.length > 0 && !args[0].isBlank()) ? args[0] : DEFAULT_TARGET_URL;
        int port = (args.length > 1) ? Integer.parseInt(args[1]) : DEFAULT_LOCAL_PORT;

        WireMockConsoleProxy proxy = new WireMockConsoleProxy(target, port);
        System.out.println(proxy.getLocalUiUrl());

        Runtime.getRuntime().addShutdownHook(new Thread(proxy::stop));
    }
}
