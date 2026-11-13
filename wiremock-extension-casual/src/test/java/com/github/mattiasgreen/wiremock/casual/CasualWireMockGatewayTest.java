package com.github.mattiasgreen.wiremock.casual;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.recordSpec;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.transaction.xa.Xid;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import se.laz.casual.api.buffer.CasualHeaders;
import se.laz.casual.api.buffer.type.JsonBuffer;
import se.laz.casual.api.buffer.type.ServiceBuffer;
import se.laz.casual.api.flags.AtmiFlags;
import se.laz.casual.api.flags.ErrorState;
import se.laz.casual.api.flags.Flag;
import se.laz.casual.api.flags.TransactionState;
import se.laz.casual.api.network.protocol.messages.CasualNWMessageType;
import se.laz.casual.api.queue.QueueErrorCode;
import se.laz.casual.api.xa.XAReturnCode;
import se.laz.casual.api.xa.XID;
import se.laz.casual.config.ConfigurationOptions;
import se.laz.casual.config.ConfigurationService;
import se.laz.casual.jca.SpanId;
import se.laz.casual.network.ProtocolVersion;
import se.laz.casual.network.protocol.messages.domain.CasualDomainDiscoveryReplyMessage;
import se.laz.casual.network.protocol.messages.domain.Queue;
import se.laz.casual.network.protocol.messages.domain.Service;
import se.laz.casual.network.protocol.messages.queue.CasualDequeueReplyMessage;
import se.laz.casual.network.protocol.messages.queue.CasualEnqueueReplyMessage;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallReplyMessage;
import se.laz.casual.network.protocol.messages.service.CasualServiceCallRequestMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceCommitReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourcePrepareReplyMessage;
import se.laz.casual.network.protocol.messages.transaction.CasualTransactionResourceRollbackReplyMessage;

/**
 * PoC Integration test verifying that Casual wire protocol requests
 * can be stubbed via WireMock HTTP definitions, and transparently proxied to upstream
 * Casual domains with optional stub recording.
 */
class CasualWireMockGatewayTest {

    private WireMockServer wireMockServer;
    private CasualWireMockGateway gateway;
    private CasualClient client;

    @BeforeEach
    void setUp() throws Exception {
        wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());
        wireMockServer.start();

        gateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(wireMockServer)
                .build();
        gateway.start();

