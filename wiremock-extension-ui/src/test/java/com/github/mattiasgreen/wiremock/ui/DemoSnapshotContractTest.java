package com.github.mattiasgreen.wiremock.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract test verifying {@link DemoSnapshotGenerator} and the checked-in
 * {@code src/main/resources/ui/modules/demo-snapshot.js} module.
 */
public class DemoSnapshotContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String EXPORT_PREFIX = "export const DEMO_SNAPSHOT = ";

    @Test
    @DisplayName("DemoSnapshotGenerator should produce valid DEMO_SNAPSHOT ES6 module source")
    void testGeneratorProducesValidSnapshotSource() throws Exception {
        String generated = DemoSnapshotGenerator.generateSnapshotModuleSource();
        assertThat(generated).contains(EXPORT_PREFIX);

        JsonNode root = parseSnapshotJson(generated);
        assertSnapshotShape(root);
    }

    @Test
    @DisplayName("Checked-in demo-snapshot.js should contain valid live-captured DEMO_SNAPSHOT payload")
    void testCheckedInDemoSnapshotModuleContract() throws Exception {
        Path snapshotPath = Path.of("src/main/resources/ui/modules/demo-snapshot.js");
        if (!Files.isRegularFile(snapshotPath)) {
            snapshotPath = Path.of("wiremock-extension-ui/src/main/resources/ui/modules/demo-snapshot.js");
        }
        assertThat(Files.isRegularFile(snapshotPath))
                .as("demo-snapshot.js must exist on disk")
                .isTrue();

        String content = Files.readString(snapshotPath, StandardCharsets.UTF_8);
        assertThat(content).contains(EXPORT_PREFIX);

        JsonNode root = parseSnapshotJson(content);
        assertSnapshotShape(root);
    }

    private static JsonNode parseSnapshotJson(String moduleSource) throws Exception {
        int idx = moduleSource.indexOf(EXPORT_PREFIX);
        assertThat(idx).as("Missing DEMO_SNAPSHOT export").isGreaterThanOrEqualTo(0);

        String jsonPart = moduleSource.substring(idx + EXPORT_PREFIX.length()).trim();
        if (jsonPart.endsWith(";")) {
            jsonPart = jsonPart.substring(0, jsonPart.length() - 1).trim();
        }
        return MAPPER.readTree(jsonPart);
    }

    private static void assertSnapshotShape(JsonNode root) {
        assertThat(root.isObject()).isTrue();

        // 1. Mappings: nested JSON/Base64/XML stub + parked priority >= 100 stub + scenario stub
        JsonNode mappings = root.get("mappings");
        assertThat(mappings).isNotNull();
        assertThat(mappings.isArray()).isTrue();
        assertThat(mappings.size()).isGreaterThanOrEqualTo(13);

        boolean hasNestedPolyglotStub = false;
        boolean hasParkedStub = false;
        boolean hasScenarioStub = false;
        boolean hasCasualProxyStub = false;
        boolean hasKafkaBridgeStub = false;
        boolean hasAmqpBridgeStub = false;
        for (JsonNode mapping : mappings) {
            String body = mapping.path("response").path("body").asText("");
            if (body.contains("complianceAudit") && body.contains("encodedManifest") && body.contains("auditReport")) {
                hasNestedPolyglotStub = true;
            }
            if (mapping.has("priority") && mapping.get("priority").asInt() >= 100) {
                hasParkedStub = true;
            }
            if ("OrderFulfillment".equals(mapping.path("scenarioName").asText(""))) {
                hasScenarioStub = true;
            }
            String protocol = mapping.path("metadata").path("protocol").asText("");
            String mode = mapping.path("metadata").path("mode").asText("");
            if ("casual".equals(protocol) && "proxy".equals(mode)) {
                hasCasualProxyStub = true;
            }
            JsonNode asyncMeta = mapping.path("metadata").path("async");
            if ("proxy".equals(asyncMeta.path("mode").asText(""))) {
                if ("kafka".equals(asyncMeta.path("protocol").asText(""))
                        && "downstream-kafka".equals(asyncMeta.path("broker").asText(""))) {
                    hasKafkaBridgeStub = true;
                }
                if ("amqp".equals(asyncMeta.path("protocol").asText(""))
                        && "downstream-artemis".equals(asyncMeta.path("broker").asText(""))) {
                    hasAmqpBridgeStub = true;
                }
            }
        }
        assertThat(hasNestedPolyglotStub)
                .as("Snapshot mappings must include nested JSON/Base64/XML stub")
                .isTrue();
        assertThat(hasParkedStub)
                .as("Snapshot mappings must include parked priority >= 100 stub")
                .isTrue();
        assertThat(hasScenarioStub)
                .as("Snapshot mappings must include OrderFulfillment scenario stub")
                .isTrue();
        assertThat(hasCasualProxyStub)
                .as("Snapshot mappings must include Casual proxy stub")
                .isTrue();
        assertThat(hasKafkaBridgeStub)
                .as("Snapshot mappings must include two-broker Kafka bridge stub")
                .isTrue();
        assertThat(hasAmqpBridgeStub)
                .as("Snapshot mappings must include two-broker AMQP bridge stub")
                .isTrue();

        // 2. Requests: matched + unmatched + proxied + async bridged + resolved hostnames & IP fallback
        JsonNode requests = root.get("requests");
        assertThat(requests).isNotNull();
        assertThat(requests.isArray()).isTrue();
        assertThat(requests.size()).isGreaterThanOrEqualTo(11);

        boolean hasMatched = false;
        boolean hasUnmatched = false;
        boolean hasSourceIp = false;
        boolean hasNamedHost = false;
        boolean hasUnresolvedFallbackIp = false;
        boolean hasKafkaBridged = false;
        boolean hasAmqpBridged = false;
        boolean hasCasualProxied = false;
        for (JsonNode req : requests) {
            if (req.path("wasMatched").asBoolean(false)) {
                hasMatched = true;
            } else {
                hasUnmatched = true;
            }
            String srcIp = req.path("sourceIp").asText("");
            String srcHost = req.path("sourceHost").asText("");
            if (!srcIp.isBlank()) {
                hasSourceIp = true;
            }
            if ("system-a.internal".equals(srcHost) || "system-b.internal".equals(srcHost)) {
                hasNamedHost = true;
            }
            if ("10.42.8.19".equals(srcIp) && srcHost.isBlank()) {
                hasUnresolvedFallbackIp = true;
            }
            String url = req.path("request").path("url").asText("");
            boolean isProxy = req.path("responseDefinition").has("proxyBaseUrl");
            if (url.contains("/__async/kafka/events.audit") && isProxy) {
                hasKafkaBridged = true;
            }
            if (url.contains("/__async/amqp/payments.settled") && isProxy) {
                hasAmqpBridged = true;
            }
            if (url.contains("/__casual/services/inventoryCheck") && isProxy) {
                hasCasualProxied = true;
            }
        }
        assertThat(hasMatched)
                .as("Snapshot requests must include matched request")
                .isTrue();
        assertThat(hasUnmatched)
                .as("Snapshot requests must include unmatched 404 request")
                .isTrue();
        assertThat(hasSourceIp)
                .as("Snapshot requests must include enriched sourceIp")
                .isTrue();
        assertThat(hasNamedHost)
                .as("Snapshot requests must include resolved source hostnames (system-a/system-b)")
                .isTrue();
        assertThat(hasUnresolvedFallbackIp)
                .as("Snapshot requests must include unresolved IP fallback (10.42.8.19)")
                .isTrue();
        assertThat(hasKafkaBridged)
                .as("Snapshot requests must include bridged Kafka ServeEvent")
                .isTrue();
        assertThat(hasAmqpBridged)
                .as("Snapshot requests must include bridged AMQP ServeEvent")
                .isTrue();
        assertThat(hasCasualProxied)
                .as("Snapshot requests must include proxied Casual ServeEvent")
                .isTrue();

        // 3. Scenarios
        JsonNode scenarios = root.get("scenarios");
        assertThat(scenarios).isNotNull();
        assertThat(scenarios.isArray()).isTrue();
        assertThat(scenarios.size()).isGreaterThanOrEqualTo(1);
        assertThat(scenarios.get(0).path("name").asText("")).isEqualTo("OrderFulfillment");

        // 4. NetEm proxies & toxics
        JsonNode netemProxies = root.get("netemProxies");
        assertThat(netemProxies).isNotNull();
        assertThat(netemProxies.isArray()).isTrue();
        assertThat(netemProxies.size()).isGreaterThanOrEqualTo(1);
        assertThat(netemProxies.get(0).path("toxics").isArray()).isTrue();
        assertThat(netemProxies.get(0).path("toxics").size()).isGreaterThanOrEqualTo(1);

        // 5. Casual config
        JsonNode casualConfig = root.get("casualConfig");
        assertThat(casualConfig).isNotNull();
        assertThat(casualConfig.isObject()).isTrue();
        assertThat(casualConfig.path("enabled").asBoolean(false)).isTrue();
        assertThat(casualConfig.path("port").asInt(0)).isEqualTo(7772);

        // 6. Async connections
        JsonNode asyncConnections = root.get("asyncConnections");
        assertThat(asyncConnections).isNotNull();
        assertThat(asyncConnections.isArray()).isTrue();
        assertThat(asyncConnections.size()).isGreaterThanOrEqualTo(2);

        // 7. Async destinations
        JsonNode asyncDestinations = root.get("asyncDestinations");
        assertThat(asyncDestinations).isNotNull();
        assertThat(asyncDestinations.isArray()).isTrue();
        assertThat(asyncDestinations.size()).isGreaterThanOrEqualTo(2);

        // 8. Async messages keyed by protocol/destination
        JsonNode asyncMessages = root.get("asyncMessages");
        assertThat(asyncMessages).isNotNull();
        assertThat(asyncMessages.isObject()).isTrue();
        assertThat(asyncMessages.size()).isGreaterThanOrEqualTo(2);
        assertThat(asyncMessages.path("kafka/orders.events").isArray()).isTrue();
        assertThat(asyncMessages.path("kafka/orders.events").size()).isGreaterThanOrEqualTo(1);
        assertThat(asyncMessages.path("amqp/orders.inbound").isArray()).isTrue();
        assertThat(asyncMessages.path("amqp/orders.inbound").size()).isGreaterThanOrEqualTo(1);
    }
}
