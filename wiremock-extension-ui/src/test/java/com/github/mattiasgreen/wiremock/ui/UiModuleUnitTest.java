package com.github.mattiasgreen.wiremock.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import java.util.List;
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

                // Customer call in CRM Risk Control with volatile correlation ID and timestamp
                const customerReq = {
                    id: "req-cust",
                    request: {
                        method: "POST",
                        url: "/__casual/services/crmRiskControlService",
                        body: JSON.stringify({
                            CORRELATION_ID: "b469694c-38eb-4f30-8ca4-10e23b49e00f",
                            REQUEST_TIMESTAMP: 1729999999,
                            customerId: "CUST-001",
                            role: "customer"
                        })
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
                        body: JSON.stringify({
                            CORRELATION_ID: "different-corr-id-777",
                            REQUEST_TIMESTAMP: 1730000000,
                            customerId: "CUST-002",
                            role: "spouse"
                        })
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

                // Casual FML / Base64 nested call
                const fmlReq = {
                    id: "req-fml",
                    request: {
                        method: "POST",
                        url: "/__casual/services/plaVLtlogg",
                        body: JSON.stringify({
                            CORRELATION_ID: "vol-9999",
                            ACME_CORP_HEADER_SOURCE_MACHINE: "SOME_MACHINE",
                            FML_ARCHIVE_JSON: "eyJjYXNlSWQiOiIxMjM1NiJ9",
                            FML_ARCHIVE_TYPE: 5
                        })
                    },
                    response: {
                        status: 200,
                        body: JSON.stringify({
                            RETURN_CODE: 0,
                            FML_ARCHIVE_JSON: "eyJzdGF0dXMiOiJPSyJ9"
                        })
                    }
                };

                const customerStub = buildSnapshotStubMapping(customerReq);
                const customerEqualToJsonStub = buildSnapshotStubMapping(customerReq, { useEqualToJson: true });
                const spouseStub = buildSnapshotStubMapping(spouseReq);
                const fmlStub = buildSnapshotStubMapping(fmlReq);
                const textStub = buildSnapshotStubMapping(textReq);
                const binStub = buildSnapshotStubMapping(binReq);

                return {
                    custName: customerStub.name,
                    custHasBodyPatterns: !!customerStub.request.bodyPatterns,
                    custBodyPatterns: customerStub.request.bodyPatterns,
                    custEqualJson: customerEqualToJsonStub.request.bodyPatterns[0].equalToJson,
                    custIgnoreArrayOrder: customerEqualToJsonStub.request.bodyPatterns[0].ignoreArrayOrder,
                    custIgnoreExtraElements: customerEqualToJsonStub.request.bodyPatterns[0].ignoreExtraElements,

                    spouseName: spouseStub.name,
                    spouseBodyPatterns: spouseStub.request.bodyPatterns,

                    fmlName: fmlStub.name,
                    fmlBodyPatterns: fmlStub.request.bodyPatterns,
                    fmlResponseBody: fmlStub.response.body,

                    textEqualTo: textStub.request.bodyPatterns[0].equalTo,
                    binBinaryEqualTo: binStub.request.bodyPatterns[0].binaryEqualTo
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("custName")).isEqualTo("[SNAPSHOT POST 200] /__casual/services/crmRiskControlService");
        assertThat(result.get("custHasBodyPatterns")).isEqualTo(true);

        // Path matching is default: matchesJsonPath patterns generated for identifying business fields
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> custPatterns =
                (java.util.List<java.util.Map<String, Object>>) result.get("custBodyPatterns");
        assertThat(custPatterns).hasSize(2);
        assertThat(custPatterns).anySatisfy(p -> {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> jp = (java.util.Map<String, Object>) p.get("matchesJsonPath");
            assertThat(jp.get("expression")).isEqualTo("$.customerId");
            assertThat(jp.get("equalTo")).isEqualTo("CUST-001");
        });
        assertThat(custPatterns).anySatisfy(p -> {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> jp = (java.util.Map<String, Object>) p.get("matchesJsonPath");
            assertThat(jp.get("expression")).isEqualTo("$.role");
            assertThat(jp.get("equalTo")).isEqualTo("customer");
        });
        // Volatile CORRELATION_ID and REQUEST_TIMESTAMP must NOT be in path patterns
        assertThat(custPatterns).noneSatisfy(p -> assertThat(String.valueOf(p.get("matchesJsonPath")))
                .containsIgnoringCase("CORRELATION"));
        assertThat(custPatterns).noneSatisfy(p -> assertThat(String.valueOf(p.get("matchesJsonPath")))
                .containsIgnoringCase("TIMESTAMP"));

        // equalToJson also filters out volatile properties and sets ignoreExtraElements: true
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> custMap = (java.util.Map<String, Object>) result.get("custEqualJson");
        assertThat(custMap.get("customerId")).isEqualTo("CUST-001");
        assertThat(custMap.get("role")).isEqualTo("customer");
        assertThat(custMap).doesNotContainKey("CORRELATION_ID");
        assertThat(custMap).doesNotContainKey("REQUEST_TIMESTAMP");
        assertThat(result.get("custIgnoreArrayOrder")).isEqualTo(true);
        assertThat(result.get("custIgnoreExtraElements")).isEqualTo(true);

        // Spouse has distinct body pattern preventing stub collision
        assertThat(result.get("spouseName")).isEqualTo("[SNAPSHOT POST 200] /__casual/services/crmRiskControlService");
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> spousePatterns =
                (java.util.List<java.util.Map<String, Object>>) result.get("spouseBodyPatterns");
        assertThat(spousePatterns).anySatisfy(p -> {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> jp = (java.util.Map<String, Object>) p.get("matchesJsonPath");
            assertThat(jp.get("expression")).isEqualTo("$.customerId");
            assertThat(jp.get("equalTo")).isEqualTo("CUST-002");
        });
        assertThat(spousePatterns).anySatisfy(p -> {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> jp = (java.util.Map<String, Object>) p.get("matchesJsonPath");
            assertThat(jp.get("expression")).isEqualTo("$.role");
            assertThat(jp.get("equalTo")).isEqualTo("spouse");
        });

        // FML Base64 JSON is decoded into nested path matcher and response body
        assertThat(result.get("fmlName")).isEqualTo("[SNAPSHOT POST 200] /__casual/services/plaVLtlogg");
        @SuppressWarnings("unchecked")
        java.util.List<java.util.Map<String, Object>> fmlPatterns =
                (java.util.List<java.util.Map<String, Object>>) result.get("fmlBodyPatterns");
        assertThat(fmlPatterns).anySatisfy(p -> {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> jp = (java.util.Map<String, Object>) p.get("matchesJsonPath");
            assertThat(jp.get("expression")).isEqualTo("$.FML_ARCHIVE_JSON.caseId");
            assertThat(jp.get("equalTo")).isEqualTo("12356");
        });
        assertThat(fmlPatterns).noneSatisfy(p -> assertThat(String.valueOf(p.get("matchesJsonPath")))
                .containsIgnoringCase("CORRELATION"));

        String fmlResBody = (String) result.get("fmlResponseBody");
        assertThat(fmlResBody).contains("\"status\": \"OK\"");
        assertThat(fmlResBody).doesNotContain("eyJzdGF0dXMiOiJPSyJ9");

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

    @Test
    @DisplayName(
            "Snapshot Modal: Reuses stub editor modal with decoded Base64 JSON, unescaped equalToJson, and relaxation controls")
    void testSnapshotStubModalPrePopulationAndMatcherRelaxation() {
        String script =
                """
            async () => {
                const { buildSnapshotStubMapping } = await import('./modules/journal.js');
                const { openStubEditor, closeStubEditor, setEditorMode, buildStubFromForm } = await import('./modules/lifecycle.js');

                const journalReq = {
                    id: "req-snapshot-editor-test",
                    request: {
                        method: "POST",
                        url: "/__casual/services/creditCheckService",
                        body: JSON.stringify({
                            SOURCE_SYSTEM: "ERP",
                            FML_ARCHIVE_JSON: "eyJzY29yZSI6NzUwfQ==",
                            REQUEST_FLAGS: 1
                        })
                    },
                    response: {
                        status: 200,
                        body: JSON.stringify({
                            STATUS: "APPROVED",
                            FML_ARCHIVE_JSON: "eyJsaW1pdCI6NTAwMDB9"
                        })
                    }
                };

                const stubData = buildSnapshotStubMapping(journalReq);

                // Open stub editor with snapshot stub
                openStubEditor(stubData, false);
                const titleEl = document.getElementById('editor-modal-title');
                titleEl.textContent = 'Snapshot Stub from Journal';

                const modal = document.getElementById('stub-editor-modal');
                const isModalVisible = !modal.classList.contains('hidden');
                const modalTitle = titleEl.textContent;

                const matchTypeSelect = document.getElementById('editor-body-match-type');
                const matchPatternInput = document.getElementById('editor-body-match-pattern');
                const ignoreExtraChk = document.getElementById('editor-json-ignore-extra');
                const ignoreOrderChk = document.getElementById('editor-json-ignore-order');
                const formatReqBtn = document.getElementById('btn-editor-format-request-json');
                const responseBodyInput = document.getElementById('editor-body');

                const matchType = matchTypeSelect.value;
                const patternValue = matchPatternInput.value;
                const responseBodyValue = responseBodyInput.value;

                // Test switching to raw JSON mode to inspect serialized representation
                setEditorMode('json');
                const rawTextarea = document.getElementById('editor-raw-json');
                const rawJson = JSON.parse(rawTextarea.value);

                // Switch back to form mode and build stub payload
                setEditorMode('form');
                const builtPayload = buildStubFromForm();

                closeStubEditor();

                return {
                    isModalVisible,
                    modalTitle,
                    matchType,
                    patternContainsScore: patternValue.includes('750'),
                    patternContainsJsonPath: patternValue.includes('$.FML_ARCHIVE_JSON.score'),
                    patternContainsNoB64: !patternValue.includes('eyJzY29yZSI6NzUwfQ=='),
                    responseContainsLimit: responseBodyValue.includes('"limit": 50000'),
                    responseContainsNoB64: !responseBodyValue.includes('eyJsaW1pdCI6NTAwMDB9'),
                    formatReqBtnVisible: !formatReqBtn.classList.contains('hidden'),
                    rawJsonPathMatches: Array.isArray(rawJson.request.bodyPatterns) && rawJson.request.bodyPatterns.some(p => (typeof p.matchesJsonPath === 'object' ? p.matchesJsonPath.expression : p.matchesJsonPath) === '$.FML_ARCHIVE_JSON.score'),
                    builtPayloadHasJsonPath: Array.isArray(builtPayload.request.bodyPatterns) && builtPayload.request.bodyPatterns.some(p => (typeof p.matchesJsonPath === 'object' ? p.matchesJsonPath.expression : p.matchesJsonPath) === '$.FML_ARCHIVE_JSON.score')
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("isModalVisible")).isEqualTo(true);
        assertThat(result.get("modalTitle")).isEqualTo("Snapshot Stub from Journal");
        assertThat(result.get("matchType")).isEqualTo("matchesJsonPath");
        assertThat(result.get("patternContainsScore")).isEqualTo(true);
        assertThat(result.get("patternContainsJsonPath")).isEqualTo(true);
        assertThat(result.get("patternContainsNoB64")).isEqualTo(true);
        assertThat(result.get("responseContainsLimit")).isEqualTo(true);
        assertThat(result.get("responseContainsNoB64")).isEqualTo(true);
        assertThat(result.get("formatReqBtnVisible")).isEqualTo(true);
        assertThat(result.get("rawJsonPathMatches")).isEqualTo(true);
        assertThat(result.get("builtPayloadHasJsonPath")).isEqualTo(true);
    }

    @Test
    @DisplayName("testerLocalHeaders: Macro resolution, storage persistence, and precedence override")
    void testTesterLocalHeaders() {
        String script =
                """
            async () => {
                const {
                    resolveMacro,
                    loadLocalHeaders,
                    saveLocalHeaders,
                    isLocalHeadersEnabled,
                    setLocalHeadersEnabled,
                    getEffectiveTesterHeaders,
                    LOCAL_HEADERS_KEY,
                    LOCAL_HEADERS_ENABLED_KEY
                } = await import('./modules/tester.js');

                // 1. Test Macro resolution
                const resolvedUuid = resolveMacro("Bearer {{uuid}}");
                const resolvedNow = resolveMacro("{{now}}");
                const resolvedTs = resolveMacro("{{timestamp}}");

                // 2. Storage save and load
                const sampleHeaders = [
                    { name: "X-Tenant-Id", value: "corp-internal", enabled: true },
                    { name: "X-User-Id", value: "user-123", enabled: true },
                    { name: "X-Disabled-Header", value: "should-not-apply", enabled: false }
                ];
                saveLocalHeaders(sampleHeaders);
                const loaded = loadLocalHeaders();

                // 3. Effective headers calculation without overrides
                setLocalHeadersEnabled(true);
                const effectiveNoOverride = getEffectiveTesterHeaders("Accept: application/json", true);

                // 4. Effective headers calculation with case-insensitive override
                const effectiveWithOverride = getEffectiveTesterHeaders("x-tenant-id: tenant-override\\nAccept: application/json", true);

                // 5. Disabled state
                setLocalHeadersEnabled(false);
                const effectiveDisabled = getEffectiveTesterHeaders("Accept: application/json", true);

                // Reset storage
                localStorage.removeItem(LOCAL_HEADERS_KEY);
                localStorage.removeItem(LOCAL_HEADERS_ENABLED_KEY);

                return {
                    uuidMatchesPattern: /^Bearer [0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(resolvedUuid),
                    nowIsIso: !isNaN(Date.parse(resolvedNow)),
                    tsIsNumeric: !isNaN(Number(resolvedTs)),
                    loadedCount: loaded.length,
                    noOverrideTenant: effectiveNoOverride.headers["X-Tenant-Id"],
                    noOverrideUser: effectiveNoOverride.headers["X-User-Id"],
                    noOverrideAccept: effectiveNoOverride.headers["Accept"],
                    hasDisabledHeader: Boolean(effectiveNoOverride.headers["X-Disabled-Header"]),
                    withOverrideTenant: effectiveWithOverride.headers["x-tenant-id"],
                    withOverrideOverriddenKeysCount: effectiveWithOverride.overriddenKeys.size,
                    withOverrideHasTenantOverridden: effectiveWithOverride.overriddenKeys.has("x-tenant-id"),
                    disabledHasTenant: Boolean(effectiveDisabled.headers["X-Tenant-Id"]),
                    disabledAccept: effectiveDisabled.headers["Accept"]
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("uuidMatchesPattern")).isEqualTo(true);
        assertThat(result.get("nowIsIso")).isEqualTo(true);
        assertThat(result.get("tsIsNumeric")).isEqualTo(true);
        assertThat(result.get("loadedCount")).isEqualTo(3);
        assertThat(result.get("noOverrideTenant")).isEqualTo("corp-internal");
        assertThat(result.get("noOverrideUser")).isEqualTo("user-123");
        assertThat(result.get("noOverrideAccept")).isEqualTo("application/json");
        assertThat(result.get("hasDisabledHeader")).isEqualTo(false);
        assertThat(result.get("withOverrideTenant")).isEqualTo("tenant-override");
        assertThat(result.get("withOverrideOverriddenKeysCount")).isEqualTo(1);
        assertThat(result.get("withOverrideHasTenantOverridden")).isEqualTo(true);
        assertThat(result.get("disabledHasTenant")).isEqualTo(false);
        assertThat(result.get("disabledAccept")).isEqualTo("application/json");
    }

    @Test
    @DisplayName("preferences: JSON path exclusions in localStorage, copy-paste import/export, and stub matching")
    void testBrowserPreferencesAndExclusionSync() {
        String script =
                """
            async () => {
                const {
                    loadPreferences,
                    savePreferences,
                    exportPreferencesJson,
                    importPreferencesJson,
                    isJsonPathExcluded,
                    LOCAL_SETTINGS_KEY
                } = await import('./modules/settings.js');
                const { buildSnapshotStubMapping } = await import('./modules/journal.js');

                // 1. Initial defaults
                localStorage.removeItem(LOCAL_SETTINGS_KEY);
                const defaultPrefs = loadPreferences();
                const defaultHeaderNames = defaultPrefs.headers.map(h => h.name);
                const defaultPaths = defaultPrefs.excludedJsonPaths;

                // 2. Custom preferences with specific exclusion paths
                const customPrefs = {
                    headers: [
                        { name: "X-Custom-Env", value: "staging-01", enabled: true }
                    ],
                    excludedJsonPaths: [
                        "$.CORRELATION_ID",
                        "$.FML_ARCHIVE_JSON.sessionToken",
                        "timestamp",
                        "traceId"
                    ]
                };
                savePreferences(customPrefs);
                const reloaded = loadPreferences();

                // 3. Path exclusion rule evaluation
                const matchesCorrelation = isJsonPathExcluded("$.CORRELATION_ID", "CORRELATION_ID", reloaded.excludedJsonPaths);
                const matchesSession = isJsonPathExcluded("$.FML_ARCHIVE_JSON.sessionToken", "sessionToken", reloaded.excludedJsonPaths);
                const matchesKeyOnly = isJsonPathExcluded("$.nested.payload.timestamp", "timestamp", reloaded.excludedJsonPaths);
                const doesNotMatchBusinessField = isJsonPathExcluded("$.FML_ARCHIVE_JSON.caseId", "caseId", reloaded.excludedJsonPaths);

                // 4. Export configuration to JSON string
                const exportedJson = exportPreferencesJson();
                const parsedExport = JSON.parse(exportedJson);

                // 5. Import modified configuration via text paste
                const modifiedImport = JSON.stringify({
                    headers: [
                        { name: "X-Imported-Auth", value: "Bearer {{uuid}}", enabled: true }
                    ],
                    excludedJsonPaths: [
                        "$.CORRELATION_ID",
                        "$.REQUEST_TIMESTAMP",
                        "ephemeralToken"
                    ]
                });
                const importRes = importPreferencesJson(modifiedImport);
                const importedPrefs = loadPreferences();

                // 6. Snapshot stub creation respects newly imported exclusion paths
                const req = {
                    id: "req-pref-test",
                    request: {
                        method: "POST",
                        url: "/api/orders",
                        body: JSON.stringify({
                            orderId: "ORD-999",
                            CORRELATION_ID: "corr-111",
                            REQUEST_TIMESTAMP: "2026-10-27T00:00:00Z",
                            ephemeralToken: "temp-987",
                            customerId: "CUST-42"
                        })
                    },
                    response: {
                        status: 201,
                        body: '{"status": "CREATED"}'
                    }
                };
                const snapshotStub = buildSnapshotStubMapping(req);
                const bodyPatterns = snapshotStub.request.bodyPatterns || [];

                // Cleanup storage
                localStorage.removeItem(LOCAL_SETTINGS_KEY);

                return {
                    defaultHasCorrHeader: defaultHeaderNames.includes("X-Correlation-Id"),
                    defaultHasCorrPath: defaultPaths.includes("$.CORRELATION_ID"),
                    reloadedHeader: reloaded.headers[0].name,
                    matchesCorrelation,
                    matchesSession,
                    matchesKeyOnly,
                    doesNotMatchBusinessField,
                    exportHasHeaders: Array.isArray(parsedExport.headers),
                    exportHasPaths: Array.isArray(parsedExport.excludedJsonPaths),
                    importSuccess: importRes.success,
                    importedHeader: importedPrefs.headers[0].name,
                    importedPathsCount: importedPrefs.excludedJsonPaths.length,
                    bodyPatternsCount: bodyPatterns.length,
                    hasOrderIdMatcher: bodyPatterns.some(p => typeof p.matchesJsonPath === 'object' ? (p.matchesJsonPath.expression === "$.orderId" && p.matchesJsonPath.equalTo === "ORD-999") : (p.matchesJsonPath === "$.orderId" && p.equalTo === "ORD-999")),
                    hasCustomerIdMatcher: bodyPatterns.some(p => typeof p.matchesJsonPath === 'object' ? (p.matchesJsonPath.expression === "$.customerId" && p.matchesJsonPath.equalTo === "CUST-42") : (p.matchesJsonPath === "$.customerId" && p.equalTo === "CUST-42")),
                    omittedCorrelationId: !bodyPatterns.some(p => JSON.stringify(p.matchesJsonPath).includes("CORRELATION_ID")),
                    omittedRequestTimestamp: !bodyPatterns.some(p => JSON.stringify(p.matchesJsonPath).includes("REQUEST_TIMESTAMP")),
                    omittedEphemeralToken: !bodyPatterns.some(p => JSON.stringify(p.matchesJsonPath).includes("ephemeralToken"))
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("defaultHasCorrHeader")).isEqualTo(true);
        assertThat(result.get("defaultHasCorrPath")).isEqualTo(true);
        assertThat(result.get("reloadedHeader")).isEqualTo("X-Custom-Env");
        assertThat(result.get("matchesCorrelation")).isEqualTo(true);
        assertThat(result.get("matchesSession")).isEqualTo(true);
        assertThat(result.get("matchesKeyOnly")).isEqualTo(true);
        assertThat(result.get("doesNotMatchBusinessField")).isEqualTo(false);
        assertThat(result.get("exportHasHeaders")).isEqualTo(true);
        assertThat(result.get("exportHasPaths")).isEqualTo(true);
        assertThat(result.get("importSuccess")).isEqualTo(true);
        assertThat(result.get("importedHeader")).isEqualTo("X-Imported-Auth");
        assertThat(result.get("importedPathsCount")).isEqualTo(3);
        assertThat(result.get("bodyPatternsCount")).isEqualTo(2);
        assertThat(result.get("hasOrderIdMatcher")).isEqualTo(true);
        assertThat(result.get("hasCustomerIdMatcher")).isEqualTo(true);
        assertThat(result.get("omittedCorrelationId")).isEqualTo(true);
        assertThat(result.get("omittedRequestTimestamp")).isEqualTo(true);
        assertThat(result.get("omittedEphemeralToken")).isEqualTo(true);
    }

    @Test
    @DisplayName(
            "StubCatalog: getVisibleCatalogStubs and getCurrentlyVisibleStubs unify filtering, projects, and selection")
    void testStubCatalogFilteringAndCounts() {
        String script =
                """
            async () => {
                const { state } = await import('./modules/state.js');
                const { getVisibleCatalogStubs, getCatalogCounts, findCatalogStubById } = await import('./modules/stubs.js');
                const { getCurrentlyVisibleStubs } = await import('./modules/bulk-actions.js');

                // Seed test state
                state.currentStubs = [
                    {
                        id: "active-1",
                        name: "Get Orders",
                        request: { method: "GET", url: "/api/v1/orders" },
                        response: { status: 200 }
                    },
                    {
                        id: "active-2",
                        name: "Create User",
                        request: { method: "POST", url: "/api/v1/users" },
                        response: { status: 201 }
                    }
                ];
                state.disabledStubs = [
                    {
                        id: "disabled-1",
                        name: "Get Single Order",
                        request: { method: "GET", url: "/api/v1/orders/123" },
                        response: { status: 200 }
                    }
                ];
                state.statefulModels = [
                    {
                        entity: "cases",
                        rules: [
                            {
                                route: { method: "POST", pathPattern: "/api/v1/cases" },
                                response: { statusCode: 201 }
                            }
                        ]
                    }
                ];
                state.selectedProject = null;
                state.statusFilter = "all";

                const allVisible = getVisibleCatalogStubs("");
                const bulkVisible = getCurrentlyVisibleStubs();

                // Project filtering check (inferred 'api-orders')
                state.selectedProject = "api-orders";
                const ordersVisible = getVisibleCatalogStubs("");
                const ordersBulk = getCurrentlyVisibleStubs();
                const ordersCounts = getCatalogCounts("api-orders");

                // Status filter checks
                state.selectedProject = null;
                state.statusFilter = "active";
                const activeOnly = getVisibleCatalogStubs("");

                state.statusFilter = "disabled";
                const disabledOnly = getVisibleCatalogStubs("");

                state.statusFilter = "dynamic";
                const dynamicOnly = getVisibleCatalogStubs("");

                // Query filter checks with boolean AND
                state.statusFilter = "all";
                const andQuery = getVisibleCatalogStubs("GET AND /orders");

                const foundActive = findCatalogStubById("active-1");
                const foundDisabled = findCatalogStubById("disabled-1");
                const foundDynamic = findCatalogStubById("dynamic-cases-0");

                // Reset state
                state.selectedProject = null;
                state.statusFilter = "all";
                state.currentStubs = [];
                state.disabledStubs = [];
                state.statefulModels = [];

                return {
                    allVisibleCount: allVisible.length,
                    bulkVisibleCount: bulkVisible.length,
                    bulkExcludesDynamic: !bulkVisible.some(s => s.isDynamic || s._dynamic),
                    ordersVisibleCount: ordersVisible.length,
                    ordersBulkCount: ordersBulk.length,
                    ordersCountsAll: ordersCounts.all,
                    ordersCountsActive: ordersCounts.active,
                    ordersCountsDisabled: ordersCounts.disabled,
                    activeOnlyCount: activeOnly.length,
                    disabledOnlyCount: disabledOnly.length,
                    dynamicOnlyCount: dynamicOnly.length,
                    andQueryCount: andQuery.length,
                    foundActive: foundActive !== null && foundActive.id === "active-1",
                    foundDisabled: foundDisabled !== null && foundDisabled.id === "disabled-1",
                    foundDynamic: foundDynamic !== null && foundDynamic.id === "dynamic-cases-0"
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("allVisibleCount")).isEqualTo(4); // 2 active + 1 disabled + 1 dynamic
        assertThat(result.get("bulkVisibleCount")).isEqualTo(3); // excludes dynamic
        assertThat(result.get("bulkExcludesDynamic")).isEqualTo(true);
        assertThat(result.get("ordersVisibleCount")).isEqualTo(2); // active-1 and disabled-1
        assertThat(result.get("ordersBulkCount")).isEqualTo(2);
        assertThat(result.get("ordersCountsAll")).isEqualTo(2);
        assertThat(result.get("ordersCountsActive")).isEqualTo(1);
        assertThat(result.get("ordersCountsDisabled")).isEqualTo(1);
        assertThat(result.get("activeOnlyCount")).isEqualTo(2);
        assertThat(result.get("disabledOnlyCount")).isEqualTo(1);
        assertThat(result.get("dynamicOnlyCount")).isEqualTo(1);
        assertThat(result.get("andQueryCount")).isEqualTo(2); // active-1 and disabled-1
        assertThat(result.get("foundActive")).isEqualTo(true);
        assertThat(result.get("foundDisabled")).isEqualTo(true);
        assertThat(result.get("foundDynamic")).isEqualTo(true);
    }

    @Test
    @DisplayName(
            "Dual-Implementation Parity: Java JournalQueryMatcher vs ES6 filter.js evaluateQuery produce identical results")
    void testDualImplementationQueryMatcherParity() {
        String corpus = "get /api/v1/orders 200 customer-42 corp-tenant happy";
        List<String> queryMatrix = List.of(
                "orders",
                "orders AND 200",
                "orders AND 500",
                "orders OR 500",
                "users OR cases",
                "orders 200",
                "orders 500",
                "orders AND 500 OR 200",
                "500 OR orders AND 200",
                "orders and 200 or 500",
                "missing OR nonexistent",
                "corp-tenant AND customer-42",
                "not-found AND happy");

        for (String query : queryMatrix) {
            boolean javaOutcome = JournalQueryMatcher.evaluateExpression(
                    query, token -> corpus.contains(token.toLowerCase(java.util.Locale.ROOT)));

            String script = String.format(
                    """
                async () => {
                    const { evaluateQuery } = await import('./modules/filter.js');
                    const corpus = %s;
                    return evaluateQuery(%s, token => corpus.includes(token.toLowerCase()));
                }
            """,
                    com.github.tomakehurst.wiremock.common.Json.write(corpus),
                    com.github.tomakehurst.wiremock.common.Json.write(query));

            boolean es6Outcome = Boolean.TRUE.equals(evalJs(script));

            assertThat(es6Outcome)
                    .as("ES6 and Java query matching parity failed for query: '%s'", query)
                    .isEqualTo(javaOutcome);
        }
    }

    @Test
    @DisplayName("Nested Payload Lens: XML formatting, minification, and token highlighting")
    void testXmlFormattingAndHighlighting() {
        String script =
                """
            async () => {
                const { formatXml, minifyXml, highlightXml, detectFormat } = await import('./modules/highlighter.js');
                const rawXml = '<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><m:GetQuote xmlns:m="http://services.samples"><m:symbol>GOOG</m:symbol><!-- Test Comment --><m:data><![CDATA[Some CData Text]]></m:data></m:GetQuote></soap:Body></soap:Envelope>';

                const formatted = formatXml(rawXml, '  ');
                const minified = minifyXml(formatted);
                const highlighted = highlightXml(formatted);
                const detected = detectFormat(rawXml);

                return {
                    detected,
                    isFormattedIndented: formatted.includes('\\n  <soap:Body>'),
                    minifiedMatchesOriginal: minified.includes('<soap:Envelope xmlns:soap=') && !minified.includes('\\n'),
                    hasTagToken: highlighted.includes('class="xml-tag"'),
                    hasPrefixToken: highlighted.includes('class="xml-prefix"'),
                    hasAttrNameToken: highlighted.includes('class="xml-attr-name"'),
                    hasAttrValToken: highlighted.includes('class="xml-attr-val"'),
                    hasCDataToken: highlighted.includes('class="xml-cdata"'),
                    hasCommentToken: highlighted.includes('class="xml-comment"')
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("detected")).isEqualTo("xml");
        assertThat(result.get("isFormattedIndented")).isEqualTo(true);
        assertThat(result.get("minifiedMatchesOriginal")).isEqualTo(true);
        assertThat(result.get("hasTagToken")).isEqualTo(true);
        assertThat(result.get("hasPrefixToken")).isEqualTo(true);
        assertThat(result.get("hasAttrNameToken")).isEqualTo(true);
        assertThat(result.get("hasAttrValToken")).isEqualTo(true);
        assertThat(result.get("hasCDataToken")).isEqualTo(true);
        assertThat(result.get("hasCommentToken")).isEqualTo(true);
    }

    @Test
    @DisplayName("Nested Payload Lens: Deep multi-layer payload discovery across JSON, XML, CDATA, and Base64")
    void testDeepPayloadUnnestingDiscovery() {
        String script =
                """
            async () => {
                const { discoverEmbeddedPayloads, utf8ToBase64 } = await import('./modules/payload-lens.js');

                // 3-level payload: JSON -> escaped JSON string -> escaped SOAP XML
                const innerSoapXml = '<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><id>12345</id></soap:Body></soap:Envelope>';
                const middleJson = JSON.stringify({ innerXml: innerSoapXml, status: "OK" });
                const rootJson = JSON.stringify({
                    requestId: "req-99",
                    payload: middleJson,
                    b64Report: utf8ToBase64('<report><score>850</score></report>')
                });

                const discovered = discoverEmbeddedPayloads(rootJson);

                return {
                    count: discovered.length,
                    firstPath: discovered[0] ? discovered[0].path : null,
                    firstFormat: discovered[0] ? discovered[0].format : null,
                    firstEncoding: discovered[0] ? discovered[0].encoding : null,
                    firstHasChildren: discovered[0] && discovered[0].children && discovered[0].children.length > 0,
                    firstGrandchildPath: (discovered[0] && discovered[0].children && discovered[0].children[0]) ? discovered[0].children[0].path : null,
                    firstGrandchildFormat: (discovered[0] && discovered[0].children && discovered[0].children[0]) ? discovered[0].children[0].format : null,
                    secondPath: discovered[1] ? discovered[1].path : null,
                    secondFormat: discovered[1] ? discovered[1].format : null,
                    secondEncoding: discovered[1] ? discovered[1].encoding : null
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("count")).isEqualTo(2);
        assertThat(result.get("firstPath")).isEqualTo("$.payload");
        assertThat(result.get("firstFormat")).isEqualTo("json");
        assertThat(result.get("firstEncoding")).isEqualTo("JSON_STRING");
        assertThat(result.get("firstHasChildren")).isEqualTo(true);
        assertThat(result.get("firstGrandchildPath")).isEqualTo("$.payload.innerXml");
        assertThat(result.get("firstGrandchildFormat")).isEqualTo("xml");
        assertThat(result.get("secondPath")).isEqualTo("$.b64Report");
        assertThat(result.get("secondFormat")).isEqualTo("xml");
        assertThat(result.get("secondEncoding")).isEqualTo("BASE64_UTF8");
    }

    @Test
    @DisplayName(
            "Nested Payload Lens: Cascading round-trip re-escaping fidelity from leaf XML through middle JSON to root JSON")
    void testRoundTripReEscapingFidelity() {
        String script =
                """
            async () => {
                const { discoverEmbeddedPayloads, cascadeLensStackToRoot, applyNestedPayloadEdit } = await import('./modules/payload-lens.js');

                // Initial 3-level document
                const innerSoapXml = '<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><id>OLD_ID</id></soap:Body></soap:Envelope>';
                const middleJson = JSON.stringify({ innerXml: innerSoapXml, notes: "Original" });
                const rootJson = JSON.stringify({
                    requestId: "REQ-001",
                    data: middleJson
                }, null, 2);

                const rootDiscovered = discoverEmbeddedPayloads(rootJson);
                const middleNode = rootDiscovered[0];
                const xmlNode = middleNode.children[0];

                // Build a 3-frame lens stack
                const stack = [
                    { text: rootJson, descriptor: null, title: 'Root', format: 'json' },
                    { text: middleNode.unescapedValue, descriptor: middleNode, title: middleNode.path, format: 'json' },
                    { text: xmlNode.unescapedValue, descriptor: xmlNode, title: xmlNode.path, format: 'xml' }
                ];

                // Edit the leaf XML
                const editedLeafXml = '<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><id>NEW_CASE_9999</id><status>APPROVED</status></soap:Body></soap:Envelope>';

                // Save and cascade to root
                const newRootJson = cascadeLensStackToRoot(stack, editedLeafXml, { minifyOnSave: false });

                // Verify wire fidelity: parse the root JSON and read middle JSON
                const parsedRoot = JSON.parse(newRootJson);
                const parsedMiddle = JSON.parse(parsedRoot.data);

                return {
                    requestId: parsedRoot.requestId,
                    middleNotes: parsedMiddle.notes,
                    updatedXmlContainsNewId: parsedMiddle.innerXml.includes('NEW_CASE_9999'),
                    updatedXmlContainsApproved: parsedMiddle.innerXml.includes('APPROVED'),
                    isMiddleJsonValid: typeof parsedMiddle === 'object'
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("requestId")).isEqualTo("REQ-001");
        assertThat(result.get("middleNotes")).isEqualTo("Original");
        assertThat(result.get("updatedXmlContainsNewId")).isEqualTo(true);
        assertThat(result.get("updatedXmlContainsApproved")).isEqualTo(true);
        assertThat(result.get("isMiddleJsonValid")).isEqualTo(true);
    }

    @Test
    @DisplayName(
            "Nested Payload Lens: Syntax validation blocks invalid payload while forceInvalid allows negative testing")
    void testValidationAndForceApplyOverride() {
        String script =
                """
            async () => {
                const { validatePayload, applyNestedPayloadEdit } = await import('./modules/payload-lens.js');

                const validJsonVal = validatePayload('{"valid": true}', 'json');
                const invalidJsonVal = validatePayload('{"bad": }', 'json');

                const validXmlVal = validatePayload('<root><child>OK</child></root>', 'xml');
                const invalidXmlVal = validatePayload('<root><mismatch></root>', 'xml');

                const parentJson = '{"embedded": "{\\\\"a\\\\": 1}"}';
                const nodeDescriptor = {
                    path: '$.embedded',
                    format: 'json',
                    encoding: 'JSON_STRING',
                    wasCompact: true
                };

                let errorThrown = false;
                try {
                    applyNestedPayloadEdit(parentJson, nodeDescriptor, '{"malformed": }', { forceInvalid: false });
                } catch (err) {
                    errorThrown = true;
                }

                // Force apply invalid payload
                const forcedParent = applyNestedPayloadEdit(parentJson, nodeDescriptor, '{"malformed": }', { forceInvalid: true });
                const parsedForced = JSON.parse(forcedParent);

                return {
                    validJsonValid: validJsonVal.valid,
                    invalidJsonValid: invalidJsonVal.valid,
                    invalidJsonHasError: typeof invalidJsonVal.errorMessage === 'string',
                    validXmlValid: validXmlVal.valid,
                    invalidXmlValid: invalidXmlVal.valid,
                    invalidXmlHasError: typeof invalidXmlVal.errorMessage === 'string',
                    errorThrownOnInvalidApply: errorThrown,
                    forcedAppliedValue: parsedForced.embedded
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("validJsonValid")).isEqualTo(true);
        assertThat(result.get("invalidJsonValid")).isEqualTo(false);
        assertThat(result.get("invalidJsonHasError")).isEqualTo(true);
        assertThat(result.get("validXmlValid")).isEqualTo(true);
        assertThat(result.get("invalidXmlValid")).isEqualTo(false);
        assertThat(result.get("invalidXmlHasError")).isEqualTo(true);
        assertThat(result.get("errorThrownOnInvalidApply")).isEqualTo(true);
        assertThat(result.get("forcedAppliedValue")).isEqualTo("{\"malformed\": }");
    }
}