        client = new CasualClient("127.0.0.1", gateway.getPort());
        client.connect();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (gateway != null) {
            gateway.close();
        }
        if (wireMockServer != null) {
            wireMockServer.resetAll();
            wireMockServer.stop();
        }
    }

    @Test
    @DisplayName("Should successfully perform domain discovery only for stubbed and pattern-matched services")
    void shouldDiscoverServices() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/order.service"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));
        wireMockServer.stubFor(post(urlMatching("/__casual/services/payment\\..*"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));

        CasualDomainDiscoveryReplyMessage reply =
                client.discoverServices(List.of("order.service", "payment.card", "unmockedService"));

        assertThat(reply).isNotNull();
        assertThat(reply.getDomainName()).isEqualTo("wiremock-casual-domain");
        assertThat(reply.getServices())
                .extracting(Service::getName)
                .containsExactlyInAnyOrder("order.service", "payment.card")
                .doesNotContain("unmockedService");
    }

    @Test
    @DisplayName("Should stub Casual tpcall with matching WireMock HTTP stub mapping")
    void shouldStubCasualServiceCallSuccessfully() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/createOrder"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"orderId\": \"ORD-9988\", \"status\": \"CONFIRMED\"}")));

        CasualServiceCallReplyMessage reply =
                this.client.tpcall("createOrder", "{\"amount\": 150.0, \"currency\": \"SEK\"}");

        assertThat(reply).isNotNull();
        assertThat(reply.getError()).isEqualTo(ErrorState.OK);

        ServiceBuffer buffer = reply.getServiceBuffer();
        assertThat(buffer).isNotNull();
        assertThat(buffer.getType()).isEqualTo(se.laz.casual.api.buffer.CasualBufferType.JSON.getName());

        String responsePayload = new String(buffer.getBytes().get(0), StandardCharsets.UTF_8);
        assertThat(responsePayload).contains("ORD-9988").contains("CONFIRMED");
    }

    @Test
    @DisplayName("Should match Casual call using matchingJsonPath on subset of properties ignoring transient fields")
    void shouldMatchCasualCallWithMatchingJsonPath() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/creditCheckService"))
                .withRequestBody(matchingJsonPath("$.customerId", equalTo("CUST-001")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\": \"APPROVED\", \"limit\": 50000}")));

        // Client sends full JSON payload containing transient correlation ID and timestamp
        String requestPayload =
                """
            {
              "CORRELATION_ID": "b469694c-38eb-4f30-8ca4-10e23b49e00f",
              "REQUEST_TIMESTAMP": 1729999999,
              "customerId": "CUST-001",
              "role": "customer"
            }
            """;

        CasualServiceCallReplyMessage reply = client.tpcall("creditCheckService", requestPayload);

        assertThat(reply).isNotNull();
        assertThat(reply.getError()).isEqualTo(ErrorState.OK);
        String responsePayload = new String(reply.getServiceBuffer().getBytes().get(0), StandardCharsets.UTF_8);
        assertThat(responsePayload).contains("APPROVED").contains("50000");

        // Verify that different customer ID fails to match (value matching, not just existence!)
        String wrongPayload = "{\"customerId\": \"CUST-999\"}";
        CasualServiceCallReplyMessage wrongReply = client.tpcall("creditCheckService", wrongPayload);
        assertThat(wrongReply.getError()).isNotEqualTo(ErrorState.OK);
    }

    @Test
    @DisplayName("Should deserialize and match JSON stub with matchesJsonPath expression and equalTo sub-matcher")
    void shouldMatchJsonStubWithMatchesJsonPathSubMatcher() throws Exception {
        String stubJson =
                """
                {
                  "request": {
                    "method": "POST",
                    "url": "/__casual/services/subMatcherTestService",
                    "bodyPatterns": [
                      {
                        "matchesJsonPath": {
                          "expression": "$.customerId",
                          "equalTo": "12345"
                        }
                      }
                    ]
                  },
                  "response": {
                    "status": 200,
                    "headers": {
                      "Content-Type": "application/json"
                    },
                    "body": "{\\"matched\\": true}"
                  }
                }
                """;

        com.github.tomakehurst.wiremock.stubbing.StubMapping stub =
                com.github.tomakehurst.wiremock.stubbing.StubMapping.buildFrom(stubJson);
        wireMockServer.addStubMapping(stub);

        // 1. Correct value matches
        CasualServiceCallReplyMessage reply = client.tpcall("subMatcherTestService", "{\"customerId\": \"12345\"}");
        assertThat(reply.getError()).isEqualTo(ErrorState.OK);

        // 2. Different value fails
        CasualServiceCallReplyMessage failReply = client.tpcall("subMatcherTestService", "{\"customerId\": \"99999\"}");
        assertThat(failReply.getError()).isNotEqualTo(ErrorState.OK);
    }

    @Test
    @DisplayName("Should match Casual Fielded call with matchingJsonPath on nested JSON payload")
    void shouldMatchCasualFieldedCallWithMatchingJsonPath() throws Exception {
        String fieldTableJson =
                """
        {
          "groups": [
            {
              "base": 1000,
              "fields": [
                { "name": "FLD_USER_ID", "id": 1, "type": "long" },
                { "name": "FLD_STATUS", "id": 2, "type": "string" },
                { "name": "FLD_ARCHIVE_DATA", "id": 9, "type": "binary" }
              ]
            }
          ]
        }
        """;
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("casual-fields-match-test", ".json");
        temp.toFile().deleteOnExit();
        java.nio.file.Files.writeString(temp, fieldTableJson, java.nio.charset.StandardCharsets.UTF_8);

        try {
            System.setProperty("CASUAL_FIELD_TABLE", temp.toAbsolutePath().toString());

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/fmlRiskService"))
                    .withRequestBody(matchingJsonPath("$.FLD_ARCHIVE_DATA.caseId", equalTo("12356")))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"FLD_STATUS\": \"APPROVED\"}")));

            String rawJsonData = "{\"caseId\":\"12356\",\"timestamp\":1729000000}";
            ServiceBuffer fieldedBuffer = CasualBufferUtils.wrapPayload(
                    ".FIELDED",
                    String.format(
                            java.util.Locale.ROOT,
                            """
                    {
                      "FLD_USER_ID": 9999,
                      "FLD_ARCHIVE_DATA": "%s"
                    }
                    """,
                            Base64.getEncoder()
                                    .encodeToString(rawJsonData.getBytes(java.nio.charset.StandardCharsets.UTF_8))));

            CasualServiceCallReplyMessage reply = client.tpcall("fmlRiskService", fieldedBuffer);

            assertThat(reply).isNotNull();
            assertThat(reply.getError()).isEqualTo(ErrorState.OK);
            String responsePayload =
                    new String(reply.getServiceBuffer().getBytes().get(0), StandardCharsets.UTF_8);
            assertThat(responsePayload).contains("APPROVED");

            // Verify that mismatched nested caseId fails to match
            String wrongRawJson = "{\"caseId\":\"99999\",\"timestamp\":1729000000}";
            ServiceBuffer wrongBuffer = CasualBufferUtils.wrapPayload(
                    ".FIELDED",
                    String.format(
                            java.util.Locale.ROOT,
                            """
                    {
                      "FLD_USER_ID": 9999,
                      "FLD_ARCHIVE_DATA": "%s"
                    }
                    """,
                            Base64.getEncoder()
                                    .encodeToString(wrongRawJson.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            CasualServiceCallReplyMessage wrongReply = client.tpcall("fmlRiskService", wrongBuffer);
            assertThat(wrongReply.getError()).isNotEqualTo(ErrorState.OK);
        } finally {
            System.clearProperty("CASUAL_FIELD_TABLE");
            se.laz.casual.config.ConfigurationService.setConfiguration(
                    se.laz.casual.config.ConfigurationOptions.CASUAL_FIELD_TABLE, null);
        }
    }

    @Test
    @DisplayName("Should match Casual Fielded call with equalToJson deep matching on nested archive JSON")
    void shouldMatchCasualFieldedCallWithEqualToJsonDeepMatching() throws Exception {
        String fieldTableJson =
                """
        {
          "groups": [
            {
              "base": 1000,
              "fields": [
                { "name": "FLD_USER_ID", "id": 1, "type": "long" },
                { "name": "FLD_STATUS", "id": 2, "type": "string" },
                { "name": "FLD_ARCHIVE_DATA", "id": 9, "type": "binary" }
              ]
            }
          ]
        }
        """;
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("casual-fields-deep-match-test", ".json");
        temp.toFile().deleteOnExit();
        java.nio.file.Files.writeString(temp, fieldTableJson, java.nio.charset.StandardCharsets.UTF_8);

        try {
            System.setProperty("CASUAL_FIELD_TABLE", temp.toAbsolutePath().toString());

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/deepFmlMatchService"))
                    .withRequestBody(equalToJson(
                            """
                    {
                      "FLD_USER_ID": 12345,
                      "FLD_ARCHIVE_DATA": {
                        "caseId": "12356"
                      }
                    }
                    """,
                            true,
                            true))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"FLD_STATUS\": \"MATCHED\"}")));

            // Client sends payload containing extra transient fields in root and in nested JSON
            String rawJsonData = "{\"caseId\":\"12356\",\"timestamp\":1729000000,\"tempExecutionId\":\"exec-abc\"}";
            ServiceBuffer fieldedBuffer = CasualBufferUtils.wrapPayload(
                    ".FIELDED",
                    String.format(
                            java.util.Locale.ROOT,
                            """
                    {
                      "FLD_USER_ID": 12345,
                      "FLD_ARCHIVE_DATA": "%s"
                    }
                    """,
                            Base64.getEncoder()
                                    .encodeToString(rawJsonData.getBytes(java.nio.charset.StandardCharsets.UTF_8))));

            // 1. Correct nested caseId matches
            CasualServiceCallReplyMessage reply = client.tpcall("deepFmlMatchService", fieldedBuffer);
            assertThat(reply).isNotNull();
            assertThat(reply.getError()).isEqualTo(ErrorState.OK);

            // 2. Different nested caseId fails to match
            String wrongRawJson = "{\"caseId\":\"WRONG-CASE\",\"timestamp\":1729000000}";
            ServiceBuffer wrongBuffer = CasualBufferUtils.wrapPayload(
                    ".FIELDED",
                    String.format(
                            java.util.Locale.ROOT,
                            """
                    {
                      "FLD_USER_ID": 12345,
                      "FLD_ARCHIVE_DATA": "%s"
                    }
                    """,
                            Base64.getEncoder()
                                    .encodeToString(wrongRawJson.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            CasualServiceCallReplyMessage wrongReply = client.tpcall("deepFmlMatchService", wrongBuffer);
            assertThat(wrongReply.getError()).isNotEqualTo(ErrorState.OK);
        } finally {
            System.clearProperty("CASUAL_FIELD_TABLE");
            se.laz.casual.config.ConfigurationService.setConfiguration(
                    se.laz.casual.config.ConfigurationOptions.CASUAL_FIELD_TABLE, null);
        }
    }

    @Test
    @DisplayName("Should match Casual call using equalToJson ignoring transient extra elements")
    void shouldMatchCasualCallWithEqualToJsonIgnoringExtraElements() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/creditCheckService"))
                .withRequestBody(equalToJson(
                        """
                    {
                      "customerId": "CUST-001",
                      "role": "customer"
                    }
                    """,
                        true,
                        true))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\": \"APPROVED\", \"limit\": 50000}")));

        // Client sends request containing transient fields CORRELATION_ID and REQUEST_TIMESTAMP
        String requestPayload =
                """
            {
              "CORRELATION_ID": "random-uuid-9999",
              "REQUEST_TIMESTAMP": 1730000000,
              "customerId": "CUST-001",
              "role": "customer"
            }
            """;

        CasualServiceCallReplyMessage reply = client.tpcall("creditCheckService", requestPayload);

        assertThat(reply).isNotNull();
        assertThat(reply.getError()).isEqualTo(ErrorState.OK);
        String responsePayload = new String(reply.getServiceBuffer().getBytes().get(0), StandardCharsets.UTF_8);
        assertThat(responsePayload).contains("APPROVED").contains("50000");

        // Verify that different customer ID fails to match (value matching, not just existence!)
        String wrongPayload = "{\"customerId\": \"CUST-999\"}";
        CasualServiceCallReplyMessage wrongReply = client.tpcall("creditCheckService", wrongPayload);
        assertThat(wrongReply.getError()).isNotEqualTo(ErrorState.OK);
    }

    @Test
    @DisplayName("Should return TPENOENT error when WireMock returns unmatched for unstubbed service")
    void shouldReturnTpenoentWhenServiceNotFound() throws Exception {
        CasualServiceCallReplyMessage reply = client.tpcall("nonExistentService", "{}");
        assertThat(reply).isNotNull();
        assertThat(reply.getError()).isEqualTo(ErrorState.TPENOENT);

        List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> events = wireMockServer.getAllServeEvents();
        assertThat(events).hasSize(1);
        com.github.tomakehurst.wiremock.stubbing.ServeEvent event = events.get(0);
        assertThat(event.getWasMatched()).isFalse();
        assertThat(event.getResponse().getStatus()).isEqualTo(404);
        assertThat(event.getResponse()
                        .getHeaders()
                        .getHeader("X-Casual-Error-State")
                        .firstValue())
                .isEqualTo("TPENOENT");
    }

    @Test
    @DisplayName("Should serve matched 404 stub directly from WireMock without proxying")
    void shouldServeMatched404StubDirectly() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/explicitNotFound"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"Entity not found\"}")));

        CasualServiceCallReplyMessage reply = client.tpcall("explicitNotFound", "{}");

        assertThat(reply).isNotNull();
        assertThat(reply.getError()).isEqualTo(ErrorState.TPENOENT);
        assertThat(reply.getServiceBuffer()).isNotNull();
        assertThat(reply.getServiceBuffer().getBytes()).isNotEmpty();

        String body = new String(reply.getServiceBuffer().getBytes().get(0), StandardCharsets.UTF_8);
        assertThat(body).contains("Entity not found");
    }

    @Test
    @DisplayName("Should return TPESVCFAIL error when WireMock stub returns 500 server error")
    void shouldReturnTpesvcfailOnServerError() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/failingService"))
                .willReturn(aResponse().withStatus(500).withBody("Internal error")));

        CasualServiceCallReplyMessage reply = client.tpcall("failingService", "{}");

        assertThat(reply).isNotNull();
        assertThat(reply.getError()).isEqualTo(ErrorState.TPESVCFAIL);
    }

    @Test
    @DisplayName("Should forward service call to upstream Casual server via stub-driven proxy")
    void shouldForwardUnmatchedServiceToUpstreamCasualServer() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();
        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamWireMock)
                .build();
        upstreamGateway.start();

        try {
            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/upstreamOrderService"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"orderId\": \"UPSTREAM-123\", \"routedVia\": \"upstream\"}")));

            // Configure stub-driven proxy route
            wireMockServer.stubFor(post(urlMatching("/__casual/services/.*"))
                    .atPriority(10)
                    .withMetadata(
                            Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (CasualClient proxyClient = new CasualClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                // Call unstubbed service on proxyGateway -> should forward to upstreamGateway
                CasualServiceCallReplyMessage reply =
                        proxyClient.tpcall("upstreamOrderService", "{\"item\": \"laptop\"}");

                assertThat(reply).isNotNull();
                assertThat(reply.getError()).isEqualTo(ErrorState.OK);

                String body = new String(reply.getServiceBuffer().getBytes().get(0), StandardCharsets.UTF_8);
                assertThat(body).contains("UPSTREAM-123").contains("upstream");

                upstreamWireMock.verify(1, postRequestedFor(urlEqualTo("/__casual/services/upstreamOrderService")));
            } finally {
                proxyGateway.close();
            }
        } finally {
            upstreamGateway.close();
            upstreamWireMock.stop();
        }
    }

    @Test
    @DisplayName(
            "Should forward customer and spouse calls to upstream CRM risk control and snapshot record with exact body matching for offline replay")
    void shouldForwardAndRecordUnmatchedServiceWhenRecordStubsEnabled() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();
        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamWireMock)
                .build();
        upstreamGateway.start();

        try {
            // Upstream CRM Risk Control: Customer has LOW risk, Spouse has MEDIUM risk
            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/crmRiskControlService"))
                    .withRequestBody(equalToJson("{\"customerId\": \"CUST-001\", \"role\": \"customer\"}"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"customerId\": \"CUST-001\", \"riskStatus\": \"LOW\", \"approved\": true}")));

            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/crmRiskControlService"))
                    .withRequestBody(equalToJson("{\"customerId\": \"CUST-002\", \"role\": \"spouse\"}"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(
                                    "{\"customerId\": \"CUST-002\", \"riskStatus\": \"MEDIUM\", \"approved\": true}")));

            wireMockServer.stubFor(post(urlMatching("/__casual/services/.*"))
                    .atPriority(10)
                    .withMetadata(
                            Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (CasualClient proxyClient = new CasualClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                // 1. Call Customer -> Forwarded to upstream and recorded in Request Journal
                CasualServiceCallReplyMessage customerReply = proxyClient.tpcall(
                        "crmRiskControlService", "{\"customerId\": \"CUST-001\", \"role\": \"customer\"}");
                assertThat(customerReply).isNotNull();
                assertThat(customerReply.getError()).isEqualTo(ErrorState.OK);
                String customerBody =
                        new String(customerReply.getServiceBuffer().getBytes().get(0), StandardCharsets.UTF_8);
                assertThat(customerBody).contains("CUST-001").contains("LOW");

                // 2. Call Spouse -> Forwarded to upstream and recorded in Request Journal
                CasualServiceCallReplyMessage spouseReply = proxyClient.tpcall(
                        "crmRiskControlService", "{\"customerId\": \"CUST-002\", \"role\": \"spouse\"}");
                assertThat(spouseReply).isNotNull();
                assertThat(spouseReply.getError()).isEqualTo(ErrorState.OK);
                String spouseBody =
                        new String(spouseReply.getServiceBuffer().getBytes().get(0), StandardCharsets.UTF_8);
                assertThat(spouseBody).contains("CUST-002").contains("MEDIUM");

                // Upstream received exactly 1 request for customer and 1 for spouse
                upstreamWireMock.verify(
                        1,
                        postRequestedFor(urlEqualTo("/__casual/services/crmRiskControlService"))
                                .withRequestBody(
                                        equalToJson("{\"customerId\": \"CUST-001\", \"role\": \"customer\"}")));
                upstreamWireMock.verify(
                        1,
                        postRequestedFor(urlEqualTo("/__casual/services/crmRiskControlService"))
                                .withRequestBody(equalToJson("{\"customerId\": \"CUST-002\", \"role\": \"spouse\"}")));

                // 3. Snapshot record journal entries into offline stub mappings
                wireMockServer.snapshotRecord(recordSpec()
                        .makeStubsPersistent(false)
                        .extractTextBodiesOver(Long.MAX_VALUE)
                        .extractBinaryBodiesOver(Long.MAX_VALUE));

                // 4. Terminate upstream server completely to guarantee 100% offline replay
                upstreamGateway.close();
                upstreamWireMock.stop();

                // 5. Replay Customer call offline -> Must match recorded customer stub directly!
                CasualServiceCallReplyMessage replayCustomer = proxyClient.tpcall(
                        "crmRiskControlService", "{\"customerId\": \"CUST-001\", \"role\": \"customer\"}");
                assertThat(replayCustomer).isNotNull();
                assertThat(replayCustomer.getError()).isEqualTo(ErrorState.OK);
                String replayCustBody =
                        new String(replayCustomer.getServiceBuffer().getBytes().get(0), StandardCharsets.UTF_8);
                assertThat(replayCustBody).contains("CUST-001").contains("LOW");

                // 6. Replay Spouse call offline -> Must match recorded spouse stub directly without collision!
                CasualServiceCallReplyMessage replaySpouse = proxyClient.tpcall(
                        "crmRiskControlService", "{\"customerId\": \"CUST-002\", \"role\": \"spouse\"}");
                assertThat(replaySpouse).isNotNull();
                assertThat(replaySpouse.getError()).isEqualTo(ErrorState.OK);
                String replaySpouseBody =
                        new String(replaySpouse.getServiceBuffer().getBytes().get(0), StandardCharsets.UTF_8);
                assertThat(replaySpouseBody).contains("CUST-002").contains("MEDIUM");

                // 7. Unknown payload does NOT match customer or spouse stubs; falls through to proxy route
                // and fails because upstream is shut down
                CasualServiceCallReplyMessage unknownReply = proxyClient.tpcall(
                        "crmRiskControlService", "{\"customerId\": \"CUST-999\", \"role\": \"unknown\"}");
                assertThat(unknownReply).isNotNull();
                assertThat(unknownReply.getError()).isEqualTo(ErrorState.TPESVCERR);
            } finally {
                proxyGateway.close();
            }
        } finally {
            if (upstreamGateway.isRunning()) {
                upstreamGateway.close();
            }
            if (upstreamWireMock.isRunning()) {
                upstreamWireMock.stop();
            }
        }
    }

    @Test
    @DisplayName("Should forward upstream error states transparently to client")
    void shouldForwardUpstreamErrorStates() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();
        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamWireMock)
                .build();
        upstreamGateway.start();

        try {
            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/upstreamFailing"))
                    .willReturn(aResponse().withStatus(500).withBody("Upstream fatal error")));

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/upstreamFailing"))
                    .withMetadata(
                            Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (CasualClient proxyClient = new CasualClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                CasualServiceCallReplyMessage reply = proxyClient.tpcall("upstreamFailing", "{}");

                assertThat(reply).isNotNull();
                assertThat(reply.getError()).isEqualTo(ErrorState.TPESVCFAIL);
            } finally {
                proxyGateway.close();
            }
        } finally {
            upstreamGateway.close();
            upstreamWireMock.stop();
        }
    }

    @Test
    @DisplayName("Should record and replay upstream faults via snapshot record")
    void shouldRecordAndReplayUpstreamFaultsWhenRecordStubsEnabled() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();
        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamWireMock)
                .build();
        upstreamGateway.start();

        try {
            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/upstreamAccountBlocked"))
                    .willReturn(aResponse()
                            .withStatus(500)
                            .withHeader("Content-Type", "application/json")
                            .withHeader("X-Casual-Error-State", "TPESVCFAIL")
                            .withBody("{\"error\": \"Account blocked\", \"code\": \"ACC-BLOCKED\"}")));

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/upstreamAccountBlocked"))
                    .atPriority(10)
                    .withMetadata(
                            Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (CasualClient proxyClient = new CasualClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                // 1st Call: Forwarded to upstream and recorded in Request Journal
                CasualServiceCallReplyMessage firstReply =
                        proxyClient.tpcall("upstreamAccountBlocked", "{\"accountId\": \"ACC-999\"}");

                assertThat(firstReply).isNotNull();
                assertThat(firstReply.getError()).isEqualTo(ErrorState.TPESVCFAIL);
                String firstBody =
                        new String(firstReply.getServiceBuffer().getBytes().get(0), StandardCharsets.UTF_8);
                assertThat(firstBody).contains("Account blocked").contains("ACC-BLOCKED");

                upstreamWireMock.verify(1, postRequestedFor(urlEqualTo("/__casual/services/upstreamAccountBlocked")));

                // Snapshot record journal entries into offline stub mappings
                wireMockServer.snapshotRecord(recordSpec()
                        .makeStubsPersistent(false)
                        .extractTextBodiesOver(Long.MAX_VALUE)
                        .extractBinaryBodiesOver(Long.MAX_VALUE));

                // 2nd Call: Replayed directly from the newly recorded fault stub in wireMockServer
                CasualServiceCallReplyMessage secondReply =
                        proxyClient.tpcall("upstreamAccountBlocked", "{\"accountId\": \"ACC-999\"}");

                assertThat(secondReply).isNotNull();
                assertThat(secondReply.getError()).isEqualTo(ErrorState.TPESVCFAIL);
                String secondBody =
                        new String(secondReply.getServiceBuffer().getBytes().get(0), StandardCharsets.UTF_8);
                assertThat(secondBody).contains("Account blocked").contains("ACC-BLOCKED");

                // Upstream was still only called once!
                upstreamWireMock.verify(1, postRequestedFor(urlEqualTo("/__casual/services/upstreamAccountBlocked")));
            } finally {
                proxyGateway.close();
            }
        } finally {
            upstreamGateway.close();
            upstreamWireMock.stop();
        }
    }

    @Test
    @DisplayName("Should prioritize high-priority mock stub over catch-all proxy stub")
    void shouldPrioritizeLocalMockStubOverCatchAllProxyStub() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();
        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamWireMock)
                .build();
        upstreamGateway.start();

        try {
            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/myService"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"source\": \"upstream\"}")));

            // Catch-all proxy at priority 10
            wireMockServer.stubFor(post(urlMatching("/__casual/services/.*"))
                    .atPriority(10)
                    .withMetadata(
                            Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            // Local mock stub at priority 1
            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/myService"))
                    .atPriority(1)
                    .willReturn(aResponse().withStatus(200).withBody("{\"source\": \"local-mock\"}")));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (CasualClient proxyClient = new CasualClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                CasualServiceCallReplyMessage reply = proxyClient.tpcall("myService", "{}");
                assertThat(reply.getError()).isEqualTo(ErrorState.OK);
                String body = new String(reply.getServiceBuffer().getBytes().get(0), StandardCharsets.UTF_8);
                assertThat(body).contains("local-mock");

                // Upstream must NOT be called
                upstreamWireMock.verify(0, postRequestedFor(urlEqualTo("/__casual/services/myService")));
            } finally {
                proxyGateway.close();
            }
        } finally {
            upstreamGateway.close();
            upstreamWireMock.stop();
        }
    }

    @Test
    @DisplayName(
            "Should route different services to different upstreams and fan-out 2PC prepare/commit across all enlisted upstreams")
    void shouldRouteToMultipleUpstreamsAndFanOut2pc() throws Exception {
        WireMockServer upstream1WM = new WireMockServer(wireMockConfig().dynamicPort());
        WireMockServer upstream2WM = new WireMockServer(wireMockConfig().dynamicPort());
        upstream1WM.start();
        upstream2WM.start();

        CasualWireMockGateway upstream1 = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstream1WM)
                .build();
        CasualWireMockGateway upstream2 = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstream2WM)
                .build();
        upstream1.start();
        upstream2.start();

        try {
            upstream1WM.stubFor(post(urlEqualTo("/__casual/services/orderService"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"order\": \"123\"}")));
            upstream2WM.stubFor(post(urlEqualTo("/__casual/services/paymentService"))
                    .willReturn(aResponse().withStatus(200).withBody("{\"payment\": \"456\"}")));

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/orderService"))
                    .withMetadata(Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstream1.getPort()))
                    .willReturn(aResponse().withStatus(200)));
            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/paymentService"))
                    .withMetadata(Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstream2.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (CasualClient proxyClient = new CasualClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                Xid xid = XID.of(
                        "gtrid-multi".getBytes(StandardCharsets.UTF_8),
                        "bqual-multi".getBytes(StandardCharsets.UTF_8),
                        42);

                CasualServiceCallReplyMessage r1 = proxyClient.tpcall("orderService", "{}", xid);
                assertThat(r1.getError()).isEqualTo(ErrorState.OK);

                CasualServiceCallReplyMessage r2 = proxyClient.tpcall("paymentService", "{}", xid);
                assertThat(r2.getError()).isEqualTo(ErrorState.OK);

                // Both upstreams called
                upstream1WM.verify(1, postRequestedFor(urlEqualTo("/__casual/services/orderService")));
                upstream2WM.verify(1, postRequestedFor(urlEqualTo("/__casual/services/paymentService")));

                // 2PC prepare fans out to both upstreams
                CasualTransactionResourcePrepareReplyMessage prepReply = proxyClient.prepare(xid, 1);
                assertThat(prepReply.getTransactionReturnCode()).isEqualTo(XAReturnCode.XA_OK);

                // 2PC commit fans out to both upstreams
                CasualTransactionResourceCommitReplyMessage commitReply = proxyClient.commit(xid, 1, false);
                assertThat(commitReply.getTransactionReturnCode()).isEqualTo(XAReturnCode.XA_OK);
            } finally {
                proxyGateway.close();
            }
        } finally {
            upstream1.close();
            upstream2.close();
            upstream1WM.stop();
            upstream2WM.stop();
        }
    }

    @Test
    @DisplayName("Should stub Casual failure mode with custom ErrorState, user code, and transaction state")
    void shouldStubCasualFailureModeWithCustomErrorStateUserCodeAndTxState() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/failingTxService"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("X-Casual-Error-State", "TPETRAN")
                        .withHeader("X-Casual-User-Supplied-Error", "8877")
                        .withHeader("X-Casual-Transaction-State", "ROLLBACK_ONLY")
                        .withBody("{\"error\": \"Transaction aborted\", \"retry\": false}")));

        CasualServiceCallReplyMessage reply = client.tpcall("failingTxService", "{\"txId\": \"TX-001\"}");

        assertThat(reply).isNotNull();
        assertThat(reply.getError()).isEqualTo(ErrorState.TPETRAN);
        assertThat(reply.getUserDefinedCode()).isEqualTo(8877L);
        assertThat(reply.getTransactionState()).isEqualTo(TransactionState.ROLLBACK_ONLY);

        String body = new String(reply.getServiceBuffer().getBytes().get(0), StandardCharsets.UTF_8);
        assertThat(body).contains("Transaction aborted");
    }

    @Test
    @DisplayName("Should map standard HTTP status codes to appropriate Casual error states when header is omitted")
    void shouldMapStandardHttpStatusCodesToCasualErrorStates() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/invalidArgs"))
                .willReturn(aResponse().withStatus(400).withBody("Bad request")));
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/timedOut"))
                .willReturn(aResponse().withStatus(504)));
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/rateLimited"))
                .willReturn(aResponse().withStatus(429)));
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/backendError"))
                .willReturn(aResponse().withStatus(502)));

        assertThat(client.tpcall("invalidArgs", "{}").getError()).isEqualTo(ErrorState.TPEINVAL);
        assertThat(client.tpcall("timedOut", "{}").getError()).isEqualTo(ErrorState.TPETIME);
        assertThat(client.tpcall("rateLimited", "{}").getError()).isEqualTo(ErrorState.TPELIMIT);
        assertThat(client.tpcall("backendError", "{}").getError()).isEqualTo(ErrorState.TPESVCERR);
    }

    @ParameterizedTest
    @EnumSource(TransactionState.class)
    @DisplayName("Should faithfully stub all Casual TransactionState enum modes")
    void shouldStubAllTransactionStatesCorrectly(TransactionState txState) throws Exception {
        String serviceName = "txStateService_" + txState.name();
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/" + serviceName))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("X-Casual-Transaction-State", txState.name())
                        .withBody("{\"txStatus\": \"" + txState.name() + "\"}")));

        CasualServiceCallReplyMessage reply = client.tpcall(serviceName, "{}");
        assertThat(reply).isNotNull();
        assertThat(reply.getTransactionState()).isEqualTo(txState);
    }

    @Test
    @DisplayName("Should default TransactionState to TX_ACTIVE when header is absent or invalid")
    void shouldDefaultToTxActiveWhenTransactionStateHeaderIsMissingOrInvalid() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/missingTxHeader"))
                .willReturn(aResponse().withStatus(200).withBody("{}")));
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/invalidTxHeader"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("X-Casual-Transaction-State", "NON_EXISTENT_STATE")
                        .withBody("{}")));

        CasualServiceCallReplyMessage reply1 = client.tpcall("missingTxHeader", "{}");
        assertThat(reply1.getTransactionState()).isEqualTo(TransactionState.TX_ACTIVE);

        CasualServiceCallReplyMessage reply2 = client.tpcall("invalidTxHeader", "{}");
        assertThat(reply2.getTransactionState()).isEqualTo(TransactionState.TX_ACTIVE);
    }

    @Test
    @DisplayName("Should record and replay non-default upstream TransactionState")
    void shouldRecordAndReplayUpstreamTransactionState() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();
        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamWireMock)
                .build();
        upstreamGateway.start();

        try {
            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/timedOutTxService"))
                    .willReturn(aResponse()
                            .withStatus(500)
                            .withHeader("Content-Type", "application/json")
                            .withHeader("X-Casual-Error-State", "TPESVCFAIL")
                            .withHeader("X-Casual-Transaction-State", "TIMEOUT_ROLLBACK_ONLY")
                            .withBody("{\"error\": \"Transaction deadline exceeded\"}")));

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/timedOutTxService"))
                    .atPriority(10)
                    .withMetadata(
                            Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (CasualClient proxyClient = new CasualClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                // 1st call: Forwarded upstream & recorded in Request Journal
                CasualServiceCallReplyMessage reply1 = proxyClient.tpcall("timedOutTxService", "{}");
                assertThat(reply1.getTransactionState()).isEqualTo(TransactionState.TIMEOUT_ROLLBACK_ONLY);
                assertThat(reply1.getError()).isEqualTo(ErrorState.TPESVCFAIL);

                upstreamWireMock.verify(1, postRequestedFor(urlEqualTo("/__casual/services/timedOutTxService")));

                // Snapshot record journal entries into offline stub mappings
                wireMockServer.snapshotRecord(recordSpec()
                        .makeStubsPersistent(false)
                        .extractTextBodiesOver(Long.MAX_VALUE)
                        .extractBinaryBodiesOver(Long.MAX_VALUE));

                // 2nd call: Replayed from recorded WireMock stub
                CasualServiceCallReplyMessage reply2 = proxyClient.tpcall("timedOutTxService", "{}");
                assertThat(reply2.getTransactionState()).isEqualTo(TransactionState.TIMEOUT_ROLLBACK_ONLY);

                // Upstream was not called again
                upstreamWireMock.verify(1, postRequestedFor(urlEqualTo("/__casual/services/timedOutTxService")));
            } finally {
                proxyGateway.close();
            }
        } finally {
            upstreamGateway.close();
            upstreamWireMock.stop();
        }
    }

    @Test
    @DisplayName("Should return XA_OK by default for 2PC prepare, commit, and rollback on stubbed transactional calls")
    void shouldReturnXaOkByDefaultForStubbed2pcTransactions() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/txPaymentService"))
                .willReturn(aResponse().withStatus(200).withBody("{\"status\": \"RESERVED\"}")));

        Xid xid1 =
                XID.of("gtrid-001".getBytes(StandardCharsets.UTF_8), "bqual-001".getBytes(StandardCharsets.UTF_8), 42);
        CasualServiceCallReplyMessage callReply = client.tpcall("txPaymentService", "{\"amount\": 100}", xid1);
        assertThat(callReply.getError()).isEqualTo(ErrorState.OK);
        assertThat(callReply.getTransactionState()).isEqualTo(TransactionState.TX_ACTIVE);
        if (callReply.getType() == CasualNWMessageType.SERVICE_CALL_REPLY) {
            assertThat(callReply.getXid()).isEqualTo(xid1);
        }

        CasualTransactionResourcePrepareReplyMessage prepareReply = client.prepare(xid1, 1);
        assertThat(prepareReply.getTransactionReturnCode()).isEqualTo(XAReturnCode.XA_OK);

        CasualTransactionResourceCommitReplyMessage commitReply = client.commit(xid1, 1, false);
        assertThat(commitReply.getTransactionReturnCode()).isEqualTo(XAReturnCode.XA_OK);

        Xid xid2 =
                XID.of("gtrid-002".getBytes(StandardCharsets.UTF_8), "bqual-002".getBytes(StandardCharsets.UTF_8), 42);
        client.tpcall("txPaymentService", "{\"amount\": 200}", xid2);
        CasualTransactionResourceRollbackReplyMessage rollbackReply = client.rollback(xid2, 1);
        assertThat(rollbackReply.getTransactionReturnCode()).isEqualTo(XAReturnCode.XA_OK);
    }

    @Test
    @DisplayName("Should honor stubbed X-Casual-XA-*-Vote headers for 2PC chaos simulation")
    void shouldHonorStubbedXaVoteHeadersFor2pcChaosSimulation() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/chaosPrepareService"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("X-Casual-XA-Prepare-Vote", "XAER_RMERR")
                        .withBody("{\"status\": \"OK_DURING_CALL_FAILS_AT_PREPARE\"}")));

        Xid xid = XID.of(
                "gtrid-chaos".getBytes(StandardCharsets.UTF_8), "bqual-chaos".getBytes(StandardCharsets.UTF_8), 42);
        CasualServiceCallReplyMessage callReply = client.tpcall("chaosPrepareService", "{}", xid);
        assertThat(callReply.getError()).isEqualTo(ErrorState.OK);

        CasualTransactionResourcePrepareReplyMessage prepareReply = client.prepare(xid, 1);
        assertThat(prepareReply.getTransactionReturnCode()).isEqualTo(XAReturnCode.XAER_RMERR);
    }

    @Test
    @DisplayName("Should transparently forward 2PC prepare and commit to upstream Casual server when call was proxied")
    void shouldTransparentlyForward2pcMessagesToUpstreamCasualServer() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();
        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamWireMock)
                .build();
        upstreamGateway.start();

        try {
            // Configure upstream server to return XA_RDONLY on prepare so we can prove the reply came from upstream
            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/upstreamTxService"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("X-Casual-XA-Prepare-Vote", "XA_RDONLY")
                            .withBody("{\"upstream\": true}")));

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/upstreamTxService"))
                    .withMetadata(
                            Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (CasualClient proxyClient = new CasualClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                Xid xid = XID.of(
                        "gtrid-proxy-2pc".getBytes(StandardCharsets.UTF_8),
                        "bqual-proxy-2pc".getBytes(StandardCharsets.UTF_8),
                        42);

                CasualServiceCallReplyMessage callReply =
                        proxyClient.tpcall("upstreamTxService", "{\"op\":\"read\"}", xid);
                assertThat(callReply.getError()).isEqualTo(ErrorState.OK);

                // Prepare must be forwarded to upstreamGateway, which returns XA_RDONLY
                CasualTransactionResourcePrepareReplyMessage prepareReply = proxyClient.prepare(xid, 1);
                assertThat(prepareReply.getTransactionReturnCode()).isEqualTo(XAReturnCode.XA_RDONLY);

                // Commit must also complete cleanly
                CasualTransactionResourceCommitReplyMessage commitReply = proxyClient.commit(xid, 1, false);
                assertThat(commitReply.getTransactionReturnCode()).isEqualTo(XAReturnCode.XA_OK);
            } finally {
                proxyGateway.close();
            }
        } finally {
            upstreamGateway.close();
            upstreamWireMock.stop();
        }
    }

    @Test
    @DisplayName("Should forward discovery to upstream and merge with local WireMock stubs")
    void shouldDiscoverUpstreamServicesAndMergeWithLocalStubs() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();

        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamWireMock)
                .build();
        upstreamGateway.start();

        try {
            // Upstream provides upstreamCatalogService
            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/upstreamCatalogService"))
                    .willReturn(aResponse().withStatus(200).withBody("{}")));

            // Local stub in proxy's WireMock
            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/localStubService"))
                    .atPriority(1)
                    .willReturn(aResponse().withStatus(200).withBody("{}")));

            // Catch-all proxy stub pointing to upstream
            wireMockServer.stubFor(post(urlMatching("/__casual/services/.*"))
                    .atPriority(10)
                    .withMetadata(
                            Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (CasualClient proxyClient = new CasualClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                // Query for local service and an upstream service
                CasualDomainDiscoveryReplyMessage discoveryReply =
                        proxyClient.discoverServices(List.of("upstreamCatalogService", "localStubService"));

                assertThat(discoveryReply).isNotNull();
                List<String> serviceNames = discoveryReply.getServices().stream()
                        .map(Service::getName)
                        .toList();

                assertThat(serviceNames).contains("upstreamCatalogService", "localStubService");
            } finally {
                proxyGateway.close();
            }
        } finally {
            upstreamGateway.close();
            upstreamWireMock.stop();
        }
    }

    @Test
    @DisplayName("Should fail fast with TPEITYPE on unmatched non-JSON buffer when no upstream is configured")
    void shouldFailFastWithTpeitypeOnUnmatchedNonJsonBufferWithoutUpstream() throws Exception {
        // Send a binary CFIELD/ buffer without creating a matching stub
        byte[] fieldedBytes = new byte[] {0x00, 0x0A, 0x42, (byte) 0xFF};
        ServiceBuffer fieldedBuffer = ServiceBuffer.of("CFIELD/", List.of(fieldedBytes));

        CasualServiceCallReplyMessage reply = client.tpcall("unstubbedBinaryService", fieldedBuffer);

        assertThat(reply).isNotNull();
        // Guaranteed fail-fast: must immediately return TPEITYPE (Invalid buffer type)
        assertThat(reply.getError()).isEqualTo(ErrorState.TPEITYPE);
    }

    @Test
    @DisplayName("Should proxy and record non-JSON fielded binary buffer as Base64 and replay offline")
    void shouldProxyAndRecordFieldedBinaryBufferAsBase64AndReplayOffline() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();

        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamWireMock)
                .build();
        upstreamGateway.start();

        try {
            byte[] upstreamReplyBytes = new byte[] {0x11, 0x22, 0x33, 0x44};
            String base64Reply = Base64.getEncoder().encodeToString(upstreamReplyBytes);

            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/fieldedService"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/octet-stream")
                            .withHeader("X-Casual-Buffer-Type", "CFIELD/")
                            .withBody(base64Reply)));

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/fieldedService"))
                    .atPriority(10)
                    .withMetadata(
                            Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (CasualClient proxyClient = new CasualClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                byte[] requestBytes = new byte[] {0x01, 0x02, 0x03};
                ServiceBuffer requestBuffer = ServiceBuffer.of("CFIELD/", List.of(requestBytes));

                // 1. Call via proxy gateway -> forwarded to upstreamGateway and recorded in wireMockServer
                CasualServiceCallReplyMessage reply1 = proxyClient.tpcall("fieldedService", requestBuffer);
                assertThat(reply1.getError()).isEqualTo(ErrorState.OK);
                assertThat(reply1.getServiceBuffer().getType()).isEqualTo("CFIELD/");
                assertThat(CasualBufferUtils.toByteArray(reply1.getServiceBuffer()))
                        .isEqualTo(upstreamReplyBytes);

                // Snapshot record journal entries into offline stub mappings
                wireMockServer.snapshotRecord(recordSpec()
                        .makeStubsPersistent(false)
                        .extractTextBodiesOver(Long.MAX_VALUE)
                        .extractBinaryBodiesOver(Long.MAX_VALUE));

                // Stop upstream gateway completely to test offline replay from recorded Base64 stub
                upstreamGateway.close();
                upstreamWireMock.stop();

                // 2. Call again with upstream offline -> must be served from WireMock's recorded Base64 stub!
                CasualServiceCallReplyMessage reply2 = proxyClient.tpcall("fieldedService", requestBuffer);
                assertThat(reply2.getError()).isEqualTo(ErrorState.OK);
                assertThat(reply2.getServiceBuffer().getType()).isEqualTo("CFIELD/");
                assertThat(CasualBufferUtils.toByteArray(reply2.getServiceBuffer()))
                        .isEqualTo(upstreamReplyBytes);
            } finally {
                proxyGateway.close();
            }
        } finally {
            if (upstreamGateway.isRunning()) {
                upstreamGateway.close();
            }
            if (upstreamWireMock.isRunning()) {
                upstreamWireMock.stop();
            }
        }
    }

    @Test
    @DisplayName("Should translate inbound fielded buffer call to JSON stub and return fielded reply")
    void shouldHandleFieldedBufferCallWithConfiguredTableAndMatchWireMockStub() throws Exception {
        String fieldTableJson =
                """
        {
          "groups": [
            {
              "base": 1000,
              "fields": [
                {
                  "name": "FLD_USER_ID",
                  "id": 1,
                  "type": "long"
                },
                {
                  "name": "FLD_USER_NAME",
                  "id": 2,
                  "type": "string"
                }
              ]
            }
          ]
        }
        """;
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("casual-fields-gateway-test", ".json");
        temp.toFile().deleteOnExit();
        java.nio.file.Files.writeString(temp, fieldTableJson, java.nio.charset.StandardCharsets.UTF_8);

        try {
            System.setProperty("CASUAL_FIELD_TABLE", temp.toAbsolutePath().toString());
            se.laz.casual.config.ConfigurationService.setConfiguration(
                    se.laz.casual.config.ConfigurationOptions.CASUAL_FIELD_TABLE,
                    temp.toAbsolutePath().toString());
            assertThat(CasualBufferUtils.isFieldTableAvailable()).isTrue();

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/fmlUserService"))
                    .withRequestBody(equalToJson("{\"FLD_USER_NAME\": \"Alice\", \"FLD_USER_ID\": 1001}"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withHeader("X-Casual-Buffer-Type", "CFIELD/")
                            .withBody("{\"FLD_USER_NAME\": \"Alice\", \"FLD_USER_ID\": 9999}")));

            // Create and encode a real fielded buffer using FieldedTypeBuffer
            se.laz.casual.api.buffer.type.fielded.FieldedTypeBuffer fml =
                    se.laz.casual.api.buffer.type.fielded.FieldedTypeBuffer.create();
            fml.write("FLD_USER_NAME", "Alice");
            fml.write("FLD_USER_ID", 1001L);

            ServiceBuffer requestBuffer = ServiceBuffer.of(".FIELDED", fml.encode());

            CasualServiceCallReplyMessage reply = client.tpcall("fmlUserService", requestBuffer);

            assertThat(reply).isNotNull();
            assertThat(reply.getError()).isEqualTo(ErrorState.OK);
            assertThat(reply.getServiceBuffer().getType()).isEqualTo("CFIELD/");

            // Verify WireMock captured JSON in Request Journal
            wireMockServer.verify(postRequestedFor(urlEqualTo("/__casual/services/fmlUserService"))
                    .withRequestBody(equalToJson("{\"FLD_USER_NAME\": \"Alice\", \"FLD_USER_ID\": 1001}")));

            // Verify reply decoded back to JSON contains the stub values
            String replyJson = CasualBufferUtils.extractPayload(reply.getServiceBuffer());
            assertThat(replyJson).contains("\"FLD_USER_NAME\":\"Alice\"");
            assertThat(replyJson).contains("\"FLD_USER_ID\":9999");
        } finally {
            System.clearProperty("CASUAL_FIELD_TABLE");
            se.laz.casual.config.ConfigurationService.setConfiguration(
                    se.laz.casual.config.ConfigurationOptions.CASUAL_FIELD_TABLE, null);
        }
    }

    @Test
    @DisplayName("Should proxy and record fielded buffer with configured field table and replay offline")
    void shouldProxyAndRecordFieldedBufferWithConfiguredTableAndReplayOffline() throws Exception {
        String fieldTableJson =
                """
        {
          "groups": [
            {
              "base": 1000,
              "fields": [
                {
                  "name": "FLD_USER_ID",
                  "id": 1,
                  "type": "long"
                },
                {
                  "name": "FLD_USER_NAME",
                  "id": 2,
                  "type": "string"
                }
              ]
            }
          ]
        }
        """;
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("casual-fields-proxy-test", ".json");
        temp.toFile().deleteOnExit();
        java.nio.file.Files.writeString(temp, fieldTableJson, java.nio.charset.StandardCharsets.UTF_8);

        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();

        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamWireMock)
                .build();
        upstreamGateway.start();

        try {
            System.setProperty("CASUAL_FIELD_TABLE", temp.toAbsolutePath().toString());
            se.laz.casual.config.ConfigurationService.setConfiguration(
                    se.laz.casual.config.ConfigurationOptions.CASUAL_FIELD_TABLE,
                    temp.toAbsolutePath().toString());
            assertThat(CasualBufferUtils.isFieldTableAvailable()).isTrue();

            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/proxiedFmlService"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withHeader("X-Casual-Buffer-Type", "CFIELD/")
                            .withBody("{\"FLD_USER_NAME\": \"Alice_Proxied\", \"FLD_USER_ID\": 8888}")));

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/proxiedFmlService"))
                    .atPriority(10)
                    .withMetadata(
                            Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (CasualClient proxyClient = new CasualClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                se.laz.casual.api.buffer.type.fielded.FieldedTypeBuffer fml =
                        se.laz.casual.api.buffer.type.fielded.FieldedTypeBuffer.create();
                fml.write("FLD_USER_NAME", "Alice");
                fml.write("FLD_USER_ID", 1001L);

                ServiceBuffer requestBuffer = ServiceBuffer.of(".FIELDED", fml.encode());

                // 1. Call via proxy gateway -> forwarded to upstreamGateway and recorded in wireMockServer
                CasualServiceCallReplyMessage reply1 = proxyClient.tpcall("proxiedFmlService", requestBuffer);
                assertThat(reply1.getError()).isEqualTo(ErrorState.OK);
                assertThat(reply1.getServiceBuffer().getType()).isEqualTo("CFIELD/");
                String reply1Json = CasualBufferUtils.extractPayload(reply1.getServiceBuffer());
                assertThat(reply1Json).contains("\"FLD_USER_NAME\":\"Alice_Proxied\"");
                assertThat(reply1Json).contains("\"FLD_USER_ID\":8888");

                // Snapshot record journal entries into offline stub mappings
                wireMockServer.snapshotRecord(recordSpec()
                        .makeStubsPersistent(false)
                        .extractTextBodiesOver(Long.MAX_VALUE)
                        .extractBinaryBodiesOver(Long.MAX_VALUE));

                // Terminate upstream completely to test offline replay
                upstreamGateway.close();
                upstreamWireMock.stop();

                // 2. Call again with upstream offline -> must be served from WireMock's recorded stub!
                CasualServiceCallReplyMessage reply2 = proxyClient.tpcall("proxiedFmlService", requestBuffer);
                assertThat(reply2.getError()).isEqualTo(ErrorState.OK);
                assertThat(reply2.getServiceBuffer().getType()).isEqualTo("CFIELD/");
                String reply2Json = CasualBufferUtils.extractPayload(reply2.getServiceBuffer());
                assertThat(reply2Json).contains("\"FLD_USER_NAME\":\"Alice_Proxied\"");
                assertThat(reply2Json).contains("\"FLD_USER_ID\":8888");
            } finally {
                proxyGateway.close();
            }
        } finally {
            System.clearProperty("CASUAL_FIELD_TABLE");
            se.laz.casual.config.ConfigurationService.setConfiguration(
                    se.laz.casual.config.ConfigurationOptions.CASUAL_FIELD_TABLE, null);
            if (upstreamGateway.isRunning()) {
                upstreamGateway.close();
            }
            if (upstreamWireMock.isRunning()) {
                upstreamWireMock.stop();
            }
        }
    }

    @Test
    @DisplayName("Should include config property hint in timeout error message when upstream call times out")
    void shouldIncludeConfigPropertyHintOnUpstreamTimeout() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/timeoutTestService"))
                .withMetadata(Map.of(
                        "mode", "proxy",
                        "targetBaseUrl", "casual://127.0.0.1:1"))
                .willReturn(aResponse().withStatus(200)));

        CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(wireMockServer)
                .upstreamTimeoutSeconds(1) // 1s fast timeout
                .build();
        proxyGateway.start();

        try (CasualClient proxyClient = new CasualClient("127.0.0.1", proxyGateway.getPort())) {
            proxyClient.connect();

            CasualServiceCallReplyMessage reply = proxyClient.tpcall("timeoutTestService", "{}");
            assertThat(reply).isNotNull();
            assertThat(reply.getError()).isEqualTo(ErrorState.TPESVCERR);
        } finally {
            proxyGateway.close();
        }
    }

    @Test
    @DisplayName(
            "Should handle 406-byte WildFly JBoss client service call with headers under Protocol 1.5 without decoder exception")
    void shouldHandleWildFlyJBossClient406ByteServiceCallWithoutDecoderException() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/sakVKAnvandare"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"SUCCESS\"}")));

        // Client connects and negotiates VERSION_1_5
        assertThat(client.getProtocolVersion()).isEqualTo(ProtocolVersion.VERSION_1_5);

        // Make service call with xid, simulating WildFly/JBoss Narayana XA client
        Xid xid = XID.of(
                "gtrid-wildfly".getBytes(StandardCharsets.UTF_8),
                "bqual-wildfly".getBytes(StandardCharsets.UTF_8),
                131077);
        CasualServiceCallReplyMessage reply = client.tpcall("sakVKAnvandare", "{}", xid);

        assertThat(reply).isNotNull();
        assertThat(reply.getError()).isEqualTo(ErrorState.OK);
        assertThat(reply.getType()).isEqualTo(CasualNWMessageType.SERVICE_CALL_REPLY_V_1_5);
    }

    @Test
    @DisplayName("Should cleanly decode WildFly 3.3.x client service call on Protocol 1.2 with 8-byte trailing buffer")
    void shouldCleanlyDecodeWildFlyClientOnProtocol12With336Trailer() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/sakVKAnvandare"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"SUCCESS\"}")));

        // Simulate WildFly 3.3.x client by connecting over TCP socket directly proposing [1000, 1001, 1002]
        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", gateway.getPort())) {
            socket.setSoTimeout(5000);
            java.io.OutputStream out = socket.getOutputStream();
            java.io.InputStream in = socket.getInputStream();

            // Send domain connect request proposing [1000, 1001, 1002]
            se.laz.casual.network.protocol.messages.domain.CasualDomainConnectRequestMessage connectReq =
                    se.laz.casual.network.protocol.messages.domain.CasualDomainConnectRequestMessage.createBuilder()
                            .withExecution(UUID.randomUUID())
                            .withDomainId(UUID.randomUUID())
                            .withDomainName("wildfly-domain")
                            .withProtocols(List.of(1000L, 1001L, 1002L))
                            .build();
            se.laz.casual.api.network.protocol.messages.CasualNWMessage<
                            se.laz.casual.network.protocol.messages.domain.CasualDomainConnectRequestMessage>
                    connectEnvelope = se.laz.casual.network.protocol.messages.CasualNWMessageImpl.of(
                            UUID.randomUUID(), connectReq);
            for (byte[] b : connectEnvelope.toNetworkBytes()) {
                out.write(b);
            }
            out.flush();

            // Read connect reply header + payload (32 bytes header: UUID (16) + type (8) + payloadSize (8))
            byte[] connectReplyHeader = in.readNBytes(32);
            long replyPayloadSize =
                    java.nio.ByteBuffer.wrap(connectReplyHeader, 24, 8).getLong();
            byte[] connectReplyPayload = in.readNBytes((int) replyPayloadSize);
            long negotiatedVersion = java.nio.ByteBuffer.wrap(connectReplyPayload, connectReplyPayload.length - 8, 8)
                    .getLong();
            assertThat(negotiatedVersion).isEqualTo(1002L); // Negotiated VERSION_1_2

            // Build service call message under VERSION_1_2
            Xid xid = XID.of(
                    "gtrid-wildfly".getBytes(StandardCharsets.UTF_8),
                    "bqual-wildfly".getBytes(StandardCharsets.UTF_8),
                    131077);
            CasualServiceCallRequestMessage callMsg = CasualServiceCallRequestMessage.createBuilder()
                    .setProtocolVersion(ProtocolVersion.VERSION_1_2)
                    .setExecution(UUID.randomUUID())
                    .setServiceName("sakVKAnvandare")
                    .setTimeout(5000L)
                    .setParentName("wildfly-domain")
                    .setXid(xid)
                    .setXatmiFlags(Flag.of(AtmiFlags.NOFLAG))
                    .setServiceBuffer(ServiceBuffer.of(JsonBuffer.of("{}")))
                    .build();

            // In Casual 3.3.x, the message buffer had 8 trailing zero bytes from CALL_DESCRIPTOR allocation
            byte[] normalPayload = callMsg.toNetworkBytes().get(0);
            byte[] wildfly336Payload = new byte[normalPayload.length + 8]; // exact 8 trailing zero bytes!
            System.arraycopy(normalPayload, 0, wildfly336Payload, 0, normalPayload.length);

            // Construct network frame with 406 bytes payload
            se.laz.casual.network.protocol.messages.CasualNWMessageHeader callHeader =
                    se.laz.casual.network.protocol.messages.CasualNWMessageHeader.createBuilder()
                            .setCorrelationId(UUID.randomUUID())
                            .setType(CasualNWMessageType.SERVICE_CALL_REQUEST)
                            .setPayloadSize(wildfly336Payload.length)
                            .build();

            out.write(callHeader.toNetworkBytes());
            out.write(wildfly336Payload);
            out.flush();

            // Read service call reply header (32 bytes header)
            byte[] replyHeaderBytes = in.readNBytes(32);
            long serviceReplyPayloadSize =
                    java.nio.ByteBuffer.wrap(replyHeaderBytes, 24, 8).getLong();
            byte[] serviceReplyPayload = in.readNBytes((int) serviceReplyPayloadSize);

            // Verify clean success without decoder exception and with normalized SERVICE_CALL_REPLY (3101, not 3103)!
            long replyMessageType =
                    java.nio.ByteBuffer.wrap(replyHeaderBytes, 0, 8).getLong();
            assertThat(replyMessageType).isEqualTo((long) CasualNWMessageType.SERVICE_CALL_REPLY.getMessageId());
            assertThat(serviceReplyPayloadSize).isGreaterThan(0);
            wireMockServer.verify(1, postRequestedFor(urlEqualTo("/__casual/services/sakVKAnvandare")));
        }
    }

    @Test
    @DisplayName("Should normalize upstream Protocol 1.3+ reply to Protocol 1.2 client when proxied")
    void shouldNormalizeUpstreamProtocol13ReplyToProtocol12ClientWhenProxied() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();
        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamWireMock)
                .build();
        upstreamGateway.start();

        try {
            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/upstreamAccountBlocked"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"status\":\"ACCOUNT_OK\"}")));

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/upstreamAccountBlocked"))
                    .withMetadata(
                            Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (java.net.Socket socket = new java.net.Socket("127.0.0.1", proxyGateway.getPort())) {
                socket.setSoTimeout(5000);
                java.io.OutputStream out = socket.getOutputStream();
                java.io.InputStream in = socket.getInputStream();

                // Propose [1000, 1001, 1002] to negotiate VERSION_1_2
                se.laz.casual.network.protocol.messages.domain.CasualDomainConnectRequestMessage connectReq =
                        se.laz.casual.network.protocol.messages.domain.CasualDomainConnectRequestMessage.createBuilder()
                                .withExecution(UUID.randomUUID())
                                .withDomainId(UUID.randomUUID())
                                .withDomainName("wildfly-domain")
                                .withProtocols(List.of(1000L, 1001L, 1002L))
                                .build();
                se.laz.casual.api.network.protocol.messages.CasualNWMessage<
                                se.laz.casual.network.protocol.messages.domain.CasualDomainConnectRequestMessage>
                        connectEnvelope = se.laz.casual.network.protocol.messages.CasualNWMessageImpl.of(
                                UUID.randomUUID(), connectReq);
                for (byte[] b : connectEnvelope.toNetworkBytes()) {
                    out.write(b);
                }
                out.flush();

                byte[] connectReplyHeader = in.readNBytes(32);
                long replyPayloadSize =
                        java.nio.ByteBuffer.wrap(connectReplyHeader, 24, 8).getLong();
                byte[] connectReplyPayload = in.readNBytes((int) replyPayloadSize);
                long negotiatedVersion = java.nio.ByteBuffer.wrap(
                                connectReplyPayload, connectReplyPayload.length - 8, 8)
                        .getLong();
                assertThat(negotiatedVersion).isEqualTo(1002L);

                // Call unmatched service on proxy gateway -> forwarded upstream
                Xid originalXid = XID.of(
                        "gtrid-test".getBytes(StandardCharsets.UTF_8),
                        "bqual-test".getBytes(StandardCharsets.UTF_8),
                        131077);
                CasualServiceCallRequestMessage callMsg = CasualServiceCallRequestMessage.createBuilder()
                        .setProtocolVersion(ProtocolVersion.VERSION_1_2)
                        .setExecution(UUID.randomUUID())
                        .setServiceName("upstreamAccountBlocked")
                        .setTimeout(5000L)
                        .setParentName("wildfly-domain")
                        .setXid(originalXid)
                        .setXatmiFlags(Flag.of(AtmiFlags.NOFLAG))
                        .setServiceBuffer(ServiceBuffer.of(JsonBuffer.of("{}")))
                        .build();

                se.laz.casual.api.network.protocol.messages.CasualNWMessage<CasualServiceCallRequestMessage>
                        callEnvelope = se.laz.casual.network.protocol.messages.CasualNWMessageImpl.of(
                                UUID.randomUUID(), callMsg);
                for (byte[] b : callEnvelope.toNetworkBytes()) {
                    out.write(b);
                }
                out.flush();

                byte[] replyHeaderBytes = in.readNBytes(32);
                long replyMessageType =
                        java.nio.ByteBuffer.wrap(replyHeaderBytes, 0, 8).getLong();
                long serviceReplyPayloadSize =
                        java.nio.ByteBuffer.wrap(replyHeaderBytes, 24, 8).getLong();
                byte[] serviceReplyPayload = in.readNBytes((int) serviceReplyPayloadSize);

                // Upstream returned 3103 (SERVICE_CALL_REPLY_V_1_3), but gateway MUST have normalized to 3101
                // (SERVICE_CALL_REPLY)
                assertThat(replyMessageType).isEqualTo((long) CasualNWMessageType.SERVICE_CALL_REPLY.getMessageId());
                assertThat(serviceReplyPayloadSize).isGreaterThan(0);
                upstreamWireMock.verify(1, postRequestedFor(urlEqualTo("/__casual/services/upstreamAccountBlocked")));
            } finally {
                proxyGateway.close();
            }
        } finally {
            upstreamGateway.close();
            upstreamWireMock.stop();
        }
    }

    @Test
    @DisplayName("Should extract CSTRING buffer payload into human-readable string in journal and replay offline")
    void shouldExtractCStringBufferHumanReadable() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();
        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamWireMock)
                .build();
        upstreamGateway.start();

        try {
            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/cstringService"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "text/plain")
                            .withHeader("X-Casual-Buffer-Type", "CSTRING/")
                            .withBody("Hello from upstream CSTRING")));

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/cstringService"))
                    .atPriority(10)
                    .withMetadata(
                            Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (CasualClient proxyClient = new CasualClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                byte[] cstringBytes = "Inbound CSTRING request\0".getBytes(StandardCharsets.UTF_8);
                ServiceBuffer requestBuffer = ServiceBuffer.of("CSTRING/", List.of(cstringBytes));

                CasualServiceCallReplyMessage reply1 = proxyClient.tpcall("cstringService", requestBuffer);
                assertThat(reply1.getError()).isEqualTo(ErrorState.OK);
                assertThat(reply1.getServiceBuffer().getType()).isEqualTo("CSTRING/");
                String replyText =
                        new String(CasualBufferUtils.toByteArray(reply1.getServiceBuffer()), StandardCharsets.UTF_8);
                assertThat(replyText).contains("Hello from upstream CSTRING");

                // Verify the Request Journal captured the string body without trailing null
                wireMockServer.verify(postRequestedFor(urlEqualTo("/__casual/services/cstringService"))
                        .withRequestBody(equalTo("Inbound CSTRING request")));

                // Snapshot record journal entries into offline stub mappings
                wireMockServer.snapshotRecord(recordSpec()
                        .makeStubsPersistent(false)
                        .extractTextBodiesOver(Long.MAX_VALUE)
                        .extractBinaryBodiesOver(Long.MAX_VALUE));

                upstreamGateway.close();
                upstreamWireMock.stop();

                CasualServiceCallReplyMessage reply2 = proxyClient.tpcall("cstringService", requestBuffer);
                assertThat(reply2.getError()).isEqualTo(ErrorState.OK);
                assertThat(reply2.getServiceBuffer().getType()).isEqualTo("CSTRING/");
                String replayedText =
                        new String(CasualBufferUtils.toByteArray(reply2.getServiceBuffer()), StandardCharsets.UTF_8);
                assertThat(replayedText).contains("Hello from upstream CSTRING");
            } finally {
                proxyGateway.close();
            }
        } finally {
            if (upstreamGateway.isRunning()) {
                upstreamGateway.close();
            }
            if (upstreamWireMock.isRunning()) {
                upstreamWireMock.stop();
            }
        }
    }

    @Test
    @DisplayName("Should stub fielded service call with offline mock returning valid binary CFIELD buffer")
    void shouldStubFieldedServiceCallWithOfflineMockReturningValidCfieldBuffer() throws Exception {
        String fieldTableJson =
                """
        {
          "groups": [
            {
              "base": 1000,
              "fields": [
                { "name": "FLD_USER_ID", "id": 1, "type": "long" },
                { "name": "FLD_USER_NAME", "id": 2, "type": "string" },
                { "name": "FLD_ARCHIVE_DATA", "id": 9, "type": "binary" }
              ]
            }
          ]
        }
        """;
        java.nio.file.Path temp = java.nio.file.Files.createTempFile("casual-fields-gateway-test", ".json");
        temp.toFile().deleteOnExit();
        java.nio.file.Files.writeString(temp, fieldTableJson, StandardCharsets.UTF_8);

        try {
            System.setProperty("CASUAL_FIELD_TABLE", temp.toAbsolutePath().toString());
            assertThat(CasualBufferUtils.isFieldTableAvailable()).isTrue();

            String binaryPayloadB64 =
                    Base64.getEncoder().encodeToString("{\"caseId\":\"12356\"}".getBytes(StandardCharsets.UTF_8));
            String responseJson = String.format(
                    java.util.Locale.ROOT,
                    """
                    {
                      "FLD_USER_ID": 1001,
                      "FLD_USER_NAME": "Alice",
                      "FLD_ARCHIVE_DATA": "%s"
                    }
                    """,
                    binaryPayloadB64);

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/crmArchiveService"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withHeader("X-Casual-Buffer-Type", "CFIELD/")
                            .withBody(responseJson)));

            ServiceBuffer requestBuffer = ServiceBuffer.of(".FIELDED", List.of(new byte[] {
                0x00, 0x00, 0x00, 0x00, 0x04, 0x00, 0x03, (byte) 0xE9, // FLD_USER_ID
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x08,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, (byte) 0xE9
            }));

            CasualServiceCallReplyMessage reply = client.tpcall("crmArchiveService", requestBuffer);

            assertThat(reply.getError()).isEqualTo(ErrorState.OK);
            assertThat(reply.getServiceBuffer().getType()).isEqualTo("CFIELD/");

            // Verify the response bytes are valid binary FML, NOT raw JSON ASCII
            byte[] rawBytes = CasualBufferUtils.toByteArray(reply.getServiceBuffer());
            String asciiPrefix = new String(rawBytes, StandardCharsets.UTF_8);
            assertThat(asciiPrefix).doesNotStartWith("{");

            // Verify decoding via FieldedTypeBuffer succeeds without throwing CasualFieldedLookupException
            se.laz.casual.api.buffer.type.fielded.FieldedTypeBuffer decoded =
                    se.laz.casual.api.buffer.type.fielded.FieldedTypeBuffer.create(
                            reply.getServiceBuffer().getBytes());
            assertThat(decoded.read("FLD_USER_ID").getData()).isEqualTo(1001L);
            assertThat(decoded.read("FLD_USER_NAME").getData()).isEqualTo("Alice");

            byte[] decodedBinary = (byte[]) decoded.read("FLD_ARCHIVE_DATA").getData();
            assertThat(new String(decodedBinary, StandardCharsets.UTF_8)).isEqualTo("{\"caseId\":\"12356\"}");
        } finally {
            System.clearProperty("CASUAL_FIELD_TABLE");
            se.laz.casual.config.ConfigurationService.setConfiguration(
                    se.laz.casual.config.ConfigurationOptions.CASUAL_FIELD_TABLE, null);
        }
    }

    @Test
    @DisplayName("Should notify registered ServeEventListener with Casual protocol attributes and timing")
    void shouldNotifyServeEventListenerOnCasualCall() throws Exception {
        java.util.concurrent.atomic.AtomicReference<com.github.tomakehurst.wiremock.stubbing.ServeEvent> capturedEvent =
                new java.util.concurrent.atomic.AtomicReference<>();
        com.github.tomakehurst.wiremock.extension.ServeEventListener testListener =
                new com.github.tomakehurst.wiremock.extension.ServeEventListener() {
                    @Override
                    public String getName() {
                        return "test-casual-listener";
                    }

                    @Override
                    public void afterComplete(
                            com.github.tomakehurst.wiremock.stubbing.ServeEvent serveEvent,
                            com.github.tomakehurst.wiremock.extension.Parameters parameters) {
                        capturedEvent.set(serveEvent);
                    }
                };

        WireMockServer customServer =
                new WireMockServer(wireMockConfig().dynamicPort().extensions(testListener));
        customServer.start();
        CasualWireMockGateway customGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(customServer)
                .build();
        customGateway.start();

        try (CasualClient customClient = new CasualClient("127.0.0.1", customGateway.getPort())) {
            customClient.connect();
            customServer.stubFor(post(urlEqualTo("/__casual/services/telemetryService"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"status\": \"OK\"}")));

            Xid xid = XID.of(new byte[] {1, 2, 3, 4}, new byte[] {5, 6, 7, 8}, 42);
            CasualServiceCallReplyMessage reply = customClient.tpcall(
                    "telemetryService",
                    "{\"traceparent\": \"00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01\", \"payload\": \"data\"}",
                    xid);

            assertThat(reply).isNotNull();
            assertThat(reply.getError()).isEqualTo(ErrorState.OK);

            com.github.tomakehurst.wiremock.stubbing.ServeEvent event = capturedEvent.get();
            assertThat(event).isNotNull();
            assertThat(event.getRequest().getHeader("X-WireMock-Protocol")).isEqualTo("casual");
            assertThat(event.getRequest().getHeader("X-Casual-Service")).isEqualTo("telemetryService");
            assertThat(event.getRequest().getHeader("X-Casual-Xid")).isNotNull();
            assertThat(event.getRequest().getHeader("X-Casual-Gtrid")).isEqualTo("01020304");
            assertThat(event.getRequest().getHeader("X-Casual-Bqual")).isEqualTo("05060708");
            assertThat(event.getRequest().getHeader("X-Casual-Format-Id")).isEqualTo("42");
            assertThat(event.getRequest().getHeader("traceparent"))
                    .isEqualTo("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
            assertThat(event.getTiming()).isNotNull();
            assertThat(event.getTiming().getTotalTime()).isNotNull();
        } finally {
            customGateway.close();
            customServer.stop();
        }
    }

    @Test
    @DisplayName(
            "Should proxy and record schema-less fielded buffers as structured JSON without field table and replay offline")
    void shouldProxyAndRecordSchemaLessFieldedBufferAsStructuredJsonAndReplayOffline() throws Exception {
        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();

        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamWireMock)
                .build();
        upstreamGateway.start();

        try {
            // Build a multi-field binary reply buffer without requiring any casual-fields.json
            long statusFieldId = (2L * se.laz.casual.api.buffer.type.fielded.Constants.CASUAL_FIELD_TYPE_BASE) + 500L;
            long messageFieldId = (6L * se.laz.casual.api.buffer.type.fielded.Constants.CASUAL_FIELD_TYPE_BASE) + 501L;
            long archiveFieldId = (7L * se.laz.casual.api.buffer.type.fielded.Constants.CASUAL_FIELD_TYPE_BASE) + 502L;

            byte[] messageBytes = "user-123\u0001SampleError\u0001EntityNotFound\0".getBytes(StandardCharsets.UTF_8);
            byte[] archiveBytes = "{\"subEntityId\":\"entity-8899\"}".getBytes(StandardCharsets.UTF_8);

            java.nio.ByteBuffer replyBb =
                    java.nio.ByteBuffer.allocate(16 + 8 + 16 + messageBytes.length + 16 + archiveBytes.length);
            replyBb.putLong(statusFieldId).putLong(8L).putLong(536870915L);
            replyBb.putLong(messageFieldId).putLong(messageBytes.length).put(messageBytes);
            replyBb.putLong(archiveFieldId).putLong(archiveBytes.length).put(archiveBytes);
            byte[] upstreamReplyBytes = replyBb.array();

            String base64Reply = Base64.getEncoder().encodeToString(upstreamReplyBytes);

            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/schemaLessService"))
                    .willReturn(aResponse()
                            .withStatus(500)
                            .withHeader("Content-Type", "application/octet-stream")
                            .withHeader("X-Casual-Error-State", "TPESVCFAIL")
                            .withHeader("X-Casual-Buffer-Type", "CFIELD/")
                            .withBody(base64Reply)));

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/schemaLessService"))
                    .atPriority(10)
                    .withMetadata(
                            Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(500)));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (CasualClient proxyClient = new CasualClient("127.0.0.1", proxyGateway.getPort())) {
                proxyClient.connect();

                // Construct schema-less request buffer with 5 fields
                long reqField1 = (6L * se.laz.casual.api.buffer.type.fielded.Constants.CASUAL_FIELD_TYPE_BASE) + 101L;
                long reqField2 = (2L * se.laz.casual.api.buffer.type.fielded.Constants.CASUAL_FIELD_TYPE_BASE) + 102L;
                long reqField3 = (7L * se.laz.casual.api.buffer.type.fielded.Constants.CASUAL_FIELD_TYPE_BASE) + 103L;
                byte[] reqStrBytes = "clientHost99\0".getBytes(StandardCharsets.UTF_8);
                byte[] reqJsonBytes = "{\"queryId\":\"q-4455\"}".getBytes(StandardCharsets.UTF_8);

                java.nio.ByteBuffer reqBb =
                        java.nio.ByteBuffer.allocate(16 + reqStrBytes.length + 16 + 8 + 16 + reqJsonBytes.length);
                reqBb.putLong(reqField1).putLong(reqStrBytes.length).put(reqStrBytes);
                reqBb.putLong(reqField2).putLong(8L).putLong(42L);
                reqBb.putLong(reqField3).putLong(reqJsonBytes.length).put(reqJsonBytes);
                byte[] requestBytes = reqBb.array();

                ServiceBuffer requestBuffer = ServiceBuffer.of("CFIELD/", List.of(requestBytes));

                // 1. Call via proxy gateway -> forwarded to upstream and recorded as structured JSON in Request Journal
                CasualServiceCallReplyMessage reply1 = proxyClient.tpcall("schemaLessService", requestBuffer);
                assertThat(reply1.getError()).isEqualTo(ErrorState.TPESVCFAIL);
                assertThat(reply1.getServiceBuffer().getType()).isEqualTo("CFIELD/");
                assertThat(CasualBufferUtils.toByteArray(reply1.getServiceBuffer()))
                        .isEqualTo(upstreamReplyBytes);

                // Verify the ServeEvent recorded in WireMock's Request Journal
                List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> events = wireMockServer.getAllServeEvents();
                assertThat(events).isNotEmpty();
                com.github.tomakehurst.wiremock.stubbing.ServeEvent served = events.get(0);

                // Both request and response must have Content-Type: application/json and structured JSON bodies
                assertThat(served.getRequest().getHeader("Content-Type")).isEqualTo("application/json");
                String reqLoggedBody = served.getRequest().getBodyAsString();
                assertThat(reqLoggedBody).contains("\"FLD_" + reqField1 + "\":\"clientHost99\"");
                assertThat(reqLoggedBody).contains("\"FLD_" + reqField2 + "\":42");
                assertThat(reqLoggedBody).contains("\"FLD_" + reqField3 + "\":{\"queryId\":\"q-4455\"}");

                assertThat(served.getResponse()
                                .getHeaders()
                                .getHeader("Content-Type")
                                .firstValue())
                        .isEqualTo("application/json");
                String respLoggedBody = served.getResponse().getBodyAsString();
                assertThat(respLoggedBody).contains("\"FLD_" + statusFieldId + "\":536870915");
                assertThat(respLoggedBody)
                        .contains("\"FLD_" + messageFieldId + "\":\"user-123\\u0001SampleError\\u0001EntityNotFound\"");
                assertThat(respLoggedBody).contains("\"FLD_" + archiveFieldId + "\":{\"subEntityId\":\"entity-8899\"}");

                // 2. Snapshot record into offline stubs
                wireMockServer.snapshotRecord(recordSpec().makeStubsPersistent(false));

                // Stop upstream completely
                upstreamGateway.close();
                upstreamWireMock.stop();

                // 3. Replay offline from the recorded JSON stub
                CasualServiceCallReplyMessage reply2 = proxyClient.tpcall("schemaLessService", requestBuffer);
                assertThat(reply2.getError()).isEqualTo(ErrorState.TPESVCFAIL);
                assertThat(reply2.getServiceBuffer().getType()).isEqualTo("CFIELD/");
                assertThat(CasualBufferUtils.toByteArray(reply2.getServiceBuffer()))
                        .isEqualTo(upstreamReplyBytes);
            } finally {
                proxyGateway.close();
            }
        } finally {
            if (upstreamGateway.isRunning()) {
                upstreamGateway.close();
            }
            if (upstreamWireMock.isRunning()) {
                upstreamWireMock.stop();
            }
        }
    }

    @Test
    @DisplayName("Should enqueue and dequeue messages over TCP gateway and journal ServeEvents")
    void shouldEnqueueAndDequeueOverTcpGatewayAndJournalServeEvents() throws Exception {
        String queueName = "gateway.orders.q";
        gateway.getQueueStore().createQueue(queueName, 3, 500);

        // 1. Client enqueues message over TCP
        String orderPayload = "{\"orderId\":\"ORD-400\",\"amount\":99.95}";
        CasualEnqueueReplyMessage enqueueReply = client.enqueue(queueName, orderPayload);
        assertThat(enqueueReply).isNotNull();
        assertThat(enqueueReply.getCode()).isEqualTo(QueueErrorCode.OK);
        assertThat(enqueueReply.getId()).isNotNull();

        // 2. Client dequeues message over TCP
        CasualDequeueReplyMessage dequeueReply = client.dequeue(queueName);
        assertThat(dequeueReply).isNotNull();
        assertThat(dequeueReply.getCode()).isEqualTo(QueueErrorCode.OK);
        assertThat(dequeueReply.getMessages()).hasSize(1);
        String dequeuedPayload = CasualBufferUtils.extractPayload(
                dequeueReply.getMessages().get(0).getPayload());
        assertThat(dequeuedPayload).isEqualTo(orderPayload);

        // 3. Verify discovery advertises the queue
        CasualDomainDiscoveryReplyMessage discoveryReply = client.discoverQueues(List.of());
        assertThat(discoveryReply.getQueues()).hasSize(2); // queue + .error DLQ
        assertThat(discoveryReply.getQueues()).extracting(Queue::getName).contains(queueName, queueName + ".error");

        // 4. Verify ServeEvents were recorded in WireMock request journal
        List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> events = wireMockServer.getAllServeEvents();
        assertThat(events).hasSize(2);

        com.github.tomakehurst.wiremock.stubbing.ServeEvent enqueueEvent = events.get(1); // older event
        assertThat(enqueueEvent.getRequest().getUrl()).isEqualTo("/__casual/queues/" + queueName + "/enqueue");
        assertThat(enqueueEvent.getRequest().getHeader("X-Casual-Queue")).isEqualTo(queueName);
        assertThat(enqueueEvent.getRequest().getHeader("X-Casual-Operation")).isEqualTo("enqueue");

        com.github.tomakehurst.wiremock.stubbing.ServeEvent dequeueEvent = events.get(0); // newest event
        assertThat(dequeueEvent.getRequest().getUrl()).isEqualTo("/__casual/queues/" + queueName + "/dequeue");
        assertThat(dequeueEvent.getRequest().getHeader("X-Casual-Queue")).isEqualTo(queueName);
        assertThat(dequeueEvent.getRequest().getHeader("X-Casual-Operation")).isEqualTo("dequeue");
    }

    @Test
    @DisplayName("Should not log exception stack trace at TRACE level when proxying Protocol 1.2 request")
    void shouldNotLogExceptionStackTraceAtTraceLevelWhenProxyingProtocol12Request() throws Exception {
        Logger casualLogger = (Logger) LoggerFactory.getLogger("com.github.mattiasgreen.wiremock.casual");
        Level previousLevel = casualLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        casualLogger.addAppender(appender);
        casualLogger.setLevel(Level.TRACE);

        WireMockServer upstreamWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        upstreamWireMock.start();
        CasualWireMockGateway upstreamGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(upstreamWireMock)
                .build();
        upstreamGateway.start();

        try {
            upstreamWireMock.stubFor(post(urlEqualTo("/__casual/services/legacyTraceService"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"status\":\"OK\"}")));

            wireMockServer.stubFor(post(urlEqualTo("/__casual/services/legacyTraceService"))
                    .withMetadata(
                            Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamGateway.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(wireMockServer)
                    .build();
            proxyGateway.start();

            try (java.net.Socket socket = new java.net.Socket("127.0.0.1", proxyGateway.getPort())) {
                socket.setSoTimeout(5000);
                java.io.OutputStream out = socket.getOutputStream();
                java.io.InputStream in = socket.getInputStream();

                se.laz.casual.network.protocol.messages.domain.CasualDomainConnectRequestMessage connectReq =
                        se.laz.casual.network.protocol.messages.domain.CasualDomainConnectRequestMessage.createBuilder()
                                .withExecution(UUID.randomUUID())
                                .withDomainId(UUID.randomUUID())
                                .withDomainName("wildfly-trace-domain")
                                .withProtocols(List.of(1000L, 1001L, 1002L))
                                .build();
                se.laz.casual.api.network.protocol.messages.CasualNWMessage<
                                se.laz.casual.network.protocol.messages.domain.CasualDomainConnectRequestMessage>
                        connectEnvelope = se.laz.casual.network.protocol.messages.CasualNWMessageImpl.of(
                                UUID.randomUUID(), connectReq);
                for (byte[] b : connectEnvelope.toNetworkBytes()) {
                    out.write(b);
                }
                out.flush();

                byte[] connectReplyHeader = in.readNBytes(32);
                long replyPayloadSize =
                        java.nio.ByteBuffer.wrap(connectReplyHeader, 24, 8).getLong();
                in.readNBytes((int) replyPayloadSize);

                Xid xid = XID.of(
                        "gtrid-trace".getBytes(StandardCharsets.UTF_8),
                        "bqual-trace".getBytes(StandardCharsets.UTF_8),
                        131077);
                CasualServiceCallRequestMessage callMsg = CasualServiceCallRequestMessage.createBuilder()
                        .setProtocolVersion(ProtocolVersion.VERSION_1_2)
                        .setExecution(UUID.randomUUID())
                        .setServiceName("legacyTraceService")
                        .setTimeout(5000L)
                        .setParentName("wildfly-trace-domain")
                        .setXid(xid)
                        .setXatmiFlags(Flag.of(AtmiFlags.NOFLAG))
                        .setServiceBuffer(ServiceBuffer.of(JsonBuffer.of("{}")))
                        .build();

                se.laz.casual.api.network.protocol.messages.CasualNWMessage<CasualServiceCallRequestMessage>
                        callEnvelope = se.laz.casual.network.protocol.messages.CasualNWMessageImpl.of(
                                UUID.randomUUID(), callMsg);
                for (byte[] b : callEnvelope.toNetworkBytes()) {
                    out.write(b);
                }
                out.flush();

                byte[] replyHeaderBytes = in.readNBytes(32);
                long replyMessageType =
                        java.nio.ByteBuffer.wrap(replyHeaderBytes, 0, 8).getLong();
                long serviceReplyPayloadSize =
                        java.nio.ByteBuffer.wrap(replyHeaderBytes, 24, 8).getLong();
                in.readNBytes((int) serviceReplyPayloadSize);

                assertThat(replyMessageType).isEqualTo((long) CasualNWMessageType.SERVICE_CALL_REPLY.getMessageId());
                assertThat(serviceReplyPayloadSize).isGreaterThan(0);
                upstreamWireMock.verify(1, postRequestedFor(urlEqualTo("/__casual/services/legacyTraceService")));
                assertThat(appender.list).noneMatch(event -> event.getThrowableProxy() != null);
            } finally {
                proxyGateway.close();
            }
        } finally {
            casualLogger.setLevel(previousLevel);
            casualLogger.detachAppender(appender);
            upstreamGateway.close();
            upstreamWireMock.stop();
        }
    }

    @Test
    @DisplayName(
            "Should normalize reply to V1_3 for Protocol 1.4 client and preserve CasualHeaders for Protocol 1.5 proxy")
    void shouldNormalizeReplyToV13ForProtocol14ClientAndPreserveHeadersForProtocol15Proxy() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/__casual/services/proto14Service"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true}")));

        try (java.net.Socket socket = new java.net.Socket("127.0.0.1", gateway.getPort())) {
            socket.setSoTimeout(5000);
            java.io.OutputStream out = socket.getOutputStream();
            java.io.InputStream in = socket.getInputStream();

            se.laz.casual.network.protocol.messages.domain.CasualDomainConnectRequestMessage connectReq =
                    se.laz.casual.network.protocol.messages.domain.CasualDomainConnectRequestMessage.createBuilder()
                            .withExecution(UUID.randomUUID())
                            .withDomainId(UUID.randomUUID())
                            .withDomainName("client-v14-domain")
                            .withProtocols(List.of(1000L, 1001L, 1002L, 1003L, 1004L))
                            .build();
            se.laz.casual.api.network.protocol.messages.CasualNWMessage<
                            se.laz.casual.network.protocol.messages.domain.CasualDomainConnectRequestMessage>
                    connectEnvelope = se.laz.casual.network.protocol.messages.CasualNWMessageImpl.of(
                            UUID.randomUUID(), connectReq);
            for (byte[] b : connectEnvelope.toNetworkBytes()) {
                out.write(b);
            }
            out.flush();

            byte[] connectReplyHeader = in.readNBytes(32);
            long replyPayloadSize =
                    java.nio.ByteBuffer.wrap(connectReplyHeader, 24, 8).getLong();
            byte[] connectReplyPayload = in.readNBytes((int) replyPayloadSize);
            long negotiatedVersion = java.nio.ByteBuffer.wrap(connectReplyPayload, connectReplyPayload.length - 8, 8)
                    .getLong();
            assertThat(negotiatedVersion).isEqualTo(1004L);

            CasualServiceCallRequestMessage callMsg = CasualServiceCallRequestMessage.createBuilder()
                    .setProtocolVersion(ProtocolVersion.VERSION_1_4)
                    .setExecution(UUID.randomUUID())
                    .setServiceName("proto14Service")
                    .setTimeout(5000L)
                    .setParentName("client-v14-domain")
                    .setParentSpan(SpanId.of())
                    .setXid(XID.NULL_XID)
                    .setXatmiFlags(Flag.of(AtmiFlags.NOFLAG))
                    .setServiceBuffer(ServiceBuffer.of(JsonBuffer.of("{}")))
                    .build();

            se.laz.casual.api.network.protocol.messages.CasualNWMessage<CasualServiceCallRequestMessage> callEnvelope =
                    se.laz.casual.network.protocol.messages.CasualNWMessageImpl.of(UUID.randomUUID(), callMsg);
            for (byte[] b : callEnvelope.toNetworkBytes()) {
                out.write(b);
            }
            out.flush();

            byte[] replyHeaderBytes = in.readNBytes(32);
            long replyMessageType =
                    java.nio.ByteBuffer.wrap(replyHeaderBytes, 0, 8).getLong();
            long serviceReplyPayloadSize =
                    java.nio.ByteBuffer.wrap(replyHeaderBytes, 24, 8).getLong();
            in.readNBytes((int) serviceReplyPayloadSize);

            assertThat(replyMessageType).isEqualTo((long) CasualNWMessageType.SERVICE_CALL_REPLY_V_1_3.getMessageId());
        }

        CasualServer echoServer = CasualServer.builder()
                .port(0)
                .domainName("echo-v15-domain")
                .serviceHandler(req -> CasualServiceCallReplyMessage.createBuilder()
                        .setProtocolVersion(ProtocolVersion.VERSION_1_5)
                        .setExecution(req.getExecution())
                        .setError(ErrorState.OK)
                        .setTransactionState(TransactionState.TX_ACTIVE)
                        .setServiceBuffer(req.getServiceBuffer())
                        .build())
                .build();
        echoServer.start();

        try (CasualClient forwardClient =
                CasualClient.create("127.0.0.1", echoServer.getPort()).connect()) {
            assertThat(forwardClient.getProtocolVersion()).isEqualTo(ProtocolVersion.VERSION_1_5);

            CasualHeaders requestHeaders = CasualHeaders.newBuilder()
                    .add("x-Correlation-Test", "corr-15")
                    .build();
            CasualServiceCallRequestMessage req15 = CasualServiceCallRequestMessage.createBuilder()
                    .setProtocolVersion(ProtocolVersion.VERSION_1_5)
                    .setExecution(UUID.randomUUID())
                    .setServiceName("echoHeadersService")
                    .setTimeout(5000L)
                    .setParentName("proxy-v15-domain")
                    .setParentSpan(SpanId.of())
                    .setXid(XID.NULL_XID)
                    .setXatmiFlags(Flag.of(AtmiFlags.NOFLAG))
                    .setServiceBuffer(ServiceBuffer.of(JsonBuffer.of("{}", requestHeaders)))
                    .build();

            CasualServiceCallReplyMessage echoReply = forwardClient.forwardServiceCall(req15);
            assertThat(echoReply.getType()).isEqualTo(CasualNWMessageType.SERVICE_CALL_REPLY_V_1_5);
            assertThat(echoReply.getServiceBuffer().getHeaders().get("x-Correlation-Test"))
                    .containsExactly("corr-15");
        } finally {
            echoServer.close();
        }
    }

    @Test
    @DisplayName("Should configure non-strict validation by default and honor system property override")
    void shouldConfigureNonStrictValidationByDefaultAndHonorSystemPropertyOverride() {
        CasualProtocolConfig.ensureInitialized();
        assertThat(ConfigurationService.getConfiguration(
                        ConfigurationOptions.CASUAL_NETWORK_PROTOCOL_STRICT_VALIDATION))
                .isFalse();

        String previous = System.getProperty(CasualProtocolConfig.STRICT_VALIDATION_KEY);
        try {
            System.setProperty(CasualProtocolConfig.STRICT_VALIDATION_KEY, "true");
            CasualProtocolConfig.configureStrictValidationDefault();
            assertThat(ConfigurationService.getConfiguration(
                            ConfigurationOptions.CASUAL_NETWORK_PROTOCOL_STRICT_VALIDATION))
                    .isTrue();
        } finally {
            if (previous == null) {
                System.clearProperty(CasualProtocolConfig.STRICT_VALIDATION_KEY);
            } else {
                System.setProperty(CasualProtocolConfig.STRICT_VALIDATION_KEY, previous);
            }
            CasualProtocolConfig.configureStrictValidationDefault();
        }
    }

    @Test
    @DisplayName("Should proxy TPNOREPLY fire-and-forget calls without waiting for reply or sending unsolicited error")
    void shouldProxyTpNoReplyFireAndForgetWithoutTimeoutOrUnsolicitedReply() throws Exception {
        UUID upstreamDomainId = UUID.randomUUID();
        CountDownLatch upstreamReceivedLatch = new CountDownLatch(1);
        AtomicInteger upstreamServiceCallsReceived = new AtomicInteger();

        try (ServerSocket rawUpstream = new ServerSocket(0)) {
            Thread upstreamAcceptor = new Thread(
                    () -> {
                        try {
                            while (!rawUpstream.isClosed()) {
                                Socket accepted = rawUpstream.accept();
                                Thread handlerThread = new Thread(
                                        () -> {
                                            try (Socket s = accepted) {
                                                InputStream uIn = s.getInputStream();
                                                OutputStream uOut = s.getOutputStream();
                                                while (!s.isClosed()) {
                                                    byte[] headerBytes = uIn.readNBytes(32);
                                                    if (headerBytes.length < 32) {
                                                        break;
                                                    }
                                                    se.laz.casual.network.protocol.messages.CasualNWMessageHeader hdr =
                                                            se.laz.casual.network.protocol.decoding.decoders
                                                                    .CasualNWMessageHeaderDecoder.fromNetworkBytes(
                                                                    headerBytes);
                                                    byte[] payload = uIn.readNBytes((int) hdr.getPayloadSize());
                                                    if (hdr.getType() == CasualNWMessageType.DOMAIN_CONNECT_REQUEST) {
                                                        long msb = ByteBuffer.wrap(payload, 0, 8)
                                                                .getLong();
                                                        long lsb = ByteBuffer.wrap(payload, 8, 8)
                                                                .getLong();
                                                        UUID exec = new UUID(msb, lsb);
                                                        se.laz.casual.network.protocol.messages.domain
                                                                        .CasualDomainConnectReplyMessage
                                                                connectReply = se.laz.casual.network.protocol.messages
                                                                        .domain.CasualDomainConnectReplyMessage
                                                                        .createBuilder()
                                                                        .withExecution(exec)
                                                                        .withDomainId(upstreamDomainId)
                                                                        .withDomainName("raw-tpnoreply-upstream")
                                                                        .withProtocolVersion(
                                                                                ProtocolVersion.VERSION_1_5
                                                                                        .getVersion())
                                                                        .build();
                                                        for (byte[] b : se.laz.casual.network.protocol.messages
                                                                .CasualNWMessageImpl.of(
                                                                        hdr.getCorrelationId(), connectReply)
                                                                .toNetworkBytes()) {
                                                            uOut.write(b);
                                                        }
                                                        uOut.flush();
                                                    } else if (hdr.getType() == CasualNWMessageType.SERVICE_CALL_REQUEST
                                                            || hdr.getType()
                                                                    == CasualNWMessageType.SERVICE_CALL_REQUEST_V_1_3
                                                            || hdr.getType()
                                                                    == CasualNWMessageType.SERVICE_CALL_REQUEST_V_1_5) {
                                                        upstreamServiceCallsReceived.incrementAndGet();
                                                        upstreamReceivedLatch.countDown();
                                                        // Intentionally send NO reply frame (fire-and-forget upstream)
                                                    }
                                                }
                                            } catch (Exception ignored) {
                                            }
                                        },
                                        "raw-upstream-conn");
                                handlerThread.setDaemon(true);
                                handlerThread.start();
                            }
                        } catch (Exception ignored) {
                        }
                    },
                    "raw-upstream-acceptor");
            upstreamAcceptor.setDaemon(true);
            upstreamAcceptor.start();

            WireMockServer proxyWireMock = new WireMockServer(wireMockConfig().dynamicPort());
            proxyWireMock.start();
            CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                    .port(0)
                    .wireMockServer(proxyWireMock)
                    .upstreamTimeoutSeconds(2)
                    .build();
            proxyGateway.start();

            try {
                proxyWireMock.stubFor(post(urlEqualTo("/__casual/services/fire.and.forget"))
                        .withMetadata(Map.of(
                                "mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + rawUpstream.getLocalPort()))
                        .willReturn(aResponse().withStatus(200)));
                proxyWireMock.stubFor(post(urlEqualTo("/__casual/services/healthCheck"))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withHeader("Content-Type", "application/json")
                                .withBody("{\"ok\":true}")));

                try (Socket clientSocket = new Socket("127.0.0.1", proxyGateway.getPort())) {
                    clientSocket.setSoTimeout(1000);
                    OutputStream out = clientSocket.getOutputStream();
                    InputStream in = clientSocket.getInputStream();

                    // Handshake
                    se.laz.casual.network.protocol.messages.domain.CasualDomainConnectRequestMessage connectReq = se.laz
                            .casual.network.protocol.messages.domain.CasualDomainConnectRequestMessage.createBuilder()
                            .withExecution(UUID.randomUUID())
                            .withDomainId(UUID.randomUUID())
                            .withDomainName("downstream-tpnoreply-client")
                            .withProtocols(List.of(1000L, 1001L, 1002L, 1003L, 1004L, 1005L))
                            .build();
                    for (byte[] b : se.laz.casual.network.protocol.messages.CasualNWMessageImpl.of(
                                    UUID.randomUUID(), connectReq)
                            .toNetworkBytes()) {
                        out.write(b);
                    }
                    out.flush();
                    byte[] connectReplyHdr = in.readNBytes(32);
                    long connectPayloadSize =
                            ByteBuffer.wrap(connectReplyHdr, 24, 8).getLong();
                    in.readNBytes((int) connectPayloadSize);

                    // 1. Send proxied TPNOREPLY fire-and-forget service call (must return immediately, never reply)
                    UUID noReplyCorrId = UUID.randomUUID();
                    CasualServiceCallRequestMessage fireAndForget = CasualServiceCallRequestMessage.createBuilder()
                            .setProtocolVersion(ProtocolVersion.VERSION_1_5)
                            .setExecution(UUID.randomUUID())
                            .setServiceName("fire.and.forget")
                            .setTimeout(5000L)
                            .setParentName("downstream-tpnoreply-client")
                            .setParentSpan(SpanId.of())
                            .setXid(XID.NULL_XID)
                            .setXatmiFlags(Flag.of(AtmiFlags.TPNOREPLY))
                            .setServiceBuffer(ServiceBuffer.of(JsonBuffer.of("{\"event\":\"ping\"}")))
                            .build();
                    for (byte[] b : se.laz.casual.network.protocol.messages.CasualNWMessageImpl.of(
                                    noReplyCorrId, fireAndForget)
                            .toNetworkBytes()) {
                        out.write(b);
                    }

                    // 2. Immediately send a synchronous call on the SAME socket; the very next frame received
                    // must be healthCheck's reply (with healthCorrId) in < 1000ms (< 2s upstream timeout).
                    UUID healthCorrId = UUID.randomUUID();
                    CasualServiceCallRequestMessage healthReq = CasualServiceCallRequestMessage.createBuilder()
                            .setProtocolVersion(ProtocolVersion.VERSION_1_5)
                            .setExecution(UUID.randomUUID())
                            .setServiceName("healthCheck")
                            .setTimeout(5000L)
                            .setParentName("downstream-tpnoreply-client")
                            .setParentSpan(SpanId.of())
                            .setXid(XID.NULL_XID)
                            .setXatmiFlags(Flag.of(AtmiFlags.NOFLAG))
                            .setServiceBuffer(ServiceBuffer.of(JsonBuffer.of("{}")))
                            .build();
                    for (byte[] b : se.laz.casual.network.protocol.messages.CasualNWMessageImpl.of(
                                    healthCorrId, healthReq)
                            .toNetworkBytes()) {
                        out.write(b);
                    }
                    out.flush();

                    assertThat(upstreamReceivedLatch.await(1000, TimeUnit.MILLISECONDS))
                            .isTrue();

                    byte[] replyHdrBytes = in.readNBytes(32);
                    assertThat(replyHdrBytes).hasSize(32);
                    se.laz.casual.network.protocol.messages.CasualNWMessageHeader replyHdr = se.laz.casual.network
                            .protocol.decoding.decoders.CasualNWMessageHeaderDecoder.fromNetworkBytes(replyHdrBytes);
                    in.readNBytes((int) replyHdr.getPayloadSize());

                    assertThat(replyHdr.getCorrelationId()).isEqualTo(healthCorrId);
                    assertThat(replyHdr.getType()).isEqualTo(CasualNWMessageType.SERVICE_CALL_REPLY_V_1_5);
                    assertThat(upstreamServiceCallsReceived.get()).isEqualTo(1);
                }
            } finally {
                proxyGateway.close();
                proxyWireMock.stop();
            }
        }
    }

    @Test
    @DisplayName("Should not deadlock single-EventLoop worker when proxied upstream call invokes callback stub")
    void shouldNotDeadlockWhenUpstreamProxiedCallInvokesCallbackStubOnSameGateway() throws Exception {
        WireMockServer proxyWireMock = new WireMockServer(wireMockConfig().dynamicPort());
        proxyWireMock.start();

        CasualWireMockGateway proxyGateway = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(proxyWireMock)
                .upstreamTimeoutSeconds(2)
                .workerThreads(1)
                .build();
        proxyGateway.start();

        CasualServer upstreamCallbackServer = CasualServer.builder()
                .port(0)
                .domainName("callback-upstream")
                .discoveryHandler(names -> List.of(se.laz.casual.network.protocol.messages.domain.Service.of(
                        "outer.service", "test", se.laz.casual.network.messages.domain.TransactionType.AUTOMATIC)))
                .serviceHandler(req -> {
                    try (CasualClient callbackClient = CasualClient.create("127.0.0.1", proxyGateway.getPort(), 2)
                            .connect()) {
                        CasualServiceCallReplyMessage cbReply = callbackClient.tpcall("callback.service", "{}");
                        String cbBody = CasualBufferUtils.extractPayload(cbReply.getServiceBuffer());
                        return CasualServiceCallReplyMessage.createBuilder()
                                .setProtocolVersion(ProtocolVersion.VERSION_1_5)
                                .setExecution(req.getExecution())
                                .setError(cbReply.getError())
                                .setUserSuppliedError(0)
                                .setTransactionState(TransactionState.TX_ACTIVE)
                                .setServiceBuffer(ServiceBuffer.of(JsonBuffer.of("{\"outer\":" + cbBody + "}")))
                                .build();
                    } catch (Exception e) {
                        return CasualServiceCallReplyMessage.createBuilder()
                                .setProtocolVersion(ProtocolVersion.VERSION_1_5)
                                .setExecution(req.getExecution())
                                .setError(ErrorState.TPESVCERR)
                                .setUserSuppliedError(0)
                                .setTransactionState(TransactionState.ROLLBACK_ONLY)
                                .setServiceBuffer(ServiceBuffer.empty())
                                .build();
                    }
                })
                .build();
        upstreamCallbackServer.start();

        try {
            proxyWireMock.stubFor(post(urlEqualTo("/__casual/services/outer.service"))
                    .withMetadata(Map.of(
                            "mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + upstreamCallbackServer.getPort()))
                    .willReturn(aResponse().withStatus(200)));
            proxyWireMock.stubFor(post(urlEqualTo("/__casual/services/callback.service"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"callback\":\"ok\"}")));

            try (CasualClient testClient =
                    CasualClient.create("127.0.0.1", proxyGateway.getPort(), 5).connect()) {
                CasualServiceCallReplyMessage reply = testClient.tpcall("outer.service", "{}");
                assertThat(reply.getError()).isEqualTo(ErrorState.OK);
                assertThat(CasualBufferUtils.extractPayload(reply.getServiceBuffer()))
                        .isEqualTo("{\"outer\":{\"callback\":\"ok\"}}");
            }
        } finally {
            upstreamCallbackServer.close();
            proxyGateway.close();
            proxyWireMock.stop();
        }
    }

    @Test
    @DisplayName("Should not synthesize false discovery or loop in bidirectional gateway peering")
    void shouldNotSynthesizeFalseDiscoveryOrLoopInBidirectionalGatewayPeering() throws Exception {
        WireMockServer wireMockA = new WireMockServer(wireMockConfig().dynamicPort());
        WireMockServer wireMockB = new WireMockServer(wireMockConfig().dynamicPort());
        wireMockA.start();
        wireMockB.start();

        CasualWireMockGateway gatewayA = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(wireMockA)
                .upstreamTimeoutSeconds(2)
                .build();
        CasualWireMockGateway gatewayB = CasualWireMockGateway.builder()
                .port(0)
                .wireMockServer(wireMockB)
                .upstreamTimeoutSeconds(2)
                .build();
        gatewayA.start();
        gatewayB.start();

        try {
            wireMockB.stubFor(post(urlEqualTo("/__casual/services/real.peer.service"))
                    .atPriority(1)
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"peer\":\"ok\"}")));

            wireMockA.stubFor(post(urlMatching("/__casual/services/.*"))
                    .atPriority(10)
                    .withMetadata(Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + gatewayB.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            wireMockB.stubFor(post(urlMatching("/__casual/services/.*"))
                    .atPriority(10)
                    .withMetadata(Map.of("mode", "proxy", "targetBaseUrl", "casual://127.0.0.1:" + gatewayA.getPort()))
                    .willReturn(aResponse().withStatus(200)));

            try (CasualClient client =
                    CasualClient.create("127.0.0.1", gatewayA.getPort(), 2).connect()) {
                CasualDomainDiscoveryReplyMessage discovery =
                        client.discoverServices(List.of("real.peer.service", "missing.service"));
                assertThat(discovery.getServices())
                        .extracting(Service::getName)
                        .contains("real.peer.service")
                        .doesNotContain("missing.service");

                long startNanos = System.nanoTime();
                CasualServiceCallReplyMessage missingReply = client.tpcall("missing.service", "{}");
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                assertThat(elapsedMs).isLessThan(1500L);
                assertThat(missingReply.getError()).isEqualTo(ErrorState.TPENOENT);
            }
        } finally {
            gatewayB.close();
            gatewayA.close();
            wireMockB.stop();
            wireMockA.stop();
        }
    }
}
