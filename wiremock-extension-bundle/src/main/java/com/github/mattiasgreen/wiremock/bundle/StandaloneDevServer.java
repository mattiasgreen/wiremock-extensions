package com.github.mattiasgreen.wiremock.bundle;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import com.github.mattiasgreen.wiremock.casual.CasualWireMockGateway;
import com.github.mattiasgreen.wiremock.netem.engine.TcpNetEmRegistry;
import com.github.mattiasgreen.wiremock.netem.model.NetEmProxyConfig;
import com.github.mattiasgreen.wiremock.stateful.ast.AstModelDefinition;
import com.github.mattiasgreen.wiremock.stateful.client.RemoteStateClient;
import com.github.mattiasgreen.wiremock.stateful.dsl.Action;
import com.github.mattiasgreen.wiremock.stateful.dsl.Expr;
import com.github.mattiasgreen.wiremock.stateful.dsl.ResponseSource;
import com.github.mattiasgreen.wiremock.stateful.dsl.SimulatorModel;
import com.github.mattiasgreen.wiremock.stateful.dsl.Source;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.common.Metadata;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.List;
import java.util.Map;

public class StandaloneDevServer {

    public static void main(String[] args) {
        int port = 8080;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {
            }
        }

        System.setProperty("wiremock.verbose.json", "true");

        WireMockServer server =
                new WireMockServer(WireMockConfiguration.options().port(port).extensionScanningEnabled(true));

        server.start();

        // 1. Static Mock (Identity / Users)
        server.stubFor(get(urlEqualTo("/api/v1/users"))
                .withName("List Users")
                .withMetadata(Metadata.metadata().attr("project", "User Service"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]")));

        // 2. Body-Matching & Example Request (Checkout / Orders)
        Map<String, Object> orderExample = Map.of(
                "path", "/api/v1/orders",
                "headers", Map.of("Content-Type", "application/json"),
                "body",
                        "{\n  \"item\": \"Wireless Noise-Canceling Headphones\",\n  \"quantity\": 1,\n  \"price\": 199.99,\n  \"customer\": \"alex@example.com\"\n}");

        server.stubFor(post(urlEqualTo("/api/v1/orders"))
                .withName("Create Order")
                .withRequestBody(matchingJsonPath("$.item"))
                .withMetadata(
                        Metadata.metadata().attr("project", "Order Service").attr("exampleRequest", orderExample))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"orderId\":\"ord-9921\",\"status\":\"CREATED\"}")));

        // 3. Dynamic Templated Mock (Handlebars response-template)
        server.stubFor(
                get(urlPathEqualTo("/api/v1/greeting"))
                        .withName("Dynamic Greeting (Handlebars)")
                        .withMetadata(Metadata.metadata().attr("project", "Greeting Service"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withTransformers("response-template")
                                        .withBody(
                                                "{\"message\":\"Hello, {{#if request.query.name}}{{request.query.name}}{{else}}World{{/if}}!\",\"timestamp\":\"{{now}}\",\"status\":\"active\"}")));

        // 4. Fault & Resilience Mocks
        server.stubFor(get(urlEqualTo("/api/v1/slow"))
                .withName("Slow Latency Simulation")
                .withMetadata(Metadata.metadata().attr("project", "Resilience & Faults"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(150).withBody("{\"latency\":\"delayed\"}")));

        server.stubFor(get(urlEqualTo("/api/v1/error"))
                .withName("Internal Server Error Mock")
                .withMetadata(Metadata.metadata().attr("project", "Resilience & Faults"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"Internal Server Error\",\"code\":500}")));

        // 5. Casual Protocol Mock (XATMI tpcall billing service)
        server.stubFor(post(urlEqualTo("/__casual/services/billing.charge"))
                .withName("Casual XATMI Service (Billing Charge)")
                .withMetadata(Metadata.metadata().attr("protocol", "casual").attr("project", "Billing Platform"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("X-Casual-Error-State", "OK")
                        .withBody("{\"txId\":\"tx-9012\",\"amount\":99.0,\"status\":\"COMMITTED\"}")));

        // 6. In-Memory Stateful AST Simulation Model (Case Management)
        try {
            AstModelDefinition caseModel = SimulatorModel.forEntity("cases")
                    .idFromPath("/api/v1/cases/{caseId}")
                    .onPost("/api/v1/cases")
                    .initialState(Map.of("status", "OPEN", "tasks", List.of()))
                    .respondWith(201, ResponseSource.entity())
                    .onGet("/api/v1/cases/{caseId}")
                    .respondWith(200, ResponseSource.entity())
                    .onPost("/api/v1/cases/{caseId}/tasks")
                    .require(Expr.field("status").eq("OPEN"), 409, "Cannot add tasks to closed case")
                    .mutate(Action.appendToList("tasks", Source.requestBodyWithGeneratedId("id", "tsk-")))
                    .respondWith(201, ResponseSource.lastAppendedItem("tasks"))
                    .onPost("/api/v1/cases/{caseId}/close")
                    .require(Expr.field("status").eq("OPEN"), 400, "Case is already closed")
                    .require(
                            Expr.list("tasks").allMatch(Expr.field("status").eq("COMPLETED")),
                            409,
                            "Cannot close case with pending tasks")
                    .mutate(Action.setField("status", "CLOSED"))
                    .respondWith(200, ResponseSource.entity())
                    .build();

            new RemoteStateClient("http://localhost:" + port).pushModel("DEFAULT", caseModel);
        } catch (Exception e) {
            System.err.println("Could not preload stateful case management model: " + e.getMessage());
        }

        // 7. L4 TCP Network Emulator Ingress Proxy
        int netemPort = Integer.getInteger("wiremock.netem.port", port + 1);
        try {
            TcpNetEmRegistry.getInstance()
                    .register(new NetEmProxyConfig("wiremock-netem", netemPort, "127.0.0.1", port, true));
        } catch (Exception e) {
            System.err.println("Could not bind default TCP NetEm proxy on port " + netemPort + ": " + e.getMessage());
        }

        // 8. Casual TCP Gateway Ingress
        int casualPort = Integer.getInteger("wiremock.casual.port", 7772);
        try {
            CasualWireMockGateway casualGateway = CasualWireMockGateway.builder()
                    .port(casualPort)
                    .wireMockBaseUrl("http://localhost:" + port)
                    .build();
            casualGateway.start();
        } catch (Exception e) {
            System.err.println(
                    "Could not bind default Casual TCP gateway on port " + casualPort + ": " + e.getMessage());
        }

        System.out.println("=================================================================");
        System.out.println("🚀 WireMock Suite Server running at http://localhost:" + port);
        System.out.println("👉 WireMock Console UI: http://localhost:" + port + "/__admin/ui");
        System.out.println(
                "👉 TCP NetEm Ingress:   http://localhost:" + netemPort + " (dual-ingress -> :" + port + ")");
        System.out.println("👉 Casual TCP Gateway:  casual://127.0.0.1:" + casualPort);
        System.out.println("👉 NetEm Journal:       http://localhost:" + port + "/__admin/tcp-netem/journal");
        System.out.println("👉 Prometheus Metrics:  http://localhost:" + port + "/__admin/metrics/prometheus");
        System.out.println("👉 Tracing & Logging:   W3C Context Propagation & MDC Active");
        System.out.println("👉 Admin API:           http://localhost:" + port + "/__admin/mappings");
        System.out.println("=================================================================");
        System.out.println("Press Ctrl+C to terminate the server.");
    }
}
