package com.github.mattiasgreen.wiremock.ui;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.mattiasgreen.wiremock.async.admin.AsyncExtensionFactory;
import com.github.mattiasgreen.wiremock.async.engine.AsyncEngine;
import com.github.mattiasgreen.wiremock.casual.CasualExtensionFactory;
import com.github.mattiasgreen.wiremock.casual.CasualRegistry;
import com.github.mattiasgreen.wiremock.casual.CasualWireMockGateway;
import com.github.mattiasgreen.wiremock.netem.admin.TcpNetEmExtensionFactory;
import com.github.mattiasgreen.wiremock.netem.engine.NetEmEngine;
import com.github.mattiasgreen.wiremock.openapi.OpenApiExtensionFactory;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Generates {@code demo-snapshot.js} from a real, isolated in-process {@link WireMockServer}
 * configured with all extension factories, seeded stubs, HTTP/Casual/Async/NetEm traffic,
 * and normalized ports.
 */
public final class DemoSnapshotGenerator {

    private static final String HEADER_COMMENT =
            "// Auto-generated from live WireMockServer by DemoSnapshotGenerator — do not hand-edit.\n";
    private static final String DEFAULT_OUTPUT_PATH =
            "wiremock-extension-ui/src/main/resources/ui/modules/demo-snapshot.js";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DefaultPrettyPrinter PRETTY_PRINTER = new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withArrayIndenter(new DefaultIndenter("  ", "\n"));

    private DemoSnapshotGenerator() {}

    public static void main(String[] args) throws Exception {
        Path outputPath = Path.of(args.length > 0 && !args[0].isBlank() ? args[0] : DEFAULT_OUTPUT_PATH);
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        String source = generateSnapshotModuleSource();
        Files.writeString(outputPath, source, StandardCharsets.UTF_8);
        System.exit(0);
    }

    public static String generateSnapshotModuleSource() {
        NetEmEngine netEmEngine = new NetEmEngine();
        CasualRegistry casualRegistry = CasualRegistry.create();
        AsyncEngine asyncEngine = AsyncEngine.create();

        WireMockServer wireMockServer = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .extensions(
                        new UiExtensionFactory(),
                        new OpenApiExtensionFactory(),
                        new TcpNetEmExtensionFactory(netEmEngine),
                        new CasualExtensionFactory(casualRegistry),
                        new AsyncExtensionFactory(asyncEngine)));

        CasualWireMockGateway casualGateway = null;

