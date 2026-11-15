package com.github.mattiasgreen.wiremock.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Resolves embedded Web UI static assets and version metadata for {@link UiAdminApiEndpoint}.
 *
 * <p>Checks local developer resource directories before falling back to classpath resources via
 * {@code UiAssetLoader.class.getResourceAsStream(...)}, rejecting path traversal segments.
 */
public final class UiAssetLoader {

    private static final List<String> DEV_ROOT_CANDIDATES = List.of(
            System.getProperty("wiremock.ui.dev.staticDir", ""),
            "wiremock-extension-ui/src/main/resources",
            "../wiremock-extension-ui/src/main/resources",
            "src/main/resources");

    private UiAssetLoader() {}

    /**
     * Loaded static UI asset payload and MIME classification.
     *
     * @param found whether the asset was resolved safely
     * @param content raw asset bytes when {@code found} is true; empty otherwise
     * @param mimeType resolved HTTP {@code Content-Type} header value
     */
    public record StaticAsset(boolean found, byte[] content, String mimeType) {

        /** Returns the asset content decoded as UTF-8, or an empty string when absent. */
        public String bodyAsString() {
            return content != null ? new String(content, StandardCharsets.UTF_8) : "";
        }
    }

    /**
     * Loads a static Web UI asset from dev resource roots or the packaged classpath.
     *
     * @param resourcePath classpath resource path (for example {@code "/ui/index.html"})
     * @return resolved {@link StaticAsset}, with {@code found == false} when missing or unsafe
     */
    public static StaticAsset loadStaticAsset(String resourcePath) {
        if (resourcePath == null
                || resourcePath.isBlank()
                || resourcePath.contains("..")
                || resourcePath.contains("\\")) {
            return notFound();
        }

        String normalizedPath = resourcePath.startsWith("/") ? resourcePath : "/" + resourcePath;
        String relativePath = normalizedPath.substring(1);
        String mimeType = guessMimeType(normalizedPath);

        // Dev workspace filesystem hot-reload
        for (String candidate : DEV_ROOT_CANDIDATES) {
            if (candidate != null && !candidate.isBlank()) {
                Path root = Path.of(candidate).toAbsolutePath().normalize();
                Path filePath = root.resolve(relativePath).normalize();
                if (filePath.startsWith(root) && Files.isRegularFile(filePath)) {
                    try {
                        byte[] bytes = Files.readAllBytes(filePath);
                        return new StaticAsset(true, bytes, mimeType);
                    } catch (IOException ignored) {
                        return notFound();
                    }
                }
            }
        }

        // Production classpath fallback
        try (InputStream in = UiAssetLoader.class.getResourceAsStream(normalizedPath)) {
            if (in == null) {
                return notFound();
            }
            return new StaticAsset(true, in.readAllBytes(), mimeType);
        } catch (IOException ignored) {
            return notFound();
        }
    }

    /**
     * Resolves the JSON payload served by {@code GET /__admin/ui/version}.
     *
     * @return JSON object string containing {@code version}, {@code commit}, and {@code branch}
     */
    public static String resolveVersionJson() {
        StaticAsset asset = loadStaticAsset("/ui/version.json");
        if (asset.found() && asset.content() != null && asset.content().length > 0) {
            String json = new String(asset.content(), StandardCharsets.UTF_8).trim();
            if (json.startsWith("{") && json.contains("\"version\"")) {
                return json;
            }
        }
        String version = resolveVersion();
        String commit = resolveGitCommit();
        String branch = resolveGitBranch();
        return String.format(
                Locale.ROOT,
                "{\"version\":\"%s\",\"commit\":\"%s\",\"branch\":\"%s\"}",
                escapeJsonValue(version),
                escapeJsonValue(commit),
                escapeJsonValue(branch));
    }

    /** Resolves an HTTP {@code Content-Type} value from a resource path extension. */
    public static String guessMimeType(String path) {
        if (path == null) {
            return "text/plain; charset=utf-8";
        }
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".ico")) return "image/x-icon";
        return "text/plain; charset=utf-8";
    }

    private static StaticAsset notFound() {
        return new StaticAsset(false, new byte[0], "text/plain; charset=utf-8");
    }

    private static String resolveVersion() {
        Package pkg = UiAssetLoader.class.getPackage();
        String ver = pkg != null ? pkg.getImplementationVersion() : null;
        return (ver != null && !ver.isBlank()) ? ver : "1.14.0";
    }

    private static String resolveGitCommit() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--short", "HEAD").start();
            return readGitOutput(process, "unknown");
        } catch (IOException | RuntimeException ignored) {
            return "unknown";
        }
    }

    private static String resolveGitBranch() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD").start();
            return readGitOutput(process, "main");
        } catch (IOException | RuntimeException ignored) {
            return "main";
        }
    }

    private static String readGitOutput(Process process, String fallback) throws IOException {
        try (InputStream in = process.getInputStream()) {
            String output = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!output.isBlank() && !output.contains("fatal")) {
                return output;
            }
        }
        return fallback;
    }

    private static String escapeJsonValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
