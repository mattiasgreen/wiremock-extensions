package com.github.mattiasgreen.wiremock.ui;

import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class UiAdminApiEndpoint implements AdminApiExtension {

    public static final String EXTENSION_NAME = "ui-admin-endpoint";

    private static final List<String> DEV_ROOT_CANDIDATES = List.of(
            System.getProperty("wiremock.ui.dev.staticDir", ""),
            "wiremock-extension-ui/src/main/resources",
            "../wiremock-extension-ui/src/main/resources",
            "src/main/resources");

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public void contributeAdminApiRoutes(Router router) {
        router.add(RequestMethod.GET, "/ui", (admin, serveEvent, pathParams) -> serveResource("/ui/index.html"));
        router.add(RequestMethod.GET, "/ui/", (admin, serveEvent, pathParams) -> serveResource("/ui/index.html"));
        router.add(
                RequestMethod.GET,
                "/ui/index.html",
                (admin, serveEvent, pathParams) -> serveResource("/ui/index.html"));

        router.add(
                RequestMethod.GET,
                "/ui/{file}",
                (admin, serveEvent, pathParams) -> serveResource("/ui/" + pathParams.get("file")));
        router.add(
                RequestMethod.GET,
                "/ui/modules/{module}",
                (admin, serveEvent, pathParams) -> serveResource("/ui/modules/" + pathParams.get("module")));
        router.add(RequestMethod.GET, "/ui/version", (admin, serveEvent, pathParams) -> serveVersionInfo());
    }

    private ResponseDefinition serveVersionInfo() {
        String version = resolveVersion();
        String commit = resolveGitCommit();
        String branch = resolveGitBranch();
        String json =
                String.format("{\"version\":\"%s\",\"commit\":\"%s\",\"branch\":\"%s\"}", version, commit, branch);
        return ResponseDefinitionBuilder.responseDefinition()
                .withStatus(200)
                .withHeader("Content-Type", "application/json; charset=utf-8")
                .withHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                .withBody(json)
                .build();
    }

    private String resolveVersion() {
        String ver = getClass().getPackage().getImplementationVersion();
        return (ver != null && !ver.isBlank()) ? ver : "0.1.0-SNAPSHOT";
    }

    private String resolveGitCommit() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--short", "HEAD").start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!output.isBlank() && !output.contains("fatal")) {
                return output;
            }
        } catch (Exception ignored) {
        }
        return "27d3381";
    }

    private String resolveGitBranch() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD").start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!output.isBlank() && !output.contains("fatal")) {
                return output;
            }
        } catch (Exception ignored) {
        }
        return "feat/openapi-proxy-recording";
    }

    private static String resolveContentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        } else if (path.endsWith(".js") || path.endsWith(".mjs")) {
            return "application/javascript; charset=utf-8";
        } else if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        } else if (path.endsWith(".json")) {
            return "application/json; charset=utf-8";
        } else if (path.endsWith(".svg")) {
            return "image/svg+xml";
        } else if (path.endsWith(".png")) {
            return "image/png";
        } else if (path.endsWith(".gif")) {
            return "image/gif";
        } else if (path.endsWith(".ico")) {
            return "image/x-icon";
        }
        return "text/plain; charset=utf-8";
    }

    private ResponseDefinition serveResource(String resourcePath) {
        if (resourcePath == null || resourcePath.contains("..")) {
            return ResponseDefinitionBuilder.responseDefinition()
                    .withStatus(400)
                    .withHeader("Content-Type", "text/plain")
                    .withBody("Invalid resource path")
                    .build();
        }
        String contentType = resolveContentType(resourcePath);
        String relativePath = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;

        // Fast inner loop: Hot-replace static UI files from filesystem if running in dev/source workspace
        for (String candidate : DEV_ROOT_CANDIDATES) {
            if (candidate != null && !candidate.isBlank()) {
                Path filePath = Path.of(candidate, relativePath);
                if (Files.isRegularFile(filePath)) {
                    try {
                        String content = Files.readString(filePath, StandardCharsets.UTF_8);
                        return ResponseDefinitionBuilder.responseDefinition()
                                .withStatus(200)
                                .withHeader("Content-Type", contentType)
                                .withHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                                .withHeader("X-Served-From", "dev-filesystem")
                                .withBody(content)
                                .build();
                    } catch (IOException e) {
                        return ResponseDefinitionBuilder.responseDefinition()
                                .withStatus(500)
                                .withHeader("Content-Type", "text/plain")
                                .withBody("Error reading dev resource from disk: " + e.getMessage())
                                .build();
                    }
                }
            }
        }

        // Production classpath fallback
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return ResponseDefinitionBuilder.responseDefinition()
                        .withStatus(404)
                        .withHeader("Content-Type", "text/plain")
                        .withBody("Asset not found: " + resourcePath)
                        .build();
            }
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return ResponseDefinitionBuilder.responseDefinition()
                    .withStatus(200)
                    .withHeader("Content-Type", contentType)
                    .withHeader("Cache-Control", "public, max-age=3600")
                    .withBody(content)
                    .build();
        } catch (IOException e) {
            return ResponseDefinitionBuilder.responseDefinition()
                    .withStatus(500)
                    .withHeader("Content-Type", "text/plain")
                    .withBody("Error reading resource: " + e.getMessage())
                    .build();
        }
    }
}
