package com.github.mattiasgreen.wiremock.ui;

import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.core.Admin;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.QueryParameter;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

public class UiAdminApiEndpoint implements AdminApiExtension {

    public static final String EXTENSION_NAME = "ui-admin-endpoint";

    private static final List<String> DEV_ROOT_CANDIDATES = List.of(
            System.getProperty("wiremock.ui.dev.staticDir", ""),
            "wiremock-extension-ui/src/main/resources",
            "../wiremock-extension-ui/src/main/resources",
            "src/main/resources");

    private final JournalFilterService journalFilterService = new JournalFilterService();

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

        router.add(RequestMethod.GET, "/ui/version", (admin, serveEvent, pathParams) -> serveVersionInfo());
        router.add(
                RequestMethod.GET,
                "/ui/api/requests",
                (admin, serveEvent, pathParams) -> serveJournalRequests(admin, serveEvent));
        router.add(
                RequestMethod.GET,
                "/ui/{file}",
                (admin, serveEvent, pathParams) -> serveResource("/ui/" + pathParams.get("file")));
        router.add(
                RequestMethod.GET,
                "/ui/modules/{module}",
                (admin, serveEvent, pathParams) -> serveResource("/ui/modules/" + pathParams.get("module")));
    }

    private ResponseDefinition serveJournalRequests(Admin admin, ServeEvent serveEvent) {
        QueryParameter qParam = serveEvent.getRequest().queryParameter("q");
        String q = (qParam != null && qParam.isPresent()) ? qParam.firstValue() : "";

        QueryParameter verdictParam = serveEvent.getRequest().queryParameter("verdict");
        String verdict = (verdictParam != null && verdictParam.isPresent()) ? verdictParam.firstValue() : "all";

        QueryParameter unmatchedParam = serveEvent.getRequest().queryParameter("unmatched");
        boolean unmatchedOnly = unmatchedParam != null
                && unmatchedParam.isPresent()
                && Boolean.parseBoolean(unmatchedParam.firstValue());

        QueryParameter statusParam = serveEvent.getRequest().queryParameter("status");
        Integer status = null;
        if (statusParam != null
                && statusParam.isPresent()
                && !statusParam.firstValue().isBlank()) {
            try {
                status = Integer.parseInt(statusParam.firstValue());
            } catch (NumberFormatException ignored) {
            }
        }

        QueryParameter limitParam = serveEvent.getRequest().queryParameter("limit");
        int limit = 100;
        if (limitParam != null && limitParam.isPresent()) {
            try {
                limit = Math.max(1, Math.min(1000, Integer.parseInt(limitParam.firstValue())));
            } catch (NumberFormatException ignored) {
            }
        }

        QueryParameter sinceParam = serveEvent.getRequest().queryParameter("since");
        Instant since = parseInstant(sinceParam);

        QueryParameter untilParam = serveEvent.getRequest().queryParameter("until");
        Instant until = parseInstant(untilParam);

        List<ServeEvent> allEvents = admin.getServeEvents().getServeEvents();
        JournalFilterService.JournalFilterResult result =
                journalFilterService.filter(allEvents, q, verdict, unmatchedOnly, status, since, until, limit);

        Map<String, Object> respMap = Map.of(
                "requests", result.requests(),
                "meta",
                        Map.of(
                                "total", result.total(),
                                "filtered", result.filtered(),
                                "limit", limit),
                "requestJournalDisabled", false);

        return ResponseDefinitionBuilder.responseDefinition()
                .withStatus(200)
                .withHeader("Content-Type", "application/json; charset=utf-8")
                .withHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                .withBody(Json.write(respMap))
                .build();
    }

    private Instant parseInstant(QueryParameter param) {
        if (param == null || !param.isPresent() || param.firstValue().isBlank()) {
            return null;
        }
        String val = param.firstValue().trim();
        try {
            return Instant.parse(val);
        } catch (DateTimeParseException e) {
            try {
                return Instant.ofEpochMilli(Long.parseLong(val));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
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
        return (ver != null && !ver.isBlank()) ? ver : "1.2.0";
    }

    private String resolveGitCommit() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--short", "HEAD").start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!output.isBlank() && !output.contains("fatal")) {
                return output;
            }
        } catch (IOException | RuntimeException ignored) {
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
        } catch (IOException | RuntimeException ignored) {
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
        try (InputStream in = UiAdminApiEndpoint.class.getResourceAsStream(resourcePath)) {
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
