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
            "src/main/resources"
    );

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public void contributeAdminApiRoutes(Router router) {
        router.add(RequestMethod.GET, "/ui", (admin, serveEvent, pathParams) -> serveResource("/ui/index.html", "text/html; charset=utf-8"));
        router.add(RequestMethod.GET, "/ui/", (admin, serveEvent, pathParams) -> serveResource("/ui/index.html", "text/html; charset=utf-8"));
        router.add(RequestMethod.GET, "/ui/index.html", (admin, serveEvent, pathParams) -> serveResource("/ui/index.html", "text/html; charset=utf-8"));

        router.add(RequestMethod.GET, "/ui/app.js", (admin, serveEvent, pathParams) -> serveResource("/ui/app.js", "application/javascript; charset=utf-8"));
        router.add(RequestMethod.GET, "/ui/style.css", (admin, serveEvent, pathParams) -> serveResource("/ui/style.css", "text/css; charset=utf-8"));
    }

    private ResponseDefinition serveResource(String resourcePath, String contentType) {
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
