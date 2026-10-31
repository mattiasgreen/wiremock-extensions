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
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

public class UiAdminApiEndpoint implements AdminApiExtension {

    public static final String EXTENSION_NAME = "ui-admin-endpoint";

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
        return ResponseDefinitionBuilder.responseDefinition()
                .withStatus(200)
                .withHeader("Content-Type", "application/json; charset=utf-8")
                .withHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                .withBody(WireMockConsoleProxy.resolveVersionJson())
                .build();
    }

    private ResponseDefinition serveResource(String resourcePath) {
        WireMockConsoleProxy.StaticAsset asset = WireMockConsoleProxy.loadStaticAsset(resourcePath);
        ResponseDefinitionBuilder builder = ResponseDefinitionBuilder.responseDefinition()
                .withStatus(asset.status())
                .withHeader("Content-Type", asset.contentType())
                .withHeader("Cache-Control", asset.cacheControl())
                .withBody(asset.bodyAsString());
        if (asset.servedFrom() != null) {
            builder.withHeader("X-Served-From", asset.servedFrom());
        }
        return builder.build();
    }
}
