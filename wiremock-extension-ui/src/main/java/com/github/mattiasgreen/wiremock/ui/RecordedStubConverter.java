package com.github.mattiasgreen.wiremock.ui;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ValueNode;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.common.Gzip;
import com.github.tomakehurst.wiremock.common.Metadata;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Converts recorded {@link ServeEvent} instances into clean, production-grade {@link StubMapping}
 * definitions on the server side, with automated Base64/FML unfolding, JSONPath extraction, and
 * volatile field pruning.
 */
public final class RecordedStubConverter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    public static final Set<String> DEFAULT_EXCLUDED_JSON_PATHS = Set.of(
            "$.CORRELATION_ID",
            "$.REQUEST_TIMESTAMP",
            "$.X-Correlation-Id",
            "$.traceparent",
            "CORRELATION_ID",
            "REQUEST_TIMESTAMP");

    public record SnapshotOptions(Set<String> excludedJsonPaths, boolean useEqualToJson) {
        public static SnapshotOptions defaults() {
            return new SnapshotOptions(DEFAULT_EXCLUDED_JSON_PATHS, false);
        }

        public boolean isExcluded(String fullPath, String key) {
            Set<String> allExcluded = new HashSet<>(DEFAULT_EXCLUDED_JSON_PATHS);
            if (excludedJsonPaths != null) {
                allExcluded.addAll(excludedJsonPaths);
            }
            for (String rule : allExcluded) {
                if (rule == null || rule.isBlank()) {
                    continue;
                }
                String trimmed = rule.trim();
                if (trimmed.startsWith("$")) {
                    if (trimmed.equalsIgnoreCase(fullPath)) {
                        return true;
                    }
                    if (trimmed.endsWith(".*")) {
                        String prefix = trimmed.substring(0, trimmed.length() - 2);
                        if (fullPath.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
                            return true;
                        }
                    }
                } else {
                    if (trimmed.equalsIgnoreCase(key)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    public record LeafPath(String path, String valueString) {}

    private RecordedStubConverter() {}

    /**
     * Converts a recorded {@link ServeEvent} into a configured {@link StubMapping}.
     *
     * @param event the serve event recorded during live proxying
     * @param targetProject project metadata tag
     * @param options configuration options for field pruning and matcher strategy
     * @return the generated StubMapping, or null if the response was invalid
     */
    public static StubMapping convert(ServeEvent event, String targetProject, SnapshotOptions options) {
        if (event == null || event.getResponse() == null || event.getResponse().getStatus() <= 0) {
            return null;
        }

        SnapshotOptions opts = options != null ? options : SnapshotOptions.defaults();
        String method = event.getRequest().getMethod().getName();
        String url = event.getRequest().getUrl();

        RequestPatternBuilder reqPattern =
                RequestPatternBuilder.newRequestPattern(event.getRequest().getMethod(), WireMock.urlEqualTo(url));

        // Process Request Body Matchers
        byte[] reqBodyBytes = event.getRequest().getBody();
        String reqBodyStr = event.getRequest().getBodyAsString();

        if (reqBodyBytes != null && reqBodyBytes.length > 0) {
            JsonNode normalizedJson = tryParseAndNormalizeJson(reqBodyBytes);
            if (normalizedJson != null) {
                if (opts.useEqualToJson()) {
                    JsonNode pruned = pruneExcludedProperties(normalizedJson.deepCopy(), opts, "$", "");
                    try {
                        String prettyJson =
                                OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(pruned);
                        reqPattern.withRequestBody(WireMock.equalToJson(prettyJson, true, true));
                    } catch (JsonProcessingException e) {
                        reqPattern.withRequestBody(WireMock.equalToJson(pruned.toString(), true, true));
                    }
                } else {
                    List<LeafPath> leafPaths = new ArrayList<>();
                    collectLeafPaths(normalizedJson, "$", "", opts, leafPaths);
                    if (!leafPaths.isEmpty()) {
                        for (LeafPath lp : leafPaths) {
                            reqPattern.withRequestBody(
                                    WireMock.matchingJsonPath(lp.path(), WireMock.equalTo(lp.valueString())));
                        }
                    } else {
                        // Fallback if all paths were pruned
                        reqPattern.withRequestBody(WireMock.equalToJson(normalizedJson.toString(), true, true));
                    }
                }
            } else if (reqBodyStr != null && !reqBodyStr.isBlank()) {
                reqPattern.withRequestBody(WireMock.equalTo(reqBodyStr));
            } else {
                reqPattern.withRequestBody(WireMock.binaryEqualTo(reqBodyBytes));
            }
        }

        // Process Response Definition
        ResponseDefinitionBuilder respDef = ResponseDefinitionBuilder.responseDefinition()
                .withStatus(event.getResponse().getStatus());

        byte[] respBodyBytes = event.getResponse().getBody();
        String respBodyStr = null;
        if (respBodyBytes != null && respBodyBytes.length > 0) {
            if (Gzip.isGzipped(respBodyBytes)) {
                respBodyStr = Gzip.unGzipToString(respBodyBytes);
            } else {
                respBodyStr = event.getResponse().getBodyAsString();
            }
        }

        if (respBodyStr != null && !respBodyStr.isEmpty()) {
            JsonNode normalizedResJson = tryParseAndNormalizeJson(respBodyStr.getBytes(StandardCharsets.UTF_8));
            if (normalizedResJson != null) {
                try {
                    respDef.withBody(
                            OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(normalizedResJson));
                } catch (JsonProcessingException e) {
                    respDef.withBody(respBodyStr);
                }
            } else {
                respDef.withBody(respBodyStr);
            }
        }

        // Response Headers (filter hop-by-hop and internal tracing headers)
        if (event.getResponse().getHeaders() != null) {
            for (HttpHeader header : event.getResponse().getHeaders().all()) {
                String name = header.key();
                if (!name.equalsIgnoreCase("Transfer-Encoding")
                        && !name.equalsIgnoreCase("Content-Length")
                        && !name.equalsIgnoreCase("Content-Encoding")
                        && !name.equalsIgnoreCase("Connection")
                        && !name.equalsIgnoreCase("Matched-Stub-Id")
                        && !name.equalsIgnoreCase("Matched-Stub-Name")) {
                    respDef.withHeader(name, header.firstValue());
                }
            }
        }

        boolean isCasualReq = url != null && url.startsWith("/__casual/services/");
        String casualErr = event.getResponse().getHeaders() != null
                        && event.getResponse()
                                .getHeaders()
                                .getHeader("X-Casual-Error-State")
                                .isPresent()
                ? event.getResponse()
                        .getHeaders()
                        .getHeader("X-Casual-Error-State")
                        .firstValue()
                : (event.getResponse().getStatus() >= 200 && event.getResponse().getStatus() < 300
                        ? "OK"
                        : "TPESVCFAIL");

        String stubName = isCasualReq
                ? String.format(Locale.ROOT, "[RECORDED CASUAL %s] %s", casualErr, url)
                : String.format(
                        Locale.ROOT,
                        "[RECORDED %s %d] %s",
                        method,
                        event.getResponse().getStatus(),
                        url);

        StubMapping stub = new StubMapping(reqPattern.build(), respDef.build());
        stub.setId(java.util.UUID.randomUUID());
        stub.setName(stubName);
        stub.setPriority(5);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", "recorded-proxy");
        meta.put("project", targetProject != null && !targetProject.isBlank() ? targetProject : "Recorded Traffic");
        meta.put("mode", "static");
        meta.put("recordedAt", Instant.now().toString());
        if (isCasualReq) {
            meta.put("protocol", "casual");
        }
        stub.setMetadata(new Metadata(meta));

        return stub;
    }

    /**
     * Parses byte payload as JSON and recursively unfolds any nested Base64-encoded JSON strings.
     */
    public static JsonNode tryParseAndNormalizeJson(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            JsonNode node = OBJECT_MAPPER.readTree(bytes);
            if (node == null || node.isMissingNode() || node.isNull()) {
                return null;
            }
            if (node.isContainerNode()) {
                return deepUnfoldBase64(node);
            }
            return null;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private static JsonNode deepUnfoldBase64(JsonNode node) {
        if (node instanceof ObjectNode objNode) {
            ObjectNode unfolded = OBJECT_MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = objNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = field.getKey();
                JsonNode val = field.getValue();
                if (val.isTextual()) {
                    JsonNode nestedJson = tryDecodeBase64Json(val.asText());
                    if (nestedJson != null) {
                        unfolded.set(key, deepUnfoldBase64(nestedJson));
                    } else {
                        unfolded.set(key, val);
                    }
                } else if (val.isContainerNode()) {
                    unfolded.set(key, deepUnfoldBase64(val));
                } else {
                    unfolded.set(key, val);
                }
            }
            return unfolded;
        } else if (node instanceof ArrayNode arrNode) {
            ArrayNode unfoldedArr = OBJECT_MAPPER.createArrayNode();
            for (JsonNode item : arrNode) {
                if (item.isTextual()) {
                    JsonNode nestedJson = tryDecodeBase64Json(item.asText());
                    if (nestedJson != null) {
                        unfoldedArr.add(deepUnfoldBase64(nestedJson));
                    } else {
                        unfoldedArr.add(item);
                    }
                } else if (item.isContainerNode()) {
                    unfoldedArr.add(deepUnfoldBase64(item));
                } else {
                    unfoldedArr.add(item);
                }
            }
            return unfoldedArr;
        }
        return node;
    }

    private static JsonNode tryDecodeBase64Json(String text) {
        if (text == null || text.length() < 4) {
            return null;
        }
        String trimmed = text.trim();
        try {
            byte[] decoded = Base64.getDecoder().decode(trimmed);
            if (decoded.length < 2) {
                return null;
            }
            JsonNode candidate = OBJECT_MAPPER.readTree(decoded);
            if (candidate != null && candidate.isContainerNode()) {
                return candidate;
            }
        } catch (IllegalArgumentException | IOException ignored) {
        }
        return null;
    }

    private static void collectLeafPaths(
            JsonNode node, String path, String key, SnapshotOptions options, List<LeafPath> leaves) {
        if (key != null && !key.isEmpty() && options.isExcluded(path, key)) {
            return; // Prune branch immediately
        }

        if (node == null || node.isNull()) {
            leaves.add(new LeafPath(path, "null"));
            return;
        }

        if (node instanceof ValueNode vn) {
            leaves.add(new LeafPath(path, vn.asText()));
            return;
        }

        if (node instanceof ArrayNode arr) {
            if (arr.isEmpty()) {
                leaves.add(new LeafPath(path, "[]"));
            } else {
                for (int i = 0; i < arr.size(); i++) {
                    collectLeafPaths(arr.get(i), path + "[" + i + "]", String.valueOf(i), options, leaves);
                }
            }
            return;
        }

        if (node instanceof ObjectNode obj) {
            if (obj.isEmpty()) {
                leaves.add(new LeafPath(path, "{}"));
            } else {
                Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    String propKey = field.getKey();
                    String childPath = IDENTIFIER_PATTERN.matcher(propKey).matches()
                            ? path + "." + propKey
                            : path + "['" + propKey + "']";
                    collectLeafPaths(field.getValue(), childPath, propKey, options, leaves);
                }
            }
        }
    }

    private static JsonNode pruneExcludedProperties(JsonNode node, SnapshotOptions options, String path, String key) {
        if (node instanceof ObjectNode obj) {
            ObjectNode cleaned = OBJECT_MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String propKey = field.getKey();
                String childPath = IDENTIFIER_PATTERN.matcher(propKey).matches()
                        ? path + "." + propKey
                        : path + "['" + propKey + "']";
                if (!options.isExcluded(childPath, propKey)) {
                    cleaned.set(propKey, pruneExcludedProperties(field.getValue(), options, childPath, propKey));
                }
            }
            return cleaned;
        } else if (node instanceof ArrayNode arr) {
            ArrayNode cleanedArr = OBJECT_MAPPER.createArrayNode();
            for (int i = 0; i < arr.size(); i++) {
                cleanedArr.add(pruneExcludedProperties(arr.get(i), options, path + "[" + i + "]", String.valueOf(i)));
            }
            return cleanedArr;
        }
        return node;
    }
}
