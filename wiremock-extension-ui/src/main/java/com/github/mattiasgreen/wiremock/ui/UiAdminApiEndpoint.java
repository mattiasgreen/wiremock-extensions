package com.github.mattiasgreen.wiremock.ui;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.admin.model.SingleServedStubResult;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.Json;
import com.github.tomakehurst.wiremock.core.Admin;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.LoggedResponse;
import com.github.tomakehurst.wiremock.http.QueryParameter;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class UiAdminApiEndpoint implements AdminApiExtension {

    public static final String EXTENSION_NAME = "ui-admin-endpoint";

    private final JournalFilterService journalFilterService;

    public UiAdminApiEndpoint() {
        this(new JournalFilterService());
    }

    public UiAdminApiEndpoint(JournalFilterService journalFilterService) {
        this.journalFilterService =
                Objects.requireNonNull(journalFilterService, "journalFilterService must not be null");
    }

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
                "/ui/api/requests/{id}",
                (admin, serveEvent, pathParams) -> serveJournalRequestDetail(admin, pathParams.get("id")));
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

        QueryParameter summaryParam = serveEvent.getRequest().queryParameter("summary");
        boolean summaryOnly =
                summaryParam != null && summaryParam.isPresent() && Boolean.parseBoolean(summaryParam.firstValue());

        List<ServeEvent> allEvents = admin.getServeEvents().getServeEvents();
        JournalFilterService.EnrichedJournalFilterResult result =
                journalFilterService.filterEnriched(allEvents, q, verdict, unmatchedOnly, status, since, until, limit);

        List<Object> enrichedRequests = new ArrayList<>(result.requests().size());
        for (ServeEvent ev : result.requests()) {
            String srcIp = ClientSourceResolver.extractSourceIp(ev.getRequest());
            String srcHost = srcIp.isEmpty() ? "" : result.resolvedHosts().getOrDefault(srcIp, "");
            if (summaryOnly) {
                enrichedRequests.add(toSummaryMap(ev, srcIp, srcHost));
            } else {
                enrichedRequests.add(new EnrichedServeEventView(ev, srcIp, srcHost));
            }
        }

        Map<String, Object> respMap = Map.of(
                "requests",
                enrichedRequests,
                "meta",
                Map.of("total", result.total(), "filtered", result.filtered(), "limit", limit, "summary", summaryOnly),
                "requestJournalDisabled",
                false);

        return ResponseDefinitionBuilder.responseDefinition()
                .withStatus(200)
                .withHeader("Content-Type", "application/json; charset=utf-8")
                .withHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                .withBody(Json.write(respMap))
                .build();
    }

    private ResponseDefinition serveJournalRequestDetail(Admin admin, String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return ResponseDefinitionBuilder.responseDefinition()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json; charset=utf-8")
                    .withBody("{\"error\":\"Missing request id\"}")
                    .build();
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(rawId.trim());
        } catch (IllegalArgumentException e) {
            return ResponseDefinitionBuilder.responseDefinition()
                    .withStatus(400)
                    .withHeader("Content-Type", "application/json; charset=utf-8")
                    .withBody("{\"error\":\"Invalid request UUID\"}")
                    .build();
        }

        SingleServedStubResult result = admin.getServedStub(uuid);
        if (result == null || !result.isPresent() || result.getItem() == null) {
            return ResponseDefinitionBuilder.responseDefinition()
                    .withStatus(404)
                    .withHeader("Content-Type", "application/json; charset=utf-8")
                    .withBody("{\"error\":\"Request not found\"}")
                    .build();
        }

        ServeEvent ev = result.getItem();
        String srcIp = ClientSourceResolver.extractSourceIp(ev.getRequest());
        String srcHost = journalFilterService.resolveSourceHost(srcIp);
        return ResponseDefinitionBuilder.responseDefinition()
                .withStatus(200)
                .withHeader("Content-Type", "application/json; charset=utf-8")
                .withHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                .withBody(Json.write(new EnrichedServeEventView(ev, srcIp, srcHost)))
                .build();
    }

    static Map<String, Object> toSummaryMap(ServeEvent ev, String srcIp, String srcHost) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", ev.getId() != null ? ev.getId().toString() : null);
        map.put("wasMatched", ev.getWasMatched());
        map.put("summaryOnly", true);
        map.put("sourceIp", srcIp != null ? srcIp : "");
        map.put("sourceHost", srcHost != null ? srcHost : "");
        if (ev.getTiming() != null) {
            map.put("timing", ev.getTiming());
        }

        LoggedRequest req = ev.getRequest();
        if (req != null) {
            Map<String, Object> reqMap = new LinkedHashMap<>();
            reqMap.put("url", req.getUrl());
            reqMap.put("absoluteUrl", req.getAbsoluteUrl());
            reqMap.put("method", req.getMethod() != null ? req.getMethod().getName() : null);
            reqMap.put("clientIp", req.getClientIp());
            reqMap.put("headers", toHeaderMap(req.getHeaders()));
            if (req.getLoggedDate() != null) {
                reqMap.put("loggedDate", req.getLoggedDate().getTime());
            }
            reqMap.put("loggedDateString", req.getLoggedDateString());
            map.put("request", reqMap);
        }

        LoggedResponse res = ev.getResponse();
        if (res != null) {
            Map<String, Object> resMap = new LinkedHashMap<>();
            resMap.put("status", res.getStatus());
            resMap.put("headers", toHeaderMap(res.getHeaders()));
            map.put("response", resMap);
        }

        ResponseDefinition rd = ev.getResponseDefinition();
        if (rd != null) {
            Map<String, Object> rdMap = new LinkedHashMap<>();
            rdMap.put("status", rd.getStatus());
            rdMap.put("headers", toHeaderMap(rd.getHeaders()));
            if (rd.getProxyBaseUrl() != null) {
                rdMap.put("proxyBaseUrl", rd.getProxyBaseUrl());
            }
            if (rd.getTransformers() != null && !rd.getTransformers().isEmpty()) {
                rdMap.put("transformers", rd.getTransformers());
            }
            map.put("responseDefinition", rdMap);
        }

        StubMapping stub = ev.getStubMapping();
        if (stub != null) {
            Map<String, Object> stubMap = new LinkedHashMap<>();
            stubMap.put("id", stub.getId() != null ? stub.getId().toString() : null);
            stubMap.put("name", stub.getName());
            if (stub.getPriority() != null) {
                stubMap.put("priority", stub.getPriority());
            }
            if (stub.getMetadata() != null) {
                stubMap.put("metadata", stub.getMetadata());
            }
            ResponseDefinition stubResp = stub.getResponse();
            if (stubResp != null) {
                Map<String, Object> stubRespMap = new LinkedHashMap<>();
                if (stubResp.getProxyBaseUrl() != null) {
                    stubRespMap.put("proxyBaseUrl", stubResp.getProxyBaseUrl());
                }
                List<String> transformers = stubResp.getTransformers();
                if (transformers != null && !transformers.isEmpty()) {
                    stubRespMap.put("transformers", transformers);
                }
                boolean isTemplated = (transformers != null && transformers.contains("response-template"))
                        || (stubResp.getBody() != null && stubResp.getBody().contains("{{"));
                if (isTemplated) {
                    stubRespMap.put("isTemplated", true);
                }
                if (!stubRespMap.isEmpty()) {
                    stubMap.put("response", stubRespMap);
                }
            }
            map.put("stubMapping", stubMap);
        }

        return map;
    }

    private static Map<String, Object> toHeaderMap(HttpHeaders headers) {
        if (headers == null || headers.size() == 0) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (HttpHeader header : headers.all()) {
            if (header == null || header.key() == null) {
                continue;
            }
            out.put(header.key(), header.isSingleValued() ? header.firstValue() : header.values());
        }
        return out;
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
                .withBody(UiAssetLoader.resolveVersionJson())
                .build();
    }

    private ResponseDefinition serveResource(String resourcePath) {
        UiAssetLoader.StaticAsset asset = UiAssetLoader.loadStaticAsset(resourcePath);
        if (!asset.found()) {
            return ResponseDefinitionBuilder.responseDefinition()
                    .withStatus(404)
                    .withHeader("Content-Type", "text/plain; charset=utf-8")
                    .withHeader("Cache-Control", "no-cache")
                    .withBody("Asset not found")
                    .build();
        }
        return ResponseDefinitionBuilder.responseDefinition()
                .withStatus(200)
                .withHeader("Content-Type", asset.mimeType())
                .withHeader("Cache-Control", "no-cache, no-store, must-revalidate")
                .withBody(asset.content())
                .build();
    }

    static final class EnrichedServeEventView {

        private final ServeEvent event;
        private final String sourceIp;
        private final String sourceHost;

        EnrichedServeEventView(ServeEvent event, String sourceIp, String sourceHost) {
            this.event = event;
            this.sourceIp = sourceIp;
            this.sourceHost = sourceHost;
        }

        @JsonUnwrapped
        public ServeEvent getEvent() {
            return event;
        }

        public String getSourceIp() {
            return sourceIp;
        }

        public String getSourceHost() {
            return sourceHost;
        }
    }
}