        try {
            wireMockServer.start();

            casualGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            casualGateway.start();
            casualRegistry.setGateway(casualGateway);

            asyncEngine.startAmqpServer(0);

            int wmPort = wireMockServer.port();
            int casualPort = casualGateway.getPort();
            int amqpPort = asyncEngine.getAmqpServerPort();

            seedStubs(wireMockServer);

            try (HttpClient httpClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build()) {
                String baseUrl = wireMockServer.baseUrl();

                int kafkaPort = startKafkaSink(httpClient, baseUrl);
                int netEmListenPort = configureNetEmProxy(httpClient, baseUrl, wmPort);

                fireTraffic(httpClient, baseUrl, netEmListenPort);
                seedAsyncMessages(httpClient, baseUrl);

                JsonNode mappingsResp = getJson(httpClient, baseUrl + "/__admin/mappings");
                JsonNode requestsResp = getJson(httpClient, baseUrl + "/__admin/ui/api/requests?limit=50");
                JsonNode scenariosResp = getJson(httpClient, baseUrl + "/__admin/scenarios");
                JsonNode netemProxiesNode = getJson(httpClient, baseUrl + "/__admin/tcp-netem/proxies");
                JsonNode casualConfigNode = getJson(httpClient, baseUrl + "/__admin/casual/config");
                JsonNode asyncDestinationsResp = getJson(httpClient, baseUrl + "/__admin/async/destinations");

                ArrayNode asyncDestinations = extractArray(asyncDestinationsResp, "destinations");
                ObjectNode asyncMessages = MAPPER.createObjectNode();
                for (JsonNode dest : asyncDestinations) {
                    String protocol = dest.path("protocol").asText("");
                    String name = dest.path("name").asText("");
                    if (!protocol.isEmpty() && !name.isEmpty()) {
                        String msgUrl = baseUrl
                                + "/__admin/async/destinations/"
                                + encodePathSegment(protocol)
                                + "/"
                                + encodePathSegment(name)
                                + "/messages";
                        JsonNode msgResp = getJson(httpClient, msgUrl);
                        asyncMessages.set(protocol + "/" + name, extractArray(msgResp, "messages"));
                    }
                }

                // Register broker connections after destination discovery so unreachable endpoints do not trigger
                // client timeouts
                registerBrokerConnections(httpClient, baseUrl, kafkaPort, amqpPort, casualPort);
                JsonNode asyncConnectionsResp = getJson(httpClient, baseUrl + "/__admin/async/connections");

                ObjectNode snapshot = MAPPER.createObjectNode();
                snapshot.set("mappings", extractArray(mappingsResp, "mappings"));
                snapshot.set("requests", extractArray(requestsResp, "requests"));
                snapshot.set("scenarios", extractArray(scenariosResp, "scenarios"));
                snapshot.set(
                        "netemProxies",
                        netemProxiesNode != null && netemProxiesNode.isArray()
                                ? netemProxiesNode
                                : MAPPER.createArrayNode());
                snapshot.set(
                        "casualConfig",
                        casualConfigNode != null && casualConfigNode.isObject()
                                ? casualConfigNode
                                : MAPPER.createObjectNode());
                snapshot.set("asyncConnections", extractArray(asyncConnectionsResp, "connections"));
                snapshot.set("asyncDestinations", asyncDestinations);
                snapshot.set("asyncMessages", asyncMessages);

                JsonNode normalized =
                        normalizePorts(snapshot, wmPort, casualPort, netEmListenPort, kafkaPort, amqpPort);
                String formattedJson = MAPPER.writer(PRETTY_PRINTER).writeValueAsString(normalized);
                return HEADER_COMMENT + "export const DEMO_SNAPSHOT = " + formattedJson + ";\n";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while generating demo snapshot", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate demo snapshot: " + e.getMessage(), e);
        } finally {
            if (casualGateway != null) {
                try {
                    casualGateway.close();
                } catch (Exception ignored) {
                }
            }
            casualRegistry.reset();
            try {
                asyncEngine.getBrokerRegistry().clear();
                asyncEngine.close();
            } catch (Exception ignored) {
            }
            try {
                netEmEngine.close();
            } catch (Exception ignored) {
            }
            if (wireMockServer.isRunning()) {
                wireMockServer.stop();
            }
        }
    }

    private static void seedStubs(WireMockServer server) throws Exception {
        String usersBody = MAPPER.writeValueAsString(List.of(
                Map.of("id", "usr-1", "name", "Ada Lovelace", "role", "ADMIN"),
                Map.of("id", "usr-2", "name", "Alan Turing", "role", "SECURITY"),
                Map.of("id", "usr-3", "name", "Grace Hopper", "role", "DEVELOPER")));

        server.stubFor(get(urlPathEqualTo("/api/v1/users"))
                .withId(UUID.fromString("10000000-0000-4000-8000-000000000001"))
                .withName("List Users")
                .atPriority(5)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json; charset=utf-8")
                        .withBody(usersBody))
                .withMetadata(Map.of("project", "identity-api", "tags", List.of("users", "rest"))));

        String embeddedXml =
                "<auditReport xmlns=\"http://example.com/orders\"><orderId>ORD-9942</orderId><status>VALIDATED</status><checksum>a9f4c82b</checksum></auditReport>";
        String nestedAuditJson =
                MAPPER.writeValueAsString(Map.of("auditor", "order-fsm", "score", 99, "embeddedXml", embeddedXml));
        String encodedManifest = Base64.getEncoder()
                .encodeToString(("{\"manifestVersion\":1,\"orderId\":\"ORD-9942\",\"xmlSummary\":"
                                + MAPPER.writeValueAsString(embeddedXml) + "}")
                        .getBytes(StandardCharsets.UTF_8));
        String orderResponseBody = MAPPER.writer(PRETTY_PRINTER)
                .writeValueAsString(Map.of(
                        "orderId",
                        "ORD-9942",
                        "status",
                        "CREATED",
                        "totalAmount",
                        1250.0,
                        "currency",
                        "EUR",
                        "complianceAudit",
                        nestedAuditJson,
                        "encodedManifest",
                        encodedManifest));

        server.stubFor(post(urlEqualTo("/api/v1/orders"))
                .withId(UUID.fromString("10000000-0000-4000-8000-000000000002"))
                .withName("Create Order (Nested Polyglot Payload)")
                .atPriority(5)
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(matchingJsonPath("$.orderId"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json; charset=utf-8")
                        .withHeader("Location", "/api/v1/orders/ORD-9942")
                        .withBody(orderResponseBody))
                .withMetadata(
                        Map.of("project", "orders-api", "tags", List.of("orders", "polyglot", "nested-payload"))));

        server.stubFor(get(urlEqualTo("/api/v1/orders/ORD-9942/status"))
                .withId(UUID.fromString("10000000-0000-4000-8000-000000000003"))
                .withName("Order Status (Started -> SHIPPED)")
                .atPriority(5)
                .inScenario("OrderFulfillment")
                .whenScenarioStateIs("Started")
                .willSetStateTo("SHIPPED")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json; charset=utf-8")
                        .withBody("{\"orderId\":\"ORD-9942\",\"status\":\"SHIPPED\",\"carrier\":\"NordicExpress\"}"))
                .withMetadata(Map.of("project", "orders-api", "tags", List.of("orders", "fsm", "scenario"))));

        server.stubFor(get(urlEqualTo("/api/v1/orders/ORD-9942/status"))
                .withId(UUID.fromString("10000000-0000-4000-8000-000000000004"))
                .withName("Order Status (SHIPPED -> DELIVERED)")
                .atPriority(5)
                .inScenario("OrderFulfillment")
                .whenScenarioStateIs("SHIPPED")
                .willSetStateTo("DELIVERED")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json; charset=utf-8")
                        .withBody("{\"orderId\":\"ORD-9942\",\"status\":\"DELIVERED\",\"signedBy\":\"A. Lindqvist\"}"))
                .withMetadata(Map.of("project", "orders-api", "tags", List.of("orders", "fsm", "scenario"))));

        server.stubFor(any(urlMatching("/erp/api/.*"))
                .withId(UUID.fromString("10000000-0000-4000-8000-000000000005"))
                .withName("ERP System Live Proxy")
                .atPriority(10)
                .willReturn(aResponse().proxiedFrom("http://erp.internal:9000"))
                .withMetadata(Map.of("project", "erp-system", "mode", "proxy", "tags", List.of("proxy", "erp"))));

        server.stubFor(any(urlMatching("/erp/api/.*"))
                .withId(UUID.fromString("10000000-0000-4000-8000-000000000006"))
                .withName("ERP Offline Guard")
                .atPriority(50)
                .willReturn(aResponse()
                        .withStatus(501)
                        .withHeader("Content-Type", "application/json; charset=utf-8")
                        .withBody("{\"error\":\"Offline guard active for erp-system\"}"))
                .withMetadata(Map.of(
                        "project", "erp-system", "role", "offline-guard", "tags", List.of("offline-guard", "erp"))));

        server.stubFor(delete(urlMatching("/api/v1/users/.*"))
                .withId(UUID.fromString("10000000-0000-4000-8000-000000000007"))
                .withName("Delete User [PARKED]")
                .atPriority(100)
                .willReturn(aResponse().withStatus(204))
                .withMetadata(Map.of("project", "identity-api", "tags", List.of("users", "parked"))));

        server.stubFor(post(urlEqualTo("/__casual/services/echoService"))
                .withId(UUID.fromString("10000000-0000-4000-8000-000000000008"))
                .withName("Casual XATMI: echoService")
                .atPriority(5)
                .willReturn(
                        aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json; charset=utf-8")
                                .withHeader("X-Casual-Service", "echoService")
                                .withHeader("X-Casual-Error-State", "OK")
                                .withBody(
                                        "{\"service\":\"echoService\",\"status\":\"OK\",\"xatmiReturnCode\":0,\"message\":\"Casual service loopback operational\"}"))
                .withMetadata(Map.of(
                        "project",
                        "Casual Gateway",
                        "protocol",
                        "casual",
                        "service",
                        "echoService",
                        "exampleRequest",
                        Map.of(
                                "path",
                                "/__casual/services/echoService",
                                "headers",
                                Map.of(
                                        "Content-Type",
                                        "application/json",
                                        "x-casual-service",
                                        "echoService",
                                        "x-casual-buffer-type",
                                        "JSON"),
                                "body",
                                Map.of("ping", "pong")),
                        "tags",
                        List.of("casual", "xatmi", "echo"))));

        server.stubFor(post(urlEqualTo("/__async/kafka/orders.events"))
                .withId(UUID.fromString("10000000-0000-4000-8000-000000000009"))
                .withName("Kafka Events Sink: orders.events")
                .atPriority(5)
                .willReturn(aResponse()
                        .withStatus(204)
                        .withHeader("X-Async-Ack", "ack")
                        .withHeader("X-Async-Protocol", "kafka"))
                .withMetadata(Map.of(
                        "project",
                        "Async Messaging",
                        "protocol",
                        "kafka",
                        "channel",
                        "orders.events",
                        "async",
                        Map.of("mode", "mock", "protocol", "kafka", "channel", "orders.events", "ackPolicy", "ack"),
                        "tags",
                        List.of("async", "kafka", "events"))));

        server.stubFor(post(urlEqualTo("/__async/amqp/orders.inbound"))
                .withId(UUID.fromString("10000000-0000-4000-8000-000000000010"))
                .withName("AMQP Queue: orders.inbound")
                .atPriority(5)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json; charset=utf-8")
                        .withBody("{\"status\":\"ACKNOWLEDGED\",\"queue\":\"orders.inbound\"}"))
                .withMetadata(Map.of(
                        "project",
                        "Async Messaging",
                        "protocol",
                        "amqp",
                        "channel",
                        "orders.inbound",
                        "async",
                        Map.of("mode", "mock", "protocol", "amqp", "channel", "orders.inbound", "ackPolicy", "ack"),
                        "tags",
                        List.of("async", "amqp", "orders"))));
    }

    private static int startKafkaSink(HttpClient httpClient, String baseUrl) throws Exception {
        JsonNode resp =
                postJson(httpClient, baseUrl + "/__admin/async/sinks/kafka", Map.of("action", "start", "port", 0), 200);
        return resp.path("kafkaSinkPort").asInt(-1);
    }

    private static int configureNetEmProxy(HttpClient httpClient, String baseUrl, int targetPort) throws Exception {
        JsonNode proxyNode = postJson(
                httpClient,
                baseUrl + "/__admin/tcp-netem/proxies",
                Map.of(
                        "name",
                        "wiremock-netem",
                        "listenPort",
                        0,
                        "targetHost",
                        "127.0.0.1",
                        "targetPort",
                        targetPort,
                        "enabled",
                        true),
                201);
        int listenPort = proxyNode.path("listenPort").asInt(0);

        postJson(
                httpClient,
                baseUrl + "/__admin/tcp-netem/proxies/wiremock-netem/toxics",
                Map.of(
                        "name",
                        "downstream_latency",
                        "type",
                        "latency",
                        "stream",
                        "downstream",
                        "toxicity",
                        1.0,
                        "attributes",
                        Map.of("latency", 25, "jitter", 5)),
                200);
        return listenPort;
    }

    private static void fireTraffic(HttpClient httpClient, String baseUrl, int netEmListenPort) throws Exception {
        // 1. Matched GET through NetEm proxy (records both WireMock ServeEvent and NetEm stats/journal)
        String netEmTarget = netEmListenPort > 0 ? "http://127.0.0.1:" + netEmListenPort : baseUrl;
        HttpResponse<String> usersResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(netEmTarget + "/api/v1/users"))
                        .header("X-Correlation-Id", "corr-users-101")
                        .header("X-Forwarded-For", "127.0.0.1")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (usersResp.statusCode() != 200) {
            throw new IllegalStateException("Expected 200 from /api/v1/users, got " + usersResp.statusCode());
        }

        // 2. Matched POST with nested JSON/XML/Base64 payload
        String reqPayload = MAPPER.writeValueAsString(Map.of(
                "orderId",
                "ORD-9942",
                "customer",
                "Nordic Retail AB",
                "rawDocument",
                "<orderRequest><orderId>ORD-9942</orderId><priority>HIGH</priority></orderRequest>"));
        HttpResponse<String> orderResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/v1/orders"))
                        .header("Content-Type", "application/json")
                        .header("X-Correlation-Id", "corr-orders-202")
                        .header("X-Forwarded-For", "127.0.0.1")
                        .POST(HttpRequest.BodyPublishers.ofString(reqPayload))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (orderResp.statusCode() != 201) {
            throw new IllegalStateException("Expected 201 from /api/v1/orders, got " + orderResp.statusCode());
        }

        // 3. Scenario state transition: Started -> SHIPPED
        HttpResponse<String> statusResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/v1/orders/ORD-9942/status"))
                        .header("X-Correlation-Id", "corr-order-fsm-303")
                        .header("X-Forwarded-For", "127.0.0.1")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (statusResp.statusCode() != 200) {
            throw new IllegalStateException(
                    "Expected 200 from /api/v1/orders/ORD-9942/status, got " + statusResp.statusCode());
        }

        // 4. Unmatched 404 request
        HttpResponse<String> missingResp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/v1/missing-route"))
                        .header("X-Correlation-Id", "corr-missing-404")
                        .header("X-Forwarded-For", "127.0.0.1")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (missingResp.statusCode() != 404) {
            throw new IllegalStateException("Expected 404 from /api/v1/missing-route, got " + missingResp.statusCode());
        }

        // 5. Casual XATMI tpcall via Admin API (exercises live Netty gateway + logs Casual ServeEvent)
        postJson(
                httpClient,
                baseUrl + "/__admin/casual/call",
                Map.of("service", "echoService", "bufferType", "JSON", "payload", "{\"ping\":\"pong\"}"),
                200);
    }

    private static void seedAsyncMessages(HttpClient httpClient, String baseUrl) throws Exception {
        String kafkaPayload1 = MAPPER.writer(PRETTY_PRINTER)
                .writeValueAsString(
                        Map.of(
                                "orderId",
                                "ORD-9942",
                                "customer",
                                "Acme Corp",
                                "legacyXmlInvoice",
                                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><invoice id=\"INV-9942\"><total currency=\"EUR\">1250.00</total></invoice>"));
        postJson(
                httpClient,
                baseUrl + "/__admin/async/destinations/kafka/orders.events/messages",
                Map.of(
                        "id",
                        "msg-kafka-9942",
                        "key",
                        "ORD-9942",
                        "correlationId",
                        "corr-kafka-9942",
                        "headers",
                        Map.of("x-tenant-id", "emea-retail"),
                        "payload",
                        kafkaPayload1),
                201);

        postJson(
                httpClient,
                baseUrl + "/__admin/async/destinations/amqp/orders.inbound/messages",
                Map.of(
                        "id",
                        "msg-amqp-9942",
                        "key",
                        "ORD-9942",
                        "correlationId",
                        "corr-amqp-9942",
                        "replyTo",
                        "orders.replies",
                        "headers",
                        Map.of("content-type", "application/json"),
                        "payload",
                        "{\"orderId\":\"ORD-9942\",\"action\":\"DISPATCH\"}"),
                201);

        postJson(
                httpClient,
                baseUrl + "/__admin/async/destinations/casual/billing.q/messages",
                Map.of(
                        "id",
                        "20000000-0000-4000-8000-000000000001",
                        "correlationId",
                        "corr-casual-billing-1",
                        "headers",
                        Map.of("X-Casual-Buffer-Type", "JSON"),
                        "payload",
                        "{\"invoiceId\":\"INV-2026-9942\",\"amount\":1250.0,\"currency\":\"EUR\"}"),
                201);

        postJson(
                httpClient,
                baseUrl + "/__admin/async/publish",
                Map.of(
                        "id",
                        "msg-inmem-9942",
                        "protocol",
                        "in-memory",
                        "destination",
                        "orders.notifications",
                        "key",
                        "ORD-9942",
                        "correlationId",
                        "corr-async-9942",
                        "payload",
                        "{\"orderId\":\"ORD-9942\",\"event\":\"ORDER_SHIPPED\"}"),
                200);
    }

    private static void registerBrokerConnections(
            HttpClient httpClient, String baseUrl, int kafkaPort, int amqpPort, int casualPort) throws Exception {
        String kafkaEndpoint = kafkaPort > 0 ? "127.0.0.1:" + kafkaPort : "localhost:9092";
        String amqpEndpoint = amqpPort > 0 ? "127.0.0.1:" + amqpPort : "127.0.0.1:5672";
        String casualEndpoint = casualPort > 0 ? "127.0.0.1:" + casualPort : "127.0.0.1:7772";

        List<Map<String, Object>> connections = List.of(
                Map.of(
                        "id",
                        "embedded-artemis",
                        "protocol",
                        "amqp",
                        "name",
                        "ActiveMQ Artemis (Embedded)",
                        "endpoint",
                        amqpEndpoint,
                        "properties",
                        Map.of("amqp.protocol", "1.0", "client.id", "wm-artemis-client")),
                Map.of(
                        "id",
                        "local-kafka-sink",
                        "protocol",
                        "kafka",
                        "name",
                        "Kafka Wire Sink (In-Process)",
                        "endpoint",
                        kafkaEndpoint,
                        "properties",
                        Map.of("client.id", "wm-kafka-sink")),
                Map.of(
                        "id",
                        "mainframe-casual",
                        "protocol",
                        "casual",
                        "name",
                        "Casual Domain Gateway",
                        "endpoint",
                        casualEndpoint,
                        "properties",
                        Map.of("domain.id", "wiremock-casual-domain")),
                Map.of(
                        "id",
                        "corp-ibmmq",
                        "protocol",
                        "ibmmq",
                        "name",
                        "IBM MQ Queue Manager (QM1)",
                        "endpoint",
                        "127.0.0.1:1414",
                        "properties",
                        Map.of(
                                "queueManager",
                                "QM1",
                                "channel",
                                "DEV.APP.SVRCONN",
                                "username",
                                "app",
                                "password",
                                "${IBM_MQ_PASSWORD}")));

        postJson(httpClient, baseUrl + "/__admin/async/connections", Map.of("connections", connections), 201);
    }

    private static JsonNode getJson(HttpClient httpClient, String url) throws IOException, InterruptedException {
        HttpResponse<String> resp = httpClient.send(
                HttpRequest.newBuilder().uri(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("GET " + url + " failed with status " + resp.statusCode());
        }
        return MAPPER.readTree(resp.body());
    }

    private static JsonNode postJson(HttpClient httpClient, String url, Object bodyObj, int expectedStatus)
            throws IOException, InterruptedException {
        String body = MAPPER.writeValueAsString(bodyObj);
        HttpResponse<String> resp = httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != expectedStatus) {
            throw new IllegalStateException("POST " + url + " expected " + expectedStatus + " but got "
                    + resp.statusCode() + ": " + resp.body());
        }
        return resp.body() != null && !resp.body().isBlank() ? MAPPER.readTree(resp.body()) : MAPPER.createObjectNode();
    }

    private static ArrayNode extractArray(JsonNode parent, String field) {
        if (parent != null && parent.has(field) && parent.get(field).isArray()) {
            return (ArrayNode) parent.get(field);
        }
        return MAPPER.createArrayNode();
    }

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static JsonNode normalizePorts(
            JsonNode node, int wmPort, int casualPort, int netEmListenPort, int kafkaPort, int amqpPort) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                JsonNode val = entry.getValue();
                if (val.isIntegralNumber()) {
                    int intVal = val.asInt();
                    if ("port".equals(key)) {
                        if (intVal == wmPort) {
                            entry.setValue(obj.numberNode(8080));
                        } else if (casualPort > 0 && intVal == casualPort) {
                            entry.setValue(obj.numberNode(7772));
                        } else if (netEmListenPort > 0 && intVal == netEmListenPort) {
                            entry.setValue(obj.numberNode(8081));
                        }
                    } else if ("listenPort".equals(key) && netEmListenPort > 0 && intVal == netEmListenPort) {
                        entry.setValue(obj.numberNode(8081));
                    } else if ("targetPort".equals(key) && intVal == wmPort) {
                        entry.setValue(obj.numberNode(8080));
                    }
                } else if (val.isTextual()) {
                    String normalized =
                            normalizePortText(val.asText(), wmPort, casualPort, netEmListenPort, kafkaPort, amqpPort);
                    entry.setValue(obj.textNode(normalized));
                } else {
                    normalizePorts(val, wmPort, casualPort, netEmListenPort, kafkaPort, amqpPort);
                }
            }
            return obj;
        }
        if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                JsonNode item = arr.get(i);
                if (item.isTextual()) {
                    arr.set(
                            i,
                            arr.textNode(normalizePortText(
                                    item.asText(), wmPort, casualPort, netEmListenPort, kafkaPort, amqpPort)));
                } else {
                    normalizePorts(item, wmPort, casualPort, netEmListenPort, kafkaPort, amqpPort);
                }
            }
            return arr;
        }
        return node;
    }

    private static String normalizePortText(
            String text, int wmPort, int casualPort, int netEmListenPort, int kafkaPort, int amqpPort) {
        if (text == null || text.isEmpty() || text.indexOf(':') < 0) {
            return text;
        }
        String result = replaceHostPort(text, wmPort, 8080);
        if (casualPort > 0) {
            result = replaceHostPort(result, casualPort, 7772);
        }
        if (netEmListenPort > 0) {
            result = replaceHostPort(result, netEmListenPort, 8081);
        }
        if (kafkaPort > 0) {
            result = replaceHostPort(result, kafkaPort, 9092);
        }
        if (amqpPort > 0) {
            result = replaceHostPort(result, amqpPort, 5672);
        }
        return result;
    }

    private static String replaceHostPort(String text, int fromPort, int toPort) {
        if (fromPort <= 0 || fromPort == toPort) {
            return text;
        }
        return text.replaceAll(":" + fromPort + "(?!\\d)", ":" + toPort);
    }
}
