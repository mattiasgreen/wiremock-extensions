package com.github.mattiasgreen.wiremock.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Headless in-browser unit tests verifying ES modules without external JS bundlers or Node.js.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class UiModuleUnitTest {

    private WireMockServer wireMockServer;
    private Page page;

    @BeforeAll
    void startAll() {
        wireMockServer =
                new WireMockServer(WireMockConfiguration.options().dynamicPort().extensions(new UiAdminApiEndpoint()));
        wireMockServer.start();

        Browser browser = AbstractUiPlaywrightTest.getSharedBrowser();
        page = browser.newPage();

        // Navigate to the UI to ensure same-origin context for ES module imports
        page.navigate(wireMockServer.baseUrl() + "/__admin/ui/");
    }

    @AfterAll
    void stopAll() {
        if (page != null) {
            page.close();
        }
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    private Object evalJs(String expression) {
        return page.evaluate(expression);
    }

    @Test
    @DisplayName("evaluateQuery: Basic free-text and boolean AND / OR expression parsing")
    void testEvaluateQueryExpressions() {
        String script =
                """
            async () => {
                const { evaluateQuery } = await import('./modules/filter.js');

                const corpus = "customer 1234 product abc5 region emea";
                const matcher = token => corpus.includes(token.toLowerCase());

                return {
                    emptyMatches: evaluateQuery("", matcher),
                    singleTermMatches: evaluateQuery("1234", matcher),
                    singleTermMisses: evaluateQuery("9999", matcher),
                    andExplicitMatches: evaluateQuery("1234 AND abc5", matcher),
                    andExplicitMisses: evaluateQuery("1234 AND 9999", matcher),
                    andImplicitWhitespaceMatches: evaluateQuery("1234 abc5", matcher),
                    andImplicitWhitespaceMisses: evaluateQuery("1234 9999", matcher),
                    orMatchesFirst: evaluateQuery("1234 OR 9999", matcher),
                    orMatchesSecond: evaluateQuery("9999 OR abc5", matcher),
                    orMissesBoth: evaluateQuery("8888 OR 9999", matcher),
                    andBindsTighterThanOr: evaluateQuery("1234 AND 9999 OR emea", matcher),
                    caseInsensitiveOperators: evaluateQuery("1234 and abc5 or 9999", matcher)
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Boolean> result = (java.util.Map<String, Boolean>) evalJs(script);

        assertThat(result.get("emptyMatches")).isTrue();
        assertThat(result.get("singleTermMatches")).isTrue();
        assertThat(result.get("singleTermMisses")).isFalse();
        assertThat(result.get("andExplicitMatches")).isTrue();
        assertThat(result.get("andExplicitMisses")).isFalse();
        assertThat(result.get("andImplicitWhitespaceMatches")).isTrue();
        assertThat(result.get("andImplicitWhitespaceMisses")).isFalse();
        assertThat(result.get("orMatchesFirst")).isTrue();
        assertThat(result.get("orMatchesSecond")).isTrue();
        assertThat(result.get("orMissesBoth")).isFalse();
        assertThat(result.get("andBindsTighterThanOr")).isTrue();
        assertThat(result.get("caseInsensitiveOperators")).isTrue();
    }

    @Test
    @DisplayName("matchesStub: Basic URL, method, name, and tag baseline matching")
    void testMatchesStubBaseline() {
        String script =
                """
            async () => {
                const { matchesStub } = await import('./modules/filter.js');

                const stub = {
                    id: "stub-1",
                    name: "Get User Orders",
                    request: {
                        method: "GET",
                        url: "/api/v1/orders"
                    },
                    metadata: {
                        tags: ["ecommerce", "checkout"]
                    }
                };

                return {
                    matchByMethod: matchesStub(stub, "get", "/api/v1/orders"),
                    matchByUrl: matchesStub(stub, "orders", "/api/v1/orders"),
                    matchByName: matchesStub(stub, "User Orders", "/api/v1/orders"),
                    matchByTag: matchesStub(stub, "checkout", "/api/v1/orders"),
                    noMatch: matchesStub(stub, "payments", "/api/v1/orders"),
                    matchAnd: matchesStub(stub, "orders AND checkout", "/api/v1/orders"),
                    matchOr: matchesStub(stub, "payments OR orders", "/api/v1/orders")
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Boolean> result = (java.util.Map<String, Boolean>) evalJs(script);

        assertThat(result.get("matchByMethod")).isTrue();
        assertThat(result.get("matchByUrl")).isTrue();
        assertThat(result.get("matchByName")).isTrue();
        assertThat(result.get("matchByTag")).isTrue();
        assertThat(result.get("noMatch")).isFalse();
        assertThat(result.get("matchAnd")).isTrue();
        assertThat(result.get("matchOr")).isTrue();
    }

    @Test
    @DisplayName("matchesJournalEntry: Method, URL, status, and stateful entity baseline matching")
    void testMatchesJournalEntryBaseline() {
        String script =
                """
            async () => {
                const { matchesJournalEntry } = await import('./modules/filter.js');

                const req = {
                    id: "req-1",
                    request: {
                        method: "POST",
                        url: "/api/v1/checkout"
                    },
                    response: {
                        status: 201
                    },
                    stubMapping: {
                        name: "Create Order"
                    }
                };

                return {
                    matchByMethod: matchesJournalEntry(req, "POST"),
                    matchByUrl: matchesJournalEntry(req, "checkout"),
                    matchByStatus: matchesJournalEntry(req, "201"),
                    matchByStubName: matchesJournalEntry(req, "Order"),
                    noMatch: matchesJournalEntry(req, "customers"),
                    matchAnd: matchesJournalEntry(req, "POST AND 201"),
                    matchOr: matchesJournalEntry(req, "500 OR 201")
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Boolean> result = (java.util.Map<String, Boolean>) evalJs(script);

        assertThat(result.get("matchByMethod")).isTrue();
        assertThat(result.get("matchByUrl")).isTrue();
        assertThat(result.get("matchByStatus")).isTrue();
        assertThat(result.get("matchByStubName")).isTrue();
        assertThat(result.get("noMatch")).isFalse();
        assertThat(result.get("matchAnd")).isTrue();
        assertThat(result.get("matchOr")).isTrue();
    }

    @Test
    @DisplayName("matchesStub: Deep search across headers, bodyPatterns, metadata, and correlation IDs with AND / OR")
    void testMatchesStubDeepInspection() {
        String script =
                """
            async () => {
                const { matchesStub } = await import('./modules/filter.js');

                // 2 stubs on identical URL with different correlation IDs in headers/metadata
                const stub1 = {
                    id: "stub-order-1",
                    name: "Create Order",
                    request: {
                        method: "POST",
                        url: "/api/orders",
                        headers: {
                            "X-Correlation-Id": {
                                "equalTo": "CORR-AAA-1234"
                            }
                        },
                        bodyPatterns: [
                            { "matches": ".*product_type=abc5.*" }
                        ]
                    },
                    metadata: {
                        customerId: "1234",
                        productType: "abc5"
                    }
                };

                const stub2 = {
                    id: "stub-order-2",
                    name: "Create Order",
                    request: {
                        method: "POST",
                        url: "/api/orders",
                        headers: {
                            "X-Correlation-Id": {
                                "equalTo": "CORR-BBB-789"
                            }
                        }
                    },
                    metadata: {
                        customerId: "789"
                    }
                };

                return {
                    // Match header matcher value
                    matchStub1Header: matchesStub(stub1, "CORR-AAA-1234", "/api/orders"),
                    missStub2Header: matchesStub(stub2, "CORR-AAA-1234", "/api/orders"),
                    // Match body pattern matcher
                    matchStub1BodyPattern: matchesStub(stub1, "product_type=abc5", "/api/orders"),
                    // Match metadata
                    matchStub1Customer: matchesStub(stub1, "customerId", "/api/orders"),
                    // AND boolean: "1234 AND abc5" matches stub1 but not stub2
                    stub1AndMatches: matchesStub(stub1, "1234 AND abc5", "/api/orders"),
                    stub2AndMisses: matchesStub(stub2, "1234 AND abc5", "/api/orders"),
                    // OR boolean: "1234 OR 789" matches both
                    stub1OrMatches: matchesStub(stub1, "1234 OR 789", "/api/orders"),
                    stub2OrMatches: matchesStub(stub2, "1234 OR 789", "/api/orders")
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Boolean> result = (java.util.Map<String, Boolean>) evalJs(script);

        assertThat(result.get("matchStub1Header")).isTrue();
        assertThat(result.get("missStub2Header")).isFalse();
        assertThat(result.get("matchStub1BodyPattern")).isTrue();
        assertThat(result.get("matchStub1Customer")).isTrue();
        assertThat(result.get("stub1AndMatches")).isTrue();
        assertThat(result.get("stub2AndMisses")).isFalse();
        assertThat(result.get("stub1OrMatches")).isTrue();
        assertThat(result.get("stub2OrMatches")).isTrue();
    }

    @Test
    @DisplayName("matchesJournalEntry: Deep search across request headers, queryParams, and payload bodies")
    void testMatchesJournalEntryDeepInspection() {
        String script =
                """
            async () => {
                const { matchesJournalEntry } = await import('./modules/filter.js');

                const req1 = {
                    id: "req-1",
                    request: {
                        method: "POST",
                        url: "/api/orders",
                        headers: {
                            "X-Correlation-Id": "J-CORR-1234",
                            "Content-Type": "application/json"
                        },
                        body: '{"customerId": "1234", "product": "abc5"}'
                    },
                    response: {
                        status: 201,
                        body: '{"orderId": "ORD-999"}'
                    }
                };

                const req2 = {
                    id: "req-2",
                    request: {
                        method: "POST",
                        url: "/api/orders",
                        headers: {
                            "X-Correlation-Id": "J-CORR-789"
                        },
                        body: '{"customerId": "789"}'
                    },
                    response: {
                        status: 200
                    }
                };

                return {
                    matchHeaderCorrId: matchesJournalEntry(req1, "J-CORR-1234"),
                    missHeaderCorrId: matchesJournalEntry(req2, "J-CORR-1234"),
                    matchResponseBody: matchesJournalEntry(req1, "ORD-999"),
                    matchAndCustomerAndProduct: matchesJournalEntry(req1, "1234 AND abc5"),
                    missAndCustomerAndProduct: matchesJournalEntry(req2, "1234 AND abc5"),
                    matchOrCustomer: matchesJournalEntry(req1, "1234 OR 789"),
                    matchOrCustomer2: matchesJournalEntry(req2, "1234 OR 789")
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Boolean> result = (java.util.Map<String, Boolean>) evalJs(script);

        assertThat(result.get("matchHeaderCorrId")).isTrue();
        assertThat(result.get("missHeaderCorrId")).isFalse();
        assertThat(result.get("matchResponseBody")).isTrue();
        assertThat(result.get("matchAndCustomerAndProduct")).isTrue();
        assertThat(result.get("missAndCustomerAndProduct")).isFalse();
        assertThat(result.get("matchOrCustomer")).isTrue();
        assertThat(result.get("matchOrCustomer2")).isTrue();
    }

    @Test
    @DisplayName("generateCreateStubCurl: Multi-line JSON stub creation cURL command with EOF heredoc")
    void testGenerateCreateStubCurl() {
        String script =
                """
            async () => {
                const { generateCreateStubCurl } = await import('./modules/stubs.js');

                const standardStub = {
                    id: "stub-1234",
                    name: "Get User",
                    request: {
                        method: "GET",
                        url: "/api/users/1234"
                    },
                    response: {
                        status: 200,
                        body: '{"id": 1234, "name": "Alice"}'
                    }
                };

                const dynamicStub = {
                    id: "dynamic-cases-0",
                    isDynamic: true,
                    rule: {
                        route: { method: "POST", pathPattern: "/api/cases" },
                        mutations: [{ type: "init_entity", idParam: "caseId" }],
                        response: { status: 201 }
                    }
                };

                return {
                    standardCurl: generateCreateStubCurl(standardStub),
                    dynamicCurl: generateCreateStubCurl(dynamicStub)
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, String> result = (java.util.Map<String, String>) evalJs(script);

        String standardCurl = result.get("standardCurl");
        assertThat(standardCurl).contains("curl -X POST ");
        assertThat(standardCurl).contains("/__admin/mappings");
        assertThat(standardCurl).contains("-H \"Content-Type: application/json\"");
        assertThat(standardCurl).contains("--data-binary @- << 'EOF'");
        assertThat(standardCurl).contains("\"name\": \"Get User\"");
        assertThat(standardCurl).contains("\"url\": \"/api/users/1234\"");
        assertThat(standardCurl).endsWith("\nEOF");

        String dynamicCurl = result.get("dynamicCurl");
        assertThat(dynamicCurl).contains("curl -X POST ");
        assertThat(dynamicCurl).contains("/__admin/stateful/rules");
        assertThat(dynamicCurl).contains("--data-binary @- << 'EOF'");
        assertThat(dynamicCurl).contains("\"pathPattern\": \"/api/cases\"");
        assertThat(dynamicCurl).endsWith("\nEOF");
    }

    @Test
    @DisplayName("base64: Decoding Base64 strings to UTF-8 and JSON detection")
    void testBase64DecodingUtilities() {
        String script =
                """
            async () => {
                const { decodeBase64ToUtf8, tryDecodeBase64Json, deepDecodeBase64InObject, decodeBase64Payload } = await import('./modules/base64.js');

                // 1. Direct Base64 UTF-8 string
                const simpleJsonB64 = btoa('{"caseId":"12356"}');
                const decodedUtf8 = decodeBase64ToUtf8(simpleJsonB64);
                const decodedJson = tryDecodeBase64Json(simpleJsonB64);

                // 2. Non-JSON string
                const nonJsonB64 = btoa("Hello World!");
                const nonJsonDecoded = tryDecodeBase64Json(nonJsonB64);

                // 3. Invalid Base64
                const invalidB64 = decodeBase64ToUtf8("not_base64_???");

                // 4. Nested object matching enterprise Casual/Tuxedo FML payload
                const enterpriseFml = {
                    CRM_HEADER_SOURCE_MACHINE: "GATEWAY_SRV_01",
                    CRM_HEADER_SOURCE_USERNAME: "crm_agent_12",
                    CRM_HEADER_SOURCE_FLAG: 1,
                    CRM_ARCHIVE_PAYLOAD: "eyJjYXNlSWQiOiIxMjM1NiJ9",
                    CRM_ARCHIVE_TYPE: 5
                };
                const deepResult = deepDecodeBase64InObject(enterpriseFml);

                // 5. decodeBase64Payload with JSON string
                const payloadResult = decodeBase64Payload(JSON.stringify(enterpriseFml));

                // 6. decodeBase64Payload with direct Base64 string
                const directPayloadResult = decodeBase64Payload(simpleJsonB64);

                // 7. Plain JSON without Base64
                const plainPayloadResult = decodeBase64Payload('{"foo": "bar", "num": 42}');

                return {
                    decodedUtf8,
                    decodedJsonCaseId: decodedJson ? decodedJson.caseId : null,
                    nonJsonDecodedIsNull: nonJsonDecoded === null,
                    invalidB64IsNull: invalidB64 === null,
                    deepModified: deepResult.modified,
                    deepArchiveCaseId: deepResult.decoded.CRM_ARCHIVE_PAYLOAD ? deepResult.decoded.CRM_ARCHIVE_PAYLOAD.caseId : null,
                    deepMachineUnchanged: deepResult.decoded.CRM_HEADER_SOURCE_MACHINE,
                    payloadHasBase64: payloadResult.hasBase64,
                    payloadDecodedCaseId: payloadResult.decoded.CRM_ARCHIVE_PAYLOAD ? payloadResult.decoded.CRM_ARCHIVE_PAYLOAD.caseId : null,
                    directPayloadHasBase64: directPayloadResult.hasBase64,
                    directPayloadCaseId: directPayloadResult.decoded ? directPayloadResult.decoded.caseId : null,
                    plainHasBase64: plainPayloadResult.hasBase64
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("decodedUtf8")).isEqualTo("{\"caseId\":\"12356\"}");
        assertThat(result.get("decodedJsonCaseId")).isEqualTo("12356");
        assertThat(result.get("nonJsonDecodedIsNull")).isEqualTo(true);
        assertThat(result.get("invalidB64IsNull")).isEqualTo(true);
        assertThat(result.get("deepModified")).isEqualTo(true);
        assertThat(result.get("deepArchiveCaseId")).isEqualTo("12356");
        assertThat(result.get("deepMachineUnchanged")).isEqualTo("GATEWAY_SRV_01");
        assertThat(result.get("payloadHasBase64")).isEqualTo(true);
        assertThat(result.get("payloadDecodedCaseId")).isEqualTo("12356");
        assertThat(result.get("directPayloadHasBase64")).isEqualTo(true);
        assertThat(result.get("directPayloadCaseId")).isEqualTo("12356");
        assertThat(result.get("plainHasBase64")).isEqualTo(false);
    }

    @Test
    @DisplayName("journal: Base64 JSON toggle and dual raw/decoded view in journal row")
    void testJournalBase64DecodedView() {
        String script =
                """
            async () => {
                const { state } = await import('./modules/state.js');
                const { renderJournal } = await import('./modules/journal.js');

                // Set up a mock journal entry with Casual FML payload containing Base64 JSON
                const fmlPayload = JSON.stringify({
                    CRM_HEADER_SOURCE_MACHINE: "GATEWAY_SRV_01",
                    CRM_HEADER_SOURCE_USERNAME: "crm_agent_12",
                    CRM_HEADER_SOURCE_FLAG: 1,
                    CRM_ARCHIVE_PAYLOAD: "eyJjYXNlSWQiOiIxMjM1NiJ9",
                    CRM_ARCHIVE_TYPE: 5
                }, null, 2);

                state.currentRequests = [{
                    id: "req-casual-b64-1",
                    wasMatched: true,
                    request: {
                        method: "POST",
                        url: "/__casual/services/crm_archive_service",
                        headers: {
                            "Content-Type": "application/json",
                            "x-casual-service": "crm_archive_service"
                        },
                        body: fmlPayload,
                        loggedDate: Date.now()
                    },
                    response: {
                        status: 200,
                        headers: {
                            "Content-Type": "application/json",
                            "x-casual-service": "crm_archive_service"
                        },
                        body: fmlPayload
                    }
                }];
                state.expandedRequestId = "req-casual-b64-1";

                renderJournal();

                const detailRow = document.querySelector('[data-request-id="req-casual-b64-1"].journal-detail-row');
                if (!detailRow) return { error: "Detail row not rendered" };

                const b64Badges = detailRow.querySelectorAll('[data-testid="b64-badge"]');
                const rawButtons = detailRow.querySelectorAll('[data-testid="btn-b64-raw"]');
                const decodedButtons = detailRow.querySelectorAll('[data-testid="btn-b64-decoded"]');
                const decodedBlocks = detailRow.querySelectorAll('[data-testid="journal-body-decoded"]');
                const rawBlocks = detailRow.querySelectorAll('[data-testid="journal-body-raw"]');

                const decodedText = decodedBlocks.length > 0 ? decodedBlocks[0].textContent : "";
                const rawText = rawBlocks.length > 0 ? rawBlocks[0].textContent : "";

                // Click 'Raw' button to test toggle
                if (rawButtons.length > 0) {
                    rawButtons[0].click();
                }
                const rawHiddenAfterClick = rawBlocks.length > 0 ? rawBlocks[0].classList.contains('hidden') : null;
                const decodedHiddenAfterClick = decodedBlocks.length > 0 ? decodedBlocks[0].classList.contains('hidden') : null;

                // Click 'Decoded' button to test toggle back
                if (decodedButtons.length > 0) {
                    decodedButtons[0].click();
                }
                const rawHiddenAfterSecondClick = rawBlocks.length > 0 ? rawBlocks[0].classList.contains('hidden') : null;
                const decodedHiddenAfterSecondClick = decodedBlocks.length > 0 ? decodedBlocks[0].classList.contains('hidden') : null;

                return {
                    badgeCount: b64Badges.length,
                    rawButtonCount: rawButtons.length,
                    decodedButtonCount: decodedButtons.length,
                    decodedTextContainsCaseId: decodedText.includes("12356"),
                    rawTextContainsBase64: rawText.includes("eyJjYXNlSWQiOiIxMjM1NiJ9"),
                    rawHiddenAfterClick,
                    decodedHiddenAfterClick,
                    rawHiddenAfterSecondClick,
                    decodedHiddenAfterSecondClick
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("error")).isNull();
        // Both request and response cards have Base64 JSON detected -> 2 badges, 2 button pairs
        assertThat(result.get("badgeCount")).isEqualTo(2);
        assertThat(result.get("rawButtonCount")).isEqualTo(2);
        assertThat(result.get("decodedButtonCount")).isEqualTo(2);
        assertThat(result.get("decodedTextContainsCaseId")).isEqualTo(true);
        assertThat(result.get("rawTextContainsBase64")).isEqualTo(true);

        // After clicking Raw: raw is visible (not hidden), decoded is hidden
        assertThat(result.get("rawHiddenAfterClick")).isEqualTo(false);
        assertThat(result.get("decodedHiddenAfterClick")).isEqualTo(true);

        // After clicking Decoded: raw is hidden, decoded is visible
        assertThat(result.get("rawHiddenAfterSecondClick")).isEqualTo(true);
        assertThat(result.get("decodedHiddenAfterSecondClick")).isEqualTo(false);
    }

    @Test
    @DisplayName("buildSnapshotStubMapping: Builds distinct non-colliding stubs matching exact request body")
    void testBuildSnapshotStubMappingExactBodyMatching() {
        String script =
                """
            async () => {
                const { buildSnapshotStubMapping } = await import('./modules/journal.js');

                // Customer call in CRM Risk Control
                const customerReq = {
                    id: "req-cust",
                    request: {
                        method: "POST",
                        url: "/__casual/services/crmRiskControlService",
                        body: '{"customerId": "CUST-001", "role": "customer"}'
                    },
                    response: {
                        status: 200,
                        body: '{"customerId": "CUST-001", "riskStatus": "LOW"}'
                    }
                };

                // Spouse call in CRM Risk Control
                const spouseReq = {
                    id: "req-spouse",
                    request: {
                        method: "POST",
                        url: "/__casual/services/crmRiskControlService",
                        body: '{"customerId": "CUST-002", "role": "spouse"}'
                    },
                    response: {
                        status: 200,
                        body: '{"customerId": "CUST-002", "riskStatus": "MEDIUM"}'
                    }
                };

                // Plain text call
                const textReq = {
                    id: "req-text",
                    request: {
                        method: "POST",
                        url: "/api/plain",
                        body: "Hello Plain Text"
                    },
                    response: { status: 200, body: "OK" }
                };

                // Binary Base64 call
                const binReq = {
                    id: "req-bin",
                    request: {
                        method: "POST",
                        url: "/api/binary",
                        bodyAsBase64: "AQIDBA=="
                    },
                    response: { status: 200, body: "OK" }
                };

                const customerStub = buildSnapshotStubMapping(customerReq);
                const spouseStub = buildSnapshotStubMapping(spouseReq);
                const textStub = buildSnapshotStubMapping(textReq);
                const binStub = buildSnapshotStubMapping(binReq);

                return {
                    custName: customerStub.name,
                    custHasBodyPatterns: !!customerStub.request.bodyPatterns,
                    custEqualJson: customerStub.request.bodyPatterns[0].equalToJson,
                    custIgnoreArrayOrder: customerStub.request.bodyPatterns[0].ignoreArrayOrder,
                    custIgnoreExtraElements: customerStub.request.bodyPatterns[0].ignoreExtraElements,

                    spouseName: spouseStub.name,
                    spouseEqualJson: spouseStub.request.bodyPatterns[0].equalToJson,

                    textEqualTo: textStub.request.bodyPatterns[0].equalTo,
                    binBinaryEqualTo: binStub.request.bodyPatterns[0].binaryEqualTo
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("custName")).isEqualTo("[SNAPSHOT POST 200] /__casual/services/crmRiskControlService");
        assertThat(result.get("custHasBodyPatterns")).isEqualTo(true);
        assertThat(result.get("custEqualJson")).isEqualTo("{\"customerId\": \"CUST-001\", \"role\": \"customer\"}");
        assertThat(result.get("custIgnoreArrayOrder")).isEqualTo(true);
        assertThat(result.get("custIgnoreExtraElements")).isEqualTo(false);

        // Spouse has distinct body pattern preventing stub collision
        assertThat(result.get("spouseName")).isEqualTo("[SNAPSHOT POST 200] /__casual/services/crmRiskControlService");
        assertThat(result.get("spouseEqualJson")).isEqualTo("{\"customerId\": \"CUST-002\", \"role\": \"spouse\"}");
        assertThat(result.get("custEqualJson")).isNotEqualTo(result.get("spouseEqualJson"));

        // Plain text and binary matchers
        assertThat(result.get("textEqualTo")).isEqualTo("Hello Plain Text");
        assertThat(result.get("binBinaryEqualTo")).isEqualTo("AQIDBA==");
    }

    @Test
    @DisplayName("Stub Editor: Toggle between Form Mode and Raw JSON Mode with live roundtrip synchronization")
    void testStubEditorRawJsonModeSwitchingAndValidation() {
        String script =
                """
            async () => {
                const {
                    openStubEditor,
                    closeStubEditor,
                    setEditorMode,
                    getEditorMode,
                    formatRawEditorJson
                } = await import('./modules/lifecycle.js');

                const initialStub = {
                    id: "test-stub-101",
                    name: "Get Risk Profile",
                    priority: 3,
                    request: {
                        method: "POST",
                        url: "/api/risk-profile",
                        bodyPatterns: [{ equalToJson: '{"tier": "gold"}', ignoreArrayOrder: true, ignoreExtraElements: true }]
                    },
                    response: {
                        status: 200,
                        body: '{"approved": true}'
                    },
                    metadata: {
                        project: "Risk Control",
                        tags: ["credit", "crm"]
                    }
                };

                // 1. Open editor in Form Mode
                openStubEditor(initialStub, false);
                const initialMode = getEditorMode();

                const formPanel = document.getElementById('editor-form-panel');
                const jsonPanel = document.getElementById('editor-json-panel');
                const rawTextarea = document.getElementById('editor-raw-json');
                const errorEl = document.getElementById('editor-raw-json-error');

                const formVisibleInitial = !formPanel.classList.contains('hidden');
                const jsonVisibleInitial = !jsonPanel.classList.contains('hidden');

                // 2. Switch to Raw JSON Mode
                const switchToJsonSuccess = setEditorMode('json');
                const modeAfterJsonSwitch = getEditorMode();
                const formHiddenAfterSwitch = formPanel.classList.contains('hidden');
                const jsonVisibleAfterSwitch = !jsonPanel.classList.contains('hidden');
                const rawJsonContent = rawTextarea.value;
                const rawContainsGold = rawJsonContent.includes('gold');

                // 3. Edit raw JSON in textarea
                const editedJson = JSON.parse(rawTextarea.value);
                editedJson.priority = 1;
                editedJson.metadata.project = "CRM Global Risk";
                rawTextarea.value = JSON.stringify(editedJson);

                // 4. Switch back to Form Mode -> should sync changes into form fields
                const switchToFormSuccess = setEditorMode('form');
                const modeAfterFormSwitch = getEditorMode();
                const priorityInputValue = document.getElementById('editor-priority').value;
                const projectInputValue = document.getElementById('editor-project').value;

                // 5. Switch to JSON Mode, write malformed JSON, and attempt switch back
                setEditorMode('json');
                rawTextarea.value = '{"unclosedJson": ';
                const invalidSwitchSuccess = setEditorMode('form');
                const modeAfterFailedSwitch = getEditorMode();
                const errorVisible = !errorEl.classList.contains('hidden');
                const errorMsg = errorEl.textContent;

                // Close editor
                closeStubEditor();

                return {
                    initialMode,
                    formVisibleInitial,
                    jsonVisibleInitial,
                    switchToJsonSuccess,
                    modeAfterJsonSwitch,
                    formHiddenAfterSwitch,
                    jsonVisibleAfterSwitch,
                    rawContainsGold,
                    rawJsonContent,
                    switchToFormSuccess,
                    modeAfterFormSwitch,
                    priorityInputValue,
                    projectInputValue,
                    invalidSwitchSuccess,
                    modeAfterFailedSwitch,
                    errorVisible,
                    errorMsgContainsInvalid: errorMsg.includes("Invalid JSON")
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("initialMode")).isEqualTo("form");
        assertThat(result.get("formVisibleInitial")).isEqualTo(true);
        assertThat(result.get("jsonVisibleInitial")).isEqualTo(false);

        assertThat(result.get("switchToJsonSuccess")).isEqualTo(true);
        assertThat(result.get("modeAfterJsonSwitch")).isEqualTo("json");
        assertThat(result.get("formHiddenAfterSwitch")).isEqualTo(true);
        assertThat(result.get("jsonVisibleAfterSwitch")).isEqualTo(true);
        assertThat(result.get("rawContainsGold")).isEqualTo(true);

        assertThat(result.get("switchToFormSuccess")).isEqualTo(true);
        assertThat(result.get("modeAfterFormSwitch")).isEqualTo("form");
        assertThat(result.get("priorityInputValue")).isEqualTo("1");
        assertThat(result.get("projectInputValue")).isEqualTo("CRM Global Risk");

        assertThat(result.get("invalidSwitchSuccess")).isEqualTo(false);
        assertThat(result.get("modeAfterFailedSwitch")).isEqualTo("json");
        assertThat(result.get("errorVisible")).isEqualTo(true);
        assertThat(result.get("errorMsgContainsInvalid")).isEqualTo(true);
    }
}
