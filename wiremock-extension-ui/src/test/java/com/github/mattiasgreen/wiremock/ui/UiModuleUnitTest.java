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
    @DisplayName("matchesJournalEntry: Method, URL, status, and stub name baseline matching")
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
    @DisplayName("extractRequestSource & matchesJournalEntry: Source IP/host extraction and src:/ip:/host: filtering")
    void testJournalSourceExtractionAndFiltering() {
        String script =
                """
            async () => {
                const { extractRequestSource, matchesJournalEntry } = await import('./modules/filter.js');

                const enrichedEntry = {
                    id: "req-enriched",
                    sourceIp: "10.20.30.40",
                    sourceHost: "billing-api-01.corp.internal",
                    request: {
                        method: "POST",
                        url: "/api/billing",
                        clientIp: "127.0.0.1",
                        headers: {
                            "X-Forwarded-For": "10.20.30.40, 10.0.0.1"
                        }
                    },
                    response: { status: 200 }
                };

                const vanillaXffEntry = {
                    id: "req-vanilla-xff",
                    request: {
                        method: "GET",
                        url: "/api/health",
                        clientIp: "127.0.0.1",
                        headers: {
                            "X-Forwarded-For": "203.0.113.50:8080, 10.0.0.1",
                            "X-Real-IP": "198.51.100.2"
                        }
                    },
                    response: { status: 200 }
                };

                const loopbackEntry = {
                    id: "req-loopback",
                    request: {
                        method: "GET",
                        url: "/api/local",
                        clientIp: "::1",
                        headers: {}
                    },
                    response: { status: 200 }
                };

                const enrichedSource = extractRequestSource(enrichedEntry);
                const vanillaSource = extractRequestSource(vanillaXffEntry);
                const loopbackSource = extractRequestSource(loopbackEntry);

                return {
                    enrichedIp: enrichedSource.ip,
                    enrichedHost: enrichedSource.host,
                    enrichedShort: enrichedSource.shortHost,
                    vanillaIp: vanillaSource.ip,
                    loopbackIp: loopbackSource.ip,
                    loopbackHost: loopbackSource.host,
                    matchHostPrefix: matchesJournalEntry(enrichedEntry, "host:billing-api-01"),
                    matchIpPrefix: matchesJournalEntry(enrichedEntry, "ip:10.20.30.40"),
                    matchSrcPrefix: matchesJournalEntry(vanillaXffEntry, "src:203.0.113.50"),
                    matchBareHost: matchesJournalEntry(enrichedEntry, "corp.internal"),
                    matchLoopbackLocalhost: matchesJournalEntry(loopbackEntry, "host:localhost"),
                    missOtherHost: matchesJournalEntry(enrichedEntry, "host:checkout-web")
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("enrichedIp")).isEqualTo("10.20.30.40");
        assertThat(result.get("enrichedHost")).isEqualTo("billing-api-01.corp.internal");
        assertThat(result.get("enrichedShort")).isEqualTo("billing-api-01");
        assertThat(result.get("vanillaIp")).isEqualTo("203.0.113.50");
        assertThat(result.get("loopbackIp")).isEqualTo("::1");
        assertThat(result.get("loopbackHost")).isEqualTo("localhost");
        assertThat(result.get("matchHostPrefix")).isEqualTo(true);
        assertThat(result.get("matchIpPrefix")).isEqualTo(true);
        assertThat(result.get("matchSrcPrefix")).isEqualTo(true);
        assertThat(result.get("matchBareHost")).isEqualTo(true);
        assertThat(result.get("matchLoopbackLocalhost")).isEqualTo(true);
        assertThat(result.get("missOtherHost")).isEqualTo(false);
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

                return {
                    standardCurl: generateCreateStubCurl(standardStub)
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
    @DisplayName(
            "Document Workspace & Lifecycle: Viewer-first open, template switch, root editor validation, force-apply, and dirty guard")
    void testDocumentWorkspaceLifecycleAndValidation() {
        String script =
                """
            async () => {
                const { openStubEditor, STUB_TEMPLATES } = await import('./modules/lifecycle.js');
                const {
                    closeDocumentWorkspace,
                    isWorkspaceOpen,
                    isWorkspaceDirty,
                    getWorkspaceRootText
                } = await import('./modules/document-workspace.js');

                // 1. Open New Stub in Document Workspace -> opens in DOCUMENT_VIEWER with templates visible
                openStubEditor(null, false);
                const modal = document.getElementById('modal-document-workspace');
                const viewerPane = document.getElementById('workspace-viewer-pane');
                const editorPane = document.getElementById('workspace-editor-pane');
                const tplGroup = document.getElementById('workspace-template-group');
                const tplSelect = document.getElementById('workspace-template-select');

                const openedInViewer = isWorkspaceOpen()
                    && !modal.classList.contains('hidden')
                    && !viewerPane.classList.contains('hidden')
                    && editorPane.classList.contains('hidden')
                    && !tplGroup.classList.contains('hidden');

                const templateCount = tplSelect.options.length;
                const templateKeysMatch = templateCount === Object.keys(STUB_TEMPLATES).length;
                const hasProtocolTemplates = ['casual-stub', 'casual-proxy', 'amqp-stub', 'amqp-proxy', 'kafka-stub', 'kafka-proxy']
                    .every(k => Array.from(tplSelect.options).some(opt => opt.value === k) && Boolean(STUB_TEMPLATES[k]))
                    && STUB_TEMPLATES['casual-proxy'].response?.proxyBaseUrl === 'casual://upstream-casual:7772'
                    && STUB_TEMPLATES['amqp-proxy'].response?.proxyBaseUrl === 'async://upstream-amqp'
                    && STUB_TEMPLATES['kafka-proxy'].response?.proxyBaseUrl === 'async://upstream-kafka';

                // 2. Switch template via select
                tplSelect.value = 'body-contains';
                tplSelect.dispatchEvent(new Event('change'));
                await new Promise(r => setTimeout(r, 20));
                const templateSwitched = getWorkspaceRootText().includes('Submit Message (Body Contains)')
                    && getWorkspaceRootText().includes('string X');

                // 3. Enter Root Section Editor
                document.getElementById('btn-workspace-edit-root').click();
                const inSectionEditor = viewerPane.classList.contains('hidden')
                    && !editorPane.classList.contains('hidden');

                // 4. Inject malformed JSON into editor and verify live validation blocks normal Apply but allows Force Apply
                const editorInput = document.querySelector('#workspace-code-editor-host [data-testid="code-editor-input"]');
                const isPre = editorInput && editorInput.tagName === 'PRE';
                if (isPre) {
                    editorInput.textContent = '{"unclosedJson": ';
                } else if (editorInput) {
                    editorInput.value = '{"unclosedJson": ';
                }
                editorInput.dispatchEvent(new Event('input', { bubbles: true }));

                const lintPill = document.getElementById('workspace-lint-pill');
                const banner = document.getElementById('workspace-validation-banner');
                const applyBtn = document.getElementById('btn-workspace-apply-section');
                const forceBtn = document.getElementById('btn-workspace-force-apply');

                const lintVisible = !lintPill.classList.contains('hidden') && lintPill.textContent.includes('Invalid JSON');
                const bannerVisible = !banner.classList.contains('hidden');
                const applyDisabled = applyBtn.disabled;
                const forceVisible = !forceBtn.classList.contains('hidden');

                // 5. Fix JSON, apply back to viewer, verify dirty badge
                const validEdited = JSON.stringify({
                    name: 'Edited Workspace Stub',
                    priority: 2,
                    request: { method: 'GET', url: '/api/workspace-test' },
                    response: { status: 200, jsonBody: { ok: true } }
                }, null, 2);
                if (isPre) {
                    editorInput.textContent = validEdited;
                } else {
                    editorInput.value = validEdited;
                }
                editorInput.dispatchEvent(new Event('input', { bubbles: true }));

                const applyReEnabled = !applyBtn.disabled;
                applyBtn.click();

                const returnedToViewer = !viewerPane.classList.contains('hidden')
                    && editorPane.classList.contains('hidden');
                const dirtyDetected = isWorkspaceDirty()
                    && !document.getElementById('workspace-dirty-badge').classList.contains('hidden');
                const rootUpdated = getWorkspaceRootText().includes('Edited Workspace Stub');

                closeDocumentWorkspace(true);

                return {
                    openedInViewer,
                    templateKeysMatch,
                    hasProtocolTemplates,
                    templateSwitched,
                    inSectionEditor,
                    lintVisible,
                    bannerVisible,
                    applyDisabled,
                    forceVisible,
                    applyReEnabled,
                    returnedToViewer,
                    dirtyDetected,
                    rootUpdated,
                    closedCleanly: !isWorkspaceOpen()
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("openedInViewer")).isEqualTo(true);
        assertThat(result.get("templateKeysMatch")).isEqualTo(true);
        assertThat(result.get("hasProtocolTemplates")).isEqualTo(true);
        assertThat(result.get("templateSwitched")).isEqualTo(true);
        assertThat(result.get("inSectionEditor")).isEqualTo(true);
        assertThat(result.get("lintVisible")).isEqualTo(true);
        assertThat(result.get("bannerVisible")).isEqualTo(true);
        assertThat(result.get("applyDisabled")).isEqualTo(true);
        assertThat(result.get("forceVisible")).isEqualTo(true);
        assertThat(result.get("applyReEnabled")).isEqualTo(true);
        assertThat(result.get("returnedToViewer")).isEqualTo(true);
        assertThat(result.get("dirtyDetected")).isEqualTo(true);
        assertThat(result.get("rootUpdated")).isEqualTo(true);
        assertThat(result.get("closedCleanly")).isEqualTo(true);
    }

    @Test
    @DisplayName(
            "Snapshot Workspace: Pre-populates Document Workspace with decoded Base64 JSON and matchesJsonPath patterns")
    void testSnapshotStubWorkspacePrePopulation() {
        String script =
                """
            async () => {
                const { buildSnapshotStubMapping } = await import('./modules/journal.js');
                const { openStubEditor } = await import('./modules/lifecycle.js');
                const { closeDocumentWorkspace, getWorkspaceRootText } = await import('./modules/document-workspace.js');

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
                const equalJsonStubData = buildSnapshotStubMapping(journalReq, { useEqualToJson: true });

                openStubEditor(stubData, false, 'Snapshot Stub from Journal');

                const modal = document.getElementById('modal-document-workspace');
                const titleEl = document.getElementById('workspace-title');
                const viewerPane = document.getElementById('workspace-viewer-pane');
                const tplGroup = document.getElementById('workspace-template-group');

                const isModalVisible = !modal.classList.contains('hidden');
                const modalTitle = titleEl.textContent;
                const templateHiddenForExisting = tplGroup.classList.contains('hidden');
                const rootText = getWorkspaceRootText();
                const parsedRoot = JSON.parse(rootText);
                const viewerText = viewerPane.textContent || '';
                const viewerContainsNestedBlock = viewerPane.querySelectorAll('.nested-payload-block').length >= 1;

                closeDocumentWorkspace(true);

                return {
                    isModalVisible,
                    modalTitle,
                    templateHiddenForExisting,
                    rootContainsScore750: rootText.includes('750'),
                    rootContainsJsonPath: rootText.includes('$.FML_ARCHIVE_JSON.score'),
                    rootContainsNoB64Req: !rootText.includes('eyJzY29yZSI6NzUwfQ=='),
                    responseContainsLimit: parsedRoot.response.body.includes('"limit": 50000'),
                    responseContainsNoB64: !parsedRoot.response.body.includes('eyJsaW1pdCI6NTAwMDB9'),
                    viewerContainsNestedBlock,
                    viewerContainsLimit: viewerText.includes('50000'),
                    rawJsonPathMatches: Array.isArray(parsedRoot.request.bodyPatterns)
                        && parsedRoot.request.bodyPatterns.some(p => p.matchesJsonPath?.expression === '$.FML_ARCHIVE_JSON.score'),
                    equalToJsonHasDecodedScore: equalJsonStubData.request.bodyPatterns[0]?.equalToJson?.FML_ARCHIVE_JSON?.score === 750
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("isModalVisible")).isEqualTo(true);
        assertThat(result.get("modalTitle")).isEqualTo("Snapshot Stub from Journal");
        assertThat(result.get("templateHiddenForExisting")).isEqualTo(true);
        assertThat(result.get("rootContainsScore750")).isEqualTo(true);
        assertThat(result.get("rootContainsJsonPath")).isEqualTo(true);
        assertThat(result.get("rootContainsNoB64Req")).isEqualTo(true);
        assertThat(result.get("responseContainsLimit")).isEqualTo(true);
        assertThat(result.get("responseContainsNoB64")).isEqualTo(true);
        assertThat(result.get("viewerContainsNestedBlock")).isEqualTo(true);
        assertThat(result.get("viewerContainsLimit")).isEqualTo(true);
        assertThat(result.get("rawJsonPathMatches")).isEqualTo(true);
        assertThat(result.get("equalToJsonHasDecodedScore")).isEqualTo(true);
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
                    },
                    {
                        id: "disabled-1",
                        name: "Get Single Order",
                        priority: 100,
                        request: { method: "GET", url: "/api/v1/orders/123" },
                        response: { status: 200 }
                    }
                ];
                state.selectedProject = null;
                state.statusFilter = "all";

                const allVisible = getVisibleCatalogStubs("");
                const bulkVisible = getCurrentlyVisibleStubs();

                // Search filtering check for 'orders' across visible catalog and bulk helpers
                const searchBox = document.getElementById("search-box");
                if (searchBox) searchBox.value = "orders";
                const ordersVisible = getVisibleCatalogStubs("orders");
                const ordersBulk = getCurrentlyVisibleStubs();
                const ordersCounts = getCatalogCounts("api-orders");
                if (searchBox) searchBox.value = "";

                // Status filter checks
                state.statusFilter = "active";
                const activeOnly = getVisibleCatalogStubs("");

                state.statusFilter = "disabled";
                const disabledOnly = getVisibleCatalogStubs("");

                // Query filter checks with boolean AND
                state.statusFilter = "all";
                const andQuery = getVisibleCatalogStubs("GET AND /orders");

                const foundActive = findCatalogStubById("active-1");
                const foundDisabled = findCatalogStubById("disabled-1");

                // Reset state
                state.selectedProject = null;
                state.statusFilter = "all";
                state.currentStubs = [];

                return {
                    allVisibleCount: allVisible.length,
                    bulkVisibleCount: bulkVisible.length,
                    ordersVisibleCount: ordersVisible.length,
                    ordersBulkCount: ordersBulk.length,
                    ordersCountsAll: ordersCounts.all,
                    ordersCountsActive: ordersCounts.active,
                    ordersCountsDisabled: ordersCounts.disabled,
                    activeOnlyCount: activeOnly.length,
                    disabledOnlyCount: disabledOnly.length,
                    andQueryCount: andQuery.length,
                    foundActive: foundActive !== null && foundActive.id === "active-1",
                    foundDisabled: foundDisabled !== null && foundDisabled.id === "disabled-1"
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("allVisibleCount")).isEqualTo(3); // 2 active + 1 disabled
        assertThat(result.get("bulkVisibleCount")).isEqualTo(3);
        assertThat(result.get("ordersVisibleCount")).isEqualTo(2); // active-1 and disabled-1
        assertThat(result.get("ordersBulkCount")).isEqualTo(2);
        assertThat(result.get("ordersCountsAll")).isEqualTo(2);
        assertThat(result.get("ordersCountsActive")).isEqualTo(1);
        assertThat(result.get("ordersCountsDisabled")).isEqualTo(1);
        assertThat(result.get("activeOnlyCount")).isEqualTo(2);
        assertThat(result.get("disabledOnlyCount")).isEqualTo(1);
        assertThat(result.get("andQueryCount")).isEqualTo(2); // active-1 and disabled-1
        assertThat(result.get("foundActive")).isEqualTo(true);
        assertThat(result.get("foundDisabled")).isEqualTo(true);
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
    @DisplayName("Nested Payload AST: Deep multi-layer payload discovery across JSON, XML, CDATA, and Base64")
    void testDeepPayloadUnnestingDiscovery() {
        String script =
                """
            async () => {
                const { discoverEmbeddedPayloads } = await import('./modules/polyglot-ast.js');
                const { utf8ToBase64 } = await import('./modules/charset-codec.js');

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
                    firstGrandchildParentPath: (discovered[0] && discovered[0].children && discovered[0].children[0]) ? discovered[0].children[0].parentPath : null,
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
        assertThat(result.get("firstGrandchildPath")).isEqualTo("$.innerXml");
        assertThat(result.get("firstGrandchildParentPath")).isEqualTo("$.payload");
        assertThat(result.get("firstGrandchildFormat")).isEqualTo("xml");
        assertThat(result.get("secondPath")).isEqualTo("$.b64Report");
        assertThat(result.get("secondFormat")).isEqualTo("xml");
        assertThat(result.get("secondEncoding")).isEqualTo("BASE64_UTF8");
    }

    @Test
    @DisplayName("Payload Unnesting: Discovers and normalizes Base64-encoded Java Map toString and pseudo-JSON KV")
    void testPseudoJsonAndBase64KvDiscovery() {
        String script =
                """
            async () => {
                const { discoverEmbeddedPayloads } = await import('./modules/polyglot-ast.js');
                const { parsePseudoJson } = await import('./modules/pseudo-json.js');

                const samplePayload = JSON.stringify({
                    id: "tx-123",
                    b5: "e2FiYz17YWJjPXtpZD0xMTExMTExMTExMTExfX19",
                    b11: "e21lc3NhZ2VzPW51bGw7IGRldmlhdGlvbnM9W107IHBlcmlvZHM9W119"
                });

                const discovered = discoverEmbeddedPayloads(samplePayload);
                const parsedFirst = parsePseudoJson(discovered[0].unescapedValue);

                return {
                    count: discovered.length,
                    firstPath: discovered[0] ? discovered[0].path : null,
                    firstFormat: discovered[0] ? discovered[0].format : null,
                    firstUnescaped: discovered[0] ? discovered[0].unescapedValue : null,
                    firstParsedNestedId: parsedFirst?.abc?.abc?.id ?? null,
                    secondPath: discovered[1] ? discovered[1].path : null,
                    secondFormat: discovered[1] ? discovered[1].format : null,
                    secondUnescaped: discovered[1] ? discovered[1].unescapedValue : null
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("count")).isEqualTo(2);
        assertThat(result.get("firstPath")).isEqualTo("$.b5");
        assertThat(result.get("firstFormat")).isEqualTo("kv");
        assertThat(result.get("firstUnescaped")).isEqualTo("{abc={abc={id=1111111111111}}}");
        assertThat(((Number) result.get("firstParsedNestedId")).longValue()).isEqualTo(1111111111111L);
        assertThat(result.get("secondPath")).isEqualTo("$.b11");
        assertThat(result.get("secondFormat")).isEqualTo("kv");
        assertThat(result.get("secondUnescaped")).isEqualTo("{messages=null; deviations=[]; periods=[]}");
    }

    @Test
    @DisplayName(
            "Nested Payload AST: Cascading round-trip re-escaping fidelity from leaf XML through middle JSON to root JSON")
    void testRoundTripReEscapingFidelity() {
        String script =
                """
            async () => {
                const { discoverEmbeddedPayloads, cascadeLensStackToRoot } = await import('./modules/polyglot-ast.js');

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
                const newRootJson = cascadeLensStackToRoot(stack, editedLeafXml, { wireStyleOverride: 'multiline' });

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
            "Nested Payload AST: Surgical in-place substitution preserves intermediate container whitespace and byte fidelity across 5 nested layers")
    void testSurgicalInPlaceCascadePreservesIntermediateWhitespaceAndEnvelopes() {
        String script =
                """
            async () => {
                const { discoverEmbeddedPayloads, cascadeLensStackToRoot } = await import('./modules/polyglot-ast.js');

                // Build a 5-layer multi-format structure cleanly with JSON.stringify
                const innermost = JSON.stringify({ auditor: "ISO20022-GATEWAY", passed: true });
                const cdataJson = JSON.stringify({ account: "ACC-112233", auditTrail: innermost });
                const xml = "<swiftMessage><header><sender>SWIFT</sender></header><body><![CDATA[" + cdataJson + "]]></body></swiftMessage>";
                const gatewayJson = JSON.stringify({ routing: { network: "SWIFT_NET" }, messagePayload: xml });
                const bodyJson = JSON.stringify({ transferId: "SWIFT-2026-X891", executionTrace: { gatewayEnvelope: gatewayJson } }, null, 2);
                const initialStub = {
                    id: "stub-multiformat-deep",
                    name: "B2B SWIFT Transfer",
                    response: {
                        status: 200,
                        body: bodyJson
                    },
                    _disabled: false,
                    _dynamic: false
                };

                const rawStubJson = JSON.stringify(initialStub, null, 2);
                const discovered = discoverEmbeddedPayloads(rawStubJson);

                function buildAllFrames(rawText, disc, current = [{ text: rawText, descriptor: null, title: 'Root', format: 'json' }]) {
                    const leaves = [];
                    for (const node of disc) {
                        const frames = [...current, { text: node.unescapedValue, descriptor: node, title: node.path, format: node.format }];
                        if (node.children && node.children.length > 0) {
                            leaves.push(...buildAllFrames(node.unescapedValue, node.children, frames));
                        } else {
                            leaves.push({ node, frames });
                        }
                    }
                    return leaves;
                }

                const leaves = buildAllFrames(rawStubJson, discovered);
                const auditLeaf = leaves.find(l => l.node.key === 'auditTrail');

                // Perform surgical edit on innermost leaf: GATEWAY -> GATEWAYYY (+2 characters)
                const editedInnermost = auditLeaf.node.unescapedValue.replace("ISO20022-GATEWAY", "ISO20022-GATEWAYYY");
                const newRootJson = cascadeLensStackToRoot(auditLeaf.frames, editedInnermost);

                // Parse each level to verify complete structural fidelity
                const pRoot = JSON.parse(newRootJson);
                const pBody = JSON.parse(pRoot.response.body);
                const pEnv = JSON.parse(pBody.executionTrace.gatewayEnvelope);
                const cdataStart = pEnv.messagePayload.indexOf('<![CDATA[');
                const cdataEnd = pEnv.messagePayload.indexOf(']]>');
                const cdataContent = pEnv.messagePayload.substring(cdataStart + 9, cdataEnd);
                const pCdata = JSON.parse(cdataContent);
                const pAudit = JSON.parse(pCdata.auditTrail);

                return {
                    lengthDiff: newRootJson.length - rawStubJson.length,
                    hasGatewayyy: newRootJson.includes("ISO20022-GATEWAYYY"),
                    hasOldGateway: newRootJson.includes('ISO20022-GATEWAY\\"'),
                    preservedDisabled: pRoot._disabled === false,
                    preservedDynamic: pRoot._dynamic === false,
                    auditor: pAudit.auditor,
                    passed: pAudit.passed
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("lengthDiff")).isEqualTo(2);
        assertThat(result.get("hasGatewayyy")).isEqualTo(true);
        assertThat(result.get("hasOldGateway")).isEqualTo(false);
        assertThat(result.get("preservedDisabled")).isEqualTo(true);
        assertThat(result.get("preservedDynamic")).isEqualTo(true);
        assertThat(result.get("auditor")).isEqualTo("ISO20022-GATEWAYYY");
        assertThat(result.get("passed")).isEqualTo(true);
    }

    @Test
    @DisplayName(
            "Nested Payload AST: Syntax validation blocks invalid payload while forceInvalid allows negative testing")
    void testValidationAndForceApplyOverride() {
        String script =
                """
            async () => {
                const { validatePayload } = await import('./modules/highlighter.js');
                const { applyNestedPayloadEdit } = await import('./modules/polyglot-ast.js');

                const validJsonVal = validatePayload('{"valid": true}', 'json');
                const invalidJsonVal = validatePayload('{"bad": }', 'json');

                const validXmlVal = validatePayload('<root><child>OK</child></root>', 'xml');
                const invalidXmlVal = validatePayload('<root><mismatch></root>', 'xml');

                const parentJson = '{"embedded": "{\\\\"a\\\\": 1}"}';
                const nodeDescriptor = {
                    path: '$.embedded',
                    format: 'json',
                    encoding: 'JSON_STRING',
                    wireStyle: 'compact'
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

    @Test
    @DisplayName("demoBackend: Live-captured DEMO_SNAPSHOT seeding, stub lifecycle, and state machine matching")
    void testDemoBackendShowcaseAndMatching() {
        String script =
                """
            async () => {
                const { demoBackend } = await import('./modules/demo-backend.js');
                demoBackend.reset();
                try {
                    // 1. Initial Seeding verification from DEMO_SNAPSHOT
                    const initialStubsCount = demoBackend.stubs.filter(s => (s.priority ?? 5) < 100).length;
                    const initialDisabledCount = demoBackend.stubs.filter(s => (s.priority ?? 5) >= 100).length;
                    const initialRequestsCount = demoBackend.requests.length;
                    const initialScenarios = demoBackend.scenarios;

                    // 2. HTTP Request Simulation (Matched with Scenario progression)
                    // OrderFulfillment starts in 'SHIPPED' state in DEMO_SNAPSHOT
                    const deliverOrderResp = await demoBackend.handleRequest('/api/v1/orders/ORD-9942/status', {
                        method: 'GET'
                    });
                    const deliverOrderBody = await deliverOrderResp.json();
                    const scenarioAfterDeliver = demoBackend.scenarios.find(s => s.name === 'OrderFulfillment').state;

                    // 3. HTTP Request Simulation (Create Order with nested polyglot audit report)
                    const orderResp = await demoBackend.handleRequest('/api/v1/orders', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ orderId: 'ORD-9942', totalAmount: 1250 })
                    });
                    const orderBody = await orderResp.json();

                    // 4. Unmatched Request Simulation
                    const unmatchedResp = await demoBackend.handleRequest('/api/v1/non-existent-route', {
                        method: 'GET'
                    });

                    // 5. Stub Lifecycle Toggle (Native Priority Tier Parking)
                    const orderLookupResp = await demoBackend.handleRequest('/__admin/mappings/10000000-0000-4000-8000-000000000002', {
                        method: 'GET'
                    });
                    const orderStub = await orderLookupResp.json();
                    const toggleResp = await demoBackend.handleRequest('/__admin/mappings/10000000-0000-4000-8000-000000000002', {
                        method: 'PUT',
                        body: JSON.stringify(Object.assign({}, orderStub, {
                            priority: 100,
                            metadata: Object.assign({}, orderStub.metadata || {}, {
                                previousPriority: orderStub.priority ?? 5
                            })
                        }))
                    });
                    const toggleResult = await toggleResp.json();

                    // 6. Casual XATMI simulation
                    const casualResp = await demoBackend.handleRequest('/__admin/casual/call', {
                        method: 'POST',
                        body: JSON.stringify({
                            service: 'echoService',
                            bufferType: 'JSON',
                            payload: '{"account": "ACC-101"}'
                        })
                    });
                    const casualResult = await casualResp.json();

                    return {
                        initialStubsCount,
                        initialDisabledCount,
                        initialRequestsCount,
                        hasOrderFulfillment: initialScenarios.some(s => s.name === 'OrderFulfillment'),
                        deliverOrderStatus: deliverOrderResp.status,
                        deliverOrderDelivered: deliverOrderBody.status === 'DELIVERED',
                        scenarioAfterDeliver,
                        orderStatus: orderResp.status,
                        orderCreated: orderBody.status === 'CREATED',
                        orderHasEmbeddedAudit: typeof orderBody.complianceAudit === 'string' && orderBody.complianceAudit.includes('<auditReport'),
                        unmatchedStatus: unmatchedResp.status,
                        toggleResultDisabled: (toggleResult.priority ?? 5) >= 100,
                        stubsAfterToggle: demoBackend.stubs.filter(s => (s.priority ?? 5) < 100).length,
                        disabledAfterToggle: demoBackend.stubs.filter(s => (s.priority ?? 5) >= 100).length,
                        casualStatus: casualResult.status,
                        casualHasEcho: casualResult.payload.includes('ACC-101'),
                        hasCasualStub: demoBackend.stubs.some(s => s.id === '10000000-0000-4000-8000-000000000008'),
                        hasCasualJournalEntry: demoBackend.requests.some(r => r.request.url.includes('/__casual/services/echoService')),
                        hasAsyncKafkaStub: demoBackend.stubs.some(s => s.id === '10000000-0000-4000-8000-000000000009'),
                        hasAsyncAmqpStub: demoBackend.stubs.some(s => s.id === '10000000-0000-4000-8000-000000000010'),
                        hasAsyncJournalEntry: demoBackend.requests.some(r => r.request.url.includes('/__async/in-memory/orders.notifications')),
                        hasKafkaDestination: demoBackend.asyncDestinations.some(d => d.id === 'kafka:orders.events'),
                        hasCasualDestination: demoBackend.asyncDestinations.some(d => d.id === 'casual:billing.q'),
                        asyncStatusUp: (await (await demoBackend.handleRequest('/__admin/async/status', { method: 'GET' })).json()).status === 'UP'
                    };
                } finally {
                    demoBackend.reset();
                }
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(((Number) result.get("initialStubsCount")).intValue()).isGreaterThanOrEqualTo(12);
        assertThat(((Number) result.get("initialDisabledCount")).intValue()).isEqualTo(1);
        assertThat(((Number) result.get("initialRequestsCount")).intValue()).isGreaterThanOrEqualTo(11);
        assertThat(result.get("hasOrderFulfillment")).isEqualTo(true);
        assertThat(result.get("hasCasualStub")).isEqualTo(true);
        assertThat(result.get("hasCasualJournalEntry")).isEqualTo(true);
        assertThat(result.get("hasAsyncKafkaStub")).isEqualTo(true);
        assertThat(result.get("hasAsyncAmqpStub")).isEqualTo(true);
        assertThat(result.get("hasAsyncJournalEntry")).isEqualTo(true);
        assertThat(result.get("hasKafkaDestination")).isEqualTo(true);
        assertThat(result.get("hasCasualDestination")).isEqualTo(true);
        assertThat(result.get("asyncStatusUp")).isEqualTo(true);
        assertThat(((Number) result.get("deliverOrderStatus")).intValue()).isEqualTo(200);
        assertThat(result.get("deliverOrderDelivered")).isEqualTo(true);
        assertThat(result.get("scenarioAfterDeliver")).isEqualTo("DELIVERED");
        assertThat(((Number) result.get("orderStatus")).intValue()).isEqualTo(201);
        assertThat(result.get("orderCreated")).isEqualTo(true);
        assertThat(result.get("orderHasEmbeddedAudit")).isEqualTo(true);
        assertThat(((Number) result.get("unmatchedStatus")).intValue()).isEqualTo(404);
        assertThat(result.get("toggleResultDisabled")).isEqualTo(true);
        assertThat(((Number) result.get("disabledAfterToggle")).intValue())
                .isEqualTo(((Number) result.get("initialDisabledCount")).intValue() + 1);
        assertThat(((Number) result.get("casualStatus")).intValue()).isEqualTo(0);
        assertThat(result.get("casualHasEcho")).isEqualTo(true);
    }

    @Test
    @DisplayName("stub-catalog: Encapsulates active/disabled stubs, counts, queries, selection, and state delegation")
    void testStubCatalogDomainModule() {
        String script =
                """
            async () => {
                const { StubCatalog, stubCatalog, getStubProject, extractBaseSegment } = await import('./modules/stub-catalog.js');
                const { state } = await import('./modules/state.js');
                const catalog = new StubCatalog();

                const active = [
                    { id: '1', request: { method: 'GET', url: '/api/v1/orders' }, metadata: { project: 'orders' } },
                    { id: '2', request: { method: 'POST', url: '/api/v1/orders' }, metadata: { project: 'orders' } },
                    { id: '3', request: { method: 'GET', url: '/payments/charge' } }
                ];
                const disabled = [
                    { id: '4', request: { method: 'DELETE', url: '/api/v1/orders/1' }, metadata: { project: 'orders' } }
                ];

                catalog.setRawCollections({ active, disabled });

                const totalAll = catalog.getAll().length;
                const counts = catalog.getCounts();
                const ordersCounts = catalog.getCounts('orders');
                const queryOrders = catalog.query({ search: 'orders', status: 'all' });
                const queryActiveOnly = catalog.query({ status: 'active' });
                const projects = catalog.getProjects();

                catalog.setSelectedStubId('2');
                const selectedStubId = catalog.getSelectedStubId();
                const selectedStub = catalog.getSelectedStub();

                catalog.select('1');
                catalog.select('2');
                const selectedBefore = catalog.getSelectedCount();
                catalog.deselect('1');
                const selectedAfter = catalog.getSelectedCount();
                const isSelected2 = catalog.isSelected('2');

                // Verify state.currentStubs and state.selectedStubId delegate to shared stubCatalog
                state.currentStubs = active;
                state.selectedStubId = '3';
                const stateCatalogCount = stubCatalog.getAll().length;
                const stateDelegatedSelectedId = stubCatalog.getSelectedStubId();
                const stateDelegatedSelectedStub = stubCatalog.getSelectedStub();
                state.currentStubs = [];
                state.selectedStubId = null;

                return {
                    totalAll,
                    countsAll: counts.all,
                    countsActive: counts.active,
                    countsDisabled: counts.disabled,
                    hasDynamicKey: Object.prototype.hasOwnProperty.call(counts, 'dynamic'),
                    ordersTotal: ordersCounts.all,
                    queryOrdersLen: queryOrders.length,
                    queryActiveOnlyLen: queryActiveOnly.length,
                    projectNames: projects.map(p => p.name),
                    selectedStubId,
                    selectedStubMatchesId2: selectedStub !== null && selectedStub.id === '2',
                    selectedBefore,
                    selectedAfter,
                    isSelected2,
                    stateCatalogCount,
                    stateDelegatedSelectedId,
                    stateDelegatedSelectedMatches3: stateDelegatedSelectedStub !== null && stateDelegatedSelectedStub.id === '3',
                    inferredProject: getStubProject({ request: { url: '/payments/charge' } }),
                    baseSegment: extractBaseSegment('/api/v2/customers/profile')
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(((Number) result.get("totalAll")).intValue()).isEqualTo(4);
        assertThat(((Number) result.get("countsAll")).intValue()).isEqualTo(4);
        assertThat(((Number) result.get("countsActive")).intValue()).isEqualTo(3);
        assertThat(((Number) result.get("countsDisabled")).intValue()).isEqualTo(1);
        assertThat(result.get("hasDynamicKey")).isEqualTo(false);
        assertThat(((Number) result.get("ordersTotal")).intValue()).isEqualTo(3);
        assertThat(((Number) result.get("queryOrdersLen")).intValue()).isEqualTo(3);
        assertThat(((Number) result.get("queryActiveOnlyLen")).intValue()).isEqualTo(3);
        assertThat(result.get("selectedStubId")).isEqualTo("2");
        assertThat(result.get("selectedStubMatchesId2")).isEqualTo(true);
        assertThat(((Number) result.get("selectedBefore")).intValue()).isEqualTo(2);
        assertThat(((Number) result.get("selectedAfter")).intValue()).isEqualTo(1);
        assertThat(result.get("isSelected2")).isEqualTo(true);
        assertThat(((Number) result.get("stateCatalogCount")).intValue()).isEqualTo(3);
        assertThat(result.get("stateDelegatedSelectedId")).isEqualTo("3");
        assertThat(result.get("stateDelegatedSelectedMatches3")).isEqualTo(true);
        assertThat(result.get("inferredProject")).isEqualTo("api-payments");
        assertThat(result.get("baseSegment")).isEqualTo("customers");
    }

    @Test
    @DisplayName("api & demoBackend: Consolidated /__admin/lifecycle/snapshots and snapshot-template routes")
    void testConsolidatedLifecycleApiAndDemoBackendRoutes() {
        String script =
                """
            async () => {
                const { snapshotProjectRecordings, snapshotFilteredRecordings, getSnapshotStubTemplate } = await import('./modules/api.js');
                const { demoBackend } = await import('./modules/demo-backend.js');

                demoBackend.reset();
                demoBackend.setDemoMode(true);
                try {
                    const reqId = demoBackend.requests[0].id;
                    const projSnap = await snapshotProjectRecordings('Order Service');
                    const filteredSnap = await snapshotFilteredRecordings([reqId], 'CustomerDomain', true);
                    const template = await getSnapshotStubTemplate(reqId, 'OrderDomain');

                    return {
                        projSnapTotal: projSnap.totalRecorded,
                        projSnapStatus: projSnap.status,
                        filteredSnapTotal: filteredSnap.totalRecorded,
                        filteredSnapStatus: filteredSnap.status,
                        templateProject: template && template.metadata ? template.metadata.project : null,
                        templateSource: template && template.metadata ? template.metadata.source : null
                    };
                } finally {
                    demoBackend.setDemoMode(false);
                    demoBackend.reset();
                }
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(((Number) result.get("projSnapTotal")).intValue()).isEqualTo(1);
        assertThat(result.get("projSnapStatus")).isEqualTo("disabled");
        assertThat(((Number) result.get("filteredSnapTotal")).intValue()).isEqualTo(1);
        assertThat(result.get("filteredSnapStatus")).isEqualTo("active");
        assertThat(result.get("templateProject")).isEqualTo("OrderDomain");
        assertThat(result.get("templateSource")).isEqualTo("recorded-proxy");
    }

    @Test
    @DisplayName("Nested Payload AST: Deep 4-level unnesting and round-tripping (cdata-json-in-xml-in-json-in-json)")
    void testDeepMultiFormatUnnesting4Levels() {
        String script =
                """
            async () => {
                const { discoverEmbeddedPayloads, cascadeLensStackToRoot } = await import('./modules/polyglot-ast.js');

                // 4-level deep payload: JSON -> JSON -> XML -> CDATA -> JSON
                const innerAuditTrailJson = JSON.stringify({ auditor: "ISO20022-GATEWAY", passed: true });
                const cdataJson = JSON.stringify({ account: "ACC-112233", auditTrail: innerAuditTrailJson });
                const xmlDoc = `<swiftMessage xmlns="http://banking.corp/swift"><header><sender>SWIFT-EXT</sender></header><body><![CDATA[${cdataJson}]]></body></swiftMessage>`;
                const middleGatewayJson = JSON.stringify({ routing: { network: "SWIFT_NET" }, messagePayload: xmlDoc });
                const rootJson = JSON.stringify({ transferId: "SWIFT-2026-X891", gatewayEnvelope: middleGatewayJson });

                const discovered = discoverEmbeddedPayloads(rootJson);

                // Level 1: gatewayEnvelope (JSON_STRING)
                const l1 = discovered[0];
                // Level 2: messagePayload (XML in JSON)
                const l2 = l1.children[0];
                // Level 3: CDATA body (XML_CDATA)
                const l3 = l2.children[0];
                // Level 4: auditTrail (JSON_STRING in CDATA JSON)
                const l4 = l3.children[0];

                // Edit deepest leaf node: change passed to false
                const editedL4 = innerAuditTrailJson.replace('true', 'false');

                // Round-trip cascade edit back to root
                const stack = [
                    { text: rootJson, descriptor: null },
                    { text: l1.unescapedValue, descriptor: l1 },
                    { text: l2.unescapedValue, descriptor: l2 },
                    { text: l3.unescapedValue, descriptor: l3 },
                    { text: l4.unescapedValue, descriptor: l4 }
                ];

                const reEscapedRoot = cascadeLensStackToRoot(stack, editedL4);
                const parsedRoot = JSON.parse(reEscapedRoot);
                const parsedGateway = JSON.parse(parsedRoot.gatewayEnvelope);

                return {
                    l1Format: l1 ? l1.format : null,
                    l2Format: l2 ? l2.format : null,
                    l3Format: l3 ? l3.format : null,
                    l3Encoding: l3 ? l3.encoding : null,
                    l4Format: l4 ? l4.format : null,
                    hasReEscapedAudit: parsedGateway.messagePayload.includes('false'),
                    validJsonRoot: typeof parsedRoot === 'object'
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("l1Format")).isEqualTo("json");
        assertThat(result.get("l2Format")).isEqualTo("xml");
        assertThat(result.get("l3Format")).isEqualTo("json");
        assertThat(result.get("l3Encoding")).isEqualTo("XML_CDATA");
        assertThat(result.get("l4Format")).isEqualTo("json");
        assertThat(result.get("hasReEscapedAudit")).isEqualTo(true);
        assertThat(result.get("validJsonRoot")).isEqualTo(true);
    }

    @Test
    @DisplayName(
            "Polyglot Tree Viewer: Renders foldable nested cards down AST hierarchy, hides edit buttons in read-only views, and wires section actions")
    void testPolyglotViewerReadOnlyAndEditableTreeRendering() {
        String script =
                """
            async () => {
                const { renderPolyglotTreeHtml, mountPolyglotViewer } = await import('./modules/polyglot-viewer.js');

                const innerXml = '<swiftMessage xmlns="http://banking.corp"><id>SW-99</id></swiftMessage>';
                const gatewayJson = JSON.stringify({ routing: "SWIFT_NET", messagePayload: innerXml });
                const rootBodyJson = JSON.stringify({ transferId: "TX-1", gatewayEnvelope: gatewayJson });
                const stubJson = JSON.stringify({ response: { body: rootBodyJson } }, null, 2);

                // 1. Read-only render: copy + open-workspace buttons only, NEVER disabled edit buttons
                const readOnlyHtml = renderPolyglotTreeHtml(stubJson, { mode: 'rich', editable: false });
                const roHost = document.createElement('div');
                let workspaceOpenedPath = null;
                let workspaceChainKeys = null;
                document.body.appendChild(roHost);
                const roCtrl = mountPolyglotViewer(roHost, {
                    text: stubJson,
                    mode: 'rich',
                    editable: false,
                    onOpenWorkspace: (chain) => {
                        workspaceOpenedPath = chain[chain.length - 1]?.path || null;
                        workspaceChainKeys = chain.map(n => n.key);
                    }
                });

                const roBlocks = roHost.querySelectorAll('.nested-payload-block').length;
                const roDisabledEditBtns = roHost.querySelectorAll('button[disabled]').length;
                const roEditBtns = roHost.querySelectorAll('.btn-workspace-edit-section').length;
                const roOpenBtns = roHost.querySelectorAll('.btn-nested-open-workspace').length;
                const roCopyBtns = roHost.querySelectorAll('.btn-nested-copy').length;

                // Click deepest Open in Workspace button
                const openBtns = roHost.querySelectorAll('.btn-nested-open-workspace');
                openBtns[openBtns.length - 1].click();

                // Click collapse toggle on first nested block
                const firstBlock = roHost.querySelector('.nested-payload-block');
                firstBlock.querySelector('.btn-nested-toggle').click();
                const collapsedAfterClick = firstBlock.classList.contains('collapsed');

                roCtrl.destroy();
                roHost.remove();

                // 2. Editable render: emits .btn-workspace-edit-section buttons and invokes onEditSection
                const editHost = document.createElement('div');
                let editedPath = null;
                let editedChainKeys = null;
                document.body.appendChild(editHost);
                const editCtrl = mountPolyglotViewer(editHost, {
                    text: stubJson,
                    mode: 'rich',
                    editable: true,
                    onEditSection: (chain) => {
                        editedPath = chain[chain.length - 1]?.path || null;
                        editedChainKeys = chain.map(n => n.key);
                    }
                });
                const editSectionBtns = editHost.querySelectorAll('.btn-workspace-edit-section').length;
                const editOpenBtns = editHost.querySelectorAll('.btn-nested-open-workspace').length;
                const editBtns = editHost.querySelectorAll('.btn-workspace-edit-section');
                editBtns[editBtns.length - 1].click();

                editCtrl.destroy();
                editHost.remove();

                return {
                    readOnlyHasTree: readOnlyHtml.includes('class="polyglot-tree'),
                    readOnlyContainsUnnestedXml: readOnlyHtml.includes('swiftMessage'),
                    roBlocks,
                    roDisabledEditBtns,
                    roEditBtns,
                    roOpenBtns,
                    roCopyBtns,
                    workspaceOpenedPath,
                    workspaceChainKeys,
                    collapsedAfterClick,
                    editSectionBtns,
                    editOpenBtns,
                    editedPath,
                    editedChainKeys
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("readOnlyHasTree")).isEqualTo(true);
        assertThat(result.get("readOnlyContainsUnnestedXml")).isEqualTo(true);
        assertThat(result.get("roBlocks")).isEqualTo(3);
        assertThat(result.get("roDisabledEditBtns")).isEqualTo(0);
        assertThat(result.get("roEditBtns")).isEqualTo(0);
        assertThat(result.get("roOpenBtns")).isEqualTo(3);
        assertThat(result.get("roCopyBtns")).isEqualTo(3);
        assertThat(result.get("workspaceOpenedPath")).isEqualTo("$.messagePayload");
        assertThat(result.get("workspaceChainKeys"))
                .isEqualTo(java.util.List.of("body", "gatewayEnvelope", "messagePayload"));
        assertThat(result.get("collapsedAfterClick")).isEqualTo(true);
        assertThat(result.get("editSectionBtns")).isEqualTo(3);
        assertThat(result.get("editOpenBtns")).isEqualTo(0);
        assertThat(result.get("editedPath")).isEqualTo("$.messagePayload");
        assertThat(result.get("editedChainKeys"))
                .isEqualTo(java.util.List.of("body", "gatewayEnvelope", "messagePayload"));
    }

    @Test
    @DisplayName("Casual Buffer Descriptor: Formats buffer type, syntax format, and deduplicates identical labels")
    void testCasualBufferDescriptorFormatting() {
        String script =
                """
            async () => {
                const { formatCasualBufferDescriptor } = await import('./modules/polyglot-ast.js');

                return {
                    jsonJson: formatCasualBufferDescriptor('JSON', 'JSON', '0.4'),
                    fieldedJson: formatCasualBufferDescriptor('FIELDED', 'JSON', '1.2'),
                    fmlJson: formatCasualBufferDescriptor('FML', 'JSON', '0.8'),
                    xoctetXml: formatCasualBufferDescriptor('X_OCTET', 'XML', '2.5'),
                    cstringText: formatCasualBufferDescriptor('CSTRING', 'TEXT', '0.1'),
                    noSize: formatCasualBufferDescriptor('FIELDED', 'JSON')
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("jsonJson")).isEqualTo("CASUAL JSON · 0.4 KB");
        assertThat(result.get("fieldedJson")).isEqualTo("FIELDED · JSON · 1.2 KB");
        assertThat(result.get("fmlJson")).isEqualTo("FIELDED · JSON · 0.8 KB");
        assertThat(result.get("xoctetXml")).isEqualTo("X_OCTET · XML · 2.5 KB");
        assertThat(result.get("cstringText")).isEqualTo("CSTRING · TEXT · 0.1 KB");
        assertThat(result.get("noSize")).isEqualTo("FIELDED · JSON");
    }

    @Test
    @DisplayName(
            "Character Encoding: Detects charset from XML prolog and headers, and formats descriptor badges with encoding and charset")
    void testCharsetDetectionAndDescriptorFormatting() {
        String script =
                """
            async () => {
                const { detectCharset } = await import('./modules/charset-codec.js');
                const { formatPayloadDescriptor, formatCasualBufferDescriptor } = await import('./modules/polyglot-ast.js');

                const xmlIso = '<?xml version="1.0" encoding="ISO-8859-1"?><note><to>Åke</to></note>';
                const xmlKoi8 = "<?xml version='1.0' encoding='koi8-r'?><msg>Привет</msg>";
                const plainJson = '{"hello":"world"}';

                return {
                    detectedXmlIso: detectCharset(xmlIso),
                    detectedXmlKoi8: detectCharset(xmlKoi8),
                    detectedHeaderWin1252: detectCharset(plainJson, { 'Content-Type': 'application/json; charset=cp1252' }),
                    detectedCasualHeader: detectCharset(plainJson, { 'x-casual-charset': 'latin1' }),
                    detectedDefaultUtf8: detectCharset(plainJson),

                    stdJsonLabel: formatPayloadDescriptor({ format: 'json', encoding: 'JSON_STRING', charset: 'UTF-8', sizeKb: '0.4' }),
                    isoXmlLabel: formatPayloadDescriptor({ format: 'xml', encoding: 'JSON_STRING', charset: 'ISO-8859-1', sizeKb: '0.2' }),
                    b64JsonLabel: formatPayloadDescriptor({ format: 'json', encoding: 'BASE64_UTF8', charset: 'UTF-8', sizeKb: '0.4' }),
                    b64Koi8XmlLabel: formatPayloadDescriptor({ format: 'xml', encoding: 'BASE64', charset: 'KOI8-R', sizeKb: '0.3' }),
                    cdataIsoXmlLabel: formatPayloadDescriptor({ format: 'xml', encoding: 'XML_CDATA', charset: 'ISO-8859-1', sizeKb: '0.2' }),
                    entityXmlLabel: formatPayloadDescriptor({ format: 'xml', encoding: 'XML_ENTITY_TEXT', charset: 'UTF-8', sizeKb: '0.1' }),

                    casualFieldedIso: formatCasualBufferDescriptor('FIELDED', 'JSON', '0.4', 'ISO-8859-1'),
                    casualCstringIso: formatCasualBufferDescriptor('CSTRING', 'TEXT', '0.1', 'ISO-8859-1'),
                    casualJsonUtf8: formatCasualBufferDescriptor('JSON', 'JSON', '0.4', 'UTF-8')
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("detectedXmlIso")).isEqualTo("ISO-8859-1");
        assertThat(result.get("detectedXmlKoi8")).isEqualTo("KOI8-R");
        assertThat(result.get("detectedHeaderWin1252")).isEqualTo("WINDOWS-1252");
        assertThat(result.get("detectedCasualHeader")).isEqualTo("ISO-8859-1");
        assertThat(result.get("detectedDefaultUtf8")).isEqualTo("UTF-8");

        assertThat(result.get("stdJsonLabel")).isEqualTo("JSON · 0.4 KB");
        assertThat(result.get("isoXmlLabel")).isEqualTo("XML · ISO-8859-1 · 0.2 KB");
        assertThat(result.get("b64JsonLabel")).isEqualTo("BASE64 · JSON · 0.4 KB");
        assertThat(result.get("b64Koi8XmlLabel")).isEqualTo("BASE64 · XML · KOI8-R · 0.3 KB");
        assertThat(result.get("cdataIsoXmlLabel")).isEqualTo("CDATA · XML · ISO-8859-1 · 0.2 KB");
        assertThat(result.get("entityXmlLabel")).isEqualTo("ENTITY · XML · 0.1 KB");

        assertThat(result.get("casualFieldedIso")).isEqualTo("FIELDED · JSON · ISO-8859-1 · 0.4 KB");
        assertThat(result.get("casualCstringIso")).isEqualTo("CSTRING · TEXT · ISO-8859-1 · 0.1 KB");
        assertThat(result.get("casualJsonUtf8")).isEqualTo("CASUAL JSON · 0.4 KB");
    }

    @Test
    @DisplayName(
            "Character Encoding: Discovers non-UTF-8 Base64 payloads (ISO-8859-1, KOI8-R), renders charset pills, and preserves single-byte wire encoding on round-trip")
    void testNonUtf8Base64DiscoveryPillLabelsAndRoundTripFidelity() {
        String script =
                """
            async () => {
                const { discoverEmbeddedPayloads, cascadeLensStackToRoot } = await import('./modules/polyglot-ast.js');
                const { encodeToBase64 } = await import('./modules/charset-codec.js');
                const { renderPolyglotTreeHtml } = await import('./modules/polyglot-viewer.js');

                // 1. Construct raw ISO-8859-1 Base64 (Å = 0xC5, ö = 0xF6 — invalid UTF-8 sequences)
                const isoXmlText = '<?xml version="1.0" encoding="ISO-8859-1"?><customer><name>Åke Söderström</name><status>PENDING</status></customer>';
                const isoB64 = encodeToBase64(isoXmlText, 'ISO-8859-1');

                // 2. Construct raw KOI8-R Base64 (Привет = [0xF0, 0xD2, 0xC9, 0xD7, 0xC5, 0xD4] in KOI8-R)
                const koi8XmlText = '<?xml version="1.0" encoding="KOI8-R"?><msg><text>Привет</text></msg>';
                const koi8B64 = encodeToBase64(koi8XmlText, 'KOI8-R');

                // 3. Construct CDATA XML with ISO-8859-1 prolog
                const outerXmlWithCdata = `<envelope><payload><![CDATA[${isoXmlText}]]></payload></envelope>`;

                const rootJson = JSON.stringify({
                    isoField: isoB64,
                    koi8Field: koi8B64,
                    cdataEnvelope: outerXmlWithCdata
                }, null, 2);

                const discovered = discoverEmbeddedPayloads(rootJson);
                const isoNode = discovered.find(d => d.key === 'isoField');
                const koi8Node = discovered.find(d => d.key === 'koi8Field');
                const cdataOuterNode = discovered.find(d => d.key === 'cdataEnvelope');
                const cdataInnerNode = cdataOuterNode && cdataOuterNode.children ? cdataOuterNode.children[0] : null;

                const renderedHtml = renderPolyglotTreeHtml(rootJson, { mode: 'rich', editable: false });

                // 4. Round-trip edit ISO-8859-1 payload
                const editedIsoXml = isoXmlText.replace('PENDING', 'APPROVED');
                const updatedRootAfterIso = cascadeLensStackToRoot([
                    { text: rootJson, descriptor: null },
                    { text: isoNode.unescapedValue, descriptor: isoNode }
                ], editedIsoXml, { wireStyleOverride: 'compact' });
                const parsedAfterIso = JSON.parse(updatedRootAfterIso);
                const rawBytesAfterIso = Uint8Array.from(atob(parsedAfterIso.isoField), c => c.charCodeAt(0));

                // 5. Round-trip edit KOI8-R payload
                const editedKoi8Xml = koi8XmlText.replace('Привет', 'Привет!');
                const updatedRootAfterKoi8 = cascadeLensStackToRoot([
                    { text: rootJson, descriptor: null },
                    { text: koi8Node.unescapedValue, descriptor: koi8Node }
                ], editedKoi8Xml, { wireStyleOverride: 'compact' });
                const parsedAfterKoi8 = JSON.parse(updatedRootAfterKoi8);
                const rawBytesAfterKoi8 = Uint8Array.from(atob(parsedAfterKoi8.koi8Field), c => c.charCodeAt(0));

                return {
                    discoveredCount: discovered.length,
                    isoCharset: isoNode ? isoNode.charset : null,
                    isoUnescapedHasNordic: isoNode ? isoNode.unescapedValue.includes('Åke Söderström') : false,
                    koi8Charset: koi8Node ? koi8Node.charset : null,
                    koi8UnescapedHasCyrillic: koi8Node ? koi8Node.unescapedValue.includes('Привет') : false,
                    cdataInnerCharset: cdataInnerNode ? cdataInnerNode.charset : null,
                    htmlHasIsoBadge: renderedHtml.includes('BASE64 · XML · ISO-8859-1'),
                    htmlHasKoi8Badge: renderedHtml.includes('BASE64 · XML · KOI8-R'),
                    htmlHasCdataIsoBadge: renderedHtml.includes('CDATA · XML · ISO-8859-1'),
                    isoHasSingleByteAring: rawBytesAfterIso.includes(0xC5) && !rawBytesAfterIso.includes(0x85),
                    isoHasSingleByteOuml: rawBytesAfterIso.includes(0xF6) && !rawBytesAfterIso.includes(0xB6),
                    koi8HasCyrillicBytes: rawBytesAfterKoi8.includes(0xF0) && rawBytesAfterKoi8.includes(0xD2) && rawBytesAfterKoi8.includes(0x21)
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("discoveredCount")).isEqualTo(3);
        assertThat(result.get("isoCharset")).isEqualTo("ISO-8859-1");
        assertThat(result.get("isoUnescapedHasNordic")).isEqualTo(true);
        assertThat(result.get("koi8Charset")).isEqualTo("KOI8-R");
        assertThat(result.get("koi8UnescapedHasCyrillic")).isEqualTo(true);
        assertThat(result.get("cdataInnerCharset")).isEqualTo("ISO-8859-1");
        assertThat(result.get("htmlHasIsoBadge")).isEqualTo(true);
        assertThat(result.get("htmlHasKoi8Badge")).isEqualTo(true);
        assertThat(result.get("htmlHasCdataIsoBadge")).isEqualTo(true);
        assertThat(result.get("isoHasSingleByteAring")).isEqualTo(true);
        assertThat(result.get("isoHasSingleByteOuml")).isEqualTo(true);
        assertThat(result.get("koi8HasCyrillicBytes")).isEqualTo(true);
    }

    @Test
    @DisplayName(
            "Character Encoding: Top-level viewer badges in Casual Tester and HTTP Tester reflect non-UTF-8 charsets")
    void testTopLevelViewerAndCasualCharsetBadgesInDom() {
        String script =
                """
            async () => {
                const { displayResponseResult, updateTesterRequestPill, switchTesterProtocol, setTesterBody } = await import('./modules/tester.js');
                const { demoBackend } = await import('./modules/demo-backend.js');

                // 1. Casual Request Pill with ISO-8859-1 header inside Unified Tester
                switchTesterProtocol('casual', false);
                const bufEl = document.getElementById('tester-casual-buffer');
                const hdrEl = document.getElementById('tester-headers');
                if (bufEl) bufEl.value = 'CSTRING';
                if (hdrEl) hdrEl.value = 'Content-Type: text/plain; charset=ISO-8859-1\\nX-Casual-Service: echoService';
                setTesterBody('Åke Söderström');
                updateTesterRequestPill();
                const casualReqPillText = document.getElementById('tester-request-top-pill')?.textContent || '';

                // 2. HTTP Tester Response Pill with KOI8-R header
                displayResponseResult(
                    200,
                    'OK',
                    12,
                    ['Content-Type: text/xml; charset=KOI8-R'],
                    '<?xml version="1.0" encoding="KOI8-R"?><msg>Привет</msg>'
                );
                const testerResPillText = document.getElementById('tester-top-pill')?.textContent || '';

                // 3. Demo Snapshot echoService Casual stub & Create Order nested polyglot stub exist
                const echoStub = demoBackend.stubs.find(s => s.id === '10000000-0000-4000-8000-000000000008');
                const orderStub = demoBackend.stubs.find(s => s.id === '10000000-0000-4000-8000-000000000002');

                return {
                    casualReqPillText,
                    testerResPillText,
                    hasEchoStub: Boolean(echoStub),
                    orderStubHasAuditReport: Boolean(orderStub && orderStub.response && orderStub.response.body.includes('auditReport'))
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat((String) result.get("casualReqPillText")).contains("CSTRING · TEXT · ISO-8859-1");
        assertThat((String) result.get("testerResPillText")).contains("XML · KOI8-R");
        assertThat(result.get("hasEchoStub")).isEqualTo(true);
        assertThat(result.get("orderStubHasAuditReport")).isEqualTo(true);
    }

    @Test
    @DisplayName(
            "Document Workspace: Viewer-first open, section editing, dirty confirm modal, validation/force-apply, and root save")
    void testDocumentWorkspaceSectionEditApplyDirtyPromptAndForceApply() {
        String script =
                """
            async () => {
                const {
                    openDocumentWorkspace,
                    closeDocumentWorkspace,
                    isWorkspaceOpen,
                    isWorkspaceDirty,
                    getWorkspaceRootText
                } = await import('./modules/document-workspace.js');

                let committedValue = null;
                try {
                    const initialJson = JSON.stringify({
                        name: 'Original Stub',
                        nestedXml: '<note><status>PENDING</status></note>'
                    }, null, 2);

                    openDocumentWorkspace({
                        title: 'Stub: Original Stub',
                        text: initialJson,
                        format: 'json',
                        editable: true,
                        saveLabel: '💾 Save Mapping',
                        onCommit: (saved) => {
                            committedValue = saved;
                        }
                    });

                    const openAtStart = isWorkspaceOpen();
                    const viewerVisibleInitially = !document.getElementById('workspace-viewer-pane').classList.contains('hidden');
                    const editorHiddenInitially = document.getElementById('workspace-editor-pane').classList.contains('hidden');
                    const cleanInitially = !isWorkspaceDirty();

                    // Enter root Section Editor via button
                    document.getElementById('btn-workspace-edit-root').click();
                    const editorVisibleAfterClick = !document.getElementById('workspace-editor-pane').classList.contains('hidden');

                    // Make an edit inside [data-testid="code-editor-input"]
                    const inputEl = document.querySelector('#workspace-code-editor-host [data-testid="code-editor-input"]');
                    const setEditorText = (val) => {
                        if ('value' in inputEl) inputEl.value = val;
                        else inputEl.textContent = val;
                        inputEl.dispatchEvent(new Event('input', { bubbles: true }));
                    };

                    setEditorText('{"broken": }');
                    const applyDisabledOnInvalid = document.getElementById('btn-workspace-apply-section').disabled;
                    const forceVisibleOnInvalid = !document.getElementById('btn-workspace-force-apply').classList.contains('hidden');
                    const dirtyAfterEdit = isWorkspaceDirty();

                    // Attempt close while dirty in SECTION_EDITOR -> triggers #modal-confirm
                    document.getElementById('btn-close-workspace').click();
                    const confirmVisible = !document.getElementById('modal-confirm').classList.contains('hidden');
                    const confirmTitle = document.getElementById('confirm-modal-title').textContent;
                    // Cancel confirm to stay in editor
                    document.getElementById('btn-confirm-cancel').click();
                    await new Promise(r => setTimeout(r, 20));
                    const stillOpenAfterCancel = isWorkspaceOpen();

                    // Force apply invalid payload -> returns to DOCUMENT_VIEWER with dirty badge
                    document.getElementById('btn-workspace-force-apply').click();
                    const returnedToViewerAfterForce = !document.getElementById('workspace-viewer-pane').classList.contains('hidden');
                    const dirtyBadgeVisible = !document.getElementById('workspace-dirty-badge').classList.contains('hidden');

                    // Re-edit root with valid JSON, apply section, and save
                    document.getElementById('btn-workspace-edit-root').click();
                    const inputEl2 = document.querySelector('#workspace-code-editor-host [data-testid="code-editor-input"]');
                    const nextJson = JSON.stringify({ name: 'Updated Stub', nestedXml: '<note><status>OK</status></note>' }, null, 2);
                    if ('value' in inputEl2) inputEl2.value = nextJson;
                    else inputEl2.textContent = nextJson;
                    inputEl2.dispatchEvent(new Event('input', { bubbles: true }));

                    document.getElementById('btn-workspace-apply-section').click();
                    const rootMatchesAfterApply = getWorkspaceRootText() === nextJson;

                    document.getElementById('btn-workspace-save').click();
                    await new Promise(r => setTimeout(r, 20));
                    const closedAfterSave = !isWorkspaceOpen();

                    return {
                        openAtStart,
                        viewerVisibleInitially,
                        editorHiddenInitially,
                        cleanInitially,
                        editorVisibleAfterClick,
                        applyDisabledOnInvalid,
                        forceVisibleOnInvalid,
                        dirtyAfterEdit,
                        confirmVisible,
                        confirmTitle,
                        stillOpenAfterCancel,
                        returnedToViewerAfterForce,
                        dirtyBadgeVisible,
                        rootMatchesAfterApply,
                        closedAfterSave,
                        committedMatches: committedValue === nextJson
                    };
                } finally {
                    closeDocumentWorkspace(true);
                }
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("openAtStart")).isEqualTo(true);
        assertThat(result.get("viewerVisibleInitially")).isEqualTo(true);
        assertThat(result.get("editorHiddenInitially")).isEqualTo(true);
        assertThat(result.get("cleanInitially")).isEqualTo(true);
        assertThat(result.get("editorVisibleAfterClick")).isEqualTo(true);
        assertThat(result.get("applyDisabledOnInvalid")).isEqualTo(true);
        assertThat(result.get("forceVisibleOnInvalid")).isEqualTo(true);
        assertThat(result.get("dirtyAfterEdit")).isEqualTo(true);
        assertThat(result.get("confirmVisible")).isEqualTo(true);
        assertThat((String) result.get("confirmTitle")).contains("Discard Section Changes");
        assertThat(result.get("stillOpenAfterCancel")).isEqualTo(true);
        assertThat(result.get("returnedToViewerAfterForce")).isEqualTo(true);
        assertThat(result.get("dirtyBadgeVisible")).isEqualTo(true);
        assertThat(result.get("rootMatchesAfterApply")).isEqualTo(true);
        assertThat(result.get("closedAfterSave")).isEqualTo(true);
        assertThat(result.get("committedMatches")).isEqualTo(true);
    }

    @Test
    @DisplayName(
            "Unified Code Editor: Default highlight-api with CSS.highlights + Tab/Enter indentation, legacy hot-swap, and 50KB degradation")
    void testUnifiedCodeEditorHighlightApiAndLegacyFallback() {
        String script =
                """
            async () => {
                const { mountCodeEditor, getEditorEngine, setEditorEngine, isHighlightApiSupported } = await import('./modules/code-editor.js');

                const origEngine = getEditorEngine();
                const container = document.createElement('div');
                document.body.appendChild(container);
                let editor = null;
                try {
                    setEditorEngine('highlight-api');
                    const sample = '{"testKey": "testVal", "num": 42}';
                    editor = mountCodeEditor(container, { value: sample, format: 'json' });

                    const activeBetaEngine = editor.getEngine();
                    const hasBetaSurface = container.querySelector('.code-editor-surface.engine-highlight-api') !== null;
                    const preEl = container.querySelector('pre.code-editor-pre[data-testid="code-editor-input"]');
                    const hasContentEditablePre = preEl !== null && (preEl.contentEditable === 'plaintext-only' || preEl.contentEditable === 'true');
                    const highlightKeyCount = isHighlightApiSupported() && CSS.highlights.has('wm-hl-key')
                        ? CSS.highlights.get('wm-hl-key').size
                        : 0;

                    // Test formatCode + getValue / setValue
                    const formatted = editor.formatCode();
                    const formattedContainsNewline = formatted.includes('\\n  "testKey"');

                    // Test Tab and Enter indentation in highlight-api <pre>
                    editor.setValue('{\\n}');
                    const textNode = preEl.firstChild;
                    const sel = window.getSelection();
                    const range = document.createRange();
                    range.setStart(textNode, 1);
                    range.collapse(true);
                    sel.removeAllRanges();
                    sel.addRange(range);
                    preEl.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true }));
                    const afterEnterValue = editor.getValue();
                    preEl.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }));
                    const afterTabValue = editor.getValue();

                    // Test 55 KB payload degradation in highlight-api (clears ranges cleanly)
                    const hugePayload = '{"large":"' + 'x'.repeat(55 * 1024) + '"}';
                    editor.setValue(hugePayload);
                    const hugeHighlightKeySize = isHighlightApiSupported() && CSS.highlights.has('wm-hl-key')
                        ? CSS.highlights.get('wm-hl-key').size
                        : 0;

                    // Hot-swap to legacy-duallayer preserving content, then restore manageable payload
                    editor.setValue(formatted);
                    const swappedEngine = editor.setEngine('legacy-duallayer');
                    const hasLegacyWrapper = container.querySelector('.dual-layer-editor-wrapper.mode-rich') !== null;
                    const legacyBackdropHasSpans = (container.querySelector('.dual-layer-code')?.innerHTML || '').includes('class="json-key"');
                    const legacyPreservedValue = editor.getValue() === formatted;

                    // Verify 55 KB degradation in legacy-duallayer switches to .mode-raw.mode-degraded
                    editor.setValue(hugePayload);
                    const legacyWrapperAfterHuge = container.querySelector('.dual-layer-editor-wrapper');
                    const legacyAutoDegraded = legacyWrapperAfterHuge.classList.contains('mode-raw')
                        && legacyWrapperAfterHuge.classList.contains('mode-degraded');

                    // Also check setEditorEngine persistence in memory
                    setEditorEngine('legacy-duallayer');
                    const defaultAfterSet = getEditorEngine();

                    return {
                        highlightApiSupported: isHighlightApiSupported(),
                        activeBetaEngine,
                        hasBetaSurface,
                        hasContentEditablePre,
                        highlightKeyCount,
                        formattedContainsNewline,
                        afterEnterIndented: afterEnterValue === '{\\n  \\n}',
                        afterTabIndented: afterTabValue === '{\\n    \\n}',
                        hugeHighlightKeySize,
                        swappedEngine,
                        hasLegacyWrapper,
                        legacyBackdropHasSpans,
                        legacyPreservedValue,
                        legacyAutoDegraded,
                        defaultAfterSet
                    };
                } finally {
                    if (editor) editor.destroy();
                    container.remove();
                    setEditorEngine(origEngine);
                }
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("highlightApiSupported")).isEqualTo(true);
        assertThat(result.get("activeBetaEngine")).isEqualTo("highlight-api");
        assertThat(result.get("hasBetaSurface")).isEqualTo(true);
        assertThat(result.get("hasContentEditablePre")).isEqualTo(true);
        assertThat(((Number) result.get("highlightKeyCount")).intValue()).isGreaterThanOrEqualTo(2);
        assertThat(result.get("formattedContainsNewline")).isEqualTo(true);
        assertThat(result.get("afterEnterIndented")).isEqualTo(true);
        assertThat(result.get("afterTabIndented")).isEqualTo(true);
        assertThat(result.get("hugeHighlightKeySize")).isEqualTo(0);
        assertThat(result.get("swappedEngine")).isEqualTo("legacy-duallayer");
        assertThat(result.get("hasLegacyWrapper")).isEqualTo(true);
        assertThat(result.get("legacyBackdropHasSpans")).isEqualTo(true);
        assertThat(result.get("legacyPreservedValue")).isEqualTo(true);
        assertThat(result.get("legacyAutoDegraded")).isEqualTo(true);
        assertThat(result.get("defaultAfterSet")).isEqualTo("legacy-duallayer");
    }

    @Test
    @DisplayName("journal: Protocol column, SUT operation verbs, clean targets, and protocol filtering")
    void testJournalProtocolColumnAndOperationSemantics() {
        String script =
                """
            async () => {
                const { state } = await import('./modules/state.js');
                const { detectRequestProtocol, renderJournal } = await import('./modules/journal.js');
                const { demoBackend } = await import('./modules/demo-backend.js');

                demoBackend.reset();
                try {
                    await demoBackend.handleRequest('/__admin/async/publish', {
                        method: 'POST',
                        body: JSON.stringify({ protocol: 'kafka', destination: 'orders.events', payload: '{"ok":true}' })
                    });
                    await demoBackend.handleRequest('/__admin/async/publish', {
                        method: 'POST',
                        body: JSON.stringify({ protocol: 'amqp', destination: 'orders.inbound', payload: '{"ok":true}' })
                    });
                    state.currentRequests = demoBackend.requests;
                    state.journalVerdictFilter = 'all';
                    state.journalProtocolFilter = 'all';

                    // 1. Verify detectRequestProtocol
                    const httpReq = state.currentRequests.find(r => r.request.url.startsWith('/api/'));
                    const casualReq = state.currentRequests.find(r => r.request.url.startsWith('/__casual/'));
                    const kafkaReq = state.currentRequests.find(r => r.request.url.startsWith('/__async/kafka/'));
                    const amqpReq = state.currentRequests.find(r => r.request.url.startsWith('/__async/amqp/'));

                    const httpProto = detectRequestProtocol(httpReq);
                    const casualProto = detectRequestProtocol(casualReq);
                    const kafkaProto = detectRequestProtocol(kafkaReq);
                    const amqpProto = detectRequestProtocol(amqpReq);

                    // 2. Render all in Journal
                    renderJournal();
                    const totalRows = document.querySelectorAll('#journal-list tr.journal-row').length;
                    const kafkaBadges = document.querySelectorAll('#journal-list .protocol-kafka').length;
                    const amqpBadges = document.querySelectorAll('#journal-list .protocol-amqp').length;
                    const casualBadges = document.querySelectorAll('#journal-list .protocol-casual').length;
                    const httpBadges = document.querySelectorAll('#journal-list .protocol-http').length;
                    const produceBadges = document.querySelectorAll('#journal-list .badge-PRODUCE').length;

                    // 3. Test protocol filtering - Kafka only
                    state.journalProtocolFilter = 'kafka';
                    renderJournal();
                    const kafkaFilteredRows = document.querySelectorAll('#journal-list tr.journal-row').length;

                    // 4. Test protocol filtering - Casual only
                    state.journalProtocolFilter = 'casual';
                    renderJournal();
                    const casualFilteredRows = document.querySelectorAll('#journal-list tr.journal-row').length;

                    // 5. Reset filter
                    state.journalProtocolFilter = 'all';
                    renderJournal();
                    const restoredRows = document.querySelectorAll('#journal-list tr.journal-row').length;

                    return {
                        httpProto,
                        casualProto,
                        kafkaProto,
                        amqpProto,
                        totalRows,
                        hasKafkaBadges: kafkaBadges >= 1,
                        hasAmqpBadges: amqpBadges >= 1,
                        hasCasualBadges: casualBadges >= 1,
                        hasHttpBadges: httpBadges >= 1,
                        hasProduceBadges: produceBadges >= 1,
                        kafkaFilteredRows,
                        casualFilteredRows,
                        restoredRows
                    };
                } finally {
                    state.journalProtocolFilter = 'all';
                    state.currentRequests = [];
                    demoBackend.reset();
                }
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("httpProto")).isEqualTo("http");
        assertThat(result.get("casualProto")).isEqualTo("casual");
        assertThat(result.get("kafkaProto")).isEqualTo("kafka");
        assertThat(result.get("amqpProto")).isEqualTo("amqp");
        assertThat(((Number) result.get("totalRows")).intValue()).isGreaterThanOrEqualTo(6);
        assertThat(result.get("hasKafkaBadges")).isEqualTo(true);
        assertThat(result.get("hasAmqpBadges")).isEqualTo(true);
        assertThat(result.get("hasCasualBadges")).isEqualTo(true);
        assertThat(result.get("hasHttpBadges")).isEqualTo(true);
        assertThat(result.get("hasProduceBadges")).isEqualTo(true);
        assertThat(((Number) result.get("kafkaFilteredRows")).intValue()).isGreaterThanOrEqualTo(1);
        assertThat(((Number) result.get("casualFilteredRows")).intValue()).isGreaterThanOrEqualTo(1);
        assertThat(result.get("restoredRows")).isEqualTo(result.get("totalRows"));
    }

    @Test
    @DisplayName(
            "charset-codec: UTF-8, ISO-8859-1, WINDOWS-1252, and KOI8-R byte/Base64 round-trips plus header/prolog detection")
    void testCharsetCodecRoundTripsAndDetection() {
        String script =
                """
            async () => {
                const {
                    normalizeCharsetName,
                    detectCharset,
                    encodeStringToBytes,
                    decodeBytesToString,
                    encodeToBase64,
                    decodeBase64WithCharset
                } = await import('./modules/charset-codec.js');

                const utfText = 'Åke € Привет 🚀';
                const utfDecodedObj = decodeBase64WithCharset(encodeToBase64(utfText, 'UTF-8'));
                const utfRoundTrip = utfDecodedObj?.text;

                const isoText = 'Åke Söderström';
                const isoBytes = encodeStringToBytes(isoText, 'ISO-8859-1');
                const isoDecoded = decodeBytesToString(isoBytes, 'ISO-8859-1');
                const isoRoundTrip = decodeBase64WithCharset(encodeToBase64(isoText, 'ISO-8859-1'))?.text;

                const winText = 'Price: 120 € “quoted”';
                const winBytes = encodeStringToBytes(winText, 'WINDOWS-1252');
                const winRoundTrip = decodeBase64WithCharset(encodeToBase64(winText, 'WINDOWS-1252'))?.text;

                const koiText = 'Привет мир';
                const koiBytes = encodeStringToBytes(koiText, 'KOI8-R');
                const koiByteRoundTrip = decodeBytesToString(koiBytes, 'KOI8-R');
                const koiXml = '<?xml version="1.0" encoding="KOI8-R"?><m>Привет мир</m>';
                const koiRoundTrip = decodeBase64WithCharset(encodeToBase64(koiXml, 'KOI8-R'))?.text;

                const headerKoiOverContentType = detectCharset('{}', {
                    'Content-Type': 'application/json; charset=iso-8859-1',
                    'X-Casual-Charset': 'koi8-r'
                });
                const xmlPrologOverHeader = detectCharset(
                    '<?xml version="1.0" encoding="cp1252"?><a/>',
                    { 'X-Casual-Charset': 'utf-8' }
                );

                return {
                    utfRoundTrip,
                    isoRoundTrip,
                    isoDecoded,
                    isoContainsSingleByteAring: isoBytes.includes(0xC5),
                    winRoundTrip,
                    winEuroByte: winBytes.includes(0x80),
                    koiByteRoundTrip,
                    koiRoundTrip,
                    koiFirstByte: koiBytes[0],
                    normLatin1: normalizeCharsetName('latin1'),
                    normWin: normalizeCharsetName('cp1252'),
                    headerKoiOverContentType,
                    xmlPrologOverHeader
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("utfRoundTrip")).isEqualTo("Åke € Привет 🚀");
        assertThat(result.get("isoRoundTrip")).isEqualTo("Åke Söderström");
        assertThat(result.get("isoDecoded")).isEqualTo("Åke Söderström");
        assertThat(result.get("isoContainsSingleByteAring")).isEqualTo(true);
        assertThat(result.get("winRoundTrip")).isEqualTo("Price: 120 € “quoted”");
        assertThat(result.get("winEuroByte")).isEqualTo(true);
        assertThat(result.get("koiByteRoundTrip")).isEqualTo("Привет мир");
        assertThat(result.get("koiRoundTrip"))
                .isEqualTo("<?xml version=\"1.0\" encoding=\"KOI8-R\"?><m>Привет мир</m>");
        assertThat(((Number) result.get("koiFirstByte")).intValue()).isEqualTo(0xF0);
        assertThat(result.get("normLatin1")).isEqualTo("ISO-8859-1");
        assertThat(result.get("normWin")).isEqualTo("WINDOWS-1252");
        assertThat(result.get("headerKoiOverContentType")).isEqualTo("KOI8-R");
        assertThat(result.get("xmlPrologOverHeader")).isEqualTo("WINDOWS-1252");
    }

    @Test
    @DisplayName(
            "pseudo-json: Nested {k=v}, semicolon maps, quoted strings with = and commas, invalid rejection, and formatPseudoJson")
    void testPseudoJsonParsingAndFormatting() {
        String script =
                """
            async () => {
                const { parsePseudoJson, formatPseudoJson } = await import('./modules/pseudo-json.js');

                const nested = parsePseudoJson('{user={id=42, roles=[ADMIN, USER], meta={active=true, score=9.5}}}');
                const semi = parsePseudoJson('{messages=null; deviations=[]; status=OK}');
                const quoted = parsePseudoJson('{expr="a=1, b=2", tag=\\'x=y,z\\', ok=true}');

                const unbalanced = parsePseudoJson('{a=1, b={c=2}');
                const trailingJunk = parsePseudoJson('{a=1} extra');
                const plainText = parsePseudoJson('hello world');

                const formatted = formatPseudoJson('{a=1, nested={msg="x=1, y=2", flag=true}}');

                return {
                    nestedId: nested?.user?.id,
                    nestedRole0: nested?.user?.roles?.[0],
                    nestedScore: nested?.user?.meta?.score,
                    semiMessagesNull: semi?.messages === null,
                    semiDeviationsEmpty: Array.isArray(semi?.deviations) && semi.deviations.length === 0,
                    quotedExpr: quoted?.expr,
                    quotedTag: quoted?.tag,
                    unbalancedIsNull: unbalanced === null,
                    trailingJunkIsNull: trailingJunk === null,
                    plainTextIsNull: plainText === null,
                    formattedPreservesKvSyntax: formatted.includes(' = ') && !formatted.includes('":'),
                    formattedReParses: parsePseudoJson(formatted)?.nested?.msg === 'x=1, y=2'
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(((Number) result.get("nestedId")).intValue()).isEqualTo(42);
        assertThat(result.get("nestedRole0")).isEqualTo("ADMIN");
        assertThat(((Number) result.get("nestedScore")).doubleValue()).isEqualTo(9.5);
        assertThat(result.get("semiMessagesNull")).isEqualTo(true);
        assertThat(result.get("semiDeviationsEmpty")).isEqualTo(true);
        assertThat(result.get("quotedExpr")).isEqualTo("a=1, b=2");
        assertThat(result.get("quotedTag")).isEqualTo("x=y,z");
        assertThat(result.get("unbalancedIsNull")).isEqualTo(true);
        assertThat(result.get("trailingJunkIsNull")).isEqualTo(true);
        assertThat(result.get("plainTextIsNull")).isEqualTo(true);
        assertThat(result.get("formattedPreservesKvSyntax")).isEqualTo(true);
        assertThat(result.get("formattedReParses")).isEqualTo(true);
    }

    @Test
    @DisplayName(
            "polyglot-ast regressions: duplicate siblings, root key collisions, literal token safety, and wire-style preservation")
    void testPolyglotAstRegressionsAndWireStyle() {
        String script =
                """
            async () => {
                const {
                    discoverEmbeddedPayloads,
                    spliceEmbeddedPayloadEdit,
                    applyNestedPathEdit,
                    buildPolyglotSegments
                } = await import('./modules/polyglot-ast.js');
                const { renderPolyglotTreeHtml } = await import('./modules/polyglot-viewer.js');

                // 1. Duplicate sibling strings: editing orderB mutates ONLY orderB
                const dupRoot = JSON.stringify({
                    orderA: JSON.stringify({ status: 'PAID' }),
                    orderB: JSON.stringify({ status: 'PAID' })
                }, null, 2);
                const dupNodes = discoverEmbeddedPayloads(dupRoot);
                const orderBNode = dupNodes.find(n => n.path === '$.orderB');
                const dupEdited = applyNestedPathEdit(dupRoot, [orderBNode], JSON.stringify({ status: 'REFUNDED' }));
                const dupParsed = JSON.parse(dupEdited);

                // 2. Root key collision: root has "status", envelope -> inner has "status"
                // Exercise external-reformat JSONPath fallback with explicit bogus span so fallback traversal runs
                const collisionParent = JSON.stringify({
                    status: 'ROOT_OK',
                    envelope: JSON.stringify({ status: 'INNER_OLD' })
                });
                const colNodes = discoverEmbeddedPayloads(collisionParent);
                const colNodeCopy = Object.assign({}, colNodes[0], { span: { start: -1, end: -1 } });
                const collisionAfterFallback = spliceEmbeddedPayloadEdit(
                    collisionParent,
                    { path: '$.envelope.status', parentPath: '$', format: 'text', encoding: 'JSON_STRING', wireStyle: 'compact', span: { start: -1, end: -1 } },
                    'INNER_NEW'
                );
                const colParsed1 = JSON.parse(collisionAfterFallback);

                // Also test two-level cascade when root also shares the leaf key name
                const twoLevelCollisionRoot = JSON.stringify({
                    payload: 'ROOT_PAYLOAD_UNCHANGED',
                    wrapper: JSON.stringify({
                        payload: JSON.stringify({ ok: false })
                    })
                }, null, 2);
                const wrapperNode = discoverEmbeddedPayloads(twoLevelCollisionRoot).find(n => n.path === '$.wrapper');
                const innerPayloadNode = wrapperNode.children.find(c => c.key === 'payload');
                const twoLevelEdited = applyNestedPathEdit(twoLevelCollisionRoot, [wrapperNode, innerPayloadNode], '{"ok":true}');
                const twoLevelParsed = JSON.parse(twoLevelEdited);

                // 3. Literal __LENS_TOKEN_0__ safety in buildPolyglotSegments + renderPolyglotTreeHtml
                const tokenTextRoot = JSON.stringify({
                    marker: '__LENS_TOKEN_0__',
                    nested: JSON.stringify({ a: 1, literal: '__LENS_TOKEN_0__' })
                }, null, 2);
                const segments = buildPolyglotSegments(tokenTextRoot);
                const renderedTokenHtml = renderPolyglotTreeHtml(tokenTextRoot, { mode: 'rich', editable: false });

                // 4. Wire-style preservation & override + KV preservation
                const compactNestedRoot = JSON.stringify({
                    compactJson: JSON.stringify({ k: 1 }),
                    multilineJson: JSON.stringify({ k: 1 }, null, 2),
                    compactXml: '<a b="1"><c>2</c></a>',
                    multilineXml: '<a>\\n  <c>2</c>\\n</a>',
                    kvMap: btoa('{alpha=1, beta=two}')
                }, null, 2);
                const wsNodes = discoverEmbeddedPayloads(compactNestedRoot);

                const editedCompactJson = JSON.parse(applyNestedPathEdit(
                    compactNestedRoot,
                    [wsNodes.find(n => n.key === 'compactJson')],
                    '{\\n  "k": 9\\n}'
                )).compactJson;

                const editedMultilineJson = JSON.parse(applyNestedPathEdit(
                    compactNestedRoot,
                    [wsNodes.find(n => n.key === 'multilineJson')],
                    '{\\n  "k": 9\\n}'
                )).multilineJson;

                const overriddenToCompact = JSON.parse(applyNestedPathEdit(
                    compactNestedRoot,
                    [wsNodes.find(n => n.key === 'multilineJson')],
                    '{\\n  "k": 9\\n}',
                    { wireStyleOverride: 'compact' }
                )).multilineJson;

                const editedCompactXml = JSON.parse(applyNestedPathEdit(
                    compactNestedRoot,
                    [wsNodes.find(n => n.key === 'compactXml')],
                    '<a b="1">\\n  <c>9</c>\\n</a>'
                )).compactXml;

                const editedMultilineXml = JSON.parse(applyNestedPathEdit(
                    compactNestedRoot,
                    [wsNodes.find(n => n.key === 'multilineXml')],
                    '<a>\\n  <c>9</c>\\n</a>'
                )).multilineXml;

                const editedKvB64 = JSON.parse(applyNestedPathEdit(
                    compactNestedRoot,
                    [wsNodes.find(n => n.key === 'kvMap')],
                    '{alpha=9, beta=three}'
                )).kvMap;

                return {
                    dupOrderA: JSON.parse(dupParsed.orderA).status,
                    dupOrderB: JSON.parse(dupParsed.orderB).status,
                    colRootStatusPreserved: colParsed1.status,
                    colEnvelopeStatusUpdated: colParsed1.envelope.status,
                    twoLevelRootPayloadPreserved: twoLevelParsed.payload,
                    twoLevelInnerOk: JSON.parse(JSON.parse(twoLevelParsed.wrapper).payload).ok,
                    segmentPreservesLiteral: segments.segments.some(s => s.type === 'code' && s.text.includes('__LENS_TOKEN_0__')),
                    htmlPreservesLiteral: renderedTokenHtml.includes('__LENS_TOKEN_0__'),
                    editedCompactJson,
                    editedMultilineJsonHasNewline: editedMultilineJson.includes('\\n'),
                    overriddenToCompact,
                    editedCompactXml,
                    editedMultilineXmlHasNewline: editedMultilineXml.includes('\\n'),
                    editedKvDecoded: atob(editedKvB64)
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("dupOrderA")).isEqualTo("PAID");
        assertThat(result.get("dupOrderB")).isEqualTo("REFUNDED");
        assertThat(result.get("colRootStatusPreserved")).isEqualTo("ROOT_OK");
        assertThat(result.get("colEnvelopeStatusUpdated")).isEqualTo("INNER_NEW");
        assertThat(result.get("twoLevelRootPayloadPreserved")).isEqualTo("ROOT_PAYLOAD_UNCHANGED");
        assertThat(result.get("twoLevelInnerOk")).isEqualTo(true);
        assertThat(result.get("segmentPreservesLiteral")).isEqualTo(true);
        assertThat(result.get("htmlPreservesLiteral")).isEqualTo(true);
        assertThat(result.get("editedCompactJson")).isEqualTo("{\"k\":9}");
        assertThat(result.get("editedMultilineJsonHasNewline")).isEqualTo(true);
        assertThat(result.get("overriddenToCompact")).isEqualTo("{\"k\":9}");
        assertThat(result.get("editedCompactXml")).isEqualTo("<a b=\"1\"><c>9</c></a>");
        assertThat(result.get("editedMultilineXmlHasNewline")).isEqualTo(true);
        assertThat(result.get("editedKvDecoded")).isEqualTo("{alpha=9, beta=three}");
    }

    @Test
    @DisplayName("bulk-actions: getVisibleSelectedStubIds excludes selected stubs hidden by active filters")
    void testBulkActionsScopesToVisibleFilteredStubs() {
        String script =
                """
            async () => {
                const { state } = await import('./modules/state.js');
                const { stubCatalog } = await import('./modules/stub-catalog.js');
                const { getVisibleSelectedStubIds } = await import('./modules/bulk-actions.js');

                const prevMappings = stubCatalog._mappings;
                const prevSelected = new Set(state.selectedStubIds);
                const prevStatus = state.statusFilter;
                const prevProject = state.selectedProject;
                const searchEl = document.getElementById('search-box');
                const prevSearch = searchEl ? searchEl.value : '';

                try {
                    stubCatalog.setMappings([
                        { id: 's-1', name: 'Orders Alpha', priority: 5, request: { method: 'GET', url: '/orders/1' }, response: { status: 200 }, metadata: { project: 'orders' } },
                        { id: 's-2', name: 'Billing Beta', priority: 5, request: { method: 'GET', url: '/billing/1' }, response: { status: 200 }, metadata: { project: 'billing' } },
                        { id: 's-3', name: 'Orders Parked', priority: 100, request: { method: 'GET', url: '/orders/2' }, response: { status: 200 }, metadata: { project: 'orders' } }
                    ]);
                    state.selectedStubIds = new Set(['s-1', 's-2', 's-3']);

                    state.statusFilter = 'all';
                    state.selectedProject = null;
                    if (searchEl) searchEl.value = '';
                    const allVisible = getVisibleSelectedStubIds();

                    if (searchEl) searchEl.value = 'Orders';
                    const searchScoped = getVisibleSelectedStubIds();

                    if (searchEl) searchEl.value = 'orders';
                    state.statusFilter = 'active';
                    const projectAndStatusScoped = getVisibleSelectedStubIds();

                    return {
                        allVisible,
                        searchScoped,
                        projectAndStatusScoped
                    };
                } finally {
                    stubCatalog.setMappings(prevMappings);
                    state.selectedStubIds = prevSelected;
                    state.statusFilter = prevStatus;
                    state.selectedProject = prevProject;
                    if (searchEl) searchEl.value = prevSearch;
                }
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("allVisible")).isEqualTo(java.util.List.of("s-1", "s-2", "s-3"));
        assertThat(result.get("searchScoped")).isEqualTo(java.util.List.of("s-1", "s-3"));
        assertThat(result.get("projectAndStatusScoped")).isEqualTo(java.util.List.of("s-1"));
    }

    @Test
    @DisplayName(
            "api: 404 or network error never silently enables Demo Mode, while enableDemoMode loads DEMO_SNAPSHOT on demand")
    void testApiDoesNotSilentlyFallbackOnFailureAndLoadsDemoOnDemand() {
        String script =
                """
            async () => {
                const { apiGet, isDemoModeActive, enableDemoMode, disableDemoMode } = await import('./modules/api.js');

                const origFetch = window.fetch;
                disableDemoMode();
                try {
                    // 1. HTTP 404 on /__admin/mappings -> rejects, stays live
                    window.fetch = async () => new Response('Not Found', { status: 404 });
                    let threwOn404 = false;
                    try {
                        await apiGet('/__admin/mappings');
                    } catch (_) {
                        threwOn404 = true;
                    }
                    const demoAfter404 = isDemoModeActive();

                    // 2. Network error on /__admin/mappings -> rejects, stays live
                    window.fetch = async () => { throw new TypeError('Failed to fetch'); };
                    let threwOnNetwork = false;
                    try {
                        await apiGet('/__admin/mappings');
                    } catch (_) {
                        threwOnNetwork = true;
                    }
                    const demoAfterNetwork = isDemoModeActive();

                    // 3. Explicit enableDemoMode() loads demo-backend backed by DEMO_SNAPSHOT
                    const backend = await enableDemoMode();
                    const demoActiveAfterEnable = isDemoModeActive();
                    const mappingsResp = await apiGet('/__admin/mappings');

                    disableDemoMode();
                    const demoActiveAfterDisable = isDemoModeActive();

                    return {
                        threwOn404,
                        demoAfter404,
                        threwOnNetwork,
                        demoAfterNetwork,
                        demoActiveAfterEnable,
                        backendLoaded: Boolean(backend),
                        demoMappingsCount: Array.isArray(mappingsResp?.mappings) ? mappingsResp.mappings.length : 0,
                        demoActiveAfterDisable
                    };
                } finally {
                    window.fetch = origFetch;
                    disableDemoMode();
                }
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(result.get("threwOn404")).isEqualTo(true);
        assertThat(result.get("demoAfter404")).isEqualTo(false);
        assertThat(result.get("threwOnNetwork")).isEqualTo(true);
        assertThat(result.get("demoAfterNetwork")).isEqualTo(false);
        assertThat(result.get("demoActiveAfterEnable")).isEqualTo(true);
        assertThat(result.get("backendLoaded")).isEqualTo(true);
        assertThat(((Number) result.get("demoMappingsCount")).intValue()).isGreaterThanOrEqualTo(13);
        assertThat(result.get("demoActiveAfterDisable")).isEqualTo(false);
    }

    @Test
    @DisplayName("Queues: filterDestinations filters correctly by protocol and free-text")
    void testQueuesFilterDestinations() {
        String script =
                """
            async () => {
                const { filterDestinations } = await import('./modules/queues.js');

                const sampleDestinations = [
                    { id: '1', name: 'orders.events', protocol: 'kafka', brokerId: 'cluster-a' },
                    { id: '2', name: 'payments.requests', protocol: 'amqp', brokerId: 'broker-b' },
                    { id: '3', name: 'billing.forward', protocol: 'casual', brokerId: 'casual-gw' },
                    { id: '4', name: 'internal.audit', protocol: 'in-memory', brokerId: 'local' }
                ];

                return {
                    allCount: filterDestinations(sampleDestinations, { protocolFilter: 'all', searchQuery: '' }).length,
                    kafkaCount: filterDestinations(sampleDestinations, { protocolFilter: 'kafka' }).length,
                    amqpCount: filterDestinations(sampleDestinations, { protocolFilter: 'amqp' }).length,
                    casualCount: filterDestinations(sampleDestinations, { protocolFilter: 'casual' }).length,
                    inMemoryCount: filterDestinations(sampleDestinations, { protocolFilter: 'in-memory' }).length,
                    searchPayment: filterDestinations(sampleDestinations, { searchQuery: 'payment' }).length,
                    searchClusterA: filterDestinations(sampleDestinations, { searchQuery: 'cluster-a' }).length,
                    combinedFilterMatch: filterDestinations(sampleDestinations, { protocolFilter: 'kafka', searchQuery: 'orders' }).length,
                    combinedFilterMiss: filterDestinations(sampleDestinations, { protocolFilter: 'kafka', searchQuery: 'payments' }).length
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(((Number) result.get("allCount")).intValue()).isEqualTo(4);
        assertThat(((Number) result.get("kafkaCount")).intValue()).isEqualTo(1);
        assertThat(((Number) result.get("amqpCount")).intValue()).isEqualTo(1);
        assertThat(((Number) result.get("casualCount")).intValue()).isEqualTo(1);
        assertThat(((Number) result.get("inMemoryCount")).intValue()).isEqualTo(1);
        assertThat(((Number) result.get("searchPayment")).intValue()).isEqualTo(1);
        assertThat(((Number) result.get("searchClusterA")).intValue()).isEqualTo(1);
        assertThat(((Number) result.get("combinedFilterMatch")).intValue()).isEqualTo(1);
        assertThat(((Number) result.get("combinedFilterMiss")).intValue()).isEqualTo(0);
    }

    @Test
    @DisplayName("Queues: filterMessages evaluates boolean queries across keys, headers, and body payload")
    void testQueuesFilterMessages() {
        String script =
                """
            async () => {
                const { filterMessages } = await import('./modules/queues.js');

                const sampleMessages = [
                    {
                        id: 'msg-1',
                        key: 'ORD-8821',
                        correlationId: 'corr-ord-8821',
                        headers: { 'x-tenant-id': 'emea-retail' },
                        payload: '{"orderId":"ORD-8821","customer":"Acme Corp","total":1250}'
                    },
                    {
                        id: 'msg-2',
                        key: 'ORD-8822',
                        correlationId: 'corr-ord-8822',
                        headers: { 'x-tenant-id': 'apac-shipping' },
                        payload: '{"orderId":"ORD-8822","customer":"Global Logistics","total":450}'
                    },
                    {
                        id: 'msg-3',
                        key: 'PAY-9921',
                        correlationId: 'corr-pay-9921',
                        headers: { 'content-type': 'application/json' },
                        payload: '{"paymentId":"PAY-9921","method":"CARD"}'
                    }
                ];

                return {
                    emptyQuery: filterMessages(sampleMessages, "").length,
                    keySearch: filterMessages(sampleMessages, "ORD-8821").length,
                    corrSearch: filterMessages(sampleMessages, "corr-pay-9921").length,
                    headerSearch: filterMessages(sampleMessages, "apac-shipping").length,
                    bodySearch: filterMessages(sampleMessages, "Global Logistics").length,
                    andQueryMatch: filterMessages(sampleMessages, "Acme AND 1250").length,
                    andQueryMiss: filterMessages(sampleMessages, "Acme AND apac").length,
                    orQueryMatch: filterMessages(sampleMessages, "Acme OR CARD").length
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(((Number) result.get("emptyQuery")).intValue()).isEqualTo(3);
        assertThat(((Number) result.get("keySearch")).intValue()).isEqualTo(1);
        assertThat(((Number) result.get("corrSearch")).intValue()).isEqualTo(1);
        assertThat(((Number) result.get("headerSearch")).intValue()).isEqualTo(1);
        assertThat(((Number) result.get("bodySearch")).intValue()).isEqualTo(1);
        assertThat(((Number) result.get("andQueryMatch")).intValue()).isEqualTo(1);
        assertThat(((Number) result.get("andQueryMiss")).intValue()).isEqualTo(0);
        assertThat(((Number) result.get("orQueryMatch")).intValue()).isEqualTo(2);
    }

    @Test
    @DisplayName(
            "request-journal: Encapsulates verdicts, protocol filtering, pagination window, and state accessor sync")
    void testRequestJournalStore() {
        String script =
                """
            async () => {
                const { RequestJournal, requestJournal } = await import('./modules/request-journal.js');
                const { state } = await import('./modules/state.js');
                const now = Date.now();
                const events = [
                    {
                        id: 'j-1',
                        wasMatched: true,
                        request: { method: 'GET', url: '/api/orders', headers: {}, loggedDate: now - 60_000 },
                        response: { status: 200, headers: {} }
                    },
                    {
                        id: 'j-2',
                        wasMatched: true,
                        request: { method: 'POST', url: '/__async/kafka/orders.events', headers: {}, loggedDate: now - 10_000 },
                        responseDefinition: { status: 200, proxyBaseUrl: 'kafka://broker:9092' }
                    },
                    {
                        id: 'j-3',
                        wasMatched: false,
                        request: { method: 'DELETE', url: '/api/unknown', headers: {}, loggedDate: now },
                        response: { status: 404, headers: {} }
                    }
                ];

                const localJournal = new RequestJournal();
                localJournal.setRequests(events, { total: 3, filtered: 3 }, true);

                const byId = localJournal.findById('j-2');
                const kafkaMatches = localJournal.query({ protocolFilter: 'kafka' }).map(r => r.id);
                const unmatched = localJournal.query({ unmatchedOnly: true }).map(r => r.id);
                const proxiedFromAll = (() => {
                    localJournal.query({});
                    return localJournal.getFilteredProxied().map(r => r.id);
                })();
                const timeBounded = localJournal.query({ since: new Date(now - 20_000).toISOString() }).map(r => r.id);

                localJournal.query({});
                localJournal.resetVisibleWindow(2);
                const slice1 = localJournal.getVisibleSlice().map(r => r.id);
                const batch2 = localJournal.nextVisibleBatch(2).map(r => r.id);
                const stats = localJournal.getVisibleStats();

                state.currentRequests = events;
                const syncedCount = requestJournal.getTotalCount();
                const foundViaSingleton = requestJournal.findById('j-3')?.id || null;
                state.currentRequests = [];

                return {
                    totalCount: localJournal.getTotalCount(),
                    serverFiltered: localJournal.isServerFiltered(),
                    metaTotal: localJournal.getMeta()?.total ?? null,
                    byIdUrl: byId?.request?.url ?? null,
                    kafkaMatches,
                    unmatched,
                    proxiedFromAll,
                    timeBounded,
                    slice1,
                    batch2,
                    stats,
                    syncedCount,
                    foundViaSingleton
                };
            }
        """;

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> result = (java.util.Map<String, Object>) evalJs(script);

        assertThat(((Number) result.get("totalCount")).intValue()).isEqualTo(3);
        assertThat(result.get("serverFiltered")).isEqualTo(true);
        assertThat(((Number) result.get("metaTotal")).intValue()).isEqualTo(3);
        assertThat(result.get("byIdUrl")).isEqualTo("/__async/kafka/orders.events");
        assertThat(result.get("kafkaMatches")).isEqualTo(java.util.List.of("j-2"));
        assertThat(result.get("unmatched")).isEqualTo(java.util.List.of("j-3"));
        assertThat(result.get("proxiedFromAll")).isEqualTo(java.util.List.of("j-2"));
        assertThat(result.get("timeBounded")).isEqualTo(java.util.List.of("j-2", "j-3"));
        assertThat(result.get("slice1")).isEqualTo(java.util.List.of("j-1", "j-2"));
        assertThat(result.get("batch2")).isEqualTo(java.util.List.of("j-3"));
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> statsMap = (java.util.Map<String, Object>) result.get("stats");
        assertThat(((Number) statsMap.get("shown")).intValue()).isEqualTo(3);
        assertThat(((Number) statsMap.get("filtered")).intValue()).isEqualTo(3);
        assertThat(((Number) statsMap.get("total")).intValue()).isEqualTo(3);
        assertThat(((Number) result.get("syncedCount")).intValue()).isEqualTo(3);
        assertThat(result.get("foundViaSingleton")).isEqualTo("j-3");
    }
}
