package com.github.mattiasgreen.wiremock.netem.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmProxy;
import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmRegistry;
import com.github.mattiasgreen.wiremock.netem.journal.NetEmJournalEntry;
import com.github.mattiasgreen.wiremock.netem.model.NetEmProxyConfig;
import com.github.mattiasgreen.wiremock.netem.model.NetEmToxicConfig;
import com.github.tomakehurst.wiremock.admin.Router;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.extension.AdminApiExtension;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class TcpNetEmAdminEndpoint implements AdminApiExtension {

    public static final String EXTENSION_NAME = "tcp-netem-admin-endpoint";
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final TcpNetEmRegistry registry;

    public TcpNetEmAdminEndpoint() {
        this(TcpNetEmRegistry.getInstance());
    }

    public TcpNetEmAdminEndpoint(TcpNetEmRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public void contributeAdminApiRoutes(Router router) {
        // --- Dedicated WireMock NetEm routes under /tcp-netem/... ---
        router.add(RequestMethod.GET, "/tcp-netem/proxies", (admin, serveEvent, pathParams) -> listProxiesJson());
        router.add(
                RequestMethod.POST,
                "/tcp-netem/proxies",
                (admin, serveEvent, pathParams) ->
                        createProxy(serveEvent.getRequest().getBodyAsString()));
        router.add(
                RequestMethod.GET,
                "/tcp-netem/proxies/{name}",
                (admin, serveEvent, pathParams) -> getProxyJson(pathParams.get("name")));
        router.add(
                RequestMethod.DELETE,
                "/tcp-netem/proxies/{name}",
                (admin, serveEvent, pathParams) -> deleteProxy(pathParams.get("name")));
        router.add(
                RequestMethod.POST,
                "/tcp-netem/proxies/{name}/toxics",
                (admin, serveEvent, pathParams) ->
                        addToxic(pathParams.get("name"), serveEvent.getRequest().getBodyAsString()));
        router.add(
                RequestMethod.DELETE,
                "/tcp-netem/proxies/{name}/toxics/{toxicName}",
                (admin, serveEvent, pathParams) -> deleteToxic(pathParams.get("name"), pathParams.get("toxicName")));
        router.add(
                RequestMethod.POST,
                "/tcp-netem/proxies/{name}/presets/{presetName}",
                (admin, serveEvent, pathParams) -> applyPreset(pathParams.get("name"), pathParams.get("presetName")));
        router.add(RequestMethod.POST, "/tcp-netem/reset", (admin, serveEvent, pathParams) -> resetAll());
        router.add(
                RequestMethod.GET,
                "/tcp-netem/journal",
                (admin, serveEvent, pathParams) -> getJournalJson(serveEvent.getRequest()));
        router.add(RequestMethod.DELETE, "/tcp-netem/journal", (admin, serveEvent, pathParams) -> clearJournal());
        router.add(RequestMethod.POST, "/tcp-netem/journal/reset", (admin, serveEvent, pathParams) -> clearJournal());

        // --- Toxiproxy v2 API compatibility routes ---
        router.add(RequestMethod.GET, "/proxies", (admin, serveEvent, pathParams) -> listToxiproxyMap());
        router.add(
                RequestMethod.POST,
                "/proxies",
                (admin, serveEvent, pathParams) ->
                        createProxy(serveEvent.getRequest().getBodyAsString()));
        router.add(
                RequestMethod.GET,
                "/proxies/{name}",
                (admin, serveEvent, pathParams) -> getToxiproxyDetails(pathParams.get("name")));
        router.add(
                RequestMethod.POST,
                "/proxies/{name}",
                (admin, serveEvent, pathParams) -> updateProxy(
                        pathParams.get("name"), serveEvent.getRequest().getBodyAsString()));
        router.add(
                RequestMethod.DELETE,
                "/proxies/{name}",
                (admin, serveEvent, pathParams) -> deleteProxy(pathParams.get("name")));
        router.add(
                RequestMethod.POST,
                "/proxies/{name}/toxics",
                (admin, serveEvent, pathParams) ->
                        addToxic(pathParams.get("name"), serveEvent.getRequest().getBodyAsString()));
        router.add(
                RequestMethod.DELETE,
                "/proxies/{name}/toxics/{toxicName}",
                (admin, serveEvent, pathParams) -> deleteToxic(pathParams.get("name"), pathParams.get("toxicName")));
        router.add(RequestMethod.POST, "/proxies/reset", (admin, serveEvent, pathParams) -> resetAll());
    }

    private ResponseDefinition listProxiesJson() {
        try {
            List<Map<String, Object>> result = new ArrayList<>();
            for (TcpNetEmProxy proxy : registry.list()) {
                result.add(proxyToMap(proxy));
            }
            return jsonResponse(200, MAPPER.writeValueAsString(result));
        } catch (IOException e) {
            return errorResponse(500, e.getMessage());
        }
    }

    private ResponseDefinition getProxyJson(String name) {
        Optional<TcpNetEmProxy> proxy = registry.get(name);
        if (proxy.isEmpty()) {
            return errorResponse(404, "Proxy not found: " + name);
        }
        try {
            return jsonResponse(200, MAPPER.writeValueAsString(proxyToMap(proxy.get())));
        } catch (IOException e) {
            return errorResponse(500, e.getMessage());
        }
    }

    private ResponseDefinition createProxy(String body) {
        try {
            NetEmProxyConfig config = parseProxyConfig(body);
            TcpNetEmProxy proxy = registry.register(config);
            return jsonResponse(201, MAPPER.writeValueAsString(proxyToMap(proxy)));
        } catch (IOException | IllegalArgumentException e) {
            return errorResponse(400, "Invalid proxy payload: " + e.getMessage());
        }
    }

    private ResponseDefinition updateProxy(String name, String body) {
        if ("reset".equalsIgnoreCase(name)) {
            return resetAll();
        }
        Optional<TcpNetEmProxy> opt = registry.get(name);
        if (opt.isEmpty()) {
            return errorResponse(404, "Proxy not found: " + name);
        }
        try {
            Map<?, ?> map = MAPPER.readValue(body, Map.class);
            if (map.containsKey("enabled") && map.get("enabled") instanceof Boolean b) {
                opt.get().setEnabled(b);
            }
            return jsonResponse(200, MAPPER.writeValueAsString(proxyToMap(opt.get())));
        } catch (IOException | IllegalArgumentException e) {
            return errorResponse(400, "Invalid update payload: " + e.getMessage());
        }
    }

    private ResponseDefinition deleteProxy(String name) {
        if (registry.remove(name)) {
            return ResponseDefinitionBuilder.responseDefinition()
                    .withStatus(204)
                    .build();
        }
        return errorResponse(404, "Proxy not found: " + name);
    }

    private ResponseDefinition addToxic(String proxyName, String body) {
        Optional<TcpNetEmProxy> opt = registry.get(proxyName);
        if (opt.isEmpty()) {
            return errorResponse(404, "Proxy not found: " + proxyName);
        }
        try {
            NetEmToxicConfig toxic = MAPPER.readValue(body, NetEmToxicConfig.class);
            opt.get().addToxic(toxic);
            return jsonResponse(200, MAPPER.writeValueAsString(toxic));
        } catch (IOException | IllegalArgumentException e) {
            return errorResponse(400, "Invalid toxic payload: " + e.getMessage());
        }
    }

    private ResponseDefinition deleteToxic(String proxyName, String toxicName) {
        Optional<TcpNetEmProxy> opt = registry.get(proxyName);
        if (opt.isEmpty()) {
            return errorResponse(404, "Proxy not found: " + proxyName);
        }
        if (opt.get().removeToxic(toxicName)) {
            return ResponseDefinitionBuilder.responseDefinition()
                    .withStatus(204)
                    .build();
        }
        return errorResponse(404, "Toxic not found: " + toxicName);
    }

    private ResponseDefinition applyPreset(String proxyName, String presetName) {
        Optional<TcpNetEmProxy> opt = registry.get(proxyName);
        if (opt.isEmpty()) {
            return errorResponse(404, "Proxy not found: " + proxyName);
        }
        try {
            opt.get().applyPreset(presetName);
            return jsonResponse(200, MAPPER.writeValueAsString(proxyToMap(opt.get())));
        } catch (IOException | IllegalArgumentException e) {
            return errorResponse(400, e.getMessage());
        }
    }

    private ResponseDefinition resetAll() {
        registry.resetAll();
        return jsonResponse(200, "{\"status\":\"RESET_ALL_SUCCESS\"}");
    }

    private ResponseDefinition listToxiproxyMap() {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            for (TcpNetEmProxy proxy : registry.list()) {
                map.put(proxy.getConfig().getName(), proxyToToxiproxyMap(proxy));
            }
            return jsonResponse(200, MAPPER.writeValueAsString(map));
        } catch (IOException e) {
            return errorResponse(500, e.getMessage());
        }
    }

    private ResponseDefinition getToxiproxyDetails(String name) {
        Optional<TcpNetEmProxy> proxy = registry.get(name);
        if (proxy.isEmpty()) {
            return errorResponse(404, "Proxy not found: " + name);
        }
        try {
            return jsonResponse(200, MAPPER.writeValueAsString(proxyToToxiproxyMap(proxy.get())));
        } catch (IOException e) {
            return errorResponse(500, e.getMessage());
        }
    }

    private Map<String, Object> proxyToMap(TcpNetEmProxy proxy) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", proxy.getConfig().getName());
        map.put("listenPort", proxy.getPort());
        map.put("targetHost", proxy.getConfig().getTargetHost());
        map.put("targetPort", proxy.getConfig().getTargetPort());
        map.put("enabled", proxy.getConfig().isEnabled());
        map.put("stats", proxy.getStats());
        map.put("toxics", proxy.getToxics());
        return map;
    }

    private Map<String, Object> proxyToToxiproxyMap(TcpNetEmProxy proxy) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", proxy.getConfig().getName());
        map.put("listen", "127.0.0.1:" + proxy.getPort());
        map.put(
                "upstream",
                proxy.getConfig().getTargetHost() + ":" + proxy.getConfig().getTargetPort());
        map.put("enabled", proxy.getConfig().isEnabled());
        map.put("toxics", proxy.getToxics());
        return map;
    }

    private NetEmProxyConfig parseProxyConfig(String body) throws IOException {
        Map<?, ?> map = MAPPER.readValue(body, Map.class);
        String name = (String) map.get("name");
        int listenPort = 0;
        if (map.containsKey("listenPort") && map.get("listenPort") instanceof Number num) {
            listenPort = num.intValue();
        } else if (map.containsKey("listen") && map.get("listen") instanceof String listenStr) {
            listenPort = parsePort(listenStr);
        }

        String targetHost = "127.0.0.1";
        int targetPort = 8080;
        if (map.containsKey("targetHost") && map.get("targetHost") instanceof String h) {
            targetHost = h;
        }
        if (map.containsKey("targetPort") && map.get("targetPort") instanceof Number num) {
            targetPort = num.intValue();
        } else if (map.containsKey("upstream") && map.get("upstream") instanceof String upstreamStr) {
            String[] parts = upstreamStr.split(":");
            targetHost = parts[0];
            targetPort = Integer.parseInt(parts[1]);
        }

        Boolean enabled = map.get("enabled") instanceof Boolean b ? b : Boolean.TRUE;
        return new NetEmProxyConfig(name, listenPort, targetHost, targetPort, enabled);
    }

    private int parsePort(String hostPort) {
        int idx = hostPort.lastIndexOf(':');
        if (idx != -1) {
            return Integer.parseInt(hostPort.substring(idx + 1).trim());
        }
        return Integer.parseInt(hostPort.trim());
    }

    private ResponseDefinition getJournalJson(Request request) {
        int limit = 100;
        if (request.queryParameter("limit") != null
                && request.queryParameter("limit").isPresent()) {
            try {
                limit = Integer.parseInt(request.queryParameter("limit").firstValue());
            } catch (NumberFormatException ignored) {
            }
        }
        String correlationId = request.queryParameter("correlationId") != null
                        && request.queryParameter("correlationId").isPresent()
                ? request.queryParameter("correlationId").firstValue()
                : null;
        String proxy = request.queryParameter("proxy") != null
                        && request.queryParameter("proxy").isPresent()
                ? request.queryParameter("proxy").firstValue()
                : null;
        Boolean impaired = null;
        if (request.queryParameter("impaired") != null
                && request.queryParameter("impaired").isPresent()) {
            impaired = Boolean.parseBoolean(request.queryParameter("impaired").firstValue());
        }

        List<NetEmJournalEntry> entries = registry.getJournal().query(limit, proxy, correlationId, impaired);
        try {
            return jsonResponse(200, MAPPER.writeValueAsString(entries));
        } catch (IOException e) {
            return errorResponse(500, e.getMessage());
        }
    }

    private ResponseDefinition clearJournal() {
        registry.getJournal().clear();
        return jsonResponse(200, "{\"status\":\"cleared\"}");
    }

    private static ResponseDefinition jsonResponse(int status, String body) {
        return ResponseDefinitionBuilder.responseDefinition()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body)
                .build();
    }

    private static ResponseDefinition errorResponse(int status, String message) {
        return ResponseDefinitionBuilder.responseDefinition()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":\"" + message.replace("\"", "\\\"") + "\"}")
                .build();
    }
}
